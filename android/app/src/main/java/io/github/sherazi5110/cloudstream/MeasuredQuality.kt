package io.github.sherazi5110.cloudstream

/**
 * The real picture size of sources that played, measured by the player (extensions sometimes label a 4K file as
 * 1080p). Remembered per address (without its query), so the source list shows the true quality next time.
 */
object MeasuredQuality {
    private val prefs get() = App.context.getSharedPreferences("measured_quality", 0)
    private fun key(url: String) = url.substringBefore('?').takeLast(200)

    /** Height in pixels (2160 for 4K), or null when this source hasn't played yet. */
    fun get(url: String): Int? = runCatching { prefs.getInt(key(url), 0).takeIf { it > 0 } }.getOrNull()

    fun remember(url: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        // A 3840×1600 (wide film) picture is 4K too: go by the larger side.
        val h = when { width >= 3200 || height >= 2000 -> 2160; width >= 2400 || height >= 1400 -> 1440
            width >= 1700 || height >= 1000 -> 1080; width >= 1200 || height >= 700 -> 720
            // A 960×400 wide film is 480p, not "400p".
            width >= 800 || height >= 450 -> 480; width >= 560 || height >= 320 -> 360; else -> height }
        runCatching {
            val p = prefs
            if (p.all.size > 800) p.edit().clear().apply()
            p.edit().putInt(key(url), h).apply()
        }
    }

    fun label(height: Int): String = if (height >= 2160) "4K" else "${height}p"
}
