package cx.m42.apps.crnote

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

/** What is on disk (D653, D662, D664, D665): nothing open, nothing countable, nothing of another PIN touched. */
class NoteVaultTest {

    private val root = Random(7).nextBytes(32)
    private val sealed = Random(8).nextBytes(256)

    private fun note(text: String, at: Long = 1_000) = Note(NoteVault.hex(Random.nextBytes(8)), text, at, at)

    @Test
    fun aNewInstallIsTheSealedRootAndEightToTwelveDecoysOfOneBlock() = runBlocking {
        val files = MemoryFiles()
        NoteVault(files).start(sealed)
        val all = files.snapshot()
        assertContentEquals(sealed, all.getValue(NoteVault.KEY_FILE))
        val decoys = all - NoteVault.KEY_FILE
        assertTrue(decoys.size in 8..12, "${decoys.size} decoys")
        decoys.forEach { (name, bytes) ->
            assertTrue(Regex("^[0-9a-f]{32}$").matches(name), name)
            assertEquals(NoteVault.BLOCK, bytes.size)
        }
    }

    @Test
    fun anUnknownPinIsAnEmptyDirectoryAndLeavesNothing() = runBlocking {
        val files = MemoryFiles()
        val vault = NoteVault(files)
        vault.start(sealed)
        val before = files.snapshot().keys
        val (dir, notes) = vault.open(root, "123456")
        assertTrue(notes.isEmpty())
        assertFalse(dir.onDisk)
        assertEquals(before, files.snapshot().keys, "a typo in the PIN writes nothing (D653)")
    }

    @Test
    fun savedNotesComeBackWithTheirPinAndOnlyWithIt() = runBlocking {
        val files = MemoryFiles()
        val vault = NoteVault(files)
        vault.start(sealed)
        val (dir, _) = vault.open(root, "123456")
        val notes = listOf(note("Первая строка\nвторая"), note("секрет 42"))
        vault.save(dir, notes)

        assertEquals(notes, vault.open(root, "123456").second)
        assertTrue(vault.open(root, "123457").second.isEmpty())
        assertTrue(vault.open(Random(9).nextBytes(32), "123456").second.isEmpty(), "another root, another world")
    }

    @Test
    fun nothingOnDiskIsOpenAndEveryFileIsWholeBlocks() = runBlocking {
        val files = MemoryFiles()
        val vault = NoteVault(files)
        vault.start(sealed)
        val (dir, _) = vault.open(root, "246810")
        val long = "Совершенно секретно. ".repeat(2_000)
        vault.save(dir, listOf(note("Совершенно секретно"), note(long)))
        val all = files.snapshot()
        for ((name, bytes) in all) {
            if (name == NoteVault.KEY_FILE) continue
            assertEquals(0, bytes.size % NoteVault.BLOCK, "$name: ${bytes.size}")
            val text = bytes.decodeToString()
            for (needle in listOf("Совершенно", "секретно", "246810", "notes", "created")) {
                assertFalse(needle in text, "$name holds \"$needle\"")
            }
            val latin = String(bytes, Charsets.ISO_8859_1)
            assertFalse("246810" in latin)
        }
        assertTrue(all.getValue(dir.name).size > NoteVault.BLOCK, "a long note takes more blocks")
    }

    @Test
    fun aSaveTouchesItsOwnFileAndItsOwnDecoysOnly() = runBlocking {
        val files = MemoryFiles()
        val vault = NoteVault(files)
        vault.start(sealed)
        val (other, _) = vault.open(root, "111111")
        vault.save(other, listOf(note("другая директория")))
        val (mine, _) = vault.open(root, "222222")
        var dir = vault.save(mine, listOf(note("моя")))
        assertTrue(dir.decoys.size in 1..4, "${dir.decoys.size} own decoys (D665)")
        dir.decoys.forEach { assertEquals(files.snapshot().getValue(dir.name).size, files.snapshot().getValue(it).size) }

        repeat(20) { i ->
            val before = files.snapshot()
            dir = vault.save(dir, listOf(note("моя $i")))
            val after = files.snapshot()
            assertEquals(before.keys, after.keys, "no file comes or goes after the first save")
            val changed = after.keys.filter { !after.getValue(it).contentEquals(before.getValue(it)) }.toSet()
            assertTrue(dir.name in changed)
            assertTrue((changed - dir.name).size in 1..2, "one or two decoys refreshed: $changed")
            assertTrue((changed - dir.name).all { it in dir.decoys }, "only its own decoys")
        }
        assertEquals("другая директория", vault.open(root, "111111").second.single().text, "the other PIN's notes are whole")
    }

    @Test
    fun aDirectoryMovedUnderAnotherPinsNameDoesNotOpen() = runBlocking {
        val files = MemoryFiles()
        val vault = NoteVault(files)
        vault.start(sealed)
        val (a, _) = vault.open(root, "111111")
        vault.save(a, listOf(note("a")))
        val b = NoteVault.fresh(root, "222222")
        files.map[b.name] = files.map.getValue(a.name).copyOf()
        assertTrue(vault.open(root, "222222").second.isEmpty(), "the slot is the AAD (D662)")
    }

    @Test
    fun wipeAllLeavesNothing() = runBlocking {
        val files = MemoryFiles()
        val vault = NoteVault(files)
        vault.start(sealed)
        val (dir, _) = vault.open(root, "123456")
        vault.save(dir, listOf(note("x")))
        vault.wipeAll()
        assertTrue(files.snapshot().isEmpty())
        assertNull(vault.sealedRoot())
    }
}
