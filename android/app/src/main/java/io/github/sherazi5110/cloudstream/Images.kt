package io.github.sherazi5110.cloudstream

import android.app.ActivityManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.allowRgb565
import okio.Path.Companion.toOkioPath

/**
 * Posters and backdrops: how much memory and storage their cache may use, and lighter pictures for old or low-memory
 * phones and TVs (Settings → Appearance → Pictures).
 */
object Images {
    /** Old or low-memory device: the phone says so, or it has under 3 GB. */
    val lowMemoryDevice: Boolean by lazy {
        runCatching {
            val am = App.context.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            am.isLowRamDevice || info.totalMem < 3L * 1024 * 1024 * 1024
        }.getOrDefault(false)
    }

    /** Smaller pictures (and 16-bit colour in memory): on by default on low-memory devices. */
    val low: Boolean get() = Settings.lowResImages ?: lowMemoryDevice

    fun install() {
        SingletonImageLoader.setSafe { ctx ->
            val diskMb = Settings.imageCacheMb
            ImageLoader.Builder(ctx)
                .memoryCache { MemoryCache.Builder().maxSizePercent(ctx, if (low) 0.10 else 0.20).build() }
                .apply {
                    if (diskMb > 0) diskCache {
                        DiskCache.Builder().directory(java.io.File(ctx.cacheDir, "image_cache").toOkioPath())
                            .maxSizeBytes(diskMb * 1024L * 1024L).build()
                    } else diskCache(null)
                }
                .allowRgb565(low)
                .build()
        }
    }

    private val tmdb = Regex("(image\\.tmdb\\.org/t/p/)(original|w\\d+)(/)")
    private val metahub = Regex("(images\\.metahub\\.space/\\w+/)(large|medium)(/)")
    private val amazon = Regex("\\._V1_[^/]*?\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)

    /**
     * The same picture in a smaller size when low resolution is on, for the image hosts that offer one (TMDB, Cinemeta,
     * IMDb/Amazon). [wide] = a backdrop, which gets a bigger size than a poster.
     */
    fun sized(url: String, wide: Boolean = false): String {
        if (!low) return url
        return when {
            // Backdrops come as original/w1280 and posters as w500 or smaller.
            "image.tmdb.org" in url -> tmdb.replace(url) { m ->
                val big = wide || m.groupValues[2] == "original" || (m.groupValues[2].drop(1).toIntOrNull() ?: 0) >= 1000
                m.groupValues[1] + (if (big) "w780" else "w342") + m.groupValues[3]
            }
            "metahub.space" in url -> metahub.replace(url) { m -> m.groupValues[1] + (if (wide) "large" else "medium") + m.groupValues[3] }
            "media-amazon.com" in url && amazon.containsMatchIn(url) -> amazon.replace(url) { m -> "._V1_SX${if (wide) 780 else 342}.${m.groupValues[1]}" }
            else -> url
        }
    }

    fun clearCache() {
        runCatching { SingletonImageLoader.get(App.context).let { it.memoryCache?.clear(); it.diskCache?.clear() } }
    }
}
