@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package cx.m42.apps.counters

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import cx.m42.superizer.testing.AppTestScope
import cx.m42.superizer.testing.runAppTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

class CountersInstanceTest {

    /** Buttons under the list move below the fold as rows are added; scroll to them first. */
    private fun AppTestScope<*>.tap(tag: String) {
        node(tag).performScrollTo()
        click(tag)
    }

    private fun AppTestScope<*>.newBoard(name: String, vararg players: String) {
        type("counters:new-name", name)
        click("counters:create")
        for (player in players) {
            node("counters:counter-name").performScrollTo()
            type("counters:counter-name", player)
            tap("counters:add")
        }
    }

    private val AppTestScope<*>.counters get() = instance as CountersInstance

    @Test
    fun anEmptyListSaysWhatToDo() = runAppTest(CountersApp()) {
        assertShown("counters:empty")
        assertAbsent("counters:archive")
        assertEquals(listOf("counters"), runtime.analytics.screens)
    }

    @Test
    fun aNewScoreOpensAndCountsUpAndDown() = runAppTest(CountersApp()) {
        newBoard("Манчкин", "Аня", "Боря")
        val board = counters.boards.value.single()
        assertEquals(Page.Open(board.id), counters.page.value)
        assertEquals("Манчкин", counters.chrome.value.title)

        click("counters:counter:0:plus")
        click("counters:counter:0:plus")
        click("counters:counter:1:minus")
        node("counters:counter:0:value").assertTextEquals("2")
        node("counters:counter:1:value").assertTextEquals("-1")

        val stored = runtime.storage.entries[CountersInstance.KEY_BOARDS]
        assertNotNull(stored)
        assertTrue("\"value\":2" in stored, stored)
    }

    @Test
    fun theScoresComeBackNextTime() {
        var stored: String? = null
        var saved: JsonObject? = null
        runAppTest(CountersApp()) {
            newBoard("Манчкин", "Аня")
            click("counters:counter:0:plus")
            stored = runtime.storage.entries[CountersInstance.KEY_BOARDS]
            saved = instance.saveState()
        }
        runAppTest(CountersApp(), restore = saved, prepare = { it.storage.entries[CountersInstance.KEY_BOARDS] = stored!! }) {
            assertTrue(counters.page.value is Page.Open)
            node("counters:counter:0:value").assertTextEquals("1")
        }
    }

    @Test
    fun theNewestScoreIsOnTop() = runAppTest(CountersApp()) {
        newBoard("Первая")
        tap("counters:back")
        newBoard("Вторая")
        tap("counters:back")
        click("counters:board:0")
        assertEquals("Вторая", counters.chrome.value.title)
    }

    @Test
    fun anArchivedScoreLeavesTheListAndComesBack() = runAppTest(CountersApp()) {
        newBoard("Старая", "Аня")
        tap("counters:edit")
        tap("counters:archive-toggle")
        tap("counters:back")
        assertEquals(Page.Archive, counters.page.value)
        tap("counters:back")
        assertShown("counters:empty")

        tap("counters:archive")
        click("counters:board:0")
        tap("counters:edit")
        tap("counters:archive-toggle")
        tap("counters:back")
        assertEquals(Page.List, counters.page.value)
        assertAbsent("counters:archive")
    }

    @Test
    fun namesAreChangedInEditAndABlankOneIsIgnored() = runAppTest(CountersApp()) {
        newBoard("Игра", "Аня")
        tap("counters:edit")
        node("counters:board-name").performTextReplacement("Игра, пятница")
        node("counters:counter:0:name").performTextReplacement("Аня!")
        idle()
        val board = counters.boards.value.single()
        assertEquals("Игра, пятница", board.name)
        assertEquals("Аня!", board.counters.single().name)
        assertEquals("Игра, пятница", counters.chrome.value.title)

        counters.renameCounter(board.id, board.counters.single().id, "   ")
        assertEquals("Аня!", counters.boards.value.single().counters.single().name)
    }

    @Test
    fun aCounterIsRemovedAndAScoreDeletedOnlyAfterConfirming() = runAppTest(CountersApp()) {
        newBoard("Игра", "Аня", "Боря")
        tap("counters:edit")
        tap("counters:counter:0:remove")
        assertEquals(listOf("Боря"), counters.boards.value.single().counters.map { it.name })

        tap("counters:delete")
        tap("counters:delete-cancel")
        assertEquals(1, counters.boards.value.size)
        tap("counters:delete")
        tap("counters:delete-confirm")
        assertTrue(counters.boards.value.isEmpty())
        assertEquals(Page.List, counters.page.value)
        assertEquals("[]", runtime.storage.entries[CountersInstance.KEY_BOARDS])
    }
}
