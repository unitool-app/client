package cx.m42.services.ssh

import cx.m42.services.ssh.key.OpenSshKeyFile
import cx.m42.services.ssh.key.PrivateKey
import cx.m42.services.ssh.key.SoftwareOps
import cx.m42.unitool.kit.crypto.SecretBytes
import java.io.File
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SSHSIG and age decryption against the real tools (03 §4, I14): what we sign, `ssh-keygen -Y
 * verify` accepts; what `ssh-keygen -Y sign` makes, we verify; what `age -R` encrypts — one
 * recipient or several, armored or not, one chunk or many — we open with the same key file, and a
 * byte changed anywhere is a refusal, never garbage.
 */
class SshSigAndAgeInteropTest {

    private val random = SecureRandom()

    private fun load(file: File, passphrase: String? = null): PrivateKey {
        val parsed = OpenSshKeyFile.parse(file.readBytes()) as OpenSshKeyFile.Parsed.Ok
        return (parsed.file.decrypt(passphrase?.let { SecretBytes.utf8(it.toCharArray()) }) as OpenSshKeyFile.Decrypted.Ok).key
    }

    private val types = listOf(
        "ed25519" to listOf("-t", "ed25519"),
        "rsa" to listOf("-t", "rsa", "-b", "2048"),
        "ecdsa256" to listOf("-t", "ecdsa", "-b", "256"),
        "ecdsa521" to listOf("-t", "ecdsa", "-b", "521"),
    )

    @Test
    fun ourSignatureIsWhatSshKeygenVerifies() = Tools.tempDir { dir ->
        Tools.require("ssh-keygen")
        for ((name, type) in types) {
            val file = Tools.keygen(dir, name, type)
            val message = "commit tree 4b825dc6 $name\n".toByteArray()
            val armored = load(file).use { SoftwareOps.sshsig(it, "git", message)!! }
            val signers = File(dir, "allowed_signers").apply { writeText("me@test " + File(file.path + ".pub").readText()) }
            val sig = File(dir, "$name.sig").apply { writeText(armored) }
            val verify = Tools.run(listOf("ssh-keygen", "-Y", "verify", "-f", signers.path, "-I", "me@test", "-n", "git", "-s", sig.path), input = message)
            assertEquals(0, verify.exit, "$name: ${verify.err}")
            val otherNamespace = Tools.run(listOf("ssh-keygen", "-Y", "verify", "-f", signers.path, "-I", "me@test", "-n", "file", "-s", sig.path), input = message)
            assertTrue(otherNamespace.exit != 0, "$name: another namespace is refused")
            // And our own verifier agrees with the tool.
            val key = SshPublicKey.parse(File(file.path + ".pub").readText())!!
            assertEquals(SshSig.SigCheck.Valid, SshSig.verify(armored, message, "git", key))
            assertIs<SshSig.SigCheck.Mismatch>(SshSig.verify(armored, message, "file", key))
            assertEquals(SshSig.SigCheck.Invalid, SshSig.verify(armored, message + 1, "git", key))
        }
    }

    @Test
    fun sshKeygensSignatureIsWhatWeVerify() = Tools.tempDir { dir ->
        Tools.require("ssh-keygen")
        for ((name, type) in types) {
            val file = Tools.keygen(dir, name, type)
            val message = "a file to sign, $name".toByteArray()
            val data = File(dir, "$name.txt").apply { writeBytes(message) }
            Tools.ok(listOf("ssh-keygen", "-Y", "sign", "-f", file.path, "-n", "file", data.path))
            val armored = File(data.path + ".sig").readText()
            val key = SshPublicKey.parse(File(file.path + ".pub").readText())!!
            assertEquals(key, SshSig.signer(armored))
            assertEquals(SshSig.SigCheck.Valid, SshSig.verify(armored, message, "file", key), name)
            assertEquals(SshSig.SigCheck.Invalid, SshSig.verify(armored, message.copyOf().also { it[0] = 'A'.code.toByte() }, "file", key), name)
        }
    }

    @Test
    fun theSignedDataCanNeverBeALogin() {
        // 02 §3.1: an SSH userauth request starts with uint32 length; SSHSIG with the six bytes "SSHSIG".
        val data = SshSig.signedData("git", cx.m42.unitool.kit.crypto.Hash.SHA512, "x".toByteArray())
        assertEquals("SSHSIG", data.copyOf(6).decodeToString())
    }

    @Test
    fun whatAgeEncryptsWeOpenAndATamperedFileIsRefused() = Tools.tempDir { dir ->
        Tools.require("ssh-keygen", "age")
        val ed = Tools.keygen(dir, "ed", listOf("-t", "ed25519"), "pass phrase one")
        val rsa = Tools.keygen(dir, "rsa", listOf("-t", "rsa", "-b", "2048"))
        val stranger = Tools.keygen(dir, "stranger", listOf("-t", "ed25519"))
        for (size in listOf(0, 1, 65_536, 65_537, 200_000)) {
            val plaintext = ByteArray(size).also { random.nextBytes(it) }
            val input = File(dir, "plain-$size").apply { writeBytes(plaintext) }
            val binary = File(dir, "file-$size.age")
            Tools.ok(listOf("age", "-R", "$ed.pub", "-R", "$rsa.pub", "-o", binary.path, input.path))
            val armored = File(dir, "file-$size.asc")
            Tools.ok(listOf("age", "-a", "-R", "$ed.pub", "-o", armored.path, input.path))

            for ((file, identity) in listOf(binary to load(ed, "pass phrase one"), binary to load(rsa), armored to load(ed, "pass phrase one"))) {
                identity.use { key ->
                    val opened = kotlinx.coroutines.runBlocking { Age.decrypt(file.readBytes(), listOf(SoftwareOps.ageIdentity(key)!!)) }
                    assertIs<Age.Opened.Ok>(opened, "$size ${file.name} ${key.publicKey.label}")
                    assertContentEquals(plaintext, opened.plaintext)
                    assertEquals(key.publicKey, opened.identity)
                }
            }
            load(stranger).use { key ->
                assertEquals(Age.Opened.NoIdentity, kotlinx.coroutines.runBlocking { Age.decrypt(binary.readBytes(), listOf(SoftwareOps.ageIdentity(key)!!)) })
            }
        }
        // One byte changed in the header, in a stanza, in the payload: refused, not garbage.
        val file = File(dir, "file-65537.age").readBytes()
        load(ed, "pass phrase one").use { key ->
            val identity = SoftwareOps.ageIdentity(key)!!
            val headerEnd = String(file, Charsets.ISO_8859_1).indexOf("\n---") + 10
            for (at in listOf(30, 60, headerEnd - 5, headerEnd + 20, file.size - 3)) {
                val bad = file.copyOf().also { it[at] = (it[at].toInt() xor 0x04).toByte() }
                val opened = kotlinx.coroutines.runBlocking { Age.decrypt(bad, listOf(identity)) }
                assertTrue(opened !is Age.Opened.Ok, "byte $at changed and it still opened")
            }
        }
    }

    @Test
    fun whatWeEncryptAgeOpensAndSoDoWe() = Tools.tempDir { dir ->
        Tools.require("ssh-keygen", "age")
        val ed = Tools.keygen(dir, "ed", listOf("-t", "ed25519"))
        val rsa = Tools.keygen(dir, "rsa", listOf("-t", "rsa", "-b", "3072"))
        val recipients = listOf(ed, rsa).map { SshPublicKey.parse(File(it.path + ".pub").readText())!! }
        val plaintext = "otpauth://totp/GitHub:octocat?secret=JBSWY3DPEHPK3PXP\n".repeat(3000).toByteArray()
        val sealed = Age.encrypt(plaintext, recipients)
        val file = File(dir, "ours.age").apply { writeBytes(sealed) }
        for (identity in listOf(ed, rsa)) {
            assertContentEquals(plaintext, Tools.ok(listOf("age", "-d", "-i", identity.path, file.path)).out)
            load(identity).use { key ->
                val opened = assertIs<Age.Opened.Ok>(kotlinx.coroutines.runBlocking { Age.decrypt(sealed, listOf(SoftwareOps.ageIdentity(key)!!)) })
                assertContentEquals(plaintext, opened.plaintext)
            }
        }
        val armored = Age.armor(sealed).toByteArray()
        load(ed).use { key -> assertIs<Age.Opened.Ok>(kotlinx.coroutines.runBlocking { Age.decrypt(armored, listOf(SoftwareOps.ageIdentity(key)!!)) }) }
    }
}
