package io.github.sherazi5110.cloudstream

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/** A native crash's tombstone (protobuf) gives its abort message, libraries and functions in the crash report. */
class TombstoneTest {
    private fun ByteArrayOutputStream.varint(v: Long) { var x = v; while (x >= 0x80) { write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; write(x.toInt()) }
    private fun ByteArrayOutputStream.field(n: Int, bytes: ByteArray) { varint((n shl 3 or 2).toLong()); varint(bytes.size.toLong()); write(bytes) }
    private fun ByteArrayOutputStream.text(n: Int, s: String) = field(n, s.toByteArray())
    private fun ByteArrayOutputStream.number(n: Int, v: Long) { varint((n shl 3).toLong()); varint(v) }
    private fun msg(block: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(block).toByteArray()

    @Test fun `text fields of a nested tombstone, in order, without build ids`() {
        val frame = msg {
            number(1, 0x1234)
            text(5, "ff_aac_decode_frame")
            text(7, "/data/app/~~x/io.github.sherazi5110.cloudstream/lib/arm64/libavcodec.so")
            text(9, "0123456789abcdef0123456789abcdef")
        }
        val thread = msg { number(1, 42); text(2, "ExoPlayer:Playback"); field(4, frame) }
        // A field 'Z' (0x5a, printable) right before a string mustn't end up in it.
        val stone = msg {
            text(2, "lenovo/TB522FU:17")
            text(14, "Abort message: 'decoder failed'")
            field(16, msg { number(1, 42); field(2, thread) })
            text(11, "Z")
        }
        assertEquals(
            listOf("lenovo/TB522FU:17", "Abort message: 'decoder failed'", "ExoPlayer:Playback", "ff_aac_decode_frame",
                "/data/app/~~x/io.github.sherazi5110.cloudstream/lib/arm64/libavcodec.so"),
            CrashReport.tombstoneLines(stone),
        )
    }
}
