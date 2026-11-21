package io.github.sherazi5110.cloudstream.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.sherazi5110.cloudstream.YouTubeActivity

/** Opened once per app start straight away; after that the tab shows this page with the Open button. */
private object YouTubeTab { var opened = false }

/** The YouTube tab: YouTube's TV app without ads, in its own full-screen window. */
@Composable
fun YouTubeScreen() {
    val context = LocalContext.current
    fun open() = context.startActivity(Intent(context, YouTubeActivity::class.java))
    LaunchedEffect(Unit) { if (!YouTubeTab.opened) { YouTubeTab.opened = true; open() } }
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("YouTube", style = MaterialTheme.typography.headlineMedium)
        Text("YouTube's TV app, with its ads removed. To sign in, choose Sign in there: it shows a code to enter at " +
            "youtube.com/activate on your phone. Back goes back in YouTube; hold Back to come back here.",
            style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.widthIn(max = 560.dp))
        PillButton("Open YouTube", Icons.Filled.PlayArrow) { open() }
    }
}
