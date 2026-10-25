package cx.m42.apps.passwords

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import cx.m42.services.link.DesktopLocalLink
import cx.m42.services.link.LocalLink
import cx.m42.services.qr.QrScanner
import cx.m42.superizer.lock.UserPresence
import cx.m42.superizer.testing.FakeUserPresence
import cx.m42.superizer.testing.FakeAppRuntime
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assume

/**
 * The whole way, on this machine (passwords-browser step 7): this app on the desktop listener is the
 * phone; `unitool browser install` sets up the real native binary for Chromium; Chromium runs the
 * built extension; the real Ctrl+Shift+L (xdotool, under Xvfb) opens the popup; the phone's camera
 * reads the QR off a picture of the popup. Then a password goes into the page — and a denial does not.
 *
 * Needs the binary (`:unitool-cli:app:install`), the extension (`npm run build` in unitool-browser),
 * node, Chromium, xvfb-run and xdotool; skipped by name without them.
 */
class BrowserE2eTest {

    private val entries = Json.encodeToString(
        listOf(
            PasswordEntry("gh", "GitHub", "octocat", "gh-pass", "https://github.com"),
            PasswordEntry("local", "Local site", "e2e-user", "e2e-pass ☃ \"quoted\"", "http://127.0.0.1/login"),
        ),
    )

    private class Camera(var next: String? = null) : QrScanner {
        override suspend fun scan(): String? = next
    }

    private fun tool(name: String) = ProcessBuilder("sh", "-c", "command -v $name").start().waitFor() == 0

    private fun decodeQr(png: ByteArray): String {
        val image = ImageIO.read(ByteArrayInputStream(png))
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text
    }

    @Test
    fun aPasswordFromThePhoneLandsInChromium() {
        val binary = File(System.getProperty("unitool.binary").orEmpty())
        val extension = File(System.getProperty("unitool.extension").orEmpty())
        val driver = File(System.getProperty("unitool.driver").orEmpty())
        val chromium = File("/usr/lib/chromium/chromium")
        Assume.assumeTrue("SKIP e2e: no unitool binary (./gradlew :unitool-cli:app:install)", binary.canExecute())
        Assume.assumeTrue("SKIP e2e: no built extension (npm run build in unitool-browser)", File(extension, "manifest.json").exists())
        Assume.assumeTrue("SKIP e2e: no node_modules in unitool-browser (npm ci)", File(driver.parentFile.parentFile, "node_modules/puppeteer-core").exists())
        Assume.assumeTrue("SKIP e2e: no Chromium at $chromium", chromium.canExecute())
        for (t in listOf("node", "xvfb-run", "xdotool", "ssh-keygen")) Assume.assumeTrue("SKIP e2e: no $t", tool(t))

        val home = kotlin.io.path.createTempDirectory("e2e-home").toFile()
        try {
            File(home, ".ssh").mkdirs()
            File(home, ".config/chromium").mkdirs()
            assertEquals(0, ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", File(home, ".ssh/id_ed25519").path).start().waitFor())
            // `install` as a person runs it: it finds Chromium's profile and writes the manifest and the wrapper.
            val install = ProcessBuilder(binary.path, "browser", "install")
                .apply { environment()["HOME"] = home.path; environment().remove("XDG_CONFIG_HOME"); environment().remove("XDG_DATA_HOME") }
                .redirectErrorStream(true).start()
            val installed = install.inputStream.bufferedReader().readText()
            assertEquals(0, install.waitFor(), installed)
            val manifest = File(home, ".config/chromium/NativeMessagingHosts/cx.m42.unitool.json").readText()
            assertTrue("chrome-extension://omcnlonedghinfecceojlapofcjfcpoi/" in manifest, manifest)

            val camera = Camera()
            // The app without Compose: a run this long is past runComposeUiTest's minute, and the
            // screens are BrowserLinkTest's. The instance is driven the way its buttons drive it.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = FakeAppRuntime(PasswordsApp.ID, scope).apply {
                secrets.entries[KEY_ENTRIES] = entries
                provide(UserPresence.Key, FakeUserPresence())
                provide(QrScanner.Key, camera)
                provide(LocalLink.Key, DesktopLocalLink(preferredPort = 0))
                // The phone's clock is the computer's here: the host sends the real time (±5 min, README §4).
                clock.advance(millis = System.currentTimeMillis() - clock.now())
            }
            val pw = PasswordsApp().launch(runtime, PasswordsConfig()) as PasswordsInstance
            fun waitUntil(seconds: Long, what: String, condition: () -> Boolean) {
                val end = System.currentTimeMillis() + seconds * 1000
                while (!condition()) {
                    assertTrue(System.currentTimeMillis() < end, "timed out: $what")
                    Thread.sleep(20)
                }
            }
            try {
                runBlocking { pw.onLaunch() }
                waitUntil(10, "the list is read") { pw.loaded.value }
                pw.browser.screenShown(true)
                val process = ProcessBuilder(
                    "xvfb-run", "-a", "-s", "-screen 0 1280x900x24", "node", driver.path,
                    "--home=${home.path}", "--profile=${home.path}/.config/chromium", "--ext=${extension.path}", "--chromium=${chromium.path}",
                ).directory(driver.parentFile.parentFile).redirectError(ProcessBuilder.Redirect.INHERIT).start()
                val events = LinkedBlockingQueue<JsonObject>()
                thread(isDaemon = true) {
                    val reader: BufferedReader = process.inputStream.bufferedReader()
                    reader.lineSequence().forEach { line ->
                        // What the browser saw, for a failure's report; the QR picture is cut short.
                        System.err.println("browser: " + line.take(240))
                        runCatching { events.put(Json.parseToJsonElement(line).jsonObject) }
                    }
                }
                fun send(cmd: String) = process.outputStream.apply { write("""{"cmd":"$cmd"}""".toByteArray() + 10); flush() }
                fun next(ev: String, seconds: Long = 120): JsonObject {
                    val end = System.currentTimeMillis() + seconds * 1000
                    while (true) {
                        val left = end - System.currentTimeMillis()
                        val e = assertNotNull(events.poll(left.coerceAtLeast(1), TimeUnit.MILLISECONDS), "no '$ev' from the browser")
                        val name = e["ev"]!!.jsonPrimitive.content
                        if (name == "error") error("browser: ${e["message"]}")
                        if (name == ev) return e
                    }
                }
                try {
                    next("ready", 60)

                    // Pairing: the popup's QR, read by the phone's camera off a picture of it.
                    send("pair")
                    val qr = next("qr", 60)
                    val uri = decodeQr(Base64.getDecoder().decode(qr["png"]!!.jsonPrimitive.content))
                    assertTrue(uri.startsWith("unitool-pair:1:"), uri)
                    camera.next = uri
                    pw.startComputers()
                    pw.browser.pair()
                    waitUntil(10, "the pairing question") { pw.browser.pairing.value is BrowserLink.Pairing.Confirm }
                    pw.browser.confirmPairing()
                    waitUntil(60, "the computer paired") { pw.browser.computers.value.isNotEmpty() }
                    val paired = next("paired", 60)
                    assertTrue("Unitool · Desktop" in paired["text"]!!.jsonPrimitive.content, paired.toString())

                    // A password: Ctrl+Shift+L on the login page, the sheet on the phone, Send.
                    send("fill")
                    waitUntil(60, "the request sheet") { pw.browser.request.value != null }
                    val sheet = pw.browser.request.value!!
                    assertEquals("127.0.0.1", sheet.host)
                    assertEquals(listOf("local"), sheet.matches)
                    assertTrue(sheet.browser.startsWith("Chromium") || sheet.browser.startsWith("Chrome"), sheet.browser)
                    pw.browser.send()
                    val result = next("result")
                    val values = result["values"]!!.jsonObject
                    assertEquals("e2e-user", values["user"]!!.jsonPrimitive.content)
                    assertEquals("e2e-pass ☃ \"quoted\"", values["pass"]!!.jsonPrimitive.content)
                    assertEquals("", values["search"]!!.jsonPrimitive.content, "only the login and the password fields")
                    assertFalse(values["submitted"]!!.jsonPrimitive.boolean, "the form is not submitted")
                    val screens = result["screens"]!!.jsonArray.map { it.jsonPrimitive.content }
                    assertEquals("filled", screens.last(), screens.toString())
                    assertTrue("shown" in screens, screens.toString())

                    // A denial fills nothing.
                    send("fill")
                    waitUntil(60, "the second sheet") { pw.browser.request.value != null }
                    pw.browser.deny()
                    val denied = next("result")
                    assertEquals("denied", denied["screens"]!!.jsonArray.last().jsonPrimitive.content)
                    assertEquals("", denied["values"]!!.jsonObject["pass"]!!.jsonPrimitive.content)

                    waitUntil(10, "the journal") { pw.browser.computers.value.single().journal.size == 2 }
                    val journal = pw.browser.computers.value.single().journal.map { it.outcome }
                    assertEquals(listOf(Outcome.Denied, Outcome.Filled), journal)
                    // The computer kept nothing but its key, the phone and the key's path.
                    val link = File(home, ".config/unitool/link")
                    assertEquals(setOf("key", "phones", "settings"), link.list()!!.toSet())
                    assertTrue(link.listFiles()!!.none { "e2e-pass" in it.readText() })
                    send("quit")
                    next("bye", 30)
                } finally {
                    process.destroy()
                    process.waitFor(10, TimeUnit.SECONDS)
                    process.destroyForcibly()
                }
            } finally {
                pw.dispose()
                scope.cancel()
            }
        } finally {
            home.deleteRecursively()
        }
    }
}
