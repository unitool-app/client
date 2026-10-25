package cx.m42.services.link

import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.json.FlatJson
import cx.m42.unitool.kit.link.LinkMessage
import cx.m42.unitool.kit.link.NoiseKeyPair
import cx.m42.unitool.kit.link.PairingCode
import cx.m42.unitool.kit.link.PasswordBox
import cx.m42.unitool.kit.ssh.Age
import cx.m42.unitool.kit.ssh.SshPublicKey
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assume

/**
 * The real `unitool browser host` — the native binary, its POSIX sockets and its own mDNS — against
 * this module's desktop listener as the phone, on the real network: pairing from the extension's
 * `pair` message, then a password (passwords-browser 01). Skipped by name when the binary has not
 * been built (`./gradlew :unitool-cli:app:install`).
 */
class NativeHostTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun stop() = scope.cancel()

    private class Host(binary: File, home: File) {
        val process: Process = ProcessBuilder(binary.path, "browser", "host", "chrome-extension://test/")
            .apply { environment()["HOME"] = home.path; environment().remove("XDG_CONFIG_HOME") }
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        private val input = DataInputStream(process.inputStream)
        private val output: OutputStream = process.outputStream

        fun send(json: String) {
            val b = json.toByteArray()
            output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(b.size).array() + b)
            output.flush()
        }

        fun receive(): Map<String, String> {
            val header = ByteArray(4).also { input.readFully(it) }
            val n = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
            val body = ByteArray(n).also { input.readFully(it) }
            return FlatJson.parseText(body) ?: mapOf("raw" to body.decodeToString())
        }
    }

    @Test
    fun theBinaryFindsPairsAndGetsAPassword() {
        val binary = File(System.getProperty("unitool.binary") ?: "")
        Assume.assumeTrue("SKIP: no unitool binary (./gradlew :unitool-cli:app:install)", binary.canExecute())
        Assume.assumeTrue("SKIP: no ssh-keygen", ProcessBuilder("sh", "-c", "command -v ssh-keygen").start().waitFor() == 0)
        val home = kotlin.io.path.createTempDirectory("host-home").toFile()
        try {
            File(home, ".ssh").mkdirs()
            ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", File(home, ".ssh/id_ed25519").path).start().waitFor(30, TimeUnit.SECONDS)
            val phone = NoiseKeyPair.generate()
            val linkId = secureRandomBytes(8)
            var code: PairingCode? = null
            var computer: ByteArray? = null
            val listener = DesktopLocalLink(preferredPort = 0).open(scope, emptyList()) { pipe ->
                val c = code ?: return@open
                val expected = LinkResponder.Expected(phone, listOfNotNull(computer), c.psk, c.computerKey)
                when (val accepted = LinkResponder(pipe).accept(expected)) {
                    is LinkResponder.Accepted.Pairing -> accepted.channel.use { ch ->
                        ch.send(LinkMessage.Welcome("JVM phone", linkId))
                        if (ch.receive(5000)?.message is LinkMessage.Paired) computer = ch.remoteStatic
                    }
                    is LinkResponder.Accepted.Session -> accepted.channel.use { ch ->
                        val got = ch.receive(5000)!!
                        val req = got.message as LinkMessage.PasswordRequest
                        ch.send(LinkMessage.PasswordShown(req.id))
                        val ssh = SshPublicKey.fromBlob(c.sshKey)!!
                        val box = PasswordBox.plaintext(got.bytes, req.origin, "octocat", "correct horse".encodeToByteArray())
                        ch.send(LinkMessage.PasswordResponse(req.id, LinkMessage.FILL, Age.encrypt(box, listOf(ssh))))
                    }
                    null -> Unit
                }
            }
            val host = Host(binary, home)
            try {
                host.send("""{"type":"pair","name":"e2e"}""")
                val pair = host.receive()
                assertEquals("pair", pair["type"], pair.toString())
                code = assertNotNull(PairingCode.parse(pair["uri"]!!))
                listener.rename(listOf(code!!.pairingInstance))
                assertEquals(mapOf("type" to "paired", "phone" to "JVM phone"), host.receive())
                val until = System.currentTimeMillis() + 5000
                while (computer == null && System.currentTimeMillis() < until) Thread.sleep(20)
                assertNotNull(computer, "the phone kept the computer on PAIRED")
                listener.rename(listOf(PairingCode.linkInstance(linkId)))

                host.send("""{"type":"fill","origin":"https://github.com","browser":"test"}""")
                val states = ArrayList<String>()
                while (true) {
                    val m = host.receive()
                    if (m["type"] == "state") {
                        states += m.getValue("state")
                        continue
                    }
                    assertEquals(listOf("searching", "found", "shown"), states)
                    assertEquals(mapOf("type" to "fill", "username" to "octocat", "password" to "correct horse", "phone" to "JVM phone"), m)
                    break
                }
                // What the host keeps: three files, 0600, in a 0700 directory, and no password in any.
                val dir = File(home, ".config/unitool/link")
                assertEquals(setOf("key", "phones", "settings"), dir.list()!!.toSet())
                assertTrue(dir.listFiles()!!.none { "correct horse" in it.readText() })
                val modes = dir.listFiles()!!.map { java.nio.file.Files.getPosixFilePermissions(it.toPath()).map(Any::toString).sorted() }
                assertTrue(modes.all { it == listOf("OWNER_READ", "OWNER_WRITE") }, modes.toString())
            } finally {
                host.process.outputStream.close()
                assertTrue(host.process.waitFor(10, TimeUnit.SECONDS), "the host leaves when the port closes")
                listener.close()
            }
        } finally {
            home.deleteRecursively()
        }
    }
}
