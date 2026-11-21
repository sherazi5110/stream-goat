package io.github.sherazi5110.cloudstream.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sherazi5110.cloudstream.HistoryStore
import io.github.sherazi5110.cloudstream.Music
import io.github.sherazi5110.cloudstream.MusicLibrary
import io.github.sherazi5110.cloudstream.WebHistory
import kotlinx.coroutines.launch

/**
 * The Music tab, laid out like YouTube Music: Home (moods, Quick picks, shelves), Explore (trending, moods &
 * genres), Library (Downloads, Liked, playlists, recent) and Search; a full-screen player with Up next; the song menu
 * with Play next / Save to playlist / Share and the rest. Kept while the app runs, so coming back shows the same page.
 */
object MusicUi {
    var page by mutableStateOf("home")            // home, explore, library
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Music.Track>>(emptyList())
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** A mood or genre opened from Home/Explore: its name and songs. */
    var mood by mutableStateOf<Pair<String, List<Music.Track>>?>(null)
    /** A list opened in Library: "fav", "dl", "recent" or a playlist's name. */
    var openList by mutableStateOf<String?>(null)
    var playerOpen by mutableStateOf(false)
    var menuFor by mutableStateOf<Music.Track?>(null)
    /** Which playlist the menu was opened from (for "Remove from …"). */
    var menuPlaylist: String? = null
    var saveFor by mutableStateOf<Music.Track?>(null)
    var newPlaylist by mutableStateOf(false)

    val moods = listOf("Energize", "Relax", "Feel good", "Party", "Commute", "Workout", "Romance", "Sad", "Focus", "Sleep")
    val genres = listOf("Bollywood & Indian", "Punjabi", "Pop", "Hip-hop", "Romance", "Indie & alternative", "Chill", "Classical",
        "Sufi & Qawwali", "Rock", "Lo-fi", "Ghazal", "Arabic", "K-Pop", "Soundtracks", "Workout")
    private val genreColors = listOf(0xFF7C4DFF, 0xFFFF4081, 0xFFE53935, 0xFFFF6D00, 0xFF00BFA5, 0xFF9E9E9E, 0xFF40C4FF, 0xFFBDBDBD,
        0xFF66BB6A, 0xFFD50000, 0xFF8D6E63, 0xFFFFD600, 0xFFFF7043, 0xFFEC407A, 0xFF26C6DA, 0xFFFFAB00)
    fun genreColor(i: Int) = Color(genreColors[i % genreColors.size])

    fun tracks(items: List<HistoryStore.Entry>) = items.map { Music.Track(it.id, it.title, it.subtitle, it.image, 0) }

    fun listTracks(id: String): List<Music.Track> = when (id) {
        "fav" -> tracks(MusicLibrary.favourites.items)
        "dl" -> tracks(MusicLibrary.downloads.items)
        "recent" -> tracks(WebHistory.songs.items.take(100))
        else -> MusicLibrary.playlists[id].orEmpty()
    }
    fun listName(id: String) = when (id) { "fav" -> "Liked songs"; "dl" -> "Downloaded"; "recent" -> "Recently played"; else -> id }
}

private val PageSide @Composable get() = if (isWide) 40.dp else 16.dp

@Composable
fun MusicScreen() {
    val scope = rememberCoroutineScope()
    // Home's shelves load once when the tab opens (suggestions follow the last song played).
    LaunchedEffect(Unit) {
        runCatching { if (Music.popular.isEmpty()) Music.loadPopular() }
        (Music.current ?: WebHistory.songs.items.firstOrNull()?.let { Music.Track(it.id, it.title, it.subtitle, it.image, 0) })
            ?.let { runCatching { Music.loadSuggestions(it) } }
    }
    fun search(q: String) {
        if (q.isBlank()) return
        MusicUi.busy = true; MusicUi.error = null
        scope.launch {
            runCatching { Music.search(q) }
                .onSuccess { MusicUi.results = it; if (it.isEmpty()) MusicUi.error = "Nothing found for \"$q\"" }
                .onFailure { MusicUi.error = "Search failed: ${it.message}" }
            MusicUi.busy = false
        }
    }
    fun openMood(name: String) {
        MusicUi.mood = name to emptyList(); MusicUi.busy = true
        scope.launch {
            runCatching { Music.search("$name songs") }.onSuccess { MusicUi.mood = name to it }.onFailure { MusicUi.error = it.message }
            MusicUi.busy = false
        }
    }

    BackHandler(MusicUi.playerOpen || MusicUi.searching || MusicUi.mood != null || MusicUi.openList != null) {
        when {
            MusicUi.playerOpen -> MusicUi.playerOpen = false
            MusicUi.mood != null -> MusicUi.mood = null
            MusicUi.openList != null -> MusicUi.openList = null
            else -> { MusicUi.searching = false; MusicUi.results = emptyList(); MusicUi.query = "" }
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.accent.copy(alpha = 0.18f), Color.Black, Color.Black)))) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            val mood = MusicUi.mood
            val open = MusicUi.openList
            when {
                MusicUi.searching -> SearchPage(::search)
                mood != null -> TrackListPage(mood.first, mood.second, onBack = { MusicUi.mood = null })
                open != null -> TrackListPage(MusicUi.listName(open), MusicUi.listTracks(open), onBack = { MusicUi.openList = null },
                    playlist = open.takeIf { it !in setOf("fav", "dl", "recent") },
                    action = when (open) {
                        "recent" -> "Clear" to { WebHistory.songs.clear() }
                        "fav", "dl" -> null
                        else -> "Delete" to { MusicLibrary.deletePlaylist(open); MusicUi.openList = null }
                    })
                else -> {
                    TopBar()
                    when (MusicUi.page) {
                        "explore" -> ExplorePage(::openMood)
                        "library" -> LibraryPage()
                        else -> HomePage(::openMood)
                    }
                }
            }
        }
        if (MusicUi.playerOpen && Music.current != null) FullPlayer()
        MusicUi.menuFor?.let { SongMenu(it) }
    }
    MusicUi.saveFor?.let { SaveToPlaylist(it) }
    if (MusicUi.newPlaylist) NewPlaylistDialog()
}

// ---------- top bar and pages ----------

@Composable
private fun TopBar() {
    Row(Modifier.fillMaxWidth().padding(horizontal = PageSide, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(Color(0xFFFF0033), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Text("Music", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.width(20.dp))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("home" to "Home", "explore" to "Explore", "library" to "Library").forEach { (id, label) ->
                Chip(label, MusicUi.page == id) { MusicUi.page = id }
            }
        }
        IconAction(Icons.Filled.Search, "Search") { MusicUi.searching = true }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.Black else Color.White,
        modifier = Modifier.card(RoundedCornerShape(8.dp)) { onClick() }
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun HomePage(openMood: (String) -> Unit) {
    val quick = Music.suggestions.ifEmpty { Music.popular }
    val recent = MusicUi.tracks(WebHistory.songs.items.take(20))
    val liked = MusicUi.tracks(MusicLibrary.favourites.items.take(20))
    val downloads = MusicUi.tracks(MusicLibrary.downloads.items.take(20))
    val side = PageSide
    val rowWidth = if (isWide) 440.dp else 300.dp
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp + LocalBottomInset.current)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = side, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MusicUi.moods.forEach { m -> Chip(m, false) { openMood(m) } }
            }
        }
        if (quick.isNotEmpty()) item {
            ShelfTitle("Quick picks", "Play all") { Music.play(quick.first(), quick) }
            // Four rows of songs that scroll sideways, like YouTube Music.
            LazyHorizontalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().height(280.dp),
                contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(quick.take(24).size, key = { "q" + quick[it].url }) { i -> SongRow(quick[i], quick, Modifier.width(rowWidth)) }
            }
        }
        if (recent.isNotEmpty()) item { Shelf("Listen again", recent) }
        if (liked.isNotEmpty()) item { Shelf("Your liked songs", liked) }
        if (Music.popular.isNotEmpty()) item { Shelf("Trending", Music.popular) }
        if (downloads.isNotEmpty()) item { Shelf("Downloaded", downloads) }
        if (quick.isEmpty() && recent.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun ExplorePage(openMood: (String) -> Unit) {
    val side = PageSide
    val cols = if (isWide) 4 else 2
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp + LocalBottomInset.current)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = side, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("New releases" to "New songs this week", "Charts" to "Top songs this week", "Party" to "Party")
                    .forEach { (label, q) ->
                        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
                            .card(RoundedCornerShape(12.dp)) { openMood(q) }
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(18.dp))
                    }
            }
        }
        if (Music.popular.isNotEmpty()) item { Shelf("Trending", Music.popular) }
        item { ShelfTitle("Moods & genres") }
        MusicUi.genres.chunked(cols).forEachIndexed { r, row ->
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = side, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEachIndexed { c, g ->
                        Row(Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(8.dp)).card(RoundedCornerShape(8.dp)) { openMood(g) }
                            .background(Color.White.copy(alpha = 0.07f)), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(5.dp).fillMaxHeight().background(MusicUi.genreColor(r * cols + c)))
                            Text(g, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp))
                        }
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun LibraryPage() {
    var filter by remember { mutableStateOf("all") }
    val side = PageSide
    val rows = buildList {
        if (filter in setOf("all", "playlists")) {
            add(Triple("fav", "Liked songs", "Auto playlist · ${MusicLibrary.favourites.items.size} songs"))
            MusicLibrary.playlists.keys.sorted().forEach { add(Triple(it, it, "Playlist · ${MusicLibrary.playlists[it]?.size ?: 0} songs")) }
        }
        if (filter in setOf("all", "downloads")) add(Triple("dl", "Downloaded", "${MusicLibrary.downloads.items.size} songs · plays without internet"))
        if (filter in setOf("all", "recent")) add(Triple("recent", "Recently played", "${WebHistory.songs.items.size} songs"))
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp + LocalBottomInset.current)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = side, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "All", "downloads" to "Downloads", "playlists" to "Playlists", "recent" to "Recent").forEach { (id, label) ->
                        Chip(label, filter == id) { filter = id }
                    }
                }
            }
            items(rows, key = { "l" + it.first }) { (id, name, sub) ->
                val cover = MusicUi.listTracks(id).firstOrNull()?.thumbnail
                Row(Modifier.fillMaxWidth().card(RoundedCornerShape(8.dp)) { MusicUi.openList = id }.padding(horizontal = side, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)).background(
                        if (id == "fav") Brush.linearGradient(listOf(Color(0xFF8E24AA), Color(0xFFEC407A)))
                        else Brush.linearGradient(listOf(Palette.surfaceHigh, Palette.surfaceHigh))),
                        contentAlignment = Alignment.Center) {
                        when {
                            id == "fav" -> Icon(Icons.Filled.Favorite, null, tint = Color.White, modifier = Modifier.size(30.dp))
                            id == "dl" -> Icon(Icons.Filled.DownloadDone, null, tint = Color.White, modifier = Modifier.size(30.dp))
                            cover != null -> NetImage(cover, Modifier.fillMaxSize())
                            else -> Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Color.White)
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sub, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, maxLines = 1)
                    }
                }
            }
        }
        // "+ New" playlist, like YouTube Music's.
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 100.dp + LocalBottomInset.current)
            .card(RoundedCornerShape(50)) { MusicUi.newPlaylist = true }.background(Color.White, RoundedCornerShape(50))
            .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Add, null, tint = Color.Black)
            Text("New", color = Color.Black, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SearchPage(search: (String) -> Unit) {
    val side = PageSide
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = side, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back") { MusicUi.searching = false; MusicUi.results = emptyList(); MusicUi.query = "" }
            OutlinedTextField(MusicUi.query, { MusicUi.query = it }, singleLine = true, shape = RoundedCornerShape(50),
                placeholder = { Text("Search songs, artists, albums") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (MusicUi.query.isNotEmpty()) IconAction(Icons.Filled.Close, "Clear") { MusicUi.query = "" } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search(MusicUi.query.trim()) }),
                modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
        if (MusicUi.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        MusicUi.error?.let { Text(it, color = Palette.muted, modifier = Modifier.padding(horizontal = side, vertical = 8.dp)) }
        val results = MusicUi.results
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 4.dp, bottom = 110.dp + LocalBottomInset.current)) {
            // The top result big, like YouTube Music.
            results.firstOrNull()?.let { top ->
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        NetImage(top.thumbnail, Modifier.size(110.dp).clip(RoundedCornerShape(8.dp)))
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(top.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("Song · ${top.artist}" + (top.seconds.takeIf { it > 0 }?.let { " · " + clock(it * 1000) } ?: ""),
                                style = MaterialTheme.typography.bodyMedium, color = Palette.muted, maxLines = 1)
                            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PillButton("Play", Icons.Filled.PlayArrow, compact = true) { Music.play(top, results) }
                                PillButton("Save", Icons.AutoMirrored.Filled.PlaylistAdd, primary = false, compact = true) { MusicUi.saveFor = top }
                            }
                        }
                    }
                }
            }
            items(results.drop(1), key = { "r" + it.url }) { t -> SongRow(t, results) }
            if (results.isEmpty() && !MusicUi.busy && MusicUi.error == null) item {
                Text("Search for a song or an artist. Music plays from YouTube, audio only, without ads, and keeps playing in the background.",
                    color = Palette.muted, modifier = Modifier.padding(vertical = 16.dp))
            }
        }
    }
}

@Composable
private fun TrackListPage(title: String, tracks: List<Music.Track>, onBack: () -> Unit, playlist: String? = null, action: Pair<String, () -> Unit>? = null) {
    val side = PageSide
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp + LocalBottomInset.current)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = side, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back") { onBack() }
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                action?.let { (label, run) -> PillButton(label, null, primary = false, compact = true) { run() } }
            }
            if (tracks.isNotEmpty()) Row(Modifier.padding(horizontal = side, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Play all", Icons.Filled.PlayArrow, compact = true) { Music.shuffle = false; Music.play(tracks.first(), tracks) }
                PillButton("Shuffle", Icons.Filled.Shuffle, primary = false, compact = true) { Music.shuffle = true; Music.play(tracks.random(), tracks) }
            }
            if (MusicUi.busy && tracks.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(side))
            else if (tracks.isEmpty()) Text("Nothing here yet. Tap ⋮ on a song (or long-press it) to like, download or save it.",
                color = Palette.muted, modifier = Modifier.padding(horizontal = side, vertical = 12.dp))
        }
        items(tracks, key = { "t" + it.url }) { t -> SongRow(t, tracks, Modifier.padding(horizontal = side), playlist = playlist) }
    }
}

// ---------- pieces ----------

@Composable
private fun ShelfTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = PageSide, end = PageSide, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) Text(action, style = MaterialTheme.typography.labelLarge, modifier = Modifier
            .card(RoundedCornerShape(50)) { onAction() }.border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

/** A row of square song cards, like YouTube Music's shelves. */
@Composable
private fun Shelf(title: String, tracks: List<Music.Track>) {
    val w = if (isWide) 190.dp else 150.dp
    ShelfTitle(title, "Play all") { Music.play(tracks.first(), tracks) }
    TvRowScroll {
        LazyRow(contentPadding = PaddingValues(horizontal = PageSide), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(tracks, key = { title + it.url }) { t ->
                Column(Modifier.width(w).card(RoundedCornerShape(8.dp), onLongClick = { MusicUi.menuPlaylist = null; MusicUi.menuFor = t }) { Music.play(t, tracks) }) {
                    Box {
                        NetImage(t.thumbnail, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)))
                        if (MusicLibrary.isDownloaded(t)) Icon(Icons.Filled.DownloadDone, null, tint = Color.White,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(3.dp).size(16.dp))
                    }
                    Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Text(t.artist, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A song in a list: picture, title, artist, ⋮. Tap plays it (the list after it plays next); long-press or ⋮ for the menu. */
@Composable
private fun SongRow(t: Music.Track, list: List<Music.Track>, modifier: Modifier = Modifier, playlist: String? = null) {
    val now = Music.current?.url == t.url
    val menu = { MusicUi.menuPlaylist = playlist; MusicUi.menuFor = t }
    Row(modifier.fillMaxWidth().card(RoundedCornerShape(8.dp), focusScale = 1.02f, onLongClick = menu) { Music.play(t, list) }
        .background(if (now) Color.White.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(8.dp)).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box {
            NetImage(t.thumbnail, Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)))
            if (now && Music.playing) Icon(Icons.Filled.Pause, null, tint = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(4.dp).size(18.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val dl = MusicLibrary.downloading[t.url]
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (MusicLibrary.isDownloaded(t)) Icon(Icons.Filled.DownloadDone, "Downloaded", tint = Palette.muted, modifier = Modifier.padding(end = 4.dp).size(14.dp))
                Text(listOfNotNull(t.artist.ifBlank { null }, dl?.let { "Downloading $it%" }, t.seconds.takeIf { it > 0 }?.let { clock(it * 1000) })
                    .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconAction(Icons.Filled.MoreVert, "More") { menu() }
    }
}

// ---------- the song menu (a sheet from the bottom, like YouTube Music's) ----------

@Composable
private fun SongMenu(t: Music.Track) {
    val context = LocalContext.current
    val close = { MusicUi.menuFor = null }
    BackHandler { close() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).card(RoundedCornerShape(0.dp), focusScale = 1f) { close() },
        contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().background(Color(0xFF212121), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(bottom = 12.dp + LocalBottomInset.current)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                NetImage(t.thumbnail, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1)
                }
                IconAction(if (MusicLibrary.isFavourite(t)) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Like") { MusicLibrary.toggleFavourite(t) }
                IconAction(Icons.Filled.Close, "Close") { close() }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(Icons.AutoMirrored.Filled.QueueMusic, "Play next", Modifier.weight(1f)) { Music.playNext(t); close() }
                Tile(Icons.AutoMirrored.Filled.PlaylistAdd, "Save to playlist", Modifier.weight(1f)) { MusicUi.saveFor = t; close() }
                Tile(Icons.Filled.Share, "Share", Modifier.weight(1f)) {
                    runCatching {
                        context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, "${t.title} — ${t.artist}\n${t.url}"), "Share")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    close()
                }
            }
            MenuLine(Icons.Filled.Radio, "Start mix") { Music.startMix(t); close() }
            MenuLine(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue") { Music.addToQueue(t); close() }
            MenuLine(if (MusicLibrary.isFavourite(t)) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                if (MusicLibrary.isFavourite(t)) "Remove from liked songs" else "Add to liked songs") { MusicLibrary.toggleFavourite(t); close() }
            when {
                MusicLibrary.isDownloaded(t) -> MenuLine(Icons.Filled.DownloadDone, "Remove download") { MusicLibrary.deleteDownload(t); close() }
                t.url in MusicLibrary.downloading -> MenuLine(Icons.Filled.Download, "Downloading ${MusicLibrary.downloading[t.url]}%") { close() }
                else -> MenuLine(Icons.Filled.Download, "Download") { MusicLibrary.download(t); close() }
            }
            MusicUi.menuPlaylist?.let { p -> MenuLine(Icons.Filled.Close, "Remove from $p") { MusicLibrary.removeFromPlaylist(p, t); close() } }
        }
    }
}

@Composable
private fun Tile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(64.dp).card(RoundedCornerShape(10.dp)) { onClick() }
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Icon(icon, label, tint = Color.White) }
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun MenuLine(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().card(RoundedCornerShape(8.dp), focusScale = 1.01f) { onClick() }.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp))
    }
}

@Composable
private fun SaveToPlaylist(t: Music.Track) {
    var name by remember { mutableStateOf("") }
    SimpleDialog("Save to playlist", { MusicUi.saveFor = null }) {
        MusicLibrary.playlists.keys.sorted().forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).card(RoundedCornerShape(10.dp)) { MusicLibrary.addToPlaylist(p, t); MusicUi.saveFor = null }
                .background(Palette.surface, RoundedCornerShape(10.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, null)
                Text(p, modifier = Modifier.padding(start = 12.dp))
            }
        }
        OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("New playlist name") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        PillButton("Create and save", Icons.Filled.Add, Modifier.padding(top = 8.dp), compact = true) {
            if (name.isNotBlank()) { MusicLibrary.addToPlaylist(name, t); MusicUi.saveFor = null }
        }
    }
}

@Composable
private fun NewPlaylistDialog() {
    var name by remember { mutableStateOf("") }
    SimpleDialog("New playlist", { MusicUi.newPlaylist = false }) {
        OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        PillButton("Create", Icons.Filled.Add, Modifier.padding(top = 8.dp), compact = true) {
            if (name.isNotBlank()) { MusicLibrary.createPlaylist(name); MusicUi.newPlaylist = false; MusicUi.openList = name.trim() }
        }
    }
}

// ---------- players ----------

/** The full-screen player: artwork, actions, seek bar and controls; Up next beside it (below on phones). */
@Composable
private fun FullPlayer() {
    val t = Music.current ?: return
    val wide = isWide
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2B2420), Color(0xFF121212)))).statusBarsPadding()) {
        if (wide) Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).padding(24.dp)) { PlayerMain(t, Modifier.weight(1f)) }
            UpNext(Modifier.weight(1f).fillMaxHeight().padding(top = 12.dp, end = 16.dp))
        } else Column(Modifier.fillMaxSize().padding(16.dp)) {
            PlayerMain(t, Modifier.weight(1f))
            UpNext(Modifier.height(240.dp))
        }
    }
}

@Composable
private fun PlayerMain(t: Music.Track, artModifier: Modifier) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconAction(Icons.Filled.KeyboardArrowDown, "Close player") { MusicUi.playerOpen = false }
        Spacer(Modifier.weight(1f))
        IconAction(Icons.Filled.MoreVert, "More") { MusicUi.menuPlaylist = null; MusicUi.menuFor = t }
    }
    Box(artModifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        NetImage(t.thumbnail, Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(10.dp)))
    }
    Text(t.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(Music.error ?: if (Music.loading) "Loading…" else t.artist, style = MaterialTheme.typography.titleMedium, color = Palette.muted, maxLines = 1)
    Row(Modifier.padding(vertical = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val liked = MusicLibrary.isFavourite(t)
        ActionPill(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (liked) "Liked" else "Like") { MusicLibrary.toggleFavourite(t) }
        ActionPill(Icons.AutoMirrored.Filled.PlaylistAdd, "Save") { MusicUi.saveFor = t }
        when {
            MusicLibrary.isDownloaded(t) -> ActionPill(Icons.Filled.DownloadDone, "Downloaded") { MusicLibrary.deleteDownload(t) }
            t.url in MusicLibrary.downloading -> ActionPill(Icons.Filled.Download, "${MusicLibrary.downloading[t.url]}%") { }
            else -> ActionPill(Icons.Filled.Download, "Download") { MusicLibrary.download(t) }
        }
        ActionPill(Icons.Filled.Radio, "Mix") { Music.startMix(t) }
    }
    var drag by remember { mutableStateOf<Float?>(null) }
    Slider(value = drag ?: if (Music.length > 0) (Music.position.toFloat() / Music.length).coerceIn(0f, 1f) else 0f,
        onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { Music.seekTo((it * Music.length).toLong()) }; drag = null },
        modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth()) {
        Text(clock(Music.position), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
        Spacer(Modifier.weight(1f))
        Text(clock(Music.length), style = MaterialTheme.typography.labelSmall, color = Palette.muted)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconAction(Icons.Filled.Shuffle, "Shuffle", tint = if (Music.shuffle) Palette.accent else Color.White) { Music.shuffle = !Music.shuffle }
        IconAction(Icons.Filled.SkipPrevious, "Previous") { Music.previous() }
        Box(Modifier.size(72.dp).card(CircleShape) { Music.toggle() }.background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
            if (Music.loading) CircularProgressIndicator(Modifier.size(32.dp), color = Color.Black)
            else Icon(if (Music.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", tint = Color.Black, modifier = Modifier.size(40.dp))
        }
        IconAction(Icons.Filled.SkipNext, "Next") { Music.next() }
        IconAction(if (Music.repeat == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repeat",
            tint = if (Music.repeat > 0) Palette.accent else Color.White) { Music.repeat = (Music.repeat + 1) % 3 }
    }
}

@Composable
private fun ActionPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.card(RoundedCornerShape(50)) { onClick() }.background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(50))
        .padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

/** What plays after this song, then similar songs (YouTube's suggestions). */
@Composable
private fun UpNext(modifier: Modifier) {
    val next = Music.upNext
    val similar = Music.suggestions.filter { s -> s.url != Music.current?.url && next.none { it.url == s.url } }
    LazyColumn(modifier) {
        item {
            Text("UP NEXT", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
        }
        items(next.take(50), key = { "n" + it.url }) { t -> SongRow(t, Music.queue) }
        if (similar.isNotEmpty()) {
            item { Text("Similar songs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)) }
            items(similar.take(30), key = { "s" + it.url }) { t -> SongRow(t, listOf(t) + similar.filter { it.url != t.url }) }
        }
    }
}

/** The bar above the tabs while a song plays (on every tab): tap it for the full player. */
@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
    val t = Music.current ?: return
    if (MusicUi.playerOpen && Nav.tab == Tab.MUSIC) return
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF212121))
        .card(RoundedCornerShape(12.dp), focusScale = 1.01f) { Nav.tab = Tab.MUSIC; MusicUi.playerOpen = true }) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NetImage(t.thumbnail, Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Music.error ?: if (Music.loading) "Loading…" else t.artist, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1)
            }
            IconAction(Icons.Filled.SkipPrevious, "Previous") { Music.previous() }
            IconAction(if (Music.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause") { Music.toggle() }
            IconAction(Icons.Filled.SkipNext, "Next") { Music.next() }
            IconAction(Icons.Filled.Close, "Stop") { Music.stop() }
        }
        if (Music.length > 0) LinearProgressIndicator(progress = { (Music.position.toFloat() / Music.length).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp), color = Color.White, trackColor = Color.White.copy(alpha = 0.15f))
    }
}

private fun clock(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
