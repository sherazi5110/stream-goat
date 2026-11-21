package io.github.sherazi5110.cloudstream

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sherazi5110.cloudstream.player.Vlc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * Music from YouTube (via NewPipe, which the app already uses for YouTube): search songs, play the audio only with
 * the built-in VLC (no video, no ads, little data), a queue made of the search results, and a history.
 */
object Music {
    data class Track(val url: String, val title: String, val artist: String, val thumbnail: String?, val seconds: Long)

    var current by mutableStateOf<Track?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var length by mutableLongStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
    /** What plays, in order (the current song is in it). Shown as "Up next" in the player. */
    var queue by mutableStateOf<List<Track>>(emptyList())
        private set
    var shuffle by mutableStateOf(false)
    /** 0 = off, 1 = repeat the list, 2 = repeat this song. */
    var repeat by mutableStateOf(0)
    /** YouTube's trending music. */
    var popular by mutableStateOf<List<Track>>(emptyList())
        private set
    /** Songs like the one playing (or the last one played): YouTube's related videos. */
    var suggestions by mutableStateOf<List<Track>>(emptyList())
        private set
    private var suggestionsFor: String? = null

    private fun StreamInfoItem.track() = Track(url, name, uploaderName.orEmpty().removeSuffix(" - Topic"),
        thumbnails.maxByOrNull { it.width }?.url, duration)

    /** Trending music on YouTube (its "Music" chart; the general trending list when there isn't one). */
    suspend fun loadPopular(): List<Track> = withContext(Dispatchers.IO) {
        val yt = ServiceList.YouTube
        val kiosks = yt.kioskList
        val id = kiosks.availableKiosks.firstOrNull { it.contains("music", true) } ?: kiosks.defaultKioskId
        val url = kiosks.getListLinkHandlerFactoryByType(id).fromId(id).url
        org.schabi.newpipe.extractor.kiosk.KioskInfo.getInfo(yt, url).relatedItems.filterIsInstance<StreamInfoItem>()
            .map { it.track() }.also { popular = it }
    }

    /** Suggestions from [track] (the last played song when the tab opens). */
    suspend fun loadSuggestions(track: Track) = withContext(Dispatchers.IO) {
        if (suggestionsFor == track.url && suggestions.isNotEmpty()) return@withContext
        val info = StreamInfo.getInfo(ServiceList.YouTube, track.url)
        setSuggestions(track.url, info)
    }

    private fun setSuggestions(from: String, info: StreamInfo) {
        val related = info.relatedItems.filterIsInstance<StreamInfoItem>().filter { it.url != from && it.duration in 60..900 }
        if (related.isNotEmpty()) { suggestions = related.map { it.track() }; suggestionsFor = from }
    }

    private val player: MediaPlayer by lazy {
        MediaPlayer(Vlc.lib).apply {
            setEventListener { e ->
                when (e.type) {
                    MediaPlayer.Event.Playing -> { playing = true; loading = false; MusicService.update() }
                    MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> { playing = false; if (current != null) MusicService.update() }
                    MediaPlayer.Event.TimeChanged -> Music.position = e.timeChanged
                    MediaPlayer.Event.LengthChanged -> Music.length = e.lengthChanged
                    MediaPlayer.Event.EndReached -> App.scope.launch(Dispatchers.Main) { next() }
                    MediaPlayer.Event.EncounteredError -> { loading = false; playing = false; error = "Couldn't play this song" }
                }
            }
        }
    }

    /** YouTube Music songs for [query] (falls back to all of YouTube when Music has nothing). */
    suspend fun search(query: String): List<Track> = withContext(Dispatchers.IO) {
        val yt = ServiceList.YouTube
        fun run(filter: String): List<Track> {
            val handler = yt.searchQHFactory.fromQuery(query, listOf(filter), "")
            return SearchInfo.getInfo(yt, handler).relatedItems.filterIsInstance<StreamInfoItem>().map { s ->
                Track(s.url, s.name, s.uploaderName.orEmpty().removeSuffix(" - Topic"), s.thumbnails.maxByOrNull { it.width }?.url, s.duration)
            }
        }
        runCatching { run(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: run(YoutubeSearchQueryHandlerFactory.VIDEOS)
    }

    /** Public Piped servers: they fetch from YouTube themselves, for when YouTube blocks this connection ("not a bot"). */
    private val piped = listOf("https://pipedapi.kavin.rocks", "https://pipedapi.adminforge.de", "https://api.piped.private.coffee",
        "https://pipedapi.r4fo.com", "https://pipedapi.leptons.xyz")

    /**
     * The address of a song's audio: from YouTube directly (NewPipe), or, when YouTube refuses this connection, from
     * a Piped server. [m4a] prefers AAC (for downloads, which play everywhere). Also loads the song's suggestions.
     */
    suspend fun audioUrl(url: String, m4a: Boolean = false): String = withContext(Dispatchers.IO) {
        val direct = runCatching {
            val info = StreamInfo.getInfo(ServiceList.YouTube, url)
            runCatching { setSuggestions(url, info) }
            val streams = info.audioStreams
            ((if (m4a) streams.filter { it.format?.suffix == "m4a" }.maxByOrNull { it.averageBitrate } else null)
                ?: streams.maxByOrNull { it.averageBitrate })?.content ?: error("No audio for this song")
        }
        direct.getOrNull()?.let { return@withContext it }
        val id = Regex("(?:v=|youtu\\.be/|/shorts/)([\\w-]{11})").find(url)?.groupValues?.get(1) ?: throw direct.exceptionOrNull()!!
        for (base in piped) {
            val found = runCatching {
                val text = com.lagradost.cloudstream3.app.get("$base/streams/$id", timeout = 10).text
                val o = org.json.JSONObject(text)
                val a = o.getJSONArray("audioStreams")
                val list = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("url").startsWith("http") }
                val best = (if (m4a) list.filter { "mp4" in it.optString("mimeType") }.maxByOrNull { it.optInt("bitrate") } else null)
                    ?: list.maxByOrNull { it.optInt("bitrate") }
                best?.getString("url")
            }.getOrNull()
            if (found != null) return@withContext found
        }
        throw direct.exceptionOrNull() ?: IllegalStateException("No audio for this song")
    }

    /** Plays [track]; the rest of [list] after it plays next. */
    fun play(track: Track, list: List<Track> = listOf(track)) {
        queue = list
        current = track; loading = true; error = null; position = 0; length = track.seconds * 1000
        WebHistory.songs.add(HistoryStore.Entry(track.url, track.title, track.artist, track.thumbnail))
        // A downloaded song plays from the app's storage (no internet needed).
        MusicLibrary.localFile(track.url)?.let { f ->
            runCatching {
                player.stop()
                val m = Media(Vlc.lib, Uri.fromFile(f)).apply { addOption(":no-video") }
                player.media = m; m.release(); player.play()
            }.onFailure { loading = false; error = "Couldn't play \"${track.title}\"" }
            App.scope.launch { runCatching { withContext(Dispatchers.IO) { StreamInfo.getInfo(ServiceList.YouTube, track.url) }.let { setSuggestions(track.url, it) } } }
            return
        }
        App.scope.launch {
            val result = runCatching { audioUrl(track.url) }
            if (current != track) return@launch
            val audio = result.getOrNull()
            if (audio == null) {
                loading = false
                error = "Couldn't play \"${track.title}\": " + (result.exceptionOrNull()?.message?.substringBefore('\n')?.take(120) ?: "no audio")
                return@launch
            }
            withContext(Dispatchers.Main) {
                runCatching {
                    player.stop()
                    val m = Media(Vlc.lib, Uri.parse(audio)).apply { addOption(":no-video") }
                    player.media = m
                    m.release()      // the player keeps its own reference
                    player.play()
                }.onFailure { loading = false; error = "Couldn't play \"${track.title}\"" }
            }
        }
    }

    /** Puts [track] right after the current song. */
    fun playNext(track: Track) {
        if (current == null) { play(track); return }
        val i = queue.indexOf(current)
        queue = queue.filterNot { it.url == track.url }.toMutableList().apply { add((queue.indexOf(current) + 1).coerceIn(0, size), track) }
        App.toast("Plays next: ${track.title}")
    }

    fun toggle() { if (player.isPlaying) player.pause() else if (current != null) player.play() }
    fun seekTo(ms: Long) { player.time = ms }
    /** The next song in the list; at the end, carries on with a suggestion (like YouTube Music's autoplay). */
    /** Songs after the current one. */
    val upNext: List<Track> get() = queue.drop(queue.indexOf(current) + 1)

    fun addToQueue(track: Track) {
        if (current == null) { play(track); return }
        queue = queue.filterNot { it.url == track.url || false } .let { q -> if (current in q) q + track else listOfNotNull(current) + q + track }
        App.toast("Added to queue")
    }

    /** Plays [track], then songs like it (YouTube's mix for it). */
    fun startMix(track: Track) {
        play(track)
        App.scope.launch {
            runCatching { loadSuggestions(track) }
            if (current?.url == track.url) queue = listOf(track) + suggestions.filter { it.url != track.url }
        }
    }

    fun next() {
        if (repeat == 2 && current != null) { seekTo(0); if (!player.isPlaying) player.play(); return }
        val i = queue.indexOf(current)
        if (shuffle && queue.size > 1) { queue.filter { it != current }.randomOrNull()?.let { play(it, queue); return } }
        queue.getOrNull(i + 1)?.let { play(it, queue); return }
        if (repeat == 1 && queue.isNotEmpty()) { play(queue.first(), queue); return }
        val more = suggestions.filter { s -> queue.none { it.url == s.url } }
        more.firstOrNull()?.let { play(it, queue + more) } ?: run { playing = false }
    }
    fun previous() { val i = queue.indexOf(current); if (position > 3000 || i <= 0) seekTo(0) else play(queue[i - 1], queue) }
    fun stop() { player.stop(); current = null; playing = false; MusicService.update() }
    /** Videos take over the sound: music pauses when a video starts. */
    fun pauseForVideo() { if (playing) player.pause() }
}
