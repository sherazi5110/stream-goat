package com.lagradost.cloudstream3.linux

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Shared source searches: a prefetch and the player share one; a complete one is kept 20 minutes. */
class SourceSessionsTest {
    private class Counting(name: String, private val fail: Boolean = false) : MainAPI() {
        override var name = name
        val calls = AtomicInteger()
        override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
            calls.incrementAndGet()
            delay(300)
            if (fail) error("site down")
            callback(newExtractorLink(name, "Server 1", "https://cdn.example/$data/1.m3u8"))
            delay(200)
            callback(newExtractorLink(name, "Server 2", "https://cdn.example/$data/2.m3u8"))
            return true
        }
    }

    private suspend fun links(api: MainAPI, data: String): List<String> {
        val out = mutableListOf<String>()
        Engine.links(api, data).collect { if (it is LinkEvent.Link) out += it.link.url }
        return out
    }

    @Test
    fun `a prefetch and the player share one search, and it is kept`() = runBlocking {
        Platform.init()
        val api = Counting("SessionsTest-${System.nanoTime()}")
        Settings.prepareSources = true
        // The title page starts the search; Play joins it while it runs: one call to the extension.
        Engine.prefetch(api, "ep1")
        delay(100)
        assertEquals(listOf("https://cdn.example/ep1/1.m3u8", "https://cdn.example/ep1/2.m3u8"), links(api, "ep1"))
        assertEquals(1, api.calls.get())
        // Playing it again: straight from the cache, no new search.
        assertTrue(Engine.cached(api, "ep1"))
        assertEquals(2, links(api, "ep1").size)
        assertEquals(1, api.calls.get())
        // Another episode is its own search; forgetting one asks again.
        links(api, "ep2")
        assertEquals(2, api.calls.get())
        Engine.forget(api, "ep1")
        assertFalse(Engine.cached(api, "ep1"))
        links(api, "ep1")
        assertEquals(3, api.calls.get())
    }

    @Test
    fun `a failed search is not kept`() = runBlocking {
        Platform.init()
        val api = Counting("SessionsFail-${System.nanoTime()}", fail = true)
        links(api, "ep1")
        assertFalse(Engine.cached(api, "ep1"))
        links(api, "ep1")
        assertEquals(2, api.calls.get(), "a failed search runs again")
    }
}
