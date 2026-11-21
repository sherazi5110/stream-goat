package io.github.sherazi5110.cloudstream

import org.junit.Assert.assertEquals
import org.junit.Test

class DisguiseTest {
    private fun ts(packets: Int) = ByteArray(188 * packets).also { b -> for (i in 0 until packets) b[i * 188] = 0x47 }

    @Test fun plainTsIsLeftAlone() = assertEquals(0, Disguise.junkBefore(ts(10), 188 * 10))

    @Test fun fakePngHeaderIsSkipped() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(112)
        val data = png + ts(10)
        assertEquals(120, Disguise.junkBefore(data, data.size))
    }

    @Test fun notTsAtAll() = assertEquals(0, Disguise.junkBefore(ByteArray(4000) { 1 }, 4000))

    @Test fun pictureNamesBecomeTs() {
        assertEquals("seg-1.ts", Disguise.segmentName("seg-1.jpg"))
        assertEquals("index.m3u8", Disguise.segmentName("index.m3u8"))
    }
}
