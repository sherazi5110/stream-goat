package com.lagradost.cloudstream3.linux.ui

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.linux.Downloads
import com.lagradost.cloudstream3.linux.Downloads.State
import com.lagradost.cloudstream3.linux.Engine
import com.lagradost.cloudstream3.linux.OfflineApi
import com.lagradost.cloudstream3.linux.Playable
import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.image.ImageView
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import kotlinx.coroutines.launch
import java.io.File

/** Download buttons for title pages and the Downloads tab of the Library. */
object DownloadsUi {
    /** Runs [update] now and whenever downloads change, while [node] is on screen. */
    fun <T : Node> live(node: T, update: (T) -> Unit): T {
        var off: (() -> Unit)? = null
        var pending = false
        update(node)
        node.sceneProperty().addListener { _, _, scene ->
            off?.invoke(); off = null
            if (scene != null) {
                update(node)
                off = Downloads.onChange {
                    if (pending) return@onChange
                    pending = true
                    Platform.runLater { pending = false; update(node) }
                }
            }
        }
        return node
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

    /** "Download" on a movie's page; shows progress and offers pause, resume or delete. */
    fun button(api: MainAPI, r: LoadResponse, p: Playable, label: String = "Download"): Button {
        val b = button(label, Icons.DOWNLOAD, "btn-ghost") {}
        b.setOnAction {
            val item = Downloads.get(api.name, p.data)
            if (item == null) start(api, r, p) else menu(b, item)
        }
        return live(b) { btn ->
            val item = Downloads.get(api.name, p.data)
            btn.text = when (item?.state) {
                null -> label
                State.RUNNING -> "Downloading " + (if (item.progress >= 0) "${(item.progress * 100).toInt()}%" else "…")
                State.QUEUED -> "Download waiting"
                State.PAUSED -> "Download paused"
                State.DONE -> "Downloaded"
                State.FAILED -> "Download failed"
            }
            btn.graphic = icon(if (item?.state == State.DONE) Icons.CHECK else Icons.DOWNLOAD, 18.0)
            btn.styleClass.remove("active"); if (item?.state == State.DONE) btn.styleClass += "active"
        }
    }

    /** A small round download icon for an episode card; the ring fills with progress. */
    fun episodeBadge(api: MainAPI, r: LoadResponse, p: Playable): Node {
        val text = label("", "dl-badge-text")
        val box = javafx.scene.layout.HBox(4.0, icon(Icons.DOWNLOAD, 16.0), text).apply {
            styleClass += "dl-badge"
            alignment = Pos.CENTER
            setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE)
        }
        box.setOnMouseClicked { e ->
            e.consume()
            val item = Downloads.get(api.name, p.data)
            if (item == null) start(api, r, p) else menu(box, item)
        }
        return live(box) { b ->
            val item = Downloads.get(api.name, p.data)
            b.styleClass.removeAll("idle", "busy", "done", "failed")
            b.styleClass += when (item?.state) { null -> "idle"; State.DONE -> "done"; State.FAILED -> "failed"; else -> "busy" }
            b.children[0] = icon(if (item?.state == State.DONE) Icons.CHECK else Icons.DOWNLOAD, 16.0)
            text.text = item?.takeIf { it.state == State.RUNNING || it.state == State.PAUSED }?.progress?.takeIf { it >= 0 }?.let { "${(it * 100).toInt()}%" } ?: ""
            text.isVisible = text.text.isNotEmpty(); text.isManaged = text.isVisible
            Tooltip(b, item?.let { status(it) } ?: "Download for offline viewing")
        }
    }

    private fun Tooltip(node: Node, text: String) {
        javafx.scene.control.Tooltip.uninstall(node, null)
        (node.properties["dl-tip"] as? javafx.scene.control.Tooltip)?.let { javafx.scene.control.Tooltip.uninstall(node, it) }
        val tip = javafx.scene.control.Tooltip(text).apply { showDelay = javafx.util.Duration.millis(300.0) }
        node.properties["dl-tip"] = tip
        javafx.scene.control.Tooltip.install(node, tip)
    }

    /** Download: asks which source and quality first (Settings → Storage), then starts. */
    fun start(api: MainAPI, r: LoadResponse, p: Playable) {
        if (com.lagradost.cloudstream3.linux.Settings.downloadAsk) Nav.window.showOverlay(DownloadChooser(api, r, p) { choice -> begin(api, r, p, choice) })
        else begin(api, r, p, null)
    }

    private fun begin(api: MainAPI, r: LoadResponse, p: Playable, choice: Downloads.Choice?) {
        Downloads.start(api, r, p, choice)
        Toasts.show("Downloading ${if (Engine.playables(r).size > 1) "${r.name} ${shortCode(p)}" else r.name}", "Downloads" to { LibraryPage.wantDownloads = true; Nav.window.show(Tab.LIBRARY) })
    }

    fun menu(anchor: Node, item: Downloads.Item) {
        val rows = mutableListOf<Node>()
        rows += Popover.row(null, status(item).take(60)) {}
        rows += Popover.separator()
        when (item.state) {
            State.DONE -> rows += Popover.row(icon(Icons.PLAY, 18.0), "Play downloaded copy") { play(item) }
            State.RUNNING, State.QUEUED -> rows += Popover.row(icon(Icons.PAUSE, 18.0), "Pause") { Downloads.pause(item.id) }
            State.PAUSED, State.FAILED -> rows += Popover.row(icon(Icons.REFRESH, 18.0), if (item.state == State.FAILED) "Try again" else "Resume") { Downloads.resume(item.id) }
        }
        rows += Popover.row(icon(Icons.FOLDER, 18.0), "Show in folder") { openFolder() }
        rows += Popover.row(icon(Icons.DELETE, 18.0), if (item.state == State.DONE) "Delete download" else "Cancel and delete") { Downloads.delete(item.id) }
        Popover.show(anchor, Popover.panel(*rows.toTypedArray()), alignRight = false)
    }

    fun play(item: Downloads.Item) {
        ui.launch {
            if (item.file == null || !File(item.file).isFile) { Toasts.show("The file is gone: ${item.file ?: ""}"); return@launch }
            val api = OfflineApi(item.apiName)
            val r = api.response(item)
            val p = Engine.playables(r).firstOrNull { it.data == item.data } ?: return@launch
            Playback.open(api, r, p)
        }
    }

    fun openFolder() {
        val dir = Downloads.folder().apply { mkdirs() }
        com.lagradost.cloudstream3.linux.Os.open(dir.absolutePath)
    }

    /** The Library's Downloads tab: one row per movie or episode, grouped by title. */
    fun page(): Node {
        val list = Downloads.all()
        val head = hbox(12.0, label(Downloads.folder().path, "muted", "small"), spacer(),
            button("Open folder", Icons.FOLDER, "btn-ghost", "btn-small") { openFolder() })
        if (list.isEmpty()) return VBox(18.0, head, emptyState(Icons.DOWNLOAD, "No downloads",
            "Use Download on a movie, or the download icon on an episode, to watch it later without a connection."))
        val groups = list.groupBy { it.apiName + "\n" + it.url }.values
        val box = VBox(26.0, head)
        for (g in groups) {
            val first = g.first()
            val poster = ImageView().apply { fitWidth = 72.0; fitHeight = 108.0; isPreserveRatio = false }
            Images.load(poster, first.poster, 72.0, 108.0, null)
            val posterBox = StackPane(poster).apply { styleClass += "poster-frame"; setMinSize(72.0, 108.0); setMaxSize(72.0, 108.0); roundedClip(this, 8.0) }
            val rows = VBox(8.0)
            g.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })).forEach { rows.children += row(it) }
            val done = g.filter { it.state == State.DONE }
            val header = hbox(12.0, label(first.title, "h3"), label(first.apiName, "tag"), spacer(),
                label("${done.size} of ${g.size} downloaded · ${human(done.sumOf { it.total })}", "muted", "small"))
            box.children += hbox(18.0, posterBox, VBox(10.0, header, rows).apply { javafx.scene.layout.HBox.setHgrow(this, javafx.scene.layout.Priority.ALWAYS) }).apply { alignment = Pos.TOP_LEFT }
        }
        return box
    }

    private fun row(item: Downloads.Item): Node {
        val code = listOfNotNull(item.season?.let { "S$it" }, item.episode?.let { "E$it" }).joinToString(" ")
        val title = label(when {
            !item.isEpisode -> "Movie"
            code.isNotEmpty() -> listOfNotNull(code, item.episodeName).joinToString(" · ")
            else -> item.label ?: item.episodeName ?: "Episode"
        }, "bold")
        val stat = label("", "muted", "small")
        val bar = StackPane()
        val actions = hbox(6.0)
        val row = hbox(14.0, VBox(3.0, title, stat).apply { minWidth = 260.0; maxWidth = 420.0 }, bar, spacer(), actions).apply {
            styleClass.addAll("card-row", "hover-row"); pad(10.0, 12.0)
        }
        return live(row) {
            val i = Downloads.all().firstOrNull { it.id == item.id } ?: return@live
            stat.text = status(i)
            stat.styleClass.remove("bad"); if (i.state == State.FAILED) stat.styleClass += "bad"
            bar.children.setAll(if (i.state != State.DONE && i.progress >= 0) progressBar(i.progress, 200.0) else javafx.scene.layout.Region())
            actions.children.setAll(listOfNotNull(
                if (i.state == State.DONE) button("Play", Icons.PLAY, "btn-accent", "btn-small") { play(i) } else null,
                when (i.state) {
                    State.RUNNING, State.QUEUED -> button(null, Icons.PAUSE, "btn-ghost", "btn-small") { Downloads.pause(i.id) }.tooltip("Pause")
                    State.PAUSED, State.FAILED -> button(null, Icons.REFRESH, "btn-ghost", "btn-small") { Downloads.resume(i.id) }.tooltip(if (i.state == State.FAILED) "Try again" else "Resume")
                    State.DONE -> null
                },
                button(null, Icons.DELETE, "btn-ghost", "btn-small") { Downloads.delete(i.id) }.tooltip(if (i.state == State.DONE) "Delete" else "Cancel and delete"),
            ))
        }
    }
}

/**
 * "Download": which source, then which quality inside it (stream sources hold several). "Best (automatic)" is
 * first. Shown over the page; Settings → Storage → "Ask which source and quality" turns it off.
 */
class DownloadChooser(
    private val api: MainAPI,
    private val response: LoadResponse,
    private val playable: Playable,
    private val onPick: (Downloads.Choice?) -> Unit,
) : StackPane(), Disposable {
    private val sources = SourceCollector(api, playable)
    private val list = VBox(8.0)
    private val status = javafx.scene.layout.HBox(10.0)
    private val heading = label("Download", "h2")
    private var picking: com.lagradost.cloudstream3.utils.ExtractorLink? = null

    init {
        val scrim = javafx.scene.layout.Region().apply { styleClass += "scrim"; onClick { close() } }
        val total = Engine.playables(response).size
        val header = hbox(14.0,
            vbox(4.0, heading, label(Playback.title(response, playable, total), "muted").apply { maxWidth = 600.0 }),
            spacer(),
            button(null, Icons.CLOSE, "btn-ghost") { close() }.tooltip("Close"),
        )
        val scroll = pageScroll(list).apply {
            maxHeightProperty().bind(Nav.window.heightProperty().multiply(0.62))
            prefViewportHeightProperty().bind(list.heightProperty())
        }
        val sheet = VBox(16.0, header, status, scroll).apply {
            styleClass += "sheet"
            pad(24.0, 26.0, 24.0, 26.0)
            maxWidth = 780.0
            maxHeight = javafx.scene.layout.Region.USE_PREF_SIZE
            setOnMouseClicked { it.consume() }
        }
        children.addAll(scrim, sheet)
        StackPane.setAlignment(sheet, Pos.CENTER)
        sources.onUpdate { if (picking == null) renderSources() }
        sources.start()
        renderSources()
    }

    private fun usable() = sources.ranked().filter {
        it.source != OfflineApi.SOURCE && (it.type == com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO || it.type == com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8)
    }

    private fun renderSources() {
        heading.text = "Download: choose a source"
        val links = usable()
        status.children.setAll(listOfNotNull(
            if (!sources.finished) javafx.scene.control.ProgressIndicator().apply { setPrefSize(18.0, 18.0) } else null,
            label(when {
                !sources.finished && links.isEmpty() -> "Looking for sources…"
                !sources.finished -> "${links.size} source(s) so far, still searching…"
                links.isEmpty() -> "No source here can be saved (only streams like DASH or torrents)"
                else -> "${links.size} source(s) · downloads use ${com.lagradost.cloudstream3.linux.Settings.downloadConnections} connections at once"
            }, "muted"),
        ))
        val rows = mutableListOf<Node>()
        rows += row("Best (automatic)", "The best source at your preferred quality, like Play does", "AUTO", best = true) { pick(null) }
        links.forEach { link ->
            val q = com.lagradost.cloudstream3.utils.Qualities.getStringByInt(link.quality).ifEmpty { "AUTO" }
            val host = runCatching { java.net.URI(link.url).host }.getOrNull()?.removePrefix("www.") ?: ""
            val stream = link.type == com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8 || link.url.substringBefore('?').endsWith(".m3u8")
            rows += row(link.name, listOf(if (stream) "Stream (choose its quality next)" else "File", host).filter { it.isNotBlank() }.joinToString("  ·  "), q, best = false) {
                if (stream) qualities(link) else pick(Downloads.Choice(link.name, link.source))
            }
        }
        list.children.setAll(rows)
    }

    /** A stream source: its qualities (from its playlist), best first. */
    private fun qualities(link: com.lagradost.cloudstream3.utils.ExtractorLink) {
        picking = link
        heading.text = "Download: choose a quality"
        status.children.setAll(javafx.scene.control.ProgressIndicator().apply { setPrefSize(18.0, 18.0) }, label("Reading ${link.name}'s qualities…", "muted"))
        list.children.clear()
        ui.launch {
            val heights = Downloads.qualities(link)
            if (heights.size <= 1) { pick(Downloads.Choice(link.name, link.source, heights.firstOrNull() ?: 0)); return@launch }
            status.children.setAll(label("${link.name} · ${heights.size} qualities", "muted"))
            list.children.setAll(heights.map { h ->
                row("${h}p", if (h == heights.first()) "Best in this source" else "Smaller file", "${h}p", best = h == heights.first()) {
                    pick(Downloads.Choice(link.name, link.source, h))
                }
            } + row("Back to sources", null, "↩", best = false) { picking = null; renderSources() })
        }
    }

    private fun row(title: String, sub: String?, pill: String, best: Boolean, onClick: () -> Unit): Node {
        val text = vbox(3.0, label(title, "bold"), label(sub ?: "", "faint", "small").apply { isVisible = sub != null; isManaged = sub != null })
        return hbox(14.0, label(pill, "quality-pill"), text, spacer(), icon(Icons.DOWNLOAD, 20.0).apply { styleClass += "accent-icon" }).apply {
            styleClass += "source-row"
            if (best) styleClass += "best"
            onClick { onClick() }
        }
    }

    private fun pick(choice: Downloads.Choice?) {
        close()
        onPick(choice)
    }

    private fun close() {
        sources.cancel()
        Nav.window.closeOverlay()
    }

    override fun dispose() { sources.cancel() }
}

/** "Download these" (a season): one quality for all of them, or the best. */
class DownloadQualityChooser(private val count: Int, private val onPick: (Int) -> Unit) : StackPane() {
    init {
        val scrim = javafx.scene.layout.Region().apply { styleClass += "scrim"; onClick { Nav.window.closeOverlay() } }
        val rows = listOf(0 to "Best (your preferred quality)", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p").map { (h, name) ->
            hbox(14.0, label(if (h == 0) "AUTO" else "${h}p", "quality-pill"), label(name, "bold"), spacer(), icon(Icons.DOWNLOAD, 20.0).apply { styleClass += "accent-icon" }).apply {
                styleClass += "source-row"
                if (h == 0) styleClass += "best"
                onClick { Nav.window.closeOverlay(); onPick(h) }
            }
        }
        val sheet = VBox(16.0,
            hbox(14.0, vbox(4.0, label("Download $count episode(s)", "h2"), label("Highest quality to save (each episode gets the best source up to it)", "muted")), spacer(),
                button(null, Icons.CLOSE, "btn-ghost") { Nav.window.closeOverlay() }),
            VBox(8.0, *rows.toTypedArray()),
        ).apply {
            styleClass += "sheet"
            pad(24.0, 26.0, 24.0, 26.0)
            maxWidth = 620.0
            maxHeight = javafx.scene.layout.Region.USE_PREF_SIZE
            setOnMouseClicked { it.consume() }
        }
        children.addAll(scrim, sheet)
        StackPane.setAlignment(sheet, Pos.CENTER)
    }
}
