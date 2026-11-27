package io.github.sherazi5110.cloudstream

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * Live TV: channel lists from IPTV sources, kept apart from Home and Search.
 *  - M3U playlists (by address or as a file), with an optional XMLTV guide;
 *  - Xtream Codes logins (player_api.php), the format most IPTV services use;
 *  - Stremio (and Nuvio) add-ons: their "tv"/"channel" catalogs. Add-ons installed on the
 *    Extensions page that offer TV channels show up here automatically.
 * Channels play in the built-in player through [LiveApi].
 */
object LiveTv {
    enum class Kind(val label: String) { M3U("M3U playlist"), XTREAM("Xtream Codes"), STREMIO("Stremio add-on") }

    data class Source(
        val id: String, val kind: Kind, val name: String, val url: String,
        val epgUrl: String? = null, val user: String? = null, val pass: String? = null, val enabled: Boolean = true,
    )

    data class Channel(
        val key: String, val source: String, val name: String, val logo: String?, val group: String?,
        val url: String, val headers: Map<String, String> = emptyMap(), val epgId: String? = null,
        val country: String? = null, val number: Int = 0,
        /** Marked in the playlist as playing only in its own country (Free-TV's Ⓖ, iptv-org's "[Geo-blocked]"). */
        val regionLocked: Boolean = false,
        /** Languages the playlist gives (tvg-language="Hindi;English"); see [languagesOf] for iptv-org's too. */
        val languages: List<String> = emptyList(),
    ) {
        /** Every group the playlist puts it in: group-title="Animation;Kids" is in both. */
        val groups: List<String> get() = group?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        /** Its groups as shown: "Animation · Kids". */
        val groupLabel: String? get() = groups.joinToString(" · ").ifEmpty { null }
    }

    data class Programme(val start: Long, val stop: Long, val title: String, val description: String?)

    private val prefs get() = App.context.getSharedPreferences("livetv_sources", 0)
    /** Favourites and recently watched belong to the profile. */
    private val mine get() = App.context.getSharedPreferences(Profiles.file("livetv"), 0)
    private val cacheDir get() = File(App.context.cacheDir, "livetv").apply { mkdirs() }
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun onChange(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun changed() = listeners.forEach { it() }

    // ---------- sources ----------

    /** Sources added on the Live TV sources page, plus Stremio add-ons that offer TV catalogs. */
    fun sources(): List<Source> = saved() + StremioAddons.all().filter { a -> a.manifest.catalogs.any { isTvType(it.type) } }.map { a ->
        Source("stremio:" + a.manifest.id, Kind.STREMIO, a.manifest.name, a.url, enabled = a.enabled)
    }

    /**
     * Playlists a new install starts with (only when no Live TV source was ever set up): public lists that are kept
     * up to date on GitHub. iptv-org for this device's country and the whole world (updated daily), and Free-TV's
     * hand-checked list of free channels. They can be removed in Settings → Live TV like any other.
     */
    private fun seedDefaults() {
        if (prefs.contains("sources")) return
        val country = java.util.Locale.getDefault().country.takeIf { it.length == 2 }?.lowercase()
        val list = buildList {
            if (country != null) add(Source(newId(), Kind.M3U, "${java.util.Locale("", country).displayCountry} (iptv-org)", iptvOrgCountryUrl(country), null, null, null, true))
            add(Source(newId(), Kind.M3U, "All countries (iptv-org)", "https://iptv-org.github.io/iptv/index.m3u", null, null, null, true))
            add(Source(newId(), Kind.M3U, "Free-TV", "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8", null, null, null, true))
        }
        save(list)
    }

    private fun newId() = java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    fun saved(): List<Source> {
        seedDefaults()
        val arr = runCatching { JSONArray(prefs.getString("sources", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            runCatching {
                Source(o.getString("id"), Kind.valueOf(o.getString("kind")), o.getString("name"), o.getString("url"),
                    o.optString("epg").ifBlank { null }, o.optString("user").ifBlank { null }, o.optString("pass").ifBlank { null },
                    o.optBoolean("enabled", true))
            }.getOrNull()
        }
    }

    private fun save(list: List<Source>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("kind", s.kind.name).put("name", s.name).put("url", s.url)
                .put("epg", s.epgUrl ?: "").put("user", s.user ?: "").put("pass", s.pass ?: "").put("enabled", s.enabled))
        }
        prefs.edit().putString("sources", arr.toString()).apply()
        synchronized(channels) { channels.clear() }
        changed()
    }

    /** Adds an M3U or Xtream source after checking it answers; returns it with its channel count. */
    suspend fun add(kind: Kind, url: String, name: String? = null, epgUrl: String? = null, user: String? = null, pass: String? = null): Pair<Source, Int> {
        if (kind == Kind.STREMIO) {
            // A TV add-on is an ordinary Stremio add-on; its TV catalogs are routed here.
            val addon = StremioAddons.install(url)
            if (addon.manifest.catalogs.none { isTvType(it.type) }) throw IllegalArgumentException("${addon.manifest.name} has no TV channel catalogs (it was added to Extensions → Stremio add-ons)")
            val source = sources().first { it.id == "stremio:" + addon.manifest.id }
            return source to load(source, force = true).size
        }
        val clean = rawGithub(url.trim().trimEnd('/'))
        if (!clean.startsWith("http://") && !clean.startsWith("https://") && !clean.startsWith("file:")) throw IllegalArgumentException("Enter an address starting with http:// or https://")
        val id = MessageDigest.getInstance("SHA-1").digest("$kind|$clean|$user".toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        val label = name?.takeIf { it.isNotBlank() }
            ?: if (clean.startsWith("file:")) File(java.net.URI(clean)).nameWithoutExtension else java.net.URI(clean).host?.removePrefix("www.") ?: "Playlist"
        val source = Source(id, kind, label, clean, epgUrl?.trim()?.ifBlank { null }, user?.trim(), pass)
        val list = fetch(source)
        if (list.isEmpty()) throw IllegalArgumentException("No channels found at that address")
        save(saved().filterNot { it.id == id } + source)
        store(source, list)
        return source to list.size
    }

    fun remove(id: String) {
        if (id.startsWith("stremio:")) StremioAddons.all().firstOrNull { "stremio:" + it.manifest.id == id }?.let { StremioAddons.remove(it.url) }
        else save(saved().filterNot { it.id == id })
        File(cacheDir, "$id.json").delete()
        changed()
    }

    fun setEnabled(id: String, on: Boolean) {
        if (id.startsWith("stremio:")) StremioAddons.all().firstOrNull { "stremio:" + it.manifest.id == id }?.let { StremioAddons.setEnabled(it.url, on) }
        else save(saved().map { if (it.id == id) it.copy(enabled = on) else it })
        changed()
    }

    var refreshDaily: Boolean
        get() = prefs.getString("refresh_daily", null) != "false"
        set(v) { prefs.edit().putString("refresh_daily", v.toString()).apply() }

    /**
     * Opt-in (off by default): a playlist channel whose secure (https) address fails, for example because its
     * server's certificate has expired, is tried once more over plain http. Never for logins such as Xtream,
     * whose addresses carry the password.
     */
    var retryPlain: Boolean
        get() = prefs.getString("retry_plain", null) == "true"
        set(v) { prefs.edit().putString("retry_plain", v.toString()).apply() }

    /**
     * Play channels whose server's security certificate has expired (but is otherwise genuine): on by default, since
     * public lists have many. Only for Live TV streams, never logins.
     */
    var allowExpired: Boolean
        get() = prefs.getString("allow_expired", null) != "false"
        set(v) { prefs.edit().putString("allow_expired", v.toString()).apply() }

    /** Whether [c] gets the plain-http retry (the setting is on, it's https, and it comes from an M3U playlist). */
    fun plainRetry(c: Channel): Boolean = retryPlain && c.url.startsWith("https://") && saved().firstOrNull { it.id == c.source }?.kind == Kind.M3U

    /**
     * Why a live stream won't open, in a few words ("its server's security certificate has expired"), from one
     * quick request to its address; null when the address answers normally (then it's the stream itself).
     */
    suspend fun diagnose(url: String, headers: Map<String, String> = emptyMap()): String? = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext null
        val client = app.baseClient.newBuilder().callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build().let { if (allowExpired) ExpiredCertTrust.client(it) else it }
        val request = okhttp3.Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> runCatching { header(k, v) } }
            if (headers.keys.none { it.equals("User-Agent", true) }) header("User-Agent", com.lagradost.cloudstream3.USER_AGENT)
        }.build()
        try {
            client.newCall(request).execute().use { r ->
                when (r.code) {
                    in 200..399 -> null
                    401, 403, 451 -> "its server refused to send it (HTTP ${r.code}), which usually means it only plays in its own country"
                    404, 410 -> "its stream address no longer exists (HTTP ${r.code})"
                    else -> "its server answered with an error (HTTP ${r.code})"
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val chain = generateSequence(e) { it.cause }.take(8).toList()
            when {
                chain.any { it is java.security.cert.CertificateExpiredException || it.message.orEmpty().contains("expired", true) } ->
                    "its server's security certificate has expired"
                chain.any { it is javax.net.ssl.SSLPeerUnverifiedException } -> "its server's security certificate is made out for a different address"
                chain.any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException } -> "its server's secure connection is broken"
                chain.any { it is java.net.UnknownHostException } -> "its server's address no longer exists"
                chain.any { it is java.net.ConnectException } -> "its server refuses connections"
                chain.any { it is java.io.InterruptedIOException } -> "its server isn't answering"
                else -> "its server can't be reached (${e.javaClass.simpleName})"
            }
        }
    }

    /** What to tell the viewer when channel [name] (address [url]) doesn't play. */
    suspend fun failureMessage(name: String, url: String, headers: Map<String, String> = emptyMap(), plainTried: Boolean = false): String {
        val why = diagnose(url, headers) ?: return "$name isn't playing right now. The channel may be off the air, or only available in its own country."
        val secure = "certificate" in why || "secure connection" in why
        val hint = when {
            secure && plainTried -> " Its unencrypted address didn't work either."
            secure && !retryPlain && url.startsWith("https://") -> " Settings → Live TV → Retry without encryption may make it play."
            else -> ""
        }
        return "$name can't play: $why.$hint"
    }

    /** Kids profiles see Live TV only when allowed, and then only channels in child-friendly groups. */
    var allowKids: Boolean
        get() = prefs.getString("allow_kids", null) == "true"
        set(v) { prefs.edit().putString("allow_kids", v.toString()).apply(); changed() }

    private val kidsGroups = Regex("kid|child|cartoon|anim|family|junior|enfant|infantil|niñ|kinder", RegexOption.IGNORE_CASE)

    /** Playlist groups with adult channels, hidden unless adult content is turned on. */
    private val adultGroups = Regex("xxx|adult|18\\+|porn|erotic", RegexOption.IGNORE_CASE)

    val availableToProfile: Boolean get() = !Profiles.isKids || allowKids

    fun isTvType(type: String) = type == "tv" || type == "channel"

    // ---------- channels ----------

    private val channels = mutableMapOf<String, List<Channel>>()

    /** All channels of the enabled sources (cached on disk; refreshed once a day when allowed). */
    suspend fun channels(sourceId: String? = null, force: Boolean = false): List<Channel> = withContext(Dispatchers.IO) {
        val chosen = sources().filter { it.enabled && (sourceId == null || it.id == sourceId) }
        val all = coroutineScope { chosen.map { s -> async { runCatching { load(s, force) }.onFailure { android.util.Log.w("livetv", "${s.name}: $it") }.getOrDefault(emptyList()) } }.awaitAll() }.flatten()
        when {
            Profiles.isKids -> all.filter { kidsGroups.containsMatchIn(it.group.orEmpty()) || kidsGroups.containsMatchIn(it.name) }
            !ContentFilter.nsfwAllowed -> all.filter { !adultGroups.containsMatchIn(it.group.orEmpty()) }
            else -> all
        }
    }

    private suspend fun load(source: Source, force: Boolean): List<Channel> {
        if (!force) synchronized(channels) { channels[source.id] }?.let { return it }
        val file = File(cacheDir, "${source.id}.json")
        val fresh = file.isFile && (!refreshDaily || System.currentTimeMillis() - file.lastModified() < 24 * 3600_000L)
        if (!force && fresh) readCache(file)?.let { list -> synchronized(channels) { channels[source.id] = list }; return list }
        val list = runCatching { fetch(source) }.getOrElse { e ->
            // Offline or the server is down: keep showing the last list.
            readCache(file)?.also { android.util.Log.w("livetv", "${source.name}: $e (using cached list)") } ?: throw e
        }
        store(source, list)
        return list
    }

    private fun store(source: Source, list: List<Channel>) {
        synchronized(channels) { channels[source.id] = list }
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().put("k", c.key).put("s", c.source).put("n", c.name).put("l", c.logo ?: "").put("g", c.group ?: "")
                .put("u", c.url).put("h", JSONObject(c.headers)).put("e", c.epgId ?: "").put("c", c.country ?: "").put("o", c.number).put("r", c.regionLocked))
        }
        runCatching { File(cacheDir, "${source.id}.json").writeText(arr.toString()) }
    }

    private fun readCache(file: File): List<Channel>? = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val h = o.optJSONObject("h")
            Channel(o.getString("k"), o.getString("s"), o.getString("n"), o.optString("l").ifBlank { null }, o.optString("g").ifBlank { null },
                o.getString("u"), h.toStringMap(), o.optString("e").ifBlank { null },
                o.optString("c").ifBlank { null }, o.optInt("o"), o.optBoolean("r"))
        }
    }.getOrNull()

    private suspend fun fetch(source: Source): List<Channel> = when (source.kind) {
        Kind.M3U -> text(source.url).let { body ->
            val head = body.trimStart().take(200).lowercase()
            if (head.startsWith("<!doctype html") || head.startsWith("<html") || (head.startsWith("<") && "#extinf" !in body.take(20000).lowercase()))
                throw IllegalArgumentException("That address is a web page, not a playlist. Use the playlist file's own address (it usually ends in .m3u or .m3u8; on GitHub, the \"Raw\" button).")
            parseM3u(source.id, body)
        }
        Kind.XTREAM -> xtream(source)
        Kind.STREMIO -> stremio(source)
    }

    /** GitHub page links to a file ("…/blob/…") become the file itself (raw.githubusercontent.com). */
    fun rawGithub(url: String): String {
        val m = Regex("^https?://github\\.com/([^/]+)/([^/]+)/blob/(.+)$").find(url) ?: return url
        return "https://raw.githubusercontent.com/${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}"
    }

    private fun text(url: String): String {
        if (url.startsWith("file:")) {
            val bytes = File(java.net.URI(url)).readBytes()
            return if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(bytes.inputStream()).readBytes().decodeToString() else bytes.decodeToString()
        }
        val r = app.baseClient.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", com.lagradost.cloudstream3.USER_AGENT).build()).execute()
        r.use {
            if (!it.isSuccessful) throw java.io.IOException("HTTP ${it.code}")
            val bytes = it.body.bytes()
            return if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(bytes.inputStream()).readBytes().decodeToString() else bytes.decodeToString()
        }
    }

    // ---------- languages (the TV guide's Language filter) ----------

    /** iptv-org's whole list grouped by language: each stream's languages, by its address. */
    private const val LANGUAGE_LIST = "https://iptv-org.github.io/iptv/index.language.m3u"
    @Volatile private var languageIndex: Map<String, List<String>> = emptyMap()
    private val languageFile get() = File(App.context.filesDir, "livetv_languages.tsv")

    /** A channel's languages: the playlist's own, else iptv-org's for the same stream ("Undefined" left out). */
    fun languagesOf(c: Channel): List<String> = c.languages.ifEmpty { languageIndex[c.url].orEmpty() }

    /** Loads iptv-org's language list (kept for a day; works offline from the copy). */
    suspend fun loadLanguages() = withContext(Dispatchers.IO) {
        val f = languageFile
        if (languageIndex.isEmpty() && f.isFile) languageIndex = runCatching { parseLanguageTsv(f.readText()) }.getOrDefault(emptyMap())
        if (f.isFile && System.currentTimeMillis() - f.lastModified() < 24 * 3600_000L) return@withContext
        runCatching {
            val map = parseLanguageM3u(text(LANGUAGE_LIST))
            if (map.isNotEmpty()) {
                languageIndex = map
                f.writeText(map.entries.joinToString("\n") { (u, l) -> u + "\t" + l.joinToString(";") })
                changed()
            }
        }.onFailure { android.util.Log.w("livetv", "languages: $it") }
    }

    /** index.language.m3u: a stream is listed once per language, its group-title the language's name. */
    internal fun parseLanguageM3u(text: String): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        var lang: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", true) -> lang = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1)?.trim()
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val l = lang
                    if (l != null && l.isNotEmpty() && !l.equals("Undefined", true)) out.getOrPut(line) { mutableListOf() }.let { if (l !in it) it += l }
                    lang = null
                }
            }
        }
        return out
    }

    private fun parseLanguageTsv(text: String): Map<String, List<String>> =
        text.lineSequence().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1].split(';') } }.toMap()

    private val circledLetters = Regex("[\\u24B6-\\u24E9]")
    private val brokenChrome = Regex("like Gecko\\) Chrome/(\\d+)")

    /**
     * A channel's name in an #EXTINF line: after the first comma that follows the attributes (names may contain
     * commas, "Love Nature, 4K"), else after the last comma. Lines a converter broke (a quote and comma inside an
     * attribute) still give the name at the end.
     */
    internal fun m3uName(inf: String): String {
        val afterAttributes = inf.indexOf(',', inf.lastIndexOf('"') + 1)
        return (if (afterAttributes >= 0) inf.substring(afterAttributes + 1) else inf.substringAfterLast(",", "")).trim()
    }

    /**
     * The same channel in the user's other lists (the same tvg-id, or the same name without quality tags), tried
     * by the player when this one doesn't play: public lists carry many channels twice, from different servers.
     */
    fun alternatives(c: Channel, max: Int = 4): List<Channel> = alternativesIn(synchronized(channels) { channels.values.flatten() }, c, max)

    internal fun alternativesIn(all: List<Channel>, c: Channel, max: Int = 4): List<Channel> {
        val id = channelId(c.epgId)
        val name = sameName(c.name)
        return all.asSequence()
            .filter { it.url != c.url && it.key != c.key }
            .filter { o ->
                val other = channelId(o.epgId)
                // Both have ids: they decide. Otherwise the names, without quality tags ("(720p)", "HD", "[Not 24/7]").
                if (id != null && other != null) id == other else name.length >= 3 && sameName(o.name) == name
            }
            .distinctBy { it.url }.take(max).toList()
    }

    /**
     * iptv-org ids: "HumTV.pk@SD" and "HumTV.pk@HD" are one channel in two qualities, but "GodStandsTV.pk@Arabic" and
     * "GodStandsTV.pk@Urdu" are two (the part after @ is a feed: a quality, or a language or region).
     */
    private fun channelId(epgId: String?): String? {
        val id = epgId?.trim()?.lowercase()?.ifBlank { null } ?: return null
        val feed = id.substringAfter('@', "")
        return if (feed.isEmpty() || feed in qualityFeeds) id.substringBefore('@') else id
    }
    private val qualityFeeds = setOf("sd", "hd", "fhd", "uhd", "4k", "hq", "lq")

    /** Quality marks only: a country in brackets, "ATV (Pakistan)", is part of the name. */
    private val qualityTags = Regex("\\((?:\\d{3,4}p|hd|sd|fhd|uhd|4k)\\)|\\[[^\\]]*]|\\b(?:hd|sd|fhd|uhd|4k|\\d{3,4}p)\\b")
    private fun sameName(n: String) = n.lowercase().replace(qualityTags, " ").replace(Regex("[^\\p{L}\\p{N}]+"), "")

    /** A source's name, for "Backup from …". */
    fun sourceName(id: String): String? = sources().firstOrNull { it.id == id }?.name

    // ---------- checking channels ----------

    /** What the last check found for a channel: whether it answered, when, and if not, why (a few words). */
    data class Health(val ok: Boolean, val at: Long, val why: String? = null)

    private val healthFile get() = File(cacheDir, "health.json")
    @Volatile private var healthCache: MutableMap<String, Health>? = null

    private fun healthMap(): MutableMap<String, Health> = healthCache ?: synchronized(this) {
        healthCache ?: runCatching {
            val o = JSONObject(healthFile.readText())
            o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Health(it.getBoolean("ok"), it.getLong("at"), it.optString("why").ifBlank { null }) } }
        }.getOrDefault(emptyMap()).toMutableMap().also { healthCache = it }
    }

    /** The last check's result for [c], or null if it hasn't been checked (or not in the last 7 days). */
    fun health(c: Channel): Health? = healthMap()[c.key]?.takeIf { System.currentTimeMillis() - it.at < 7 * 24 * 3600_000L }

    /**
     * The viewer's country (two letters), for region-locked channels: chosen in Settings → Live TV, else the SIM's or
     * mobile network's country, else the phone's region setting.
     */
    var homeCountry: String
        get() = prefs.getString("home_country", null)?.ifBlank { null } ?: detectedCountry()
        set(v) { prefs.edit().putString("home_country", v.uppercase()).apply(); changed() }

    fun detectedCountry(): String = runCatching {
        val tm = App.context.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        listOfNotNull(tm?.simCountryIso, tm?.networkCountryIso).firstOrNull { it.length == 2 }
    }.getOrNull()?.uppercase() ?: java.util.Locale.getDefault().country.uppercase().ifBlank { "US" }

    /** Leave out channels marked as playing only in their own country, unless that's [homeCountry] (on by default). */
    var hideRegionLocked: Boolean
        get() = prefs.getString("hide_region_locked", null) != "false"
        set(v) { prefs.edit().putString("hide_region_locked", v.toString()).apply(); changed() }

    /** Marked region-locked, and not for the viewer's country. */
    fun lockedOut(c: Channel): Boolean = c.regionLocked && !c.country.equals(homeCountry, ignoreCase = true)

    /** A country code in words: "PK" → "Pakistan". */
    fun countryName(code: String): String = java.util.Locale("", code.uppercase()).getDisplayCountry(java.util.Locale.ENGLISH).ifBlank { code.uppercase() }

    /**
     * iptv-org's free channels for one country, instead of its whole list (index.m3u: 10,000+ channels from every
     * country, many of which play only in their own country).
     */
    fun iptvOrgCountryUrl(code: String) = "https://iptv-org.github.io/iptv/countries/${code.lowercase()}.m3u"
    fun isIptvOrgIndex(url: String) = Regex("""iptv-org\.github\.io/iptv/index(\.\w+)?\.m3u""").containsMatchIn(url)

    /** Not checked in the last day: due for the automatic check. */
    fun stale(c: Channel): Boolean = healthMap()[c.key]?.let { System.currentTimeMillis() - it.at > 24 * 3600_000L } ?: true

    /** Check the channels showing in the background when Live TV opens (each at most once a day). On by default. */
    var autoCheck: Boolean
        get() = prefs.getString("auto_check", null) != "false"
        set(v) { prefs.edit().putString("auto_check", v.toString()).apply() }

    /** Leave out channels the last check found not working (on by default). */
    var hideNotWorking: Boolean
        get() = prefs.getString("hide_not_working", null) != "false"
        set(v) { prefs.edit().putString("hide_not_working", v.toString()).apply(); changed() }

    /**
     * Opens every channel's stream (24 at a time) to see which answer: public playlists always carry some dead
     * links. HLS lists are followed into their first quality, since a list often still answers when the video
     * behind it is gone. [onProgress] gets (checked, working) as it goes. Returns how many work.
     */
    suspend fun check(list: List<Channel>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        // 24 at a time with short waits: a public list of 10,000 channels takes minutes, not an hour.
        val client = app.baseClient.newBuilder().connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS).callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
            .let { if (allowExpired) ExpiredCertTrust.client(it) else it }
        val gate = kotlinx.coroutines.sync.Semaphore(24)
        val done = java.util.concurrent.atomic.AtomicInteger()
        val working = java.util.concurrent.atomic.AtomicInteger()
        val results = java.util.concurrent.ConcurrentHashMap<String, Health>()
        try {
            coroutineScope {
                list.filter { webPage(it) == null && it.url.startsWith("http") }.map { c ->
                    async {
                        gate.acquire()
                        try {
                            val why = runCatching { probe(client, c.url, c.headers, depth = 0) }.getOrElse { reason(it) }
                            results[c.key] = Health(why == null, System.currentTimeMillis(), why)
                            if (why == null) working.incrementAndGet()
                        } finally { gate.release() }
                        onProgress(done.incrementAndGet(), working.get())
                    }
                }.awaitAll()
            }
        } finally {
            // Also when stopped half-way: what was checked is kept.
            saveHealth(results)
        }
        working.get()
    }

    private fun saveHealth(results: Map<String, Health>) {
        val map = healthMap()
        synchronized(map) {
            map.putAll(results)
            val o = JSONObject()
            map.forEach { (k, h) -> o.put(k, JSONObject().put("ok", h.ok).put("at", h.at).put("why", h.why ?: "")) }
            runCatching { healthFile.writeText(o.toString()) }
        }
        changed()
    }

    /** A channel that failed to play (and its backups too): counted as not working, like a check would. */
    fun markNotWorking(key: String, why: String?) = saveHealth(mapOf(key to Health(false, System.currentTimeMillis(), why ?: "didn't play")))

    /** A channel that played: counted as working again. */
    fun markWorking(key: String) { if (healthMap()[key]?.ok == false) saveHealth(mapOf(key to Health(true, System.currentTimeMillis()))) }

    /** null when the stream answers like a stream; otherwise why not, in a few words. */
    private fun probe(client: okhttp3.OkHttpClient, url: String, headers: Map<String, String>, depth: Int): String? {
        val request = okhttp3.Request.Builder().url(url).apply {
            header("User-Agent", headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: com.lagradost.cloudstream3.USER_AGENT)
            headers.forEach { (k, v) -> if (!k.equals("User-Agent", true)) header(k, v) }
        }.build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return when (r.code) {
                401, 403 -> "refused (HTTP ${r.code}): blocked here or needs a login"
                404, 410 -> "gone (HTTP ${r.code})"
                else -> "HTTP ${r.code}"
            }
            val buffer = ByteArray(8 * 1024)
            var n = 0
            r.body.byteStream().use { input ->
                while (n < buffer.size) { val got = input.read(buffer, n, buffer.size - n); if (got < 0) break; n += got; if (depth > 0 && n >= 2048) break }
            }
            if (n == 0) return "empty answer"
            val text = String(buffer, 0, n, Charsets.UTF_8)
            val head = text.trimStart().take(64).lowercase()
            val playlist = "#extm3u" in text.take(2048).lowercase()
            if (!playlist && (head.startsWith("<!doctype html") || head.startsWith("<html"))) return "a web page, not a stream"
            if (!playlist || depth > 0) return null
            // A master list: follow the first quality (or the first media file) one level.
            val next = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return "an empty list"
            val nextUrl = r.request.url.resolve(next)?.toString() ?: return null
            if (!(nextUrl.substringBefore('?').endsWith(".m3u8") || "#ext-x-stream-inf" in text.lowercase())) return null
            return probe(client, nextUrl, headers, depth + 1)
        }
    }

    private fun reason(e: Throwable): String = when (val cause = generateSequence(e) { it.cause }.last()) {
        is java.net.UnknownHostException -> "its server wasn't found"
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "no answer"
        is java.net.ConnectException -> "its server refused the connection"
        is javax.net.ssl.SSLException, is java.security.cert.CertificateException -> "security certificate problem"
        else -> cause.message?.take(60) ?: cause.javaClass.simpleName
    }


    /**
     * Channels that are web pages rather than streams (a YouTube or Twitch channel's live page, which
     * playlists such as Free-TV list): the player can't open those, so they open in that service's app or
     * the browser. Returns the service's name, or null for an ordinary stream.
     */
    fun webPage(c: Channel): String? {
        val host = runCatching { java.net.URI(c.url).host?.lowercase() }.getOrNull() ?: return null
        fun on(vararg domains: String) = domains.any { host == it || host.endsWith(".$it") }
        return when {
            on("youtube.com", "youtu.be") -> "YouTube"
            on("twitch.tv") -> "Twitch"
            on("dailymotion.com", "dai.ly") -> "Dailymotion"
            on("facebook.com", "fb.watch") -> "Facebook"
            on("vimeo.com") -> "Vimeo"
            else -> null
        }
    }

    /**
     * How a web channel plays inside the app: in the service's own embed player (the way they let other apps and
     * sites show their streams). [kind]: "twitch" (id = channel name), "youtube-video" (video id), "youtube-channel"
     * (a UC… channel id: its current live stream), "youtube-page" (id = the channel's page, whose live video has to be
     * found first) or "dailymotion" (video id).
     */
    data class WebEmbed(val service: String, val kind: String, val id: String)

    fun webEmbed(c: Channel): WebEmbed? {
        val service = webPage(c) ?: return null
        val uri = runCatching { java.net.URI(c.url) }.getOrNull() ?: return null
        val path = uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
        val query = uri.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val videoId = Regex("^[A-Za-z0-9_-]{11}$")
        return when (service) {
            "Twitch" -> path.firstOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_]{2,40}")) }?.let { WebEmbed(service, "twitch", it.lowercase()) }
            "Dailymotion" -> (if (uri.host.orEmpty().endsWith("dai.ly")) path.firstOrNull() else path.getOrNull(path.indexOf("video") + 1)?.takeIf { "video" in path })
                ?.substringBefore('_')?.let { WebEmbed(service, "dailymotion", it) }
            "YouTube" -> when {
                query["v"]?.matches(videoId) == true -> WebEmbed(service, "youtube-video", query.getValue("v"))
                uri.host.orEmpty().endsWith("youtu.be") && path.firstOrNull()?.matches(videoId) == true -> WebEmbed(service, "youtube-video", path[0])
                path.size >= 2 && path[0] in setOf("embed", "live", "shorts") && path[1].matches(videoId) -> WebEmbed(service, "youtube-video", path[1])
                path.size >= 2 && path[0] == "channel" && path[1].startsWith("UC") -> WebEmbed(service, "youtube-channel", path[1])
                else -> WebEmbed(service, "youtube-page", c.url)
            }
            else -> null
        }
    }

    /** The live video on a YouTube page, from its address, canonical link or og:url (text holding any of them). */
    fun youtubeLiveVideo(text: String): WebEmbed? {
        Regex("(?:watch\\?v=|/live/|/embed/)([A-Za-z0-9_-]{11})").find(text)?.let { return WebEmbed("YouTube", "youtube-video", it.groupValues[1]) }
        Regex("/channel/(UC[A-Za-z0-9_-]{20,})").find(text)?.let { return WebEmbed("YouTube", "youtube-channel", it.groupValues[1]) }
        return null
    }

    /**
     * A full-window page holding the service's embed player, for a page served from [origin] (its address must be
     * a real web address: Twitch checks the page's host against `parent`, YouTube wants a referrer). YouTube errors
     * (embedding turned off, not live) are passed to `StreamGoat.error(code)` when the page provides it.
     */
    fun embedHtml(e: WebEmbed, origin: String): String? {
        val host = runCatching { java.net.URI(origin).host }.getOrNull() ?: return null
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val src = when (e.kind) {
            "twitch" -> "https://player.twitch.tv/?channel=${enc(e.id)}&parent=${enc(host)}&autoplay=true&muted=false"
            "youtube-video" -> "https://www.youtube.com/embed/${enc(e.id)}?autoplay=1&playsinline=1&rel=0&enablejsapi=1&origin=${enc(origin)}"
            "youtube-channel" -> "https://www.youtube.com/embed/live_stream?channel=${enc(e.id)}&autoplay=1&playsinline=1&enablejsapi=1&origin=${enc(origin)}"
            "dailymotion" -> "https://www.dailymotion.com/embed/video/${enc(e.id)}?autoplay=1"
            else -> return null
        }
        val youtube = e.kind.startsWith("youtube")
        return """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="strict-origin-when-cross-origin"><title>Stream Goat</title>
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{position:fixed;inset:0;width:100%;height:100%;border:0}</style></head>
<body><iframe id="p" src="$src" allow="autoplay; encrypted-media; fullscreen; picture-in-picture" allowfullscreen referrerpolicy="strict-origin-when-cross-origin"></iframe>
""" + (if (youtube) """<script>function onYouTubeIframeAPIReady(){new YT.Player('p',{events:{onReady:function(ev){ev.target.playVideo()},onError:function(ev){if(window.StreamGoat)StreamGoat.error(String(ev.data))}}})}</script>
<script src="https://www.youtube.com/iframe_api"></script>
""" else "") + "</body></html>"
    }

    /** iptv-org ids carry the country: "HumTV.pk@SD" → "pk". */
    internal fun countryOfId(id: String?): String? = id?.let { Regex("""\.([a-z]{2})(?:@|$)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }

    /** #EXTM3U playlists: attributes, #EXTVLCOPT headers and the "url|Header=value" form. */
    fun parseM3u(sourceId: String, text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var info: String? = null
        var extGroup: String? = null
        val options = mutableMapOf<String, String>()
        val attr = Regex("([\\w-]+)=\"([^\"]*)\"")
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", true) -> { info = line; options.clear(); extGroup = null }
                line.startsWith("#EXTGRP:", true) -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val opt = line.substringAfter(':')
                    when {
                        opt.startsWith("http-referrer=", true) -> options["Referer"] = opt.substringAfter('=')
                        opt.startsWith("http-user-agent=", true) -> options["User-Agent"] = opt.substringAfter('=')
                        opt.startsWith("http-origin=", true) -> options["Origin"] = opt.substringAfter('=')
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val inf = info ?: continue
                    val attrs = attr.findAll(inf).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val rawName = m3uName(inf).ifBlank { attrs["tvg-name"] ?: "Channel ${out.size + 1}" }
                    // Free-TV marks channels with circled letters (Ⓢ SD, Ⓖ region-locked, Ⓨ YouTube, Ⓣ Twitch): shown as tags instead.
                    val name = rawName.replace(circledLetters, "").replace(Regex("\\s+"), " ").trim().ifBlank { rawName }
                    val locked = 'Ⓖ' in rawName || rawName.contains("geo-blocked", true)
                    val headers = options.toMutableMap()
                    // iptv-org style: the headers as attributes of the #EXTINF line (#EXTVLCOPT lines win).
                    attrs["http-referrer"]?.ifBlank { null }?.let { headers.putIfAbsent("Referer", it) }
                    attrs["http-user-agent"]?.ifBlank { null }?.let { headers.putIfAbsent("User-Agent", it) }
                    attrs["http-origin"]?.ifBlank { null }?.let { headers.putIfAbsent("Origin", it) }
                    // Some converters cut http-user-agent="…(KHTML, like Gecko) Chrome/120…" at its comma, leaving
                    // "like Gecko) Chrome/120… Safari/537.36" in the line: the server wanted desktop Chrome, so ask as it.
                    if ("User-Agent" !in headers) brokenChrome.find(inf)?.let {
                        headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${it.groupValues[1]}.0.0.0 Safari/537.36"
                    }
                    var url = line
                    if ('|' in line) {
                        url = line.substringBefore('|')
                        line.substringAfter('|').split('&').forEach { kv ->
                            val k = kv.substringBefore('=').trim(); val v = java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
                            if (k.isNotEmpty() && v.isNotEmpty()) headers[if (k.equals("referrer", true)) "Referer" else k] = v
                        }
                    }
                    if (url.startsWith("http") || url.startsWith("rtmp") || url.startsWith("rtsp") || url.startsWith("udp")) {
                        out += Channel("$sourceId:${out.size}:${url.hashCode()}", sourceId, name, attrs["tvg-logo"]?.ifBlank { null },
                            (attrs["group-title"] ?: extGroup)?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(";")?.ifBlank { null },
                            url, headers,
                            attrs["tvg-id"]?.ifBlank { null }, (attrs["tvg-country"]?.ifBlank { null } ?: countryOfId(attrs["tvg-id"]))?.uppercase(),
                            attrs["tvg-chno"]?.toIntOrNull() ?: (out.size + 1), locked,
                            attrs["tvg-language"]?.split(';', ',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty())
                    }
                    info = null
                }
            }
        }
        return out
    }

    private fun xtream(s: Source): List<Channel> {
        val auth = "username=${enc(s.user.orEmpty())}&password=${enc(s.pass.orEmpty())}"
        val login = JSONObject(text("${s.url}/player_api.php?$auth"))
        val status = login.optJSONObject("user_info")?.optString("auth")
        if (status == "0") throw IllegalArgumentException("The provider refused the username or password")
        val groups = runCatching { JSONArray(text("${s.url}/player_api.php?$auth&action=get_live_categories")) }.getOrDefault(JSONArray())
        val names = (0 until groups.length()).mapNotNull { groups.optJSONObject(it) }.associate { it.optString("category_id") to it.optString("category_name") }
        val streams = JSONArray(text("${s.url}/player_api.php?$auth&action=get_live_streams"))
        return (0 until streams.length()).mapNotNull { streams.optJSONObject(it) }.map { o ->
            val id = o.optString("stream_id")
            Channel("${s.id}:$id", s.id, o.optString("name"), o.optString("stream_icon").ifBlank { null },
                names[o.optString("category_id")], "${s.url}/live/${s.user}/${s.pass}/$id.m3u8",
                epgId = o.optString("epg_channel_id").ifBlank { null }, number = o.optInt("num"))
        }
    }

    private suspend fun stremio(s: Source): List<Channel> {
        val addon = StremioAddons.all().firstOrNull { "stremio:" + it.manifest.id == s.id } ?: return emptyList()
        val out = mutableListOf<Channel>()
        for (c in addon.manifest.catalogs.filter { isTvType(it.type) && !it.needsExtra }) {
            var skip = 0
            // Pages until a page comes back empty (bounded: a TV catalog rarely needs more).
            for (page in 0 until 20) {
                val extra = if (skip > 0) "/skip=$skip" else ""
                val metas = StremioAddons.json("${addon.base}/catalog/${c.type}/${StremioAddons.encode(c.id)}$extra.json")?.optJSONArray("metas")
                if (metas == null || metas.length() == 0) break
                for (i in 0 until metas.length()) {
                    val m = metas.optJSONObject(i) ?: continue
                    val id = m.optString("id")
                    if (id.isBlank() || out.any { it.key == "${s.id}:$id" }) continue
                    val genre = m.optJSONArray("genres")?.optString(0)?.ifBlank { null } ?: m.optJSONArray("genre")?.optString(0)?.ifBlank { null }
                    out += Channel("${s.id}:$id", s.id, m.optString("name", id), m.optString("logo").ifBlank { m.optString("poster") }.ifBlank { null },
                        genre ?: c.name, "${addon.base}/meta/${c.type}/${StremioAddons.encode(id)}", number = out.size + 1)
                }
                skip += metas.length()
                if (metas.length() < 20) break
            }
        }
        return out
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- guide (XMLTV) ----------

    private val guide = mutableMapOf<String, List<Programme>>()
    private var guideLoaded = mutableSetOf<String>()

    /** Loads the XMLTV guides of the enabled sources (Xtream ones come with the login). */
    suspend fun loadGuides(force: Boolean = false) = withContext(Dispatchers.IO) {
        for (s in sources().filter { it.enabled }) {
            val url = when (s.kind) {
                Kind.M3U -> s.epgUrl
                Kind.XTREAM -> s.epgUrl ?: "${s.url}/xmltv.php?username=${enc(s.user.orEmpty())}&password=${enc(s.pass.orEmpty())}"
                Kind.STREMIO -> null
            } ?: continue
            if (!force && url in guideLoaded) continue
            val file = File(cacheDir, "guide-" + MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).take(6).joinToString("") { "%02x".format(it) } + ".xml")
            runCatching {
                val stale = !file.isFile || System.currentTimeMillis() - file.lastModified() > 12 * 3600_000L
                if (force || stale) {
                    app.baseClient.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", com.lagradost.cloudstream3.USER_AGENT).build()).execute().use { r ->
                        if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                        file.outputStream().use { out -> r.body.byteStream().copyTo(out) }
                    }
                }
                val parsed = parseXmltv(file.inputStream())
                synchronized(guide) { guide.putAll(parsed) }
                guideLoaded += url
            }.onFailure { android.util.Log.w("livetv", "guide $url: $it") }
        }
        changed()
    }

    /** Programmes from 3 hours ago to 2 days ahead, per channel id. Gzipped files are fine. */
    fun parseXmltv(input: InputStream): Map<String, List<Programme>> {
        val stream = java.io.BufferedInputStream(input).let { b ->
            b.mark(2)
            val gz = b.read() == 0x1f && b.read() == 0x8b
            b.reset()
            if (gz) GZIPInputStream(b) else b
        }
        val from = System.currentTimeMillis() - 3 * 3600_000L
        val to = System.currentTimeMillis() + 48 * 3600_000L
        val out = mutableMapOf<String, MutableList<Programme>>()
        // Android's pull parser (no DTDs, no external entities) instead of StAX.
        val r = android.util.Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(stream, null)
        }
        var channel: String? = null; var start = 0L; var stop = 0L
        var title: String? = null; var desc: String? = null; var field: String? = null
        val text = StringBuilder()
        while (true) {
            when (r.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when (r.name) {
                    "programme" -> {
                        channel = r.getAttributeValue(null, "channel")
                        start = xmltvTime(r.getAttributeValue(null, "start")); stop = xmltvTime(r.getAttributeValue(null, "stop"))
                        title = null; desc = null
                    }
                    "title", "desc" -> { field = r.name; text.clear() }
                }
                XmlPullParser.TEXT -> if (field != null) text.append(r.text)
                XmlPullParser.END_TAG -> when (r.name) {
                    "title" -> { if (title == null) title = text.toString().trim(); field = null }
                    "desc" -> { if (desc == null) desc = text.toString().trim(); field = null }
                    "programme" -> {
                        val ch = channel
                        if (ch != null && title != null && stop > from && start < to) out.getOrPut(ch) { mutableListOf() } += Programme(start, stop, title!!, desc)
                        channel = null
                    }
                }
            }
        }
        return out.mapValues { (_, v) -> v.sortedBy { it.start } }
    }

    private val xmltvFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /** "20261029190000 +0100" → epoch millis. */
    fun xmltvTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val parts = s.trim().split(' ')
        val local = LocalDateTime.parse(parts[0].take(14).padEnd(14, '0'), xmltvFormat)
        val offset = parts.getOrNull(1)?.let { runCatching { ZoneOffset.of(it.substring(0, 3) + ":" + it.substring(3, 5)) }.getOrNull() } ?: ZoneOffset.UTC
        return local.toInstant(offset).toEpochMilli()
    }

    /** The guide for [channel] between [from] and [to] (for the TV guide grid); empty when none covers it. */
    fun programmes(channel: Channel, from: Long, to: Long): List<Programme> {
        val list = synchronized(guide) { guide[channel.epgId ?: channel.name] ?: guide[channel.name] } ?: return emptyList()
        return list.filter { it.stop > from && it.start < to }
    }

    /** Whether any guide is loaded at all. */
    fun hasGuide(): Boolean = synchronized(guide) { guide.isNotEmpty() }

    /** What's on now and next on [channel], if a guide covers it. */
    fun nowNext(channel: Channel, at: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val list = synchronized(guide) { guide[channel.epgId ?: channel.name] ?: guide[channel.name] } ?: return null to null
        val i = list.indexOfFirst { at in it.start until it.stop }
        return if (i < 0) null to list.firstOrNull { it.start > at } else list[i] to list.getOrNull(i + 1)
    }

    // ---------- favourites and recently watched (per profile) ----------

    private fun keys(name: String): List<String> = runCatching { JSONArray(mine.getString(name, "[]")) }.getOrDefault(JSONArray())
        .let { a -> (0 until a.length()).map { a.getString(it) } }

    fun favourites(): Set<String> = keys("favourites").toSet()
    fun recent(): List<String> = keys("recent")

    fun toggleFavourite(c: Channel): Boolean {
        val now = favourites().toMutableSet()
        val added = c.key !in now
        if (added) now += c.key else now -= c.key
        mine.edit().putString("favourites", JSONArray(now.toList()).toString()).apply()
        changed()
        return added
    }

    /** Channels hidden from the lists and the guide (a channel's menu: Hide channel). */
    fun hidden(): Set<String> = keys("hidden").toSet()

    fun hide(c: Channel) {
        mine.edit().putString("hidden", JSONArray((hidden() + c.key).toList()).toString()).apply()
        changed()
    }

    fun unhideAll() {
        mine.edit().remove("hidden").apply()
        changed()
    }

    fun watched(c: Channel) {
        val list = (listOf(c.key) + recent().filterNot { it == c.key }).take(30)
        mine.edit().putString("recent", JSONArray(list).toString()).apply()
        changed()
    }
}

/**
 * Plays Live TV channels in the built-in player: a channel's address (and headers) is its
 * data. Stremio TV channels go through their add-on instead ([StremioApi]).
 */
object LiveApi : MainAPI() {
    override var name = "Live TV"
    override var mainUrl = "livetv://"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Live)

    /** A channel as the "url" this API loads: everything needed to play it. */
    fun data(c: LiveTv.Channel) = JSONObject().put("url", c.url).put("headers", JSONObject(c.headers)).put("name", c.name).put("logo", c.logo ?: "").put("key", c.key)
        .apply {
            if (LiveTv.plainRetry(c)) put("plain", true)
            val alts = LiveTv.alternatives(c)
            if (alts.isNotEmpty()) put("alts", org.json.JSONArray(alts.map { a ->
                JSONObject().put("url", a.url).put("headers", JSONObject(a.headers)).put("from", LiveTv.sourceName(a.source) ?: "another list")
            }))
        }.toString()

    /** The link name of the retry that accepts an expired certificate (see [LiveTv.allowExpired]). */
    const val EXPIRED = "Live (expired certificate)"
    /** Marks that link's address apart from the first try's (the player tries each address once). */
    const val EXPIRED_MARK = "#sg-expired-certificate"

    /** The link name of the plain-http retry (see [LiveTv.retryPlain]). */
    const val PLAIN = "Live (unencrypted)"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = null

    override suspend fun load(url: String): LoadResponse? {
        val o = JSONObject(url)
        return newLiveStreamLoadResponse(o.optString("name", "Live TV"), url, url) {
            posterUrl = o.optString("logo").ifBlank { null }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val o = JSONObject(data)
        val url = o.getString("url")
        val h = o.optJSONObject("headers")
        val headers = h.toStringMap()
        val type = if (url.substringBefore('?').endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(newExtractorLink(o.optString("name", "Live TV"), "Live", url, type) {
            this.headers = headers
            headers["Referer"]?.let { referer = it }
        })
        // A server whose certificate is genuine but out of date (common in public lists): tried when the first fails.
        if (LiveTv.allowExpired && url.startsWith("https://")) callback(newExtractorLink(o.optString("name", "Live TV"), EXPIRED, url + EXPIRED_MARK, type) {
            this.headers = headers
            headers["Referer"]?.let { referer = it }
        })
        // Opt-in: the same address over plain http, which the player tries when the secure one fails.
        if (o.optBoolean("plain") && url.startsWith("https://")) callback(newExtractorLink(o.optString("name", "Live TV"), PLAIN, "http://" + url.removePrefix("https://"), type) {
            this.headers = headers
            headers["Referer"]?.let { referer = it }
        })
        // The same channel from the user's other lists: the player moves on to them when this one doesn't play.
        o.optJSONArray("alts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val altUrl = a.optString("url").ifBlank { null } ?: continue
                val altHeaders = a.optJSONObject("headers").toStringMap()
                val altType = if (altUrl.substringBefore('?').endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback(newExtractorLink(o.optString("name", "Live TV"), "Backup from ${a.optString("from", "another list")}", altUrl, altType) {
                    this.headers = altHeaders
                    altHeaders["Referer"]?.let { referer = it }
                })
            }
        }
        return true
    }
}

/**
 * Channel logos from the open iptv-org database (github.com/iptv-org/database, public domain),
 * for channels whose playlist has no logo or a broken one. Matched by tvg-id, then by name.
 * The lookup tables are downloaded once a week and kept small on disk.
 */
object ChannelLogos {
    /** iptv-org's API, with GitHub's copy of the same files as a second address. */
    private val bases get() = System.getProperty("cs.logos.base")?.let { listOf(it) }
        ?: listOf("https://iptv-org.github.io/api", "https://raw.githubusercontent.com/iptv-org/api/gh-pages")
    private val file get() = File(App.context.cacheDir, "livetv/iptv-org-logos.json")
    @Volatile private var byId: Map<String, String>? = null
    @Volatile private var byName: Map<String, String>? = null
    private val lock = kotlinx.coroutines.sync.Mutex()

    /** Logo addresses to try for [c], best first. */
    suspend fun candidates(c: LiveTv.Channel): List<String> {
        val own = c.logo?.takeIf { it.startsWith("http") }
        val fromDb = runCatching { lookup(c) }.getOrNull()
        return listOfNotNull(own, fromDb).distinct()
    }

    suspend fun lookup(c: LiveTv.Channel): String? {
        ensureLoaded()
        val id = c.epgId?.substringBefore('@')?.lowercase()
        return id?.let { byId?.get(it) } ?: byName?.get(normalize(c.name))
    }

    /** "1+1 International HD (720p) [Geo-blocked]" → "1+1international". */
    fun normalize(name: String) = name.lowercase()
        .replace(Regex("\\[[^\\]]*]|\\([^)]*\\)"), " ")
        .replace(Regex("\\b(hd|fhd|uhd|sd|4k|hevc|backup|tv)\\b"), " ")
        .replace(Regex("[^\\p{L}\\p{N}+]"), "")

    private suspend fun ensureLoaded() {
        if (byId != null) return
        lock.withLock {
            if (byId != null) return
            withContext(Dispatchers.IO) {
                val fresh = file.isFile && System.currentTimeMillis() - file.lastModified() < 7 * 24 * 3600_000L
                val json = (if (fresh) runCatching { JSONObject(file.readText()) }.getOrNull() else null) ?: runCatching { download() }
                    .onFailure { android.util.Log.w("logos", "$it") }.getOrNull()
                    ?: runCatching { JSONObject(file.readText()) }.getOrNull() // stale is better than nothing
                val ids = json?.optJSONObject("ids"); val names = json?.optJSONObject("names")
                byId = ids.toStringMap()
                byName = names.toStringMap()
            }
        }
    }

    private fun get(url: String): String {
        app.baseClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url")
            return r.body.string()
        }
    }

    private fun download(): JSONObject {
        var last: Throwable? = null
        for (base in bases) {
            try { return download(base) } catch (t: Throwable) { last = t }
        }
        throw last ?: java.io.IOException("no logo database address")
    }

    private fun download(base: String): JSONObject {
        // logos.json: [{channel, feed, format, width, url}]; channels.json: [{id, name, alt_names, logo?}]
        val logos = mutableMapOf<String, Pair<Int, String>>()
        runCatching { JSONArray(get("$base/logos.json")) }.getOrNull()?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val format = o.optString("format").uppercase()
                if (format == "SVG" || o.optString("url").endsWith(".svg")) continue // JavaFX can't draw SVG
                val score = o.optInt("width") + (if (o.isNull("feed") || o.optString("feed").isBlank()) 10_000 else 0) +
                    (if (o.optBoolean("in_use", true)) 20_000 else 0)
                val id = o.optString("channel").lowercase()
                if ((logos[id]?.first ?: -1) < score) logos[id] = score to o.optString("url")
            }
        }
        val names = mutableMapOf<String, String>()
        val channels = JSONArray(get("$base/channels.json"))
        for (i in 0 until channels.length()) {
            val o = channels.optJSONObject(i) ?: continue
            val id = o.optString("id").lowercase()
            val logo = logos[id]?.second ?: o.optString("logo").takeIf { it.startsWith("http") && !it.endsWith(".svg") } ?: continue
            if (id !in logos) logos[id] = 0 to logo
            val all = listOf(o.optString("name")) + (o.optJSONArray("alt_names")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty())
            all.map(::normalize).filter { it.length >= 2 }.forEach { names.putIfAbsent(it, logo) }
        }
        val out = JSONObject().put("ids", JSONObject(logos.mapValues { it.value.second })).put("names", JSONObject(names as Map<*, *>))
        file.parentFile.mkdirs()
        file.writeText(out.toString())
        return out
    }
}


/** A JSON object of strings as a map (Android's org.json has no keySet()). */
fun org.json.JSONObject?.toStringMap(): Map<String, String> =
    this?.keys()?.asSequence()?.associateWith { getString(it) }.orEmpty()
