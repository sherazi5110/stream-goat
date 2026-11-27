package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.horizontalScroll
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import io.github.sherazi5110.cloudstream.Settings
import io.github.sherazi5110.cloudstream.player.PlayerController
import io.github.sherazi5110.cloudstream.player.Zoom
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

private enum class Sheet(val title: String) { SOURCES("Sources"), SUBTITLES("Subtitles"), ONLINE("Online subtitles"), TRANSLATE("Translate subtitles"), AUDIO("Audio"), BOOST("Volume boost"), ENGINE("Player engine"), SPEED("Speed"), ZOOM("Zoom"), QUALITY("Quality"),
    SUB_SYNC("Sync subtitles"), AUDIO_SYNC("Sync audio"), SUB_STYLE("Subtitle style") }

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun PlayerScreen(screen: Screen.Player) {
    val c = remember(screen) { PlayerController(screen.api, screen.response, screen.playable, screen.chooseSource, live = screen.channel != null) }
    val scope = rememberCoroutineScope()
    // CH+ / CH− (and page up/down) move through the Live TV list.
    val zap: (Int) -> Boolean = { step ->
        val list = screen.channels; val ch = screen.channel
        if (list != null && ch != null) { LivePlayback.neighbour(list, ch, step)?.let { LivePlayback.play(scope, it, list, replace = true) }; true } else false
    }
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val view = LocalView.current
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    // "Let me choose the source" (Settings → Player): the list opens at once and fills in as sources are found.
    var sheet by remember(screen) { mutableStateOf(if (screen.chooseSource || (!Settings.autoPlay && screen.channel == null)) Sheet.SOURCES else null) }
    var poke by remember { mutableIntStateOf(0) } // any interaction restarts the hide timer
    var pinch by remember { mutableFloatStateOf(1f) }
    val rootFocus = remember { FocusRequester() }
    // Next-episode countdown: shown from the credits (or 20 s before the end); seeking back resets it.
    var countdownLeft by remember(c) { mutableIntStateOf(-1) } // -1 = not showing
    var countdownCancelled by remember(c) { mutableStateOf(false) }
    val countdownAt = c.countdownStart()
    val inCountdown = Settings.autoPlayNext && countdownAt != null && c.started && c.time >= countdownAt && !c.ended
    LaunchedEffect(inCountdown) { if (!inCountdown) { countdownLeft = -1; countdownCancelled = false } }
    LaunchedEffect(inCountdown, countdownCancelled) {
        if (!inCountdown || countdownCancelled) { countdownLeft = -1; return@LaunchedEffect }
        for (n in 10 downTo 1) { countdownLeft = n; delay(1000) }
        c.next?.let { Nav.replace(Screen.Player(c.api, c.response, it)) }
    }
    val playNext: () -> Unit = { c.next?.let { Nav.replace(Screen.Player(c.api, c.response, it)) } }

    DisposableEffect(c) { onDispose { c.release() } }

    // Choosing a source keeps the phone as it's held; it turns to landscape once a video plays.
    val turn = !isTv && (c.started || sheet != Sheet.SOURCES)
    LaunchedEffect(turn) { if (turn) activity?.requestedOrientation = orientationOf(Settings.playerOrientation) }
    // Full screen, landscape on phones, screen kept on.
    DisposableEffect(Unit) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        val orientation = activity?.requestedOrientation
        view.keepScreenOn = true
        io.github.sherazi5110.cloudstream.Music.pauseForVideo()
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            if (!isTv) activity?.requestedOrientation = orientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            view.keepScreenOn = false
            window?.attributes = window?.attributes?.apply { screenBrightness = -1f }
        }
    }

    // Pause when the app goes to the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { if (c.playing) c.pause(); c.save() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // The end: next episode (if on), else back to the title page.
    LaunchedEffect(c.ended) {
        if (!c.ended) return@LaunchedEffect
        val next = c.next
        if (next != null && Settings.autoPlayNext) Nav.replace(Screen.Player(c.api, c.response, next)) else Nav.back()
    }

    LaunchedEffect(controls, poke, c.playing, sheet) {
        if (controls && c.playing && sheet == null) {
            delay(if (isTv) 5000 else 4000)
            controls = false
        }
    }
    LaunchedEffect(controls, c.started) { if ((!controls || !c.started) && sheet == null) runCatching { rootFocus.requestFocus() } }
    // With the controls hidden the picture holds the remote's selection (OK pauses, ←/→ seek): nothing to put back.
    androidx.compose.runtime.SideEffect { FocusKeeper.paused = c.started && !controls && sheet == null }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { FocusKeeper.paused = false } }

    // Live TV, full screen (like TiviMate): ← a see-through channel list over the picture, → or Info the channel's
    // banner (now, next, quality), Menu the channel's menu. ↑/↓ change channel.
    val liveChannels = screen.channels; val liveChannel = screen.channel
    var liveList by remember(screen) { mutableStateOf(false) }
    var liveMenu by remember(screen) { mutableStateOf(false) }
    var bannerUntil by remember(screen) { mutableStateOf(if (liveChannel != null) System.currentTimeMillis() + 5000 else 0L) }
    var bannerTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(bannerUntil) { val wait = bannerUntil - System.currentTimeMillis(); if (wait > 0) { delay(wait); bannerTick++ } }
    val showBanner = { bannerUntil = System.currentTimeMillis() + 5000 }
    // The channel list or menu closed: the picture takes the remote again (↑/↓ change channel, ← the list…).
    LaunchedEffect(liveList, liveMenu) { if (liveChannel != null && !liveList && !liveMenu && !controls && sheet == null) { delay(50); runCatching { rootFocus.requestFocus() } } }

    BackHandler {
        when {
            liveMenu -> liveMenu = false
            liveList -> liveList = false
            sheet != null -> sheet = null
            countdownLeft > 0 -> countdownCancelled = true
            isTv && controls && c.started -> controls = false
            else -> Nav.back()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            // A button that disappears (a panel closing, Next episode ending) drops the selection here: FocusKeeper
            // then puts it on the nearest button instead of leaving the remote to start from the top-left.
            .focusFallback()
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                poke++
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { c.togglePause(); true }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { c.resume(); true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { c.pause(); true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { c.seekBy(30_000); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { c.seekBy(-30_000); true }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> { c.next?.let { Nav.replace(Screen.Player(c.api, c.response, it)) }; true }
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> zap(-1)
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> zap(1)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!controls && sheet == null && countdownLeft > 0) { playNext(); true }
                        else if (!controls && sheet == null && c.activeSegment != null) { c.skipSegment(); true }
                        else if (!controls && c.started && sheet == null) { c.togglePause(); controls = true; true } else false
                    // Live TV with the picture alone on screen: the channel keys of a TV remote.
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (liveChannel != null && liveChannels != null && !controls && sheet == null && !liveList && !liveMenu) {
                        zap(if (e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                    } else if (!controls && c.started && sheet == null && !liveList && !liveMenu) { controls = true; true } else false
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (liveChannel != null && liveChannels != null && !controls && sheet == null && !liveList && !liveMenu) { liveList = true; true }
                        else if (!controls && c.started && sheet == null && !liveList && !liveMenu) { c.seekBy(-10_000); true } else false
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> if (liveChannel != null && !controls && sheet == null && !liveList && !liveMenu) { showBanner(); true }
                        else if (e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !controls && c.started && sheet == null && !liveList && !liveMenu) { c.seekBy(10_000); true } else false
                    KeyEvent.KEYCODE_MENU -> if (liveChannel != null && sheet == null && !liveList) { liveMenu = true; true }
                        else if (!controls && c.started && sheet == null) { controls = true; true } else false
                    else -> false
                }
            }
    ) {
        // A new view per playback (next episode replaces the controller) and per engine (each draws on its own kind).
        key(c, c.engine) {
            AndroidView(
                factory = { ctx -> c.createView(ctx).also { c.attach(it) } },
                onRelease = { c.detach(it) },
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = if (c.hdrSurface) 1f else pinch, scaleY = if (c.hdrSurface) 1f else pinch),
            )
        }

        // Settings → Player → Ask every time: which player plays this video.
        if (c.askingEngine && c.current != null) {
            var remember by remember { mutableStateOf(false) }
            val kind = c.sourceKind()
            SimpleDialog("Play with", { c.chooseEngine(io.github.sherazi5110.cloudstream.player.EngineKind.valueOf(Settings.engineFor(kind.key)), false) }) {
                Text(c.current?.name.orEmpty() + " · " + kind.label, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                io.github.sherazi5110.cloudstream.player.EngineKind.entries.forEach { k ->
                    val isDefault = Settings.engineFor(kind.key) == k.name
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp).card(RoundedCornerShape(12.dp)) { c.chooseEngine(k, remember) }
                        .background(if (isDefault) Palette.accent.copy(alpha = 0.22f) else Palette.surface, RoundedCornerShape(12.dp)).padding(14.dp)) {
                        Text(k.label + if (isDefault) "  (default)" else "", style = MaterialTheme.typography.titleMedium)
                        Text(k.detail, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    }
                }
                Row(Modifier.padding(top = 10.dp).card(RoundedCornerShape(8.dp)) { remember = !remember }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(remember, null)
                    Text("Always use it for ${kind.label.lowercase()}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (!c.started) {
            LoadingOverlay(c, onChoose = { c.stopAutoPlay(); sheet = Sheet.SOURCES })
        } else {
            if (!isTv) Gestures(locked, onTap = { controls = !controls; poke++ }, onSeek = { c.seekBy(it); poke++ }, onPinch = { pinch = (pinch * it).coerceIn(1f, 4f) })
            // TV with a mouse: a click on the picture shows the controls (and settings buttons), another hides them.
            else Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { controls = !controls; poke++ } })
            if (c.buffering && c.error == null) Spinner(Modifier.fillMaxSize())
            if (c.slowWarning && c.error == null) {
                Row(Modifier.align(Alignment.TopCenter).padding(top = 72.dp).widthIn(max = 640.dp)
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your internet seems too slow for this video. A lower quality or another source will play smoother.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (c.variants.size > 1) PillButton("Lower quality", null, primary = false, compact = true) { c.slowWarning = false; sheet = Sheet.QUALITY }
                    PillButton("Sources", null, primary = false, compact = true) { c.slowWarning = false; sheet = Sheet.SOURCES }
                    IconAction(androidx.compose.material.icons.Icons.Filled.Clear, "Dismiss") { c.slowWarning = false }
                }
            }
            c.error?.let {
                Column(Modifier.align(Alignment.Center).widthIn(max = 560.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(10.dp)).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                    // Not the link's fault: one tap writes it up for the developer (GitHub issue, filled in).
                    c.appIssue?.let { details ->
                        PillButton("Report to the developer", Icons.Filled.BugReport, modifier = Modifier.padding(top = 12.dp)) {
                            io.github.sherazi5110.cloudstream.GlitchReport.draft = io.github.sherazi5110.cloudstream.GlitchReport.Draft(
                                io.github.sherazi5110.cloudstream.GlitchReport.describe(Nav.top), io.github.sherazi5110.cloudstream.GlitchReport.steps(), null, crash = details)
                        }
                    }
                }
            }
            if (locked) {
                AnimatedVisibility(controls, Modifier.align(Alignment.TopEnd), enter = fadeIn(), exit = fadeOut()) {
                    RoundIcon(Icons.Filled.Lock, "Unlock", Modifier.padding(24.dp)) { locked = false; poke++ }
                }
            } else AnimatedVisibility(controls && !inPip(), enter = fadeIn(), exit = fadeOut()) {
                Controls(c, sheetOpen = sheet != null, onSheet = { sheet = it }, onLock = { locked = true; poke++ }, poke = { poke++ })
            }
            val seg = c.activeSegment
            if (countdownLeft > 0) {
                NextEpisodeCard(c, countdownLeft, Modifier.align(Alignment.BottomEnd).padding(end = 36.dp, bottom = 110.dp),
                    onPlay = playNext, onCancel = { countdownCancelled = true })
            } else if (seg != null && !locked) {
                PillButton(seg.kind.label + (if (isTv && !controls) "  (OK)" else ""), Icons.Filled.SkipNext,
                    Modifier.align(Alignment.BottomEnd).padding(end = 36.dp, bottom = 110.dp), primary = false) { c.skipSegment() }
            }
        }

        if (liveChannel != null) {
            bannerTick.let { }
            if (!controls && sheet == null && !liveList && System.currentTimeMillis() < bannerUntil)
                ChannelBanner(liveChannel, liveChannels, c.measured, Modifier.align(Alignment.BottomCenter))
            if (liveList && liveChannels != null) LiveChannelList(liveChannels, liveChannel, Modifier.align(Alignment.CenterStart),
                onPick = { ch -> liveList = false; if (ch.key != liveChannel.key) LivePlayback.play(scope, ch, liveChannels, replace = true) },
                onClose = { liveList = false })
            if (liveMenu) {
                var fav by remember { mutableStateOf(liveChannel.key in io.github.sherazi5110.cloudstream.LiveTv.favourites()) }
                SimpleDialog(liveChannel.name, { liveMenu = false }) {
                    @Composable
                    fun item(label: String, action: () -> Unit) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .card(RoundedCornerShape(10.dp)) { liveMenu = false; action() }.background(Palette.surfaceHigh, RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 11.dp))
                    }
                    item(if (fav) "★  Remove from favourites" else "☆  Add to favourites") { fav = io.github.sherazi5110.cloudstream.LiveTv.toggleFavourite(liveChannel) }
                    if (liveChannels != null) item("☰  Channel list") { liveList = true }
                    item("ⓘ  What's on") { showBanner() }
                    item("⇄  Try another link") { sheet = Sheet.SOURCES }
                    item("CC  Subtitles") { sheet = Sheet.SUBTITLES }
                    item("♪  Audio") { sheet = Sheet.AUDIO }
                }
            }
        }

        sheet?.let { s ->
            if (s == Sheet.SOURCES) SourcesPanel(c) { sheet = null }
            else if (s == Sheet.SUB_STYLE) SubtitleStylePanel(c) { sheet = null }
            else if (s == Sheet.SUB_SYNC || s == Sheet.AUDIO_SYNC) SyncPanel(c, audio = s == Sheet.AUDIO_SYNC) { sheet = null }
            else SheetPanel(s, c, Modifier.align(Alignment.CenterEnd), onOpen = { sheet = it }) { sheet = null }
        }
    }
}

/** "Next episode in N s" with Play now / Cancel. OK on the remote plays, Back cancels. */
@Composable
private fun NextEpisodeCard(c: PlayerController, left: Int, modifier: Modifier, onPlay: () -> Unit, onCancel: () -> Unit) {
    val next = c.next ?: return
    Column(
        modifier.widthIn(max = 340.dp).background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("NEXT EPISODE", style = MaterialTheme.typography.labelMedium, color = Palette.muted)
        Text(listOfNotNull(next.label, next.name?.takeIf { it != next.label }).joinToString(" · "),
            style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("Starts in $left s", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Play now", Icons.Filled.PlayArrow, compact = true, onClick = onPlay)
            PillButton("Cancel", null, primary = false, compact = true, onClick = onCancel)
        }
    }
}

/** Swipes, taps and pinch on phones: left side brightness, right side volume, double-tap ±10 s. */
@Composable
private fun Gestures(locked: Boolean, onTap: () -> Unit, onSeek: (Long) -> Unit, onPinch: (Float) -> Unit) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var indicator by remember { mutableStateOf<Pair<Boolean, Float>?>(null) } // (isBrightness, level)
    var seekFlash by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(indicator) { if (indicator != null) { delay(900); indicator = null } }
    LaunchedEffect(seekFlash) { if (seekFlash != null) { delay(600); seekFlash = null } }

    Box(Modifier.fillMaxSize()
        .pointerInput(locked) {
            detectTapGestures(
                onTap = { onTap() },
                onDoubleTap = { p -> if (!locked) { val ms = if (p.x < size.width / 2) -10_000L else 10_000L; onSeek(ms); seekFlash = ms } },
            )
        }
        .pointerInput(locked) {
            if (locked) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val left = down.position.x < size.width / 2
                var mode = 0 // 0 undecided, 1 swipe, 2 pinch
                var total = 0f
                var level = if (left) activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f
                else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.size >= 2) {
                        mode = 2
                        onPinch(event.calculateZoom())
                        event.changes.forEach { it.consume() }
                    } else if (mode != 2) {
                        val ch = event.changes.first()
                        val dy = ch.position.y - ch.previousPosition.y
                        total += dy
                        if (mode == 0 && abs(total) > viewConfiguration.touchSlop * 2) mode = 1
                        if (mode == 1) {
                            level = (level - dy / (size.height * 0.8f)).coerceIn(0f, 1f)
                            if (left) activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = level.coerceAtLeast(0.01f) } }
                            else {
                                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt(), 0)
                            }
                            indicator = left to level
                            ch.consume()
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    ) {
        indicator?.let { (bright, level) ->
            Column(Modifier.align(if (bright) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 48.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (bright) Icons.Filled.Brightness6 else Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.White)
                Box(Modifier.padding(top = 8.dp).width(4.dp).height(110.dp).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp))) {
                    Box(Modifier.align(Alignment.BottomCenter).width(4.dp).fillMaxHeight(level).background(Color.White, RoundedCornerShape(2.dp)))
                }
            }
        }
        seekFlash?.let { ms ->
            Text(if (ms < 0) "−10 s" else "+10 s", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(if (ms < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 96.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(18.dp))
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, modifier: Modifier = Modifier, size: Int = 44, onClick: () -> Unit) {
    Box(modifier.size(size.dp).card(CircleShape, onClick = onClick).background(Color.Black.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size((size * 0.55).dp))
    }
}

@Composable
private fun Controls(c: PlayerController, sheetOpen: Boolean = false, onSheet: (Sheet) -> Unit, onLock: () -> Unit, poke: () -> Unit) {
    val playFocus = remember { FocusRequester() }
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode
    // When the controls appear the remote's selection goes to Play/pause (it used to bounce to the back button or
    // need an extra OK). When a panel (Audio, Subtitles, Sources…) closes, it goes back to the button that opened it
    // (FocusKeeper), not to Play/pause.
    LaunchedEffect(Unit) {
        if (!sheetOpen && (isTv || inputMode == androidx.compose.ui.input.InputMode.Keyboard)) {
            delay(80)
            val onButton = FocusKeeper.current?.let { it.alive && it.focused } == true
            if (!onButton) runCatching { playFocus.requestFocus() }
        }
    }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.7f), 0.3f to Color.Transparent, 0.65f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)))) {
        // Top: back, title, lock, clock.
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back") { Nav.back() }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(c.response.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = (if (c.playables.size > 1) c.playable.label else c.current?.name)?.let { s ->
                    c.measured?.let { m -> "$s · actual $m" + if (m.substringBefore('×').toIntOrNull()?.let { it >= 3200 } == true) " (4K)" else "" } ?: s
                }
                sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            }
            // Report a glitch: a picture of the player as it is now, the source playing and the last steps.
            val reportCtx = androidx.compose.ui.platform.LocalContext.current
            RoundIcon(Icons.Filled.BugReport, "Report a glitch", Modifier.padding(end = 8.dp)) {
                io.github.sherazi5110.cloudstream.GlitchReport.start(reportCtx.findActivity())
            }
            if (!isTv) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                // Rotation: landscape (either way), portrait (vertical videos, shorts) or as the device is held; remembered.
                RoundIcon(Icons.Filled.ScreenRotation, "Rotate") {
                    val next = when (Settings.playerOrientation) { "LANDSCAPE" -> "PORTRAIT"; "PORTRAIT" -> "AUTO"; else -> "LANDSCAPE" }
                    Settings.playerOrientation = next
                    ctx.findActivity()?.requestedOrientation = orientationOf(next)
                    io.github.sherazi5110.cloudstream.App.toast(when (next) { "PORTRAIT" -> "Portrait"; "AUTO" -> "Rotates with the device"; else -> "Landscape" })
                    poke()
                }
                // Picture in picture: the video keeps playing in a small window over other apps.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                    ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                    RoundIcon(Icons.Filled.PictureInPictureAlt, "Picture in picture", Modifier.padding(start = 8.dp)) {
                        runCatching {
                            ctx.findActivity()?.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder()
                                .setAspectRatio(android.util.Rational(16, 9)).build())
                        }.onFailure { io.github.sherazi5110.cloudstream.App.toast("Picture in picture isn't available here") }
                    }
                }
                Spacer(Modifier.width(8.dp))
                RoundIcon(Icons.Filled.LockOpen, "Lock") { onLock() }
            }
            val ends = if (c.length > 0) " · ends ${timeFormat.format(Date(now + ((c.length - c.time) / c.speed).toLong()))}" else ""
            Text(timeFormat.format(Date(now)) + ends, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp))
        }
        // Middle: −10 · play/pause · +10
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Filled.Replay10, "Back 10 seconds", size = 56) { c.seekBy(-10_000); poke() }
            RoundIcon(if (c.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", Modifier.focusRequester(playFocus), size = 76) { c.togglePause(); poke() }
            RoundIcon(Icons.Filled.Forward10, "Forward 10 seconds", size = 56) { c.seekBy(10_000); poke() }
        }
        // Bottom: seek bar and the menus.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
            if (!c.isLive) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(c.time / 1000.0), style = MaterialTheme.typography.titleSmall)
                var dragging by remember { mutableStateOf<Float?>(null) }
                val focusManager = LocalFocusManager.current
                Slider(
                    value = dragging ?: if (c.length > 0) c.time.toFloat() / c.length else 0f,
                    onValueChange = { dragging = it; poke() },
                    onValueChangeFinished = { dragging?.let { c.seekTo((it * c.length).toLong()) }; dragging = null },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Palette.accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    // Remote: ←/→ seek 10 s, ↑/↓ leave the bar (the slider would otherwise move with them).
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp).onPreviewKeyEvent { e ->
                        val down = e.type == KeyEventType.KeyDown
                        when (e.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) { c.seekBy(-10_000); poke() }; true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) { c.seekBy(10_000); poke() }; true }
                            KeyEvent.KEYCODE_DPAD_UP -> { if (down) focusManager.moveFocus(FocusDirection.Up); true }
                            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) focusManager.moveFocus(FocusDirection.Down); true }
                            else -> false
                        }
                    },
                )
                Text(clock(c.length / 1000.0), style = MaterialTheme.typography.titleSmall)
            }
            // On a phone held upright the buttons don't all fit: the row scrolls sideways instead of squeezing the last
            // one into a column of letters (issue #53). Wide screens spread them out as before.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 640.dp
            Row(Modifier.fillMaxWidth().then(if (narrow) Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()) else Modifier),
                horizontalArrangement = if (narrow) Arrangement.spacedBy(2.dp) else Arrangement.SpaceBetween) {
                BarButton(Icons.AutoMirrored.Filled.List, "Sources") { onSheet(Sheet.SOURCES) }
                BarButton(Icons.Filled.ClosedCaption, "Subtitles") { onSheet(Sheet.SUBTITLES) }
                BarButton(Icons.Filled.Translate, "Audio") { onSheet(Sheet.AUDIO) }
                // Several qualities inside this source (an HLS master): pin one, or Auto.
                if (c.variants.isNotEmpty()) BarButton(Icons.Filled.HighQuality, c.variant?.label ?: "Auto") { onSheet(Sheet.QUALITY) }
                BarButton(Icons.Filled.Memory, c.engine.kind.label) { onSheet(Sheet.ENGINE) }
                BarButton(Icons.Filled.Speed, "${c.speed}×") { onSheet(Sheet.SPEED) }
                // One tap: Fit → Fill → Stretch (Original, 16:9 and 4:3 are in Settings → Player).
                BarButton(Icons.Filled.AspectRatio, c.zoom.label) { c.cycleZoom(); poke() }
                c.next?.let { next -> BarButton(Icons.Filled.SkipNext, "Next episode") { Nav.replace(Screen.Player(c.api, c.response, next)) } }
            }
            }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.card(RoundedCornerShape(8.dp), onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
    }
}

/** Before playback: artwork, how many sources were found, and Play now / Choose source / Cancel. */
@Composable
private fun LoadingOverlay(c: PlayerController, onChoose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        NetImage(c.response.backgroundPosterUrl ?: c.response.posterUrl, Modifier.fillMaxSize(), c.response.posterHeaders)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)))
        Column(Modifier.align(Alignment.Center).widthIn(max = 560.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(c.response.name, style = MaterialTheme.typography.displayMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (c.playables.size > 1) c.playable.label else c.api.name, style = MaterialTheme.typography.titleMedium, color = Palette.muted, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(24.dp))
            val torrent = c.torrentStatus
            val status = when {
                torrent != null && !c.started -> "Torrent · " + (if (torrent.peers == 0 && torrent.ready == 0f) torrent.phase
                    else "${torrent.peers} peer${if (torrent.peers == 1) "" else "s"} · ${"%.1f".format(torrent.bytesPerSecond / 1_048_576.0)} MB/s · buffering ${(torrent.ready * 100).toInt()}%")
                c.current != null -> "Starting ${c.current!!.name}…"
                c.searching -> "Looking for sources…"
                c.links.isEmpty() -> c.searchError ?: "No sources found"
                else -> "Pick a source"
            }
            Row(Modifier.fillMaxWidth()) {
                Text(status, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${c.links.size} SOURCE${if (c.links.size == 1) "" else "S"}", style = MaterialTheme.typography.labelMedium, color = Palette.muted)
            }
            if (c.searching || c.current != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 10.dp), color = Palette.accent, trackColor = Color.White.copy(alpha = 0.15f))
            else Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                // While a source is starting, "Play now" would only restart it: offer the next one instead. One button
                // that changes its words, so the remote's selection stays on it (two buttons swapped dropped it).
                if (c.current != null || c.links.isNotEmpty()) {
                    val starting = c.current != null
                    PillButton(if (starting) "Next source" else if (c.searching) "Play now (${c.links.size})" else "Play now",
                        if (starting) Icons.Filled.SkipNext else Icons.Filled.PlayArrow) {
                        if (c.current != null) c.nextSource() else c.ranked().firstOrNull()?.let { c.play(it) }
                    }
                }
                if (c.links.isNotEmpty()) PillButton("Choose source", Icons.AutoMirrored.Filled.List, primary = false) { onChoose() }
                PillButton("Cancel", null, primary = false) { Nav.back() }
            }
        }
    }
}

private fun ExtractorLink.qualityText(): String = when {
    quality == Qualities.Unknown.value || quality <= 0 -> ""
    quality >= 2160 -> "4K"
    else -> "${quality}p"
}

@Composable
private fun SheetPanel(sheet: Sheet, c: PlayerController, modifier: Modifier, onOpen: (Sheet) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Online subtitles: searched when the panel opens.
    var online by remember(sheet) { mutableStateOf<List<io.github.sherazi5110.cloudstream.OnlineSubtitles.Result>?>(null) }
    var onlineError by remember(sheet) { mutableStateOf<String?>(null) }
    if (sheet == Sheet.ONLINE) LaunchedEffect(Unit) {
        runCatching {
            io.github.sherazi5110.cloudstream.OnlineSubtitles.search(io.github.sherazi5110.cloudstream.OnlineSubtitles.query(c.response, c.playable.season, c.playable.episode))
        }.onSuccess { list ->
            val wanted = io.github.sherazi5110.cloudstream.Settings.subtitleLanguage.split(',').map { it.trim() }.filter { it.isNotBlank() }
            online = list.sortedBy { r -> if (wanted.any { io.github.sherazi5110.cloudstream.OnlineSubtitles.matches(r.language, it) }) 0 else 1 }
        }.onFailure { onlineError = it.message ?: it.toString(); online = emptyList() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { onClose() } })
    val first = remember { FocusRequester() }
    val empty = remember { FocusRequester() }
    FocusOverlay("player panel") { trap ->
    Column(trap.then(modifier).fillMaxHeight().width(340.dp).glassPanel(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp), blur = false).padding(vertical = 16.dp)) {
        Text(sheet.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        data class Option(val label: String, val detail: String?, val selected: Boolean, val action: () -> Unit)
        val options: List<Option> = when (sheet) {
            Sheet.SOURCES -> c.ranked().map { l ->
                Option(l.name, listOf(l.qualityText(), l.source.takeIf { it != l.name }.orEmpty(), if (l.isM3u8) "HLS" else "").filter { it.isNotBlank() }.joinToString(" · "),
                    l.url == c.current?.url) { c.play(l); onClose() }
            }
            Sheet.SUBTITLES -> buildList {
                val spu = c.spuTracks()
                val currentSpu = c.spuTrack()
                add(Option("Off", null, currentSpu == -1 && c.selectedSubtitle == null) { c.setSpu(-1); onClose() })
                spu.filter { it.id != -1 }.forEach { t -> add(Option(t.name, "In the video", t.id == currentSpu && c.selectedSubtitle == null) { c.setSpu(t.id); onClose() }) }
                c.subtitles.forEach { s -> add(Option(s.lang, "From ${c.api.name}", s.url == c.selectedSubtitle) { c.addSubtitle(s); onClose() }) }
                c.addedFiles.forEach { (f, name) -> add(Option(name, if ("→" in name) "Translated" else "Online", f.absolutePath == c.selectedSubtitle) { c.selectFile(f); onClose() }) }
                add(Option("Sync subtitles", if (c.subtitleDelay == 0L) "Earlier or later, to match the voices" else delayText(c.subtitleDelay), false) { onOpen(Sheet.SUB_SYNC) })
                add(Option("Subtitle style", "Size, colour, font, outline, background, position…", false) { onOpen(Sheet.SUB_STYLE) })
                if (!c.isLive) add(Option("Search online…", "OpenSubtitles, no account", false) { onOpen(Sheet.ONLINE) })
                if (c.translateSourceName() != null) add(Option("Translate subtitles to…", c.translating?.let { "Translating to $it…" } ?: "Hindi, Urdu, Chinese, French and more", false) { onOpen(Sheet.TRANSLATE) })
            }
            Sheet.TRANSLATE -> io.github.sherazi5110.cloudstream.SubtitleTranslator.languages.map { (code, name) ->
                Option(name, if (code == io.github.sherazi5110.cloudstream.SubtitleTranslator.languages.first().first) "From: ${c.translateSourceName()}" else null, false) { c.translateSubtitles(code); onClose() }
            }
            Sheet.ONLINE -> online.orEmpty().map { r ->
                Option(r.languageName, r.id, false) {
                    // In the player's own scope: this panel closes at once, and its scope would cancel the download.
                    c.addOnlineSubtitle(r)
                    onClose()
                }
            }
            Sheet.AUDIO -> c.audioTracks().filter { it.id != -1 }.map { t -> Option(t.name, null, t.id == c.audioTrack()) { c.setAudio(t.id); onClose() } } +
                Option("Volume boost: ${c.boost}%", "Louder than the device's maximum, for quiet streams", c.boost > 100) { onOpen(Sheet.BOOST) } +
                Option(if (Settings.passthrough) "Surround passthrough (5.1/7.1): On" else "Surround passthrough (5.1/7.1): Off",
                    "On: Dolby and DTS go as they are to your receiver or soundbar. Off: the app plays 5.1 itself (all channels on TVs and receivers, mixed to stereo on phones)",
                    Settings.passthrough) {
                    Settings.passthrough = !Settings.passthrough
                    c.reopen()
                    onClose()
                } +
                Option("Sync audio", if (c.audioDelay == 0L) "Earlier or later, to match the lips" else delayText(c.audioDelay), false) { onOpen(Sheet.AUDIO_SYNC) } +
                Option(if (Settings.levelAudio) "Even out loud and quiet sounds: On" else "Even out loud and quiet sounds: Off",
                    "Quieter music and explosions, clearer voices (good on TVs)", Settings.levelAudio) {
                    Settings.levelAudio = !Settings.levelAudio
                    c.reopen()
                    onClose()
                }
            Sheet.BOOST -> listOf(100, 125, 150, 175, 200).map { v -> Option("$v%", if (v == 100) "Normal" else "Louder (may distort)", v == c.boost) { c.changeBoost(v); onClose() } }
            Sheet.ENGINE -> listOf(Option("Automatic (Settings)", "ExoPlayer for extension streams; VLC for Live TV, torrents and files",
                    c.pinnedEngine == null) { c.useEngine(null); onClose() }) +
                io.github.sherazi5110.cloudstream.player.EngineKind.entries.map { k ->
                    Option(k.label + if (c.engine.kind == k) " (playing)" else "", k.detail, c.pinnedEngine == k) { c.useEngine(k); onClose() }
                }
            Sheet.SPEED -> listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { r -> Option("$r×", if (r == 1f) "Normal" else null, r == c.speed) { c.setRate(r); onClose() } }
            Sheet.ZOOM -> Zoom.entries.map { z -> Option(z.label, null, z == c.zoom) { c.setZoomMode(z); onClose() } }
            Sheet.SUB_SYNC, Sheet.AUDIO_SYNC, Sheet.SUB_STYLE -> emptyList()
            Sheet.QUALITY -> listOf(Option("Auto", "Changes with your connection", c.variant == null) { c.setQuality(null); onClose() }) +
                c.variants.map { v -> Option(v.label, v.bandwidth?.let { "${it / 1000} kbps" }, v == c.variant) { c.setQuality(v); onClose() } }
        }
        // The remote starts on what's chosen now (the subtitle or audio track playing, the speed…), not on the first
        // line; while there's nothing to choose yet (online subtitles still searching) it waits on the message.
        val chosen = remember(sheet) { options.indexOfFirst { it.selected }.coerceAtLeast(0) }
        val listState = remember(sheet) { androidx.compose.foundation.lazy.LazyListState(chosen) }
        LaunchedEffect(sheet, options.isEmpty()) {
            delay(30)
            runCatching { if (options.isEmpty()) empty.requestFocus() else first.requestFocus() }
        }
        if (options.isEmpty()) Text(
            when (sheet) {
                Sheet.SOURCES -> if (c.searching) "Looking for sources…" else "No sources found"
                Sheet.ONLINE -> if (online == null) "Searching OpenSubtitles…" else onlineError ?: "No subtitles found"
                else -> "Nothing to choose here"
            },
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp).focusRequester(empty).focusable())
        // Keyed by the option (not its place): a list that grows while open (subtitles still loading) keeps the
        // selection on the same line.
        val keys = run { val seen = HashMap<String, Int>(); options.map { o -> val k = o.label + "|" + o.detail; k + "#" + seen.merge(k, 1, Int::plus) } }
        LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 8.dp)) {
            items(options.size, key = { i -> keys[i] }) { i ->
                val o = options[i]
                Row(Modifier.fillMaxWidth().then(if (i == chosen.coerceAtMost(options.size - 1)) Modifier.focusRequester(first) else Modifier)
                    .card(RoundedCornerShape(8.dp), focusScale = 1.02f, onClick = o.action)
                    .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(o.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        o.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (o.selected) Icon(Icons.Filled.Check, null, tint = Palette.accent)
                }
            }
        }
    }
    }
}

/** Settings.playerOrientation → the activity's requested orientation. */
internal fun orientationOf(setting: String) = when (setting) {
    "PORTRAIT" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    "AUTO" -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}

/** In picture in picture: only the video, no controls. */
@Composable
private fun inPip(): Boolean {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.ui.platform.LocalConfiguration.current  // read so this recomposes when the window changes
    return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && ctx.findActivity()?.isInPictureInPictureMode == true
}

/** Live TV's banner: number, channel, what's on and next, time, progress, picture quality. Hides after 5 s. */
@Composable
private fun ChannelBanner(ch: io.github.sherazi5110.cloudstream.LiveTv.Channel, list: List<io.github.sherazi5110.cloudstream.LiveTv.Channel>?, quality: String?, modifier: Modifier) {
    val now = System.currentTimeMillis()
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val (cur, next) = remember(ch) { io.github.sherazi5110.cloudstream.LiveTv.nowNext(ch, now) }
    val number = list?.indexOfFirst { it.key == ch.key }?.takeIf { it >= 0 }?.plus(1)
    Row(modifier.padding(24.dp).widthIn(max = 900.dp).fillMaxWidth().background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(16.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ChannelLogo(ch, 64.dp)
        Column(Modifier.weight(1f)) {
            Text(listOfNotNull(number?.toString(), ch.name).joinToString("   "), style = MaterialTheme.typography.titleSmall, color = Palette.muted, maxLines = 1)
            Text(cur?.title ?: ch.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(quality, cur?.let { "${time.format(Date(it.start))} – ${time.format(Date(it.stop))}" } ?: time.format(Date(now)))
                .joinToString("   ·   "), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            cur?.let { ProgressLine(((now - it.start).toFloat() / (it.stop - it.start).coerceAtLeast(1)).coerceIn(0f, 1f), Modifier.widthIn(max = 300.dp).padding(vertical = 6.dp)) }
            next?.let { Text("Next  ${time.format(Date(it.start))}  ${it.title}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** The see-through channel list over the picture (← while watching): OK switches channel, Back closes it. */
@Composable
private fun LiveChannelList(list: List<io.github.sherazi5110.cloudstream.LiveTv.Channel>, current: io.github.sherazi5110.cloudstream.LiveTv.Channel,
                            modifier: Modifier, onPick: (io.github.sherazi5110.cloudstream.LiveTv.Channel) -> Unit, onClose: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    val start = remember { list.indexOfFirst { it.key == current.key }.coerceAtLeast(0) }
    val state = androidx.compose.foundation.lazy.rememberLazyListState((start - 3).coerceAtLeast(0))
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    FocusOverlay("channel list") { trap ->
        Column(trap.then(modifier).fillMaxHeight().width(420.dp).background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.88f), Color.Black.copy(alpha = 0.55f))))
            .padding(vertical = 16.dp)) {
            Text("Channels", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
            LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 8.dp)) {
                items(list.size, key = { list[it].key }) { i ->
                    val ch = list[i]
                    val cur = remember(ch) { io.github.sherazi5110.cloudstream.LiveTv.nowNext(ch, now).first }
                    Row(Modifier.fillMaxWidth().then(if (i == start) Modifier.focusRequester(first) else Modifier)
                        .card(RoundedCornerShape(10.dp), focusScale = 1.02f) { onPick(ch) }
                        .background(if (ch.key == current.key) Palette.accent.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = Palette.muted, modifier = Modifier.width(34.dp))
                        ChannelLogo(ch, 32.dp)
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            cur?.let {
                                Text(it.title, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                ProgressLine(((now - it.start).toFloat() / (it.stop - it.start).coerceAtLeast(1)).coerceIn(0f, 1f), Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
