package io.github.sherazi5110.cloudstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.net.URL

/** NetMirror-like source: playlist served from a .php address as text/plain, segments named .jpg with a fake PNG header. */
class StreamProxyTest {
    private fun ts(packets: Int) = ByteArray(188 * packets).also { b -> for (i in 0 until packets) b[i * 188] = 0x47 }

    @Test fun phpPlaylistIsRewrittenAndDisguisedSegmentsAreCleaned() {
        // After some other tests in the same JVM, OkHttp thinks it's on Android and can't make TLS/clients there
        // (no Conscrypt): nothing to do with the proxy, so the test is skipped rather than failed.
        val env = runCatching {
            com.lagradost.cloudstream3.app.baseClient.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:1/").build()).execute().close()
        }.exceptionOrNull()
        org.junit.Assume.assumeFalse(env is NoClassDefFoundError || env is ExceptionInInitializerError)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(60)
        val seg = png + ts(20)
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    sock.use {
                        val reader = it.getInputStream().bufferedReader()
                        val path = reader.readLine().orEmpty().split(" ").getOrElse(1) { "/" }
                        while (reader.readLine()?.isNotEmpty() == true) { }   // the rest of the request
                        val (type, body) = if (path.startsWith("/list.php"))
                            "text/plain" to "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nseg1.jpg\n#EXT-X-ENDLIST\n".toByteArray()
                        else "image/jpeg" to seg
                        val out = it.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(body); out.flush()
                    }
                }
            }
        }
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            val proxied = StreamProxy.wrap("$base/list.php?token=1", mapOf("Cookie" to "a=b"))
            val playlist = URL(proxied).readText()
            val segLine = playlist.lines().first { it.isNotBlank() && !it.startsWith("#") }
            assertTrue("segment goes through the proxy: $segLine", segLine.startsWith("http://127.0.0.1:") && segLine.endsWith(".ts"))
            val bytes = URL(segLine).readBytes()
            assertEquals(188 * 20, bytes.size)
            assertEquals(0x47.toByte(), bytes[0])
        } finally { server.close() }
    }
}
