package io.github.sherazi5110.cloudstream.player

import android.content.Context
import android.net.Uri
import android.view.View

/** An audio or subtitle track of the playing source (id -1 = off). */
data class TrackInfo(val id: Int, val name: String)

/** Which video engine plays a source. Settings → Player → Player engine; Auto picks per source. */
enum class EngineKind(val label: String, val detail: String) {
    EXO("ExoPlayer", "What the official CloudStream app uses: extensions are made for it"),
    VLC("VLC", "Plays almost anything; best for Live TV, torrents and files"),
}

/** The kinds of source that each have their own default engine (Settings → Player → Player engine). */
enum class PlayerSource(val key: String, val label: String) {
    EXTENSION("extension", "CloudStream extensions"),
    STREMIO("stremio", "Stremio add-ons"),
    TORRENT("torrent", "Torrents"),
    LIVE("live", "Live TV"),
    FILE("file", "Downloaded files"),
}

/**
 * What a source needs from the engine that plays it. [url] is where to play from (already through the local
 * proxy when [proxied]); [original] and [headers] are the source as the extension gave it, for engines that fetch
 * it themselves (ExoPlayer, like the official app).
 */
data class OpenRequest(
    val url: String,
    val original: String,
    val headers: Map<String, String>,
    val proxied: Boolean,
    val startMs: Long,
    val hls: Boolean,
    val dash: Boolean,
    val softwareDecode: Boolean,
    val bufferAheadMs: Int,
    val startBufferMs: Int,
    val bufferMemoryMb: Int,
    val levelAudio: Boolean,
    val passthrough: Boolean,
    val volume: Int,
    val rate: Float,
    val zoom: Zoom,
    val hdrSurface: Boolean,
    /** Subtitle files to show (ExoPlayer needs them when the source opens). */
    val subtitles: List<Uri> = emptyList(),
    /** A Live TV channel: plays its sharpest quality (up to the screen's) from the start, not the lowest. */
    val live: Boolean = false,
)

/**
 * A video engine: VLC or ExoPlayer behind the same calls, so the player screen and everything around it
 * (sources, failover, subtitles, quality, skip intro, Continue Watching) work the same with each.
 * Calls come from the main thread; engines do slow work on their own threads. Events come back on the main thread.
 */
interface PlaybackEngine {
    val kind: EngineKind

    interface Listener {
        fun onPlaying()
        fun onPaused()
        fun onBuffering(buffering: Boolean)
        fun onTime(ms: Long)
        fun onLength(ms: Long)
        fun onEnded()
        fun onError(message: String?)
        fun onVideoSize(width: Int, height: Int)
    }

    var listener: Listener?

    /** True while a source is opening (track lists would wait for it). */
    val opening: Boolean

    fun open(r: OpenRequest)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun setRate(rate: Float)
    /** Percent: 100 normal, up to 200 (louder than the device's maximum). */
    fun setVolume(percent: Int)
    fun setZoom(z: Zoom)
    fun setDelays(audioMs: Long, subtitleMs: Long)

    fun audioTracks(): List<TrackInfo>
    fun audioTrack(): Int
    fun setAudio(id: Int)
    fun subtitleTracks(): List<TrackInfo>
    fun subtitleTrack(): Int
    fun setSubtitle(id: Int)
    /** Shows a subtitle file (a file: or http: address). */
    fun addSubtitle(uri: Uri)

    /** True once the source has really reached its end (not a failure that also ends it). */
    fun reallyEnded(): Boolean

    /** The view the picture is drawn on; [attach] puts the engine on it, [detach] takes it off. */
    fun createView(context: Context): View
    fun attach(view: View, hdr: Boolean)
    fun detach(view: View)
    val view: View?

    fun release()
}
