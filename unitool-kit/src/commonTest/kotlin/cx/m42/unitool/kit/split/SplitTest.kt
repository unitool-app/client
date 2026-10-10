package cx.m42.unitool.kit.split

import cx.m42.unitool.kit.crypto.SecretBytes
import cx.m42.unitool.kit.crypto.secureRandomBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shamir, the part's form and the link (.ai-notes/done/split 03 §3), on every target the kit builds for.
 * The field is checked against a table built a different way; the sharing against an evaluation
 * written separately; secrecy by sweeping every coefficient and counting.
 */
class SplitTest {

    /** GF(2⁸) by logarithms over the generator 3: a second, independent way to multiply. */
    private object Table {
        val exp = IntArray(510)
        val log = IntArray(256)

        init {
            var x = 1
            for (i in 0 until 255) {
                exp[i] = x
                log[x] = i
                x = x xor (x shl 1) xor (if (x and 0x80 != 0) 0x11b else 0)
                x = x and 0xff
            }
            for (i in 255 until 510) exp[i] = exp[i - 255]
        }

        fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

        /** f(x) for coefficients c[0] + c[1]x + …, power by power rather than Horner. */
        fun evaluate(c: List<Int>, x: Int): Int {
            var y = 0
            var power = 1
            for (coefficient in c) {
                y = y xor mul(coefficient, power)
                power = mul(power, x)
            }
            return y
        }
    }

    @Test
    fun fieldMultiplicationMatchesTheLogTableForEveryPair() {
        for (a in 0..255) for (b in 0..255) assertEquals(Table.mul(a, b), Gf256.mul(a, b), "$a · $b")
        for (a in 1..255) assertEquals(1, Gf256.mul(a, Gf256.inverse(a)), "$a · $a⁻¹")
        assertFailsWith<IllegalArgumentException> { Gf256.inverse(0) }
    }

    @Test
    fun partsAreThePolynomialsAtOneToN() {
        val secret = secureRandomBytes(32)
        val coefficients = List(2) { secureRandomBytes(32) }
        var next = 0
        // Copies: split zeroes its coefficients when done, as it should.
        val parts = Shamir.split(secret, k = 3, n = 5) { coefficients[next++].copyOf() }
        for (x in 1..5) for (j in 0 until 32) {
            val c = listOf(secret[j], coefficients[0][j], coefficients[1][j]).map { it.toInt() and 0xff }
            assertEquals(Table.evaluate(c, x), parts[x - 1][j].toInt() and 0xff, "x=$x byte $j")
        }
    }

    @Test
    fun everyKOfNPartsGiveTheSecretBack() {
        for (n in 2..5) for (k in 2..n) {
            val secret = secureRandomBytes(32)
            val ys = Shamir.split(secret, k, n)
            for (subset in subsets(n)) {
                if (subset.size < k) continue
                val xs = subset.map { it + 1 }.toIntArray()
                assertContentEquals(secret, Shamir.combine(xs, subset.map { ys[it] }), "$k of $n from $subset")
            }
        }
    }

    /**
     * Secrecy, exactly: fix the secret, run through every value of the random coefficients, and
     * count. k − 1 parts must take every possible value equally often, so they say nothing.
     */
    @Test
    fun fewerThanKPartsAreUniformWhateverTheSecret() {
        for (secret in listOf(0, 1, 0x5a, 0xff)) {
            for (n in listOf(2, 3, 5)) {
                // 2 of n: one part alone, over every coefficient a.
                val seen = Array(n) { IntArray(256) }
                for (a in 0..255) {
                    val ys = Shamir.split(byteArrayOf(secret.toByte()), 2, n) { byteArrayOf(a.toByte()) }
                    ys.forEachIndexed { i, y -> seen[i][y[0].toInt() and 0xff]++ }
                }
                seen.forEach { counts -> assertTrue(counts.all { it == 1 }, "2 of $n, secret $secret") }
            }
            // 3 of 5: any two parts together, over every pair of coefficients.
            val pairs = IntArray(65_536)
            for (a in 0..255) for (b in 0..255) {
                val coefficients = listOf(a, b)
                var next = 0
                val ys = Shamir.split(byteArrayOf(secret.toByte()), 3, 5) { byteArrayOf(coefficients[next++].toByte()) }
                pairs[((ys[1][0].toInt() and 0xff) shl 8) or (ys[4][0].toInt() and 0xff)]++
            }
            assertTrue(pairs.all { it == 1 }, "parts 2 and 5 of 3-of-5, secret $secret")
        }
    }

    @Test
    fun splitRefusesWhatIsNotAThreshold() {
        val secret = ByteArray(32)
        assertFailsWith<IllegalArgumentException> { Shamir.split(secret, 1, 3) }
        assertFailsWith<IllegalArgumentException> { Shamir.split(secret, 4, 3) }
        assertFailsWith<IllegalArgumentException> { Shamir.split(secret, 2, 256) }
        assertFailsWith<IllegalArgumentException> { Shamir.combine(intArrayOf(1, 1), listOf(secret, secret)) }
        assertFailsWith<IllegalArgumentException> { Shamir.combine(intArrayOf(0, 1), listOf(secret, secret)) }
    }

    @Test
    fun aPartIsFortyEightBytesAndReadsBack() {
        val parts = make(3, 5)
        parts.forEach { part ->
            val bytes = part.bytes()
            assertEquals(48, bytes.size)
            assertEquals(1, bytes[0].toInt())
            val back = assertIs<PartReading.Ok>(SplitPart.read(bytes)).part
            assertEquals(part.setIdHex, back.setIdHex)
            assertEquals(listOf(3, 5, part.x), listOf(back.k, back.n, back.x))
            assertContentEquals(bytes, back.bytes())
        }
        assertEquals(1, parts.map { it.setIdHex }.toSet().size)
        assertEquals(16, parts.first().setIdHex.length)
        assertEquals(listOf(1, 2, 3, 4, 5), parts.map { it.x })
    }

    @Test
    fun noChangeToAnyByteOfAPartReadsAsAPart() {
        val bytes = make(2, 3).first().bytes()
        for (i in bytes.indices) for (flip in listOf(0x01, 0x80, 0xff)) {
            val changed = bytes.copyOf().also { it[i] = (it[i].toInt() xor flip).toByte() }
            val reading = SplitPart.read(changed)
            assertTrue(reading !is PartReading.Ok, "byte $i xor $flip read as a part")
            if (i == 0) assertIs<PartReading.Unreadable>(reading) else assertIs<PartReading.Damaged>(reading)
        }
        assertIs<PartReading.NotAPart>(SplitPart.read(bytes.copyOf(47)))
    }

    @Test
    fun aLinkCarriesThePartInItsFragmentOnAnyAddress() {
        val part = make(2, 3).first()
        for (base in listOf("https://example.com/p", "http://ex.com", "https://ex.com/a/b?ref=1&x=2", "HTTPS://Example.COM/x")) {
            val link = SplitLink.format(part, base)
            assertTrue(link.startsWith("$base#"), link)
            assertEquals(base.length + 1 + 64, link.length)
            for (text in listOf(link, "  $link\n", "$link\r\n", link.substringAfter('#'))) {
                val back = assertIs<PartReading.Ok>(SplitLink.read(text), text).part
                assertContentEquals(part.bytes(), back.bytes())
            }
        }
        val fragment = SplitLink.format(part, "https://ex.com/p").substringAfter('#')
        assertTrue(fragment.all { it.isLetterOrDigit() || it == '-' || it == '_' }, fragment)
    }

    @Test
    fun noChangeToAnyCharacterOfALinkReadsAsAPart() {
        val link = SplitLink.format(make(2, 2).first(), "https://ex.com/p")
        val start = link.indexOf('#') + 1
        for (i in start until link.length) {
            val other = if (link[i] == 'A') 'B' else 'A'
            val changed = link.substring(0, i) + other + link.substring(i + 1)
            assertTrue(SplitLink.read(changed) !is PartReading.Ok, "character ${i - start} changed")
        }
        assertIs<PartReading.NotAPart>(SplitLink.read(link.dropLast(1)))
        assertIs<PartReading.NotAPart>(SplitLink.read(link + "A"))
        assertIs<PartReading.NotAPart>(SplitLink.read("https://example.com/page#section-2"))
        assertIs<PartReading.NotAPart>(SplitLink.read("https://example.com/" + link.substringAfter('#')))
        assertIs<PartReading.NotAPart>(SplitLink.read(""))
    }

    @Test
    fun anAddressMustBeAbsoluteHttpWithoutAFragment() {
        assertNull(SplitLink.checkBase("https://example.com/p"))
        assertNull(SplitLink.checkBase("http://example.com"))
        assertNull(SplitLink.checkBase("https://example.com/p?a=1"))
        assertEquals(BaseProblem.NotHttp, SplitLink.checkBase("example.com/p"))
        assertEquals(BaseProblem.NotHttp, SplitLink.checkBase("ftp://example.com/p"))
        assertEquals(BaseProblem.NoHost, SplitLink.checkBase("https:///p"))
        assertEquals(BaseProblem.Fragment, SplitLink.checkBase("https://example.com/p#x"))
        assertEquals(BaseProblem.Spaces, SplitLink.checkBase("https://example.com/a b"))
        assertFailsWith<IllegalArgumentException> { SplitLink.format(make(2, 2).first(), "example.com") }
    }

    @Test
    fun theRulesSayWhatIsWrongWithASetOfParts() {
        val parts = make(2, 3)
        val other = make(2, 3)
        val id = parts.first().setId
        assertNull(SplitPlan.check(id, 2, 3, parts.take(2)))
        assertNull(SplitPlan.check(id, 2, 3, parts))
        val few = assertIs<SplitProblem.TooFew>(SplitPlan.check(id, 2, 3, parts.take(1)))
        assertEquals(1 to 2, few.have to few.need)
        val foreign = assertIs<SplitProblem.OtherSet>(SplitPlan.check(id, 2, 3, listOf(parts[0], other[1])))
        assertEquals(1, foreign.index)
        assertEquals(other[1].setIdHex, foreign.setId)
        val twice = assertIs<SplitProblem.SameNumber>(SplitPlan.check(id, 2, 3, listOf(parts[1], parts[1])))
        assertEquals(listOf(1, 0, 2), listOf(twice.index, twice.earlier, twice.x))
        assertIs<SplitProblem.Inconsistent>(SplitPlan.check(id, 3, 3, parts.take(2)))

        assertEquals(2..5, SplitPlan.PARTS)
        assertEquals(2..4, SplitPlan.thresholds(4))
        assertEquals(1, SplitPlan.meaning(3, 2).mayLose)
        assertEquals(0, SplitPlan.meaning(2, 2).mayLose)
        assertFailsWith<IllegalArgumentException> { SplitPlan.meaning(6, 2) }
    }

    @Test
    fun joinGivesTheKeyAndSearchFindsTheForgedPart() {
        SecretBytes(secureRandomBytes(32)).use { key ->
            val parts = Split.make(key, 2, 3)
            val joined = assertIs<Split.Joined.Ok>(Split.join(parts[0].setId, 2, 3, listOf(parts[2], parts[0])))
            assertContentEquals(key.bytes, joined.key.bytes)

            // A forged share with a correct check: only the key it gives can tell.
            val forged = SplitPart(parts[1].setId, 2, 3, 2, SecretBytes(secureRandomBytes(32)))
            assertIs<PartReading.Ok>(SplitPart.read(forged.bytes()))
            val found = assertNotNull(Split.search(2, listOf(parts[0], forged, parts[2])) { it.bytes.contentEquals(key.bytes) })
            assertContentEquals(key.bytes, found.key.bytes)
            assertEquals(listOf(1), found.disagreeing)
            assertNull(Split.search(2, listOf(parts[0], forged)) { it.bytes.contentEquals(key.bytes) })
        }
    }

    private fun make(k: Int, n: Int): List<SplitPart> = SecretBytes(secureRandomBytes(32)).use { Split.make(it, k, n) }

    private fun subsets(n: Int): List<List<Int>> = (1 until (1 shl n)).map { mask -> (0 until n).filter { mask and (1 shl it) != 0 } }
}
