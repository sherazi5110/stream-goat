package com.lagradost.cloudstream3.syncproviders.providers

/**
 * The official app's OpenSubtitles account (syncproviders/providers/OpenSubtitlesApi.kt). Extensions (3isk) use
 * its public API key and headers to ask OpenSubtitles for subtitles themselves.
 */
class OpenSubtitlesApi private constructor() {
    companion object {
        const val API_KEY = "uyBLgFD17MgrYmA0gSXoKllMJBelOYj2"
        const val HOST = "https://api.opensubtitles.com/api/v1"
        const val TAG = "OPENSUBS"
        const val COOLDOWN_DURATION: Long = 1000L * 30L
        const val userAgent = "Cloudstream3 v0.2"
        val headers = mapOf("user-agent" to userAgent, "Api-Key" to API_KEY)
    }
}
