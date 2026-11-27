package io.github.sherazi5110.cloudstream.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app

/**
 * ExoPlayer (Media3), the player of the official CloudStream app: extensions are written and tested against it.
 * It fetches the source itself through the app's network client (the extension's headers and cookies, DNS over
 * HTTPS), and reads HLS, DASH and inline (data:) playlists natively, so sources like NetMirror's show their real
 * length, start at once and seek where you ask.
 */
@OptIn(UnstableApi::class)
class ExoEngine(context: Context) : PlaybackEngine {
    override val kind = EngineKind.EXO
    override var listener: PlaybackEngine.Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    override var opening = false
        private set
    private var playerView: PlayerView? = null
    override val view: View? get() = playerView
    private var ended = false
    private var startedOnce = false
    private var request: OpenRequest? = null
    private val extraSubs = mutableListOf<Uri>()
    private var loudness: android.media.audiofx.LoudnessEnhancer? = null
    private var loudnessSession = C.AUDIO_SESSION_ID_UNSET
    private var volumePercent = 100

    private val player: ExoPlayer = run {
        val s = io.github.sherazi5110.cloudstream.Settings
        val memMb = s.bufferMemoryMb.takeIf { it > 0 } ?: if (io.github.sherazi5110.cloudstream.Images.lowMemoryDevice) 48 else 160
        val ahead = (s.bufferAheadSec * 1000).coerceAtLeast(15_000)
        val startMs = s.startBufferMs.coerceIn(500, 20_000)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minOf(15_000, ahead), ahead, startMs, maxOf(startMs, 2_000))
            .setTargetBufferBytes(memMb * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val renderers = WithFfmpegAudio(appContext).setEnableDecoderFallback(true)
        ExoPlayer.Builder(appContext, renderers).setLoadControl(load).build().apply {
            addListener(object : Player.Listener {
                // The subtitles go to this app's own subtitle view, in the chosen style (see createView).
                override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) { styledSubs?.setCues(styled(cueGroup.cues)) }

                // A subtitle file just added (OpenSubtitles, translated, the extension's): selected as soon as
                // ExoPlayer has it, instead of the track chosen before.
                override fun onTracksChanged(tracks: Tracks) = onTracksChangedNow()

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> listener?.onBuffering(true)
                        Player.STATE_READY -> {
                            opening = false
                            listener?.onBuffering(false)
                            duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { listener?.onLength(it) }
                        }
                        Player.STATE_ENDED -> { ended = true; listener?.onEnded() }
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) { startedOnce = true; listener?.onPlaying(); tick() }
                    else if (playbackState == Player.STATE_READY) listener?.onPaused()
                }
                override fun onPlayerError(error: PlaybackException) {
                    opening = false
                    android.util.Log.w("player", "ExoPlayer: ${error.errorCodeName}: ${error.message}", error)
                    listener?.onError("${error.errorCodeName}: ${error.message}")
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) listener?.onVideoSize(videoSize.width, videoSize.height)
                }
                override fun onAudioSessionIdChanged(audioSessionId: Int) { applyVolume() }
            })
        }
    }

    /** ExoPlayer has no time events: the position is read twice a second while playing. */
    private val ticker = object : Runnable {
        override fun run() {
            listener?.onTime(player.currentPosition)
            if (player.isPlaying) main.postDelayed(this, 500)
        }
    }
    private fun tick() { main.removeCallbacks(ticker); main.post(ticker) }

    private fun dataSourceFactory(headers: Map<String, String>): DefaultDataSource.Factory {
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: USER_AGENT
        val http = OkHttpDataSource.Factory(app.baseClient)
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        // DefaultDataSource adds data:, file: and content: addresses on top of the network.
        return DefaultDataSource.Factory(appContext, http)
    }

    private fun subtitleMime(uri: Uri): String {
        val path = (uri.lastPathSegment ?: uri.toString()).lowercase().substringBefore('?')
        return when {
            path.endsWith(".vtt") || path.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            path.endsWith(".ttml") || path.endsWith(".xml") || path.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun mediaItem(r: OpenRequest): MediaItem {
        // ExoPlayer fetches the source itself (the original address with its headers), unless it's a local file.
        val uri = if (r.url.startsWith("file:")) r.url else r.original
        val subs = (r.subtitles + extraSubs).distinct().mapIndexed { i, u ->
            MediaItem.SubtitleConfiguration.Builder(u).setMimeType(subtitleMime(u)).setId("sub$i")
                .setSelectionFlags(if (u == extraSubs.lastOrNull()) C.SELECTION_FLAG_DEFAULT else 0).build()
        }
        return MediaItem.Builder().setUri(uri).apply {
            when {
                r.hls || uri.startsWith("data:", true) && "mpegurl" in uri.substringBefore(',').lowercase() -> setMimeType(MimeTypes.APPLICATION_M3U8)
                r.dash -> setMimeType(MimeTypes.APPLICATION_MPD)
            }
            if (subs.isNotEmpty()) setSubtitleConfigurations(subs)
        }.build()
    }

    override fun open(r: OpenRequest) {
        // Subtitle files added to the previous source don't belong to this one.
        if (r.original != request?.original) extraSubs.clear()
        openSource(r)
    }

    private fun openSource(r: OpenRequest) {
        request = r
        ended = false
        opening = true
        // Live TV: ExoPlayer starts an HLS channel on its lowest quality and is slow to step up; take the sharpest the
        // screen shows from the start (as TiviMate does). Films keep adapting to the connection.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            setForceHighestSupportedBitrate(r.live)
            if (r.live) androidx.media3.common.util.Util.getCurrentDisplayModeSize(appContext).let { setMaxVideoSize(maxOf(it.x, it.y), minOf(it.x, it.y)) }
            else clearVideoSizeConstraints()
        }.build()
        val source = DefaultMediaSourceFactory(dataSourceFactory(r.headers)).createMediaSource(mediaItem(r))
        player.setMediaSource(source, r.startMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
        player.setPlaybackSpeed(r.rate)
        volumePercent = r.volume
        applyVolume()
        setZoom(r.zoom)
    }

    override fun play() { player.play() }
    override fun pause() { player.pause() }
    override fun seekTo(ms: Long) { player.seekTo(ms); listener?.onTime(ms) }
    override fun setRate(rate: Float) { player.setPlaybackSpeed(rate) }

    override fun setVolume(percent: Int) { volumePercent = percent; applyVolume() }

    /** Up to 100 % with the player's volume; above that, Android's loudness enhancer on its audio session. */
    private fun applyVolume() {
        player.volume = (volumePercent / 100f).coerceAtMost(1f)
        val boostMb = ((volumePercent - 100).coerceAtLeast(0) * 12)   // 200 % ≈ +12 dB
        runCatching {
            if (boostMb == 0) { loudness?.enabled = false; return }
            val session = player.audioSessionId.takeIf { it != C.AUDIO_SESSION_ID_UNSET } ?: return
            // A new audio session (another output, a new source) needs its own enhancer.
            if (loudness != null && loudnessSession != session) { loudness?.release(); loudness = null }
            val l = loudness ?: android.media.audiofx.LoudnessEnhancer(session).also { loudness = it; loudnessSession = session }
            l.setTargetGain(boostMb)
            l.enabled = true
        }
    }

    override fun setZoom(z: Zoom) {
        playerView?.resizeMode = when (z) {
            Zoom.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            Zoom.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    /** ExoPlayer can't shift audio or subtitles in time: sync works with VLC. */
    override fun setDelays(audioMs: Long, subtitleMs: Long) = Unit

    private fun tracksOf(type: Int): List<Pair<Tracks.Group, Int>> = player.currentTracks.groups
        .filter { it.type == type }.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }

    private fun idOf(group: Tracks.Group, index: Int) = player.currentTracks.groups.indexOf(group) * 1000 + index

    private fun nameOf(g: Tracks.Group, i: Int, n: Int): String {
        val f = g.getTrackFormat(i)
        val lang = f.language?.takeIf { it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage }
        return listOfNotNull(f.label, lang.takeIf { l -> l != null && f.label?.contains(l) != true },
            f.channelCount.takeIf { it > 2 }?.let { if (it == 6) "5.1" else if (it == 8) "7.1" else "$it ch" })
            .joinToString(" · ").ifBlank { "Track $n" }
    }

    override fun audioTracks() = tracksOf(C.TRACK_TYPE_AUDIO).mapIndexed { n, (g, i) -> TrackInfo(idOf(g, i), nameOf(g, i, n + 1)) }
    override fun audioTrack() = tracksOf(C.TRACK_TYPE_AUDIO).firstOrNull { (g, i) -> g.isTrackSelected(i) }?.let { (g, i) -> idOf(g, i) } ?: -1
    override fun setAudio(id: Int) = select(C.TRACK_TYPE_AUDIO, id)

    override fun subtitleTracks() = listOf(TrackInfo(-1, "Off")) +
        tracksOf(C.TRACK_TYPE_TEXT).mapIndexed { n, (g, i) -> TrackInfo(idOf(g, i), nameOf(g, i, n + 1)) }
    override fun subtitleTrack() = if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) -1
        else tracksOf(C.TRACK_TYPE_TEXT).firstOrNull { (g, i) -> g.isTrackSelected(i) }?.let { (g, i) -> idOf(g, i) } ?: -1
    override fun setSubtitle(id: Int) { pendingSub = null; select(C.TRACK_TYPE_TEXT, id) }

    private fun select(type: Int, id: Int) {
        val params = player.trackSelectionParameters.buildUpon()
        if (id < 0) { player.trackSelectionParameters = params.setTrackTypeDisabled(type, true).build(); return }
        val group = player.currentTracks.groups.getOrNull(id / 1000) ?: return
        player.trackSelectionParameters = params.setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, id % 1000)).build()
    }

    /** ExoPlayer takes subtitle files with the source: it reopens at the same place with the file added. */
    override fun addSubtitle(uri: Uri) {
        val r = request ?: return
        // The track chosen before (a subtitle inside the video) would stay chosen and hide the added file: cleared,
        // and the file's own track is selected once it's there (onTracksChanged).
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        if (uri in extraSubs) {
            // Added before (picked again from the list): just select it.
            pendingSub = "sub${(r.subtitles + extraSubs).distinct().indexOf(uri)}"
            onTracksChangedNow()
            return
        }
        extraSubs += uri
        pendingSub = "sub${(r.subtitles + extraSubs).distinct().indexOf(uri)}"
        val pos = player.currentPosition
        val playing = player.playWhenReady
        openSource(r.copy(startMs = pos))
        player.playWhenReady = playing
    }

    /** The subtitle file waiting to be selected ("sub3": its id in the media item). */
    private var pendingSub: String? = null

    private fun onTracksChangedNow() {
        val want = pendingSub ?: return
        val found = tracksOf(C.TRACK_TYPE_TEXT).firstOrNull { (g, i) -> g.getTrackFormat(i).id?.substringAfterLast(':') == want } ?: return
        pendingSub = null
        select(C.TRACK_TYPE_TEXT, idOf(found.first, found.second))
    }

    override fun reallyEnded() = ended

    override fun createView(context: Context): View = PlayerView(context).apply {
        useController = false
        setShutterBackgroundColor(android.graphics.Color.BLACK)
        setKeepContentOnPlayerReset(true)
        // ExoPlayer's own subtitle view always drew plain white text (Settings → Subtitle style only reached VLC):
        // this app's view draws them instead, in the chosen size, colour, font, edge, background, height and side.
        subtitleView?.visibility = View.GONE
        val own = androidx.media3.ui.SubtitleView(context)
        overlayFrameLayout?.addView(own, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        styledSubs = own
        restyle()
    }

    /** This app's subtitle view, styled (see [restyle]). */
    private var styledSubs: androidx.media3.ui.SubtitleView? = null
    private var look = SubtitleStyle.current()

    /** Applies Settings → Subtitle style to the subtitles at once (no reopening, unlike VLC). */
    fun restyle() {
        look = SubtitleStyle.current()
        val v = styledSubs ?: return
        val l = look
        val shadow = l.edge == "SHADOW"
        val edge = when (l.edge) {
            "NONE" -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
            "SHADOW" -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            else -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
        }
        val family = when (l.font) { "SERIF" -> "serif"; "MONO" -> "monospace"; "CONDENSED" -> "sans-serif-condensed"; else -> "sans-serif" }
        val alpha = (l.backgroundOpacity.coerceIn(0, 100) * 255 / 100) shl 24
        v.setStyle(androidx.media3.ui.CaptionStyleCompat(
            0xff000000.toInt() or SubtitleStyle.rgb(l.color),
            if (l.background) alpha or SubtitleStyle.rgb(l.backgroundColor) else android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
            edge,
            if (shadow) 0xff000000.toInt() else 0xff000000.toInt() or SubtitleStyle.rgb(l.outlineColor),
            android.graphics.Typeface.create(family, if (l.bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL),
        ))
        // The stream's own sizes and colours would win over the chosen ones; its italics stay.
        v.setApplyEmbeddedFontSizes(false)
        v.setFractionalTextSize(SubtitleStyle.exoTextSize(l.size) * io.github.sherazi5110.cloudstream.Settings.textScale.coerceIn(0.8f, 1.5f))
        v.setBottomPaddingFraction(SubtitleStyle.exoBottom(l.height))
        player.currentCues.cues.let { v.setCues(styled(it)) }
    }

    /** The chosen side (left, centre, right) for text subtitles (pictures, as on some Blu-rays, stay where they are). */
    private fun styled(cues: List<androidx.media3.common.text.Cue>): List<androidx.media3.common.text.Cue> {
        val align = when (look.align) {
            "LEFT" -> android.text.Layout.Alignment.ALIGN_NORMAL
            "RIGHT" -> android.text.Layout.Alignment.ALIGN_OPPOSITE
            else -> return cues
        }
        return cues.map { c -> if (c.bitmap != null) c else c.buildUpon().setTextAlignment(align).build() }
    }

    override fun attach(view: View, hdr: Boolean) {
        val v = view as? PlayerView ?: return
        playerView?.takeIf { it !== v }?.player = null
        playerView = v
        v.player = player
        request?.zoom?.let { setZoom(it) }
    }

    override fun detach(view: View) { if (view === playerView) { playerView?.player = null; playerView = null } }

    override fun release() {
        listener = null
        main.removeCallbacksAndMessages(null)
        playerView?.player = null
        playerView = null
        runCatching { loudness?.release() }
        player.release()
    }
}

/**
 * ExoPlayer's renderers plus FFmpeg's audio decoders after the device's own (NextLib): a sound the device can't decode
 * (Dolby Digital Plus "DDP 5.1", DTS, TrueHD on many phones) is decoded by FFmpeg instead of playing silently. The
 * device's decoders come first, so passthrough to a receiver and hardware decoding are unchanged.
 */
@OptIn(UnstableApi::class)
private class WithFfmpegAudio(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
        enableDecoderFallback: Boolean, audioSink: androidx.media3.exoplayer.audio.AudioSink, eventHandler: Handler,
        eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener, out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
    ) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        runCatching {
            if (io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary.isAvailable())
                out += io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer(eventHandler, eventListener, audioSink)
        }.onFailure { android.util.Log.w("StreamGoat", "FFmpeg audio decoders unavailable", it) }
    }
}
