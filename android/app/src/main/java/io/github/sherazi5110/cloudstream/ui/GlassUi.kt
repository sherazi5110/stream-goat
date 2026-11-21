package io.github.sherazi5110.cloudstream.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Liquid glass, after Apple's: glass is the layer of controls that floats over the content (bars, sheets, menus,
 * dialogs), never the content itself. It blurs what's behind it, carries a dark tint so white text always reads,
 * has a thin bright rim (brighter at the top, like light on glass) and a soft highlight, and floats on a shadow.
 * Other themes get their usual solid panels from the same calls.
 */
object Glass {
    /** The page behind, for in-window blur (set by AppRoot around the page). */
    val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

    /** Real blur needs Android 12 (RenderEffect); older devices get a darker tint instead. */
    // Live blur costs a lot per frame on TV boxes and sticks: TVs get the solid tinted glass instead, so remote
    // scrolling stays smooth.
    val canBlur: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !io.github.sherazi5110.cloudstream.App.isTv

    /** Glass tint over the blur: deep ink, strong enough for white text over any poster. */
    val tint: Color get() = Color(0xFF120E24).copy(alpha = if (canBlur) 0.58f else 0.90f)
    /** For surfaces that can't blur what's behind them (another window, the video): nearly opaque. */
    val solid: Color get() = Color(0xFF17132B).copy(alpha = 0.95f)
}

/**
 * The lens (Android 13+), after Liquid-Glass-Android's lens pipeline: inside a bevel along the edge the backdrop is
 * pulled inward (refraction) with a slight colour split (dispersion), colours get a vibrancy lift, and two rim
 * highlights light the edge where it faces the light (top-left) and the opposite inner wall (bottom-right).
 */
private const val LENS = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float bevel;
uniform float refraction;
float sdBox(float2 p, float2 b, float r) { float2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }
half4 main(float2 xy) {
    float2 c = size * 0.5;
    float2 p = xy - c;
    float d = sdBox(p, c, radius);
    float inside = -d;
    float e = 1.0;
    float2 n = normalize(float2(sdBox(p + float2(e, 0), c, radius) - sdBox(p - float2(e, 0), c, radius),
                                sdBox(p + float2(0, e), c, radius) - sdBox(p - float2(0, e), c, radius)) + 1e-4);
    float t = clamp(1.0 - inside / bevel, 0.0, 1.0);
    float2 off = -n * refraction * t * t;
    half4 col = content.eval(xy + off);
    col.r = content.eval(xy + off * 1.10).r;
    col.b = content.eval(xy + off * 0.90).b;
    half l = dot(col.rgb, half3(0.299, 0.587, 0.114));
    col.rgb = mix(half3(l), col.rgb, 1.22);
    float2 L = normalize(float2(-0.5, -0.866));
    float lobe = pow(abs(dot(n, L)), 4.5);
    float rim = (1.0 - smoothstep(0.0, 2.2, inside)) * lobe;
    float glow = (1.0 - smoothstep(0.0, 7.0, inside)) * max(dot(n, L), 0.0);
    col.rgb += (rim * 0.65 + glow * 0.10) * col.a;
    return col;
}
"""

/** The lens on a glass layer, or nothing where the device can't run it. */
private fun Modifier.lens(radiusDp: Float): Modifier = if (Build.VERSION.SDK_INT < 33) this else composed {
    val shader = androidx.compose.runtime.remember { runCatching { android.graphics.RuntimeShader(LENS) }.getOrNull() }
    if (shader == null) this else this.graphicsLayer {
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("radius", minOf(radiusDp * density, size.minDimension / 2))
        shader.setFloatUniform("bevel", minOf(14f * density, size.minDimension / 3))
        shader.setFloatUniform("refraction", 9f * density)
        renderEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

/** The rim and highlight on glass: a gradient edge and a soft light across the top. */
private fun Modifier.glassRim(shape: Shape): Modifier = this
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.06f))), shape)
    .drawWithContent {
        drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.35f to Color.White.copy(alpha = 0.02f), 1f to Color.Transparent))
        drawContent()
    }

/**
 * A glass panel (sheet, menu, dialog, bar). In the glass theme: blur of the page behind (when [blur] and the page
 * is a haze source), dark tint, rim, highlight and shadow. In other themes: the theme's solid surface.
 */
fun Modifier.glassPanel(shape: Shape, blur: Boolean = true, elevation: Int = 18, cornerDp: Float = 22f, lens: Boolean = false): Modifier = composed {
    if (!Palette.isGlass) return@composed this.background(Palette.panel, shape)
    val haze = Glass.LocalHaze.current
    val base = this.shadow(elevation.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black).clip(shape)
    val filled = if (blur && haze != null && Glass.canBlur)
        base.hazeEffect(haze, HazeStyle(backgroundColor = Palette.background, tint = HazeTint(Glass.tint), blurRadius = 20.dp, noiseFactor = 0.06f))
    else base.background(if (blur) Glass.tint.copy(alpha = 0.92f) else Glass.solid)
    // The lens (opt-in: it redraws on every frame, too heavy for bars over scrolling pages) bends what's behind (blurred glass only: flat tint has nothing to bend, the rim still shows).
    filled.glassRim(shape).then(if (lens && blur && haze != null && Glass.canBlur) Modifier.lens(cornerDp) else Modifier)
}

/**
 * Inside a Dialog: in the glass theme, blurs everything behind the dialog window (Android 12+) and dims it a little,
 * so the dialog reads as glass over the app rather than a white or see-through box.
 */
@Composable
fun DialogGlass() {
    if (!Palette.isGlass) return
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    SideEffect {
        if (Glass.canBlur) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 48 }
        }
        window.setDimAmount(0.35f)
    }
}
