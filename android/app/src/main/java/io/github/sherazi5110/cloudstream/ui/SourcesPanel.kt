package io.github.sherazi5110.cloudstream.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.utils.ExtractorLink
import io.github.sherazi5110.cloudstream.player.PlayerController

/**
 * What the sources panel can filter on, read from each source's name: languages (Hindi, English, Tamil…, also
 * written "Hin", "Eng"), "Dual audio" / "Multi audio", and quality (4K, 1080p…).
 */
object SourceFilters {
    private val languages = linkedMapOf(
        "Hindi" to listOf("hindi", "hin"), "English" to listOf("english", "eng"), "Tamil" to listOf("tamil", "tam"),
        "Telugu" to listOf("telugu", "tel"), "Malayalam" to listOf("malayalam", "mal"), "Kannada" to listOf("kannada", "kan"),
        "Bengali" to listOf("bengali", "bangla", "ben"), "Urdu" to listOf("urdu"), "Punjabi" to listOf("punjabi"),
        "Marathi" to listOf("marathi"), "Gujarati" to listOf("gujarati"), "Japanese" to listOf("japanese", "jpn", "jap"),
        "Korean" to listOf("korean", "kor"), "Chinese" to listOf("chinese", "mandarin", "cantonese"), "Spanish" to listOf("spanish", "español", "latino", "spa"),
        "French" to listOf("french", "français", "fre", "fra"), "German" to listOf("german", "deutsch", "ger", "deu"),
        "Italian" to listOf("italian", "ita"), "Portuguese" to listOf("portuguese", "português", "por"), "Arabic" to listOf("arabic", "ara"),
        "Turkish" to listOf("turkish", "tur"), "Russian" to listOf("russian", "rus"), "Indonesian" to listOf("indonesian"),
        "Thai" to listOf("thai"), "Vietnamese" to listOf("vietnamese"), "Polish" to listOf("polish", "polski"),
    )
    private val wordsOf = Regex("[\\p{L}\\p{N}]+")
    private val qualityInName = Regex("(?i)\\b(4k|2160p|1440p|1080p|720p|576p|480p|360p)\\b")

    /** The filter tags of a source: its languages, audio kind and quality. */
    fun tags(name: String, quality: Int): List<String> {
        val words = wordsOf.findAll(name.lowercase()).map { it.value }.toSet()
        val out = mutableListOf<String>()
        languages.forEach { (lang, keys) -> if (keys.any { it in words }) out += lang }
        if ("dual" in words) out += "Dual audio"
        if ("multi" in words) out += "Multi audio"
        quality(name, quality)?.let { out += it }
        return out
    }

    fun quality(name: String, quality: Int): String? = when {
        quality >= 2160 -> "4K"
        quality > 0 -> "${quality}p"
        else -> qualityInName.find(name)?.value?.lowercase()?.let { if (it == "4k" || it == "2160p") "4K" else it }
    }

    /** Every word of [query] appears somewhere in [text] (case ignored): "hindi 1080" finds "… Hindi … 1080p …". */
    fun matches(text: String, query: String): Boolean {
        val t = text.lowercase()
        return query.lowercase().split(' ').filter { it.isNotBlank() }.all { it in t }
    }

    /** Chips to offer: languages first (most sources first), then qualities best first. */
    fun chips(all: List<List<String>>): List<String> {
        val counts = all.flatten().groupingBy { it }.eachCount()
        val order = listOf("4K", "1440p", "1080p", "720p", "576p", "480p", "360p")
        val (q, other) = counts.keys.partition { it in order }
        return other.sortedByDescending { counts[it] } + q.sortedBy { order.indexOf(it) }
    }
}

/**
 * Sources, from the bottom of the screen: every source's whole name (nothing cut off), a search box ("hindi",
 * "1080", a server's name) and chips for the languages and qualities found in the names.
 */
@Composable
fun SourcesPanel(c: PlayerController, onClose: () -> Unit) =
    SourcesPanel(c.ranked(), c.current?.url, c.searching, onPick = { c.play(it) }, onClose = onClose)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SourcesPanel(all: List<ExtractorLink>, currentUrl: String?, searching: Boolean, onPick: (ExtractorLink) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    data class Row(val link: ExtractorLink, val tags: List<String>, val text: String)
    val rows = remember(all.size, all.firstOrNull()?.url) {
        all.map { l ->
            // A source that already played here shows the quality the player measured, not the extension's label.
            val q = io.github.sherazi5110.cloudstream.MeasuredQuality.get(l.url) ?: l.quality
            Row(l, SourceFilters.tags(l.name + " " + l.source, q), listOf(l.name, l.source, SourceFilters.quality(l.name, q).orEmpty()).joinToString(" "))
        }
    }
    val chips = remember(rows) { SourceFilters.chips(rows.map { it.tags }) }
    val shown = rows.filter { r -> picked.all { it in r.tags } && SourceFilters.matches(r.text, query) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).pointerInput(Unit) { detectTapGestures { onClose() } })
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f).glassPanel(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), blur = false)
            .pointerInput(Unit) { detectTapGestures { } }.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(44.dp).padding(bottom = 10.dp).background(Palette.muted.copy(alpha = 0.5f), RoundedCornerShape(3.dp)).padding(vertical = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sources", style = MaterialTheme.typography.headlineSmall)
                Text(if (shown.size == rows.size) "  ${rows.size}" else "  ${shown.size} of ${rows.size}", style = MaterialTheme.typography.titleMedium, color = Palette.muted)
                if (searching) Text("   still looking…", style = MaterialTheme.typography.bodySmall, color = Palette.accent)
                Spacer(Modifier.weight(1f))
                IconAction(Icons.Filled.Close, "Close") { onClose() }
            }
            val chipsFocus = remember { FocusRequester() }
            val searchFocus = remember { FocusRequester() }
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search: hindi, english, 1080p, a server's name…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(50),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Palette.surfaceHigh, focusedContainerColor = Palette.surfaceHigh,
                    unfocusedBorderColor = Color.Transparent, focusedBorderColor = Palette.accent),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(searchFocus)
                    .focusProperties { down = if (chips.isNotEmpty()) chipsFocus else first })
            if (chips.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 10.dp),
                modifier = Modifier.focusRequester(chipsFocus).focusProperties { up = searchFocus; down = first }.focusGroup()) {
                items(chips) { tag ->
                    val on = tag in picked
                    Text(tag, style = MaterialTheme.typography.labelLarge, modifier = Modifier
                        .card(RoundedCornerShape(50)) { picked = if (on) picked - tag else picked + tag }
                        .background(if (on) Palette.accent else Palette.surfaceHigh, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            if (shown.isEmpty()) Text(
                when { rows.isEmpty() && searching -> "Looking for sources…"; rows.isEmpty() -> "No sources found"; else -> "No source matches. Clear the search or a chip." },
                style = MaterialTheme.typography.bodyLarge, color = Palette.muted, modifier = Modifier.padding(vertical = 20.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown.size, key = { i -> "${i}|${shown[i].link.url}" }) { i ->
                    val r = shown[i]
                    val playing = r.link.url == currentUrl
                    Row(Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first).focusProperties { up = if (chips.isNotEmpty()) chipsFocus else searchFocus } else Modifier)
                        .card(RoundedCornerShape(12.dp), focusScale = 1.01f) { onPick(r.link); onClose() }
                        .background(if (playing) Palette.accent.copy(alpha = 0.18f) else Palette.surfaceHigh, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            // The whole name, on as many lines as it takes.
                            Text(r.link.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            val extra = listOfNotNull(r.link.source.takeIf { it != r.link.name }, if (r.link.isM3u8) "HLS" else null)
                            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                (r.tags + extra).forEach { t ->
                                    Text(t, style = MaterialTheme.typography.labelSmall, color = if (t in picked) Color.White else Palette.muted, modifier = Modifier
                                        .background(if (t in picked) Palette.accent else Palette.surface, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
                                }
                            }
                        }
                        if (playing) Icon(Icons.Filled.Check, "Playing", tint = Palette.accent, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
        }
    }
}
