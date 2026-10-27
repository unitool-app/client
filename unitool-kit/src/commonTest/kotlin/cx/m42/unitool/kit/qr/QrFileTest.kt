package cx.m42.unitool.kit.qr

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Base45 against RFC 9285 and a file cut into codes and gathered back (.ai-notes/qr-scanner D630, D636). */
class QrFileTest {

    @Test
    fun base45MatchesTheRfcExamples() {
        assertEquals("BB8", Base45.encode("AB".encodeToByteArray()))
        assertEquals("%69 VD92EX0", Base45.encode("Hello!!".encodeToByteArray()))
        assertEquals("UJCLQE7W581", Base45.encode("base-45".encodeToByteArray()))
        assertEquals("ietf!", Base45.decode("QED8WEX0")!!.decodeToString())
        assertEquals("", Base45.encode(ByteArray(0)))
    }

    @Test
    fun base45RefusesWhatIsNotBase45() {
        assertNull(Base45.decode("GGW"), "65535 + 1")
        assertNull(Base45.decode("A"), "a dangling character")
        assertNull(Base45.decode("ab"), "small letters")
        assertNull(Base45.decode("Z9"), "one byte over 255")
    }

    @Test
    fun base45RoundTripsEveryLength() {
        val random = Random(1)
        for (length in 0..300) {
            val bytes = random.nextBytes(length)
            assertContentEquals(bytes, Base45.decode(Base45.encode(bytes)))
        }
    }

    @Test
    fun chunksAreAlphanumericAndGatherInAnyOrderWithRepeats() {
        val bytes = Random(2).nextBytes(10_000)
        val chunks = QrFile.cut("report.pdf", bytes, chunk = 700)
        assertEquals(QrFile.count("report.pdf", bytes.size.toLong(), 700).toInt(), chunks.size)
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
        assertTrue(chunks.all { chunk -> chunk.all { it in alphabet } }, "alphanumeric mode only")
        assertTrue(chunks.all { Qr.encode(it)!!.version <= 20 })

        val order = chunks.indices.shuffled(Random(5))
        var gathering = QrFileGathering(QrFile.parse(chunks[order.first()])!!)
        assertNull(if (order.first() == 0) null else gathering.name, "no name before the first chunk")
        for (i in order.drop(1)) {
            gathering = (gathering.add(QrFile.parse(chunks[i])!!) as QrFileGathering.Added.New).gathering
            assertSame(QrFileGathering.Added.Repeat, gathering.add(QrFile.parse(chunks[i])!!))
        }
        assertEquals("report.pdf", gathering.name)
        assertEquals(bytes.size, gathering.size)
        val file = assertIs<QrFileGathering.Assembled.Ok>(gathering.assemble())
        assertEquals("report.pdf", file.name)
        assertContentEquals(bytes, file.bytes)
    }

    @Test
    fun missingChunksAreNamedAndTheFileWaitsForThem() {
        val chunks = QrFile.cut("a.bin", ByteArray(500) { it.toByte() }, chunk = 100)
        var gathering = QrFileGathering(QrFile.parse(chunks[0])!!)
        gathering = (gathering.add(QrFile.parse(chunks[3])!!) as QrFileGathering.Added.New).gathering
        assertEquals(chunks.size, gathering.count)
        assertEquals(2, gathering.have)
        assertEquals((1..chunks.size).filter { it != 1 && it != 4 }, gathering.missing)
        assertSame(QrFileGathering.Assembled.Incomplete, gathering.assemble())
    }

    @Test
    fun aChunkOfAnotherFileIsNotMixedIn() {
        val one = QrFile.cut("one", ByteArray(300), chunk = 100)
        val two = QrFile.cut("two", ByteArray(300), chunk = 100)
        val gathering = QrFileGathering(QrFile.parse(one[0])!!)
        assertSame(QrFileGathering.Added.OtherFile, gathering.add(QrFile.parse(two[1])!!))
    }

    @Test
    fun aChangedByteIsCaughtByTheHash() {
        val chunks = QrFile.cut("x", ByteArray(300) { 7 }, chunk = 100, set = byteArrayOf(9, 9, 9, 9))
        val last = QrFile.parse(chunks.last())!!
        val forged = QrFileChunk(last.set, last.index, last.count, last.data.copyOf().also { it[it.size - 1] = 8 })
        var gathering = QrFileGathering(forged)
        for (chunk in chunks.dropLast(1)) gathering = (gathering.add(QrFile.parse(chunk)!!) as QrFileGathering.Added.New).gathering
        assertSame(QrFileGathering.Assembled.Damaged, gathering.assemble())
    }

    @Test
    fun everySetSplitsIntoItsFields() {
        // A set byte that base45 would have written as ':' once broke the fields apart.
        for (b in 0..255) {
            val chunk = QrFile.cut("x", ByteArray(10), set = byteArrayOf(b.toByte(), b.toByte(), 0x3A, b.toByte())).single()
            assertEquals(1, QrFile.parse(chunk)?.count, chunk)
        }
        assertNull(QrFile.parse("UQR1:0a0b0c0d:1:3:BB8"), "lower case is not the set")
    }

    @Test
    fun anEmptyFileIsOneChunk() {
        val chunks = QrFile.cut("empty.txt", ByteArray(0))
        assertEquals(1, chunks.size)
        val file = assertIs<QrFileGathering.Assembled.Ok>(QrFileGathering(QrFile.parse(chunks.single())!!).assemble())
        assertEquals(0, file.bytes.size)
    }

    @Test
    fun namesLoseTheirPathAndControlCharacters() {
        assertEquals("passwd", QrFile.cleanName("../../etc/passwd"))
        assertEquals("evil.txt", QrFile.cleanName("C:\\x\\evil.txt"))
        assertEquals("ab", QrFile.cleanName("a\u0000\nb"))
        assertEquals("", QrFile.cleanName(".."))
        assertTrue(QrFile.cleanName("я".repeat(300)).encodeToByteArray().size <= 255)
    }

    @Test
    fun whatIsNotAChunkIsNotReadAsOne() {
        assertNull(QrFile.parse("https://m42.cx"))
        assertNull(QrFile.parse("UQR1:0A0B0C0D:0:3:BB8"))
        assertNull(QrFile.parse("UQR1:0A0B0C0D:4:3:BB8"))
        assertNull(QrFile.parse("UQR1:0A0B0C0D:1:3:"))
        assertNull(QrFile.parse("UQR1:0A0B0C0D:1:3:bb8"))
        assertNull(QrFile.parse("UQR1:0A0B0C:1:3:BB8"))
    }
}
