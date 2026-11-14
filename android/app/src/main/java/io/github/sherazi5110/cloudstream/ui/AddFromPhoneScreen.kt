package io.github.sherazi5110.cloudstream.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sherazi5110.cloudstream.PhoneLink
import kotlinx.coroutines.launch

/** A QR code for [text] (black on white), [size] pixels square. */
fun qrBitmap(text: String, size: Int): Bitmap {
    val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 1, com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M)
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/**
 * "Add from phone" (Extensions): scan the QR code with a phone on the same Wi-Fi, paste a Stremio add-on or a
 * CloudStream repository there, then press Add here. Listens only while this screen is open.
 */
@Composable
fun AddFromPhoneScreen() {
    val scope = rememberCoroutineScope()
    var noNetwork by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        noNetwork = !PhoneLink.start()
        onDispose { PhoneLink.stop() }
    }
    val wide = isWide
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back") { Nav.back() }
            Text("Add from your phone", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 8.dp))
        }
        val url = PhoneLink.qrUrl
        val info: @Composable () -> Unit = {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Step("1", "Scan the code with your phone's camera. The phone must be on the same Wi-Fi as this TV.")
                Step("2", "Paste a Stremio add-on link (…/manifest.json or stremio://…) or a CloudStream repository: its link, a cloudstreamrepo:// link or a shortcode. Then tap Send to TV.")
                Step("3", "Press Add below. Nothing is installed until you do.")
                when {
                    noNetwork -> Text("This TV isn't connected to a home network (Wi-Fi or Ethernet), so a phone can't reach it.",
                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFFF87171))
                    !PhoneLink.running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Stopped after 10 minutes.", style = MaterialTheme.typography.bodyMedium)
                        PillButton("Start again", Icons.Filled.Refresh, Modifier.padding(start = 12.dp), compact = true) { noNetwork = !PhoneLink.start() }
                    }
                    else -> Column {
                        Text("No camera? On the phone open", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                        Text(PhoneLink.address.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("and enter the code", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                        Text(PhoneLink.code, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp, color = Palette.accent)
                    }
                }
            }
        }
        val qr: @Composable () -> Unit = {
            if (url != null) {
                val bitmap = remember(url) { qrBitmap(url, 480).asImageBitmap() }
                Image(bitmap, "QR code to open the page on your phone", Modifier.size(if (wide) 240.dp else 200.dp)
                    .background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp))
            }
        }
        if (wide) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            qr()
            Box(Modifier.weight(1f)) { info() }
        } else Column(Modifier.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            qr(); info()
        }
        SectionTitle(if (PhoneLink.received.isEmpty()) "Waiting for your phone…" else "From your phone", Modifier.padding(top = 16.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(PhoneLink.received, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().background(Palette.surface, RoundedCornerShape(12.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.kind.label, style = MaterialTheme.typography.labelMedium, color = Palette.accent)
                        Text(item.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        val note = when (item.state) {
                            PhoneLink.ADDING -> "Adding…"
                            PhoneLink.ADDED -> "Added ✓" + (item.message?.let { " · $it" } ?: "")
                            PhoneLink.FAILED -> "Couldn't add it: ${item.message ?: "unknown error"}"
                            PhoneLink.IGNORED -> "Ignored"
                            else -> null
                        }
                        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (item.state == PhoneLink.FAILED) Color(0xFFF87171) else Palette.muted) }
                    }
                    when (item.state) {
                        PhoneLink.WAITING, PhoneLink.FAILED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton(if (item.state == PhoneLink.FAILED) "Try again" else "Add", Icons.Filled.Add, compact = true) { scope.launch { PhoneLink.add(item) } }
                            PillButton("Ignore", Icons.Filled.Close, primary = false, compact = true) { PhoneLink.update(item.id) { it.copy(state = PhoneLink.IGNORED) } }
                        }
                        PhoneLink.ADDED -> if (item.repoUrl != null) PillButton("Open repository", Icons.Filled.Extension, compact = true) {
                            Nav.open(Screen.Repository(item.repoUrl, item.repoName ?: "Repository"))
                        }
                        PhoneLink.ADDING -> Spinner(Modifier.size(28.dp))
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(n, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.background(Palette.accent, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 2.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp).width(560.dp))
    }
}
