package cx.m42.unitool.kit.password

import cx.m42.unitool.kit.crypto.hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EntropyPoolTest {
    private val zeros: (Int) -> ByteArray = { ByteArray(it) }

    private fun drawn(points: List<Pair<Int, Int>>) = EntropyPool().apply {
        points.forEachIndexed { i, (x, y) -> add(x, y, i * 16_000_000L) }
    }

    @Test
    fun aStraightLineOrAStillHandCountsNothing() {
        assertEquals(0.0, drawn(List(500) { it to it * 2 }).bits)
        val still = drawn(List(500) { 7 to 7 })
        assertEquals(0.0, still.bits)
        assertEquals(500, still.samples)
    }

    @Test
    fun aWobblyLineCountsAtMostOneBitAPoint() {
        val wobbly = List(1000) { i -> (i * 3 + (i * i * 7919) % 23) to ((i * 31337) % 97) }
        val pool = drawn(wobbly)
        assertTrue(pool.bits > 100, "${pool.bits}")
        assertTrue(pool.bits <= 998, "${pool.bits}")
        assertTrue(drawn(wobbly).full == (pool.bits >= EntropyPool.TARGET_BITS))
    }

    @Test
    fun theSourceDependsOnTheDrawingAndOnTheSystem() {
        val a = drawn(listOf(1 to 2, 5 to 9, 3 to 3)).source(zeros)(64)
        val same = drawn(listOf(1 to 2, 5 to 9, 3 to 3)).source(zeros)(64)
        val other = drawn(listOf(1 to 2, 5 to 9, 3 to 4)).source(zeros)(64)
        assertEquals(a.hex(), same.hex(), "the same points and the same system bytes")
        assertNotEquals(a.hex(), other.hex(), "one point moved")
        val salted = drawn(listOf(1 to 2, 5 to 9, 3 to 3)).source { ByteArray(it) { 1 } }(64)
        assertNotEquals(a.hex(), salted.hex(), "other system bytes")

        val source = drawn(listOf(1 to 2)).source(zeros)
        assertNotEquals(source(64).hex(), source(64).hex(), "every request is new")
    }

    @Test
    fun withTheRealSystemTwoPasswordsDiffer() {
        val pool = drawn(listOf(1 to 2, 5 to 9))
        val rules = PasswordRules()
        assertNotEquals(PasswordGenerator.generate(rules, pool.source()), PasswordGenerator.generate(rules, pool.source()))
    }

    @Test
    fun clearForgets() {
        val pool = drawn(List(300) { i -> (i * i % 17) to (i * 7 % 13) })
        val before = pool.source(zeros)(32).hex()
        pool.clear()
        assertEquals(0, pool.samples)
        assertEquals(0.0, pool.bits)
        assertFalse(pool.full)
        assertNotEquals(before, pool.source(zeros)(32).hex())
    }
}
