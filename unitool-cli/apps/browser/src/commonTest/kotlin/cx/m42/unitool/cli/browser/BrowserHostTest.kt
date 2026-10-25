package cx.m42.unitool.cli.browser

import cx.m42.unitool.cli.Cli
import cx.m42.unitool.cli.FakeIo
import cx.m42.unitool.cli.exec
import cx.m42.unitool.kit.crypto.SecretBytes
import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.link.LinkMessage
import cx.m42.unitool.kit.ssh.OpenSshKeyFile
import cx.m42.unitool.kit.ssh.PrivateKey
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `unitool browser host` as the extension sees it (passwords-browser 01 §5), against a phone made of
 * the kit: pairing, a password, the refusals, the answers that must not be believed, cancelling.
 * Runs on the JVM and natively.
 */
class BrowserHostTest {
    private val home = "/home/u"
    private val keyPath = "$home/.ssh/id_ed25519"
    private val ssh = PrivateKey.ed25519(secureRandomBytes(32), "u@laptop")

    private fun io(locked: Boolean = false) = FakeIo(environment = mapOf("HOME" to home)).also {
        it.files[keyPath] = OpenSshKeyFile.write(ssh, if (locked) SecretBytes("phrase".encodeToByteArray()) else null, rounds = 2)
    }

    private fun host(io: FakeIo, system: FakeBrowserSystem): Int = NativeHost(io, system).run()

    /** Pairs [phone] through the host, as the popup's "Pair a phone" does, and returns the io with the files. */
    private fun paired(phone: FakePhone, io: FakeIo = io()): FakeIo {
        val system = FakeBrowserSystem(phone, listOf("""{"type":"pair"}"""))
        assertEquals(0, host(io, system))
        val messages = system.messages()
        assertEquals(listOf("pair", "paired"), messages.map { it["type"] }, system.rawStdout())
        assertEquals("Test phone", messages[1]["phone"])
        return io
    }

    @Test
    fun pairingShowsTheCodeAndKeepsThePhone() {
        val phone = FakePhone()
        val io = io()
        val system = FakeBrowserSystem(phone, listOf("""{"type":"pair","name":"work laptop"}"""))
        assertEquals(0, host(io, system))
        val pair = system.messages().first()
        assertTrue(pair["uri"]!!.startsWith("unitool-pair:1:"))
        assertTrue(pair["svg"]!!.startsWith("<svg") || pair["svg"]!!.startsWith("<?xml"))
        assertEquals(ssh.publicKey.fingerprint, pair["fingerprint"])
        assertEquals("work laptop", phone.scanned!!.name)
        assertEquals(listOf("10.0.0.5"), phone.scanned!!.addresses)
        assertContentEqualsBytes(ssh.publicKey.blob, phone.scanned!!.sshKey)
        // What the computer keeps: its key, the phone, the key path — all 0600, nothing else.
        val dir = "$home/.config/unitool/link"
        assertEquals(setOf("$dir/key", "$dir/phones", "$dir/settings"), io.privateFiles)
        assertContains(io.files.getValue("$dir/phones").decodeToString(), "Test%20phone")
        assertEquals("ssh=$keyPath\n", io.files.getValue("$dir/settings").decodeToString())
        assertTrue(phone.paired != null, "the phone got PAIRED")
        assertTrue("$dir" in system.dirs)
    }

    @Test
    fun aPasswordComesAndIsCheckedAndNothingIsWritten() {
        val phone = FakePhone()
        val io = paired(phone)
        val before = io.files.mapValues { it.value.decodeToString() }
        val system = FakeBrowserSystem(phone, listOf("""{"type":"fill","origin":"https://github.com","browser":"Chromium 145 · Linux"}"""))
        assertEquals(0, host(io, system))
        val messages = system.messages()
        assertEquals(listOf("state", "state", "state", "fill"), messages.map { it["type"] })
        assertEquals(listOf("searching", "found", "shown"), messages.take(3).map { it["state"] })
        assertEquals("octocat", messages[3]["username"])
        assertEquals("s3cret\"\\pass", messages[3]["password"])
        val request = phone.requests.single()
        assertEquals("https://github.com", request.origin)
        assertEquals("Chromium 145 · Linux", request.browser)
        assertEquals(system.time, request.issuedAt, "the computer's clock, which the phone checks (±5 min)")
        // The password exists nowhere on "disk"; the only change is the phone's last address.
        assertTrue(io.files.values.none { "s3cret" in it.decodeToString() })
        assertEquals(before.keys, io.files.keys)
    }

    @Test
    fun anAnswerToAnotherRequestIsDroppedAndASpoofedOneRefused() {
        val phone = FakePhone()
        val io = paired(phone)
        phone.behaviour = FakePhone.Behaviour.WrongIdFirst("octocat", "right")
        val first = FakeBrowserSystem(phone, listOf("""{"type":"fill","origin":"https://github.com","browser":"x"}"""))
        host(io, first)
        assertEquals("right", first.messages().last()["password"])

        phone.behaviour = FakePhone.Behaviour.WrongDigest
        val second = FakeBrowserSystem(phone, listOf("""{"type":"fill","origin":"https://github.com","browser":"x"}"""))
        host(io, second)
        assertEquals(mapOf("type" to "error", "code" to "bad-response"), second.messages().last())
        assertFalse("\"y\"" in second.rawStdout())
    }

    @Test
    fun theDecisionsAndThePhonesRefusalsReachThePopup() {
        val phone = FakePhone()
        val io = paired(phone)
        phone.behaviour = FakePhone.Behaviour.Deny
        FakeBrowserSystem(phone, listOf("""{"type":"fill","origin":"https://github.com"}""")).let {
            host(io, it)
            assertEquals(mapOf("type" to "done", "decision" to "deny"), it.messages().last())
        }
        phone.behaviour = FakePhone.Behaviour.Error(LinkMessage.CLOCK)
        FakeBrowserSystem(phone, listOf("""{"type":"fill","origin":"https://github.com"}""")).let {
            host(io, it)
            assertEquals(mapOf("type" to "error", "code" to "clock"), it.messages().last())
        }
        // Silence: the host gives up after the 65 s the sheet and the trip take, and says so.
        phone.behaviour = FakePhone.Behaviour.Silent
        FakeBrowserSystem(phone, listOf("""{"type":"fill","origin":"https://github.com"}""")).let {
            host(io, it)
            assertEquals(mapOf("type" to "done", "decision" to "timeout"), it.messages().last())
        }
    }

    @Test
    fun closingThePopupStopsTheWait() {
        val phone = FakePhone()
        val io = paired(phone)
        phone.behaviour = FakePhone.Behaviour.Silent
        val system = FakeBrowserSystem(phone, listOf("""{"type":"fill","origin":"https://github.com"}""", """{"type":"cancel"}"""))
        host(io, system)
        assertEquals(mapOf("type" to "done", "decision" to "cancelled"), system.messages().last())
        assertTrue(phone.requests.size <= 1)
    }

    @Test
    fun withoutAPairingOrAPhoneOrAGoodKey() {
        FakeBrowserSystem(FakePhone(), listOf("""{"type":"fill","origin":"https://github.com"}""")).let {
            host(io(), it)
            assertEquals(mapOf("type" to "error", "code" to "not-paired"), it.messages().last())
        }
        // Paired, but the phone is not in the network for a minute.
        val io = paired(FakePhone())
        FakeBrowserSystem(null, listOf("""{"type":"fill","origin":"https://github.com"}""")).let {
            host(io, it)
            assertEquals(mapOf("type" to "error", "code" to "no-phone"), it.messages().last())
        }
        // A phone that forgot this computer closes the handshake.
        FakeBrowserSystem(FakePhone(), listOf("""{"type":"fill","origin":"https://github.com"}""")).let { system ->
            system.phone!!.scanned = null
            // It announces nothing as a pairing; reach it at the remembered address instead.
            host(io, system)
            assertEquals(mapOf("type" to "error", "code" to "phone-refused"), system.messages().last())
        }
        // Not a web page.
        FakeBrowserSystem(FakePhone(), listOf("""{"type":"fill","origin":"chrome://settings"}""")).let {
            host(io, it)
            assertEquals(mapOf("type" to "error", "code" to "origin"), it.messages().last())
        }
    }

    @Test
    fun aKeyUnderAPassphraseIsRefusedAtPairingBeforeAnyCode() {
        val system = FakeBrowserSystem(FakePhone(), listOf("""{"type":"pair"}"""))
        host(io(locked = true), system)
        val last = system.messages().single()
        assertEquals("key", last["code"])
        assertContains(last["message"]!!, "protected by a passphrase")
        assertContains(last["message"]!!, "--key")
    }

    @Test
    fun devicesAndForget() {
        val phone = FakePhone()
        val io = paired(phone)
        val system = FakeBrowserSystem(phone, listOf("""{"type":"devices"}"""))
        host(io, system)
        val raw = system.rawStdout()
        assertContains(raw, "\"name\":\"Test phone\"")
        assertContains(raw, "\"fingerprint\":\"${ssh.publicKey.fingerprint}\"")
        val id = LinkStore(io, system).phones().single().id
        val forget = FakeBrowserSystem(phone, listOf("""{"type":"forget","id":"$id"}"""))
        host(io, forget)
        assertContains(forget.rawStdout(), "\"phones\":[]")
    }

    @Test
    fun installWritesTheWrapperAndAManifestPerBrowser() {
        val io = FakeIo(environment = mapOf("HOME" to home))
        val system = FakeBrowserSystem(null).apply { existing += "$home/.config/chromium"; existing += "$home/.mozilla" }
        val cli = Cli(name = "unitool", title = "Unitool", version = "test", apps = listOf(BrowserApp { system }))
        val run = cli.exec("browser", "install", "--extension-id", "abcdefghijklmnopabcdefghijklmnop", io = io)
        assertEquals(0, run.code, run.toString())
        val wrapper = "$home/.local/share/unitool/browser-host"
        assertEquals("#!/bin/sh", system.executables.getValue(wrapper).lines().first())
        assertContains(system.executables.getValue(wrapper), "exec '/opt/unitool/unitool' browser host \"\$@\"")
        val chromium = io.files.getValue("$home/.config/chromium/NativeMessagingHosts/cx.m42.unitool.json").decodeToString()
        assertContains(chromium, "\"path\":\"$wrapper\"")
        assertContains(chromium, "\"allowed_origins\":[\"chrome-extension://$CHROME_EXTENSION/\",\"chrome-extension://abcdefghijklmnopabcdefghijklmnop/\"]")
        val firefox = io.files.getValue("$home/.mozilla/native-messaging-hosts/cx.m42.unitool.json").decodeToString()
        assertContains(firefox, "\"allowed_extensions\":[\"browser@unitool.m42.cx\"]")
        assertFalse(io.files.keys.any { "google-chrome" in it }, "only browsers that are there")
        assertEquals(1, cli.exec("browser", "install", "--extension-id", "nope", io = io).code)
    }

    @Test
    fun pairAtTheTerminalPrintsTheCodeAndWaits() {
        val phone = FakePhone()
        val io = io()
        val system = FakeBrowserSystem(phone)
        // The phone "scans" the code the command shows, as a camera would.
        val cli = Cli(name = "unitool", title = "Unitool", version = "test", apps = listOf(BrowserApp({ system }, { phone.scanned = it })))
        val run = cli.exec("browser", "pair", "--name", "desk", io = io)
        assertEquals(0, run.code, run.toString())
        assertContains(run.err, "Paired: Test phone")
        assertContains(run.err, ssh.publicKey.fingerprint)
        assertEquals("desk", phone.scanned!!.name)
        assertNotNull(LinkStore(io, system).phones().singleOrNull())
        val devices = cli.exec("browser", "devices", io = io)
        assertContains(devices.out, "Test phone")
        // A key under a passphrase: refused before any code is shown.
        val locked = io(locked = true)
        val refused = cli.exec("browser", "pair", io = locked)
        assertEquals(1, refused.code)
        assertContains(refused.err, "protected by a passphrase")
        assertFalse("unitool-pair" in refused.err)
    }

    private fun assertContentEqualsBytes(a: ByteArray, b: ByteArray) = assertTrue(a.contentEquals(b))
}
