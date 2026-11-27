package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.sherazi5110.cloudstream.LiveTv
import io.github.sherazi5110.cloudstream.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * The guide's filters, like a TiviMate-style guide: country, category, language and source ("✓ Working" by default).
 * Counts follow the other filters; when nothing matches, the narrower ones widen (without changing what's saved).
 */
internal object LiveFilters {
    const val ALL = "all"
    const val FAV = "fav"
    const val WORKING = "working"

    data class Choice(val country: String?, val category: String, val language: String, val source: String)

    fun inCategory(c: LiveTv.Channel, k: String, favs: Set<String>) = when (k) {
        ALL, "" -> true
        FAV -> c.key in favs
        else -> k in c.groups.ifEmpty { listOf("Other") }
    }
    fun inLanguage(c: LiveTv.Channel, k: String, langs: (LiveTv.Channel) -> List<String>) = k == ALL || k == "" || k in langs(c)
    fun inSource(c: LiveTv.Channel, k: String, broken: (LiveTv.Channel) -> Boolean) = when (k) {
        ALL, "" -> true
        WORKING -> !broken(c)
        else -> c.source == k
    }
    fun inCountry(c: LiveTv.Channel, cc: String?) = cc == null || c.country.equals(cc, ignoreCase = true)

    /** The channels for [choice], and the choice actually used (widened when nothing matched). */
    fun apply(all: List<LiveTv.Channel>, choice: Choice, favs: Set<String>, langs: (LiveTv.Channel) -> List<String>,
              broken: (LiveTv.Channel) -> Boolean): Pair<List<LiveTv.Channel>, Choice> {
        var ch = choice
        while (true) {
            val rows = all.filter { inCountry(it, ch.country) && inCategory(it, ch.category, favs) && inLanguage(it, ch.language, langs) && inSource(it, ch.source, broken) }
            if (rows.isNotEmpty() || all.isEmpty() || ch.category == FAV) return rows to ch
            ch = when {
                ch.source != ALL -> ch.copy(source = ALL)
                ch.language != ALL -> ch.copy(language = ALL)
                ch.category != ALL -> ch.copy(category = ALL)
                ch.country != null -> ch.copy(country = null)
                else -> return rows to ch
            }
        }
    }

    /** Working channels first, then the ones with a guide, then by name; numbered in that order. */
    fun order(list: List<LiveTv.Channel>, broken: (LiveTv.Channel) -> Boolean, hasGuide: (LiveTv.Channel) -> Boolean): List<LiveTv.Channel> =
        list.sortedWith(compareBy<LiveTv.Channel>({ broken(it) }, { !hasGuide(it) }, { it.name.lowercase() }))
}

/**
 * Live TV's TV guide, as on TiviMate (and Horizon Home's): the filters at the top, then what's on the selected
 * channel (title, time, progress, description, what's next) beside a live preview of it, and the guide grid under
 * them. OK watches full screen, Menu (or holding OK) opens the channel's menu.
 */
@Composable
internal fun LiveGuide(
    all: List<LiveTv.Channel>, now: Long, guideVersion: Int, scope: CoroutineScope,
    /** Changes when a link check finished (the "✓ Working" filter and the order follow it). */
    healthVersion: Int = 0,
    onCards: () -> Unit, onRefresh: () -> Unit, onSources: () -> Unit,
) {
    val wide = isWide
    var favVersion by remember { mutableStateOf(0) }
    var langVersion by remember { mutableStateOf(0) }
    DisposableEffect(Unit) { val off = LiveTv.onChange { langVersion++ }; onDispose { off() } }
    LaunchedEffect(Unit) { LiveTv.loadLanguages() }
    val favs = remember(favVersion) { LiveTv.favourites() }
    val broken: (LiveTv.Channel) -> Boolean = { LiveTv.health(it)?.ok == false || (LiveTv.hideRegionLocked && LiveTv.lockedOut(it)) }
    val hasGuide: (LiveTv.Channel) -> Boolean = { LiveTv.nowNext(it, now).let { (a, b) -> a != null || b != null } }
    val ordered = remember(all, guideVersion, healthVersion) { LiveFilters.order(all, broken, hasGuide) }
    val numbers = remember(ordered) { ordered.withIndex().associate { (i, c) -> c.key to i + 1 } }

    var choice by remember {
        mutableStateOf(LiveFilters.Choice(Settings.liveCountry?.takeIf { it != "ALL" } ?: LiveTv.homeCountry.takeIf { cc -> all.any { it.country.equals(cc, true) } },
            Settings.liveCategory.ifBlank { LiveFilters.ALL }, Settings.liveLanguage.ifBlank { LiveFilters.ALL }, Settings.liveSourceFilter))
    }
    val (rows, used) = remember(ordered, choice, favs, langVersion) { LiveFilters.apply(ordered, choice, favs, LiveTv::languagesOf, broken) }

    var selected by remember { mutableStateOf<LiveTv.Channel?>(null) }
    var selectedProg by remember { mutableStateOf<LiveTv.Programme?>(null) }
    var menuFor by remember { mutableStateOf<LiveTv.Channel?>(null) }
    // Opens on the channel watched last (Back from the player: the one you zapped to).
    val start = remember(rows) { LiveTv.recent().firstNotNullOfOrNull { k -> rows.firstOrNull { it.key == k } } ?: rows.firstOrNull() }
    val sel = selected?.takeIf { s -> rows.any { it.key == s.key } } ?: start

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(start = if (wide) 28.dp else 12.dp, end = if (wide) 28.dp else 12.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Filters.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val inOthers = { pick: (LiveTv.Channel) -> Boolean -> ordered.filter(pick) }
            FilterChip(
                label = used.country?.let { LiveTv.countryName(it) } ?: "All countries",
                options = listOf<String?>(null) + ordered.mapNotNull { it.country?.uppercase() }.groupingBy { it }.eachCount().entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.key == LiveTv.homeCountry }.thenByDescending { it.value }).map { it.key },
                name = { it?.let { cc -> LiveTv.countryName(cc) } ?: "All countries" },
                count = { cc -> inOthers { c -> LiveFilters.inCountry(c, cc) && LiveFilters.inSource(c, used.source, broken) }.size },
                selected = used.country,
            ) { cc -> choice = choice.copy(country = cc); Settings.liveCountry = cc ?: "ALL" }
            val inCountry = ordered.filter { LiveFilters.inCountry(it, used.country) && LiveFilters.inSource(it, used.source, broken) }
            FilterChip(
                label = categoryName(used.category),
                options = listOf(LiveFilters.FAV, LiveFilters.ALL) + inCountry.flatMap { it.groups.ifEmpty { listOf("Other") } }.groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }.map { it.key },
                name = ::categoryName,
                count = { k -> inCountry.count { LiveFilters.inCategory(it, k, favs) && LiveFilters.inLanguage(it, used.language, LiveTv::languagesOf) } },
                selected = used.category,
            ) { k -> choice = choice.copy(category = k); Settings.liveCategory = k }
            FilterChip(
                label = if (used.language == LiveFilters.ALL) "All languages" else used.language,
                options = listOf(LiveFilters.ALL) + inCountry.flatMap { LiveTv.languagesOf(it) }.groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }.map { it.key },
                name = { if (it == LiveFilters.ALL) "All languages" else it },
                count = { k -> inCountry.count { LiveFilters.inLanguage(it, k, LiveTv::languagesOf) && LiveFilters.inCategory(it, used.category, favs) } },
                selected = used.language,
            ) { k -> choice = choice.copy(language = k); Settings.liveLanguage = k }
            val inPlace = ordered.filter { LiveFilters.inCountry(it, used.country) && LiveFilters.inCategory(it, used.category, favs) && LiveFilters.inLanguage(it, used.language, LiveTv::languagesOf) }
            FilterChip(
                label = sourceName(used.source),
                options = listOf(LiveFilters.WORKING, LiveFilters.ALL) + inPlace.map { it.source }.distinct(),
                name = ::sourceName,
                count = { k -> inPlace.count { LiveFilters.inSource(it, k, broken) } },
                selected = used.source,
            ) { k -> choice = choice.copy(source = k); Settings.liveSourceFilter = k }
            Text("${rows.size} channels · ${rows.count(hasGuide)} with guide", style = MaterialTheme.typography.labelMedium, color = Palette.muted,
                maxLines = 1, modifier = Modifier.weight(1f).padding(start = 6.dp))
            IconAction(Icons.Filled.GridView, "Channel cards") { onCards() }
            IconAction(Icons.Filled.Refresh, "Refresh channels and guide") { onRefresh() }
            IconAction(Icons.Filled.Settings, "Live TV sources") { onSources() }
        }
        // What's on, beside the live preview.
        if (sel != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            InfoPanel(sel, numbers[sel.key] ?: 0, selectedProg?.takeIf { selected?.key == sel.key }, now, sel.key in favs, Modifier.weight(1f))
            if (wide) LivePreview(sel, Modifier.width(if (isTv) 360.dp else 300.dp))
        }
        when {
            rows.isEmpty() -> EmptyState("No channels", if (used.category == LiveFilters.FAV) "No favourites yet: hold a channel down (or press Menu on it) to add it."
                else "Nothing for this choice. Pick another country, category or language at the top.", Modifier.fillMaxWidth())
            else -> GuideGrid(rows, now, guideVersion, wide, onPlay = { LivePlayback.play(scope, it, rows) }, modifier = Modifier.weight(1f),
                numbers = numbers, startKey = start?.key,
                onFocus = { c, p -> selected = c; selectedProg = p },
                onMenu = { menuFor = it })
        }
    }

    menuFor?.let { c ->
        ChannelMenu(c, c.key in favs, onClose = { menuFor = null },
            onFavourite = { LiveTv.toggleFavourite(c); favVersion++ },
            onHide = { LiveTv.hide(c); io.github.sherazi5110.cloudstream.App.toast("${c.name} hidden: Sources ▾ shows hidden channels again") }) {
            item("▶  Watch full screen") { LivePlayback.play(scope, c, rows) }
            item("⇄  Try another link") { LivePlayback.play(scope, c, rows, chooseSource = true) }
            c.groups.firstOrNull()?.let { g -> item("☰  More in $g") { choice = choice.copy(category = g); Settings.liveCategory = g } }
            LiveTv.languagesOf(c).firstOrNull()?.let { l -> item("文  More in $l") { choice = choice.copy(language = l); Settings.liveLanguage = l } }
        }
    }
}

private fun categoryName(k: String) = when (k) { LiveFilters.ALL, "" -> "All categories"; LiveFilters.FAV -> "★ Favourites"; else -> k }
private fun sourceName(k: String) = when (k) { LiveFilters.WORKING -> "✓ Working"; LiveFilters.ALL, "" -> "All sources"; else -> LiveTv.sourceName(k) ?: k }

/** A filter as a chip that opens its list (with how many channels each choice shows). */
@Composable
private fun <T> FilterChip(label: String, options: List<T>, name: (T) -> String, count: (T) -> Int, selected: T, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text("$label  ▾", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier
            .widthIn(max = 220.dp).card(RoundedCornerShape(50)) { open = true }
            .background(Palette.surfaceHigh, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 9.dp))
        DropdownMenu(open, { open = false }, Modifier.heightIn(max = 420.dp)) {
            options.forEach { o ->
                val on = o == selected
                DropdownMenuItem(
                    text = {
                        Row(Modifier.widthIn(min = 220.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text((if (on) "✓  " else "") + name(o), fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                color = if (on) Palette.accent else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(count(o).toString(), style = MaterialTheme.typography.labelSmall, color = Palette.muted, modifier = Modifier.padding(start = 12.dp))
                        }
                    },
                    onClick = { open = false; onPick(o) },
                )
            }
        }
    }
}

/** The selected channel and programme: number, name, title, time, progress, description, what's next. */
@Composable
private fun InfoPanel(c: LiveTv.Channel, number: Int, focused: LiveTv.Programme?, now: Long, favourite: Boolean, modifier: Modifier) {
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val (cur, next) = remember(c, now) { LiveTv.nowNext(c, now) }
    val p = focused ?: cur
    Column(modifier.heightIn(min = 150.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChannelLogo(c, 36.dp)
            Text("$number  ·  ${c.name}" + if (favourite) "  ★" else "", style = MaterialTheme.typography.titleSmall, color = Palette.muted, maxLines = 1)
        }
        if (p == null) {
            Text(c.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (LiveTv.hasGuide()) "No TV guide for this channel" else "No TV guide loaded for your sources",
                style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
        } else {
            Text(p.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val live = now in p.start until p.stop
            val whenText = when {
                live -> "On now · ${((p.stop - now) / 60_000).coerceAtLeast(1)} min left"
                p.start > now -> "Later"
                else -> "Ended"
            }
            Text("$whenText  ·  ${time.format(Date(p.start))} – ${time.format(Date(p.stop))}", style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
            if (live) ProgressLine(((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f), Modifier.widthIn(max = 260.dp).padding(vertical = 6.dp))
            p.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            val after = if (p == cur) next else LiveTv.programmes(c, p.stop, p.stop + 6 * 3600_000L).firstOrNull()
            after?.let { Text("Then  ${time.format(Date(it.start))}  ${it.title}", style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/**
 * The selected channel playing in a small box, after a moment on it (moving through the list doesn't start every
 * channel). The sharpest quality up to 1080p; nothing to tap: OK on the channel watches full screen.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun LivePreview(c: LiveTv.Channel, modifier: Modifier) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    val player = remember {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon().setForceHighestSupportedBitrate(true).setMaxVideoSize(1920, 1088).build()
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { status = "No preview for this channel. Press OK to watch it." }
                override fun onPlaybackStateChanged(state: Int) { if (state == androidx.media3.common.Player.STATE_READY) status = null }
            })
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(c.key) {
        player.stop()
        status = "Tuning ${c.name}…"
        delay(1500)
        // A YouTube or web channel has no stream to preview.
        if (LiveTv.webPage(c) != null) { status = "Press OK to watch ${c.name}"; return@LaunchedEffect }
        runCatching {
            val http = androidx.media3.datasource.DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                .setUserAgent(c.headers["User-Agent"] ?: com.lagradost.cloudstream3.USER_AGENT)
                .setDefaultRequestProperties(c.headers - "User-Agent")
            val item = androidx.media3.common.MediaItem.Builder().setUri(c.url).apply {
                if (".m3u8" in c.url.lowercase() || "m3u" in c.url.lowercase().substringAfterLast('/')) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
            }.build()
            player.setMediaSource(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(http).createMediaSource(item))
            player.prepare()
            player.playWhenReady = true
        }.onFailure { status = "No preview for this channel. Press OK to watch it." }
    }
    Box(modifier.aspectRatio(16f / 9f).background(Color.Black, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        AndroidView({ ctx -> androidx.media3.ui.PlayerView(ctx).apply { useController = false; this.player = player } }, Modifier.fillMaxSize())
        status?.let {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ChannelLogo(c, 48.dp)
                Spacer(Modifier.width(1.dp))
                Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.muted, modifier = Modifier.padding(8.dp), maxLines = 2)
            }
        }
    }
}
