package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.Catalogs
import io.github.sherazi5110.cloudstream.ContentFilter
import io.github.sherazi5110.cloudstream.Engine
import io.github.sherazi5110.cloudstream.Library
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow

/** A title bar with a back arrow, for pushed screens. */
@Composable
fun BackBar(title: String, modifier: Modifier = Modifier) {
    Row(modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White,
            modifier = Modifier.size(44.dp).card(CircleShape) { Nav.back() }.padding(10.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

private val gridCells @Composable get() = posterCells(if (isWide) 140.dp else 108.dp)

/** Grid columns for posters [width] wide (wider ones for wide posters, Settings → Appearance → Posters). */
internal fun posterCells(width: androidx.compose.ui.unit.Dp) =
    GridCells.Adaptive(if (io.github.sherazi5110.cloudstream.Settings.widePosters) width * 1.6f else width)

/** "See all": a catalog's pages, loading the next one near the bottom until the extension says there are no more. */
@Composable
fun CatalogScreen(screen: Screen.Catalog) {
    val api = remember(screen) { Engine.provider(screen.apiName) }
    val section = remember(api) { api?.let { Catalogs.all(it).firstOrNull { e -> e.section.name == screen.sectionName }?.section } }
    val items = remember(screen) { mutableStateListOf<SearchResponse>() }
    var page by remember(screen) { mutableStateOf(0) }
    var hasNext by remember(screen) { mutableStateOf(true) }
    var loading by remember(screen) { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 >= items.size - 12 } }

    // One loop per screen: wait until the user nears the end, load the next page, repeat until the
    // extension says there are no more. (Keying an effect on `loading` would cancel its own request.)
    LaunchedEffect(screen) {
        if (api == null || section == null) { hasNext = false; return@LaunchedEffect }
        while (hasNext) {
            snapshotFlow { nearEnd }.first { it }
            loading = true
            val response = Engine.homePage(api, section, page + 1)
            val list = response?.items?.firstOrNull { it.name == screen.title } ?: response?.items?.firstOrNull()
            val fresh = ContentFilter.filter(list?.list.orEmpty()).filter { n -> items.none { it.url == n.url } }
            items.addAll(fresh)
            hasNext = response?.hasNext == true && fresh.isNotEmpty()
            page++
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(screen.title)
        LazyVerticalGrid(gridCells, state = grid, contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + LocalBottomInset.current),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            uniqueItems(items, key = { it.url }) { item -> PosterCard(item, 200.dp, Modifier.fillMaxWidth()) { openTitle(item) } }
            if (loading) item(span = { GridItemSpan(maxLineSpan) }) { Spinner(Modifier.fillMaxWidth().height(80.dp)) }
            if (!loading && !hasNext && items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState("Nothing here", "The extension returned no titles for this catalog.", Modifier.fillMaxWidth())
            }
        }
    }
}

/** Search across every extension; each extension's results appear as soon as it answers. */
@Composable
fun SearchScreen() {
    val results = remember { mutableStateListOf<Pair<MainAPI, List<SearchResponse>>>() }
    var searching by remember { mutableStateOf(0) }
    val typed = Nav.searchQuery
    val query = Nav.searchSubmitted
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Suggestions while typing, like the official app (TMDB with the user's key, else Cinemeta).
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(typed) {
        suggestions = emptyList()
        if (typed.trim().length < 2 || typed.trim() == query.trim()) return@LaunchedEffect
        delay(220)
        suggestions = io.github.sherazi5110.cloudstream.Metadata.suggestions(typed)
    }
    val submit: (String) -> Unit = { q -> Nav.searchQuery = q; Nav.searchSubmitted = q.trim(); suggestions = emptyList(); focusManager.clearFocus() }

    // Home's Search button searches only the extension Home shows; the Search tab searches all of them.
    val scope = Nav.searchScope
    val scopedApi = remember(scope) { scope?.let { name -> Engine.providers().firstOrNull { it.name == name } } }
    LaunchedEffect(query, scopedApi) {
        results.clear()
        if (query.isBlank()) return@LaunchedEffect
        io.github.sherazi5110.cloudstream.SearchHistory.add(query)
        val apis = scopedApi?.let { listOf(it) } ?: Engine.providers()
        searching = apis.size
        Engine.search(query.trim(), apis).collect { batch ->
            searching--
            if (batch.results.isNotEmpty()) results.add(batch.api to batch.results)
        }
        searching = 0
    }
    LaunchedEffect(Unit) { if (!isTv && query.isBlank()) runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            // The text being typed (the search that ran is Nav.searchSubmitted).
            value = typed,
            onValueChange = { Nav.searchQuery = it; if (it.isBlank()) Nav.searchSubmitted = "" },
            placeholder = { Text(scopedApi?.let { "Search in ${it.name}…" } ?: "Search films, series, anime…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (typed.isNotEmpty()) Icon(Icons.Filled.Close, "Clear", Modifier.card(CircleShape) { Nav.searchQuery = ""; Nav.searchSubmitted = "" }.padding(6.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(50),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit(typed) }),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Palette.surface, focusedContainerColor = Palette.surface,
                unfocusedBorderColor = Color.Transparent, focusedBorderColor = Palette.accent),
            modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus),
        )
        if (scopedApi != null) Row(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Searching in ${scopedApi.name}", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
            PillButton("Search all extensions", Icons.Filled.Search, primary = false, compact = true) { Nav.searchScope = null }
        }
        if (suggestions.isNotEmpty()) Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(Palette.surfaceHigh, RoundedCornerShape(14.dp)).padding(6.dp),
        ) {
            suggestions.forEach { s ->
                Row(Modifier.fillMaxWidth().card(RoundedCornerShape(10.dp), focusScale = 1.02f) { submit(s) }.padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Search, null, tint = Palette.muted, modifier = Modifier.size(18.dp))
                    Text(s, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
        when {
            query.isBlank() -> if (suggestions.isEmpty()) RecentSearches(submit) {
                EmptyState("Search", if (scopedApi != null) "Type a title and press Search to look for it in ${scopedApi.name}."
                    else "Type a title and press Search, or pick a suggestion; every installed extension is searched at once.", Modifier.fillMaxWidth())
            }
            results.isEmpty() && searching > 0 -> Spinner(Modifier.fillMaxWidth().height(200.dp))
            results.isEmpty() && !App.online() -> OfflineState(Modifier.fillMaxWidth()) { submit(query) }
            results.isEmpty() -> EmptyState("Nothing found", (scopedApi?.let { "${it.name} had" } ?: "No extension had") + " results for \"$query\".", Modifier.fillMaxWidth())
            // One extension: its results as a grid.
            scopedApi != null -> LazyVerticalGrid(posterCells(if (isWide) 140.dp else 112.dp),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + LocalBottomInset.current), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                uniqueItems(results.flatMap { it.second }, key = { it.url }) { item -> PosterCard(item, 200.dp, Modifier.fillMaxWidth()) { openTitle(item) } }
            }
            else -> androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + LocalBottomInset.current)) {
                uniqueItems(results, key = { it.first.name }) { (api, list) ->
                    Column(Modifier.padding(top = 8.dp)) {
                        SectionTitle("${api.name} · ${list.size}", Modifier.padding(horizontal = 16.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            uniqueItems(list, key = { it.url }) { item -> PosterCard(item, if (isWide) 140.dp else 112.dp) { openTitle(item) } }
                        }
                    }
                }
                if (searching > 0) item { Text("Still searching $searching extension(s)…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

/** My list (bookmarks) and history. Phase 2 adds profiles and watched controls here. */
@Composable
fun LibraryScreen() {
    var bookmarks by remember { mutableStateOf(Library.bookmarks()) }
    var history by remember { mutableStateOf(Library.history()) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val off = Library.onChange { bookmarks = Library.bookmarks(); history = Library.history() }
        onDispose { off() }
    }
    // Saved with the tab: playing a download and coming back shows Downloads again, not My list.
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    if (openLibraryDownloads) { openLibraryDownloads = false; tab = 2 }
    // Back inside the list goes to the tabs (My list · History · Downloads) first, then Home.
    val tabFocus = remember { FocusRequester() }
    val content = backToHeader(tabFocus)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("My list", "History", "Downloads").forEachIndexed { i, label ->
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier
                    .then(if (tab == i) Modifier.focusRequester(tabFocus) else Modifier)
                    .card(RoundedCornerShape(50)) { tab = i }
                    .background(if (tab == i) Palette.accent else Palette.surfaceHigh, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        Box(content.weight(1f)) {
        if (tab == 2) DownloadsTab()
        else if (tab == 0) {
            if (bookmarks.isEmpty()) EmptyState("Your list is empty", "Tap + My list on a title page to keep it here.", Modifier.fillMaxWidth())
            else LazyVerticalGrid(gridCells, contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + LocalBottomInset.current),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                uniqueItems(bookmarks, key = { "${it.apiName}|${it.url}" }) { b ->
                    Column {
                        Box(Modifier.fillMaxWidth().height(if (isWide) 210.dp else 160.dp).card { Nav.open(Screen.Details(b.apiName, b.url, b.name, b.posterUrl)) }) {
                            NetImage(b.posterUrl, Modifier.fillMaxSize())
                        }
                        Text(b.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        } else {
            if (history.isEmpty()) EmptyState("No history yet", "Titles you watch appear here.", Modifier.fillMaxWidth())
            else LazyVerticalGrid(if (isWide) GridCells.Adaptive(260.dp) else GridCells.Adaptive(170.dp), contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + LocalBottomInset.current),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                uniqueItems(history, key = { "${it.apiName}|${it.url}" }) { e ->
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        WideCard(e.name, if (e.finished) "Watched" else e.leftText(), e.posterUrl, e.progress.toFloat(), 400.dp,
                            Modifier.fillMaxWidth(), onLongClick = { menu = true }) { openTitle(e) }
                        WatchedMenu(menu, e) { menu = false }
                    }
                }
            }
        }
        }
    }
}

/** Set before opening the Library tab to show its Downloads (from the offline screen). */
var openLibraryDownloads = false

/** Recent searches (this profile's), to search again with one tap; [empty] when there are none. */
@Composable
internal fun RecentSearches(
    onSearch: (String) -> Unit,
    initial: List<String> = io.github.sherazi5110.cloudstream.SearchHistory.all(),
    empty: @Composable () -> Unit,
) {
    var recent by remember { mutableStateOf(initial) }
    if (recent.isEmpty()) { empty(); return }
    androidx.compose.foundation.lazy.LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Recent searches", Modifier.weight(1f))
                PillButton("Clear", null, primary = false, compact = true) {
                    io.github.sherazi5110.cloudstream.SearchHistory.clear(); recent = emptyList()
                }
            }
        }
        uniqueItems(recent, key = { "recent|$it" }) { q ->
            // × sits beside the search (→ from it), not inside it, where the remote couldn't reach it.
            ActionRow(onClick = { onSearch(q) }, modifier = Modifier.fillMaxWidth(),
                padding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                actions = {
                    Icon(Icons.Filled.Close, "Remove $q", tint = Palette.muted, modifier = Modifier.size(32.dp).card(CircleShape) {
                        io.github.sherazi5110.cloudstream.SearchHistory.remove(q); recent = io.github.sherazi5110.cloudstream.SearchHistory.all()
                    }.padding(6.dp))
                }) {
                Icon(Icons.Filled.History, null, tint = Palette.muted, modifier = Modifier.size(20.dp))
                Text(q, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 12.dp))
            }
        }
    }
}
