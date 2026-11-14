package io.github.sherazi5110.cloudstream.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.CloudBackup
import kotlinx.coroutines.launch

/** Google Drive backup: what's open right now (the dialogs can be opened from Settings or the first-run screen). */
object CloudUi {
    var signingIn by mutableStateOf(false)
    /** A backup found in the account, offered for restoring. */
    var offerRestore by mutableStateOf<CloudBackup.Remote?>(null)
    var restored by mutableStateOf(false)

    /** Sign in (if needed), then offer the backup found in the account. */
    fun restoreFromDrive() {
        if (!CloudBackup.state.value.signedIn) { signingIn = true; return }
        App.scope.launch {
            runCatching { CloudBackup.remote() }
                .onSuccess { r -> if (r == null) App.toast("There's no backup in this Google account yet") else offerRestore = r }
                .onFailure { App.toast("Couldn't reach Google Drive: ${it.message}") }
        }
    }
}

fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

private fun ago(ms: Long): String {
    if (ms <= 0) return "not yet"
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 48 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }
}

private fun date(iso: String): String = runCatching {
    java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
}.getOrDefault(iso)

/** Settings → Updates and backup → Google Drive. */
@Composable
internal fun CloudBackupSection() {
    val state by CloudBackup.state.collectAsState()
    Heading("Google Drive backup")
    if (!CloudBackup.configured) {
        Hint("Google sign-in isn't set up in this build of the app.")
        return
    }
    if (!state.signedIn) {
        Hint("Sign in with your Google account to keep this backup in your Google Drive, automatically, and restore it on " +
            "another device or after reinstalling. Stream Goat only sees its own hidden backup file there, never your other files.")
        PillButton("Sign in with Google", Icons.Filled.CloudUpload, compact = true, modifier = Modifier.padding(top = 6.dp)) { CloudUi.signingIn = true }
        return
    }
    Text("Signed in as ${state.email ?: "your Google account"}", style = MaterialTheme.typography.bodyLarge)
    Hint("Last backup: ${ago(state.lastBackup)}" + (state.busy?.let { " · $it" } ?: "") + (state.error?.let { " · Problem: $it" } ?: ""))
    var auto by remember { mutableStateOf(CloudBackup.auto) }
    SwitchRow("Back up automatically", "When something changed: every 15 minutes, and when you leave the app.", auto) { auto = it; CloudBackup.auto = it }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
        PillButton("Back up now", Icons.Filled.CloudUpload, compact = true) {
            App.scope.launch {
                runCatching { CloudBackup.backupNow() }.onSuccess { App.toast("Backed up to Google Drive") }
                    .onFailure { App.toast("Couldn't back up: ${it.message}") }
            }
        }
        PillButton("Restore", Icons.Filled.CloudDownload, primary = false, compact = true) { CloudUi.restoreFromDrive() }
    }
    PillButton("Sign out", Icons.Filled.Logout, primary = false, compact = true, modifier = Modifier.padding(top = 6.dp)) {
        App.scope.launch { CloudBackup.signOut(); App.toast("Signed out. The backup stays in your Google Drive.") }
    }
}

/** The Google Drive dialogs, shown over any screen (see AppRoot). */
@Composable
fun CloudDialogs() {
    if (CloudUi.signingIn) SignInDialog()
    CloudUi.offerRestore?.let { RestoreDialog(it) }
    if (CloudUi.restored) {
        val context = LocalContext.current
        SimpleDialog("Restored", { CloudUi.restored = false }, confirm = "Restart now", onConfirm = { restartApp(context) }) {
            Hint("Your backup is back, and the extensions in it were downloaded. Restart the app to finish.")
        }
    }
}

@Composable
private fun SignInDialog() {
    var signIn by remember { mutableStateOf<CloudBackup.SignIn?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { CloudBackup.startSignIn() }.onSuccess { s ->
            signIn = s
            runCatching { CloudBackup.finishSignIn(s) }.onSuccess {
                CloudUi.signingIn = false
                App.toast("Signed in as ${CloudBackup.state.value.email ?: "your Google account"}")
                // A backup already in the account is offered; otherwise this device's is saved there now.
                App.scope.launch {
                    val remote = runCatching { CloudBackup.remote() }.getOrNull()
                    if (remote != null) CloudUi.offerRestore = remote
                    else runCatching { CloudBackup.backupNow() }.onSuccess { App.toast("Backed up to Google Drive") }
                }
            }.onFailure { error = it.message }
        }.onFailure { error = it.message }
    }
    SimpleDialog("Sign in with Google", { CloudUi.signingIn = false }) { SignInBody(signIn, error) }
}

/** The code to enter at google.com/device, with a QR code for the page. */
@Composable
internal fun SignInBody(signIn: CloudBackup.SignIn?, error: String?) {
        val s = signIn
        when {
            error != null -> Hint("Couldn't sign in: $error")
            s == null -> Hint("Getting a code from Google…")
            else -> Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                val qr = remember(s.url) { qrBitmap(s.url, 360).asImageBitmap() }
                Image(qr, "QR code for ${s.url}", Modifier.size(150.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("On your phone or computer, open", style = MaterialTheme.typography.bodyMedium)
                    Text(s.url.removePrefix("https://").removePrefix("www."), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("and enter this code:", style = MaterialTheme.typography.bodyMedium)
                    Text(s.userCode, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp,
                        color = Palette.accent)
                    Hint("Then choose your Google account and allow Stream Goat to keep its backup in your Drive. This screen moves on by itself.")
                }
            }
        }
}

@Composable
private fun RestoreDialog(remote: CloudBackup.Remote) {
    val context = LocalContext.current
    SimpleDialog("Restore your backup?", { CloudUi.offerRestore = null }, confirm = "Restore", onConfirm = {
        CloudUi.offerRestore = null
        App.toast("Restoring…")
        App.scope.launch {
            runCatching { CloudBackup.restore() }
                .onSuccess { CloudUi.restored = true }
                .onFailure { App.toast("Couldn't restore: ${it.message}") }
        }
    }) {
        Hint("Google Drive has a backup" + (remote.device?.let { " from $it" } ?: "") + ", saved ${date(remote.modified)}" +
            (remote.app?.let { " (Stream Goat $it)" } ?: "") + ".")
        Hint("Restoring replaces this device's settings, profiles, My list, history, repositories and extensions with the backup's. " +
            "The extensions are downloaded again.")
    }
}
