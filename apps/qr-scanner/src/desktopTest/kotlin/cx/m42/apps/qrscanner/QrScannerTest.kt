package cx.m42.apps.qrscanner

import cx.m42.services.files.FileSaver
import cx.m42.services.qr.QrScanner
import cx.m42.services.qr.SeriesStep
import cx.m42.superizer.testing.AppTestScope
import cx.m42.superizer.testing.runAppTest
import cx.m42.unitool.kit.qr.QrFile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The screen on fakes (D636): a code to its kind, binary to hex, a series of chunks to a saved file; nothing kept. */
class QrScannerTest {

    private class FakeSaver : FileSaver {
        var name: String? = null
        var bytes: ByteArray? = null
        override suspend fun save(suggestedName: String, bytes: ByteArray, mimeType: String): Boolean {
            name = suggestedName
            this.bytes = bytes.copyOf()
            return true
        }
    }

    /** A camera that reads [codes] in order, and in a series hands them all over while it stays open. */
    private class Camera(vararg codes: String) : QrScanner {
        val queue = ArrayDeque(codes.toList())
        val labels = mutableListOf<String?>()
        var series = 0

        override suspend fun scan(): String? = queue.removeFirstOrNull()

        override suspend fun scanSeries(hint: String?, onCode: (String) -> SeriesStep): Boolean {
            series++
            labels += hint
            while (true) {
                val code = queue.removeFirstOrNull() ?: return false
                when (val step = onCode(code)) {
                    SeriesStep.Done -> return true
                    is SeriesStep.Continue -> labels += step.label
                }
            }
        }
    }

    private val saver = FakeSaver()

    private fun AppTestScope<*>.app() = instance as QrScannerInstance

    private fun run(camera: QrScanner? = null, block: AppTestScope<Unit>.() -> Unit) = runAppTest(QrScannerApp(), prepare = {
        it.provide(FileSaver.Key, saver)
        camera?.let { c -> it.provide(QrScanner.Key, c) }
    }, block = block)

    private fun AppTestScope<*>.assertNothingKept() {
        assertTrue(runtime.storage.entries.isEmpty(), "nothing in storage: ${runtime.storage.entries.keys}")
        assertNull(instance.saveState())
    }

    @Test
    fun withoutACameraOnlyPasteIsOffered() = run {
        assertShown("qr-scanner:empty")
        assertShown("qr-scanner:paste")
        assertAbsent("qr-scanner:scan")
    }

    @Test
    fun aLinkIsShownWithOpenCopyAndSave() = run(Camera("https://m42.cx/unitool")) {
        click("qr-scanner:scan")
        assertShown("qr-scanner:kind")
        assertShown("qr-scanner:open")
        assertEquals(Content.Link("https://m42.cx/unitool"), (app().result.value as Result.Single).content)
        click("qr-scanner:copy")
        assertShown("qr-scanner:notice")
        assertEquals("https://m42.cx/unitool", app().copyText())
        click("qr-scanner:save")
        assertEquals("https://m42.cx/unitool", saver.bytes!!.decodeToString())
        assertTrue(saver.name!!.startsWith("qr-") && saver.name!!.endsWith(".txt"), saver.name)
        assertNothingKept()
    }

    @Test
    fun textCanBeShownAsHexAndBack() = run(Camera("Привет")) {
        click("qr-scanner:scan")
        assertShown("qr-scanner:text")
        click("qr-scanner:hex")
        assertShown("qr-scanner:hex-dump")
        click("qr-scanner:hex")
        assertShown("qr-scanner:text")
    }

    @Test
    fun bytesThatAreNotTextAreHexAndCopyAsHex() = run(Camera("a\u0000b\u0001")) {
        click("qr-scanner:scan")
        assertShown("qr-scanner:hex-dump")
        assertAbsent("qr-scanner:hex")
        assertEquals("61006201", app().copyText())
        click("qr-scanner:save")
        assertTrue(saver.name!!.endsWith(".bin"), saver.name)
    }

    @Test
    fun oneMoreCodeJoinsOnANewLineAndBase64Decodes() = run(Camera("aGVs", "bG8=")) {
        click("qr-scanner:scan")
        click("qr-scanner:more")
        val joined = assertIs<Result.Single>(app().result.value)
        assertEquals("aGVs\nbG8=", joined.text)
        assertEquals(2, joined.codes)
        click("qr-scanner:decode")
        val file = assertIs<Result.File>(app().result.value)
        assertEquals("hello", file.text)
    }

    @Test
    fun aFileIsGatheredInAnyOrderInOneSeriesAndSaved() {
        val bytes = Random(4).nextBytes(4000)
        val chunks = QrFile.cut("photo.jpg", bytes, chunk = 500)
        val order = chunks.shuffled(Random(9))
        // A repeat and a stray code in the middle change nothing.
        val camera = Camera(*(order.take(3) + order[1] + "https://not.a.chunk" + order.drop(3)).toTypedArray())
        run(camera) {
            click("qr-scanner:scan")
            assertEquals(1, camera.series, "one camera session for every chunk")
            val file = assertIs<Result.File>(app().result.value)
            assertEquals("photo.jpg", file.name)
            assertContentEquals(bytes, file.bytes)
            assertShown("qr-scanner:hex-dump")
            assertEquals("1 из ${chunks.size}", camera.labels[1])
            click("qr-scanner:save")
            assertEquals("photo.jpg", saver.name)
            assertContentEquals(bytes, saver.bytes)
            assertNothingKept()
        }
    }

    @Test
    fun anInterruptedSeriesShowsWhatIsMissingAndContinues() {
        val chunks = QrFile.cut("a.txt", "строка\n".repeat(300).encodeToByteArray(), chunk = 200)
        val camera = Camera(chunks[0], chunks[2])
        run(camera) {
            click("qr-scanner:scan")
            val gathering = assertIs<Result.Gathering>(app().result.value).gathering
            assertEquals(2, gathering.have)
            assertShown("qr-scanner:progress")
            assertShown("qr-scanner:grid")
            assertShown("qr-scanner:missing")
            assertEquals("a.txt", gathering.name)

            camera.queue.addAll(chunks.filterIndexed { i, _ -> i != 0 && i != 2 })
            click("qr-scanner:continue")
            val file = assertIs<Result.File>(app().result.value)
            assertEquals("строка\n".repeat(300), file.text)
            assertShown("qr-scanner:text")
        }
    }

    @Test
    fun aChunkOfAnotherFileIsSkippedAndSaid() {
        val one = QrFile.cut("one", ByteArray(600), chunk = 200)
        val two = QrFile.cut("two", ByteArray(600), chunk = 200)
        run(Camera(one[0], two[1])) {
            click("qr-scanner:scan")
            assertShown("qr-scanner:other-file")
            assertEquals(1, assertIs<Result.Gathering>(app().result.value).gathering.have)
            click("qr-scanner:again")
            assertShown("qr-scanner:empty")
        }
    }

    @Test
    fun pastedWifiShowsItsFields() = run {
        app().paste("WIFI:T:WPA;S:Home;P:secret;;")
        idle()
        assertShown("qr-scanner:password")
        assertShown("qr-scanner:copy-password")
        assertEquals(Content.Wifi("Home", "WPA", "secret", false), (app().result.value as Result.Single).content)
    }
}
