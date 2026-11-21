package io.github.sherazi5110.cloudstream.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sherazi5110.cloudstream.App
import kotlin.math.PI
import kotlin.math.sin

/**
 * The Stream Goat logo (Syed Ali Hamza's goat head): a white cut-out (assets/brand) painted in the theme's accent
 * colour, or a rainbow (Settings → Appearance → Logo), always on black.
 */
object Brand {
    val mark: ImageBitmap? by lazy { load("brand/goat-mark.png") }
    val word: ImageBitmap? by lazy { load("brand/goat-word.png") }
    private fun load(name: String) = runCatching {
        App.context.assets.open(name).use { BitmapFactory.decodeStream(it) }.asImageBitmap()
    }.getOrNull()

    /** The rainbow of the logo style "Rainbow". */
    val rainbow = listOf(Color(0xFFFF5F6D), Color(0xFFFFC371), Color(0xFF4ADE80), Color(0xFF38BDF8), Color(0xFFA78BFA), Color(0xFFF472B6))

    /** A second colour that goes with [accent] for the waves: the accent turned 50° round the colour wheel. */
    fun companion(accent: Color): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
        hsv[0] = (hsv[0] + 50f) % 360f
        hsv[1] = (hsv[1] * 1.05f).coerceAtMost(1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
}

/** The goat alone, painted in the logo style (accent or rainbow). */
@Composable
fun GoatMark(modifier: Modifier, tint: Color = Palette.accent, rainbow: Boolean = ThemeState.logoRainbow) {
    val mark = Brand.mark ?: return
    if (!rainbow) Image(mark, "Stream Goat", modifier, colorFilter = ColorFilter.tint(tint))
    else Image(mark, "Stream Goat", modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        drawRect(Brush.linearGradient(Brand.rainbow, Offset.Zero, Offset(size.width, size.height)), blendMode = BlendMode.SrcIn)
    })
}

/** The logo: the goat on a black rounded tile. Follows the theme's colour. */
@Composable
fun Logo(size: Dp = 36.dp) {
    Box(Modifier.size(size).background(Color.Black, RoundedCornerShape(size * 0.24f)), contentAlignment = Alignment.Center) {
        GoatMark(Modifier.size(size * 0.72f))
    }
}

/**
 * Glowing wavy lines (the startup screen's backdrop), in the accent colour and its companion, drifting slowly.
 * [alpha] fades them in.
 */
@Composable
fun Waves(modifier: Modifier, accent: Color = Palette.accent, alpha: Float = 1f) {
    val t by rememberInfiniteTransition(label = "waves").animateFloat(0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val second = Brand.companion(accent)
    // Light enough for TVs and old phones: fewer lines and points, and the paths are reused frame to frame.
    val lines = 16
    val paths = androidx.compose.runtime.remember { List(lines) { Path() } }
    Canvas(modifier.background(Color.Black)) {
        val w = size.width; val h = size.height
        val step = (w / 48f).coerceAtLeast(10f)
        for (i in 0 until lines) {
            val f = i / (lines - 1f)
            val base = h * (-0.15f + 1.3f * f)
            val path = paths[i].apply { reset() }
            var x = -step
            var first = true
            while (x <= w + step) {
                val u = x / w
                val y = base - (u - 0.5f) * h * 0.42f +
                    h * 0.045f * sin(u * 7.0f + t + i * 0.33f) +
                    h * 0.025f * sin(u * 13f - t * 2f + i * 0.71f) +
                    h * 0.012f * sin(u * 23f + t * 3f + i * 1.3f)
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                x += step
            }
            // Colour across the screen: accent → companion, with a bright crest that wanders along each line.
            val c = lerp(accent, second, (0.5f + 0.5f * sin(t + i * 0.4f)) * 0.8f)
            val crest = 0.55f + 0.45f * sin(t * 2f + i * 0.9f)
            val brush = Brush.horizontalGradient(listOf(c.copy(alpha = 0.25f * alpha), lerp(c, Color.White, 0.25f * crest).copy(alpha = alpha), c.copy(alpha = 0.35f * alpha)))
            if (i % 2 == 0) drawPath(path, brush, style = Stroke(width = 9f), alpha = 0.16f)
            drawPath(path, brush, style = Stroke(width = 2.4f))
        }
    }
}
