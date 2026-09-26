package cx.m42.services.backup

import cx.m42.superizer.ssh.SshOutcome
import cx.m42.superizer.testing.FakeNetwork
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume

/**
 * The backup's cipher held to the real `age` (03 §4, D158): what it writes, `age -d -i ~/.ssh/…`
 * opens on a computer — the path 05 §3.4 promises when the key is only there. Skipped by name
 * without `age` or `ssh-keygen`.
 */
class BackupCipherTest {

    @Test
    fun aBackupOpensWithTheRealAgeAndTheKeyOnTheComputer() = runBlocking<Unit> {
        requireTools("age", "ssh-keygen")
        val dir = Files.createTempDirectory("backup-cipher").toFile()
        try {
            val key = File(dir, "id_ed25519")
            run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "backup@test", "-f", key.path)
            val bundle = """{"schema":1,"apps":{}}""".encodeToByteArray()
            val cipher = AgeBackupCipher { SshOutcome.Cancelled }
            val file = cipher.encrypt(bundle.copyOf(), listOf(File(dir, "id_ed25519.pub").readText()))
            assertTrue(file.decodeToString().startsWith("-----BEGIN AGE ENCRYPTED FILE-----"), "armored: it travels as text")
            val encrypted = File(dir, "backup.age").apply { writeBytes(file) }
            val opened = run("age", "-d", "-i", key.path, encrypted.path)
            assertEquals(bundle.decodeToString(), opened)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun noKeyAgeCanEncryptToIsTheLibrarysNoRecipients() = runBlocking<Unit> {
        val cipher = AgeBackupCipher { SshOutcome.Cancelled }
        // ECDSA is published by GitHub and age has no recipient type for it.
        val ecdsa = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEIsMsXqmDgDTtFzJYG8gCJX0455Aa7IlDHEiYA0PtCHVoTPjGzWFYwQgxsQRhLRYXwgEw9EjUXF79TLbDAzMEo="
        assertFailsWith<IllegalArgumentException> { cipher.encrypt(ByteArray(1), listOf(ecdsa, "not a key")) }
        assertIs<SshOutcome.Cancelled>(cipher.decrypt(ByteArray(1)))
    }

    @Test
    fun githubKeysComeUsableFirstAndEveryFailureHasAName() = runBlocking<Unit> {
        val ed = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOh1VbDdG6aEqkiUkfr6tLE1wlJZ/jm90yZxcCjDq3xS"
        val ecdsa = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEIsMsXqmDgDTtFzJYG8gCJX0455Aa7IlDHEiYA0PtCHVoTPjGzWFYwQgxsQRhLRYXwgEw9EjUXF79TLbDAzMEo="
        val network = FakeNetwork().apply {
            respond(GitHubKeys.url("octocat"), """[{"id":1,"key":"$ecdsa"},{"id":2,"key":"$ed"}]""")
            respond(GitHubKeys.url("nobody"), "[]")
        }
        val keys = assertIs<GitHubKeys.Fetched.Keys>(GitHubKeys.fetch(network, "octocat"))
        assertEquals(listOf(true, false), keys.keys.map { it.usable })
        assertEquals(ed, keys.keys.first().line)
        assertEquals(GitHubKeys.Problem.NoKeys, assertIs<GitHubKeys.Fetched.Failed>(GitHubKeys.fetch(network, "nobody")).problem)
        assertEquals("octocat", GitHubKeys.login(" https://github.com/octocat.keys "))
        assertEquals(null, GitHubKeys.login("octo cat"))
    }

    private fun requireTools(vararg tools: String) = tools.forEach { tool ->
        val has = runCatching { ProcessBuilder("sh", "-c", "command -v $tool").start().waitFor() == 0 }.getOrDefault(false)
        Assume.assumeTrue("SKIP interop: no $tool", has)
    }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        val out = process.inputStream.readAllBytes().decodeToString()
        val err = process.errorStream.readAllBytes().decodeToString()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "${command.toList()}: $err" }
        return out
    }
}
