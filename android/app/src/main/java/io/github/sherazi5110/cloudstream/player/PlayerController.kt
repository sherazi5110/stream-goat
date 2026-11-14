package io.github.sherazi5110.cloudstream.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

/** The one libVLC instance (it is heavy to create). */
object Vlc {
    val lib: LibVLC by lazy {
        LibVLC(App.context, arrayListOf(
            "--http-reconnect",
            // 1.5 s of buffer before playing (was 3 s): starts as quickly as the official app's player.
            "--network-caching=1500",
            "--audio-time-stretch", // keeps the pitch at other speeds
            "--subsdec-encoding=UTF-8",
            "--freetype-rel-fontsize=16",
            "--freetype-color=16777215",
            "--freetype-outline-thickness=4",
        ))
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
    private val main = Handler(Looper.getMainLooper())
    val player = MediaPlayer(Vlc.lib)

    val links = mutableStateListOf<ExtractorLink>()
    val subtitles = mutableStateListOf<SubtitleFile>()
    var searching by mutableStateOf(true)
    var searchError by mutableStateOf<String?>(null)
    var current by mutableStateOf<ExtractorLink?>(null)
    var started by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var time by mutableLongStateOf(0L)
    var length by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
    var zoom by mutableStateOf(runCatching { Zoom.valueOf(Settings.playerZoom) }.getOrDefault(Zoom.FIT))
    var selectedSubtitle by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var ended by mutableStateOf(false)

    /** Auto-play waits for this; "Choose source" turns it off. */
    var autoPlay = Settings.autoPlay && !chooseSource
        private set
    private val tried = mutableSetOf<String>()
    private var startAt: Long = 0
    private var grace: Job? = null
    private var watchdog: Job? = null
    private var saver: Job? = null
    val isLive = live || response is LiveStreamLoadResponse
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
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> {
                    // Next time (Continue Watching, Resume) this title starts with the same source.
                    current?.takeIf { api !is io.github.sherazi5110.cloudstream.LiveApi }?.let { io.github.sherazi5110.cloudstream.LastSource.remember(api.name, response.url, it) }
                    playing = true; buffering = false; started = true; error = null; watchdog?.cancel()
                    if (!languagesApplied) { languagesApplied = true; main.postDelayed({ applyLanguages() }, 1500) }
                }
                MediaPlayer.Event.Paused -> playing = false
                MediaPlayer.Event.Buffering -> buffering = e.buffering < 100f
                MediaPlayer.Event.TimeChanged -> { time = e.timeChanged; if (buffering && playing) buffering = false; updateSkip(e.timeChanged) }
                MediaPlayer.Event.LengthChanged -> { length = e.lengthChanged; loadSegments() }
                MediaPlayer.Event.EndReached -> main.post { onEndReached() }
                MediaPlayer.Event.EncounteredError -> main.post { failover() }
            }
        }
        scope.launch { findSources() }
        saver = scope.launch {
            while (true) { delay(10_000); if (playing) save() }
        }
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
                    if (link.type == ExtractorLinkType.MAGNET || link.type == ExtractorLinkType.TORRENT) return@collect
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
                    if (links.isEmpty()) searchError = searchError ?: "No sources found"
                }
            }
        }
    }

    /** Best first: closest to the preferred quality without going over it. */
    fun ranked(): List<ExtractorLink> = Engine.rank(links.toList())

    /**
     * Like the official app: while the extension is still finding links nothing starts, so the best of all of
     * them plays (starting with an early, weaker link made extensions that ask many sites at once, such as
     * Eclipsia, fail over from link to link). "Play now (N)" on the loading screen starts at once, like the
     * official "Skip loading (N)"; the search itself ends after Settings → Player → Wait for sources.
     */
    private fun onNewLink() = Unit

    fun stopAutoPlay() { autoPlay = false; grace?.cancel() }

    /** Starts [link], keeping the current position when switching sources mid-playback. */
    fun play(link: ExtractorLink) {
        grace?.cancel()
        playCalledAt = android.os.SystemClock.elapsedRealtime()
        val keep = if (started) player.time.takeIf { it > 0 } ?: startAt else startAt
        current = link
        matchSurface(link)
        tried += link.url
        error = null
        ended = false
        buffering = true
        val headers = buildMap {
            putAll(link.headers)
            if (link.referer.isNotBlank() && keys.none { it.equals("Referer", true) }) put("Referer", link.referer)
        }
        val needsProxy = headers.keys.any { !it.equals("Referer", true) && !it.equals("User-Agent", true) }
        val url = if (needsProxy) StreamProxy.wrap(link.url, headers) else link.url
        val media = Media(Vlc.lib, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            if (!needsProxy) {
                addOption(":http-user-agent=" + (headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: USER_AGENT))
                headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { addOption(":http-referrer=${it.value}") }
            }
            if (keep > 0) addOption(":start-time=${keep / 1000.0}")
        }
        io.github.sherazi5110.cloudstream.CrashReport.playing("${api.name}: ${response.name} (${playable.label}); source ${link.name} [${link.type}] from " +
            "${runCatching { Uri.parse(link.url).host }.getOrNull() ?: "?"}")
        player.media = media
        media.release()
        player.play()
        // A source that hasn't started after 25 s (dead host, endless redirect) gives way to the next one;
        // a TV channel after 12 s (it has nothing else to wait for).
        watchdog?.cancel()
        if (!started) watchdog = scope.launch {
            delay(if (api is io.github.sherazi5110.cloudstream.LiveApi) 12_000 else 25_000)
            if (!started && current?.url == link.url) failover()
        }
        player.rate = speed
        player.videoScale = zoom.scale
        selectedSubtitle?.let { url ->
            subtitles.firstOrNull { it.url == url }?.let { addSubtitle(it) }
                ?: addedFiles.firstOrNull { it.first.absolutePath == url }?.let { selectFile(it.first) }
        }
    }

    /** Shows an already added subtitle file again (a new source starts without it). */
    fun selectFile(file: java.io.File) {
        selectedSubtitle = file.absolutePath
        player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), true)
    }

    /**
     * libVLC also reports "end reached" when a source fails to open or drops mid-way. Only a real end
     * (close to the length) finishes the episode; otherwise it's a failed source, like Android's player.
     */
    private fun onEndReached() {
        // A late event from the media that was just replaced (a source picked by hand): the player is
        // already opening the new one, so it isn't "ended". Ignore it.
        if (player.playerState != org.videolan.libvlc.interfaces.IMedia.State.Ended &&
            android.os.SystemClock.elapsedRealtime() - playCalledAt < 5_000) return
        val len = length
        val t = player.time.takeIf { it > 0 } ?: time
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
                    error = io.github.sherazi5110.cloudstream.LiveTv.failureMessage(response.name, first.url, first.headers,
                        plainTried = tried.any { it.startsWith("http://") } && first.url.startsWith("https://"))
                }
            } else error = "This source can't be played. Pick another one in Sources."
            buffering = false
        }
    }

    /** Picks the audio and subtitle languages from Settings → Player, once per playback. */
    private fun applyLanguages() {
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
    fun addSubtitleFile(file: java.io.File, name: String = file.name) {
        selectedSubtitle = file.absolutePath
        addedFiles += file to name
        player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), true)
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
            main.post { seekTo(seg.end) }
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

    fun togglePause() = if (player.isPlaying) player.pause() else player.play()

    fun seekBy(ms: Long) = seekTo((player.time + ms).coerceIn(0, length.takeIf { it > 0 } ?: Long.MAX_VALUE))

    fun seekTo(ms: Long) {
        player.setTime(ms)
        time = ms
    }

    fun setRate(rate: Float) {
        speed = rate
        player.rate = rate
    }

    fun setZoomMode(z: Zoom) {
        zoom = z
        player.videoScale = z.scale
        Settings.playerZoom = z.name
    }

    fun audioTracks(): List<MediaPlayer.TrackDescription> = player.audioTracks?.toList().orEmpty()
    fun audioTrack(): Int = player.audioTrack
    fun setAudio(id: Int) { player.setAudioTrack(id) }

    /** Embedded subtitle tracks (id -1 = off) plus the extension's subtitle files. */
    fun spuTracks(): List<MediaPlayer.TrackDescription> = player.spuTracks?.toList().orEmpty()
    fun spuTrack(): Int = player.spuTrack
    fun setSpu(id: Int) { selectedSubtitle = null; player.setSpuTrack(id) }

    fun addSubtitle(s: SubtitleFile) {
        selectedSubtitle = s.url
        val url = s.headers?.takeIf { it.isNotEmpty() }?.let { StreamProxy.wrap(s.url, it) } ?: s.url
        player.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(url), true)
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
        player.videoScale = zoom.scale
    }

    /** A new source may need the other kind of surface (HDR or not): re-attach before it plays. */
    private fun matchSurface(link: ExtractorLink) {
        val want = io.github.sherazi5110.cloudstream.Picture.useHdrSurface(link, screenHdr)
        if (want == hdrSurface) return
        val l = layout ?: run { hdrSurface = want; return }
        player.detachViews()
        hdrSurface = want
        player.attachViews(l, null, true, !want)
        player.videoScale = zoom.scale
    }

    /** A view leaving the screen: detach only if the video is still on it (the next view may already have taken over). */
    fun detach(view: Any) { if (view === layout) detachAll() }

    /** Records progress for Continue Watching and the episode list. */
    fun save(finished: Boolean = false) {
        if (isLive || !started) return
        val len = length.takeIf { it > 0 } ?: return
        val pos = if (finished) len else player.time
        Library.record(WatchEntry(
            api.name, response.url, response.name, response.posterUrl, playable.data,
            if (playables.size > 1) playable.label else null, pos / 1000.0, len / 1000.0, System.currentTimeMillis(),
        ))
    }

    fun release() {
        io.github.sherazi5110.cloudstream.CrashReport.playing(null)
        save()
        scope.cancel()
        player.setEventListener(null)
        player.stop()
        detachAll()
        player.release()
    }
}
