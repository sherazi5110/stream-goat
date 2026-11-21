package io.github.sherazi5110.cloudstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** NetMirror hands the player its playlist inline, as a data: address. */
class DataUrlTest {
    private val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nhttps://cdn.example/seg1.jpg\n#EXT-X-ENDLIST\n"

    @Test fun base64() {
        val url = "data:application/vnd.apple.mpegurl;base64," + Base64.getEncoder().encodeToString(playlist.toByteArray())
        assertEquals(playlist, DataUrl.decode(url))
    }

    @Test fun plain() = assertEquals("#EXTM3U\nx y", DataUrl.decode("data:application/x-mpegurl,%23EXTM3U%0Ax%20y"))

    @Test fun notData() = assertNull(DataUrl.decode("https://example.com/a.m3u8"))

    @Test fun servedThroughTheProxy() {
        val url = "data:application/vnd.apple.mpegurl;base64," + Base64.getEncoder().encodeToString(playlist.toByteArray())
        val proxied = StreamProxy.wrap(url, mapOf("Referer" to "https://net52.cc/", "Cookie" to "t_hash_t=1"))
        assertTrue(proxied, proxied.startsWith("http://127.0.0.1:") && proxied.endsWith("/playlist.m3u8"))
        val served = java.net.URL(proxied).readText()
        val seg = served.lines().first { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("segment goes through the proxy as .ts: $seg", seg.startsWith("http://127.0.0.1:") && seg.endsWith(".ts"))
    }
}
