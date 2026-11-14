package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.utils.ExtractorLink
import org.json.JSONObject

/**
 * The source that last played for each title (per profile): Continue Watching and Resume start with it again as
 * soon as the extension finds it, instead of waiting for every source. Kept by the source's name and server (and
 * quality), not its address, which usually expires; it applies to every episode of the title.
 */
object LastSource {
    private val prefs get() = Settings.profilePrefs

    private fun key(apiName: String, titleUrl: String): String =
        "last_source:" + java.security.MessageDigest.getInstance("SHA-1").digest("$apiName|$titleUrl".toByteArray())
            .take(10).joinToString("") { "%02x".format(it) }

    /** Remembers [link] as the source that played [titleUrl] (from extension [apiName]). */
    fun remember(apiName: String, titleUrl: String, link: ExtractorLink) {
        prefs.edit().putString(key(apiName, titleUrl), JSONObject().put("name", link.name).put("source", link.source)
            .put("quality", link.quality).toString()).apply()
    }

    /** The link among [links] that's the source last used for this title (the same quality if there are several). */
    fun find(apiName: String, titleUrl: String, links: List<ExtractorLink>): ExtractorLink? {
        val saved = runCatching { JSONObject(prefs.getString(key(apiName, titleUrl), null) ?: return null) }.getOrNull() ?: return null
        val same = links.filter { it.name == saved.optString("name") && it.source == saved.optString("source") }
        return same.firstOrNull { it.quality == saved.optInt("quality") } ?: same.firstOrNull()
    }

    /** The name of the source last used for this title, if any (for the loading screen). */
    fun name(apiName: String, titleUrl: String): String? =
        runCatching { JSONObject(prefs.getString(key(apiName, titleUrl), null) ?: return null).optString("name").ifBlank { null } }.getOrNull()
}
