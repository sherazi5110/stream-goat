package io.github.sherazi5110.cloudstream

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Sync with WeTrakr (wetrakr.com), a free movie and TV tracker: sign in with a code (works on TVs), then what you
 * watch shows as "now watching" there and is marked watched once you've seen 80% of it. Each profile connects its
 * own account. Only the sign-in tokens are kept on the device, never a password.
 * API: api.wetrakr.com (device sign-in under /oauth/device, playback under /scrobble).
 */
object WeTrakr {
    private val api get() = System.getProperty("cs.wetrakr.api") ?: "https://api.wetrakr.com"
    /** The Stream Goat app's key on wetrakr.com (GitHub secret WETRAKR_CLIENT_ID; empty in builds without it). */
    val clientId: String get() = System.getProperty("cs.wetrakr.client") ?: BuildConfig.WETRAKR_CLIENT_ID
    val available: Boolean get() = clientId.isNotBlank()

    private val prefs get() = App.context.getSharedPreferences(Profiles.file("wetrakr"), 0)
    val connected: Boolean get() = prefs.getString("refresh", null) != null
    /** Share what's playing now (pause and progress); off = only mark titles watched when they finish. */
    var shareNowPlaying: Boolean
        get() = prefs.getString("now_playing", null) != "false"
        set(v) { prefs.edit().putString("now_playing", v.toString()).apply() }

    private val json = "application/json".toMediaType()
    /** WeTrakr's edge refuses HTTP libraries' default User-Agent (Cloudflare 1010): the app names itself. */
    private val userAgent by lazy { "StreamGoat/" + runCatching { App.context.packageManager.getPackageInfo(App.context.packageName, 0).versionName }.getOrNull().orEmpty() + " (+https://github.com/sherazi5110/stream-goat)" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tokenLock = Mutex()

    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val expiresIn: Int, val interval: Int)

    /** Thrown with WeTrakr's own words ("The code expired, start again."). */
    class Failure(message: String) : Exception(message)

    private fun post(path: String, body: JSONObject, token: String? = null): Pair<Int, JSONObject> {
        val request = okhttp3.Request.Builder().url(api + path)
            .header("Content-Type", "application/json")
            .header("wetrakr-api-version", "1")
            .header("wetrakr-api-key", clientId)
            .header("User-Agent", userAgent)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .post(body.toString().toRequestBody(json)).build()
        app.baseClient.newCall(request).execute().use { r ->
            val text = r.body.string()
            return r.code to (runCatching { JSONObject(text) }.getOrNull() ?: JSONObject())
        }
    }

    /** An authorised GET (the sync reads): status and body text, or null when not signed in or offline. */
    internal suspend fun get(path: String): Pair<Int, String>? {
        val token = token() ?: return null
        val request = okhttp3.Request.Builder().url(api + path)
            .header("wetrakr-api-version", "1")
            .header("wetrakr-api-key", clientId)
            .header("User-Agent", userAgent)
            .header("Authorization", "Bearer $token").get().build()
        return runCatching { app.baseClient.newCall(request).execute().use { r -> r.code to r.body.string() } }.getOrNull()
    }

    /** An authorised POST (sync writes). */
    internal suspend fun postAuthed(path: String, body: JSONObject): Int? {
        val token = token() ?: return null
        return runCatching { post(path, body, token).first }.getOrNull()
    }

    /** Step 1: a code for the person to enter at [DeviceCode.url] (on any phone or computer). */
    suspend fun start(): DeviceCode = withContext(Dispatchers.IO) {
        if (!available) throw Failure("WeTrakr isn't set up in this build yet")
        val (code, o) = post("/oauth/device/code", JSONObject().put("client_id", clientId))
        if (code != 200) throw Failure(o.optString("message").ifBlank { "WeTrakr answered $code" })
        DeviceCode(o.getString("device_code"), o.getString("user_code"), o.optString("verification_url", "https://wetrakr.com/activate"),
            o.optInt("expires_in", 600), o.optInt("interval", 5).coerceAtLeast(2))
    }

    /** Step 2, every [DeviceCode.interval] seconds: true once approved (tokens saved), false while pending. */
    suspend fun poll(d: DeviceCode): Boolean = withContext(Dispatchers.IO) {
        val (code, o) = post("/oauth/device/token", JSONObject().put("code", d.deviceCode).put("client_id", clientId))
        when {
            code == 200 -> { save(o); true }
            code == 429 || (code == 400 && o.optString("error") == "authorization_pending") -> false
            else -> throw Failure(o.optString("message").ifBlank { "WeTrakr answered $code" })
        }
    }

    private fun save(o: JSONObject) {
        val refresh = o.optString("new_refresh_token").ifBlank { o.optString("refresh_token") }
        prefs.edit().putString("access", o.getString("access_token")).putString("refresh", refresh)
            .putLong("expires", System.currentTimeMillis() + o.optLong("expires_in", 604800) * 1000).apply()
    }

    /** A valid access token, renewed a day before it runs out (they last 7 days, the refresh token 180). */
    private suspend fun token(): String? = tokenLock.withLock {
        val refresh = prefs.getString("refresh", null) ?: return null
        val access = prefs.getString("access", null)
        if (access != null && prefs.getLong("expires", 0) - System.currentTimeMillis() > 24 * 3600_000L) return access
        val (code, o) = post("/oauth/token/refresh", JSONObject().put("refresh_token", refresh))
        when (code) {
            200 -> { save(o); o.getString("access_token") }
            401 -> { prefs.edit().clear().apply(); null } // revoked or expired: sign in again
            else -> access
        }
    }

    /** Signs out here and ends the session on WeTrakr. */
    fun disconnect() {
        val refresh = prefs.getString("refresh", null)
        val access = prefs.getString("access", null)
        prefs.edit().remove("access").remove("refresh").remove("expires").apply()
        WeTrakrSync.forget()
        if (refresh != null) scope.launch { runCatching { post("/oauth/logout", JSONObject().put("refresh_token", refresh), access) } }
    }

    // ---------- scrobbling ----------

    /** What WeTrakr needs to know which title this is: ids when known, else title and year. */
    internal fun media(r: LoadResponse, season: Int?, episode: Int?, tmdb: Int?, imdb: String?, progress: Double): JSONObject {
        val isMovie = r.type == TvType.Movie || r.type == TvType.AnimeMovie || (season == null && episode == null)
        return media(r.name, r.year, isMovie, season, episode, tmdb, imdb, progress)
    }

    /** The scrobble body: movie, or show plus episode, with ids when known, and the progress (0-100). */
    internal fun media(title: String, year: Int?, isMovie: Boolean, season: Int?, episode: Int?, tmdb: Int?, imdb: String?, progress: Double): JSONObject {
        val ids = JSONObject().apply { tmdb?.let { put("tmdb", it) }; imdb?.takeIf { it.startsWith("tt") }?.let { put("imdb", it) } }
        val item = JSONObject().put("title", title).apply { year?.let { put("year", it) }; if (ids.length() > 0) put("ids", ids) }
        return JSONObject().put("progress", (progress * 10).toInt() / 10.0).put("app_version", "Stream Goat " + runCatching {
            App.context.packageManager.getPackageInfo(App.context.packageName, 0).versionName
        }.getOrDefault("")).apply {
            if (isMovie) put("movie", item)
            else { put("show", item); put("episode", JSONObject().put("season", season ?: 1).put("number", episode ?: 1)) }
        }
    }

    /**
     * One playback, reported to WeTrakr: [playing] at the start, on resume and every few minutes; [paused];
     * [stopped] when the player closes (marked watched at 80% or more). Nothing is sent when not connected.
     */
    class Session(private val r: LoadResponse, private val season: Int?, private val episode: Int?) {
        private var ids: Pair<Int?, String?>? = null
        private var lastSent = 0L
        private var stopped = false

        private suspend fun ids(): Pair<Int?, String?> = ids ?: runCatching { Metadata.details(r) }.getOrNull()
            .let { (it?.tmdbId to (it?.imdbId ?: imdbFromResponse())) }.also { ids = it }

        private fun imdbFromResponse(): String? = runCatching { r.getImdbId() }.getOrNull()?.takeIf { it.startsWith("tt") }

        private fun send(action: String, progress: Double) {
            if (!connected || stopped) return
            if (action == "stop") stopped = true
            lastSent = System.currentTimeMillis()
            scope.launch {
                runCatching {
                    val token = token() ?: return@launch
                    val (tmdb, imdb) = ids()
                    val (code, o) = post("/scrobble/$action", media(r, season, episode, tmdb, imdb, progress.coerceIn(0.0, 100.0)), token)
                    if (code >= 400) android.util.Log.w("wetrakr", "$action: $code ${o.optString("message")}")
                    else if (action == "stop" && o.optString("action") == "scrobble") GlitchReport.step("WeTrakr: marked ${r.name} watched")
                }.onFailure { android.util.Log.w("wetrakr", "$action failed: $it") }
            }
        }

        fun playing(progress: Double) { if (shareNowPlaying) send("start", progress) }
        fun paused(progress: Double) { if (shareNowPlaying) send("pause", progress) }
        /** Every few minutes while playing, so WeTrakr's progress stays close. */
        fun tick(progress: Double) { if (shareNowPlaying && System.currentTimeMillis() - lastSent > 5 * 60_000L) send("start", progress) }
        fun stopped(progress: Double) = send("stop", progress)
    }
}
