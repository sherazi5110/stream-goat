package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.SportsSoccer
import dev.chrisbanes.haze.hazeSource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Wide layout (the Linux app's: icon rail on the left, top bar) on Android TV and whenever the screen is
 * wider than tall (tablets and phones in landscape); the phone layout (bottom bar) in portrait.
 */
val LocalWide = compositionLocalOf { false }

/** Also callable outside composition-aware code paths that already read it once. */
val isWide: Boolean @Composable get() = LocalWide.current

private fun Tab.icon(): ImageVector = when (this) {
    Tab.HOME -> Icons.Filled.Home
    Tab.LIVE -> Icons.Filled.LiveTv
    Tab.SEARCH -> Icons.Filled.Search
    Tab.LIBRARY -> Icons.Filled.VideoLibrary
    Tab.YOUTUBE -> Icons.Filled.SmartDisplay
    Tab.SPORTS -> Icons.Filled.SportsSoccer
    Tab.MUSIC -> Icons.Filled.MusicNote
    Tab.EXTENSIONS -> Icons.Filled.Extension
    Tab.MORE -> Icons.Filled.MoreHoriz
}

/**
 * TVs give apps a small canvas (960 units wide), so everything was drawn ~40% bigger than on a tablet: two episodes
 * per row, cut-off posters and rows. On a TV the app is laid out as on a 1280-unit-wide tablet instead (the same
 * layout, scaled to the screen), which shows as much as the tablet does.
 */
@Composable
fun TvScale(content: @Composable () -> Unit) {
    if (!isTv) return content()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val widthPx = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.takeIf { it > 0 }
        ?: androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics.widthPixels
    val target = 1280f
    val scaled = (widthPx / target).takeIf { widthPx / density.density < target } ?: return content()
    CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(scaled, density.fontScale)) {
        content()
    }
}

@Composable
fun AppRoot() {
    val top = Nav.top
    // What each screen remembers (the Library tab you were on, the Settings section, scroll positions): kept here,
    // above the player, so playing a video and coming back doesn't reset it (it used to: Downloads → My list).
    val saved = rememberSaveableStateHolder()
    BackHandler(enabled = Nav.stack.isNotEmpty() || Nav.tab != Tab.HOME) { Nav.back() }
    // Leaving the Music section stops the music (it keeps playing with the screen off or the app in the background).
    androidx.compose.runtime.LaunchedEffect(Nav.tab) {
        if (Nav.tab != Tab.MUSIC && io.github.sherazi5110.cloudstream.Music.current != null) io.github.sherazi5110.cloudstream.Music.stop()
    }
    FocusKeeperRoot { keeper ->
    BoxWithConstraints(keeper.fillMaxSize().background(Palette.background)) {
        if (ThemeState.mode == ThemeMode.GLASS) GlassBackdrop(Modifier.fillMaxSize())
        val wide = isTv || maxWidth > maxHeight
        CompositionLocalProvider(LocalWide provides wide) {
            if (top is Screen.Player) {
                PlayerScreen(top)
                return@CompositionLocalProvider
            }
            if (top is Screen.WebPlayer) {
                WebPlayerScreen(top)
                return@CompositionLocalProvider
            }
            if (wide) Row(Modifier.fillMaxSize()) {
                Rail()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Content(top, saved)
                    if (top == null) MiniPlayer(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).widthIn(max = 720.dp))
                }
            } else if (Palette.isGlass && top == null) {
                // Liquid glass on phones: the page runs under the floating tab bar, which blurs it.
                val haze = dev.chrisbanes.haze.rememberHazeState()
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalBottomInset provides 88.dp) {
                        Box(Modifier.fillMaxSize().hazeSource(haze)) { Content(top, saved) }
                    }
                    CompositionLocalProvider(Glass.LocalHaze provides haze) {
                        Column(Modifier.align(Alignment.BottomCenter)) { MiniPlayer(); BottomBar() }
                    }
                }
            } else Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { Content(top, saved) }
                if (top == null) { MiniPlayer(Modifier.padding(top = 6.dp)); BottomBar() }
            }
            ProfileOverlays()
            UpdateDialog()
            DownloadChooserDialog()
            CrashDialog()
            CloudDialogs()
        }
    }
    }
}

@Composable
private fun Content(top: Screen?, holder: androidx.compose.runtime.saveable.SaveableStateHolder) {
    // Each tab and screen keeps its state (scroll position, chosen tab or section) when you come back to it.
    when (top) {
        null -> holder.SaveableStateProvider(Nav.tab.name) {
            when (Nav.tab) {
                Tab.HOME -> HomeScreen()
                Tab.LIVE -> LiveTvScreen()
                Tab.SEARCH -> SearchScreen()
                Tab.LIBRARY -> LibraryScreen()
                Tab.MUSIC -> MusicScreen()
                Tab.YOUTUBE -> YouTubeScreen()
                Tab.SPORTS -> SportsScreen()
                Tab.EXTENSIONS -> ExtensionsScreen()
                Tab.MORE -> SettingsScreen()
            }
        }
        is Screen.Details -> holder.SaveableStateProvider("details|${top.apiName}|${top.url}") { DetailsScreen(top) }
        is Screen.Catalog -> holder.SaveableStateProvider("catalog|${top.apiName}|${top.sectionName}") { CatalogScreen(top) }
        is Screen.Repository -> holder.SaveableStateProvider("repo|${top.url}") { RepositoryScreen(top) }
        is Screen.ProviderTests -> ProviderTestsScreen()
        is Screen.AddFromPhone -> AddFromPhoneScreen()
        is Screen.SettingsPage -> holder.SaveableStateProvider("settings|${top.section}") { SettingsPageScreen(top) }
        is Screen.Player, is Screen.WebPlayer -> Unit
    }
}

/**
 * The wide layout's top bar: page controls on the left (Home's extension picker), Search on the right.
 * Drawn over the page, so Home's banner reaches the top edge.
 */
@Composable
fun TopBar(modifier: Modifier = Modifier, start: @Composable () -> Unit = {}) {
    // Reached with ↑ (or from the side bar), not with → at the end of a row (it used to jump to Search or the profile).
    NavArea("top bar", listOf(androidx.compose.ui.focus.FocusDirection.Up)) { nav ->
    Row(
        nav.then(modifier).fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        start()
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.card(RoundedCornerShape(50)) { Nav.searchHome() }
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text("Search", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.width(10.dp))
        ProfileButton(30.dp, showName = true)
    }
    }
}

/** Space a page leaves at its end for a tab bar floating over it (liquid glass on phones). */
val LocalBottomInset = androidx.compose.runtime.staticCompositionLocalOf { 0.dp }

/** The pill bars' look: a dark rounded capsule with a faint edge, the chosen tab a lighter pill inside it. */
private val pillColor: Color get() = Color(0xFF16161B).copy(alpha = 0.94f)
private val pillEdge: Color get() = Color.White.copy(alpha = 0.10f)
private val chosenColor: Color get() = Color.White.copy(alpha = 0.14f)

/**
 * Portrait: a floating pill: Home · Live TV · Sports · Search · Music · YouTube · My list | Settings. The chosen tab
 * shows its name where there's room. Settings always stays in view: with Sports, Music and YouTube added, the row was
 * wider than a phone and Settings went off the right edge (issue #74); now narrow phones get compact tabs that scroll.
 */
@Composable
private fun BottomBar() {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
        val tabs = visibleTabs(listOf(Tab.HOME, Tab.LIVE, Tab.SPORTS, Tab.SEARCH, Tab.MUSIC, Tab.YOUTUBE, Tab.LIBRARY))
        // Room for every tab at full size with the chosen one's name (about 48 units each, plus ~75 for the name)?
        val compact = maxWidth < (48 * (tabs.size + 1) + 75 + 40).dp
        NavArea("bottom bar", listOf(androidx.compose.ui.focus.FocusDirection.Down)) { nav ->
        Row(
            nav.then(if (Palette.isGlass) Modifier.glassPanel(RoundedCornerShape(50)) else Modifier.shadow(12.dp, RoundedCornerShape(50))
                .background(pillColor, RoundedCornerShape(50)).border(1.dp, pillEdge, RoundedCornerShape(50))).padding(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                tabs.forEach { tab -> PillItem(tab, tab.icon(), vertical = false, compact = compact) }
            }
            Box(Modifier.padding(horizontal = if (compact) 3.dp else 6.dp).width(1.dp).height(22.dp).background(Color.White.copy(alpha = 0.18f)))
            PillItem(Tab.MORE, Icons.Filled.Settings, vertical = false, compact = compact)
        }
        }
    }
}

/** Wide (tablet, TV, landscape): a vertical pill beside the page, the logo above it and the profile below. */
@Composable
private fun Rail() {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxHeight().width(84.dp).systemBarsPadding().padding(vertical = 14.dp)) {
        val tabs = visibleTabs(listOf(Tab.HOME, Tab.LIVE, Tab.SPORTS, Tab.SEARCH, Tab.MUSIC, Tab.YOUTUBE, Tab.LIBRARY, Tab.EXTENSIONS))
        val settings = !ProfileUi.current.kids
        val count = tabs.size + if (settings) 1 else 0
        // Everything has to fit the screen's height: a TV is only 540 units tall to the app, where 48-unit buttons
        // pushed Settings and the profile off the bottom. The buttons (and the gaps) shrink to fit, down to 34.
        val logo = if (maxHeight < 600.dp) 34.dp else 42.dp
        val avatar = if (maxHeight < 600.dp) 30.dp else 36.dp
        val free = maxHeight - logo - avatar - 16.dp /* breathing room */ - 12.dp /* pill padding */ - (if (settings) 7.dp else 0.dp)
        val gap = if (free / count >= 54.dp) 6.dp else 3.dp
        val item = ((free - gap * (count - 1)) / count).coerceIn(34.dp, 48.dp)
        // Reached with ← from the page; a lost selection never lands here by itself (it used to: the Home icon).
        NavArea("side bar", listOf(androidx.compose.ui.focus.FocusDirection.Left)) { nav ->
        Column(nav.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(logo)
            Spacer(Modifier.weight(1f))
            Column(
                (if (Palette.isGlass) Modifier.glassPanel(RoundedCornerShape(50), blur = false) else Modifier.shadow(12.dp, RoundedCornerShape(50))
                    .background(pillColor, RoundedCornerShape(50)).border(1.dp, pillEdge, RoundedCornerShape(50))).padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                tabs.forEach { PillItem(it, it.icon(), vertical = true, size = item) }
                if (settings) {
                    Box(Modifier.padding(vertical = 2.dp).height(1.dp).width(22.dp).background(Color.White.copy(alpha = 0.18f)))
                    PillItem(Tab.MORE, Icons.Filled.Settings, vertical = true, size = item)
                }
            }
            Spacer(Modifier.weight(1f))
            ProfileButton(avatar)
        }
        }
    }
}

/** One tab in a pill bar: chosen = a lighter inner pill (with its name in the bottom bar). */
@Composable
private fun PillItem(tab: Tab, icon: ImageVector, vertical: Boolean, size: androidx.compose.ui.unit.Dp = 48.dp, compact: Boolean = false) {
    val selected = (Nav.tab == tab || (tab == Tab.MORE && Nav.tab == Tab.EXTENSIONS && !vertical)) && (Nav.stack.isEmpty() || !vertical)
    val shape = RoundedCornerShape(50)
    val tint = if (selected) Color.White else Palette.muted
    Row(
        Modifier.card(shape, focusScale = 1.1f) { Nav.select(tab) }
            .background(if (selected) chosenColor else Color.Transparent, shape)
            .then(if (selected && !vertical) Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), shape) else Modifier)
            .animateContentSize()
            .then(if (vertical) Modifier.size(size) else Modifier.height(44.dp).padding(horizontal = if (compact) 10.dp else if (selected) 16.dp else 13.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, tab.label, tint = if (selected && vertical) Palette.accent else tint, modifier = Modifier.size(if (vertical) (size * 0.5f).coerceIn(20.dp, 24.dp) else 22.dp))
        if (selected && !vertical && !compact) {
            Spacer(Modifier.width(8.dp))
            Text(tab.label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
        }
    }
}

/** Tabs this profile sees: no Live TV for kids unless allowed, no Extensions for kids. */
private fun visibleTabs(tabs: List<Tab>): List<Tab> = tabs.filter { t ->
    when (t) {
        Tab.LIVE -> io.github.sherazi5110.cloudstream.LiveTv.availableToProfile && io.github.sherazi5110.cloudstream.Settings.showLiveTv
        Tab.MUSIC -> io.github.sherazi5110.cloudstream.Settings.showMusic
        Tab.EXTENSIONS -> !ProfileUi.current.kids
        Tab.YOUTUBE -> !ProfileUi.current.kids && io.github.sherazi5110.cloudstream.Settings.showYouTube
        else -> true
    }
}
