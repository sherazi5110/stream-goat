package io.github.sherazi5110.cloudstream

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * "Add from phone": while that screen is open on the TV, a small web page on the home network lets a phone send
 * links (scan the QR code, paste, Send): Stremio add-ons, CloudStream repositories (address, cloudstreamrepo://
 * link or shortcode) and Live TV playlists. Nothing is installed until it's confirmed on the TV.
 *
 * Safety: it only listens while the screen is open (and at most [LIFETIME_MS]), only answers devices on the home
 * network (private addresses), and needs the 4-digit code shown on the TV; after [MAX_WRONG] wrong codes the code
 * changes.
 */
object PhoneLink {
    enum class Kind(val label: String) {
        STREMIO("Stremio add-on"), REPO("CloudStream repository"), PLAYLIST("Live TV playlist"), UNKNOWN("Link")
    }

    /** A link sent from a phone, waiting to be added (or added / ignored / failed) on the TV. */
    data class Received(val id: Int, val text: String, val kind: Kind, val state: String = WAITING, val message: String? = null,
                        val repoUrl: String? = null, val repoName: String? = null)

    const val WAITING = "waiting"
    const val ADDING = "adding"
    const val ADDED = "added"
    const val IGNORED = "ignored"
    const val FAILED = "failed"
    private const val LIFETIME_MS = 10 * 60_000L
    private const val MAX_WRONG = 5
    private val PORTS = 8765..8775

    val received = mutableStateListOf<Received>()
    var code by mutableStateOf("")
        private set
    /** "http://192.168.1.23:8765", or null when it isn't running (or the TV isn't on a network). */
    var address by mutableStateOf<String?>(null)
        private set
    var running by mutableStateOf(false)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "phone-link").apply { isDaemon = true } }
    @Volatile private var wrong = 0
    @Volatile private var nextId = 1
    private val stopLater = Runnable { stop() }

    /** Which kind of link [text] is, from the link itself ([Kind.UNKNOWN] is checked online when it's added). */
    fun classify(text: String): Kind {
        val t = text.trim()
        val path = t.substringBefore('?').substringBefore('#').lowercase().trimEnd('/')
        return when {
            t.startsWith("stremio://", true) -> Kind.STREMIO
            (t.startsWith("http://", true) || t.startsWith("https://", true)) && path.endsWith("/manifest.json") -> Kind.STREMIO
            t.startsWith("cloudstreamrepo://", true) || t.startsWith("https://cs.repo/", true) -> Kind.REPO
            (t.startsWith("http://", true) || t.startsWith("https://", true)) && path.endsWith(".json") -> Kind.REPO
            t.matches(Regex("^[a-zA-Z0-9!_-]{2,40}$")) -> Kind.REPO   // a shortcode, as in the official app
            (t.startsWith("http://", true) || t.startsWith("https://", true)) &&
                (path.endsWith(".m3u") || path.endsWith(".m3u8") || "type=m3u" in t.lowercase()) -> Kind.PLAYLIST
            else -> Kind.UNKNOWN
        }
    }

    /** Starts listening (a new code); false when the TV has no home-network address. */
    fun start(): Boolean {
        stop()
        val ip = lanAddress() ?: return false
        val socket = PORTS.firstNotNullOfOrNull { p -> runCatching { ServerSocket(p, 16) }.getOrNull() } ?: return false
        server = socket
        code = newCode()
        wrong = 0
        address = "http://${ip.hostAddress}:${socket.localPort}"
        running = true
        pool.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                pool.execute { client.use { runCatching { handle(it) } } }
            }
        }
        main.postDelayed(stopLater, LIFETIME_MS)
        return true
    }

    fun stop() {
        main.removeCallbacks(stopLater)
        runCatching { server?.close() }
        server = null
        running = false
        address = null
    }

    /** The QR code's address: opens the phone page with the code filled in. */
    val qrUrl: String? get() = address?.let { "$it/?c=$code" }

    fun update(id: Int, f: (Received) -> Received) {
        val i = received.indexOfFirst { it.id == id }
        if (i >= 0) received[i] = f(received[i])
    }

    /** Adds a received link on the TV: installs the add-on, adds the repository or the playlist. */
    suspend fun add(item: Received) {
        update(item.id) { it.copy(state = ADDING, message = null) }
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                when (item.kind) {
                    Kind.STREMIO -> addStremio(item)
                    Kind.REPO -> addRepo(item)
                    Kind.PLAYLIST -> LiveTv.add(LiveTv.Kind.M3U, item.text).let { (s, n) -> item.copy(message = "${s.name}: $n channels, in Live TV") }
                    // Not obvious from the link: a CloudStream repository's JSON, else a Stremio add-on.
                    Kind.UNKNOWN -> runCatching { addRepo(item) }.getOrElse { addStremio(item) }
                }
            }
        }
        result.onSuccess { done -> update(item.id) { done.copy(state = ADDED) } }
            .onFailure { e -> update(item.id) { it.copy(state = FAILED, message = e.message ?: e.toString()) } }
        PluginManager.bumpVersion()
    }

    private suspend fun addStremio(item: Received): Received {
        val addon = StremioAddons.install(item.text)
        return item.copy(kind = Kind.STREMIO, message = "${addon.manifest.name}: added to Stremio add-ons")
    }

    private suspend fun addRepo(item: Received): Received {
        val repo = RepositoryManager.addRepository(item.text)
        return item.copy(kind = Kind.REPO, message = "${repo.name}: open it to install its extensions", repoUrl = repo.url, repoName = repo.name)
    }

    // ---------- the phone's side: a tiny web server ----------

    /** Tests: a fixed code instead of a random one. */
    internal var fixedCode: String? = null

    private fun newCode(): String = fixedCode ?: "%04d".format(random.nextInt(10_000))

    /** Tests: the address to listen on instead of the home network's. */
    internal var testAddress: InetAddress? = null

    /** The TV's address on the home network (Wi-Fi or Ethernet). */
    private fun lanAddress(): InetAddress? = testAddress ?: runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { n -> if (n.name.startsWith("wlan") || n.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }.getOrNull()

    /** Only devices on the home network (private addresses) may talk to it. */
    private fun fromHomeNetwork(a: InetAddress): Boolean =
        a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress ||
            (a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc)   // IPv6 unique-local (fc00::/7)

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val out = socket.getOutputStream()
        if (!fromHomeNetwork(socket.inetAddress)) return respond(out, 403, "text/plain", "Only devices on the same network")
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val (method, target) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        var length = 0
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            if (line.startsWith("content-length:", true)) length = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        if (length > 64 * 1024) return respond(out, 413, "text/plain", "Too long")
        val body = ByteArray(length).also { var r = 0; while (r < length) { val n = input.read(it, r, length - r); if (n < 0) break; r += n } }.decodeToString()
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
        when {
            method == "GET" && (path == "/" || path == "/index.html") ->
                respond(out, 200, "text/html; charset=utf-8", App.context.assets.open("phone/index.html").use { it.readBytes() }.decodeToString())
            method == "POST" && path == "/send" -> send(out, body)
            method == "GET" && path == "/status" -> status(out, query["code"].orEmpty(), query["ids"].orEmpty())
            else -> respond(out, 404, "text/plain", "Not found")
        }
    }

    private fun send(out: OutputStream, body: String) {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return json(out, 400, JSONObject().put("error", "Bad request"))
        if (!checkCode(o.optString("code"))) return json(out, 403, JSONObject().put("error", if (wrong == 0) "Too many wrong codes: use the new code on the TV" else "Wrong code: use the 4 digits shown on the TV"))
        val links = o.optString("text").split(Regex("[\\s]+")).map { it.trim().trim(',', ';', '"', '\'') }.filter { it.isNotBlank() }.distinct().take(20)
        if (links.isEmpty()) return json(out, 400, JSONObject().put("error", "Paste a link first"))
        val sent = links.map { text -> Received(nextId++, text, classify(text)) }
        val done = java.util.concurrent.CountDownLatch(1)
        main.post { received.addAll(0, sent); done.countDown() }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        json(out, 200, JSONObject().put("items", JSONArray(sent.map { JSONObject().put("id", it.id).put("text", it.text).put("kind", it.kind.label) })))
    }

    private fun status(out: OutputStream, code: String, ids: String) {
        if (!checkCode(code)) return json(out, 403, JSONObject().put("error", "Wrong code"))
        val wanted = ids.split(',').mapNotNull { it.toIntOrNull() }.toSet()
        val items = received.toList().filter { it.id in wanted }
        json(out, 200, JSONObject().put("items", JSONArray(items.map { JSONObject().put("id", it.id).put("state", it.state).put("message", it.message ?: "") })))
    }

    @Synchronized
    private fun checkCode(given: String): Boolean {
        val ok = java.security.MessageDigest.isEqual(given.trim().toByteArray(), code.toByteArray())
        if (ok) return true
        if (++wrong >= MAX_WRONG) { val fresh = newCode(); main.post { code = fresh }; code = fresh; wrong = 0 }
        return false
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun json(out: OutputStream, code: Int, o: JSONObject) = respond(out, code, "application/json; charset=utf-8", o.toString())

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Error" }
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }
}
