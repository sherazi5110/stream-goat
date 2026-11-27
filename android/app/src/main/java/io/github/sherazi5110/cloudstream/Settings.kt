package io.github.sherazi5110.cloudstream

import android.content.SharedPreferences

/** DNS-over-HTTPS resolvers, the same list the Android app offers (RequestsHelper.kt / DohProviders.kt). */
enum class DnsProvider(val label: String, val url: String?, val ips: List<String>) {
    SYSTEM("System default", null, emptyList()),
    CLOUDFLARE("Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")),
    GOOGLE("Google", "https://dns.google/dns-query", listOf("8.8.4.4", "8.8.8.8")),
    ADGUARD("AdGuard", "https://dns.adguard.com/dns-query", listOf("94.140.14.140", "94.140.14.141")),
    QUAD9("Quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
    DNS_SB("DNS.SB", "https://doh.dns.sb/dns-query", listOf("185.222.222.222", "45.11.45.11")),
    DNS_WATCH("DNS.Watch", "https://resolver2.dns.watch/dns-query", listOf("84.200.69.80", "84.200.70.40")),
    CANADIAN_SHIELD("Canadian Shield", "https://private.canadianshield.cira.ca/dns-query", listOf("149.112.121.10", "149.112.122.10")),
}

/**
 * App settings (separate from the key store extensions use). App-wide ones live in
 * "app_settings"; the look and player preferences belong to the current [Profiles] profile.
 */
object Settings {
    private val prefs: SharedPreferences get() = App.context.getSharedPreferences("app_settings", 0)

    /** The current profile's preferences (theme, player, home). */
    val profilePrefs: SharedPreferences get() = App.context.getSharedPreferences(Profiles.file("profile_settings"), 0)

    private val PROFILE_KEYS = setOf(
        "auto_play",
        "auto_play_next", "skip_buttons", "auto_skip", "subtitle_language", "preferred_quality", "player_volume", "hero_slider", "anti_spoiler",
        "player_equalizer", "audio_language", "subtitle_size", "subtitle_color", "subtitle_outline", "subtitle_background",
        "subtitle_font", "subtitle_bold", "subtitle_italic", "subtitle_outline_color", "subtitle_edge", "subtitle_bg_color",
        "subtitle_bg_opacity", "subtitle_height", "subtitle_align", "subtitle_no_captions", "subtitle_no_ads", "subtitle_upper",
        "player_zoom", "last_home_provider", "home_plugin_file", "show_nsfw",
        "theme_mode", "theme_accent", "theme_wallpaper", "theme_wallpaper_dim", "theme_wallpaper_blur",
    )

    private fun store(key: String) = if (key in PROFILE_KEYS) profilePrefs else prefs
    private fun string(key: String, def: String) = store(key).getString(key, null) ?: def
    private fun put(key: String, value: Any) {
        store(key).edit().putString(key, value.toString()).apply()
    }

    /** Before profiles (v5 and older) these were app-wide: they become the first profile's. */
    fun migrateToProfiles() {
        if (prefs.contains("profiles_migrated")) return
        val target = App.context.getSharedPreferences("profile_settings", 0).edit()
        prefs.getAll().forEach { (k, v) -> if (k in PROFILE_KEYS && v is String) target.putString(k, v) }
        target.apply()
        prefs.edit().putString("profiles_migrated", "true").apply()
    }

    /** Lower-power playback for low-end PCs and battery: skip H.264 deblocking, buffer more. */
    var batterySaver: Boolean
        get() = string("battery_saver", "false").toBoolean()
        set(v) = put("battery_saver", v)

    var autoPlayNext: Boolean
        get() = string("auto_play_next", "true").toBoolean()
        set(v) = put("auto_play_next", v)

    /** Anime: show "Skip intro" / "Skip credits" buttons (times from AniSkip). */
    var skipButtons: Boolean
        get() = string("skip_buttons", "true").toBoolean()
        set(v) = put("skip_buttons", v)

    /** Anime: skip intros and recaps without asking. */
    var autoSkip: Boolean
        get() = string("auto_skip", "false").toBoolean()
        set(v) = put("auto_skip", v)

    /** Subtitle languages to turn on automatically, e.g. "en" or "ar,en"; empty = off by default. */
    var subtitleLanguage: String
        get() = string("subtitle_language", "")
        set(v) = put("subtitle_language", v)

    /** Start the best source automatically once sources have been found. */
    var autoPlay: Boolean
        get() = string("auto_play", "true").toBoolean()
        set(v) = put("auto_play", v)

    /** Preferred maximum quality in pixels; higher sources are ranked below it. 0 = best available. */
    var preferredQuality: Int
        get() = string("preferred_quality", "1080").toInt()
        set(v) = put("preferred_quality", v)

    var dns: DnsProvider
        get() = runCatching { DnsProvider.valueOf(string("dns", "SYSTEM")) }.getOrDefault(DnsProvider.SYSTEM)
        set(v) = put("dns", v.name)

    /** Use system DNS for a while when the DNS-over-HTTPS resolver fails. */
    var dnsFallback: Boolean
        get() = string("dns_fallback", "true").toBoolean()
        set(v) = put("dns_fallback", v)

    /** Upper bound for one provider's loadLinks call; links found before it are kept. */
    var sourcesTimeoutSeconds: Int
        get() = string("sources_timeout", "60").toInt()
        set(v) = put("sources_timeout", v)

    var autoUpdateExtensions: Boolean
        get() = string("auto_update_extensions", "true").toBoolean()
        set(v) = put("auto_update_extensions", v)

    /** Built-in player volume, 0–200 (VLC allows boosting above 100). */
    var playerVolume: Int
        get() = string("player_volume", "100").toIntOrNull()?.coerceIn(0, 200) ?: 100
        set(v) = put("player_volume", v)

    /** Look for a newer release on GitHub once a day at startup. */
    var checkUpdates: Boolean
        get() = string("check_updates", "true") == "true"
        set(v) = put("check_updates", v)

    /** Text size across the app (issue #55): 0.85 small … 1.3 largest, on top of the phone's own font size. */
    var textScale: Float
        get() = string("text_scale", "1.0").toFloatOrNull() ?: 1f
        set(v) = put("text_scale", v.toString())

    /** Home: rotate the featured banner through the first catalog's top titles. */
    var heroSlider: Boolean
        get() = string("hero_slider", "true") == "true"
        set(v) = put("hero_slider", v)

    /** Hide thumbnails and descriptions of episodes not watched yet. */
    var antiSpoiler: Boolean
        get() = string("anti_spoiler", "false") == "true"
        set(v) = put("anti_spoiler", v)

    /** VLC equalizer preset name ("Rock", "Classical", …); empty = off. */
    var playerEqualizer: String
        get() = string("player_equalizer", "")
        set(v) = put("player_equalizer", v)

    /** Audio languages to pick automatically, e.g. "en" or "ja,en"; empty = the stream's default. */
    var audioLanguage: String
        get() = string("audio_language", "")
        set(v) = put("audio_language", v.trim())

    /** Subtitle look: size SMALL/NORMAL/LARGE/HUGE, text colour, outline 0–6, background box. */
    var subtitleSize: String
        get() = string("subtitle_size", "NORMAL")
        set(v) = put("subtitle_size", v)
    var subtitleColor: String
        get() = string("subtitle_color", "#ffffff")
        set(v) = put("subtitle_color", v)
    var subtitleOutline: Int
        get() = string("subtitle_outline", "4").toIntOrNull()?.coerceIn(0, 6) ?: 4
        set(v) = put("subtitle_outline", v)
    var subtitleBackground: Boolean
        get() = string("subtitle_background", "false") == "true"
        set(v) = put("subtitle_background", v)
    /** Font family: DEFAULT, SERIF, MONO, CONDENSED. */
    var subtitleFont: String
        get() = string("subtitle_font", "DEFAULT")
        set(v) = put("subtitle_font", v)
    var subtitleBold: Boolean
        get() = string("subtitle_bold", "false") == "true"
        set(v) = put("subtitle_bold", v)
    var subtitleItalic: Boolean
        get() = string("subtitle_italic", "false") == "true"
        set(v) = put("subtitle_italic", v)
    var subtitleOutlineColor: String
        get() = string("subtitle_outline_color", "#000000")
        set(v) = put("subtitle_outline_color", v)
    /** Edge around the letters: OUTLINE, SHADOW, BOTH, NONE. */
    var subtitleEdge: String
        get() = string("subtitle_edge", "OUTLINE")
        set(v) = put("subtitle_edge", v)
    var subtitleBackgroundColor: String
        get() = string("subtitle_bg_color", "#000000")
        set(v) = put("subtitle_bg_color", v)
    /** Background box opacity in percent (only with [subtitleBackground]). */
    var subtitleBackgroundOpacity: Int
        get() = string("subtitle_bg_opacity", "60").toIntOrNull()?.coerceIn(0, 100) ?: 60
        set(v) = put("subtitle_bg_opacity", v)
    /** Height above the bottom of the picture: 0 (bottom) … 3 (high). */
    var subtitleHeight: Int
        get() = string("subtitle_height", "0").toIntOrNull()?.coerceIn(0, 3) ?: 0
        set(v) = put("subtitle_height", v)
    /** Text alignment: CENTER, LEFT, RIGHT. */
    var subtitleAlign: String
        get() = string("subtitle_align", "CENTER")
        set(v) = put("subtitle_align", v)
    /** Removes [door knocks], (laughs), ♪ lyrics and SPEAKER: labels. */
    var subtitleNoCaptions: Boolean
        get() = string("subtitle_no_captions", "false") == "true"
        set(v) = put("subtitle_no_captions", v)
    /** Removes "Subtitles by…", "Downloaded from…" and website lines. */
    var subtitleNoAds: Boolean
        get() = string("subtitle_no_ads", "true") == "true"
        set(v) = put("subtitle_no_ads", v)
    /** Phones: shaking opens Report a glitch. */
    var shakeToReport: Boolean
        get() = string("shake_to_report", "true") == "true"
        set(v) = put("shake_to_report", v)
    var subtitleUppercase: Boolean
        get() = string("subtitle_upper", "false") == "true"
        set(v) = put("subtitle_upper", v)

    /** Player zoom mode, a [io.github.sherazi5110.cloudstream.player.Zoom] name. */
    var playerZoom: String
        get() = string("player_zoom", "FIT")
        set(v) = put("player_zoom", v)

    /** The player's rotation on phones and tablets: LANDSCAPE (either way round), PORTRAIT or AUTO (as the device is held). */
    var playerOrientation: String
        get() = string("player_orientation", "LANDSCAPE")
        set(v) = put("player_orientation", v)

    /** Lower-resolution posters and pictures (null = automatic: on for old or low-memory devices). */
    var lowResImages: Boolean?
        get() = string("low_res_images", "").toBooleanStrictOrNull()
        set(v) = put("low_res_images", v?.toString() ?: "")

    /** How much storage the picture cache may use, in MB (0 = no cache on storage). Applies after a restart. */
    var imageCacheMb: Int
        get() = string("image_cache_mb", "250").toIntOrNull() ?: 250
        set(v) = put("image_cache_mb", v.toString())

    /** How far ahead the player loads, in seconds (HLS streams). More = fewer pauses on slow internet, more memory. */
    var bufferAheadSec: Int
        get() = string("buffer_ahead_s", "60").toIntOrNull()?.coerceIn(10, 600) ?: 60
        set(v) = put("buffer_ahead_s", v.toString())

    /** Most memory the player's download buffer may use, in MB (single-file videos). 0 = automatic. */
    var bufferMemoryMb: Int
        get() = string("buffer_memory_mb", "0").toIntOrNull()?.coerceIn(0, 1024) ?: 0
        set(v) = put("buffer_memory_mb", v.toString())

    /** How much to load before a video starts or resumes after a jump, in milliseconds. */
    var startBufferMs: Int
        get() = string("start_buffer_ms", "1500").toIntOrNull()?.coerceIn(300, 20_000) ?: 1500
        set(v) = put("start_buffer_ms", v.toString())

    /** Player engine: "AUTO" (ExoPlayer for extension streams, VLC for Live TV, torrents and files), "EXO" or "VLC". */
    var playerEngine: String
        get() = string("player_engine", "AUTO")
        set(v) = put("player_engine", v)

    /** The engine for one kind of source ([PlayerSource]): "EXO" or "VLC". */
    fun engineFor(kind: String): String = string("player_engine_$kind", when (kind) { "extension", "stremio" -> "EXO"; else -> "VLC" })
    fun setEngineFor(kind: String, engine: String) = put("player_engine_$kind", engine)

    /** Ask which engine to play with every time a video starts. */
    /** Live TV's country filter as last picked: null = never picked (your country), "ALL" = all countries. */
    var liveCountry: String?
        get() = string("live_country", "").ifBlank { null }
        set(v) = put("live_country", v ?: "")
    /** The TV guide's filters (Category, Language, Source), kept between visits like the country. */
    var liveCategory: String
        get() = string("live_category", "")
        set(v) = put("live_category", v)
    var liveLanguage: String
        get() = string("live_language", "")
        set(v) = put("live_language", v)
    var liveSourceFilter: String
        get() = string("live_source_filter", "working")
        set(v) = put("live_source_filter", v)

    /** Whether the YouTube tab is shown (Settings → Appearance → Tabs). */
    var showYouTube: Boolean
        get() = string("show_youtube", "true").toBoolean()
        set(v) = put("show_youtube", v.toString())
    /** Settings → Appearance → Tabs: the Live TV and Music tabs (off hides them, like YouTube). */
    var showLiveTv: Boolean
        get() = string("show_livetv", "true").toBoolean()
        set(v) = put("show_livetv", v.toString())
    var showMusic: Boolean
        get() = string("show_music", "true").toBoolean()
        set(v) = put("show_music", v.toString())

    var askEngine: Boolean
        get() = string("ask_engine", "false").toBoolean()
        set(v) = put("ask_engine", v.toString())

    /** Volume boost in percent (100 = normal, up to 200): for quiet streams and weak speakers. */
    var volumeBoost: Int
        get() = string("volume_boost", "100").toIntOrNull()?.coerceIn(100, 200) ?: 100
        set(v) = put("volume_boost", v.coerceIn(100, 200).toString())

    /** Send Dolby/DTS 5.1 and 7.1 sound as is to an AV receiver or soundbar (HDMI/optical passthrough). */
    var passthrough: Boolean
        get() = string("audio_passthrough", "false").toBoolean()
        set(v) = put("audio_passthrough", v.toString())

    /** Even out loud and quiet sounds (libVLC's compressor): quieter explosions and music, clearer voices. */
    var levelAudio: Boolean
        get() = string("level_audio", "false").toBoolean()
        set(v) = put("level_audio", v.toString())

    /**
     * Show adult (NSFW) extensions and titles, like the Android app's setting. Off by default,
     * per profile, never in kids profiles.
     */
    var showNsfw: Boolean
        get() = string("show_nsfw", "false") == "true"
        set(v) { put("show_nsfw", v); ContentFilter.syncAdult() }

    /** "auto" (detect Android TV), "phone" or "tv": for TV boxes that report themselves wrongly. App-wide. */
    var layout: String
        get() = string("layout", "auto")
        set(v) = put("layout", v)

    var lastHomeProvider: String?
        get() = string("last_home_provider", "").ifBlank { null }
        set(v) = put("last_home_provider", v ?: "")

    /** The extension file Home's provider comes from: loaded first at start, like the official app. */
    var homePluginFile: String?
        get() = string("home_plugin_file", "").ifBlank { null }
        set(v) = put("home_plugin_file", v ?: "")

    /** Extension languages to show (lowercase codes such as "en", "hi"); empty = all, like the official app. */
    var extensionLanguages: Set<String>
        get() = string("extension_languages", "").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        set(v) = put("extension_languages", v.joinToString(","))

    /** Media types to show extensions for (TvType names); empty = all ("Preferred media" in the official app). */
    var mediaTypes: Set<String>
        get() = string("media_types", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        set(v) = put("media_types", v.joinToString(","))

    /** Install every new extension of the added repositories by itself, at startup. */
    var autoInstallExtensions: Boolean
        get() = string("auto_install_extensions", "false").toBoolean()
        set(v) = put("auto_install_extensions", v)

    /** Downloads running at the same time (1–5). */
    /** Start finding an episode's sources when its title page opens, so Play starts sooner. */
    var prepareSources: Boolean
        get() = string("prepare_sources", "true").toBoolean()
        set(v) = put("prepare_sources", v)

    /** Connections per download (like IDM): pieces of a file, or stream segments, fetched at the same time. */
    var downloadConnections: Int
        get() = string("download_connections", "8").toIntOrNull()?.coerceIn(1, 16) ?: 8
        set(v) = put("download_connections", v.coerceIn(1, 16))

    /** Ask which source and quality to download (otherwise the best one is picked). */
    var downloadAsk: Boolean
        get() = string("download_ask", "true").toBoolean()
        set(v) = put("download_ask", v)

    var parallelDownloads: Int
        get() = string("parallel_downloads", "2").toIntOrNull()?.coerceIn(1, 5) ?: 2
        set(v) = put("parallel_downloads", v.coerceIn(1, 5))

    /** The short animated intro when the app opens. */
    var startupAnimation: Boolean
        get() = string("startup_animation", "true").toBoolean()
        set(v) = put("startup_animation", v)

    /** HDR video on HDR screens: AUTO (HDR sources), ON (always the HDR-capable surface), OFF. */
    var hdrOutput: String
        get() = string("hdr_output", "AUTO")
        set(v) = put("hdr_output", v)
}
