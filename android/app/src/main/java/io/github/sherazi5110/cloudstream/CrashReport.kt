package io.github.sherazi5110.cloudstream

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * When Stream Goat closes because of an error, the next start shows what happened (and what was playing), with
 * Copy and Share, so it can be reported. An extension's own background thread failing doesn't close the app: that
 * extension's work stops and a message says so.
 */
object CrashReport {
    private const val TAG = "CrashReport"
    private val prefs get() = App.context.getSharedPreferences("crash", Context.MODE_PRIVATE)
    private val file get() = File(App.context.filesDir, "crash-report.txt")

    /** A report waiting to be shown (from the last run), or null. */
    @Volatile var pending: String? = null

    fun install() {
        pending = readPending()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val extension = runCatching { if (thread !== Looper.getMainLooper().thread) extensionOf(error) else null }.getOrNull()
            if (extension != null) {
                Log.e(TAG, "$extension failed in a background thread (${thread.name}); the app keeps running", error)
                runCatching { App.toast("$extension had an error and stopped: ${error.message ?: error.javaClass.simpleName}") }
                return@setDefaultUncaughtExceptionHandler
            }
            runCatching { file.writeText(report("Stream Goat closed because of an error", thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** What is playing, kept on disk so a crash of the video engine itself (no Java error) can still say it. */
    /**
     * GitHub's "new issue" page with the report filled in (the person reviews it and presses Submit; nothing is sent
     * by the app itself, so no GitHub key ships in it). [maxReport] keeps the link short enough for a QR code on TVs.
     */
    fun issueUrl(report: String?, maxReport: Int = 5500): String {
        val title = report?.lineSequence()?.firstOrNull()?.take(80) ?: "Problem report"
        val body = buildString {
            appendLine("**What happened** (what you were doing):")
            appendLine()
            appendLine()
            appendLine("**Report from the app**")
            appendLine("```")
            appendLine(report?.take(maxReport) ?: header())
            appendLine("```")
        }
        fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
        return "https://github.com/sherazi5110/stream-goat/issues/new?title=${enc(title)}&body=${enc(body)}"
    }

    fun playing(what: String?) {
        prefs.edit().apply { if (what == null) remove("playing") else putString("playing", what) }.apply()
    }

    fun dismiss() {
        pending = null
        file.delete()
    }

    private fun readPending(): String? {
        val java = file.takeIf { it.exists() }?.readText()
        val native = if (java == null) nativeExit() else null
        val playing = prefs.getString("playing", null)
        prefs.edit().remove("playing").apply()
        return java ?: native?.let { it + (playing?.let { p -> "\nWas playing: $p\n" } ?: "") }
    }

    /** Android 11+: the system remembers why the app's last run ended, including crashes inside native code. */
    private fun nativeExit(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val am = App.context.getSystemService(ActivityManager::class.java) ?: return null
            val last = am.getHistoricalProcessExitReasons(App.context.packageName, 0, 1).firstOrNull() ?: return null
            val seen = prefs.getLong("seenExit", 0)
            prefs.edit().putLong("seenExit", last.timestamp).apply()
            if (last.timestamp <= seen) return null
            val reason = when (last.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "a crash inside native code (video engine or graphics)"
                ApplicationExitInfo.REASON_CRASH -> "an error"
                ApplicationExitInfo.REASON_ANR -> "not responding"
                // Android closing the app in the background to free memory is normal, not a fault: only report it
                // when it happened while the app was on screen.
                ApplicationExitInfo.REASON_LOW_MEMORY ->
                    if (last.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) "low memory (while on screen)" else return null
                else -> return null
            }
            buildString {
                appendLine("Stream Goat closed because of $reason")
                appendLine(header())
                appendLine("Exit: ${last.description ?: "-"} (reason ${last.reason}, status ${last.status})")
                appendLine("When: ${date(last.timestamp)}")
                // What the screen was waiting for: the main thread's stack from Android's report.
                if (last.reason == ApplicationExitInfo.REASON_ANR) mainThreadStack(last)?.let { appendLine(); appendLine(it) }
            }
        }.getOrNull()
    }

    /** The "main" thread of an ANR trace (Android 11+), the first 40 lines. */
    private fun mainThreadStack(info: ApplicationExitInfo): String? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val text = info.traceInputStream?.use { it.readBytes().decodeToString() } ?: return null
        val start = text.indexOf("\n\"main\"").takeIf { it >= 0 } ?: return null
        val end = text.indexOf("\n\n", start + 1).takeIf { it > 0 } ?: text.length
        text.substring(start + 1, end).lines().take(40).joinToString("\n")
    }.getOrNull()

    internal fun header(): String {
        val version = runCatching { App.context.packageManager.getPackageInfo(App.context.packageName, 0).versionName }.getOrNull()
        return "Stream Goat $version on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.firstOrNull()}"
    }

    private fun date(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    private fun report(title: String, thread: Thread, error: Throwable): String = buildString {
        appendLine(title)
        appendLine(header())
        appendLine("When: ${date(System.currentTimeMillis())}")
        prefs.getString("playing", null)?.let { appendLine("Was playing: $it") }
        appendLine("Thread: ${thread.name}")
        appendLine()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        append(trace.take(16_000))
    }

    /**
     * The extension whose code is in [error]'s stack, if any: a class there was defined by an extension's class
     * loader. Only then is a background failure kept from closing the app.
     */
    private fun extensionOf(error: Throwable): String? {
        val names = generateSequence(error) { it.cause }.take(8)
            .flatMap { it.stackTrace.asSequence() }.map { it.className }.distinct().take(200).toList()
        for (plugin in PluginManager.loaded.values) {
            val loader = plugin.loader
            for (name in names) {
                if (name.startsWith("java.") || name.startsWith("android.") || name.startsWith("kotlin")) continue
                val cls = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
                if (cls.classLoader === loader) return plugin.data.name ?: plugin.data.internalName
            }
        }
        return null
    }
}
