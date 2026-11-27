package io.github.sherazi5110.cloudstream.ui

import io.github.sherazi5110.cloudstream.LiveTv
import org.junit.Assert.assertEquals
import org.junit.Test

/** Live TV: a playlist's languages, and the channel logos' backgrounds. */
class LiveTvTest {
    @Test fun `a playlist's own tvg-language is read`() {
        val list = LiveTv.parseM3u("s", "#EXTM3U\n#EXTINF:-1 tvg-language=\"Hindi;English\" group-title=\"News\",Aaj Tak\nhttps://example.com/a.m3u8\n")
        assertEquals(listOf("Hindi", "English"), list.single().languages)
    }

    @Test fun `a dark see-through logo gets a light background, a white one or a full picture doesn't`() {
        val clear = 0x00000000; val black = 0xFF101010.toInt(); val white = 0xFFFFFFFF.toInt()
        assertEquals(true, LogoTone.darkPixels(IntArray(100) { if (it % 3 == 0) black else clear }))
        assertEquals(false, LogoTone.darkPixels(IntArray(100) { if (it % 3 == 0) white else clear }))
        assertEquals(false, LogoTone.darkPixels(IntArray(100) { black }))
    }
}
