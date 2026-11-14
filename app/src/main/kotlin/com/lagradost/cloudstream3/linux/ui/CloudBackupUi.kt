package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.linux.CloudBackup
import com.lagradost.cloudstream3.linux.Os
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Google Drive sign-in on Windows/Linux: the code to enter at google.com/device (a button opens the page),
 * then the backup found in the account is offered for restoring, or this computer's is saved there.
 */
class CloudSignInSheet(private val onDone: () -> Unit) : StackPane(), Disposable {
    private val content = VBox(12.0)
    private var job: Job? = null

    init {
        val scrim = javafx.scene.layout.Region().apply { styleClass += "scrim"; onClick { close() } }
        val header = hbox(14.0, label("Sign in with Google", "h2"), spacer(), button(null, Icons.CLOSE, "btn-ghost") { close() }.tooltip("Close"))
        val sheet = VBox(16.0, header, content).apply {
            styleClass += "sheet"
            pad(24.0, 26.0, 24.0, 26.0)
            maxWidth = 620.0
            maxHeight = javafx.scene.layout.Region.USE_PREF_SIZE
            setOnMouseClicked { it.consume() }
        }
        children.addAll(scrim, sheet)
        StackPane.setAlignment(sheet, Pos.CENTER)
        content.children.setAll(label("Getting a code from Google…", "muted"))
        job = ui.launch {
            val s = runCatching { CloudBackup.startSignIn() }.getOrElse { show("Couldn't sign in: ${it.message}"); return@launch }
            content.children.setAll(
                label("Open this page in your browser (on this computer or your phone):", "muted"),
                hbox(10.0, label(s.url.removePrefix("https://").removePrefix("www."), "h3"),
                    button("Open the page", Icons.OPEN, "btn-accent", "btn-small") { Os.open(s.url) }),
                label("and enter this code:", "muted"),
                hbox(10.0, label(s.userCode, "h1").apply { style = "-fx-font-family: monospace; -fx-text-fill: -cs-accent;" },
                    button("Copy", Icons.COPY, "btn-ghost", "btn-small") {
                        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(s.userCode) })
                        Toasts.show("Code copied")
                    }),
                label("Then choose your Google account and allow Stream Goat to keep its backup in your Drive. " +
                    "Stream Goat only sees its own hidden backup file there. This moves on by itself.", "muted").apply { isWrapText = true },
            )
            runCatching { CloudBackup.finishSignIn(s) }.onFailure { show("Couldn't sign in: ${it.message}"); return@launch }
            Toasts.show("Signed in as ${CloudBackup.state.value.email ?: "your Google account"}")
            close()
            afterSignIn(onDone)
        }
    }

    private fun show(text: String) {
        content.children.setAll(label(text, "muted").apply { isWrapText = true })
    }

    override fun dispose() { job?.cancel() }

    private fun close() {
        dispose()
        Nav.window.closeOverlay()
        onDone()
    }

    companion object {
        /** A backup already in the account is offered; otherwise this computer's is saved there. */
        fun afterSignIn(onDone: () -> Unit) {
            ui.launch {
                val remote = runCatching { CloudBackup.remote() }.getOrNull()
                if (remote != null) offerRestore(remote, onDone)
                else runCatching { CloudBackup.backupNow() }.onSuccess { Toasts.show("Backed up to Google Drive"); onDone() }
            }
        }

        fun offerRestore(remote: CloudBackup.Remote, onDone: () -> Unit) {
            val alert = javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "Google Drive has a backup" + (remote.device?.let { " from $it" } ?: "") + ", saved " + remote.modified.replace('T', ' ').substringBefore('.') +
                    (remote.app?.let { " (Stream Goat $it)" } ?: "") + ".\n\nRestore it? This replaces this computer's settings, profiles, " +
                    "My list, history, repositories and extensions with the backup's.",
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL).apply { headerText = "Restore your backup?" }
            if (alert.showAndWait().orElse(null) != javafx.scene.control.ButtonType.OK) return
            Toasts.show("Restoring…")
            ui.launch {
                runCatching { CloudBackup.restore() }
                    .onSuccess { got -> Toasts.show("Restored${if (got.isNotEmpty()) " with ${got.size} extension(s)" else ""}. Restart the app to finish.", seconds = 10.0) }
                    .onFailure { Toasts.show("Couldn't restore: ${it.message}") }
                onDone()
            }
        }
    }
}

/** Settings → Storage → Google Drive backup rows. */
fun cloudBackupRows(row: (String, String?, Node) -> Node, refresh: () -> Unit): List<Node> {
    if (!CloudBackup.configured) return listOf(row("Google Drive backup", "Google sign-in isn't set up in this build of the app.", javafx.scene.layout.Region()))
    val s = CloudBackup.state.value
    if (!s.signedIn) return listOf(row("Google Drive backup",
        "Sign in with your Google account to keep this backup in your Google Drive, automatically, and restore it on another device " +
            "or after reinstalling. Stream Goat only sees its own hidden backup file there, never your other files.",
        button("Sign in with Google", Icons.CLOUD_UPLOAD, "btn-accent", "btn-small") { Nav.window.showOverlay(CloudSignInSheet(refresh)) }))
    val last = if (s.lastBackup <= 0) "not yet" else java.time.Instant.ofEpochMilli(s.lastBackup).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))
    return listOf(
        row("Google Drive: ${s.email ?: "signed in"}", "Last backup: $last" + (s.error?.let { ". Problem: $it" } ?: ""),
            hbox(8.0,
                button("Back up now", Icons.CLOUD_UPLOAD, "btn-ghost", "btn-small") {
                    ui.launch {
                        runCatching { CloudBackup.backupNow() }.onSuccess { Toasts.show("Backed up to Google Drive") }
                            .onFailure { Toasts.show("Couldn't back up: ${it.message}") }
                        refresh()
                    }
                },
                button("Restore", Icons.CLOUD_DOWNLOAD, "btn-ghost", "btn-small") {
                    ui.launch {
                        runCatching { CloudBackup.remote() }.onSuccess { r ->
                            if (r == null) Toasts.show("There's no backup in this Google account yet") else CloudSignInSheet.offerRestore(r, refresh)
                        }.onFailure { Toasts.show("Couldn't reach Google Drive: ${it.message}") }
                    }
                },
                button("Sign out", null, "btn-ghost", "btn-small") {
                    ui.launch { CloudBackup.signOut(); Toasts.show("Signed out. The backup stays in your Google Drive."); refresh() }
                },
            )),
        row("Back up automatically", "When something changed, every 15 minutes.", Switch(CloudBackup.auto) { CloudBackup.auto = it }),
    )
}
