package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.allowHardware
import androidx.compose.foundation.layout.fillMaxHeight
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.ChannelLogos
import io.github.sherazi5110.cloudstream.Engine
import io.github.sherazi5110.cloudstream.LiveApi
import io.github.sherazi5110.cloudstream.LiveTv
import io.github.sherazi5110.cloudstream.StremioAddons
import io.github.sherazi5110.cloudstream.StremioApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

object LivePlayback {
    /** Opens a channel in the player; [list] lets CH+ / CH− move through the channels shown. */
    fun play(scope: CoroutineScope, c: LiveTv.Channel, list: List<LiveTv.Channel>, replace: Boolean = false, chooseSource: Boolean = false) {
        LiveTv.watched(c)
        // A YouTube, Twitch or Dailymotion channel: plays in that service's own embed player, inside the app.
        if (LiveTv.webEmbed(c) != null) {
            val screen = Screen.WebPlayer(c, list)
            if (replace) Nav.replace(screen) else Nav.open(screen)
            return
        }
        // Anything else that's a web page: that service's app (or the browser) can open it.
        LiveTv.webPage(c)?.let { service ->
            val opened = runCatching {
                App.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(c.url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            App.toast(if (opened) "Opening ${c.name} on $service" else "${c.name} is a $service channel: install the $service app or a browser to watch it")
            return
        }
        scope.launch {
            runCatching {
                val (api, r) = if (c.source.startsWith("stremio:")) {
                    val addon = StremioAddons.all().first { "stremio:" + it.manifest.id == c.source }
                    val api = StremioApi(addon)
                    api to (Engine.load(api, c.url) ?: error("The add-on returned nothing for ${c.name}"))
                } else LiveApi to LiveApi.load(LiveApi.data(c))!!
                val playable = Engine.playables(r).firstOrNull() ?: error("Nothing to play")
                val screen = Screen.Player(api, r, playable, chooseSource = chooseSource, channels = list, channel = c)
                if (replace) Nav.replace(screen) else Nav.open(screen)
            }.onFailure { App.toast("Couldn't play ${c.name}: ${it.message ?: it}") }
        }
    }

    /** CH+ / CH−: the channel [step] places away in the list, wrapping around. */
    fun neighbour(list: List<LiveTv.Channel>, current: LiveTv.Channel, step: Int): LiveTv.Channel? {
        if (list.isEmpty()) return null
        val i = list.indexOfFirst { it.key == current.key }.coerceAtLeast(0)
        return list[(i + step).mod(list.size)]
    }
}

/** The channel's logo (playlist, else iptv-org), over its initials so a missing logo still looks right. */
@Composable
fun ChannelLogo(c: LiveTv.Channel, size: androidx.compose.ui.unit.Dp) {
    val logo = rememberLogo(c)
    val hue = (c.name.hashCode() and 0xffffff).let { Color(0xFF000000 or it.toLong()).copy(alpha = 1f) }
    Box(Modifier.size(size).background(hue.copy(red = hue.red * 0.6f, green = hue.green * 0.6f, blue = hue.blue * 0.6f), RoundedCornerShape(size * 0.2f)),
        contentAlignment = Alignment.Center) {
        Text(c.name.filter { it.isLetterOrDigit() }.take(3).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.28f).sp)
        if (logo != null) LogoImage(logo, c.name, Modifier.fillMaxSize(), RoundedCornerShape(size * 0.2f), size * 0.12f)
    }
}

@Composable
private fun rememberLogo(c: LiveTv.Channel): String? =
    produceState(c.logo, c.key) { value = runCatching { ChannelLogos.candidates(c).firstOrNull() }.getOrNull() ?: c.logo }.value

/**
 * A logo on a background that shows it: light behind a dark logo (Bloomberg's black letters vanished on the dark
 * tile), dark behind a white one. Decided from the picture's own pixels, once per logo.
 */
@Composable
private fun LogoImage(url: String, name: String, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, pad: androidx.compose.ui.unit.Dp) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var dark by remember(url) { mutableStateOf(LogoTone.known[url]) }
    val request = remember(url) { coil3.request.ImageRequest.Builder(ctx).data(url).allowHardware(false).build() }
    val bg = when (dark) { true -> Color(0xFFEDEDF2); false -> Color(0xFF1B1B22); null -> Color.Transparent }
    AsyncImage(request, name, contentScale = ContentScale.Fit,
        onSuccess = { st -> if (dark == null) dark = LogoTone.isDark(url, st.result.image) },
        modifier = modifier.background(bg, shape).padding(pad))
}

/** Whether a logo is mostly dark (needs a light background); remembered per address. */
internal object LogoTone {
    val known = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun isDark(url: String, image: coil3.Image): Boolean = known.getOrPut(url) {
        runCatching {
            val bmp = (image as? coil3.BitmapImage)?.bitmap ?: return@runCatching false
            val small = android.graphics.Bitmap.createScaledBitmap(bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false), 24, 24, true)
            val px = IntArray(24 * 24).also { small.getPixels(it, 0, 24, 0, 0, 24, 24) }
            darkPixels(px)
        }.getOrDefault(false)
    }

    /** Mostly dark over its visible pixels (a transparent background doesn't count); an opaque picture keeps the dark tile. */
    fun darkPixels(argb: IntArray): Boolean {
        var seen = 0; var light = 0.0; var opaqueAll = true
        for (p in argb) {
            val a = (p ushr 24) and 0xff
            if (a < 200) { opaqueAll = false; if (a < 60) continue }
            val l = (0.2126 * ((p shr 16) and 0xff) + 0.7152 * ((p shr 8) and 0xff) + 0.0722 * (p and 0xff)) / 255
            light += l; seen++
        }
        // A full picture (a photo or a logo on its own background) already shows; only see-through logos need help.
        if (opaqueAll || seen == 0) return false
        return light / seen < 0.42
    }
}

@Composable
private fun LiveBadge(modifier: Modifier = Modifier, label: String = "LIVE") {
    Row(modifier.background(Color(0xFFE11D48), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(Color.White, RoundedCornerShape(3.dp)))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(start = 4.dp))
    }
}

/** "Region-locked": the playlist says the channel only plays in its own country. */
@Composable
private fun RegionBadge(modifier: Modifier = Modifier) {
    Text("Region-locked", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
        modifier = modifier.background(Color(0xCC111118), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}

private fun progressOf(p: LiveTv.Programme, now: Long) = ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)

@Composable
fun LiveTvScreen() {
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { val off = LiveTv.onChange { version++ }; onDispose { off() } }
    var channels by remember { mutableStateOf<List<LiveTv.Channel>?>(null) }
    var guideVersion by remember { mutableIntStateOf(0) }
    var force by remember { mutableStateOf(false) }
    // One source or all of them, as on Windows/Linux ("All sources ▾").
    var sourceId by remember { mutableStateOf<String?>(null) }
    var pickingSource by remember { mutableStateOf(false) }
    LaunchedEffect(version, force, sourceId) {
        if (sourceId != null && LiveTv.sources().none { it.id == sourceId && it.enabled }) sourceId = null
        channels = LiveTv.channels(sourceId, force = force)
        force = false
        runCatching { LiveTv.loadGuides() }
        guideVersion++
    }
    // Now/next move on by themselves.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    val all = channels
    when {
        LiveTv.sources().isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState("Live TV", "Add an M3U playlist, an Xtream Codes login or a Stremio TV add-on in Settings → Live TV.",
                button = "Add a source") { ProfileUi.guarded { if (isWideNow) Nav.select(Tab.MORE) else Nav.open(Screen.SettingsPage(Section.LIVETV.name)) } }
        }
        all == null -> Spinner(Modifier.fillMaxSize())
        // Nothing came back and there's no connection: say so (the channel lists are kept once loaded).
        all.isEmpty() && !App.online() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            OfflineState { force = true; version++ }
        }
        else -> LiveContent(all, now, guideVersion, scope, LiveTv.sources().firstOrNull { it.id == sourceId }?.name ?: "All sources",
            onSources = { pickingSource = true }) { force = true; version++ }
    }
    if (pickingSource) SimpleDialog("Live TV sources", { pickingSource = false }) {
        @Composable
        fun row(title: String, sub: String?, on: Boolean, onClick: () -> Unit) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).card(RoundedCornerShape(12.dp)) { onClick(); pickingSource = false }
                .background(if (on) Palette.accent.copy(alpha = 0.25f) else Palette.surfaceHigh, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted) }
            }
        }
        row("All sources", null, sourceId == null) { sourceId = null }
        LiveTv.sources().filter { it.enabled }.forEach { s -> row(s.name, s.kind.label, s.id == sourceId) { sourceId = s.id } }
        row("Refresh channels and guide", null, false) { force = true; version++ }
        row("Live TV sources…", null, false) { ProfileUi.guarded { if (isWideNow) Nav.select(Tab.MORE) else Nav.open(Screen.SettingsPage(Section.LIVETV.name)) } }
    }
}

private var isWideNow = false

@Composable
private fun LiveContent(all: List<LiveTv.Channel>, now: Long, guideVersion: Int, scope: CoroutineScope, sourceName: String, onSources: () -> Unit, onRefresh: () -> Unit) {
    val wide = isWide
    isWideNow = wide
    var favVersion by remember { mutableIntStateOf(0) }
    val favourites = remember(favVersion) { LiveTv.favourites() }
    val recentKeys = remember(favVersion) { LiveTv.recent() }
    // A channel in several groups ("Animation;Kids") is under each of them.
    val groups = remember(all) { all.flatMap { c -> c.groups.ifEmpty { listOf("Other") } }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    var filter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    // "Check channels": which answer (public lists always have dead links), and hiding the ones that don't.
    var healthVersion by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var checkJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var checkTotal by remember { mutableIntStateOf(0) }
    var hideDead by remember { mutableStateOf(LiveTv.hideNotWorking) }
    val notWorking = remember(all, healthVersion) { all.count { LiveTv.health(it)?.ok == false } }
    // Region-locked channels (iptv-org's "[Geo-blocked]", Free-TV's Ⓖ) play only in their own country: hidden unless
    // they're from the viewer's.
    var hideLocked by remember { mutableStateOf(LiveTv.hideRegionLocked) }
    val home = remember { LiveTv.homeCountry }
    val lockedCount = remember(all) { all.count { LiveTv.lockedOut(it) } }
    // Lists from many countries (iptv-org's whole list): pick one; the viewer's own first.
    val countries = remember(all) {
        all.mapNotNull { it.country?.uppercase() }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.key == home }.thenByDescending { it.value }).map { it.key to it.value }
    }
    // The country picked last stays picked (playing a channel and coming back, or restarting the app); until one is
    // picked, your own country.
    val saved = io.github.sherazi5110.cloudstream.Settings.liveCountry
    var country by remember(all) { mutableStateOf(when {
        saved == "ALL" -> null
        saved != null && countries.any { it.first.equals(saved, true) } -> saved
        countries.size >= 5 && countries.any { it.first == home } -> home
        else -> null
    }) }
    fun pickCountry(code: String?) { country = code; io.github.sherazi5110.cloudstream.Settings.liveCountry = code ?: "ALL" }
    var countryMenu by remember { mutableStateOf(false) }
    // The automatic check: the channels in view that weren't checked in the last day, quietly in the background;
    // the ones with broken links then drop out ("Hide not working" is on by default).
    LaunchedEffect(all, country, hideLocked) {
        if (!LiveTv.autoCheck || checkJob?.isActive == true) return@LaunchedEffect
        kotlinx.coroutines.delay(1500)
        val todo = all.filter { c -> (!hideLocked || !LiveTv.lockedOut(c)) && (country == null || c.country.equals(country, true)) && LiveTv.stale(c) }
        if (todo.isEmpty()) return@LaunchedEffect
        checkTotal = todo.size
        checkJob = scope.launch {
            checking = 0 to 0
            runCatching { LiveTv.check(todo) { done, working -> checking = done to working } }
            checking = null
            healthVersion++
        }
    }
    val shown = remember(all, filter, query, favVersion, hideDead, healthVersion, hideLocked, country) {
        all.filter { c ->
            (!hideDead || LiveTv.health(c)?.ok != false) &&
            (!hideLocked || !LiveTv.lockedOut(c)) &&
            (country == null || c.country.equals(country, ignoreCase = true)) &&
            when (filter) { "All" -> true; "★ Favourites" -> c.key in favourites; else -> filter in c.groups.ifEmpty { listOf("Other") } } &&
                (query.isBlank() || c.name.contains(query.trim(), ignoreCase = true))
        }
    }
    val recent = remember(all, recentKeys) { recentKeys.mapNotNull { k -> all.firstOrNull { it.key == k } } }
    var selected by remember { mutableStateOf<LiveTv.Channel?>(null) }
    val banner = selected ?: recent.firstOrNull() ?: shown.firstOrNull()
    val edge = if (wide) 40.dp else 16.dp
    // A TV or a tablet opens straight into the TV guide (TiviMate-style, with a live preview); phones get the cards.
    val tablet = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
    var guide by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(isTv || tablet) }

    val toolbar: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$sourceName ▾", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier
                    .padding(end = 10.dp).widthIn(max = 220.dp).card(RoundedCornerShape(50)) { onSources() }
                    .background(Palette.surfaceHigh, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 11.dp))
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search channels") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(50),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Palette.surface, focusedContainerColor = Palette.surface,
                        unfocusedBorderColor = Color.Transparent, focusedBorderColor = Palette.accent),
                    modifier = Modifier.weight(1f).widthIn(max = 420.dp))
                Spacer(Modifier.weight(0.01f))
                IconAction(if (guide) Icons.Filled.GridView else Icons.Filled.Schedule, if (guide) "Channels" else "TV guide") { guide = !guide }
                IconAction(Icons.Filled.NetworkCheck, "Check channels") {
                    if (checkJob?.isActive == true) return@IconAction
                    // The channels showing (one country of a big list checks in minutes).
                    val list = shown
                    checkTotal = list.size
                    checkJob = scope.launch {
                        checking = 0 to 0
                        val ok = runCatching { LiveTv.check(list) { done, working -> checking = done to working } }.getOrNull()
                        checking = null
                        healthVersion++
                        if (ok != null) App.toast("$ok of ${list.size} channels work" + if (ok < list.size) ". \"Hide not working\" leaves the others out." else "")
                    }
                }
                IconAction(Icons.Filled.Refresh, "Refresh") { onRefresh() }
                IconAction(Icons.Filled.Settings, "Live TV sources") {
                    ProfileUi.guarded { if (wide) Nav.select(Tab.MORE) else Nav.open(Screen.SettingsPage(Section.LIVETV.name)) }
                }
            }
    }
    val chipRow: @Composable () -> Unit = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            checking?.let { (done, working) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Checking channels… $done of $checkTotal · $working work", style = MaterialTheme.typography.labelLarge, color = Palette.accent)
                    Text("Stop", style = MaterialTheme.typography.labelMedium, modifier = Modifier.card(RoundedCornerShape(50)) { checkJob?.cancel(); checking = null; healthVersion++ }
                        .background(Palette.surfaceHigh, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                if (countries.size >= 2) item {
                    Box {
                        Text("🌍 " + (country?.let { LiveTv.countryName(it) } ?: "All countries") + " ▾", style = MaterialTheme.typography.labelMedium, modifier = Modifier
                            .card(RoundedCornerShape(50)) { countryMenu = true }
                            .background(if (country != null) Palette.accent else Palette.surfaceHigh, RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 8.dp))
                        androidx.compose.material3.DropdownMenu(countryMenu, { countryMenu = false }, Modifier.heightIn(max = 420.dp)) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("All countries (${all.size})") }, onClick = { pickCountry(null); countryMenu = false })
                            countries.forEach { (code, n) ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(LiveTv.countryName(code) + " ($n)" + if (code == home) " · your country" else "") },
                                    onClick = { pickCountry(code); countryMenu = false })
                            }
                        }
                    }
                }
                if (lockedCount > 0) item {
                    Text(if (hideLocked) "✓ Hiding $lockedCount region-locked" else "Show $lockedCount region-locked", style = MaterialTheme.typography.labelMedium, modifier = Modifier
                        .card(RoundedCornerShape(50)) { hideLocked = !hideLocked; LiveTv.hideRegionLocked = hideLocked }
                        .background(if (hideLocked) Palette.accent else Palette.surfaceHigh, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 8.dp))
                }
                if (notWorking > 0 || hideDead) item {
                    Text(if (hideDead) "✓ Hiding $notWorking not working" else "Hide $notWorking not working", style = MaterialTheme.typography.labelMedium, modifier = Modifier
                        .card(RoundedCornerShape(50)) { hideDead = !hideDead; LiveTv.hideNotWorking = hideDead }
                        .background(if (hideDead) Palette.accent else Palette.surfaceHigh, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 8.dp))
                }
                val chips = listOf("All", "★ Favourites") + groups
                items(chips) { g ->
                    val on = g == filter
                    val count = when (g) { "All" -> all.size; "★ Favourites" -> favourites.size; else -> null }
                    Text(listOfNotNull(g, count?.toString()).joinToString(" "), style = MaterialTheme.typography.labelMedium, modifier = Modifier
                        .card(RoundedCornerShape(50)) { filter = g }
                        .background(if (on) Palette.accent else Palette.surfaceHigh, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
    } }

    if (guide) {
        LiveGuide(all, now, guideVersion, scope, healthVersion = healthVersion, onCards = { guide = false }, onRefresh = onRefresh,
            onSources = { ProfileUi.guarded { if (wide) Nav.select(Tab.MORE) else Nav.open(Screen.SettingsPage(Section.LIVETV.name)) } })
        return
    }

    // On now, Starting soon and Tonight: programmes from the guide on the channels showing.
    val listings = remember(shown, now, guideVersion) { LiveListings.of(shown, now) }

    LazyVerticalGrid(
        if (wide) GridCells.Adaptive(220.dp) else GridCells.Adaptive(160.dp),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = edge, end = edge, bottom = 32.dp + LocalBottomInset.current),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            banner?.let { c -> LiveBanner(c, all.indexOf(c) + 1, now, guideVersion, c.key in favourites, wide,
                onWatch = { LivePlayback.play(scope, c, shown.ifEmpty { all }) },
                onFavourite = { LiveTv.toggleFavourite(c); favVersion++ }) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { toolbar() }
        item(span = { GridItemSpan(maxLineSpan) }) { chipRow() }
        if (filter == "All" && query.isBlank()) listings.forEach { (title, items) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    SectionTitle(title, Modifier.padding(top = 8.dp))
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        uniqueItems(items, key = { "$title|${it.first.key}|${it.second.start}" }) { (c, p) ->
                            ProgrammeCard(c, p, now, Modifier.width(if (wide) 260.dp else 200.dp)) { LivePlayback.play(scope, c, shown) }
                        }
                    }
                }
            }
        }
        if (recent.isNotEmpty() && filter == "All" && query.isBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Recently watched", Modifier.padding(top = 8.dp)) }
            uniqueItems(recent.take(if (wide) 5 else 4), key = { "recent|" + it.key }) { c ->
                ChannelCard(c, now, guideVersion + healthVersion, onFocus = { selected = c }) { LivePlayback.play(scope, c, all) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(if (filter == "All") "All channels" else filter, Modifier.padding(top = 8.dp)) }
        }
        uniqueItems(shown, key = { it.key }) { c ->
            ChannelCard(c, now, guideVersion + healthVersion, onFocus = { selected = c }) { LivePlayback.play(scope, c, shown) }
        }
        if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState("No channels", if (query.isNotBlank()) "Nothing matches \"$query\"." else "This list is empty.", Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LiveBanner(c: LiveTv.Channel, number: Int, now: Long, guideVersion: Int, favourite: Boolean, wide: Boolean, onWatch: () -> Unit, onFavourite: () -> Unit) {
    val (cur, next) = remember(c, now, guideVersion) { LiveTv.nowNext(c, now) }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 20.dp, bottom = 8.dp)
        .background(Brush.horizontalGradient(listOf(Palette.accent.copy(alpha = 0.10f), Color.Transparent)), RoundedCornerShape(16.dp)).padding(if (wide) 8.dp else 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveBadge(label = LiveTv.webPage(c)?.let { "LIVE · ${it.uppercase()}" } ?: "LIVE")
            Text(listOfNotNull("Channel $number", c.groupLabel, "Region-locked".takeIf { c.regionLocked }).joinToString(" · "), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp))
        }
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(c, if (wide) 92.dp else 64.dp)
            Text(c.name, style = if (wide) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displayMedium, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 18.dp))
        }
        if (cur != null) {
            val left = ((cur.stop - now) / 60_000).coerceAtLeast(0)
            Text("Now: ${cur.title}   ${time.format(Date(cur.start))} – ${time.format(Date(cur.stop))} · $left min left", style = MaterialTheme.typography.titleSmall)
            ProgressLine(progressOf(cur, now), Modifier.widthIn(max = 460.dp).padding(vertical = 8.dp))
            next?.let { Text("Next: ${it.title} · ${time.format(Date(it.start))}", style = MaterialTheme.typography.bodyMedium) }
        } else Text("No guide for this channel", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Watch live", Icons.Filled.PlayArrow, compact = !wide) { onWatch() }
            PillButton(if (favourite) "Favourite" else "Add to favourites", if (favourite) Icons.Filled.Star else Icons.Filled.StarBorder, primary = false, compact = !wide) { onFavourite() }
        }
    }
}

@Composable
private fun ChannelCard(c: LiveTv.Channel, now: Long, guideVersion: Int, onFocus: () -> Unit, onClick: () -> Unit) {
    val cur = remember(c, now, guideVersion) { LiveTv.nowNext(c, now).first }
    val health = remember(c, guideVersion) { LiveTv.health(c) }
    val dead = health?.ok == false
    Column(Modifier.fillMaxWidth().alpha(if (dead) 0.5f else 1f).card(RoundedCornerShape(12.dp), onFocus = onFocus, selectFirst = true, onClick = onClick)
        .background(Palette.surface, RoundedCornerShape(12.dp))) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 8f).background(Brush.linearGradient(listOf(Palette.surfaceHigh, Palette.accent.copy(alpha = 0.18f)))),
            contentAlignment = Alignment.Center) {
            CardLogo(c, Modifier.fillMaxWidth(0.62f).fillMaxHeight(0.62f))
            LiveBadge(Modifier.align(Alignment.TopStart).padding(8.dp), LiveTv.webPage(c)?.let { "LIVE · ${it.uppercase()}" } ?: "LIVE")
            if (c.regionLocked) RegionBadge(Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        Column(Modifier.padding(10.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (dead) "Not working: ${health?.why ?: "no answer"}" else cur?.let { "Now: ${it.title}" } ?: (c.groupLabel ?: " "),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (dead) Palette.muted else Color.Unspecified)
            if (cur != null) ProgressLine(progressOf(cur, now), Modifier.padding(top = 6.dp))
        }
    }
}

/** A card's logo: as large as the card allows, else the channel's initials. */
@Composable
private fun CardLogo(c: LiveTv.Channel, modifier: Modifier) {
    val logo = rememberLogo(c)
    if (logo != null) LogoImage(logo, c.name, modifier, RoundedCornerShape(10.dp), 8.dp)
    else Text(c.name.filter { it.isLetterOrDigit() }.take(4).uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 26.sp)
}

/** The guide's rows: what's on now, what starts in the next hour, and this evening (19:00 to midnight). */
internal object LiveListings {
    fun of(channels: List<LiveTv.Channel>, now: Long, max: Int = 20): List<Pair<String, List<Pair<LiveTv.Channel, LiveTv.Programme>>>> {
        if (!LiveTv.hasGuide()) return emptyList()
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val evening = (cal.clone() as java.util.Calendar).apply { set(java.util.Calendar.HOUR_OF_DAY, 19); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis
        val midnight = (cal.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis
        val onNow = mutableListOf<Pair<LiveTv.Channel, LiveTv.Programme>>()
        val soon = mutableListOf<Pair<LiveTv.Channel, LiveTv.Programme>>()
        val tonight = mutableListOf<Pair<LiveTv.Channel, LiveTv.Programme>>()
        for (c in channels) {
            if (onNow.size >= max && soon.size >= max && tonight.size >= max) break
            val progs = LiveTv.programmes(c, now, midnight)
            progs.firstOrNull { now in it.start until it.stop }?.let { if (onNow.size < max) onNow += c to it }
            progs.firstOrNull { it.start > now && it.start <= now + 3600_000L }?.let { if (soon.size < max) soon += c to it }
            progs.firstOrNull { it.start >= maxOf(evening, now + 3600_000L) && it.start < midnight }?.let { if (tonight.size < max) tonight += c to it }
        }
        return listOf("On now" to onNow, "Starting soon" to soon.sortedBy { it.second.start }, "Tonight" to tonight.sortedBy { it.second.start })
            .filter { it.second.isNotEmpty() }
    }
}

/** One programme in the On now / Starting soon / Tonight rows: its title, the channel and the time. */
@Composable
private fun ProgrammeCard(c: LiveTv.Channel, p: LiveTv.Programme, now: Long, modifier: Modifier, onClick: () -> Unit) {
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val live = now in p.start until p.stop
    Column(modifier.card(RoundedCornerShape(12.dp), selectFirst = true, onClick = onClick).background(Palette.surface, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChannelLogo(c, 30.dp)
            Text(c.name, style = MaterialTheme.typography.labelMedium, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(if (live) "Now · ${((p.stop - now) / 60_000).coerceAtLeast(1)} min left" else time.format(Date(p.start)) + " – " + time.format(Date(p.stop)),
            style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        if (live) ProgressLine(progressOf(p, now), Modifier.padding(top = 6.dp))
    }
}
