package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SmartDisplay
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
    Tab.MUSIC -> Icons.Filled.MusicNote
    Tab.EXTENSIONS -> Icons.Filled.Extension
    Tab.MORE -> Icons.Filled.MoreHoriz
}

@Composable
fun AppRoot() {
    val top = Nav.top
    BackHandler(enabled = Nav.stack.isNotEmpty() || Nav.tab != Tab.HOME) { Nav.back() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.background)) {
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
                    Content(top)
                    if (top == null) MiniPlayer(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).widthIn(max = 720.dp))
                }
            } else if (Palette.isGlass && top == null) {
                // Liquid glass on phones: the page runs under the floating tab bar, which blurs it.
                val haze = dev.chrisbanes.haze.rememberHazeState()
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalBottomInset provides 88.dp) {
                        Box(Modifier.fillMaxSize().hazeSource(haze)) { Content(top) }
                    }
                    CompositionLocalProvider(Glass.LocalHaze provides haze) {
                        Column(Modifier.align(Alignment.BottomCenter)) { MiniPlayer(); BottomBar() }
                    }
                }
            } else Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { Content(top) }
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

@Composable
private fun Content(top: Screen?) {
    // Each tab keeps its scroll position when you come back to it.
    val holder = rememberSaveableStateHolder()
    when (top) {
        null -> holder.SaveableStateProvider(Nav.tab.name) {
            when (Nav.tab) {
                Tab.HOME -> HomeScreen()
                Tab.LIVE -> LiveTvScreen()
                Tab.SEARCH -> SearchScreen()
                Tab.LIBRARY -> LibraryScreen()
                Tab.MUSIC -> MusicScreen()
                Tab.YOUTUBE -> YouTubeScreen()
                Tab.EXTENSIONS -> ExtensionsScreen()
                Tab.MORE -> SettingsScreen()
            }
        }
        is Screen.Details -> holder.SaveableStateProvider("details|${top.apiName}|${top.url}") { DetailsScreen(top) }
        is Screen.Catalog -> CatalogScreen(top)
        is Screen.Repository -> RepositoryScreen(top)
        is Screen.ProviderTests -> ProviderTestsScreen()
        is Screen.AddFromPhone -> AddFromPhoneScreen()
        is Screen.SettingsPage -> SettingsPageScreen(top)
        is Screen.Player, is Screen.WebPlayer -> Unit
    }
}

/**
 * The wide layout's top bar: page controls on the left (Home's extension picker), Search on the right.
 * Drawn over the page, so Home's banner reaches the top edge.
 */
@Composable
fun TopBar(modifier: Modifier = Modifier, start: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 10.dp),
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

/** Space a page leaves at its end for a tab bar floating over it (liquid glass on phones). */
val LocalBottomInset = androidx.compose.runtime.staticCompositionLocalOf { 0.dp }

/** The pill bars' look: a dark rounded capsule with a faint edge, the chosen tab a lighter pill inside it. */
private val pillColor: Color get() = Color(0xFF16161B).copy(alpha = 0.94f)
private val pillEdge: Color get() = Color.White.copy(alpha = 0.10f)
private val chosenColor: Color get() = Color.White.copy(alpha = 0.14f)

/** Portrait: a floating pill: Home · Live TV · Search · My list | Settings. The chosen tab shows its name. */
@Composable
private fun BottomBar() {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
        Row(
            (if (Palette.isGlass) Modifier.glassPanel(RoundedCornerShape(50)) else Modifier.shadow(12.dp, RoundedCornerShape(50))
                .background(pillColor, RoundedCornerShape(50)).border(1.dp, pillEdge, RoundedCornerShape(50))).padding(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            visibleTabs(listOf(Tab.HOME, Tab.LIVE, Tab.SEARCH, Tab.MUSIC, Tab.YOUTUBE, Tab.LIBRARY)).forEach { tab -> PillItem(tab, tab.icon(), vertical = false) }
            Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(22.dp).background(Color.White.copy(alpha = 0.18f)))
            PillItem(Tab.MORE, Icons.Filled.Settings, vertical = false)
        }
    }
}

/** Wide (tablet, TV, landscape): a vertical pill beside the page, the logo above it and the profile below. */
@Composable
private fun Rail() {
    Column(
        Modifier.fillMaxHeight().width(84.dp).systemBarsPadding().padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Logo(42.dp)
        Spacer(Modifier.weight(1f))
        Column(
            (if (Palette.isGlass) Modifier.glassPanel(RoundedCornerShape(50), blur = false) else Modifier.shadow(12.dp, RoundedCornerShape(50))
                .background(pillColor, RoundedCornerShape(50)).border(1.dp, pillEdge, RoundedCornerShape(50))).padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            visibleTabs(listOf(Tab.HOME, Tab.LIVE, Tab.SEARCH, Tab.MUSIC, Tab.YOUTUBE, Tab.LIBRARY, Tab.EXTENSIONS)).forEach { PillItem(it, it.icon(), vertical = true) }
            if (!ProfileUi.current.kids) {
                Box(Modifier.padding(vertical = 2.dp).height(1.dp).width(22.dp).background(Color.White.copy(alpha = 0.18f)))
                PillItem(Tab.MORE, Icons.Filled.Settings, vertical = true)
            }
        }
        Spacer(Modifier.weight(1f))
        ProfileButton(36.dp)
    }
}

/** One tab in a pill bar: chosen = a lighter inner pill (with its name in the bottom bar). */
@Composable
private fun PillItem(tab: Tab, icon: ImageVector, vertical: Boolean) {
    val selected = (Nav.tab == tab || (tab == Tab.MORE && Nav.tab == Tab.EXTENSIONS && !vertical)) && (Nav.stack.isEmpty() || !vertical)
    val shape = RoundedCornerShape(50)
    val tint = if (selected) Color.White else Palette.muted
    Row(
        Modifier.card(shape, focusScale = 1.1f) { Nav.select(tab) }
            .background(if (selected) chosenColor else Color.Transparent, shape)
            .then(if (selected && !vertical) Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), shape) else Modifier)
            .animateContentSize()
            .then(if (vertical) Modifier.size(48.dp) else Modifier.height(44.dp).padding(horizontal = if (selected) 16.dp else 13.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, tab.label, tint = if (selected && vertical) Palette.accent else tint, modifier = Modifier.size(if (vertical) 24.dp else 22.dp))
        if (selected && !vertical) {
            Spacer(Modifier.width(8.dp))
            Text(tab.label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
        }
    }
}

/** Tabs this profile sees: no Live TV for kids unless allowed, no Extensions for kids. */
private fun visibleTabs(tabs: List<Tab>): List<Tab> = tabs.filter { t ->
    when (t) {
        Tab.LIVE -> io.github.sherazi5110.cloudstream.LiveTv.availableToProfile
        Tab.EXTENSIONS, Tab.YOUTUBE -> !ProfileUi.current.kids
        else -> true
    }
}
