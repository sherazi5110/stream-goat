package io.github.sherazi5110.cloudstream.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * The startup screen (about 2.5 seconds): glowing wavy lines in the theme's colours drift across black, the logo
 * rises in on its black tile, then the name. A tap or any key skips it; Settings → Appearance turns it off.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val accent = Palette.accent
    val waves = remember { Animatable(0f) }
    val logo = remember { Animatable(0f) }
    val words = remember { Animatable(0f) }
    val done by androidx.compose.runtime.rememberUpdatedState(onDone)
    // It must never hold the app up.
    LaunchedEffect(Unit) { delay(5000); done() }
    LaunchedEffect(Unit) {
        // Start once the first frames are on screen: the app is still busy starting up before that, and an animation
        // begun then skips its first part (the stutter at the start).
        repeat(3) { androidx.compose.runtime.withFrameNanos { } }
        coroutineScope {
            listOf(
                async { waves.animateTo(1f, tween(900)) },
                async { delay(250); logo.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) },
                async { delay(900); words.animateTo(1f, tween(700)) },
            ).awaitAll()
        }
        delay(900)
        done()
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, null) { done() }
            .onKeyEvent { done(); true },
        contentAlignment = Alignment.Center,
    ) {
        Waves(Modifier.fillMaxSize(), accent, waves.value)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            // The glow is a plain gradient drawn once (a coloured shadow on a moving layer was redrawn every frame).
            Box(Modifier.size(150.dp).graphicsLayer {
                    alpha = logo.value; val s = 0.82f + 0.18f * logo.value; scaleX = s; scaleY = s; translationY = 40f * (1 - logo.value)
                }.drawBehind {
                    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(accent.copy(alpha = 0.45f), Color.Transparent),
                        center, size.minDimension * 0.85f), size.minDimension * 0.85f)
                },
                contentAlignment = Alignment.Center) { Logo(150.dp) }
            Column(Modifier.graphicsLayer { alpha = words.value; translationY = 16f * (1 - words.value) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Brand.word?.let { Image(it, "stream goat", Modifier.width(230.dp), colorFilter = ColorFilter.tint(Color.White)) }
                    ?: Text("stream goat", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Text("Created by Syed Ali Hamza", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}
