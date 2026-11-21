package io.github.sherazi5110.cloudstream

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Two-way WeTrakr sync. Playback already reports to WeTrakr (scrobble); this adds the other way: what you watched
 * anywhere else that uses WeTrakr (other apps, add-ons, the website) shows as watched here too, and marking something
 * watched here by hand is sent there. WeTrakr's watch history is read in full once, then only what's new.
 *
 * Titles are matched by their TMDB / IMDb ids (and season and episode numbers), which is how every app on WeTrakr
 * names them, so a show watched in another app matches whichever extension it's opened from here.
 */
object WeTrakrSync {
    private val prefs get() = App.context.getSharedPreferences(Profiles.file("wetrakr_sync"), 0)
    private val lock = Mutex()
    @Volatile private var movies: MutableSet<String>? = null
    @Volatile private var episodes: MutableSet<String>? = null

    private fun loadSet(key: String): MutableSet<String> = prefs.getStringSet(key, emptySet())!!.toMutableSet()

    private fun movies() = movies ?: loadSet("movies").also { movies = it }
    private fun episodes() = episodes ?: loadSet("episodes").also { episodes = it }

    /** Keys for one title: by TMDB and by IMDb id, whichever is known. */
    internal fun keys(tmdb: Int?, imdb: String?): List<String> =
        listOfNotNull(tmdb?.let { "t$it" }, imdb?.takeIf { it.startsWith("tt") }?.let { "i$it" })

    fun movieWatched(tmdb: Int?, imdb: String?) = keys(tmdb, imdb).any { it in movies() }
    fun episodeWatched(tmdb: Int?, imdb: String?, season: Int, episode: Int) =
        keys(tmdb, imdb).any { "$it:$season:$episode" in episodes() }

    /** Reads WeTrakr's watch history: everything the first time, then the plays added since the last time. */
    suspend fun pull(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!WeTrakr.available || !WeTrakr.connected) return@withContext
        lock.withLock {
            val last = prefs.getLong("pulled_at", 0L)
            if (!force && System.currentTimeMillis() - last < 2 * 60_000L) return@withLock
            val started = System.currentTimeMillis()
            // Plays added a moment before the previous read could be missed by clock differences: overlap by an hour.
            val since = if (last == 0L) null else java.time.Instant.ofEpochMilli(last - 3_600_000L).toString()
            val m = movies(); val e = episodes()
            for (target in listOf("movies", "episodes")) {
                var page = 1
                while (page < 200) {
                    val q = "compact=true&limit=5000&page=$page" + (since?.let { "&from_date=$it" } ?: "")
                    val (code, body) = WeTrakr.get("/sync/tracking/watched/history/$target?$q") ?: return@withLock
                    if (code !in 200..299) return@withLock
                    val rows = runCatching { JSONArray(body) }.getOrNull() ?: break
                    for (i in 0 until rows.length()) {
                        val o = rows.optJSONObject(i) ?: continue
                        if (target == "movies") keys(o.idsTmdb("ids"), o.idsImdb("ids")).forEach { m += it }
                        else {
                            val s = o.optInt("season_number", -1); val n = o.optInt("number", -1)
                            if (s < 0 || n < 0) continue
                            keys(o.idsTmdb("show_ids"), o.idsImdb("show_ids")).forEach { e += "$it:$s:$n" }
                        }
                    }
                    if (rows.length() < 5000) break
                    page++
                }
            }
            prefs.edit().putStringSet("movies", m.toSet()).putStringSet("episodes", e.toSet()).putLong("pulled_at", started).apply()
            Library.notifyChanged()
        }
    }

    private fun JSONObject.idsTmdb(key: String) = optJSONObject(key)?.optInt("tmdb", 0)?.takeIf { it > 0 }
    private fun JSONObject.idsImdb(key: String) = optJSONObject(key)?.optString("imdb")?.takeIf { it.startsWith("tt") }

    /**
     * Marks here what WeTrakr says was watched, for a title being opened: the movie, or each episode by its season
     * and number. Returns how many were marked.
     */
    suspend fun apply(apiName: String, r: LoadResponse, playables: List<Playable>): Int {
        if (!WeTrakr.available || !WeTrakr.connected) return 0
        pull()
        val info = runCatching { Metadata.details(r) }.getOrNull()
        val tmdb = info?.tmdbId
        val imdb = info?.imdbId ?: runCatching { r.getImdbIdSafe() }.getOrNull()
        if (tmdb == null && imdb == null) return 0
        val isMovie = r.type == TvType.Movie || r.type == TvType.AnimeMovie || playables.all { it.episode == null }
        val todo = playables.filter { p ->
            !Library.isWatched(apiName, p.data) && if (isMovie) movieWatched(tmdb, imdb)
            else p.episode != null && episodeWatched(tmdb, imdb, p.season ?: 1, p.episode)
        }
        if (todo.isNotEmpty()) withContext(Dispatchers.Main) {
            Library.markWatched(apiName, r.url, r.name, r.posterUrl, todo.map { it.data to it.label })
        }
        return todo.size
    }

    /** Sends titles marked watched by hand here to WeTrakr (an already watched title doesn't get a second play). */
    fun push(r: LoadResponse, playables: List<Playable>) {
        if (!WeTrakr.available || !WeTrakr.connected || playables.isEmpty()) return
        App.scope.launch(Dispatchers.IO) {
            runCatching {
                val info = Metadata.details(r)
                val tmdb = info?.tmdbId
                val imdb = info?.imdbId ?: r.getImdbIdSafe()
                val ids = JSONObject().apply { tmdb?.let { put("tmdb", it) }; imdb?.let { put("imdb", it) } }
                if (ids.length() == 0) return@runCatching
                val isMovie = r.type == TvType.Movie || r.type == TvType.AnimeMovie || playables.all { it.episode == null }
                val body = JSONObject().put("allow_rewatch", false)
                if (isMovie) {
                    body.put("movies", JSONArray().put(JSONObject().put("ids", ids).put("status", "watched")))
                    synchronized(this@WeTrakrSync) { keys(tmdb, imdb).forEach { movies() += it } }
                } else {
                    val seasons = JSONArray()
                    playables.filter { it.episode != null }.groupBy { it.season ?: 1 }.forEach { (s, eps) ->
                        seasons.put(JSONObject().put("number", s).put("episodes", JSONArray().apply {
                            eps.forEach { put(JSONObject().put("number", it.episode).put("status", "watched")) }
                        }))
                        eps.forEach { ep -> keys(tmdb, imdb).forEach { k -> episodes() += "$k:$s:${ep.episode}" } }
                    }
                    body.put("shows", JSONArray().put(JSONObject().put("ids", ids).put("seasons", seasons)))
                }
                WeTrakr.postAuthed("/sync/tracking", body)
            }
        }
    }

    /** Keeps pulling every 30 minutes while the app runs. */
    fun start() {
        App.scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { pull(force = true) }
                delay(30 * 60_000L)
            }
        }
    }

    fun forget() { movies = null; episodes = null; prefs.edit().clear().apply() }

    private fun LoadResponse.getImdbIdSafe(): String? = runCatching { getImdbId() }.getOrNull()?.takeIf { it.startsWith("tt") }
}
