package io.github.sherazi5110.cloudstream.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sherazi5110.cloudstream.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The app's own log (Android keeps it for this app only), to read, copy or share when something goes wrong. */
object AppLogs {
    suspend fun read(errorsOnly: Boolean, lines: Int = 1500): String = withContext(Dispatchers.IO) {
        runCatching {
            val p = ProcessBuilder("logcat", "-d", "-t", lines.toString(), "-v", "time", "--pid=${android.os.Process.myPid()}",
                if (errorsOnly) "*:W" else "*:D").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().use { it.readText() }.also { p.waitFor() }
        }.getOrElse { "Couldn't read the log: ${it.message}" }.ifBlank { "Nothing logged yet." }
    }

    fun clear() { runCatching { ProcessBuilder("logcat", "-c").start().waitFor() } }
}

@Composable
fun AppLogsSection() {
    val ctx = LocalContext.current
    var errorsOnly by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("Loading…") }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(errorsOnly, refresh) { text = AppLogs.read(errorsOnly) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("App logs", style = MaterialTheme.typography.titleMedium)
        Text("What the app recorded since it started: useful when a video or an extension doesn't work. It stays on this device unless you share it.",
            style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(errorsOnly, { errorsOnly = true }, { Text("Warnings & errors") })
            FilterChip(!errorsOnly, { errorsOnly = false }, { Text("Everything") })
            PillButton("Refresh", null, primary = false, compact = true) { refresh++ }
            PillButton("Copy", null, primary = false, compact = true) {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Stream Goat log", text)); App.toast("Log copied")
            }
            PillButton("Share", null, primary = false, compact = true) {
                runCatching {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Stream Goat log").putExtra(Intent.EXTRA_TEXT, text.takeLast(90_000)), "Share log")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PillButton("Clear", null, primary = false, compact = true) { AppLogs.clear(); refresh++ }
        }
        SelectionContainer {
            Text(text.takeLast(60_000), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).background(Palette.surface, RoundedCornerShape(10.dp))
                    .verticalScroll(rememberScrollState(Int.MAX_VALUE)).padding(10.dp))
        }
    }
}
