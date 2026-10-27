package cx.m42.unitool.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class MouseReportsTest {
    private fun read(text: String): Pair<List<Pair<Int, Int>>, List<MouseReports.Key>> {
        val points = mutableListOf<Pair<Int, Int>>()
        val reader = MouseReports { x, y -> points += x to y }
        val keys = text.encodeToByteArray().map { reader.feed(it.toInt() and 0xff) }.filter { it != MouseReports.Key.None }
        return points to keys
    }

    @Test
    fun sgrReportsAreCellsAndKeysAreKeys() {
        val (points, keys) = read("\u001b[<35;12;7M\u001b[<0;120;45m\u001b[<35;13;7Mx\u001b[A\r")
        assertEquals(listOf(12 to 7, 120 to 45, 13 to 7), points)
        assertEquals(listOf(MouseReports.Key.Enter), keys)
        assertEquals(listOf(MouseReports.Key.Cancel), read("\u0003").second)
    }

    @Test
    fun aBrokenReportIsLetGo() {
        val (points, _) = read("\u001b[<35;12M\u001b[<35;x;7M\u001b[<35;2;3M")
        assertEquals(listOf(2 to 3), points)
    }
}
