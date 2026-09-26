package cx.m42.services.ssh

import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.ssh.ImportOutcome
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * "Everything the way ssh does it" (02 §2, I14): our agent on a UNIX socket, and the real `ssh-add`
 * and `ssh-keygen` talking to it through `SSH_AUTH_SOCK` — list, add, sign, remove, lock, lifetime.
 */
class AgentInteropTest {

    private class Running(val socket: File, val stop: () -> Unit)

    private fun serve(host: SshKeyringHost, dir: File): Running {
        val path = File(dir, "agent.sock")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(path.toPath())) }
        val worker = thread(isDaemon = true) {
            while (server.isOpen) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { client.use { connection(host, it) } }
            }
        }
        return Running(path) {
            server.close()
            worker.join(2000)
        }
    }

    private fun connection(host: SshKeyringHost, channel: SocketChannel) {
        fun read(n: Int): ByteArray? {
            val buffer = ByteBuffer.allocate(n)
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) return null
            return buffer.array()
        }
        while (true) {
            val header = read(4) ?: return
            val length = ByteBuffer.wrap(header).int
            val message = read(length) ?: return
            val response = runBlocking { host.agentMessage(message) }
            channel.write(ByteBuffer.allocate(4 + response.size).putInt(response.size).put(response).flip())
        }
    }

    private fun askpass(dir: File, answer: String): File = File(dir, "askpass-${answer.hashCode()}").apply {
        writeText("#!/bin/sh\necho '$answer'\n")
        setExecutable(true)
    }

    @Test
    fun sshAddAndSshKeygenWorkThroughOurAgent() = Tools.tempDir { dir ->
        Tools.require("ssh-keygen", "ssh-add")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prompter = ScriptedPrompter()
        val host = SshKeyringHost.passphraseOnly(cx.m42.services.ssh.keyring.InMemoryRecordStore(), prompter, cx.m42.superizer.testing.FakeClock(), Platform.Desktop, scope)
        val running = serve(host, dir)
        try {
            val env = mapOf("SSH_AUTH_SOCK" to running.socket.path)
            fun sshAdd(vararg args: String, askpass: String? = null) = Tools.run(
                listOf("ssh-add") + args,
                env = env + (askpass?.let { mapOf("SSH_ASKPASS" to askpass(dir, it).path, "SSH_ASKPASS_REQUIRE" to "force", "DISPLAY" to ":0") } ?: emptyMap()),
            )

            assertEquals(1, sshAdd("-l").exit, "empty agent: exit 1")

            // A key in the keyring (level 3) is listed by the agent.
            val kept = Tools.keygen(dir, "kept", listOf("-t", "ed25519"), GOOD)
            runBlocking { assertIs<ImportOutcome.Imported>(host.admin.import(kept.readBytes(), GOOD.toCharArray(), ImportOptions())) }
            val listed = sshAdd("-L")
            assertEquals(0, listed.exit, listed.err)
            assertTrue(File(kept.path + ".pub").readText().trim().substringBeforeLast(' ') in listed.text)

            // ssh-add decrypts the file itself (askpass) and hands us the key: kept in memory, like ssh-agent.
            val added = Tools.keygen(dir, "added", listOf("-t", "rsa", "-b", "2048"), "rsa passphrase 1")
            val add = sshAdd(added.path, askpass = "rsa passphrase 1")
            assertEquals(0, add.exit, add.err)
            assertEquals(2, sshAdd("-l").text.lines().count { it.isNotBlank() })

            // ssh-keygen signs *through the agent* with each, and verifies it.
            prompter.passphrases.addAll(List(3) { GOOD })
            for (key in listOf(kept, added)) {
                val data = File(dir, "data-${key.name}").apply { writeText("signed through the agent") }
                val sign = Tools.run(listOf("ssh-keygen", "-Y", "sign", "-f", "${key.path}.pub", "-n", "file", data.path), env = env)
                assertEquals(0, sign.exit, "${key.name}: ${sign.err}")
                val signers = File(dir, "signers").apply { writeText("me " + File(key.path + ".pub").readText()) }
                val verify = Tools.run(listOf("ssh-keygen", "-Y", "verify", "-f", signers.path, "-I", "me", "-n", "file", "-s", "${data.path}.sig"), input = data.readBytes())
                assertEquals(0, verify.exit, "${key.name}: ${verify.err}")
            }
            assertTrue(prompter.count("confirm") >= 1, "the memory key's signature was confirmed")
            assertTrue(prompter.count("passphrase") >= 1, "the keyring key's passphrase was asked")

            // Lock: nothing listed, nothing signed; unlock with the same passphrase.
            assertEquals(0, sshAdd("-x", askpass = "lock it now").exit)
            assertEquals(1, sshAdd("-l").exit)
            assertTrue(sshAdd("-X", askpass = "not the one").exit != 0)
            assertEquals(0, sshAdd("-X", askpass = "lock it now").exit)

            // Remove the memory key; -D takes the rest of the agent's own, and leaves the keyring's.
            assertEquals(0, sshAdd("-d", "${added.path}.pub").exit)
            assertEquals(0, sshAdd("-t", "60", "-c", added.path, askpass = "rsa passphrase 1").exit)
            assertEquals(0, sshAdd("-D").exit)
            assertEquals(1, sshAdd("-l").text.lines().count { it.isNotBlank() }, "the keyring's own key stays")
        } finally {
            running.stop()
            scope.cancel()
        }
    }
}
