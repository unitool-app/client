package cx.m42.apps.crnote

import cx.m42.unitool.kit.openssl.OpensslEnc
import cx.m42.unitool.kit.ssh.AgeKeys
import cx.m42.unitool.kit.ssh.SshPublicKey
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * The export opened by the real `age` and `openssl`, and their files read back (D660, D661), as
 * cr-restore checks itself (D618). A tool that is missing skips the test by name, never passes it.
 */
class TransferInteropTest {

    private val notes = listOf(
        Note("a", "Первая строка\nи вторая ✓", 1_700_000_000_000, 1_700_000_100_000),
        Note("b", "second \"quoted\" note", 1_700_000_200_000, 1_700_000_200_000),
    )

    private fun has(tool: String): Boolean = runCatching {
        ProcessBuilder("sh", "-c", "command -v $tool").start().waitFor() == 0
    }.getOrDefault(false)

    private fun require(vararg tools: String) = tools.forEach { Assume.assumeTrue("SKIP interop: no $it", has(it)) }

    private fun run(command: List<String>, dir: File, input: ByteArray? = null): Pair<Int, ByteArray> {
        val process = ProcessBuilder(command).directory(dir).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val writer = Thread { runCatching { process.outputStream.use { out -> input?.let { out.write(it) } } } }.apply { start() }
        val out = process.inputStream.readAllBytes()
        check(process.waitFor(120, TimeUnit.SECONDS)) { "$command timed out" }
        writer.join()
        return process.exitValue() to out
    }

    private fun temp(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("cr-note").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun assertOurs(plain: ByteArray) {
        val back = Transfer.notes(plain, 0)!!
        assertEquals(notes.map { it.text }, back.map { it.text })
        assertEquals(notes.map { it.created }, back.map { it.created })
    }

    @Test
    fun ageWithAPasswordOpensWithAge() = temp { dir ->
        require("age", "script")
        val password = "a long password ✓"
        File(dir, "n.age").writeText(Transfer.agePassword(Transfer.plaintext(notes), password))
        // age reads a passphrase from a terminal only: `script` gives it one.
        val (exit, _) = run(listOf("script", "-qec", "age -d -o n.json n.age", "/dev/null"), dir, (password + "\n").encodeToByteArray())
        assertEquals(0, exit)
        assertOurs(File(dir, "n.json").readBytes())
    }

    @Test
    fun ageToAnSshKeyAndAnAgeKeyOpensWithEither() = temp { dir ->
        require("age", "age-keygen", "ssh-keygen")
        run(listOf("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", "id"), dir)
        run(listOf("age-keygen", "-o", "age.txt"), dir)
        val ssh = SshPublicKey.parse(File(dir, "id.pub").readText())!!
        val identity = AgeKeys.parseIdentities(File(dir, "age.txt").readText())!!.single()
        File(dir, "n.age").writeText(Transfer.ageKeys(Transfer.plaintext(notes), listOf(ssh), listOf(AgeKeys.parseRecipient(identity.recipient)!!)))
        for (key in listOf("id", "age.txt")) {
            val (exit, out) = run(listOf("age", "-d", "-i", key, "n.age"), dir)
            assertEquals(0, exit, key)
            assertOurs(out)
        }
    }

    @Test
    fun opensslOpensWithTheCommandShown() = temp { dir ->
        require("openssl")
        val password = "a long password ✓"
        val name = Transfer.fileName(0, ExportMethod.Openssl)
        File(dir, name).writeText(Transfer.openssl(Transfer.plaintext(notes), password))
        val command = Transfer.command(ExportMethod.Openssl, name).split(" ") + listOf("-pass", "pass:$password")
        val (exit, out) = run(command, dir)
        assertEquals(0, exit)
        assertOurs(out)
    }

    @Test
    fun filesByAgeAndOpensslComeInAsOneNoteEach() = temp { dir ->
        require("age", "openssl", "script")
        val text = "a note from a computer\nline two"
        File(dir, "t.txt").writeText(text)

        // openssl's own defaults — 10 000 iterations, not ours — and armored.
        run(listOf("openssl", "enc", "-aes-256-cbc", "-pbkdf2", "-a", "-salt", "-pass", "pass:pw", "-in", "t.txt", "-out", "t.enc"), dir)
        val enc = assertIs<Incoming.Openssl>(Transfer.detect(File(dir, "t.enc").readBytes()))
        val opened = assertIs<OpensslEnc.Opened.Ok>(OpensslEnc.decrypt(enc.file, OpensslEnc.Key.Password("pw".encodeToByteArray())))
        assertEquals(text, Transfer.notes(opened.plaintext, 5)!!.single().text)

        run(listOf("script", "-qec", "age -p -a -o t.age t.txt", "/dev/null"), dir, "pw\npw\n".encodeToByteArray())
        val age = assertIs<Incoming.Age>(Transfer.detect(File(dir, "t.age").readBytes()))
        assertTrue(age.password)
        val plain = assertIs<AgeKeys.Opened.Ok>(AgeKeys.decrypt(age.file, passphrase = "pw".encodeToByteArray())).plaintext
        assertEquals(text, Transfer.notes(plain, 5)!!.single().text)
    }
}
