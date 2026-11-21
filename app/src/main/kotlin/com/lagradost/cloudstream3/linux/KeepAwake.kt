package com.lagradost.cloudstream3.linux

import java.util.concurrent.Executors

/**
 * Keeps the screen on while a video plays (issue #59), and lets it sleep again when playback pauses or stops.
 * Windows: SetThreadExecutionState, held by one long-lived thread (the request belongs to the thread that made it).
 * Mac: `caffeinate -d`. Linux: `systemd-inhibit` for idle (desktops that honour it keep the screen on).
 */
object KeepAwake {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "keep-awake").apply { isDaemon = true } }
    @Volatile private var held = false
    private var process: Process? = null

    private interface Kernel32 : com.sun.jna.Library { fun SetThreadExecutionState(flags: Int): Int }
    private val kernel32: Kernel32? by lazy { runCatching { com.sun.jna.Native.load("kernel32", Kernel32::class.java) }.getOrNull() }
    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002

    fun on() { if (!held) { held = true; worker.execute { apply(true) } } }
    fun off() { if (held) { held = false; worker.execute { apply(false) } } }

    private fun apply(awake: Boolean) = runCatching {
        when {
            Os.isWindows -> kernel32?.SetThreadExecutionState(
                if (awake) ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED else ES_CONTINUOUS)
            awake && process == null -> {
                val pid = ProcessHandle.current().pid().toString()
                val cmd = if (Os.isMac) listOf("caffeinate", "-d", "-i", "-w", pid)
                    else listOf("systemd-inhibit", "--what=idle:sleep", "--who=Stream Goat", "--why=Playing a video", "--mode=block", "sleep", "infinity")
                process = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            }
            !awake -> { process?.destroy(); process = null }
            else -> Unit
        }
    }.onFailure { System.err.println("W/keep-awake: ${it.message}") }
}
