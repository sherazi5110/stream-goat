package io.github.sherazi5110.cloudstream.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.Engine
import io.github.sherazi5110.cloudstream.Library
import io.github.sherazi5110.cloudstream.LinkEvent
import io.github.sherazi5110.cloudstream.Playable
import io.github.sherazi5110.cloudstream.Settings
import io.github.sherazi5110.cloudstream.SkipTimes
import io.github.sherazi5110.cloudstream.StreamProxy
import io.github.sherazi5110.cloudstream.WatchEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/** The one libVLC instance (it is heavy to create). A new subtitle style needs a new one ([restart]). */
object Vlc {
    @Volatile private var instance: LibVLC? = null
    /** The subtitle look [instance] was made with. */
    @Volatile var look: SubtitleStyle.Look? = null
        private set

    val lib: LibVLC
        get() = instance ?: synchronized(this) {
            instance ?: SubtitleStyle.current().let { l ->
                look = l
                LibVLC(App.context, arrayListOf(
                    "--http-reconnect",
                    // 1.5 s of buffer before playing (was 3 s): starts as quickly as the official app's player.
                    "--network-caching=1500",
                    "--audio-time-stretch", // keeps the pitch at other speeds
                    "--subsdec-encoding=UTF-8",
                ).apply { addAll(SubtitleStyle.vlcOptions(l)) })
            }.also { instance = it }
        }

    /** Drops the instance so the next player gets the current subtitle style (released after the players queued before). */
    fun restart() {
        val old = synchronized(this) { instance.also { instance = null } } ?: return
        VlcThread.run { old.release() }
    }
}

/** Zoom modes, the Linux player's names. */
enum class Zoom(val label: String, val scale: MediaPlayer.ScaleType) {
    FIT("Fit", MediaPlayer.ScaleType.SURFACE_BEST_FIT),
    FILL("Fill", MediaPlayer.ScaleType.SURFACE_FIT_SCREEN),
    STRETCH("Stretch", MediaPlayer.ScaleType.SURFACE_FILL),
    ORIGINAL("Original", MediaPlayer.ScaleType.SURFACE_ORIGINAL),
    WIDE("16:9", MediaPlayer.ScaleType.SURFACE_16_9),
    TV("4:3", MediaPlayer.ScaleType.SURFACE_4_3),
}

/**
 * One playback: finds sources (streamed in as the extension reports them), starts the best one,
 * falls back to the next source when one fails, and records progress for Continue Watching.
 * State is Compose state, so the screen simply reads it.
 */
class PlayerController(
    val api: MainAPI,
    val response: LoadResponse,
    val playable: Playable,
    chooseSource: Boolean,
    /** Live TV channel: no progress or history, no seeking. */
    live: Boolean = false,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** True while libVLC opens a source or stops the previous one: calls that wait for it are skipped or queued. */
    @Volatile private var opening = false
    private val main = Handler(Looper.getMainLooper())
    /** Replaced by a new one when the subtitle style changes ([restyle]). */
    var player = MediaPlayer(Vlc.lib)
        private set

    val links = mutableStateListOf<ExtractorLink>()
    val subtitles = mutableStateListOf<SubtitleFile>()
    var searching by mutableStateOf(true)
    var searchError by mutableStateOf<String?>(null)
    var current by mutableStateOf<ExtractorLink?>(null)
    var started by mutableStateOf(false)
    var playing by mutableStateOf(false)
    /** The playing video's real size, e.g. "3840×2160" (null until it's known). */
    var measured by mutableStateOf<String?>(null)
    /** Set after the hardware decoder stalled on this title: decode in software from then on. */
    private var softwareDecode = false
    var buffering by mutableStateOf(true)
    var time by mutableLongStateOf(0L)
    var length by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
    /** Volume in percent: above 100 boosts quiet streams (libVLC amplifies up to 200). */
    var boost by mutableIntStateOf(Settings.volumeBoost)
        private set
    var zoom by mutableStateOf(runCatching { Zoom.valueOf(Settings.playerZoom) }.getOrDefault(Zoom.FIT))
    var selectedSubtitle by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    /** Set when a stream that answers fine still doesn't play: what to send the developer (a problem in the app). */
    var appIssue by mutableStateOf<String?>(null)
    var ended by mutableStateOf(false)

    /** Auto-play waits for this; "Choose source" turns it off. */
    var autoPlay = Settings.autoPlay && !chooseSource
        private set
    private val tried = mutableSetOf<String>()
    private var startAt: Long = 0
    /** The last position that played, kept when a source fails or is switched. */
    private var lastGoodTime: Long = 0
    private var grace: Job? = null
    private var watchdog: Job? = null
    private var saver: Job? = null
    val isLive = live || response is LiveStreamLoadResponse
    /** A Live TV channel's key in its list (for "Hide not working"). */
    private val channelKey: String? = if (api is io.github.sherazi5110.cloudstream.LiveApi)
        runCatching { org.json.JSONObject(response.url).optString("key").ifBlank { null } }.getOrNull() else null
    private var languagesApplied = false
    private var playCalledAt = 0L

    /** Intro/recap/credits times (anime, from AniSkip) and the one the playhead is in, if any. */
    var segments by mutableStateOf<List<SkipTimes.Segment>>(emptyList())
    var activeSegment by mutableStateOf<SkipTimes.Segment?>(null)
    private var segmentsRequested = false
    private val autoSkipped = mutableSetOf<SkipTimes.Segment>()

    val playables = Engine.playables(response)
    val next: Playable? = playables.indexOfFirst { it.data == playable.data && it.dub == playable.dub }
        .takeIf { it >= 0 }?.let { playables.getOrNull(it + 1) }

    init {
        Library.progress(api.name, playable.data)?.let { p ->
            if (p.duration > 0 && p.position / p.duration in 0.02..0.92) startAt = (p.position * 1000).toLong()
        }
        listen()
        scope.launch { findSources() }
        saver = scope.launch {
            while (true) { delay(10_000); if (playing) { save(); trakr?.tick(percent()) } }
        }
        // Some phones' hardware video decoders stall ("buffer deadlock"): the picture freezes while the player still
        // says it's playing. If the clock stops for 8 s, reload the same source at the same place, decoded in software.
        scope.launch {
            var last = -1L; var still = 0
            while (true) {
                delay(2_000)
                if (playing && !softwareDecode && !buffering && time > 0 && time == last) still++ else still = 0
                last = time
                if (still >= 4) {
                    still = 0; softwareDecode = true
                    App.toast("The video stalled: switching to software decoding")
                    current?.let { play(it, lastVariantUrl) }
                }
            }
        }
    }

    private fun listen() {
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> {
                    // Next time (Continue Watching, Resume) this title starts with the same source.
                    current?.takeIf { api !is io.github.sherazi5110.cloudstream.LiveApi }?.let { io.github.sherazi5110.cloudstream.LastSource.remember(api.name, response.url, it) }
                    playing = true; buffering = false; started = true; error = null; watchdog?.cancel()
                    // The real picture size (extensions sometimes label a 4K file as 1080p): shown in the player and
                    // remembered for the source list.
                    // Read on the player's own thread: asking libVLC from the main thread while it's busy froze the
                    // app ("isn't responding").
                    main.postDelayed({ VlcThread.run {
                        runCatching {
                            val t = player.currentVideoTrack ?: return@runCatching
                            measured = if (t.width > 0 && t.height > 0) "${t.width}×${t.height}" else null
                            // With several qualities, Auto starts low: what plays now isn't what the source offers.
                            // The best quality in its playlist is (and is what the source list shows).
                            val best = variants.mapNotNull { it.height }.maxOrNull()
                            current?.let { l ->
                                if (best != null) io.github.sherazi5110.cloudstream.MeasuredQuality.remember(l.url, best * 16 / 9, best)
                                else io.github.sherazi5110.cloudstream.MeasuredQuality.remember(l.url, t.width, t.height)
                            }
                        }
                    } }, 1500)
                    channelKey?.let { io.github.sherazi5110.cloudstream.LiveTv.markWorking(it) }
                    trakr?.playing(percent())
                    if (!languagesApplied) { languagesApplied = true; main.postDelayed({ applyLanguages() }, 1500) }
                    applyDelays()
                    restoreTracks?.let { (a, sp) ->
                        restoreTracks = null
                        main.postDelayed({ if (a >= 0) setAudio(a); sp?.let { setSpu(it) } }, 1200)
                    }
                }
                MediaPlayer.Event.Paused -> { playing = false; trakr?.paused(percent()) }
                MediaPlayer.Event.Buffering -> {
                    val now = e.buffering < 100f
                    // A pause to load in the middle of playing (not the start, not right after a jump).
                    if (now && !buffering && started && android.os.SystemClock.elapsedRealtime() - lastSeekAt > 4000) stalled()
                    buffering = now
                }
                MediaPlayer.Event.TimeChanged -> { time = e.timeChanged; if (e.timeChanged > 0) lastGoodTime = e.timeChanged; if (buffering && playing) buffering = false; updateSkip(e.timeChanged) }
                MediaPlayer.Event.LengthChanged -> { length = e.lengthChanged; loadSegments() }
                MediaPlayer.Event.EndReached -> main.post { onEndReached() }
                MediaPlayer.Event.EncounteredError -> main.post { failover() }
            }
        }
    }

    /** Subtitle and audio sync in milliseconds (positive = later). libVLC resets them with every source. */
    var subtitleDelay by mutableLongStateOf(0L)
        private set
    var audioDelay by mutableLongStateOf(0L)
        private set
    fun syncSubtitles(ms: Long) { subtitleDelay = ms.coerceIn(-600_000, 600_000); applyDelays() }
    fun syncAudio(ms: Long) { audioDelay = ms.coerceIn(-600_000, 600_000); applyDelays() }
    private fun applyDelays() {
        val sub = subtitleDelay * 1000; val audio = audioDelay * 1000
        VlcThread.run { if (player.spuDelay != sub) player.spuDelay = sub; if (player.audioDelay != audio) player.audioDelay = audio }
    }

    /** Audio and subtitle track to pick again after [restyle] (subtitle null = a file, re-added by play). */
    private var restoreTracks: Pair<Int, Int?>? = null

    /**
     * Applies a new subtitle look: libVLC reads it only when it starts, so a new libVLC and player take over
     * and the source opens again where it was (like VLC for Android does after changing subtitle settings).
     * True when the player was replaced (a chosen subtitle file is then shown again by play).
     */
    fun restyle(): Boolean {
        if (released || Vlc.look == SubtitleStyle.current()) return false
        val old = player
        old.setEventListener(null)
        val l = layout
        detachAll()
        val keep = if (started) audioTrack() to (if (selectedSubtitle == null) spuTrack() else null) else null
        VlcThread.run { old.stop(); old.release() }
        Vlc.restart()
        player = MediaPlayer(Vlc.lib)
        listen()
        l?.let { attach(it) }
        val link = current ?: return true
        // Ended, or a torrent still joining (it plays on the new player when ready): nothing to reopen.
        if (ended || lastVariantUrl == null && io.github.sherazi5110.cloudstream.Torrents.isTorrent(link)) return true
        restoreTracks = keep
        play(link, lastVariantUrl)
        return true
    }

    /** The cleaned-up subtitles settings changed: show the chosen subtitle file again with them. */
    fun reloadSubtitle() {
        if (released) return
        val sel = selectedSubtitle ?: return
        subtitles.firstOrNull { it.url == sel }?.let { addSubtitle(it); return }
        addedFiles.firstOrNull { it.first.absolutePath == sel }?.let { selectFile(it.first) }
    }

    /** These sources came from the 20-minute cache (or the search started on the title page). */
    private var fromCache = false
    private var refreshed = false

    private suspend fun findSources() {
        fromCache = Engine.cached(api, playable.data)
        Engine.links(api, playable.data).collect { event ->
            when (event) {
                is LinkEvent.Link -> {
                    val link = event.link
                    // Torrents play only with torrent streaming on (Settings → Player); otherwise they're counted, to say so.
                    if (io.github.sherazi5110.cloudstream.Torrents.isTorrent(link) && !io.github.sherazi5110.cloudstream.Torrents.enabled) {
                        skippedTorrents++
                        return@collect
                    }
                    if (links.none { it.url == link.url }) links.add(link)
                    // The source that last played this title starts as soon as it's found (Continue Watching, Resume).
                    if (autoPlay && current == null && io.github.sherazi5110.cloudstream.LastSource.find(api.name, response.url, listOf(link)) != null) {
                        App.toast("Continuing with ${link.name}")
                        play(link)
                    }
                    onNewLink()
                }
                // Added to the player when picked in the Subtitles menu.
                is LinkEvent.Subtitle -> if (subtitles.none { it.url == event.subtitle.url }) subtitles.add(event.subtitle)
                is LinkEvent.Done -> {
                    searching = false
                    event.error?.let { searchError = it.message ?: it.toString() }
                    if (autoPlay && current == null) ranked().firstOrNull()?.let { play(it) }
                    if (links.isEmpty()) searchError = if (skippedTorrents > 0)
                        "Only torrent sources were found ($skippedTorrents). Turn on Settings → Player → Torrent streaming, or set the add-on " +
                            "up with a debrid service (Torrentio: torrentio.strem.fun/configure)."
                    else searchError ?: "No sources found"
                }
            }
        }
    }

    /** Best first: closest to the preferred quality without going over it; torrents after direct links. */
    fun ranked(): List<ExtractorLink> = Engine.rank(links.toList()).sortedBy { io.github.sherazi5110.cloudstream.Torrents.isTorrent(it) }

    /** Torrent sources left out because torrent streaming is off. */
    private var skippedTorrents = 0
    /** While a torrent connects and buffers: peers, speed, how much of the start is there. */
    var torrentStatus by mutableStateOf<io.github.sherazi5110.cloudstream.Torrents.Status?>(null)
        private set
    private var torrentJob: Job? = null
    private var openTorrent: String? = null

    private fun closeTorrent() {
        torrentJob?.cancel()
        torrentStatus = null
        openTorrent?.let { uri -> openTorrent = null; Thread { io.github.sherazi5110.cloudstream.Torrents.close(uri) }.start() }
    }

    /**
     * Like the official app: while the extension is still finding links nothing starts, so the best of all of
     * them plays (starting with an early, weaker link made extensions that ask many sites at once, such as
     * Eclipsia, fail over from link to link). "Play now (N)" on the loading screen starts at once, like the
     * official "Skip loading (N)"; the search itself ends after Settings → Player → Wait for sources.
     */
    private fun onNewLink() = Unit

    fun stopAutoPlay() { autoPlay = false; grace?.cancel() }

    /** Starts [link], keeping the current position when switching sources mid-playback. */
    /** The qualities inside the current HLS source (empty when it has one), and the one pinned (null = automatic). */
    var variants by mutableStateOf<List<HlsVariants.Variant>>(emptyList())
        private set
    var variant by mutableStateOf<HlsVariants.Variant?>(null)
        private set
    private var master: HlsVariants.Master? = null
    private var mastersJob: Job? = null

    /** Pins a quality of the current source ([v] null = automatic), keeping the position. */
    fun setQuality(v: HlsVariants.Variant?) {
        val link = current ?: return
        val m = master ?: return
        scope.launch {
            val target = when {
                v == null -> null
                !m.separateAudio -> v.url
                else -> withContext(Dispatchers.IO) {
                    // The audio is separate: a master with only this quality, so it keeps its sound.
                    val headers = headersOf(link)
                    val proxied = true // through the local proxy, like every HLS source (see play())
                    val text = HlsVariants.single(m, v) { if (proxied) StreamProxy.wrap(it, headers) else it }
                    val file = java.io.File(App.context.cacheDir, "quality-${v.url.hashCode()}.m3u8").apply { writeText(text) }
                    Uri.fromFile(file).toString()
                }
            }
            variant = v
            play(link, target)
        }
    }

    private fun headersOf(link: ExtractorLink) = buildMap {
        putAll(link.headers)
        if (link.referer.isNotBlank() && keys.none { it.equals("Referer", true) }) put("Referer", link.referer)
    }

    /** Reads an HLS source's master playlist for its qualities (in the background; nothing waits for it). */
    private fun loadVariants(link: ExtractorLink) {
        mastersJob?.cancel()
        master = null; variants = emptyList(); variant = null
        if (!(link.isM3u8 || link.url.substringBefore('?').endsWith(".m3u8") || link.url.startsWith("data:", true))) return
        mastersJob = scope.launch {
            val m = withContext(Dispatchers.IO) {
                io.github.sherazi5110.cloudstream.DataUrl.decode(link.url)?.let { text ->
                    return@withContext runCatching { HlsVariants.parse(text, link.referer.ifBlank { "https://localhost/" }) }.getOrNull()
                }
                runCatching {
                    val request = okhttp3.Request.Builder().url(link.url).apply {
                        headersOf(link).forEach { (k, v) -> header(k, v) }
                        if (headersOf(link).keys.none { it.equals("User-Agent", true) }) header("User-Agent", USER_AGENT)
                    }.build()
                    com.lagradost.cloudstream3.app.baseClient.newCall(request).execute().use { r ->
                        if (!r.isSuccessful) null else HlsVariants.parse(r.body.string().take(512 * 1024), r.request.url.toString())
                    }
                }.getOrNull()
            }
            if (current?.url == link.url && m != null) { master = m; variants = m.variants }
        }
    }

    /** Starts [link] ([variantUrl]: one of its qualities instead), keeping the position when switching mid-playback. */
    fun play(link: ExtractorLink, variantUrl: String? = null) {
        if (variantUrl == null && openTorrent != null && openTorrent != link.url) closeTorrent()
        // A torrent: join it and buffer its start, then play it from this device's own little server.
        if (variantUrl == null && io.github.sherazi5110.cloudstream.Torrents.isTorrent(link)) {
            grace?.cancel(); watchdog?.cancel()
            current = link
            tried += link.url
            error = null; ended = false; buffering = true
            openTorrent = link.url
            torrentJob?.cancel()
            torrentJob = scope.launch {
                val local = runCatching { io.github.sherazi5110.cloudstream.Torrents.open(link.url) { st -> torrentStatus = st } }
                if (current?.url != link.url) return@launch
                local.onSuccess { url -> play(link, url) }.onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) return@onFailure
                    torrentStatus = null
                    App.toast("Torrent: ${e.message}")
                    failover()
                }
            }
            return
        }
        grace?.cancel()
        lastVariantUrl = variantUrl
        playCalledAt = android.os.SystemClock.elapsedRealtime()
        // Switching or reloading a source goes on from where it was, even after it failed (the clock then reads 0).
        val keep = lastGoodTime.takeIf { it > 0 } ?: startAt
        if (variantUrl == null && (current?.url != link.url || variant != null)) loadVariants(link.also { current = it })
        current = link
        matchSurface(link)
        tried += link.url
        error = null
        ended = false
        buffering = true
        val headers = buildMap {
            putAll(link.headers)
            if (link.referer.isNotBlank() && keys.none { it.equals("Referer", true) }) put("Referer", link.referer)
            if (keys.none { it.equals("User-Agent", true) }) put("User-Agent", USER_AGENT)
        }
        val source = (variantUrl ?: link.url).removeSuffix(io.github.sherazi5110.cloudstream.LiveApi.EXPIRED_MARK)
        // HLS (not Live TV) always goes through the local proxy: libVLC's own HTTP/2 client gave up on servers that
        // reset streams ("http demux: local stream error: Stream closed", then "cannot peek" and the video stopped),
        // while OkHttp retries and reuses connections; the proxy also fixes disguised video segments.
        val hls = api !is io.github.sherazi5110.cloudstream.LiveApi && !source.startsWith("file:") &&
            (link.isM3u8 || source.substringBefore('?').endsWith(".m3u8", true))
        // A playlist given inline (data: address, e.g. NetMirror): only the proxy can serve it to libVLC.
        val inline = source.startsWith("data:", true)
        val headersNeedProxy = inline || headers.keys.any { !it.equals("Referer", true) && !it.equals("User-Agent", true) }
        // A source that failed through the proxy is tried once directly (as before), in case the server objects to it.
        val viaProxyForHls = hls && !headersNeedProxy && link.url !in directTried
        proxiedOnlyForHls = viaProxyForHls
        val needsProxy = viaProxyForHls || headersNeedProxy
        // A Live TV server with an expired (but genuine) certificate: through the local proxy, which accepts it.
        val expiredOk = link.name == io.github.sherazi5110.cloudstream.LiveApi.EXPIRED
        val url = if (source.startsWith("file:")) source else if (needsProxy || expiredOk) StreamProxy.wrap(source, headers, expiredOk) else source
        val media = Media(Vlc.lib, Uri.parse(url)).apply {
            setHWDecoderEnabled(!softwareDecode, false)
            if (!needsProxy) {
                addOption(":http-user-agent=" + (headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: USER_AGENT))
                headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { addOption(":http-referrer=${it.value}") }
            }
            if (keep > 0) addOption(":start-time=${keep / 1000.0}")
            bufferOptions(variantUrl).forEach { addOption(it) }
            // Even out loud and quiet sounds: a gentle compressor with make-up gain (voices up, peaks down).
            if (io.github.sherazi5110.cloudstream.Settings.levelAudio) {
                addOption(":audio-filter=compressor")
                listOf("rms-peak=0.2", "attack=25", "release=150", "threshold=-24", "ratio=4", "knee=4", "makeup-gain=9")
                    .forEach { addOption(":compressor-$it") }
            }
        }
        io.github.sherazi5110.cloudstream.CrashReport.playing("${api.name}: ${response.name} (${playable.label}); source ${link.name} [${link.type}] from " +
            "${runCatching { Uri.parse(link.url).host }.getOrNull() ?: "?"}")
        io.github.sherazi5110.cloudstream.GlitchReport.step("Playing source ${link.name} [${link.type}] from ${runCatching { Uri.parse(link.url).host }.getOrNull() ?: "?"}" +
            (variant?.let { " at ${it.label}" } ?: ""))
        // Opening stops the previous source first, which waits for its network reads to end: on a slow host that
        // took seconds and froze the screen ("not responding"). Like VLC for Android, it runs on its own thread.
        val rate = speed
        val scale = zoom.scale
        val volume = boost
        opening = true
        VlcThread.run {
            try {
                // 5.1/7.1 sound: by default libVLC plays every channel the device can (multichannel on TVs and
                // receivers, mixed down to stereo on phones); passthrough sends Dolby/DTS untouched to a receiver.
                runCatching { player.setAudioDigitalOutputEnabled(Settings.passthrough) }
                player.media = media
                media.release()
                player.play()
                player.volume = volume
                player.rate = rate
                player.videoScale = scale
            } finally { opening = false }
        }
        // A source that hasn't started after 25 s (dead host, endless redirect) gives way to the next one;
        // a TV channel after 12 s (it has nothing else to wait for).
        watchdog?.cancel()
        if (!started) watchdog = scope.launch {
            delay(if (api is io.github.sherazi5110.cloudstream.LiveApi) 12_000 else if (io.github.sherazi5110.cloudstream.Torrents.isTorrent(link)) 60_000 else 25_000)
            if (!started && current?.url == link.url) failover()
        }
        selectedSubtitle?.let { url ->
            subtitles.firstOrNull { it.url == url }?.let { addSubtitle(it) }
                ?: addedFiles.firstOrNull { it.first.absolutePath == url }?.let { selectFile(it.first) }
        }
    }

    /** Shows an already added subtitle file again (a new source starts without it). */
    fun selectFile(file: java.io.File) {
        selectedSubtitle = file.absolutePath
        showSubtitle(file.absolutePath, null)
    }

    /** Adds a subtitle file to the player: cleaned first (Subtitle style: captions, ads, capitals) when it is SRT/VTT. */
    private fun showSubtitle(source: String, headers: Map<String, String>?) {
        val direct = if (source.startsWith("/")) Uri.fromFile(java.io.File(source))
            else headers?.takeIf { it.isNotEmpty() }?.let { Uri.parse(StreamProxy.wrap(source, it)) } ?: Uri.parse(source)
        val clean = SubtitleStyle.currentClean()
        if (!clean.active) { VlcThread.run { player.addSlave(IMedia.Slave.Type.Subtitle, direct, true) }; return }
        val wanted = selectedSubtitle
        scope.launch {
            val file = runCatching { cleanedFile(source, headers, clean) }
                .onFailure { android.util.Log.w("StreamGoat", "subtitle clean-up failed", it) }.getOrNull()
            if (selectedSubtitle != wanted) return@launch // something else was picked meanwhile
            val uri = file?.let { Uri.fromFile(it) } ?: direct
            VlcThread.run { player.addSlave(IMedia.Slave.Type.Subtitle, uri, true) }
        }
    }

    /** [source] cleaned with [c], cached; null when it isn't a text subtitle file (left to libVLC as it is). */
    private suspend fun cleanedFile(source: String, headers: Map<String, String>?, c: SubtitleStyle.Clean): java.io.File? = withContext(Dispatchers.IO) {
        val dir = java.io.File(App.context.cacheDir, "subtitles").apply { mkdirs() }
        val prefix = "c-${source.hashCode().toUInt()}-${c.key}."
        dir.listFiles()?.firstOrNull { it.name.startsWith(prefix) && it.length() > 0 }?.let { return@withContext it }
        val bytes = when {
            source.startsWith("/") -> java.io.File(source).readBytes()
            source.startsWith("file:") -> java.io.File(Uri.parse(source).path!!).readBytes()
            else -> {
                val h = headers ?: listOfNotNull(current?.referer?.takeIf { it.isNotBlank() }?.let { "Referer" to it }).toMap()
                val r = com.lagradost.cloudstream3.app.get(source, headers = h, timeout = 20)
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                r.body.bytes()
            }
        }
        val text = SubtitleStyle.decode(bytes)
        if (!text.contains("-->")) return@withContext null
        val out = SubtitleStyle.clean(text, c)
        val ext = if (out.trimStart().startsWith("WEBVTT")) "vtt" else if (out === text) "ass" else "srt"
        java.io.File(dir, prefix + ext).apply { writeText(out) }
    }

    /**
     * libVLC also reports "end reached" when a source fails to open or drops mid-way. Only a real end
     * (close to the length) finishes the episode; otherwise it's a failed source, like Android's player.
     */
    private fun onEndReached() {
        // A late event from the media that was just replaced (a source picked by hand): the player is
        // already opening the new one, so it isn't "ended". Ignore it.
        if (opening || player.playerState != org.videolan.libvlc.interfaces.IMedia.State.Ended &&
            android.os.SystemClock.elapsedRealtime() - playCalledAt < 5_000) return
        val len = length
        val t = time
        playing = false
        // Some hosts send a short "error" clip instead of failing; a real episode is longer than 3 minutes.
        if (started && !isLive && len >= 180_000 && t >= len - maxOf(20_000L, len / 20)) {
            ended = true
            save(finished = true)
        } else failover()
    }

    /** Skips to the next source not tried yet ("Next source" on the loading screen). */
    fun nextSource() = failover()

    /** A source failed: try the next untried one, like the Linux player. */
    private fun failover() {
        val cur = current
        if (cur != null && proxiedOnlyForHls && directTried.add(cur.url)) {
            android.util.Log.w("player", "${cur.name}: failed through the proxy, trying it directly")
            play(cur, lastVariantUrl)
            return
        }
        val next = ranked().firstOrNull { it.url !in tried }
        if (next != null) {
            App.toast("${current?.name ?: "Source"} failed, trying ${next.name}")
            play(next)
        } else if (fromCache && !refreshed && api !is io.github.sherazi5110.cloudstream.LiveApi) {
            // Every remembered source failed: they've probably expired. Ask the extension again, once.
            refreshed = true
            Engine.forget(api, playable.data)
            links.clear(); tried.clear(); current = null
            searching = true; error = null
            App.toast("Those sources expired; looking again…")
            scope.launch { findSources() }
        } else {
            // A TV channel has one stream: find out what stops it (an expired certificate, no answer…) and say so.
            if (api is io.github.sherazi5110.cloudstream.LiveApi) {
                val first = links.firstOrNull { it.name != io.github.sherazi5110.cloudstream.LiveApi.PLAIN } ?: current
                error = "Checking why ${response.name} doesn't play…"
                if (first != null) scope.launch {
                    val why = io.github.sherazi5110.cloudstream.LiveTv.diagnose(first.url, first.headers)
                    if (why == null) {
                        // The stream answers like a stream, yet the player couldn't play it: likely the app's fault, not the link's.
                        error = "${response.name}'s stream is online, but the player couldn't play it. This looks like a problem in Stream Goat, not the channel."
                        appIssue = buildString {
                            appendLine("Live TV channel that answers but doesn't play")
                            appendLine("Channel: ${response.name}")
                            appendLine("Address: ${first.url.take(300)}")
                            if (first.headers.isNotEmpty()) appendLine("Headers sent: ${first.headers.keys.joinToString()}")
                            appendLine("Addresses tried: ${tried.size}")
                        }
                    } else {
                        error = io.github.sherazi5110.cloudstream.LiveTv.failureMessage(response.name, first.url, first.headers,
                            plainTried = tried.any { it.startsWith("http://") } && first.url.startsWith("https://"))
                        // A broken link: "Hide not working" then leaves it out of the list too.
                        channelKey?.let { io.github.sherazi5110.cloudstream.LiveTv.markNotWorking(it, why) }
                    }
                }
            } else error = "This source can't be played. Pick another one in Sources."
            buffering = false
        }
    }

    /** Picks the audio and subtitle languages from Settings → Player, once per playback. */
    private fun applyLanguages() {
        if (opening) { languagesApplied = false; return } // a source switch is under way: on its Playing event
        val audio = Settings.audioLanguage.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (audio.isNotEmpty()) audioTracks().filter { it.id != -1 }.firstOrNull { t ->
            audio.any { w -> io.github.sherazi5110.cloudstream.OnlineSubtitles.matches(t.name, w) || t.name.contains(w, ignoreCase = true) }
        }?.let { setAudio(it.id) }
        val subs = Settings.subtitleLanguage.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (subs.isEmpty() || selectedSubtitle != null) return
        val embedded = spuTracks().filter { it.id != -1 }.firstOrNull { t -> subs.any { w -> t.name.contains(w, ignoreCase = true) } }
        if (embedded != null) { setSpu(embedded.id); return }
        subtitles.firstOrNull { s -> subs.any { w -> io.github.sherazi5110.cloudstream.OnlineSubtitles.matches(s.lang, w) } }?.let { addSubtitle(it) }
    }

    /** A subtitle file downloaded from the internet (OpenSubtitles) or translated. */
    /** Downloads an OpenSubtitles result and shows it (lives as long as the player, not the menu it was picked in). */
    fun addOnlineSubtitle(r: io.github.sherazi5110.cloudstream.OnlineSubtitles.Result) {
        App.toast("Downloading ${r.languageName} subtitles…")
        scope.launch {
            runCatching { io.github.sherazi5110.cloudstream.OnlineSubtitles.download(r) }
                .onSuccess { addSubtitleFile(it, r.languageName) }
                .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) App.toast("Couldn't download the subtitles: ${e.message}") }
        }
    }

    fun addSubtitleFile(file: java.io.File, name: String = file.name) {
        selectedSubtitle = file.absolutePath
        addedFiles += file to name
        showSubtitle(file.absolutePath, null)
    }

    /** Subtitle files added during this playback (online, translated), with their names. */
    val addedFiles = mutableStateListOf<Pair<java.io.File, String>>()
    var translating by mutableStateOf<String?>(null)
        private set

    /** What "Translate subtitles" starts from: the subtitles showing, else the first ones on offer. */
    fun translateSourceName(): String? {
        val sel = selectedSubtitle
        addedFiles.firstOrNull { it.first.absolutePath == sel }?.let { return it.second }
        subtitles.firstOrNull { it.url == sel }?.let { return it.lang }
        return subtitles.firstOrNull()?.lang ?: addedFiles.firstOrNull()?.second
    }

    private suspend fun translateSourceFile(): java.io.File? = withContext(Dispatchers.IO) {
        val sel = selectedSubtitle
        addedFiles.firstOrNull { it.first.absolutePath == sel }?.let { return@withContext it.first }
        val sub = subtitles.firstOrNull { it.url == sel } ?: subtitles.firstOrNull()
            ?: return@withContext addedFiles.firstOrNull()?.first
        if (sub.url.startsWith("file:")) return@withContext java.io.File(Uri.parse(sub.url).path!!)
        if (sub.url.startsWith("/")) return@withContext java.io.File(sub.url)
        val dir = java.io.File(App.context.cacheDir, "subtitles").apply { mkdirs() }
        val file = java.io.File(dir, "x-${sub.url.hashCode().toUInt()}.sub")
        if (!file.isFile || file.length() == 0L) {
            val headers = sub.headers ?: listOfNotNull(current?.referer?.takeIf { it.isNotBlank() }?.let { "Referer" to it }).toMap()
            val r = com.lagradost.cloudstream3.app.get(sub.url, headers = headers, timeout = 20)
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            file.writeBytes(r.body.bytes())
        }
        file
    }

    /** Translates the current subtitles into [lang] and switches to them. */
    fun translateSubtitles(lang: String) {
        val name = io.github.sherazi5110.cloudstream.SubtitleTranslator.languageName(lang)
        val from = translateSourceName() ?: return
        translating = name
        App.toast("Translating subtitles to $name…")
        scope.launch {
            try {
                val src = translateSourceFile() ?: throw java.io.IOException("No subtitles to translate")
                val out = io.github.sherazi5110.cloudstream.SubtitleTranslator.translateFile(src, lang, java.io.File(App.context.cacheDir, "subtitles"))
                addSubtitleFile(out, "$from → $name")
                App.toast("Subtitles: $name (translated)")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                App.toast("Couldn't translate: ${t.message ?: t}")
            } finally {
                translating = null
            }
        }
    }

    private fun loadSegments() {
        if (segmentsRequested || isLive || length <= 0 || !SkipTimes.applies(response)) return
        if (!Settings.skipButtons && !Settings.autoSkip && !Settings.autoPlayNext) return
        segmentsRequested = true
        scope.launch { segments = runCatching { SkipTimes.forEpisode(response, playable.episode, length) }.getOrDefault(emptyList()) }
    }

    private fun updateSkip(t: Long) {
        val seg = segments.firstOrNull { t >= it.start && t < it.end - 1000 }
        if (seg != null && Settings.autoSkip && seg !in autoSkipped) {
            autoSkipped += seg
            seekTo(seg.end)
            activeSegment = null
            return
        }
        activeSegment = seg?.takeIf { Settings.skipButtons }
    }

    fun skipSegment() { activeSegment?.let { seekTo(it.end) }; activeSegment = null }

    /** Where the next-episode countdown starts: at the credits if known, else 20 s before the end. */
    fun countdownStart(): Long? {
        if (next == null || isLive || length < 5 * 60_000) return null
        val credits = segments.firstOrNull { it.kind == SkipTimes.Kind.CREDITS && it.start > length / 2 && it.start < length - 5000 }
        return credits?.start ?: (length - 20_000)
    }

    // Everything that changes playback goes through the VLC thread, in order; what the screen shows comes from
    // libVLC's events (time, playing), so the screen never waits for libVLC.
    fun togglePause() = if (playing) pause() else resume()
    fun pause() = VlcThread.run { player.pause() }
    fun resume() = VlcThread.run { player.play() }

    fun seekBy(ms: Long) = seekTo((time + ms).coerceIn(0, length.takeIf { it > 0 } ?: Long.MAX_VALUE))

    fun seekTo(ms: Long) {
        time = ms; lastGoodTime = ms; lastSeekAt = android.os.SystemClock.elapsedRealtime()
        VlcThread.run { player.setTime(ms) }
    }

    /**
     * Settings → Player → Buffering: how much loads before starting (and after a jump), how far ahead it keeps
     * loading, and the most memory that may take. The time ahead is cut to what fits in that memory at the stream's
     * bitrate (3 minutes of a 25 Mbit/s 4K stream would be over 500 MB).
     */
    private fun bufferOptions(variantUrl: String?): List<String> {
        val memMb = Settings.bufferMemoryMb.takeIf { it > 0 } ?: if (io.github.sherazi5110.cloudstream.Images.lowMemoryDevice) 48 else 160
        val bitrate = (variants.firstOrNull { it.url == variantUrl } ?: variants.maxByOrNull { it.bandwidth ?: 0 })?.bandwidth
        val fits = bitrate?.takeIf { it > 0 }?.let { (memMb * 8L * 1024 * 1024 / it).toInt() } ?: Int.MAX_VALUE
        val aheadSec = minOf(Settings.bufferAheadSec, fits).coerceAtLeast(10)
        return listOf(
            ":network-caching=${Settings.startBufferMs}",
            ":adaptive-maxbuffer=${aheadSec * 1000}",
            ":prefetch-buffer-size=${memMb * 1024}",
        )
    }

    /** Set when the video keeps stopping to load: the player suggests a lower quality or another source. */
    var slowWarning by mutableStateOf(false)
    private val stalls = ArrayDeque<Long>()

    /** Counts a pause to load mid-video; three within two minutes means the connection can't keep up. */
    private fun stalled() {
        val now = android.os.SystemClock.elapsedRealtime()
        stalls.addLast(now)
        while (stalls.isNotEmpty() && now - stalls.first() > 120_000) stalls.removeFirst()
        if (stalls.size >= 3 && !slowWarningShown) { slowWarning = true; slowWarningShown = true }
    }
    private var slowWarningShown = false
    private var lastSeekAt = 0L
    /** Sources already tried without the proxy, and whether the current one went through it only because it's HLS. */
    private val directTried = mutableSetOf<String>()
    private var proxiedOnlyForHls = false

    fun changeBoost(percent: Int) {
        boost = percent.coerceIn(100, 200)
        Settings.volumeBoost = boost
        VlcThread.run { player.volume = boost }
    }

    fun setRate(rate: Float) {
        speed = rate
        VlcThread.run { player.rate = rate }
    }

    /** The resize button: Fit → Fill → Stretch → Fit (the others stay in Settings → Player). */
    fun cycleZoom(): Zoom {
        val next = when (zoom) { Zoom.FIT -> Zoom.FILL; Zoom.FILL -> Zoom.STRETCH; else -> Zoom.FIT }
        setZoomMode(next)
        return next
    }

    /** Reopens the current source where it is (after changing something that applies when a source opens). */
    fun reopen() { current?.let { play(it, lastVariantUrl) } }
    private var lastVariantUrl: String? = null

    fun setZoomMode(z: Zoom) {
        zoom = z
        VlcThread.run { player.videoScale = z.scale }
        Settings.playerZoom = z.name
    }

    // Track lists are read when a menu opens; while a source is opening they'd wait for it, so they're empty then.
    fun audioTracks(): List<MediaPlayer.TrackDescription> = if (opening) emptyList() else player.audioTracks?.toList().orEmpty()
    fun audioTrack(): Int = if (opening) -1 else player.audioTrack
    fun setAudio(id: Int) { VlcThread.run { player.setAudioTrack(id) } }

    /** Embedded subtitle tracks (id -1 = off) plus the extension's subtitle files. */
    fun spuTracks(): List<MediaPlayer.TrackDescription> = if (opening) emptyList() else player.spuTracks?.toList().orEmpty()
    fun spuTrack(): Int = if (opening) -1 else player.spuTrack
    fun setSpu(id: Int) { selectedSubtitle = null; VlcThread.run { player.setSpuTrack(id) } }

    fun addSubtitle(s: SubtitleFile) {
        selectedSubtitle = s.url
        showSubtitle(s.url, s.headers)
    }

    private var layout: VLCVideoLayout? = null

    /** Detaches whichever view the video is on. */
    private fun detachAll() {
        if (layout != null) { player.detachViews(); layout = null }
    }

    /** True while the video is on a SurfaceView (HDR output) rather than a TextureView. */
    var hdrSurface by mutableStateOf(false)
        private set
    private val screenHdr by lazy { io.github.sherazi5110.cloudstream.Picture.screenHdrTypes(layoutActivity()).isNotEmpty() }

    private fun layoutActivity(): android.app.Activity? {
        var ctx = layout?.context
        while (ctx is android.content.ContextWrapper) { if (ctx is android.app.Activity) return ctx; ctx = ctx.baseContext }
        return null
    }

    fun attach(layout: VLCVideoLayout) {
        detachAll()
        this.layout = layout
        hdrSurface = io.github.sherazi5110.cloudstream.Picture.useHdrSurface(current, screenHdr)
        // TextureView, so pinch-to-zoom (a view transform) also scales the picture; SurfaceView for HDR.
        player.attachViews(layout, null, true, !hdrSurface)
        val scale = zoom.scale
        VlcThread.run { player.videoScale = scale }
    }

    /** A new source may need the other kind of surface (HDR or not): re-attach before it plays. */
    private fun matchSurface(link: ExtractorLink) {
        val want = io.github.sherazi5110.cloudstream.Picture.useHdrSurface(link, screenHdr)
        if (want == hdrSurface) return
        val l = layout ?: run { hdrSurface = want; return }
        player.detachViews()
        hdrSurface = want
        player.attachViews(l, null, true, !want)
        val scale = zoom.scale
        VlcThread.run { player.videoScale = scale }
    }

    /** A view leaving the screen: detach only if the video is still on it (the next view may already have taken over). */
    fun detach(view: Any) { if (view === layout) detachAll() }

    /** Records progress for Continue Watching and the episode list. */
    /** WeTrakr sync for this playback (movies and episodes; not Live TV). */
    private val trakr by lazy { if (isLive) null else io.github.sherazi5110.cloudstream.WeTrakr.Session(response, playable.season, playable.episode) }
    private fun percent(): Double = if (length > 0) time * 100.0 / length else 0.0

    fun save(finished: Boolean = false) {
        if (finished) trakr?.stopped(100.0)
        if (isLive || !started) return
        val len = length.takeIf { it > 0 } ?: return
        val pos = if (finished) len else time
        Library.record(WatchEntry(
            api.name, response.url, response.name, response.posterUrl, playable.data,
            if (playables.size > 1) playable.label else null, pos / 1000.0, len / 1000.0, System.currentTimeMillis(),
        ))
    }

    private var released = false

    fun release() {
        if (started) trakr?.stopped(percent())
        released = true
        io.github.sherazi5110.cloudstream.CrashReport.playing(null)
        save()
        closeTorrent()
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        player.setEventListener(null)
        detachAll()
        // Stopping waits for the network reads to end (seconds on a slow host): off the main thread, after
        // everything queued before it. Like VLC for Android: views detached here, the player released there.
        val p = player
        VlcThread.run { p.stop(); p.release() }
    }
}

/**
 * The one thread that makes libVLC calls which can wait: opening a source (it stops the previous one), seeking,
 * changing tracks, stopping and releasing. In order, so a release always comes after the calls before it.
 */
internal object VlcThread {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vlc-player").apply { isDaemon = true } }
    fun run(block: () -> Unit) {
        executor.execute { runCatching(block).onFailure { android.util.Log.w("StreamGoat", "libVLC call failed", it) } }
    }
}
