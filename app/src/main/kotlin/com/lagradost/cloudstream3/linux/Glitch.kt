package com.lagradost.cloudstream3.linux

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * "Report a glitch" and the crash catcher (Windows, Linux, macOS). The app remembers the last steps (pages opened,
 * sources played, messages and errors), notices when its last run ended without closing properly (a crash inside the
 * video engine, a freeze that was force-quit) and puts it all into GitHub's new-issue page for the person to read
 * and submit. Nothing is sent by the app, so no GitHub key ships in it.
 */
object Glitch {
    private val dir get() = File(AppDirs.data, "crash").apply { mkdirs() }
    private val running get() = File(dir, "running")
    private val freezeFile get() = File(dir, "freeze.txt")
    private val stepsFile get() = File(dir, "steps.txt")
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "glitch-steps").apply { isDaemon = true } }
    private val crumbs = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** The page showing now, in words ("Details: Dune (HDHub4U)"). */
    @Volatile var screen: String = "Home"

    /** What's playing ("Dune (HDHub4U), source X from host"), for reports after the video engine crashed. */
    @Volatile var playing: String? = null
        set(v) { field = v; if (v != null) step("Playing $v") }

    /** The report about how the last run ended, if it didn't close properly; shown once at start. */
    @Volatile var pending: String? = null

    /** Remembers one step. Cheap; safe from any thread; kept on disk for after a crash. */
    fun step(text: String) {
        val line = "${time.format(Date())} ${text.replace('\n', ' ').take(180)}"
        val all = synchronized(crumbs) {
            crumbs.addLast(line)
            while (crumbs.size > 25) crumbs.removeFirst()
            crumbs.toList()
        }
        writer.execute { runCatching { stepsFile.writeText(all.joinToString("\n")) } }
    }

    fun steps(): List<String> = synchronized(crumbs) { crumbs.toList() }

    /**
     * At start (after Logs.init): reads how the last run ended, then watches this one: errors in any thread become
     * steps, a clean exit removes the "running" mark, and a window that stops answering for 10 s is written down.
     */
    fun install() {
        pending = runCatching { lastRunReport() }.getOrNull()
        runCatching {
            running.writeText("${ProcessHandle.current().pid()}\n${System.currentTimeMillis()}\n")
            freezeFile.delete()
        }
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { running.delete(); freezeFile.delete() } })
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            step("Error in ${thread.name}: ${error.javaClass.simpleName}: ${error.message ?: ""}")
            lastError = "Thread ${thread.name}\n" + error.stackTraceToString().take(6000)
            if (previous != null) previous.uncaughtException(thread, error) else error.printStackTrace()
        }
        watchFreezes()
    }

    /** The last error seen in this run (stack trace), for glitch reports. */
    @Volatile var lastError: String? = null

    /** Pings the window's thread every 2 s; 10 s without an answer is a freeze (written down in case it's force-quit). */
    private fun watchFreezes() {
        val beat = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        Thread({
            var frozenSince = 0L
            while (true) {
                Thread.sleep(2000)
                runCatching { javafx.application.Platform.runLater { beat.set(System.currentTimeMillis()) } }
                val silent = System.currentTimeMillis() - beat.get()
                if (silent > 10_000 && frozenSince == 0L) {
                    frozenSince = beat.get()
                    val fx = Thread.getAllStackTraces().entries.firstOrNull { it.key.name == "JavaFX Application Thread" }
                    val stack = fx?.value?.take(40)?.joinToString("\n") { "    at $it" }.orEmpty()
                    runCatching { freezeFile.writeText("The window stopped responding at ${Date(beat.get())} on:\n$stack\n") }
                    System.err.println("W/glitch: the window hasn't answered for ${silent / 1000} s")
                } else if (silent < 3000 && frozenSince != 0L) {
                    step("The window froze for ${(System.currentTimeMillis() - frozenSince) / 1000} s")
                    frozenSince = 0L
                    runCatching { freezeFile.delete() }
                }
            }
        }, "glitch-watchdog").apply { isDaemon = true }.start()
    }

    /** Null when the last run closed properly (or this is the first). */
    private fun lastRunReport(): String? {
        if (!running.isFile) return null
        val (pid, started) = running.readLines().let { (it.getOrNull(0)?.toLongOrNull() ?: 0L) to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
        val froze = freezeFile.takeIf { it.isFile }?.readText()
        val steps = stepsFile.takeIf { it.isFile }?.readLines().orEmpty()
        val jvmCrash = crashLog(pid, started)
        val log = previousRunLog()
        return buildString {
            appendLine(if (froze != null) "Stream Goat stopped responding and was closed" else "Stream Goat closed unexpectedly")
            appendLine(header())
            appendLine("Started: ${Date(started)}")
            froze?.let { appendLine(); append(it) }
            jvmCrash?.let { appendLine(); appendLine("Crash log (${it.first}):"); appendLine(it.second) }
            if (steps.isNotEmpty()) { appendLine(); appendLine("Last steps:"); steps.takeLast(12).forEach { appendLine("  $it") } }
            if (log.isNotBlank()) { appendLine(); appendLine("App log (end):"); appendLine(log) }
        }
    }

    /** Java writes hs_err_pid<pid>.log when native code (the video engine) crashes: the working or temp folder. */
    private fun crashLog(pid: Long, after: Long): Pair<String, String>? {
        val places = listOfNotNull(System.getProperty("user.dir"), System.getProperty("java.io.tmpdir"), dir.path, System.getProperty("user.home"))
        val file = places.map { File(it, "hs_err_pid$pid.log") }.firstOrNull { it.isFile && it.lastModified() >= after } ?: return null
        val lines = file.readLines()
        // The summary at the top and the frames of the crashing thread.
        val head = lines.take(14)
        val frames = lines.dropWhile { !it.startsWith("Native frames") && !it.startsWith("Java frames") }.take(30)
        return file.name to (head + listOf("…") + frames).joinToString("\n").take(5000)
    }

    /** The previous run's last lines in the app's log file (between the last two start lines). */
    private fun previousRunLog(): String = runCatching {
        val lines = Logs.tail(1500)
        val starts = lines.indices.filter { lines[it].startsWith("---- cloudstream-linux started") }
        val from = starts.dropLast(1).lastOrNull() ?: return ""
        val to = starts.last()
        lines.subList(from, to).filter { it.isNotBlank() }.takeLast(30).joinToString("\n") { redact(it).take(300) }
    }.getOrDefault("")

    /** This run's last warnings and errors. */
    fun logTail(lines: Int = 30): String = runCatching {
        val all = Logs.tail(800)
        val start = all.indexOfLast { it.startsWith("---- cloudstream-linux started") }.coerceAtLeast(0)
        all.drop(start).filter { it.startsWith("W/") || it.startsWith("E/") || it.contains("Exception") || it.contains("Error") || it.trimStart().startsWith("at ") }
            .takeLast(lines).joinToString("\n") { redact(it).take(300) }
    }.getOrDefault("")

    fun header(): String = "Stream Goat ${com.lagradost.cloudstream3.linux.ui.AppInfo.VERSION} on ${Os.name} " +
        "${System.getProperty("os.version")} (${System.getProperty("os.arch")}), Java ${System.getProperty("java.version")}, " +
        "libVLC ${if (com.lagradost.cloudstream3.linux.ui.Vlc.isLoaded) runCatching { com.lagradost.cloudstream3.linux.ui.Vlc.version }.getOrNull() ?: "?" else "not started yet"}"

    private val tokenish = Regex("""(?i)(access_token|refresh_token|id_token|client_secret|api[_-]?key|password|token)(["'=:\s]+)[^\s"'&,]+""")
    private val auth = Regex("""(?i)(authorization:\s*\w+\s+)\S+""")
    private val email = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")
    private val homeDir = System.getProperty("user.home").orEmpty()

    /** Blanks tokens, keys, passwords, email addresses and the user's home folder name. */
    fun redact(text: String): String {
        var t = tokenish.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}…" }
        t = auth.replace(t) { "${it.groupValues[1]}…" }
        t = email.replace(t, "…@…")
        if (homeDir.length > 3) t = t.replace(homeDir, "~")
        return t
    }

    /** The details under the person's own words in a glitch report. */
    fun reportText(screenshot: String?): String = buildString {
        appendLine(header())
        appendLine("Screen: $screen")
        playing?.let { appendLine("Playing: $it") }
        if (screenshot != null) appendLine("Screenshot: $screenshot")
        val s = steps()
        if (s.isNotEmpty()) { appendLine(); appendLine("Last steps:"); s.takeLast(12).forEach { appendLine("  $it") } }
        lastError?.let { appendLine(); appendLine("Last error:"); appendLine(redact(it).lines().take(15).joinToString("\n")) }
        val log = logTail()
        if (log.isNotBlank()) { appendLine(); appendLine("App log (warnings and errors):"); appendLine(log) }
    }

    /** GitHub's new-issue page: [what] (the person's words) first, then [report]. */
    fun issueUrl(what: String, report: String, screenshotCopied: Boolean, crash: Boolean = false): String {
        val first = what.lineSequence().firstOrNull { it.isNotBlank() }?.take(70)
        val title = when {
            crash -> report.lineSequence().first().take(80)
            first != null -> "Glitch: $first"
            else -> "Glitch on ${Os.name}: $screen".take(90)
        }
        val body = buildString {
            appendLine(if (crash) "**What were you doing?**" else "**What went wrong**")
            appendLine(what.ifBlank { "(describe what you saw and what you expected)" })
            appendLine()
            if (screenshotCopied) {
                appendLine("**Screenshot**: it's copied: click here and paste (${if (Os.isMac) "⌘V" else "Ctrl+V"}), or drag in the file from Downloads → Stream Goat.")
                appendLine()
            }
            appendLine("**Details from the app**")
            appendLine("```")
            appendLine(report.take(5500))
            appendLine("```")
        }
        fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
        return "https://github.com/sherazi5110/stream-goat/issues/new?title=${enc(title)}&body=${enc(body)}"
    }

    /** Where glitch screenshots go: Downloads/Stream Goat. */
    fun screenshotFile(): File {
        val folder = File(Os.downloadsDir(), "Stream Goat").apply { mkdirs() }
        return File(folder, "glitch-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".png")
    }

    /** The person has seen the crash report (or dismissed it). */
    fun dismissPending() { pending = null }
}
