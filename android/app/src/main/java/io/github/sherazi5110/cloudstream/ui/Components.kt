package io.github.sherazi5110.cloudstream.ui

import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.lagradost.cloudstream3.SearchResponse
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.R

/**
 * Click, long-press and remote focus in one: on Android TV the focused item gets a white ring
 * and grows a little (the Linux app's look); on phones a long press opens the item's menu.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.card(
    shape: Shape = RoundedCornerShape(10.dp),
    focusScale: Float = 1.06f,
    onFocus: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** TV with a mouse: the first click only selects (highlights) it, a second click opens it. The remote's OK opens. */
    selectFirst: Boolean = false,
    onClick: () -> Unit,
): Modifier = this.then(Modifier.cardImpl(shape, focusScale, onFocus, onLongClick, selectFirst, onClick))

/** Whether the mouse button went down on a card that wasn't selected yet (then that click only selects it). */
private class PressState { var focused = false; var pressedUnselected = false; var pressedAt = 0L }

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.cardImpl(
    shape: Shape, focusScale: Float, onFocus: (() -> Unit)?, onLongClick: (() -> Unit)?, selectFirst: Boolean, onClick: () -> Unit,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f, label = "focus")
    val firstClickSelects = selectFirst && isTv
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    val press = remember { PressState() }
    this
        .scale(scale)
        .then(if (firstClickSelects) Modifier.focusRequester(requester).pointerInput(Unit) {
            // Seen before the click handler: was it already selected when the button went down?
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                        press.pressedUnselected = !press.focused
                        press.pressedAt = android.os.SystemClock.uptimeMillis()
                    }
                }
            }
        } else Modifier)
        .onFocusChanged {
            focused = it.isFocused
            press.focused = it.isFocused
            if (it.isFocused) onFocus?.invoke()
        }
        .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
        .clip(shape)
        .combinedClickable(onLongClick = onLongClick, onClick = {
            val mouseFirstClick = firstClickSelects && press.pressedUnselected && android.os.SystemClock.uptimeMillis() - press.pressedAt < 2000
            press.pressedUnselected = false
            if (mouseFirstClick) runCatching { requester.requestFocus() } else onClick()
        })
}

/**
 * Rows of posters on TV: the selected one stays about a card away from the row's edges, so selecting the last one
 * you can see (with the remote or a click) scrolls the row to show the next ones.
 */
@OptIn(ExperimentalFoundationApi::class)
internal val RevealScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = minOf(size * 1.1f, containerSize * 0.3f)
        val before = offset - margin
        val after = offset + size + margin - containerSize
        return when { before < 0f && after > 0f -> 0f; before < 0f -> before; after > 0f -> after; else -> 0f }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvRowScroll(content: @Composable () -> Unit) {
    if (isTv) androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides RevealScroll) {
        // Right on the last card stays put (it used to jump to the profile button at the far side of the screen).
        androidx.compose.foundation.layout.Box(Modifier
            .focusProperties { onExit = { if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Right) cancelFocusChange() } }
            // Coming back to a row (Up/Down from the next row) returns to the card that was selected there, instead
            // of whichever card happens to be nearest on screen.
            .focusRestorer()) { content() }
    }
    else content()
}

/** A network image with the extension's headers (some sites refuse posters without a Referer). */
@Composable
fun NetImage(
    url: String?,
    modifier: Modifier = Modifier,
    headers: Map<String, String>? = null,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    /** A grey box behind the image (posters); off for logos drawn over artwork. */
    placeholder: Boolean = true,
) {
    val context = LocalContext.current
    if (url.isNullOrBlank()) {
        if (placeholder) Box(modifier.background(Palette.surfaceHigh))
        return
    }
    val request = remember(url, headers) {
        ImageRequest.Builder(context).data(io.github.sherazi5110.cloudstream.Images.sized(url)).crossfade(!isTv).apply {
            if (!headers.isNullOrEmpty()) httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build())
        }.build()
    }
    AsyncImage(model = request, contentDescription = null, contentScale = contentScale, alignment = alignment,
        modifier = if (placeholder) modifier.background(Palette.surfaceHigh) else modifier)
}


/** "★ 7.9" in a dark chip, for posters. */
@Composable
fun ScoreBadge(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Star, null, tint = Color.White, modifier = Modifier.size(10.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

fun SearchResponse.scoreText(): String? = score?.toString(10)?.takeIf { it != "0" && it != "0.0" }

/** A poster in a catalog row or a grid: image with its score, the title under it. */
@Composable
fun PosterCard(
    item: SearchResponse,
    width: Dp,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    // Hidden titles (long-press → Hide) don't show anywhere a poster is listed.
    if (io.github.sherazi5110.cloudstream.Hidden.isHidden(item.url)) return
    var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(modifier.width(width)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .card(onFocus = onFocus, onLongClick = onLongClick ?: { menu = true }, selectFirst = true, onClick = onClick)
        ) {
            androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                androidx.compose.material3.DropdownMenuItem(text = { Text("Hide this title") }, onClick = {
                    menu = false
                    io.github.sherazi5110.cloudstream.Hidden.hide(item)
                    io.github.sherazi5110.cloudstream.App.toast("Hidden. Restore it in Settings → Profiles → Hidden titles")
                })
            }
            NetImage(item.posterUrl, Modifier.fillMaxSize(), item.posterHeaders)
            item.scoreText()?.let { ScoreBadge(it, Modifier.align(Alignment.TopStart).padding(6.dp)) }
            PosterRating(item, Modifier.align(Alignment.BottomStart).padding(6.dp))
        }
        Text(
            item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** A Continue Watching card: wide artwork, title, what's left, and a progress bar. */
@Composable
fun WideCard(
    title: String,
    subtitle: String,
    imageUrl: String?,
    progress: Float,
    width: Dp,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier.width(width).aspectRatio(16f / 9f)
            .card(onFocus = onFocus, onLongClick = onLongClick, selectFirst = true, onClick = onClick)
    ) {
        NetImage(imageUrl, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp))
            ProgressLine(progress)
        }
    }
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Palette.accent))
    }
}

/** A rounded chip: "PG-13", "TMDB 8.2", a season… */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, label: String? = null, labelColor: Color = Palette.muted, outlined: Boolean = false) {
    Row(
        modifier
            .then(if (outlined) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(6.dp)) else Modifier.background(Palette.surfaceHigh, RoundedCornerShape(6.dp)))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        label?.let { Text(it, color = labelColor, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

/** The big rounded buttons ("Play", "More Info", "Resume S1 E2"). */
@Composable
fun PillButton(
    text: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .card(shape = shape, focusScale = 1.04f, onClick = onClick)
            .background(if (primary) Palette.accent else Color.White.copy(alpha = 0.16f), shape)
            .padding(horizontal = if (compact) 18.dp else 22.dp, vertical = if (compact) 9.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let { Icon(it, null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A round icon with a caption under it (My list · Watched · Trailer · Sources). */
@Composable
fun CircleAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = Color.White, onClick: () -> Unit) {
    Column(modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(44.dp).card(shape = CircleShape, onClick = onClick).background(Palette.surfaceHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp)) }
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) {
            Text(action, style = MaterialTheme.typography.labelMedium, modifier = Modifier.card(RoundedCornerShape(6.dp), onClick = onAction).padding(6.dp))
        }
    }
}

@Composable
fun Spinner(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Palette.accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
    }
}

/** A friendly empty state with an optional button. */
@Composable
fun EmptyState(title: String, text: String, modifier: Modifier = Modifier, button: String? = null, onButton: (() -> Unit)? = null) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Logo(64.dp)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (button != null && onButton != null) PillButton(button, null, Modifier.padding(top = 8.dp), onClick = onButton)
    }
}

/** Phone or TV, for layouts that differ. */
val isTv: Boolean get() = App.isTv

/** Minutes as "2h 4m". */
fun formatMinutes(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

/** TMDB / IMDb / AniList facts for a title page or banner (null until loaded, or when nothing matched). */
@Composable
fun rememberMetadata(r: com.lagradost.cloudstream3.LoadResponse?, version: Int = 0): io.github.sherazi5110.cloudstream.Metadata.Info? {
    val state = androidx.compose.runtime.produceState<io.github.sherazi5110.cloudstream.Metadata.Info?>(null, r?.url, version) {
        value = r?.let { runCatching { io.github.sherazi5110.cloudstream.Metadata.details(it) }.getOrNull() }
    }
    return state.value
}

/** "TMDB 8.4" on a poster (when a TMDB key is set and poster ratings are on). */
@Composable
fun PosterRating(item: SearchResponse, modifier: Modifier = Modifier) {
    val m = io.github.sherazi5110.cloudstream.Metadata
    if (!m.posterRatings || !(m.hasTmdb || m.anilist)) return
    val text = androidx.compose.runtime.produceState<String?>(null, item.url) { value = runCatching { m.posterRating(item) }.getOrNull() }.value ?: return
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Palette.tmdb, modifier = modifier
        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
        .border(1.dp, Palette.tmdb.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
        .padding(horizontal = 6.dp, vertical = 2.dp))
}

/**
 * Keys for a lazy list or grid that stay unique even when an extension returns the same item twice: Compose
 * closes the app on a repeated key ("Key … was already used"). A repeat gets "#2", "#3"…, so every item still shows.
 */
fun <T> uniqueKeys(items: List<T>, key: (T) -> Any): List<String> {
    val used = HashSet<String>(items.size * 2)
    return items.map { item ->
        val base = key(item).toString()
        var k = base
        var n = 1
        while (!used.add(k)) k = "$base#${++n}"
        k
    }
}

/** items(…) with keys that can't repeat (see uniqueKeys). */
inline fun <T> androidx.compose.foundation.lazy.LazyListScope.uniqueItems(
    items: List<T>,
    noinline key: (T) -> Any,
    crossinline content: @Composable androidx.compose.foundation.lazy.LazyItemScope.(T) -> Unit,
) {
    val keys = uniqueKeys(items, key)
    items(items.size, key = { keys[it] }) { content(items[it]) }
}

/** items(…) with keys that can't repeat, for grids (see uniqueKeys). */
inline fun <T> androidx.compose.foundation.lazy.grid.LazyGridScope.uniqueItems(
    items: List<T>,
    noinline key: (T) -> Any,
    crossinline content: @Composable androidx.compose.foundation.lazy.grid.LazyGridItemScope.(T) -> Unit,
) {
    val keys = uniqueKeys(items, key)
    items(items.size, key = { keys[it] }) { content(items[it]) }
}
