package cx.m42.unitool.kit.qr

import java.util.zip.Inflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** The hand-written deflate, read back by the JDK's zlib. */
class DeflateTest {

    private fun inflate(stream: ByteArray, size: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(stream)
        val out = ByteArray(size + 1) // one spare byte: a zero-length buffer never lets the stream finish
        var offset = 0
        while (!inflater.finished()) {
            val n = inflater.inflate(out, offset, out.size - offset)
            if (n == 0 && !inflater.finished() && (inflater.needsInput() || inflater.needsDictionary())) {
                error("truncated stream at $offset of $size")
            }
            offset += n
        }
        inflater.end()
        return out.copyOf(offset)
    }

    private fun roundTrip(data: ByteArray) {
        // Inflater verifies the Adler-32 at the end and fails on a wrong one.
        assertContentEquals(data, inflate(Deflate.zlib(data), data.size), "${data.size} bytes")
    }

    @Test
    fun emptyAndTiny() {
        roundTrip(ByteArray(0))
        roundTrip(byteArrayOf(42))
        roundTrip(byteArrayOf(1, 2))
        roundTrip(byteArrayOf(7, 7, 7))
    }

    @Test
    fun randomBytesThatDoNotCompress() {
        val random = Random(1)
        for (size in listOf(10, 255, 1000, 65_536, 300_000)) roundTrip(random.nextBytes(size))
    }

    @Test
    fun repetitionAtEveryDistanceAndLength() {
        roundTrip(ByteArray(1_000_000))
        val random = Random(2)
        for (period in listOf(1, 2, 3, 4, 5, 7, 100, 257, 258, 259, 4096, 32767, 32768, 32769, 40000)) {
            val unit = random.nextBytes(period)
            roundTrip(ByteArray(100_000) { unit[it % period] })
        }
    }

    @Test
    fun mixedTextAndRuns() {
        val random = Random(3)
        val text = buildString { repeat(20_000) { append(if (random.nextInt(10) == 0) "\n" else ('a' + random.nextInt(4)).toString().repeat(random.nextInt(1, 300))) } }
        roundTrip(text.encodeToByteArray())
    }

    @Test
    fun aQrPictureCompressesWell() {
        val code = Qr.encode("a".repeat(2953))!!
        val png = QrImage.png(code)
        val raw = (code.size + 8) * 8 * ((code.size + 8) * 8 / 8 + 1)
        assertTrue(png.size < raw / 10, "${png.size} bytes for $raw raw: back-references should do most of the work")
    }
}
