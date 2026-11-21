package io.github.sherazi5110.cloudstream

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File

/**
 * Your music: favourites, your own playlists and downloaded songs. Downloads are kept inside the app (its private
 * storage: they don't show up in the phone's files or gallery) and play without internet from the Downloaded list.
 */
object MusicLibrary {
    val favourites = HistoryStore("music_favourites", 2000)
    val downloads = HistoryStore("music_downloads", 2000)

    /** Playlist name → songs (in order). */
    val playlists = mutableStateMapOf<String, List<Music.Track>>().apply { putAll(loadPlaylists()) }
    /** Songs downloading now: url → percent. */
    val downloading = mutableStateMapOf<String, Int>()

    private val prefs get() = App.context.getSharedPreferences("music_playlists", 0)
    private val dir get() = File(App.context.filesDir, "music").apply { mkdirs() }

    private fun Music.Track.entry() = HistoryStore.Entry(url, title, artist, thumbnail)

    fun isFavourite(t: Music.Track) = favourites.contains(t.url)
    fun toggleFavourite(t: Music.Track) { if (isFavourite(t)) favourites.remove(t.url) else favourites.add(t.entry()); App.toast(if (isFavourite(t)) "Added to favourites" else "Removed from favourites") }

    // ---------- playlists ----------

    private fun loadPlaylists(): Map<String, List<Music.Track>> = runCatching {
        val o = JSONObject(App.context.getSharedPreferences("music_playlists", 0).getString("all", "{}")!!)
        o.keys().asSequence().associateWith { name ->
            val a = o.getJSONArray(name)
            (0 until a.length()).map { i -> a.getJSONObject(i).let { Music.Track(it.getString("url"), it.optString("title"), it.optString("artist"), it.optString("image").ifBlank { null }, 0) } }
        }
    }.getOrDefault(emptyMap())

    private fun savePlaylists() {
        val o = JSONObject()
        playlists.forEach { (name, songs) ->
            o.put(name, JSONArray().apply { songs.forEach { put(JSONObject().put("url", it.url).put("title", it.title).put("artist", it.artist).put("image", it.thumbnail ?: "")) } })
        }
        prefs.edit().putString("all", o.toString()).apply()
    }

    fun addToPlaylist(name: String, t: Music.Track) {
        val n = name.trim().ifBlank { return }
        val songs = playlists[n].orEmpty()
        if (songs.any { it.url == t.url }) { App.toast("Already in $n"); return }
        playlists[n] = songs + t.copy(seconds = 0)
        savePlaylists(); App.toast("Added to $n")
    }

    fun createPlaylist(name: String) { val n = name.trim().ifBlank { return }; if (n !in playlists) { playlists[n] = emptyList(); savePlaylists() } }
    fun removeFromPlaylist(name: String, t: Music.Track) { playlists[name] = playlists[name].orEmpty().filterNot { it.url == t.url }; savePlaylists() }
    fun deletePlaylist(name: String) { playlists.remove(name); savePlaylists() }

    // ---------- downloads ----------

    private fun fileOf(url: String) = File(dir, url.hashCode().toUInt().toString(16) + ".m4a")
    /** The downloaded file of a song, if it's downloaded. */
    fun localFile(url: String): File? = fileOf(url).takeIf { it.isFile && it.length() > 0 && downloads.contains(url) }
    fun isDownloaded(t: Music.Track) = localFile(t.url) != null

    fun download(t: Music.Track) {
        if (isDownloaded(t) || t.url in downloading) return
        downloading[t.url] = 0
        App.toast("Downloading \"${t.title}\"")
        App.scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                // M4A (AAC) plays everywhere; from YouTube, or a Piped server when YouTube blocks this connection.
                val audio = Music.audioUrl(t.url, m4a = true)
                val tmp = File(dir, fileOf(t.url).name + ".part")
                val req = okhttp3.Request.Builder().url(audio).header("User-Agent", "Mozilla/5.0").build()
                com.lagradost.cloudstream3.app.baseClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val total = r.body.contentLength()
                    tmp.outputStream().use { out ->
                        r.body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                            while (input.read(buf).also { n = it } > 0) {
                                out.write(buf, 0, n); done += n
                                if (total > 0) withContext(Dispatchers.Main) { downloading[t.url] = (done * 100 / total).toInt() }
                            }
                        }
                    }
                }
                if (!tmp.renameTo(fileOf(t.url))) error("Couldn't save it")
            }
            withContext(Dispatchers.Main) {
                downloading.remove(t.url)
                ok.onSuccess { downloads.add(t.entry()); App.toast("Downloaded \"${t.title}\"") }
                    .onFailure { App.toast("Couldn't download \"${t.title}\": ${it.message}") }
            }
        }
    }

    fun deleteDownload(t: Music.Track) { fileOf(t.url).delete(); downloads.remove(t.url); App.toast("Download removed") }
}
