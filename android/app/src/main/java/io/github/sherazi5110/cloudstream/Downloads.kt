package io.github.sherazi5110.cloudstream

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/**
 * Downloads for watching offline. A download is one movie or episode: the extension is asked for
 * its sources, the best one that can be saved (a direct file or HLS; not DASH or torrents) is
 * written to the downloads folder, with subtitles beside it. HLS segments are joined into one
 * file (AES-128 decrypted). Downloads resume after a pause, a restart or a dropped connection.
 *
 * Downloaded episodes play through [OfflineApi], which keeps the extension's name and episode
 * data, so progress, Continue Watching and "watched" are shared with streaming.
 */
object Downloads {
    enum class State { QUEUED, RUNNING, PAUSED, DONE, FAILED }

    data class Item(
        val id: String, val profile: String,
        val apiName: String, val url: String, val title: String, val poster: String?, val type: String,
        val data: String, val label: String?, val season: Int?, val episode: Int?, val episodeName: String?,
        val file: String? = null, val state: State = State.QUEUED, val bytes: Long = 0, val total: Long = 0,
        val segmentsDone: Int = 0, val segments: Int = 0, val error: String? = null,
        val created: Long = System.currentTimeMillis(),
        val source: String? = null, val headers: Map<String, String> = emptyMap(), val hls: Boolean = false,
        val subtitles: List<String> = emptyList(), val retried: Boolean = false,
        /** What the viewer picked in the download chooser (null: the best source automatically). */
        val prefName: String? = null, val prefSource: String? = null,
        /** The highest quality to download (0: Settings → Player → Preferred quality). */
        val maxHeight: Int = 0,
        /** Multi-connection downloads: how many 4 MB pieces, and which are saved ("0110…"). */
        val chunks: Int = 0, val chunksDone: String = "",
        /** Bytes per second right now (not saved). */
        val speed: Long = 0,
    ) {
        /** 0..1, or -1 when the size isn't known yet. */
        val progress: Double get() = when {
            state == State.DONE -> 1.0
            segments > 0 -> segmentsDone.toDouble() / segments
            total > 0 -> bytes.toDouble() / total
            else -> -1.0
        }
        val isEpisode get() = label != null

        /** Seconds left at the current speed, or -1 when it can't be told. */
        val secondsLeft: Long get() {
            if (state != State.RUNNING || speed <= 0) return -1
            val remaining = when {
                total > 0 -> total - bytes
                segments > 0 && segmentsDone > 0 -> (bytes.toDouble() / segmentsDone * (segments - segmentsDone)).toLong()
                else -> return -1
            }
            return (remaining / speed).coerceAtLeast(0)
        }
    }

    /** The viewer's choice in the download chooser: a source (by name and server) and the highest quality. */
    data class Choice(val name: String? = null, val source: String? = null, val maxHeight: Int = 0)

    /** Multi-connection downloads: piece size, and the smallest file worth splitting. */
    private val CHUNK get() = System.getProperty("cs.download.chunk")?.toLongOrNull() ?: (4L * 1024 * 1024)
    private val MIN_SPLIT get() = CHUNK * 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val items = ConcurrentHashMap<String, Item>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val stateFile get() = File(App.context.filesDir, "downloads.json")
    @Volatile private var loaded = false
    @Volatile private var lastSave = 0L

    // ---------- where files go ----------

    /**
     * The app's own folder on shared storage (Android/data/<app>/files/Movies/Stream Goat): no storage
     * permission needed. Uninstalling the app removes it.
     */
    fun folder(): File =
        App.context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)?.let { File(it, "Stream Goat") }
            ?: File(App.context.filesDir, "downloads")

    // ---------- the list ----------

    fun onChange(listener: () -> Unit): () -> Unit { listeners += listener; return { listeners -= listener } }
    private fun changed() = listeners.forEach { runCatching { it() } }

    /** This profile's downloads, newest first. */
    fun all(): List<Item> { ensureLoaded(); return items.values.filter { it.profile == Profiles.current.id }.sortedByDescending { it.created } }

    /** Running or waiting downloads of every profile (they all keep going after a profile switch). */
    fun active(): List<Item> { ensureLoaded(); return items.values.filter { it.state == State.RUNNING || it.state == State.QUEUED } }

    fun get(apiName: String, data: String): Item? { ensureLoaded(); return items[id(apiName, data)]?.takeIf { it.profile == Profiles.current.id } }

    /** The finished file for an episode, if it is downloaded and still on disk. */
    fun localFile(apiName: String, data: String): File? = get(apiName, data)?.takeIf { it.state == State.DONE }?.file?.let(::File)?.takeIf { it.isFile }

    fun id(apiName: String, data: String): String =
        MessageDigest.getInstance("SHA-1").digest("$apiName\n$data".toByteArray()).take(10).joinToString("") { "%02x".format(it) }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val arr = JSONArray(stateFile.readText())
                for (i in 0 until arr.length()) fromJson(arr.getJSONObject(i)).let { items[it.id] = it }
            }
            loaded = true
        }
    }

    @Synchronized
    private fun save(force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 2000) return
        lastSave = now
        val arr = JSONArray(); items.values.sortedBy { it.created }.forEach { arr.put(toJson(it)) }
        stateFile.parentFile?.mkdirs()
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(stateFile)
    }

    private fun update(id: String, persist: Boolean = true, f: (Item) -> Item): Item? {
        val next = items.computeIfPresent(id) { _, old -> f(old) } ?: return null
        save(force = persist)
        changed()
        return next
    }

    // ---------- actions ----------

    /** Restarts what was running or waiting when the app closed. */
    fun init() {
        ensureLoaded()
        items.values.filter { it.state == State.RUNNING }.forEach { items[it.id] = it.copy(state = State.QUEUED) }
        schedule()
    }

    fun start(api: MainAPI, response: LoadResponse, playable: Playable, choice: Choice? = null): Item {
        ensureLoaded()
        val id = id(api.name, playable.data)
        items[id]?.let { existing ->
            if (existing.state == State.FAILED || existing.state == State.PAUSED) resume(id)
            return items[id]!!
        }
        val item = Item(
            id, Profiles.current.id, api.name, response.url, response.name, response.posterUrl, response.type.name,
            playable.data, playable.label.takeIf { Engine.playables(response).size > 1 }, playable.season, playable.episode,
            playable.name, prefName = choice?.name, prefSource = choice?.source, maxHeight = choice?.maxHeight ?: 0,
        )
        items[id] = item
        save(); changed()
        schedule()
        return item
    }

    fun pause(id: String) {
        jobs.remove(id)?.cancel()
        update(id) { if (it.state == State.DONE) it else it.copy(state = State.PAUSED, speed = 0) }
        schedule()
    }

    fun resume(id: String) {
        update(id) { if (it.state == State.DONE) it else it.copy(state = State.QUEUED, error = null, retried = false) }
        schedule()
    }

    /** Stops it and deletes the file and its subtitles. */
    fun delete(id: String) {
        jobs.remove(id)?.cancel()
        val item = items.remove(id) ?: return
        item.file?.let { File(it).delete(); File("$it.part").delete(); File("$it.parts").deleteRecursively() }
        item.subtitles.forEach { File(it).delete() }
        save(); changed()
        schedule()
    }

    @Synchronized
    private fun schedule() {
        val running = items.values.count { it.state == State.RUNNING && jobs[it.id]?.isActive == true }
        val waiting = items.values.filter { it.state == State.QUEUED }.sortedBy { it.created }.take((Settings.parallelDownloads - running).coerceAtLeast(0))
        for (item in waiting) {
            items[item.id] = item.copy(state = State.RUNNING)
            jobs[item.id] = scope.launch {
                try {
                    run(item.id)
                    update(item.id) { it.copy(state = State.DONE, error = null, speed = 0) }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    android.util.Log.w("downloads", "${item.title} ${item.label.orEmpty()}: $t")
                    val current = items[item.id]
                    if (current != null && !current.retried && current.source != null) {
                        // Links often expire: ask the extension again, once, and start over.
                        current.file?.let { File(it).delete(); File("$it.part").delete(); File("$it.parts").deleteRecursively() }
                        update(item.id) { it.copy(state = State.QUEUED, source = null, bytes = 0, total = 0, segmentsDone = 0, segments = 0, chunks = 0, chunksDone = "", speed = 0, retried = true) }
                    } else update(item.id) { it.copy(state = State.FAILED, error = t.message ?: t.toString(), speed = 0) }
                } finally {
                    jobs.remove(item.id)
                    schedule()
                }
            }
        }
        if (waiting.isNotEmpty()) { save(); changed() }
    }

    // ---------- the work ----------

    private suspend fun run(id: String) {
        var item = items[id] ?: return
        if (item.source == null) item = resolve(item)
        val dir = folder().apply { mkdirs() }
        if (!dir.isDirectory) throw IOException("Can't write to ${dir.path}")
        if (item.hls) hls(item) else direct(item)
    }

    /** Asks the extension for sources and picks the best one that can be saved. */
    private suspend fun resolve(item: Item): Item {
        val api = Engine.allProviders().firstOrNull { it.name == item.apiName }
            ?: throw IOException("The extension ${item.apiName} isn't installed or turned on")
        val links = mutableListOf<ExtractorLink>()
        val subs = mutableListOf<SubtitleFile>()
        Engine.links(api, item.data).collect { e ->
            when (e) {
                is LinkEvent.Link -> if (e.link.source != OfflineApi.SOURCE) links += e.link
                is LinkEvent.Subtitle -> subs += e.subtitle
                is LinkEvent.Done -> Unit
            }
        }
        val usable = Engine.rank(links.distinctBy { it.url }).filter { it.type == ExtractorLinkType.VIDEO || it.type == ExtractorLinkType.M3U8 }
        val chosen = item.prefName?.let { n -> usable.firstOrNull { it.name == n && it.source == item.prefSource } ?: usable.firstOrNull { it.name == n } }
        // The chosen quality for a single-file source: the best one at or under it.
        val capped = if (item.maxHeight > 0) usable.filter { it.quality in 1..item.maxHeight }.ifEmpty { null } else null
        val link = chosen ?: capped?.firstOrNull() ?: usable.firstOrNull() ?: throw IOException(if (links.isEmpty()) "No sources found" else "No source can be downloaded (only streams like DASH or torrents)")
        val hls = link.type == ExtractorLinkType.M3U8 || link.url.substringBefore('?').endsWith(".m3u8")
        val base = baseName(item)
        val ext = if (hls) "ts" else link.url.substringBefore('?').substringAfterLast('.', "mp4").lowercase().takeIf { it in setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts") } ?: "mp4"
        val headers = link.getAllHeaders().toMutableMap().apply { if (keys.none { it.equals("user-agent", true) }) put("User-Agent", USER_AGENT) }
        val subtitleFiles = saveSubtitles(subs, base, headers)
        return update(item.id) {
            it.copy(source = link.url, headers = headers, hls = hls, file = File(folder(), "$base.$ext").path, subtitles = subtitleFiles)
        } ?: throw CancellationException()
    }

    private fun baseName(item: Item): String {
        val clean = { s: String -> s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim() }
        val code = listOfNotNull(item.season?.let { "S%02d".format(it) }, item.episode?.let { "E%02d".format(it) }).joinToString("")
        val name = listOfNotNull(clean(item.title).take(80), code.ifBlank { item.label?.let(clean) }?.takeIf { item.isEpisode }).joinToString(" ")
        var base = name.ifBlank { "video" }
        var n = 2
        while (File(folder(), "$base.mp4").exists() || File(folder(), "$base.ts").exists() || File(folder(), "$base.mkv").exists()) base = "$name ($n)".also { n++ }
        return base
    }

    /** Up to three subtitle files, in the preferred languages first, beside the video (VLC finds them by name). */
    private fun saveSubtitles(subs: List<SubtitleFile>, base: String, linkHeaders: Map<String, String>): List<String> {
        val wanted = Settings.subtitleLanguage.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val picked = subs.distinctBy { it.url }.sortedBy { s -> wanted.indexOfFirst { s.lang.contains(it, true) }.let { if (it < 0) 99 else it } }.take(3)
        return picked.mapIndexedNotNull { i, s ->
            runCatching {
                val body = get(s.url, s.headers?.takeIf { it.isNotEmpty() } ?: linkHeaders).use { r -> if (!r.isSuccessful) throw IOException("HTTP ${r.code}"); r.body.bytes() }
                val ext = s.url.substringBefore('?').substringAfterLast('.', "srt").lowercase().takeIf { it in setOf("srt", "vtt", "ass", "ssa") }
                    ?: if (body.take(6).toByteArray().decodeToString().startsWith("WEBVTT")) "vtt" else "srt"
                val lang = s.lang.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").take(24).ifBlank { "sub$i" }
                File(folder(), "$base.$lang.$ext").also { it.writeBytes(body) }.path
            }.getOrNull()
        }
    }

    private fun get(url: String, headers: Map<String, String>, range: String? = null): okhttp3.Response {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        if (headers.keys.none { it.equals("user-agent", true) }) b.header("User-Agent", USER_AGENT)
        range?.let { b.header("Range", it) }
        return app.baseClient.newCall(b.build()).execute()
    }

    /** One connection (servers that don't send parts of a file, or small files). */
    private suspend fun single(item: Item) {
        val file = File(item.file!!)
        val part = File(file.path + ".part")
        val have = part.length()
        get(item.source!!, item.headers, if (have > 0) "bytes=$have-" else null).use { r ->
            if (r.code == 416 && have > 0) { place(part, file); return }
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val append = r.code == 206 && have > 0
            val length = r.body.contentLength()
            val total = if (length > 0) (if (append) have + length else length) else 0
            var done = if (append) have else 0
            update(item.id) { it.copy(bytes = done, total = total) }
            RandomAccessFile(part, "rw").use { out ->
                if (!append) out.setLength(0)
                out.seek(done)
                val input = r.body.byteStream()
                val buf = ByteArray(256 * 1024)
                var lastTick = 0L
                val meter = Meter()
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastTick > 500) { lastTick = now; update(item.id, persist = false) { it.copy(bytes = done, speed = meter.tick(done)) }; save(force = false) }
                }
            }
            if (total in 1 until done || (total > 0 && done < total)) throw IOException("Connection closed early")
            update(item.id) { it.copy(bytes = done, total = done) }
        }
        place(part, file)
    }

    /**
     * Like a download manager (IDM): the file is split into 4 MB pieces that several connections fetch at the same
     * time, each written straight into its place. Saved pieces survive a pause, a restart or a dropped connection.
     * Servers that don't send parts of a file get one connection ([single]).
     */
    private suspend fun direct(item: Item) {
        val connections = Settings.downloadConnections
        val total = if (connections > 1) runCatching { probe(item.source!!, item.headers) }.getOrNull() else null
        if (total == null || total < MIN_SPLIT) return single(item)
        try {
            multi(item, total, connections)
        } catch (e: NoRanges) {
            single(item)
        }
    }

    private class NoRanges : IOException("The server sent the whole file instead of a part")

    /** The file's size when the server sends parts of it (Range requests), else null. */
    private fun probe(url: String, headers: Map<String, String>): Long? =
        get(url, headers, "bytes=0-0").use { r ->
            if (r.code != 206) null else r.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
        }

    private fun chunkLength(i: Int, total: Long): Long = minOf(CHUNK, total - i.toLong() * CHUNK)

    private suspend fun multi(item: Item, total: Long, connections: Int) {
        val file = File(item.file!!)
        val part = File(file.path + ".part")
        val n = ((total + CHUNK - 1) / CHUNK).toInt()
        val done = BooleanArray(n)
        if (item.chunks == n && item.total == total && part.isFile && part.length() == total)
            item.chunksDone.forEachIndexed { i, c -> if (i < n && c == '1') done[i] = true }
        fun marks() = synchronized(done) { String(CharArray(n) { if (done[it]) '1' else '0' }) }
        val received = java.util.concurrent.atomic.AtomicLong((0 until n).sumOf { if (done[it]) chunkLength(it, total) else 0L })
        update(item.id) { it.copy(total = total, chunks = n, chunksDone = marks(), bytes = received.get()) }
        val pending = java.util.concurrent.ConcurrentLinkedQueue((0 until n).filter { !done[it] })
        val meter = Meter()
        val lastTick = java.util.concurrent.atomic.AtomicLong(0)
        fun tick(force: Boolean = false) {
            val now = System.currentTimeMillis()
            val last = lastTick.get()
            if (!force && now - last < 500) return
            if (!lastTick.compareAndSet(last, now)) return
            val bytes = received.get()
            update(item.id, persist = false) { it.copy(bytes = bytes, chunksDone = marks(), speed = meter.tick(bytes)) }
            save(force = false)
        }
        RandomAccessFile(part, "rw").use { raf ->
            if (raf.length() != total) raf.setLength(total)
            kotlinx.coroutines.coroutineScope {
                repeat(connections.coerceAtMost(pending.size.coerceAtLeast(1))) {
                    launch(Dispatchers.IO) {
                        while (true) {
                            val i = pending.poll() ?: break
                            val from = i.toLong() * CHUNK
                            val to = from + chunkLength(i, total) - 1
                            var attempt = 0
                            while (true) {
                                ensureActive()
                                var got = 0L
                                try {
                                    get(item.source!!, item.headers, "bytes=$from-$to").use { r ->
                                        if (r.code == 200) throw NoRanges()
                                        if (r.code != 206) throw IOException("HTTP ${r.code}")
                                        val input = r.body.byteStream()
                                        val buf = ByteArray(64 * 1024)
                                        var pos = from
                                        while (pos <= to) {
                                            ensureActive()
                                            val k = input.read(buf, 0, minOf(buf.size.toLong(), to - pos + 1).toInt())
                                            if (k < 0) break
                                            synchronized(raf) { raf.seek(pos); raf.write(buf, 0, k) }
                                            pos += k; got += k
                                            received.addAndGet(k.toLong())
                                            tick()
                                        }
                                        if (pos != to + 1) throw IOException("Connection closed early")
                                    }
                                    synchronized(done) { done[i] = true }
                                    tick(force = true)
                                    break
                                } catch (e: IOException) {
                                    received.addAndGet(-got)
                                    if (e is NoRanges || ++attempt >= 5) throw e
                                    kotlinx.coroutines.delay(1000L * attempt)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (synchronized(done) { done.any { !it } }) throw IOException("Some pieces are missing")
        update(item.id) { it.copy(bytes = total, chunksDone = marks(), speed = 0) }
        place(part, file)
    }

    /** Puts the finished file in place (replacing an older one). */
    private fun place(part: File, file: File) {
        if (file.exists()) file.delete()
        if (!part.renameTo(file)) throw IOException("Can't move the finished file to ${file.path}")
    }

    /** Download speed, smoothed: for "4.2 MB/s · 2 min left". */
    private class Meter {
        private var lastBytes = -1L
        private var lastTime = 0L
        private var speed = 0L

        @Synchronized fun tick(bytes: Long): Long {
            val now = System.nanoTime()
            if (lastBytes < 0) { lastBytes = bytes; lastTime = now; return speed }
            val dt = (now - lastTime) / 1e9
            if (dt >= 0.4) {
                val instant = ((bytes - lastBytes) / dt).toLong().coerceAtLeast(0)
                speed = if (speed == 0L) instant else (speed * 0.7 + instant * 0.3).toLong()
                lastBytes = bytes; lastTime = now
            }
            return speed
        }
    }

    // ---------- HLS ----------

    internal data class Segment(val url: String, val keyUrl: String?, val iv: ByteArray?, val sequence: Long, val range: String?)

    private fun resolveUrl(base: String, ref: String): String = base.toHttpUrlOrNull()?.resolve(ref)?.toString() ?: ref

    private fun text(url: String, headers: Map<String, String>): String =
        get(url, headers).use { r -> if (!r.isSuccessful) throw IOException("HTTP ${r.code} for playlist"); r.body.string() }

    /** The qualities inside a stream source (its HLS master playlist's heights, best first); empty for a single file. */
    suspend fun qualities(link: ExtractorLink): List<Int> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val hls = link.type == ExtractorLinkType.M3U8 || link.url.substringBefore('?').endsWith(".m3u8")
        if (!hls) return@withContext emptyList()
        val headers = link.getAllHeaders().toMutableMap().apply { if (keys.none { it.equals("user-agent", true) }) put("User-Agent", USER_AGENT) }
        val master = runCatching { text(link.url, headers) }.getOrNull() ?: return@withContext emptyList()
        Regex("RESOLUTION=\\d+x(\\d+)").findAll(master).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it > 0 }
            .distinct().sortedDescending().toList()
    }

    /** The variant closest to the preferred quality (highest when none is set). */
    internal fun pickVariant(master: String, base: String, maxHeight: Int = Settings.preferredQuality): String? {
        val lines = master.lines().map { it.trim() }
        data class V(val url: String, val height: Int, val bandwidth: Long)
        val variants = lines.mapIndexedNotNull { i, l ->
            if (!l.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@mapIndexedNotNull null
            val height = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val bw = Regex("[^-]BANDWIDTH=(\\d+)").find(",$l")?.groupValues?.get(1)?.toLongOrNull() ?: 0
            V(resolveUrl(base, uri), height, bw)
        }
        if (variants.isEmpty()) return null
        val preferred = maxHeight.takeIf { it > 0 }
        val ok = preferred?.let { p -> variants.filter { it.height in 1..p } }.orEmpty()
        return (ok.ifEmpty { variants }).maxWith(compareBy<V> { it.height }.thenBy { it.bandwidth }).url
    }

    internal fun parseMedia(playlist: String, base: String): Pair<String?, List<Segment>> {
        var keyUrl: String? = null; var iv: ByteArray? = null; var seq = 0L; var map: String? = null
        var range: String? = null; var nextOffset = 0L
        val out = mutableListOf<Segment>()
        var expectSegment = false
        for (raw in playlist.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = l.substringAfter(':').toLongOrNull() ?: 0
                l.startsWith("#EXT-X-KEY:") -> {
                    val method = Regex("METHOD=([^,]+)").find(l)?.groupValues?.get(1)
                    when (method) {
                        "NONE" -> { keyUrl = null; iv = null }
                        "AES-128" -> {
                            keyUrl = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)?.let { resolveUrl(base, it) }
                            iv = Regex("IV=0[xX]([0-9a-fA-F]+)").find(l)?.groupValues?.get(1)?.padStart(32, '0')?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
                        }
                        else -> throw IOException("This stream uses $method encryption (DRM); it can't be downloaded")
                    }
                }
                l.startsWith("#EXT-X-MAP:") -> map = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)?.let { resolveUrl(base, it) }
                l.startsWith("#EXT-X-BYTERANGE:") -> {
                    val (len, off) = l.substringAfter(':').split('@').let { it[0].toLong() to (it.getOrNull(1)?.toLong() ?: nextOffset) }
                    range = "bytes=$off-${off + len - 1}"; nextOffset = off + len
                }
                l.startsWith("#EXTINF") -> expectSegment = true
                l.isNotEmpty() && !l.startsWith("#") && expectSegment -> {
                    out += Segment(resolveUrl(base, l), keyUrl, iv, seq++, range)
                    range = null; expectSegment = false
                }
            }
        }
        return map to out
    }

    private suspend fun hls(item: Item) {
        val headers = item.headers
        var url = item.source!!
        var playlist = text(url, headers)
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            url = pickVariant(playlist, url) ?: throw IOException("Empty HLS playlist")
            playlist = text(url, headers)
        }
        if (!playlist.contains("#EXT-X-ENDLIST") && playlist.contains("#EXTINF")) throw IOException("This is a live stream; it can't be downloaded")
        val (init, segments) = parseMedia(playlist, url)
        if (segments.isEmpty()) throw IOException("Empty HLS playlist")
        // fMP4 segments join into an .mp4; MPEG-TS into a .ts. Both play in VLC.
        var item0 = item
        if (init != null && item.file!!.endsWith(".ts")) item0 = update(item.id) { it.copy(file = it.file!!.removeSuffix(".ts") + ".mp4") } ?: item
        val file = File(item0.file!!)
        val part = File(file.path + ".part")
        // Several segments at a time (like a download manager), each saved as its own piece, joined in order at the end.
        val dir = File(file.path + ".parts").apply { mkdirs() }
        if (part.isFile && dir.listFiles().isNullOrEmpty()) part.delete()   // left by an older version: start over
        fun piece(i: Int) = File(dir, "%05d".format(i))
        val doneCount = java.util.concurrent.atomic.AtomicInteger(segments.indices.count { piece(it).isFile })
        val written = java.util.concurrent.atomic.AtomicLong(segments.indices.sumOf { piece(it).takeIf { f -> f.isFile }?.length() ?: 0L })
        update(item.id) { it.copy(segments = segments.size, segmentsDone = doneCount.get(), bytes = written.get()) }
        val keys = ConcurrentHashMap<String, ByteArray>()
        val pending = java.util.concurrent.ConcurrentLinkedQueue(segments.indices.filter { !piece(it).isFile })
        val meter = Meter()
        val lastTick = java.util.concurrent.atomic.AtomicLong(0)
        kotlinx.coroutines.coroutineScope {
            repeat(Settings.downloadConnections.coerceAtMost(pending.size.coerceAtLeast(1))) {
                launch(Dispatchers.IO) {
                    while (true) {
                        ensureActive()
                        val i = pending.poll() ?: break
                        val s = segments[i]
                        var bytes = fetch(s.url, headers, s.range)
                        if (s.keyUrl != null) {
                            val key = keys[s.keyUrl] ?: fetch(s.keyUrl, headers, null).also { keys[s.keyUrl] = it }
                            val iv = s.iv ?: ByteArray(16).also { b -> var v = s.sequence; for (k in 15 downTo 8) { b[k] = (v and 0xff).toByte(); v = v shr 8 } }
                            bytes = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(bytes)
                        }
                        val tmp = File(dir, "%05d.tmp".format(i))
                        tmp.writeBytes(bytes)
                        if (!tmp.renameTo(piece(i))) throw IOException("Can't save a piece in ${dir.path}")
                        val d = doneCount.incrementAndGet()
                        val w = written.addAndGet(bytes.size.toLong())
                        val now = System.currentTimeMillis()
                        val last = lastTick.get()
                        if (now - last >= 500 && lastTick.compareAndSet(last, now)) {
                            update(item.id, persist = false) { it.copy(segmentsDone = d, bytes = w, speed = meter.tick(w)) }
                            save(force = false)
                        }
                    }
                }
            }
        }
        java.io.FileOutputStream(part).use { out ->
            if (init != null) out.write(fetch(init, headers, null))
            for (i in segments.indices) piece(i).inputStream().use { it.copyTo(out, 256 * 1024) }
        }
        val total = part.length()
        dir.deleteRecursively()
        place(part, file)
        update(item.id) { it.copy(segmentsDone = segments.size, total = total, bytes = total, speed = 0) }
    }

    /** One segment or key, with a few retries for flaky connections. */
    private suspend fun fetch(url: String, headers: Map<String, String>, range: String?): ByteArray {
        var last: Throwable? = null
        repeat(4) { attempt ->
            coroutineContext.ensureActive()
            try {
                return get(url, headers, range).use { r -> if (!r.isSuccessful) throw IOException("HTTP ${r.code}"); r.body.bytes() }
            } catch (t: IOException) {
                last = t
                kotlinx.coroutines.delay(1000L * (attempt + 1))
            }
        }
        throw last ?: IOException("Download failed")
    }

    // ---------- JSON ----------

    private fun toJson(i: Item) = JSONObject().apply {
        put("prefName", i.prefName ?: ""); put("prefSource", i.prefSource ?: ""); put("maxHeight", i.maxHeight)
        put("chunks", i.chunks); put("chunksDone", i.chunksDone)
        put("id", i.id); put("profile", i.profile); put("apiName", i.apiName); put("url", i.url); put("title", i.title)
        put("poster", i.poster ?: ""); put("type", i.type); put("data", i.data); put("label", i.label ?: "")
        i.season?.let { put("season", it) }; i.episode?.let { put("episode", it) }; put("episodeName", i.episodeName ?: "")
        put("file", i.file ?: ""); put("state", i.state.name); put("bytes", i.bytes); put("total", i.total)
        put("segmentsDone", i.segmentsDone); put("segments", i.segments); put("error", i.error ?: ""); put("created", i.created)
        put("source", i.source ?: ""); put("headers", JSONObject(i.headers)); put("hls", i.hls); put("subtitles", JSONArray(i.subtitles))
        put("retried", i.retried)
    }

    private fun fromJson(o: JSONObject): Item {
        fun s(k: String) = o.optString(k).ifBlank { null }
        val h = o.optJSONObject("headers")
        val subs = o.optJSONArray("subtitles")
        return Item(
            o.getString("id"), o.optString("profile", Profiles.DEFAULT), o.getString("apiName"), o.getString("url"), o.getString("title"),
            s("poster"), o.optString("type", TvType.Movie.name), o.getString("data"), s("label"),
            if (o.has("season")) o.getInt("season") else null, if (o.has("episode")) o.getInt("episode") else null, s("episodeName"),
            s("file"), runCatching { State.valueOf(o.getString("state")) }.getOrDefault(State.PAUSED), o.optLong("bytes"), o.optLong("total"),
            o.optInt("segmentsDone"), o.optInt("segments"), s("error"), o.optLong("created"),
            s("source"), h?.keys()?.asSequence()?.associateWith { h.getString(it) }.orEmpty(), o.optBoolean("hls"),
            subs?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(), o.optBoolean("retried"),
            s("prefName"), s("prefSource"), o.optInt("maxHeight"), o.optInt("chunks"), o.optString("chunksDone"),
        )
    }
}

/**
 * Plays downloaded files. It takes the extension's name, so the player records progress under
 * the same title and episode as when streaming.
 */
class OfflineApi(original: String) : MainAPI() {
    override var name = original
    override var mainUrl = "offline://"
    override val hasMainPage = false

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? = null

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val item = Downloads.get(name, data) ?: return false
        val file = item.file?.let(::File)?.takeIf { it.isFile } ?: return false
        // The file itself comes from Engine.links (a downloaded copy always plays first); add its subtitles.
        item.subtitles.map(::File).filter { it.isFile }.forEach { f ->
            subtitleCallback(newSubtitleFile(f.name.removePrefix(file.nameWithoutExtension).trim('.').substringBeforeLast('.').ifBlank { "Subtitles" }, android.net.Uri.fromFile(f).toString()))
        }
        return true
    }

    /** A title page made from what's downloaded of it (for playing and next episode offline). */
    suspend fun response(item: Downloads.Item): LoadResponse {
        val siblings = Downloads.all().filter { it.apiName == item.apiName && it.url == item.url && it.state == Downloads.State.DONE }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }, { it.created }))
        val type = runCatching { TvType.valueOf(item.type) }.getOrDefault(TvType.Movie)
        return if (!item.isEpisode) newMovieLoadResponse(item.title, item.url, type, item.data) { posterUrl = item.poster }
        else newTvSeriesLoadResponse(item.title, item.url, type, siblings.map { d ->
            newEpisode(d.data) { name = d.episodeName; season = d.season; episode = d.episode }
        }) { posterUrl = item.poster }
    }

    companion object {
        const val SOURCE = "Downloaded"

        suspend fun local(file: File): ExtractorLink = newExtractorLink(SOURCE, SOURCE, android.net.Uri.fromFile(file).toString(), ExtractorLinkType.VIDEO) {
            quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value
        }
    }
}
