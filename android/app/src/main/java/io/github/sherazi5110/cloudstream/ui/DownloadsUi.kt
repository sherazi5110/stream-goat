package io.github.sherazi5110.cloudstream.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import io.github.sherazi5110.cloudstream.App
import io.github.sherazi5110.cloudstream.Downloads
import io.github.sherazi5110.cloudstream.Downloads.State
import io.github.sherazi5110.cloudstream.Engine
import io.github.sherazi5110.cloudstream.OfflineApi
import io.github.sherazi5110.cloudstream.Playable
import kotlinx.coroutines.launch
import java.io.File

/** Download buttons for title pages and the Downloads tab of the Library. */
object DownloadsUi {
    /** Bumps (at most a few times a second) whenever a download changes. */
    @Composable
    fun version(): Int {
        var v by remember { mutableIntStateOf(0) }
        DisposableEffect(Unit) {
            val main = Handler(Looper.getMainLooper())
            var pending = false
            val off = Downloads.onChange {
                if (pending) return@onChange
                pending = true
                main.postDelayed({ pending = false; v++ }, 400)
            }
            onDispose { off() }
        }
        return v
    }

    fun status(i: Downloads.Item): String = when (i.state) {
        State.QUEUED -> "Waiting"
        State.RUNNING -> (if (i.progress >= 0) "${(i.progress * 100).toInt()}%" + (if (i.bytes > 0) " · ${human(i.bytes)}" else "") else "Starting…") +
            (if (i.speed > 0) " · ${human(i.speed)}/s" else "") + (i.secondsLeft.takeIf { it >= 0 }?.let { " · ${eta(it)} left" } ?: "")
        State.PAUSED -> "Paused" + (if (i.progress > 0) " at ${(i.progress * 100).toInt()}%" else "")
        State.DONE -> "Downloaded" + (if (i.total > 0) " · ${human(i.total)}" else "")
        State.FAILED -> "Failed: ${i.error ?: "unknown error"}"
    }

    fun eta(seconds: Long): String = when {
        seconds >= 3600 -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
        seconds >= 60 -> "${seconds / 60} min"
        else -> "$seconds s"
    }

    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} KB"
    }

    /** What the download chooser is asked about (shown by [ChooserDialog] in AppRoot). */
    class Request(val api: MainAPI, val r: LoadResponse, val p: Playable)
    var request by mutableStateOf<Request?>(null)

    /** Download: asks which source and quality first (Settings → Downloads), then starts. */
    fun start(context: android.content.Context, api: MainAPI, r: LoadResponse, p: Playable) {
        // The progress notification needs permission on Android 13+ (downloads work either way).
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            context.findActivity()?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7) }
        }
        if (io.github.sherazi5110.cloudstream.Settings.downloadAsk) request = Request(api, r, p) else begin(api, r, p, null)
    }

    fun begin(api: MainAPI, r: LoadResponse, p: Playable, choice: Downloads.Choice?) {
        Downloads.start(api, r, p, choice)
        App.toast("Downloading ${if (Engine.playables(r).size > 1) "${r.name} ${p.label}" else r.name}")
    }

    fun play(item: Downloads.Item) {
        App.scope.launch {
            if (item.file == null || !File(item.file).isFile) { App.toast("The file is gone"); return@launch }
            val api = OfflineApi(item.apiName)
            val r = api.response(item)
            val p = Engine.playables(r).firstOrNull { it.data == item.data } ?: return@launch
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { Playback.play(api, r, p) }
        }
    }

    fun icon(item: Downloads.Item?) = when (item?.state) {
        null -> Icons.Filled.Download
        State.DONE -> Icons.Filled.DownloadDone
        State.FAILED -> Icons.Filled.ErrorOutline
        State.PAUSED -> Icons.Filled.Pause
        else -> Icons.Filled.Download
    }

    fun label(item: Downloads.Item?) = when (item?.state) {
        null -> "Download"
        State.RUNNING -> "Downloading " + (if (item.progress >= 0) "${(item.progress * 100).toInt()}%" else "…")
        State.QUEUED -> "Waiting"
        State.PAUSED -> "Paused"
        State.DONE -> "Downloaded"
        State.FAILED -> "Download failed"
    }

    /** Pause, resume, play the copy or delete it. */
    @Composable
    fun Menu(open: Boolean, item: Downloads.Item?, onDismiss: () -> Unit) {
        if (item == null) return
        DropdownMenu(open, onDismissRequest = onDismiss) {
            DropdownMenuItem(text = { Text(status(item), maxLines = 2, color = Palette.muted) }, onClick = onDismiss, enabled = false)
            when (item.state) {
                State.DONE -> DropdownMenuItem(text = { Text("Play downloaded copy") }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) }, onClick = { onDismiss(); play(item) })
                State.RUNNING, State.QUEUED -> DropdownMenuItem(text = { Text("Pause") }, leadingIcon = { Icon(Icons.Filled.Pause, null) }, onClick = { onDismiss(); Downloads.pause(item.id) })
                State.PAUSED, State.FAILED -> DropdownMenuItem(text = { Text(if (item.state == State.FAILED) "Try again" else "Resume") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                    onClick = { onDismiss(); Downloads.resume(item.id) })
            }
            DropdownMenuItem(text = { Text(if (item.state == State.DONE) "Delete download" else "Cancel and delete") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { onDismiss(); Downloads.delete(item.id) })
        }
    }

    /** A small round status for an episode: a check when downloaded, the percentage while downloading. */
    @Composable
    fun Badge(item: Downloads.Item?, modifier: Modifier = Modifier) {
        if (item == null) return
        val bg = when (item.state) { State.DONE -> Palette.watched; State.FAILED -> Color(0xFFE5484D); else -> Palette.accent }
        Row(modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon(item), null, tint = Color.White, modifier = Modifier.size(14.dp))
            if (item.state == State.RUNNING && item.progress >= 0)
                Text("${(item.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 3.dp))
        }
    }
}

/** The Library's Downloads tab: one line per movie or episode, grouped by title. */
@Composable
fun DownloadsTab() {
    val v = DownloadsUi.version()
    val list = remember(v) { Downloads.all() }
    if (list.isEmpty()) {
        EmptyState("No downloads", "Use Download on a movie, or hold an episode and choose Download, to watch it without a connection.", Modifier.fillMaxWidth())
        return
    }
    val groups = remember(list) { list.groupBy { it.apiName + "\n" + it.url }.values.toList() }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("Saved in ${Downloads.folder().path.substringAfter("/Android/").let { "Android/$it" }}. Uninstalling the app removes them.",
                style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
        items(groups, key = { it.first().apiName + "|" + it.first().url }) { g ->
            val first = g.first()
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.width(64.dp).height(96.dp).background(Palette.surfaceHigh, RoundedCornerShape(8.dp))) {
                    NetImage(first.poster, Modifier.width(64.dp).height(96.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(first.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val done = g.filter { it.state == State.DONE }
                    Text("${done.size} of ${g.size} downloaded · ${DownloadsUi.human(done.sumOf { it.total })} · ${first.apiName}",
                        style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                    g.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })).forEach { DownloadLine(it) }
                }
            }
        }
    }
}

@Composable
private fun DownloadLine(item: Downloads.Item) {
    var menu by remember { mutableStateOf(false) }
    val code = listOfNotNull(item.season?.let { "S$it" }, item.episode?.let { "E$it" }).joinToString(" ")
    val title = when {
        !item.isEpisode -> "Movie"
        code.isNotEmpty() -> listOfNotNull(code, item.episodeName).joinToString(" · ")
        else -> item.label ?: item.episodeName ?: "Episode"
    }
    Box {
        Row(Modifier.fillMaxWidth().card(RoundedCornerShape(10.dp), focusScale = 1.02f, onLongClick = { menu = true }) {
            if (item.state == State.DONE) DownloadsUi.play(item) else menu = true
        }.background(Palette.surface, RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(if (item.state == State.DONE) Palette.accent else Palette.surfaceHigh, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (item.state == State.DONE) Icons.Filled.PlayArrow else DownloadsUi.icon(item), null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(DownloadsUi.status(item), style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    color = if (item.state == State.FAILED) Color(0xFFFF6B6B) else Palette.muted)
                if (item.state != State.DONE && item.progress >= 0) ProgressLine(item.progress.toFloat(), Modifier.padding(top = 6.dp))
            }
            Icon(Icons.Filled.Delete, "Delete", tint = Palette.muted, modifier = Modifier.size(36.dp).card(CircleShape) { Downloads.delete(item.id) }.padding(8.dp))
        }
        DownloadsUi.Menu(menu, item) { menu = false }
    }
}

/**
 * "Download": which source, then which quality inside it (stream sources hold several). "Best (automatic)" first.
 * Works with touch and the TV remote.
 */
@Composable
fun DownloadChooserDialog() {
    val req = DownloadsUi.request ?: return
    val links = androidx.compose.runtime.remember(req) { androidx.compose.runtime.mutableStateListOf<com.lagradost.cloudstream3.utils.ExtractorLink>() }
    var finished by remember(req) { mutableStateOf(false) }
    var stream by remember(req) { mutableStateOf<com.lagradost.cloudstream3.utils.ExtractorLink?>(null) }
    var heights by remember(req) { mutableStateOf<List<Int>?>(null) }
    androidx.compose.runtime.LaunchedEffect(req) {
        Engine.links(req.api, req.p.data).collect { e ->
            if (e is io.github.sherazi5110.cloudstream.LinkEvent.Link) {
                val l = e.link
                val ok = l.source != OfflineApi.SOURCE && (l.type == com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO || l.type == com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8)
                if (ok && links.none { it.url == l.url }) links.add(l)
            }
            if (e is io.github.sherazi5110.cloudstream.LinkEvent.Done) finished = true
        }
        finished = true
    }
    androidx.compose.runtime.LaunchedEffect(stream) {
        val s = stream ?: return@LaunchedEffect
        heights = null
        val h = Downloads.qualities(s)
        if (h.size <= 1) { DownloadsUi.request = null; DownloadsUi.begin(req.api, req.r, req.p, Downloads.Choice(s.name, s.source, h.firstOrNull() ?: 0)) }
        else heights = h
    }
    fun pick(choice: Downloads.Choice?) { DownloadsUi.request = null; DownloadsUi.begin(req.api, req.r, req.p, choice) }
    androidx.compose.ui.window.Dialog(onDismissRequest = { DownloadsUi.request = null }) {
        Column(Modifier.fillMaxWidth().background(Palette.surface, RoundedCornerShape(16.dp)).padding(18.dp)) {
            Text(if (stream == null) "Download: choose a source" else "Download: choose a quality", style = MaterialTheme.typography.titleLarge)
            Text(if (Engine.playables(req.r).size > 1) "${req.r.name} · ${req.p.label}" else req.r.name, style = MaterialTheme.typography.bodySmall,
                color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sorted = Engine.rank(links.toList())
            Text(when {
                stream != null && heights == null -> "Reading ${stream!!.name}'s qualities…"
                stream != null -> "${stream!!.name} · ${heights!!.size} qualities"
                !finished && sorted.isEmpty() -> "Looking for sources…"
                !finished -> "${sorted.size} source(s) so far, still searching…"
                sorted.isEmpty() -> "No source here can be saved"
                else -> "${sorted.size} source(s) · ${io.github.sherazi5110.cloudstream.Settings.downloadConnections} connections at once"
            }, style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
            LazyColumn(Modifier.height(360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val s = stream
                if (s == null) {
                    item { ChooserRow("AUTO", "Best (automatic)", "The best source at your preferred quality", true) { pick(null) } }
                    items(sorted, key = { it.url }) { l ->
                        val isStream = l.type == com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8 || l.url.substringBefore('?').endsWith(".m3u8")
                        ChooserRow(com.lagradost.cloudstream3.utils.Qualities.getStringByInt(l.quality).ifEmpty { "AUTO" }, l.name,
                            if (isStream) "Stream · choose its quality next" else "File", false) {
                            if (isStream) stream = l else pick(Downloads.Choice(l.name, l.source))
                        }
                    }
                } else heights?.let { hs ->
                    items(hs) { h -> ChooserRow("${h}p", "${h}p", if (h == hs.first()) "Best in this source" else "Smaller file", h == hs.first()) { pick(Downloads.Choice(s.name, s.source, h)) } }
                    item { ChooserRow("↩", "Back to sources", null, false) { stream = null; heights = null } }
                }
            }
        }
    }
}

@Composable
private fun ChooserRow(pill: String, title: String, sub: String?, best: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().card(RoundedCornerShape(10.dp), onClick = onClick)
        .background(if (best) Palette.accent.copy(alpha = 0.16f) else Palette.surfaceHigh, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(pill, style = MaterialTheme.typography.labelMedium, color = Color.White,
            modifier = Modifier.background(Palette.accent, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(Icons.Filled.Download, null, tint = Palette.accent)
    }
}
