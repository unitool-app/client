package cx.m42.unitool.kit.crypt

import cx.m42.unitool.kit.crypto.Hash
import cx.m42.unitool.kit.crypto.hmac
import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.split.PartReading
import cx.m42.unitool.kit.split.SplitLink
import cx.m42.unitool.kit.split.SplitPart
import cx.m42.unitool.kit.split.SplitProblem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ucrypt core with the split recipient (.ai-notes/crypt 01, split 02 §4 and 03 §3), on every
 * target: the layout byte by byte, every part combination, every byte of tampering, the version
 * rules, and the armor.
 */
class UcryptTest {

    private fun open(file: ByteArray, parts: List<SplitPart>): ByteArray {
        val header = assertIs<Inspected.Ok>(Ucrypt.inspect(file)).header
        val unlocked = assertIs<Unlocked.Ok>(header.unlock(parts))
        return unlocked.decryptor.use { assertIs<Decrypted.Ok>(it.decrypt(file)).plaintext }
    }

    @Test
    fun anyKOfNPartsOpenTheFile() {
        val text = "Привет, split!\n".encodeToByteArray()
        for (n in 2..5) for (k in 2..n) {
            val sealed = Ucrypt.encryptSplit(text, k, n)
            assertEquals(n, sealed.parts.size)
            for (start in 0..n - k) {
                val chosen = sealed.parts.subList(start, start + k).reversed()
                assertContentEquals(text, open(sealed.file, chosen), "$k of $n from part ${start + 1}")
            }
            assertContentEquals(text, open(sealed.file, sealed.parts), "all $n")
        }
    }

    @Test
    fun theLayoutIsTheOneTheNotesDescribe() {
        val text = ByteArray(100) { it.toByte() }
        val file = Ucrypt.encryptSplit(text, 2, 3).file
        // preamble 84 · split record 6 + 10 · MAC 32 = 132; one chunk of 100 + 16.
        assertEquals(132 + 116, file.size)
        assertContentEquals(byteArrayOf(0x89.toByte(), 0x55, 0x43, 0x52, 0x0D, 0x0A, 0x1A, 0x0A), file.copyOf(8))
        assertEquals(listOf(1, 0), listOf(file[8].toInt(), file[9].toInt()))
        assertEquals(0, file.short(0x0A))
        assertEquals(132, file.int(0x0C).toInt())
        assertEquals(132, file.long(0x10))
        assertEquals(116, file.long(0x18))
        assertEquals(100, file.long(0x20))
        assertEquals(65_536, file.int(0x28).toInt())
        assertEquals(listOf(1, 1, 3, 256), listOf(0x2C, 0x2E, 0x30, 0x32).map { file.short(it) })
        assertEquals(0x0008, file.short(84))
        assertEquals(10, file.int(86).toInt())
        val header = assertIs<Inspected.Ok>(Ucrypt.inspect(file)).header
        val split = assertNotNull(header.split)
        assertEquals(listOf(2, 3), listOf(split.k, split.n))
        assertEquals(listOf(0x0008), header.recipientTypes)
        assertEquals(1, header.chunkCount)
        assertFalse(header.hasMeta)
        assertEquals(16, header.fileId.length)
    }

    @Test
    fun chunksFollowStreamAtEveryAwkwardLength() {
        val chunk = 4096
        for (size in listOf(0, 1, chunk - 1, chunk, chunk + 1, 3 * chunk, 3 * chunk + 17)) {
            val text = secureRandomBytes(size)
            val sealed = Ucrypt.encryptSplit(text, 2, 2, Meta(), chunk)
            val count = Ucrypt.chunkCount(size.toLong(), chunk)
            assertEquals(maxOf(1, (size + chunk - 1) / chunk).toLong(), count)
            assertEquals(132 + size + 16 * count, sealed.file.size.toLong(), "size $size")
            assertContentEquals(text, open(sealed.file, sealed.parts), "size $size")
        }
    }

    @Test
    fun theMetadataTravelsUnderItsOwnKey() {
        val meta = Meta(name = "отчёт.pdf", modified = 1_760_000_000, mode = "640".toInt(8))
        val sealed = Ucrypt.encryptSplit("x".encodeToByteArray(), 2, 2, meta)
        assertFalse("отчёт".encodeToByteArray().let { needle -> sealed.file.toList().windowed(needle.size).any { it == needle.toList() } }, "the name is in the clear")
        val header = assertIs<Inspected.Ok>(Ucrypt.inspect(sealed.file)).header
        assertTrue(header.hasMeta)
        val opened = assertIs<Unlocked.Ok>(header.unlock(sealed.parts)).decryptor
        assertEquals("отчёт.pdf", opened.meta.name)
        assertEquals(1_760_000_000, opened.meta.modified)
        assertEquals("640".toInt(8), opened.meta.mode)
        for (bad in listOf("", ".", "..", "a/b", "a\u0000b", "x".repeat(256))) {
            assertFailsWith<IllegalArgumentException>(bad) { Meta(name = bad) }
        }
        assertFailsWith<IllegalArgumentException> { Meta(mode = "1777".toInt(8)) }
    }

    @Test
    fun partsThatCannotOpenSayWhy() {
        val sealed = Ucrypt.encryptSplit("secret".encodeToByteArray(), 3, 5)
        val other = Ucrypt.encryptSplit("other".encodeToByteArray(), 3, 5)
        val header = assertIs<Inspected.Ok>(Ucrypt.inspect(sealed.file)).header
        val few = assertIs<SplitProblem.TooFew>(assertIs<Unlocked.Parts>(header.unlock(sealed.parts.take(2))).problem)
        assertEquals(2 to 3, few.have to few.need)
        assertIs<SplitProblem.OtherSet>(assertIs<Unlocked.Parts>(header.unlock(sealed.parts.take(2) + other.parts[0])).problem)
        assertIs<SplitProblem.SameNumber>(assertIs<Unlocked.Parts>(header.unlock(sealed.parts.take(2) + sealed.parts[1])).problem)

        // A forged share whose check is right: the key it makes fails the MAC.
        val forged = assertIs<PartReading.Ok>(SplitPart.read(forge(sealed.parts[2].bytes()))).part
        assertEquals(Unlocked.DoNotMatch, header.unlock(sealed.parts.take(2) + forged))
        // With one part more than it takes, the forged one is left out and named.
        val found = assertIs<Unlocked.Ok>(header.unlock(listOf(sealed.parts[0], forged, sealed.parts[1], sealed.parts[3])))
        assertEquals(listOf(1), found.disagreeing)
        assertContentEquals("secret".encodeToByteArray(), assertIs<Decrypted.Ok>(found.decryptor.decrypt(sealed.file)).plaintext)
    }

    @Test
    fun noChangeToAnyByteOfTheHeaderOpens() {
        val sealed = Ucrypt.encryptSplit("header".encodeToByteArray(), 2, 3, Meta(name = "a.txt"))
        val headerSize = assertIs<Inspected.Ok>(Ucrypt.inspect(sealed.file)).header.headerSize
        for (i in 0 until headerSize) {
            val changed = sealed.file.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            when (val inspected = Ucrypt.inspect(changed)) {
                is Inspected.Ok -> {
                    val unlocked = inspected.header.unlock(sealed.parts)
                    assertTrue(unlocked !is Unlocked.Ok, "byte $i changed and the file still opened")
                }
                else -> Unit
            }
        }
    }

    @Test
    fun noChangeToTheContentGoesUnnoticed() {
        val sealed = Ucrypt.encryptSplit(secureRandomBytes(3 * 4096 + 5), 2, 2, Meta(), 4096)
        val header = assertIs<Inspected.Ok>(Ucrypt.inspect(sealed.file)).header
        val decryptor = assertIs<Unlocked.Ok>(header.unlock(sealed.parts)).decryptor
        for (i in header.headerSize until sealed.file.size step 97) {
            val changed = sealed.file.copyOf().also { it[i] = (it[i].toInt() xor 0x80).toByte() }
            assertIs<Decrypted.BadChunk>(decryptor.decrypt(changed), "byte $i")
        }
        assertIs<Decrypted.WrongLength>(decryptor.decrypt(sealed.file.copyOf(sealed.file.size - 1)))
        assertIs<Decrypted.WrongLength>(decryptor.decrypt(sealed.file + 0))
        // Chunks 0 and 1 swapped: each fails, being in the other's place.
        val c0 = decryptor.location(0)
        val c1 = decryptor.location(1)
        val swapped = sealed.file.copyOf()
        sealed.file.copyInto(swapped, c0.first.toInt(), c1.first.toInt(), c1.last.toInt() + 1)
        sealed.file.copyInto(swapped, c1.first.toInt(), c0.first.toInt(), c0.last.toInt() + 1)
        assertEquals(0, assertIs<Decrypted.BadChunk>(decryptor.decrypt(swapped)).index)
        // A chunk read at another index fails: its number is in its nonce.
        val chunk1 = sealed.file.copyOfRange(c1.first.toInt(), c1.last.toInt() + 1)
        assertNotNull(decryptor.chunk(1, chunk1))
        assertNull(decryptor.chunk(2, chunk1))
    }

    @Test
    fun versionsFollowTheRules() {
        val file = Ucrypt.encryptSplit("v".encodeToByteArray(), 2, 2).file
        fun with(edit: (ByteArray) -> Unit) = file.copyOf().also(edit)

        assertEquals(Inspected.NotUcrypt, Ucrypt.inspect("hello".encodeToByteArray()))
        val major = assertIs<Inspected.Newer>(Ucrypt.inspect(with { it[8] = 2 }))
        assertEquals(2, major.major)
        assertIs<Inspected.Damaged>(Ucrypt.inspect(with { it[8] = 0 }))
        // A newer minor's unknown flag is a missing feature; in 1.0 it is damage.
        assertIs<Inspected.Newer>(Ucrypt.inspect(with { it[9] = 1; it[0x0B] = 1 }))
        assertIs<Inspected.Damaged>(Ucrypt.inspect(with { it[0x0B] = 1 }))
        assertIs<Inspected.Newer>(Ucrypt.inspect(with { it[9] = 1; it[0x31] = 9 }))
        assertIs<Inspected.Damaged>(Ucrypt.inspect(with { it[0x31] = 9 }))

        // An extra record: skipped from a newer minor, refused when critical, damage in 1.0.
        val extra = withRecord(file, 0x0042, byteArrayOf(1, 2, 3), minor = 1)
        assertEquals(listOf(0x0042), assertIs<Inspected.Ok>(Ucrypt.inspect(extra)).header.skippedTypes)
        assertEquals("record type 0x8042", assertIs<Inspected.Newer>(Ucrypt.inspect(withRecord(file, 0x8042, ByteArray(0), minor = 1))).missing)
        assertIs<Inspected.Damaged>(Ucrypt.inspect(withRecord(file, 0x0042, ByteArray(0), minor = 0)))
        // An SSH recipient is known: the file is read, and said to be for keys.
        val ssh = withRecord(file, 0x0001, ByteArray(112), minor = 0)
        assertEquals(listOf(0x0008, 0x0001), assertIs<Inspected.Ok>(Ucrypt.inspect(ssh)).header.recipientTypes)

        assertIs<Inspected.Damaged>(Ucrypt.inspect(file.copyOf(100)))
        assertIs<Inspected.Damaged>(Ucrypt.inspect(with { it.putInt(0x0C, 115) }))
        assertIs<Inspected.Damaged>(Ucrypt.inspect(with { it.putInt(0x28, 65_535) }))
    }

    @Test
    fun aFileForSshKeysIsNotOpenedByParts() {
        val sealed = Ucrypt.encryptSplit("v".encodeToByteArray(), 2, 2)
        val only = withRecord(sealed.file, 0x0001, ByteArray(112), minor = 0, dropSplit = true)
        val header = assertIs<Inspected.Ok>(Ucrypt.inspect(only)).header
        assertNull(header.split)
        assertEquals(Unlocked.NotSplit, header.unlock(sealed.parts))
    }

    @Test
    fun theArmorIsCanonicalPem() {
        val file = Ucrypt.encryptSplit(secureRandomBytes(300), 2, 3).file
        val armor = Ucrypt.armor(file)
        val lines = armor.trimEnd().lines()
        assertEquals("-----BEGIN UCRYPT FILE-----", lines.first())
        assertEquals("-----END UCRYPT FILE-----", lines.last())
        assertTrue(lines.subList(1, lines.size - 2).all { it.length == 64 })
        assertTrue(Ucrypt.isArmored(("\n  " + armor).encodeToByteArray()))
        assertFalse(Ucrypt.isArmored(file))
        assertContentEquals(file, Ucrypt.dearmor(armor.encodeToByteArray()))
        assertContentEquals(file, Ucrypt.dearmor(armor.replace("\n", "\r\n").encodeToByteArray()))
        assertNull(Ucrypt.dearmor(armor.replaceFirst("A", "*").encodeToByteArray()))
        assertNull(Ucrypt.dearmor(armor.replace("-----END UCRYPT FILE-----", "").encodeToByteArray()))
    }

    @Test
    fun partsTravelAsLinksAndStillOpen() {
        val sealed = Ucrypt.encryptSplit("by link".encodeToByteArray(), 2, 3)
        val links = sealed.parts.map { SplitLink.format(it, "https://example.com/p") }
        val back = links.drop(1).map { assertIs<PartReading.Ok>(SplitLink.read(it)).part }
        assertContentEquals("by link".encodeToByteArray(), open(sealed.file, back))
    }

    /**
     * A file frozen when 1.0 was written, with two of its three parts. Every later reader must open
     * it (crypt/01 §5.2: reference files are kept forever).
     */
    @Test
    fun theReferenceFileOfVersion10StillOpens() {
        val file = assertNotNull(Ucrypt.dearmor(REFERENCE_1_0.encodeToByteArray()))
        val parts = REFERENCE_1_0_PARTS.map { assertIs<PartReading.Ok>(SplitLink.read(it)).part }
        val header = assertIs<Inspected.Ok>(Ucrypt.inspect(file)).header
        val decryptor = assertIs<Unlocked.Ok>(header.unlock(parts)).decryptor
        assertEquals("reference.txt", decryptor.meta.name)
        assertEquals(REFERENCE_1_0_TEXT, assertIs<Decrypted.Ok>(decryptor.decrypt(file)).plaintext.decodeToString())
    }

    /** The share's bytes changed and the check recomputed: a part that reads, and lies. */
    private fun forge(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf()
        out[20] = (out[20].toInt() xor 0x55).toByte()
        Hash.SHA256.digest("ucrypt/1 split part".encodeToByteArray() + out.copyOf(44)).copyOf(4).copyInto(out, 44)
        return out
    }

    /**
     * [file]'s header with one more record (and, with [dropSplit], without the split record), at
     * version 1.[minor]. The MAC is recomputed under a key nobody has: enough for [Ucrypt.inspect],
     * which never checks it.
     */
    private fun withRecord(file: ByteArray, type: Int, body: ByteArray, minor: Int, dropSplit: Boolean = false): ByteArray {
        val headerSize = file.int(0x0C).toInt()
        val oldRecords = file.copyOfRange(84, headerSize - 32)
        val kept = if (dropSplit) ByteArray(0) else oldRecords
        val record = ByteArray(6 + body.size).also { it.putShort(0, type); it.putInt(2, body.size); body.copyInto(it, 6) }
        val records = kept + record
        val newHeaderSize = 84 + records.size + 32
        val preamble = file.copyOf(84)
        preamble[9] = minor.toByte()
        preamble.putInt(0x0C, newHeaderSize)
        preamble.putLong(0x10, newHeaderSize.toLong())
        preamble.putShort(0x2C, if (dropSplit) 1 else 2)
        val head = preamble + records
        return head + hmac(Hash.SHA256, ByteArray(32), head) + file.copyOfRange(headerSize, file.size)
    }
}

/** Written by ucrypt 1.0 on 2026-10-08: `reference.txt`, 2 of 3. Never regenerate it; add a new one. */
private const val REFERENCE_1_0_TEXT = "ucrypt 1.0, split 2 of 3.\nПривет из 2026-10-08.\n"

private val REFERENCE_1_0 = """
-----BEGIN UCRYPT FILE-----
iVVDUg0KGgoBAAAAAAAAugAAAAAAAAC6AAAAAAAAAEgAAAAAAAAAOAABAAAAAgAB
AAMBAJ6652bS0Zk1cXatvEZEt8eRK3pT/mSKSZxnSaOq/8N2AAgAAAAKSdfSm/+T
r4ICAwADAAAAMMRFp1dBWb3FvFxiEfOb6ju3UZtasRb6hnQVfFwg9X54VLyYGbhP
VqZJxlgzxc0XO1QnzaK4XQRqM6qY6EruqGu9ouY+9iNedidob51WsIVqBk39Iqhf
TzPipqsw+zeFrQJ2+FbyoUHooQBtc1PC3ztkHQl0gFXOtcArFg6syQCpjaCip47n
tGFgAoMCFulgApkaOF76XL+/
-----END UCRYPT FILE-----
""".trimIndent()

/** Parts 1 and 3; part 2 stays out, as a part that was lost. */
private val REFERENCE_1_0_PARTS = listOf(
    "https://example.com/p#AUnX0pv_k6-CAgMBGiRj9uJla3l-Ypg5dDr30eHeJRlCggNG3l7hBY-pqnE22AmF",
    "https://example.com/p#AUnX0pv_k6-CAgMD4giMtpblEFUP4857MgNpCh8HN3enByGxhP9Gt1TFGmoBpmf5",
)
