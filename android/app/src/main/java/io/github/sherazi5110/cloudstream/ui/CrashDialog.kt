package io.github.sherazi5110.cloudstream.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.CrashReport

/** After Stream Goat closed because of an error: what happened, to copy, share or photograph for a report. */
@Composable
fun CrashDialog() {
    var report by remember { mutableStateOf(CrashReport.pending) }
    val text = report ?: return
    val context = LocalContext.current
    fun close() { CrashReport.dismiss(); report = null }
    SimpleDialog(
        "Stream Goat closed unexpectedly", ::close, confirm = "Share", onConfirm = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            runCatching { context.startActivity(Intent.createChooser(send, "Send the error report")) }
                .onFailure { App.toast("No app to share with here: use Copy, or take a photo of this screen") }
        },
        extra = {
            PillButton("Copy", null, primary = false, compact = true) {
                runCatching {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Stream Goat error report", text))
                    App.toast("Copied")
                }
            }
        },
    ) {
        Hint("Here's what went wrong. Copy or share it (or take a photo) and send it to the developer, so it can be fixed.")
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, style = MaterialTheme.typography.bodySmall)
    }
}
