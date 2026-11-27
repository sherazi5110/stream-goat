package io.github.sherazi5110.cloudstream.ui

import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import kotlinx.coroutines.launch
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
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
    // TV with a mouse used to need two clicks (one to select); a click now opens, as in the official app.
    val firstClickSelects = false
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    val press = remember { PressState() }
    // FocusKeeper: where this card is, so a lost selection can come back to it or to its neighbour.
    val area = LocalFocusArea.current
    val kept = remember(area) { FocusKeeper.Item(requester, area) }
    androidx.compose.runtime.DisposableEffect(kept) {
        FocusKeeper.register(kept)
        onDispose { FocusKeeper.unregister(kept) }
    }
    this
        .watchDetach(kept)
        .then(Modifier.onPlaced { FocusKeeper.placed(kept, it) })
        // Read while drawing (not composing): the zoom animation doesn't rebuild the card on every frame.
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .focusRequester(requester)
        .then(if (firstClickSelects) Modifier.pointerInput(Unit) {
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
            FocusKeeper.focusChanged(kept, it.isFocused)
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
 * Rows of posters on TV, as in the official app (LinearListLayout.requestChildRectangleOnScreen): the selected card
 * slides to the row's start, so the next ones are always in view. (At the row's end the list stops by itself.)
 */
@OptIn(ExperimentalFoundationApi::class)
internal val RevealScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - containerSize * 0.04f   // the row's own side padding
}

/**
 * Pages of rows on TV, as in the official app (MainActivity.centerView): the selected row is brought to the middle
 * of the screen, so the rows above and below show and nothing sits under the top bar.
 */
@OptIn(ExperimentalFoundationApi::class)
internal val CenterScroll = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        size >= containerSize -> offset
        // Already comfortably in view (the banner's Play button, a row in the middle): stay put. Centering it
        // pushed the banner's top under the top bar.
        offset >= containerSize * 0.15f && offset + size <= containerSize * 0.88f -> 0f
        else -> offset + size / 2f - containerSize / 2f
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvRowScroll(
    /** The row's first card: where the remote lands when it comes into the row for the first time. */
    first: androidx.compose.ui.focus.FocusRequester? = null,
    content: @Composable (androidx.compose.foundation.lazy.LazyListState) -> Unit,
) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    // A mouse (a TV's air mouse, a phone or tablet with a mouse): its wheel only scrolls the page up and down, so
    // a row shows ‹ › buttons at its ends while the pointer is over it, to move it back and forward.
    var hovered by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rowWithArrows: @Composable () -> Unit = {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.pointerInput(Unit) {
            // Seen before the cards: hold the mouse button and drag to move the row (as in the official app);
            // a drag doesn't open the card it started on.
            var downX = 0f
            var lastX = 0f
            var pressed = false
            var dragging = false
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.type == androidx.compose.ui.input.pointer.PointerType.Mouse } ?: continue
                    when (e.type) {
                        androidx.compose.ui.input.pointer.PointerEventType.Exit -> hovered = false
                        androidx.compose.ui.input.pointer.PointerEventType.Press -> { hovered = true; pressed = true; dragging = false; downX = c.position.x; lastX = downX }
                        androidx.compose.ui.input.pointer.PointerEventType.Move -> {
                            hovered = true
                            if (pressed && c.pressed) {
                                if (!dragging && kotlin.math.abs(c.position.x - downX) > viewConfiguration.touchSlop) dragging = true
                                if (dragging) { state.dispatchRawDelta(lastX - c.position.x); c.consume() }
                                lastX = c.position.x
                            }
                        }
                        androidx.compose.ui.input.pointer.PointerEventType.Release -> { if (dragging) c.consume(); pressed = false; dragging = false }
                        else -> hovered = true
                    }
                }
            }
        }) {
            content(state)
            val step = with(androidx.compose.ui.platform.LocalDensity.current) { (maxWidth * 0.8f).toPx() }
            @Composable
            fun Arrow(forward: Boolean, align: Alignment) {
                val can = if (forward) state.canScrollForward else state.canScrollBackward
                if (hovered && can) androidx.compose.foundation.layout.Box(
                    Modifier.align(align).padding(horizontal = 6.dp).size(52.dp)
                        .background(Color.Black.copy(alpha = 0.62f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                        .clickable(interactionSource = null, indication = null) {
                            scope.launch { state.animateScrollBy(if (forward) step else -step) }
                        }
                        .focusProperties { canFocus = false },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (forward) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        if (forward) "Forward" else "Back", tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
            Arrow(false, Alignment.CenterStart)
            Arrow(true, Alignment.CenterEnd)
        }
    }
    if (isTv) androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides RevealScroll) {
        // Right on the last card stays put (it used to jump to the profile button at the far side of the screen).
        androidx.compose.foundation.layout.Box(Modifier
            .focusProperties { onExit = { if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Right) cancelFocusChange() } }
            // Coming back to a row (Up/Down from the next row) returns to the card that was selected there, instead
            // of whichever card happens to be nearest on screen.
            .then(if (first != null) Modifier.focusRestorer(first) else Modifier.focusRestorer())) { rowWithArrows() }
    }
    else rowWithArrows()
}

/**
 * For a menu opened by holding OK: the remote keeps sending OK while it's held, and the menu (its own window)
 * received those and the release, so letting go picked its first entry ("Mark as watched"). Until a new OK press,
 * OK is ignored here: the menu waits for you to move to an entry and press OK.
 */
fun Modifier.freshOkOnly(): Modifier = composed {
    var armed by remember { mutableStateOf(false) }
    onPreviewKeyEvent { e ->
        val k = e.nativeKeyEvent
        val ok = k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
            k.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER || k.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A
        when {
            !ok -> false
            k.action == android.view.KeyEvent.ACTION_DOWN && k.repeatCount == 0 -> { armed = true; false }
            k.action == android.view.KeyEvent.ACTION_DOWN -> !armed      // held from before the menu
            else -> !armed                                                               // the release of that hold
        }
    }
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
    // Settings → Appearance → Posters: wide cards (about the same height as a tall poster's two thirds).
    val wide = io.github.sherazi5110.cloudstream.Settings.widePosters
    Column(modifier.width(if (wide) width * 1.6f else width)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f)
                .card(onFocus = onFocus, onLongClick = onLongClick ?: { menu = true }, selectFirst = true, onClick = onClick)
        ) {
            androidx.compose.material3.DropdownMenu(menu, { menu = false }, Modifier.freshOkOnly()) {
                androidx.compose.material3.DropdownMenuItem(text = { Text("Hide this title") }, onClick = {
                    menu = false
                    io.github.sherazi5110.cloudstream.Hidden.hide(item)
                    io.github.sherazi5110.cloudstream.App.toast("Hidden. Restore it in Settings → Profiles → Hidden titles")
                })
            }
            if (wide) WideArt(item) else NetImage(item.posterUrl, Modifier.fillMaxSize(), item.posterHeaders)
            item.scoreText()?.let { ScoreBadge(it, Modifier.align(Alignment.TopStart).padding(6.dp)) }
            WatchedStamp(item)
            PosterRating(item, Modifier.align(Alignment.BottomStart).padding(6.dp))
        }
        Text(
            item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/**
 * A wide card's picture: the extension's own picture, whole (a wide one fills the card, a tall one sits in the middle),
 * over a blurred, dimmed copy of itself. Nothing is looked up elsewhere, so it loads as fast as a tall poster.
 */
@Composable
private fun WideArt(item: SearchResponse) {
    // TVs skip the blur (a blur per card is heavy for a TV box's graphics); the copy is dimmed more instead.
    NetImage(item.posterUrl, Modifier.fillMaxSize().then(if (isTv) Modifier else Modifier.blur(18.dp)), item.posterHeaders)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (isTv) 0.6f else 0.35f)))
    NetImage(item.posterUrl, Modifier.fillMaxSize(), item.posterHeaders, contentScale = androidx.compose.ui.layout.ContentScale.Fit, placeholder = false)
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

/**
 * A row you open with OK that has buttons at its end (delete, ⋮…). The buttons sit beside the row's main part, not
 * inside it: the remote can't reach a button inside a selectable row, so → goes from the row to its buttons.
 * [modifier] and [background] are for the whole row, [padding] for its main part.
 */
@Composable
fun ActionRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    background: Color = Color.Transparent,
    padding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(10.dp),
    focusScale: Float = 1.02f,
    onLongClick: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(modifier.background(background, shape), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).card(shape, focusScale, onLongClick = onLongClick, onClick = onClick).padding(padding),
            verticalAlignment = Alignment.CenterVertically, content = content)
        Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
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
val isTv: Boolean get() = tvForTests ?: App.isTv
/** The remote-control tests run the TV layout without an Android TV. */
internal var tvForTests: Boolean? = null

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

/**
 * A big green rubber stamp across a watched poster: "WATCHED" (a film) or "WATCHING" (a show), watched here or on
 * WeTrakr (matched by the title's ids, so it shows whichever extension lists it). The poster is dimmed a little so the
 * stamp reads. Redraws when watched marks change.
 */
@Composable
fun WatchedStamp(item: SearchResponse, modifier: Modifier = Modifier) {
    val version = io.github.sherazi5110.cloudstream.WeTrakrSync.version
    val stamp = androidx.compose.runtime.produceState<io.github.sherazi5110.cloudstream.WeTrakrSync.Stamp?>(null, item.url, version) {
        value = runCatching { io.github.sherazi5110.cloudstream.WeTrakrSync.stamp(item) }.getOrNull()
    }.value ?: return
    if (stamp.big) StampMark(stamp.label, modifier)
    else Box(modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.TopEnd) {
        val color = if (stamp == io.github.sherazi5110.cloudstream.WeTrakrSync.Stamp.NEW_EPISODES) Color(0xFFFF9F1C) else Palette.accent
        Text((if (stamp == io.github.sherazi5110.cloudstream.WeTrakrSync.Stamp.CONTINUE) "▶ " else "") + stamp.label.uppercase(),
            fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1,
            modifier = Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
    }
}

/** The stamp itself: [label] in a tilted double green frame over the whole poster. */
@Composable
fun StampMark(label: String, modifier: Modifier = Modifier) {
    val green = Color(0xFF2EE06A)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)), contentAlignment = Alignment.Center) {
        val w = maxWidth
        val text = label.uppercase()
        Box(
            Modifier.graphicsLayer { rotationZ = -18f }
                .border((w.value / 55f).coerceIn(1.5f, 3.5f).dp, green, RoundedCornerShape(w * 0.05f))
                .padding((w.value / 60f).coerceIn(1.5f, 3f).dp)
                .border((w.value / 110f).coerceIn(1f, 2f).dp, green.copy(alpha = 0.85f), RoundedCornerShape(w * 0.035f))
                .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(w * 0.035f))
                .padding(horizontal = w * 0.06f, vertical = w * 0.02f),
        ) {
            Text(text, color = green, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false,
                fontSize = (w.value * if (text.length > 7) 0.135f else 0.16f).sp,
                letterSpacing = (w.value * 0.008f).sp)
        }
    }
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
