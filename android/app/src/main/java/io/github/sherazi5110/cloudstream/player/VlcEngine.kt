package io.github.sherazi5110.cloudstream.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import com.lagradost.cloudstream3.USER_AGENT
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/** The libVLC engine (what Stream Goat always used): plays almost anything, fetches through the local proxy. */
class VlcEngine : PlaybackEngine {
    override val kind = EngineKind.VLC
    override var listener: PlaybackEngine.Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private val player = MediaPlayer(Vlc.lib)
    @Volatile override var opening = false
        private set
    private var layout: VLCVideoLayout? = null
    override val view: View? get() = layout
    private var zoom = Zoom.FIT

    init {
        player.setEventListener { e ->
            val l = listener ?: return@setEventListener
            when (e.type) {
                MediaPlayer.Event.Playing -> {
                    l.onPlaying()
                    // Read on the player's own thread: asking libVLC from the main thread while it's busy froze the app.
                    main.postDelayed({ VlcThread.run {
                        runCatching { player.currentVideoTrack?.let { t -> main.post { listener?.onVideoSize(t.width, t.height) } } }
                    } }, 1500)
                }
                MediaPlayer.Event.Paused -> l.onPaused()
                MediaPlayer.Event.Buffering -> l.onBuffering(e.buffering < 100f)
                MediaPlayer.Event.TimeChanged -> l.onTime(e.timeChanged)
                MediaPlayer.Event.LengthChanged -> l.onLength(e.lengthChanged)
                MediaPlayer.Event.EndReached -> main.post { listener?.onEnded() }
                MediaPlayer.Event.EncounteredError -> main.post { listener?.onError(null) }
            }
        }
    }

    override fun open(r: OpenRequest) {
        val media = Media(Vlc.lib, Uri.parse(r.url)).apply {
            setHWDecoderEnabled(!r.softwareDecode, false)
            if (!r.proxied) {
                addOption(":http-user-agent=" + (r.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: USER_AGENT))
                r.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { addOption(":http-referrer=${it.value}") }
            }
            if (r.startMs > 0) addOption(":start-time=${r.startMs / 1000.0}")
            addOption(":network-caching=${r.startBufferMs}")
            // Live TV: the sharpest quality from the start (VLC's HLS otherwise starts low).
            if (r.live) addOption(":adaptive-logic=highest")
            addOption(":adaptive-maxbuffer=${r.bufferAheadMs}")
            addOption(":prefetch-buffer-size=${r.bufferMemoryMb * 1024}")
            // Even out loud and quiet sounds: a gentle compressor with make-up gain (voices up, peaks down).
            if (r.levelAudio) {
                addOption(":audio-filter=compressor")
                listOf("rms-peak=0.2", "attack=25", "release=150", "threshold=-24", "ratio=4", "knee=4", "makeup-gain=9")
                    .forEach { addOption(":compressor-$it") }
            }
        }
        zoom = r.zoom
        // Opening stops the previous source first, which waits for its network reads to end: on its own thread.
        opening = true
        VlcThread.run {
            try {
                runCatching { player.setAudioDigitalOutputEnabled(r.passthrough) }
                player.media = media
                media.release()
                player.play()
                player.volume = r.volume
                player.rate = r.rate
                player.videoScale = r.zoom.scale
            } finally { opening = false }
        }
    }

    override fun play() = VlcThread.run { player.play() }
    override fun pause() = VlcThread.run { player.pause() }
    override fun seekTo(ms: Long) = VlcThread.run { player.setTime(ms) }
    override fun setRate(rate: Float) = VlcThread.run { player.rate = rate }
    override fun setVolume(percent: Int) = VlcThread.run { player.volume = percent }
    override fun setZoom(z: Zoom) { zoom = z; VlcThread.run { player.videoScale = z.scale } }
    override fun setDelays(audioMs: Long, subtitleMs: Long) {
        val sub = subtitleMs * 1000; val audio = audioMs * 1000
        VlcThread.run { if (player.spuDelay != sub) player.spuDelay = sub; if (player.audioDelay != audio) player.audioDelay = audio }
    }

    // Track lists are read when a menu opens; while a source is opening they'd wait for it, so they're empty then.
    override fun audioTracks() = if (opening) emptyList() else player.audioTracks?.map { TrackInfo(it.id, it.name) }.orEmpty()
    override fun audioTrack() = if (opening) -1 else player.audioTrack
    override fun setAudio(id: Int) = VlcThread.run { player.setAudioTrack(id) }
    override fun subtitleTracks() = if (opening) emptyList() else player.spuTracks?.map { TrackInfo(it.id, it.name) }.orEmpty()
    override fun subtitleTrack() = if (opening) -1 else player.spuTrack
    override fun setSubtitle(id: Int) = VlcThread.run { player.setSpuTrack(id) }
    override fun addSubtitle(uri: Uri) = VlcThread.run { player.addSlave(IMedia.Slave.Type.Subtitle, uri, true) }

    override fun reallyEnded() = !opening && player.playerState == IMedia.State.Ended

    override fun createView(context: Context): View = VLCVideoLayout(context)

    override fun attach(view: View, hdr: Boolean) {
        val l = view as? VLCVideoLayout ?: return
        if (layout != null) player.detachViews()
        layout = l
        // TextureView, so pinch-to-zoom (a view transform) also scales the picture; SurfaceView for HDR.
        player.attachViews(l, null, true, !hdr)
        val scale = zoom.scale
        VlcThread.run { player.videoScale = scale }
    }

    override fun detach(view: View) { if (view === layout) { player.detachViews(); layout = null } }

    override fun release() {
        listener = null
        player.setEventListener(null)
        if (layout != null) { player.detachViews(); layout = null }
        // Stopping waits for the network reads to end (seconds on a slow host): off the main thread.
        val p = player
        VlcThread.run { p.stop(); p.release() }
    }
}
