package io.github.sherazi5110.cloudstream.ui

import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import io.github.sherazi5110.cloudstream.Library
import io.github.sherazi5110.cloudstream.WatchEntry

@Composable
fun HomeScreen() {
    val state = rememberHomeState()
    if (state.providers.isEmpty()) {
        NoExtensions()
        return
    }
    if (isWide) WideHome(state) else {
        PhoneHome(state)
        if (state.editing) HomeEditor(state) { state.editing = false }
    }
}

@Composable
private fun NoExtensions() {
    val progress by io.github.sherazi5110.cloudstream.PluginManager.progress.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (progress.running) Spinner()
        else androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(
                "Welcome to Stream Goat",
                "Add a repository and install an extension to fill Home. Extensions are the same ones the official app uses.",
                button = "Add extensions",
            ) { Nav.select(Tab.EXTENSIONS) }
            // After reinstalling (or on a new device): everything back from the Google Drive backup.
            if (io.github.sherazi5110.cloudstream.CloudBackup.configured) {
                PillButton("Restore from Google Drive", Icons.Filled.CloudDownload, primary = false) { CloudUi.restoreFromDrive() }
            }
        }
    }
}

// ---------------------------------------------------------------- phone

@Composable
private fun PhoneHome(state: HomeState) {
    LazyColumn(Modifier.fillMaxSize(), state = state.phoneList, contentPadding = PaddingValues(bottom = 24.dp + LocalBottomInset.current)) {
        item(key = "hero") { PhoneHero(state) }
        if (state.continueWatching.isNotEmpty()) item(key = "continue") {
            ContinueRow(state.continueWatching, cardWidth = 200.dp)
        }
        uniqueItems(state.sections, key = { "${it.api.name}|${it.order}" }) { section ->
            CatalogRow(section, posterWidth = 112.dp)
        }
        if (state.loading && state.sections.isEmpty()) item { Spinner(Modifier.fillMaxWidth().height(200.dp)) }
        if (state.offline && state.sections.isEmpty()) item { OfflineState(Modifier.fillMaxWidth()) { state.catalogVersion++ } }
        if (nothingCame(state)) item { NothingCame(state) }
    }
}

@Composable
private fun PhoneHero(state: HomeState) {
    val featured = state.featured
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { featured.size.coerceAtLeast(1) }
    Box(Modifier.fillMaxWidth().height(560.dp)) {
        if (featured.isEmpty()) Box(Modifier.fillMaxSize().background(Palette.surface))
        else HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val item = featured[page]
            val details = rememberDetails(item.apiName, item.url)
            Box(Modifier.fillMaxSize()) {
                NetImage(details?.backgroundPosterUrl ?: details?.posterUrl ?: item.posterUrl, Modifier.fillMaxSize(),
                    details?.posterHeaders ?: item.posterHeaders)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.55f), 0.25f to Color.Transparent, 0.55f to Color.Transparent, 1f to Palette.background)))
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
                    state.sections.firstOrNull()?.let {
                        Text("#${page + 1} in ${it.list.name}", style = MaterialTheme.typography.labelMedium)
                    }
                    Text(item.name, style = MaterialTheme.typography.displayLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 6.dp))
                    MetaLine(item, details)
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Play", Icons.Filled.PlayArrow, Modifier.weight(1f)) { Playback.playFrom(scope, item) }
                        PillButton("More Info", Icons.Filled.Info, Modifier.weight(1f), primary = false) { openTitle(item) }
                    }
                }
            }
        }
        // Top bar over the banner: logo, extension picker, search.
        Row(
            Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Logo(34.dp)
            SourcePicker(state)
            EditCatalogsButton(state)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.Search, "Search", tint = Color.White,
                modifier = Modifier.size(40.dp).card(CircleShape) { Nav.searchHome() }.padding(8.dp))
        }
        if (featured.size > 1) Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(featured.size) { i ->
                val selected = pager.currentPage == i
                Box(Modifier.size(width = if (selected) 18.dp else 6.dp, height = 6.dp)
                    .background(if (selected) Palette.accent else Color.White.copy(alpha = 0.4f), CircleShape))
            }
        }
    }
}

/** "2026 · 2h 4m · PG-13 · ★ 7.6" */
@Composable
fun MetaLine(item: SearchResponse?, details: LoadResponse?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        details?.year?.let { Text("$it", style = MaterialTheme.typography.titleSmall) }
        details?.duration?.takeIf { it > 0 }?.let { Text(formatMinutes(it), style = MaterialTheme.typography.titleSmall) }
        details?.contentRating?.takeIf { it.isNotBlank() }?.let { Pill(it, outlined = true) }
        (details?.score?.toString(10) ?: item?.scoreText())?.takeIf { it != "0" }?.let {
            Text("★ $it", style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** The extension Home shows, as a dropdown chip. */
@Composable
fun SourcePicker(state: HomeState, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.card(RoundedCornerShape(50)) { open = true }
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                .padding(start = 14.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(state.api?.name ?: "Source", style = MaterialTheme.typography.labelLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            Icon(Icons.Filled.ArrowDropDown, null, tint = Color.White)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.freshOkOnly()) {
            state.providers.forEach { api ->
                DropdownMenuItem(
                    text = { Text(api.name) },
                    leadingIcon = { if (api == state.api) Icon(Icons.Filled.Check, null, tint = Palette.accent) },
                    onClick = { open = false; state.choose(api) },
                )
            }
        }
    }
}

@Composable
fun ContinueRow(entries: List<WatchEntry>, cardWidth: androidx.compose.ui.unit.Dp, onFocus: ((WatchEntry) -> Unit)? = null) {
    val edge = if (isWide) 40.dp else 16.dp
    Column(Modifier.padding(top = 12.dp)) {
        SectionTitle("Continue Watching", Modifier.padding(horizontal = edge))
        val first = remember { androidx.compose.ui.focus.FocusRequester() }
        val firstKey = entries.firstOrNull()?.let { "${it.apiName}|${it.url}" }
        TvRowScroll(first) { rowState -> LazyRow(state = rowState,
            contentPadding = PaddingValues(horizontal = edge, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            uniqueItems(entries, key = { "${it.apiName}|${it.url}" }) { e ->
                var menu by remember { mutableStateOf(false) }
                // The remote's Menu key opens the same menu as a long press.
                Box(Modifier.onPreviewKeyEvent {
                    if (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU && it.type == KeyEventType.KeyUp) { menu = true; true } else false
                }) {
                    WideCard(
                        e.name, e.leftText(), e.posterUrl, e.progress.toFloat(), cardWidth,
                        modifier = if ("${e.apiName}|${e.url}" == firstKey) Modifier.focusRequester(first) else Modifier,
                        onFocus = onFocus?.let { { it(e) } },
                        onLongClick = { menu = true },
                        onClick = { openTitle(e) },
                    )
                    WatchedMenu(menu, e) { menu = false }
                }
            }
        } }
    }
}

/** Long-press / Menu key on a Continue Watching card: ✓ watched or ✕ remove. */
@Composable
fun WatchedMenu(open: Boolean, e: WatchEntry, onDismiss: () -> Unit) {
    DropdownMenu(open, onDismissRequest = onDismiss, modifier = Modifier.freshOkOnly()) {
        DropdownMenuItem(text = { Text("Mark as watched") }, leadingIcon = { Icon(Icons.Filled.Check, null) },
            onClick = { onDismiss(); Library.markWatched(e) })
        DropdownMenuItem(text = { Text("Remove from Continue Watching") }, leadingIcon = { Icon(Icons.Filled.Close, null) },
            onClick = { onDismiss(); Library.removeHistory(e.apiName, e.url) })
    }
}

@Composable
fun CatalogRow(
    section: io.github.sherazi5110.cloudstream.Engine.HomeSection,
    posterWidth: androidx.compose.ui.unit.Dp,
    onFocus: ((SearchResponse) -> Unit)? = null,
) {
    val edge = if (isWide) 40.dp else 16.dp
    Column(Modifier.padding(top = 12.dp)) {
        val viewAll = { Nav.open(Screen.Catalog(section.api.name, section.section.name, section.list.name)) }
        // On TV the poster-sized tile at the end of the row is the way in; touch also gets the link.
        SectionTitle(
            section.list.name, Modifier.padding(horizontal = edge),
            action = if (isTv) null else "View all",
        ) { viewAll() }
        val first = remember { androidx.compose.ui.focus.FocusRequester() }
        val firstUrl = section.list.list.firstOrNull()?.url
        TvRowScroll(if (firstUrl != null) first else null) { rowState -> LazyRow(state = rowState,
            contentPadding = PaddingValues(horizontal = edge, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isWide) 14.dp else 10.dp),
        ) {
            uniqueItems(section.list.list, key = { it.url }) { item ->
                PosterCard(item, posterWidth, modifier = if (item.url == firstUrl) Modifier.focusRequester(first) else Modifier,
                    onFocus = onFocus?.let { { it(item) } }) { openTitle(item) }
            }
            item(key = "view-all") { ViewAllTile(section.list.name, posterWidth) { viewAll() } }
        } }
    }
}

// ---------------------------------------------------------------- wide (landscape, TV)

/**
 * The Linux app's Home: a full-width banner with the title on the left (extension, logo or name, facts,
 * "#1 in …", plot, Play / More Info) and dots on the right, then Continue Watching and the catalogs.
 * The banner slides by itself and can be swiped; on TV it follows the focused card.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun WideHome(state: HomeState) {
    var focused by remember { mutableStateOf<SearchResponse?>(null) }
    val list = state.wideList
    // Liquid glass: what scrolls under the top bar is blurred and fades out, with no hard edge (Apple's scroll edge).
    val topHaze = dev.chrisbanes.haze.rememberHazeState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The banner is a rounded card below the top bar (nothing drawn over it), so a little shorter than full-bleed.
        val bannerHeight = (maxHeight * 0.62f).coerceIn(300.dp, 560.dp)
        val scope = rememberCoroutineScope()
        // On a TV, Compose scrolls a list so the focused item sits a third of the way down (the TV "pivot"). With the
        // remote on the banner's Play button that pushed the banner's top (logo, title) up under the top bar. Here
        // the list only scrolls as far as needed, and the banner always shows whole when it has the focus.
        // Moving down from the banner with the remote: the list used to scroll only as far as the row needed, leaving
        // the banner's bottom strip (Play, More Info) under the see-through top bar. Now the banner goes all the way
        // under the bar, and the row sits just below it.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val barPx = with(density) { (androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 66.dp).toPx() }
        fun hideBannerUnderBar() {
            scope.launch {
                kotlinx.coroutines.delay(60)   // after the focus scroll
                val banner = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "banner" } ?: return@launch
                val bottom = banner.offset + banner.size
                if (bottom > barPx) list.animateScrollBy(bottom - barPx)
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides if (isTv) CenterScroll else MinimalScroll) {
        LazyColumn(Modifier.fillMaxSize().then(if (Palette.isGlass) Modifier.hazeSource(topHaze) else Modifier), state = list, contentPadding = PaddingValues(bottom = 32.dp + LocalBottomInset.current)) {
            item(key = "banner") {
                Box(Modifier.onFocusChanged { f ->
                    if (f.hasFocus && (list.firstVisibleItemIndex != 0 || list.firstVisibleItemScrollOffset != 0)) scope.launch { list.animateScrollToItem(0) }
                }) { WideBanner(state, focused, bannerHeight) }
            }
            if (state.continueWatching.isNotEmpty()) item(key = "continue") {
                Box(Modifier.onFocusChanged { if (it.hasFocus) hideBannerUnderBar() }) { ContinueRow(state.continueWatching, 280.dp) }
            }
            uniqueItems(state.sections, key = { "${it.api.name}|${it.order}" }) { section ->
                Box(Modifier.onFocusChanged { if (it.hasFocus) hideBannerUnderBar() }) {
                    CatalogRow(section, 140.dp)
                }
            }
            if (state.loading && state.sections.isEmpty()) item { Spinner(Modifier.fillMaxWidth().height(200.dp)) }
            if (state.offline && state.sections.isEmpty()) item { OfflineState(Modifier.fillMaxWidth()) { state.catalogVersion++ } }
            if (nothingCame(state)) item { NothingCame(state) }
        }
        }
        // Fixed top bar; it gets a backdrop as soon as the page scrolls, so nothing shows through it.
        val scrolled = list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0
        TopBar(
            when {
                !scrolled -> Modifier
                Palette.isGlass && Glass.canBlur -> Modifier.hazeEffect(topHaze, HazeStyle(Color.Transparent, HazeTint(Palette.background.copy(alpha = 0.55f)), 24.dp, 0f)) {
                    progressive = dev.chrisbanes.haze.HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)
                }
                Palette.isGlass -> Modifier.background(Brush.verticalGradient(listOf(Palette.background.copy(alpha = 0.92f), Palette.background.copy(alpha = 0.7f), Color.Transparent)))
                else -> Modifier.background(Palette.background.copy(alpha = 0.94f))
            },
        ) {
            SourcePicker(state)
            Spacer(Modifier.width(10.dp))
            EditCatalogsButton(state)
        }
    }
    if (state.editing) HomeEditor(state) { state.editing = false }
}

@Composable
private fun WideBanner(state: HomeState, focused: SearchResponse?, height: androidx.compose.ui.unit.Dp) {
    val featured = if (io.github.sherazi5110.cloudstream.Settings.heroSlider) state.featured else state.featured.take(1)
    val pager = rememberPagerState { featured.size.coerceAtLeast(1) }
    // Slides every 9 seconds (unless a card on TV holds the banner).
    LaunchedEffect(featured.size, focused) {
        while (featured.size > 1 && focused == null) {
            delay(9_000)
            pager.animateScrollToPage((pager.currentPage + 1) % featured.size)
        }
    }
    val rowName = state.sections.firstOrNull()?.list?.name
    // At least [height] tall, taller when the title, plot and buttons need it: on a short screen (a 1080p TV is
    // only 540 units tall to the app) the text used to run off the top of the banner.
    // A card with rounded corners that starts below the top bar (extension picker, Catalogs, Search, profile), lined
    // up with it, so the bar never covers the picture and every corner looks the same.
    val topBarSpace = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 66.dp
    val cardShape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = topBarSpace)
        .shadow(18.dp, cardShape, clip = false).clip(cardShape).heightIn(min = height)) {
        if (featured.isEmpty()) Box(Modifier.fillMaxWidth().height(height).background(Palette.surface))
        else if (focused != null) BannerPage(focused, featured.indexOfFirst { it.url == focused.url }.takeIf { it >= 0 }, rowName, height)
        else HorizontalPager(pager, Modifier.fillMaxWidth()) { page -> BannerPage(featured[page], page, rowName, height) }
        if (featured.size > 1 && focused == null) Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(featured.size) { i ->
                val selected = pager.currentPage == i
                Box(Modifier.size(width = if (selected) 22.dp else 7.dp, height = 7.dp)
                    .background(if (selected) Palette.accent else Color.White.copy(alpha = 0.4f), CircleShape))
            }
        }
    }
}

@Composable
private fun BannerPage(item: SearchResponse, rank: Int?, rowName: String?, height: androidx.compose.ui.unit.Dp) {
    val compact = height < 460.dp
    val details = rememberDetails(item.apiName, item.url)
    val info = rememberMetadata(details)
    val art = io.github.sherazi5110.cloudstream.Metadata.artwork
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isTv && rank == 0 && !FocusKeeper.returning) runCatching { playFocus.requestFocus() } }
    Box(Modifier.fillMaxWidth().heightIn(min = height)) {
        NetImage(info?.backdrop?.takeIf { art } ?: details?.backgroundPosterUrl ?: details?.posterUrl ?: item.posterUrl, Modifier.matchParentSize(),
            details?.posterHeaders ?: item.posterHeaders)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to Palette.background.copy(alpha = 0.95f), 0.45f to Palette.background.copy(alpha = 0.55f), 0.75f to Color.Transparent)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 36.dp, top = 24.dp, bottom = if (compact) 20.dp else 28.dp).widthIn(max = 560.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(18.dp)
                Text(item.apiName.uppercase(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
            }
            val logo = details?.logoUrl ?: info?.logo?.takeIf { art }
            if (logo != null) NetImage(logo, Modifier.padding(vertical = if (compact) 8.dp else 12.dp).height(if (compact) 64.dp else 96.dp).widthIn(max = 460.dp).fillMaxWidth(),
                contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, placeholder = false)
            else Text(item.name, style = MaterialTheme.typography.displayLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 10.dp))
            MetaLine(item, details)
            if (rank != null && rowName != null) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Tv, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text("#${rank + 1} in $rowName", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
            }
            (details?.plot?.takeIf { it.isNotBlank() } ?: info?.overview)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            }
            Row(Modifier.padding(top = if (compact) 12.dp else 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Play", Icons.Filled.PlayArrow, Modifier.focusRequester(playFocus), compact = true) { Playback.playFrom(scope, item) }
                PillButton("More Info", Icons.Filled.Info, primary = false, compact = true) { openTitle(item) }
            }
        }
    }
}

/** The last "poster" of every row: opens the whole catalog (easy to reach with a remote). */
@Composable
private fun ViewAllTile(title: String, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Column(Modifier.width(width)) {
        Column(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).card(selectFirst = true, onClick = onClick)
                .background(Palette.surfaceHigh, RoundedCornerShape(10.dp))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(48.dp).background(Palette.accent.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Palette.accent)
            }
            Text("View all", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        }
        Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp))
    }
}

/** Next to the extension picker: opens the Home screen editor. */
@Composable
private fun EditCatalogsButton(state: HomeState) {
    Row(
        Modifier.card(RoundedCornerShape(50)) { state.editing = true }
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Tune, "Edit catalogs", tint = Color.White, modifier = Modifier.size(18.dp))
        if (isWide) Text("Catalogs", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * Home screen editor (like the Linux app's): pick an extension, then turn its catalogs on or off and
 * reorder them. "Show on Home" also switches Home to that extension.
 */
@Composable
private fun HomeEditor(state: HomeState, onClose: () -> Unit) {
    var api by remember { mutableStateOf(state.api ?: state.providers.firstOrNull()) }
    val entries = remember(api) { androidx.compose.runtime.mutableStateListOf<io.github.sherazi5110.cloudstream.Catalogs.Entry>().apply {
        api?.let { addAll(io.github.sherazi5110.cloudstream.Catalogs.all(it)) }
    } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        DialogGlass()
        Column(
            Modifier.padding(24.dp).widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(0.9f)
                .glassPanel(RoundedCornerShape(22.dp), blur = false).padding(20.dp),
        ) {
            Text("Home screen", style = MaterialTheme.typography.headlineSmall)
            Text("Pick an extension, then choose and order its catalogs.", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                uniqueItems(state.providers, key = { it.name }) { p ->
                    val selected = p == api
                    Text(p.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier
                        .card(RoundedCornerShape(50)) { api = p }
                        .background(if (selected) Palette.accent else Palette.surfaceHigh, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            LazyColumn(Modifier.weight(1f).padding(top = 10.dp)) {
                val entryKeys = uniqueKeys(entries) { it.section.name }
                items(entries.size, key = { entryKeys[it] }) { i ->
                    val e = entries[i]
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).card(RoundedCornerShape(8.dp), focusScale = 1.02f) { entries[i] = e.copy(enabled = !e.enabled) }
                            .padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (e.enabled) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, null,
                                tint = if (e.enabled) Palette.accent else Palette.muted)
                            Text(e.section.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 12.dp), color = if (e.enabled) Color.White else Palette.muted)
                        }
                        IconAction(Icons.Filled.KeyboardArrowUp, "Move up") { if (i > 0) { entries.removeAt(i); entries.add(i - 1, e) } }
                        IconAction(Icons.Filled.KeyboardArrowDown, "Move down") { if (i < entries.lastIndex) { entries.removeAt(i); entries.add(i + 1, e) } }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Reset", null, primary = false, compact = true) {
                    api?.let { a -> io.github.sherazi5110.cloudstream.Catalogs.reset(a); entries.clear(); entries.addAll(io.github.sherazi5110.cloudstream.Catalogs.all(a)) }
                }
                PillButton("Cancel", null, primary = false, compact = true) { onClose() }
                PillButton("Save & show", Icons.Filled.Check, compact = true) {
                    api?.let { a ->
                        io.github.sherazi5110.cloudstream.Catalogs.save(a, entries.toList())
                        if (a != state.api) state.choose(a)
                    }
                    onClose()
                }
            }
        }
    }
}

/** Online, finished loading, and the extension gave Home nothing (its site down or blocked, or every catalog off). */
private fun nothingCame(state: HomeState) = !state.loading && !state.offline && state.sections.isEmpty() && state.api != null

/** Instead of an empty, black Home: what happened and what to do. */
@Composable
private fun NothingCame(state: HomeState) {
    val api = state.api ?: return
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Nothing came from ${api.name}", style = MaterialTheme.typography.titleLarge)
        Text("Its website may be down or blocked on this network, or all its catalogs are switched off (Catalogs, top left). " +
            "Try again, or pick another extension at the top left.",
            style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Palette.muted)
        io.github.sherazi5110.cloudstream.Engine.homeErrors[api.name]?.let {
            Text("Last problem: $it", style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Palette.muted)
        }
        PillButton("Try again", Icons.Filled.Refresh, compact = true, modifier = Modifier.padding(top = 6.dp)) {
            io.github.sherazi5110.cloudstream.Engine.homeErrors.remove(api.name)
            state.catalogVersion++
        }
    }
}
