package cx.m42.apps.passwords

import androidx.compose.ui.test.ExperimentalTestApi
import cx.m42.services.link.DesktopLocalLink
import cx.m42.services.link.LinkState
import cx.m42.services.link.LocalLink
import cx.m42.services.qr.QrScanner
import cx.m42.superizer.lock.UserPresence
import cx.m42.superizer.testing.AppTestScope
import cx.m42.superizer.testing.FakeAppRuntime
import cx.m42.superizer.testing.FakeUserPresence
import cx.m42.superizer.testing.runAppTest
import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.link.FrameReader
import cx.m42.unitool.kit.link.LinkMessage
import cx.m42.unitool.kit.link.LinkWire
import cx.m42.unitool.kit.link.NoiseHandshake
import cx.m42.unitool.kit.link.NoiseKeyPair
import cx.m42.unitool.kit.link.NoisePattern
import cx.m42.unitool.kit.link.NoiseTransport
import cx.m42.unitool.kit.link.PairingCode
import cx.m42.unitool.kit.link.PasswordBox
import cx.m42.unitool.kit.ssh.Age
import cx.m42.unitool.kit.ssh.PrivateKey
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * "Computers" and the request sheet on a real socket (passwords-browser README §3–§5): the test is
 * the computer — it shows a pairing code to the fake camera, calls the phone on 127.0.0.1, asks for
 * a password and opens the answer with its own SSH key, as `unitool browser host` will.
 */
@OptIn(ExperimentalTestApi::class)
class BrowserLinkTest {

    private val stored = Json.encodeToString(
        listOf(
            PasswordEntry("a", "GitHub", "octocat", "gh-pass", "https://github.com", "recovery: 1111"),
            PasswordEntry("b", "GitHub work", "octo-work", "gh-work", "https://github.com"),
            PasswordEntry("c", "", "ann", "bank-pass", "https://www.bank.example"),
        ),
    )

    /** The computer: its link key, its SSH key, a code it would show. */
    private class Computer {
        val link = NoiseKeyPair.generate()
        val ssh = PrivateKey.ed25519(secureRandomBytes(32), "laptop key")
        val psk = secureRandomBytes(32)
        val code = PairingCode(link.public, psk, "laptop", ssh.publicKey.blob, listOf("127.0.0.1"))
        var phone: ByteArray? = null

        /** The computer's clock; the tests set it to the phone's fake one (README §4: ±5 minutes). */
        var clock: () -> Long = System::currentTimeMillis

        private class Wire(val socket: Socket) {
            private val frames = FrameReader()
            fun send(bytes: ByteArray) = socket.getOutputStream().apply { write(bytes); flush() }
            fun frame(): ByteArray? {
                val buffer = ByteArray(4096)
                while (true) {
                    frames.next()?.let { return it }
                    val n = runCatching { socket.getInputStream().read(buffer) }.getOrDefault(-1)
                    if (n < 0) return null
                    frames.push(buffer, 0, n)
                }
            }
        }

        fun pair(port: Int): String {
            val w = Wire(Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 10_000 })
            w.send(LinkWire.preamble(LinkWire.Mode.Pair))
            val hs = NoiseHandshake.initiator(NoisePattern.XXpsk3, link, psk = psk, prologue = LinkWire.prologue(LinkWire.Mode.Pair))
            w.send(LinkWire.frame(hs.write()))
            hs.read(w.frame()!!)!!
            w.send(LinkWire.frame(hs.write()))
            val t = hs.split()
            val welcome = LinkMessage.decode(t.open(w.frame()!!)!!) as LinkMessage.Welcome
            phone = t.remoteStatic
            w.send(LinkWire.frame(t.seal(LinkMessage.encode(LinkMessage.Paired))))
            w.socket.close()
            return welcome.phoneName
        }

        class Asked(val shown: Boolean, val answer: LinkMessage?, val request: ByteArray)

        fun ask(port: Int, origin: String, id: ByteArray = secureRandomBytes(16), at: Long = clock()): CompletableFuture<Asked> {
            val result = CompletableFuture<Asked>()
            thread(isDaemon = true) {
                try {
                    val w = Wire(Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 20_000 })
                    w.send(LinkWire.preamble(LinkWire.Mode.Session))
                    val hs = NoiseHandshake.initiator(NoisePattern.KK, link, phone!!, prologue = LinkWire.prologue(LinkWire.Mode.Session))
                    w.send(LinkWire.frame(hs.write()))
                    val reply = w.frame()
                    if (reply == null || hs.read(reply) == null) {
                        result.complete(Asked(false, null, ByteArray(0)))
                        return@thread
                    }
                    val t: NoiseTransport = hs.split()
                    val request = LinkMessage.encode(LinkMessage.PasswordRequest(id, origin, "Chromium 145 · Linux", at))
                    w.send(LinkWire.frame(t.seal(request)))
                    var shown = false
                    while (true) {
                        val m = w.frame()?.let { t.open(it) }?.let(LinkMessage::decode)
                        if (m is LinkMessage.PasswordShown) {
                            shown = true
                            continue
                        }
                        result.complete(Asked(shown, m, request))
                        break
                    }
                    w.socket.close()
                } catch (e: Throwable) {
                    result.completeExceptionally(e)
                }
            }
            return result
        }

        fun open(answer: LinkMessage.PasswordResponse): PasswordBox.Opened {
            val opened = assertIs<Age.Opened.Ok>(Age.decrypt(answer.box, listOf(Age.identity(ssh)!!)))
            return assertNotNull(PasswordBox.open(opened.plaintext))
        }
    }

    private class Camera(var next: String? = null) : QrScanner {
        override suspend fun scan(): String? = next
    }

    private fun AppTestScope<*>.port(): Int {
        val pw = instance as PasswordsInstance
        ui.waitUntil(timeoutMillis = 10_000) { pw.browser.listening.value is LinkState.Listening }
        return (pw.browser.listening.value as LinkState.Listening).port
    }

    private fun AppTestScope<*>.pairWith(computer: Computer, camera: Camera) {
        computer.clock = runtime.clock::now
        click("pw:computers")
        assertShown("pw:computers-page")
        assertShown("pw:no-computers")
        camera.next = computer.code.format()
        click("pw:pair")
        assertShown("pw:pair-question")
        assertEquals(computer.ssh.publicKey.fingerprint, (instance as PasswordsInstance).browser.pairing.value.let { (it as BrowserLink.Pairing.Confirm).sshKey.fingerprint })
        click("pw:pair-yes")
        assertShown("pw:pair-waiting")
        assertEquals("Unitool · Desktop", computer.pair(port()))
        ui.waitUntil(timeoutMillis = 10_000) { (instance as PasswordsInstance).browser.computers.value.size == 1 }
        idle()
        assertShown("pw:paired")
    }

    private fun prepared(presence: UserPresence, camera: Camera): (FakeAppRuntime) -> Unit = {
        it.secrets.entries[KEY_ENTRIES] = stored
        it.provide(UserPresence.Key, presence)
        it.provide(QrScanner.Key, camera)
        it.provide(LocalLink.Key, DesktopLocalLink(preferredPort = 0))
    }

    @Test
    fun pairThenFillAndTheAnswerOpensOnlyWithTheComputersKey() {
        val presence = FakeUserPresence()
        val camera = Camera()
        val computer = Computer()
        runAppTest(PasswordsApp(), prepare = prepared(presence, camera)) {
            val pw = instance as PasswordsInstance
            pairWith(computer, camera)
            val saved = Json.decodeFromString<List<cx.m42.apps.passwords.Computer>>(runtime.secrets.entries.getValue(KEY_COMPUTERS)).single()
            assertEquals("laptop", saved.name)
            assertTrue(runtime.secrets.entries.containsKey(KEY_LINK_KEY))

            // A request for a site with two entries: the person picks one, confirms, it goes.
            val asked = computer.ask(port(), "https://github.com")
            ui.waitUntil(timeoutMillis = 10_000) { pw.browser.request.value != null }
            idle()
            assertShown("pw:request")
            node("pw:request-site").assertExists()
            assertEquals(listOf("a", "b"), pw.browser.request.value!!.matches)
            assertNull(pw.browser.request.value!!.selected, "two entries: nothing chosen for the person")
            click("pw:request-entry-1")
            click("pw:request-send")
            val result = asked.get(10, TimeUnit.SECONDS)
            assertTrue(result.shown)
            val answer = assertIs<LinkMessage.PasswordResponse>(result.answer)
            assertEquals(LinkMessage.FILL, answer.decision)
            computer.open(answer).use { box ->
                assertEquals("octo-work", box.username)
                assertEquals("gh-work", box.password.bytes.decodeToString())
                assertEquals("https://github.com", box.origin)
                assertEquals(PasswordBox.requestDigest(result.request), box.request)
            }
            assertFalse("recovery" in answer.box.decodeToString(), "the note never goes")
            // Another key cannot open it.
            assertIs<Age.Opened.NoIdentity>(Age.decrypt(answer.box, listOf(Age.identity(PrivateKey.ed25519(secureRandomBytes(32), "x"))!!)))
            assertEquals(listOf("Отправить пароль для github.com на «laptop»"), presence.reasons)
            idle()
            assertAbsent("pw:request")

            // The journal: time, site, outcome — no login anywhere in what is stored about the computer.
            ui.waitUntil(timeoutMillis = 5_000) { pw.browser.computers.value.single().journal.isNotEmpty() }
            val line = pw.browser.computers.value.single().journal.single()
            assertEquals("github.com" to Outcome.Filled, line.site to line.outcome)
            assertFalse("octo" in runtime.secrets.entries.getValue(KEY_COMPUTERS))
            assertTrue(runtime.storage.entries.values.none { "gh-work" in it || "laptop" in it }, "nothing in the plain storage")
            assertFalse("gh-work" in pw.saveState().toString())
        }
    }

    @Test
    fun denyTheDevicesNoAndTheRedWarning() {
        val presence = FakeUserPresence(answer = false)
        val camera = Camera()
        val computer = Computer()
        runAppTest(PasswordsApp(), prepare = prepared(presence, camera)) {
            val pw = instance as PasswordsInstance
            pairWith(computer, camera)

            // The device says no: nothing goes, the sheet stays; then the person denies.
            val first = computer.ask(port(), "https://github.com")
            ui.waitUntil(timeoutMillis = 10_000) { pw.browser.request.value != null }
            idle()
            click("pw:request-entry-0")
            click("pw:request-send")
            idle()
            assertShown("pw:request")
            click("pw:request-deny")
            val denied = first.get(10, TimeUnit.SECONDS)
            assertEquals(LinkMessage.DENY, (denied.answer as LinkMessage.PasswordResponse).decision)
            assertEquals(0, (denied.answer as LinkMessage.PasswordResponse).box.size)

            // A look-alike site: no entries of its own, a search, and the red line when one is picked.
            presence.answer = true
            val phishing = computer.ask(port(), "https://github-login.example")
            ui.waitUntil(timeoutMillis = 10_000) { pw.browser.request.value != null }
            idle()
            assertTrue(pw.browser.request.value!!.searching)
            assertAbsent("pw:request-mismatch")
            type("pw:request-search", "octocat")
            click("pw:request-entry-0")
            assertShown("pw:request-mismatch")
            click("pw:request-send")
            val sent = phishing.get(10, TimeUnit.SECONDS)
            assertEquals(LinkMessage.FILL, (sent.answer as LinkMessage.PasswordResponse).decision)
        }
    }

    @Test
    fun theClockAReplayAndAnIdnSite() {
        val camera = Camera()
        val computer = Computer()
        runAppTest(PasswordsApp(), prepare = prepared(FakeUserPresence(), camera)) {
            val pw = instance as PasswordsInstance
            pairWith(computer, camera)
            // FakeClock's "now" is the phone's: a computer six minutes off is refused (README §4).
            val phoneNow = runtime.clock.now()
            val skewed = computer.ask(port(), "https://github.com", at = phoneNow + 6 * 60_000).get(10, TimeUnit.SECONDS)
            assertEquals(LinkMessage.Error(LinkMessage.CLOCK), skewed.answer)
            assertFalse(skewed.shown)

            val id = secureRandomBytes(16)
            val once = computer.ask(port(), "https://xn--e1afmkfd.xn--p1ai", id, phoneNow)
            ui.waitUntil(timeoutMillis = 10_000) { pw.browser.request.value != null }
            idle()
            assertEquals("пример.рф", pw.browser.request.value!!.unicodeHost)
            assertShown("pw:request-ascii")
            click("pw:request-deny")
            once.get(10, TimeUnit.SECONDS)
            val again = computer.ask(port(), "https://xn--e1afmkfd.xn--p1ai", id, phoneNow).get(10, TimeUnit.SECONDS)
            assertEquals(LinkMessage.Error(LinkMessage.REPLAY), again.answer)

            ui.waitUntil(timeoutMillis = 5_000) { pw.browser.computers.value.single().journal.size == 3 }
            assertEquals(listOf(Outcome.Refused, Outcome.Denied, Outcome.Refused), pw.browser.computers.value.single().journal.map { it.outcome }.reversed())
        }
    }

    @Test
    fun theStrangerTheBackgroundAndForgetting() {
        val camera = Camera()
        val computer = Computer()
        runAppTest(PasswordsApp(), prepare = prepared(FakeUserPresence(), camera)) {
            val pw = instance as PasswordsInstance
            pairWith(computer, camera)
            val port = port()

            // A computer the phone does not know gets a closed connection, nothing else.
            val stranger = Computer().apply { phone = computer.phone; clock = computer.clock }
            assertFalse(stranger.ask(port, "https://github.com").get(10, TimeUnit.SECONDS).shown)

            // In the background the phone stops listening (D538).
            runtime.hostLifecycle.value = cx.m42.superizer.runtime.HostLifecycle.Background
            ui.waitUntil(timeoutMillis = 5_000) { pw.browser.listening.value == null }
            assertTrue(runCatching { Socket(InetAddress.getLoopbackAddress(), port).close() }.isFailure, "the port is closed")
            runtime.hostLifecycle.value = cx.m42.superizer.runtime.HostLifecycle.Foreground
            port()

            // Forgetting asks the device; afterwards the computer is a stranger.
            click("pw:computer-0")
            click("pw:forget-0")
            ui.waitUntil(timeoutMillis = 5_000) { pw.browser.computers.value.isEmpty() }
            ui.waitUntil(timeoutMillis = 5_000) { pw.browser.listening.value == null }
            assertEquals("[]", runtime.secrets.entries.getValue(KEY_COMPUTERS))
        }
    }

    @Test
    fun aCodeThatIsNotOneOrHasAnEcdsaKeyIsTurnedAway() {
        val camera = Camera()
        runAppTest(PasswordsApp(), prepare = prepared(FakeUserPresence(), camera)) {
            click("pw:computers")
            camera.next = "otpauth://totp/x?secret=AAAA"
            click("pw:pair")
            assertShown("pw:pair-failed")
            assertEquals(
                BrowserLink.Pairing.Failed(BrowserLink.PairProblem.NotACode),
                (instance as PasswordsInstance).browser.pairing.value,
            )
            val ecdsa = cx.m42.unitool.kit.ssh.SshPublicKey.parse(
                "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBCDy8c/J2GvgfZ3I7Pgw+pVZrQBgDTR8nlZehFqIQK20w4wvySEM1CfQT1795ZPmgeBkxwTu4pVJYNzqPTpQsbg=",
            )!!
            camera.next = PairingCode(ByteArray(32) { 1 }, ByteArray(32) { 2 }, "pc", ecdsa.blob).format()
            click("pw:pair")
            assertEquals(
                BrowserLink.Pairing.Failed(BrowserLink.PairProblem.KeyNotSupported),
                (instance as PasswordsInstance).browser.pairing.value,
            )
        }
    }

    @Test
    fun noLinkNoComputers() = runAppTest(PasswordsApp(), prepare = { it.secrets.entries[KEY_ENTRIES] = stored }) {
        assertAbsent("pw:computers")
    }
}
