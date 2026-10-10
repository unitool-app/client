package cx.m42.services.ssh

import cx.m42.services.ssh.key.OpenSshKeyFile
import cx.m42.services.ssh.key.PrivateKey
import cx.m42.superizer.ssh.ImportRefusal
import cx.m42.unitool.kit.crypto.SecretBytes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `openssh-key-v1` against the real `ssh-keygen` (03 §4): every type, cipher and round count it
 * writes opens here to the same public key `ssh-keygen -y` prints; a wrong passphrase is refused;
 * what cannot be taken is refused with the reason that names its fix; and what [OpenSshKeyFile.write]
 * makes, `ssh-keygen -y -P` opens (I19's half that is about the format).
 */
class OpenSshKeyFileInteropTest {

    private val passphrase = "correct horse battery"

    private fun open(file: File, pass: String?): OpenSshKeyFile.Decrypted {
        val parsed = OpenSshKeyFile.parse(file.readBytes())
        assertIs<OpenSshKeyFile.Parsed.Ok>(parsed, "parse ${file.name}")
        return parsed.file.decrypt(pass?.let { SecretBytes.utf8(it.toCharArray()) })
    }

    @Test
    fun everyTypeAndCipherSshKeygenWritesOpensToTheSamePublicKey() = Tools.tempDir { dir ->
        Tools.require("ssh-keygen")
        val types = listOf(
            "ed25519" to listOf("-t", "ed25519"),
            "rsa2048" to listOf("-t", "rsa", "-b", "2048"),
            "rsa3072" to listOf("-t", "rsa", "-b", "3072"),
            "ecdsa256" to listOf("-t", "ecdsa", "-b", "256"),
            "ecdsa384" to listOf("-t", "ecdsa", "-b", "384"),
            "ecdsa521" to listOf("-t", "ecdsa", "-b", "521"),
        )
        for ((name, type) in types) {
            val plain = Tools.keygen(dir, "$name-plain", type)
            val expected = SshPublicKey.parse(File(plain.path + ".pub").readText())!!
            val opened = open(plain, null)
            assertIs<OpenSshKeyFile.Decrypted.Ok>(opened, name)
            opened.key.use {
                assertEquals(expected, it.publicKey, name)
                assertEquals("$name-plain@test", it.comment)
            }
            for ((cipher, rounds) in listOf("aes256-ctr" to "16", "aes128-ctr" to "24", "aes256-gcm@openssh.com" to "16")) {
                if (name.startsWith("rsa3072") && cipher != "aes256-ctr") continue // the slow half; ctr covers it
                val file = Tools.keygen(dir, "$name-$cipher", type, passphrase, listOf("-Z", cipher, "-a", rounds))
                val key = SshPublicKey.parse(File(file.path + ".pub").readText())!!
                val parsed = OpenSshKeyFile.parse(file.readBytes()) as OpenSshKeyFile.Parsed.Ok
                assertEquals(key, parsed.file.publicKey, "the public half is readable before the passphrase")
                assertTrue(parsed.file.isEncrypted)
                assertEquals(rounds.toInt(), parsed.file.rounds)
                val ok = open(file, passphrase)
                assertIs<OpenSshKeyFile.Decrypted.Ok>(ok, "$name $cipher")
                ok.key.close()
                assertEquals(OpenSshKeyFile.Decrypted.WrongPassphrase, open(file, "correct horse battery!"), "$name $cipher wrong")
            }
        }
    }

    @Test
    fun whatWeWriteSshKeygenOpensWithTheNewPassphraseAndNotTheOld() = Tools.tempDir { dir ->
        Tools.require("ssh-keygen")
        for (type in listOf(listOf("-t", "ed25519"), listOf("-t", "rsa", "-b", "2048"), listOf("-t", "ecdsa", "-b", "384"))) {
            val original = Tools.keygen(dir, "orig-${type.last()}", type, "old passphrase 1")
            val key = (open(original, "old passphrase 1") as OpenSshKeyFile.Decrypted.Ok).key
            val newPass = "new passphrase Ω 2"
            val written = File(dir, "rekeyed-${type.last()}").apply {
                writeBytes(key.use { OpenSshKeyFile.write(it, SecretBytes.utf8(newPass.toCharArray()), rounds = 16) })
                Tools.ownerOnly(this)
            }
            val printed = Tools.ok(listOf("ssh-keygen", "-y", "-P", newPass, "-f", written.path)).text.trim()
            assertEquals(SshPublicKey.parse(File(original.path + ".pub").readText()), SshPublicKey.parse(printed))
            val refused = Tools.run(listOf("ssh-keygen", "-y", "-P", "old passphrase 1", "-f", written.path))
            assertTrue(refused.exit != 0, "the old passphrase no longer opens it")
            assertIs<OpenSshKeyFile.Decrypted.Ok>(open(written, newPass)).key.close()
        }
    }

    @Test
    fun aCanaryKeyWrittenInTheClearIsEncryptedByTheRealSshKeygen() = Tools.tempDir { dir ->
        // 03 §3.1: our writer makes the clear file, OpenSSH encrypts it, and we import what OpenSSH made.
        Tools.require("ssh-keygen")
        val seed = cx.m42.superizer.testing.Canary.ED25519_SEED.reveal()
        val file = File(dir, "canary")
        file.writeBytes(PrivateKey.ed25519(seed, "canary").use { OpenSshKeyFile.write(it, null) })
        Tools.ownerOnly(file)
        seed.fill(0)
        Tools.ok(listOf("ssh-keygen", "-p", "-N", passphrase, "-a", "16", "-f", file.path))
        val opened = assertIs<OpenSshKeyFile.Decrypted.Ok>(open(file, passphrase))
        opened.key.use { key -> key.ed25519Seed().use { assertEquals("SUPERIZER-CANARY-ED25519-SEED-01", it.bytes.decodeToString()) } }
    }

    @Test
    fun everyRefusalNamesItsReason() = Tools.tempDir { dir ->
        Tools.require("ssh-keygen")
        fun refusal(bytes: ByteArray) = (OpenSshKeyFile.parse(bytes) as? OpenSshKeyFile.Parsed.Refused)?.refusal

        val chacha = Tools.keygen(dir, "chacha", listOf("-t", "ed25519"), passphrase, listOf("-Z", "chacha20-poly1305@openssh.com", "-a", "4"))
        assertEquals(ImportRefusal.UnsupportedCipher, refusal(chacha.readBytes()))
        val pem = Tools.keygen(dir, "pem", listOf("-t", "rsa", "-b", "2048"), "", listOf("-m", "PEM"))
        assertEquals(ImportRefusal.OldFormat, refusal(pem.readBytes()))
        assertEquals(ImportRefusal.PublicKeyOnly, refusal(File(chacha.path + ".pub").readBytes()))
        val short = Tools.keygen(dir, "short", listOf("-t", "rsa", "-b", "1024"))
        assertEquals(ImportRefusal.RsaTooShort, refusal(short.readBytes()))
        assertEquals(ImportRefusal.PuttyFormat, refusal("PuTTY-User-Key-File-3: ssh-ed25519\n".toByteArray()))
        assertEquals(ImportRefusal.NotAKey, refusal("hello".toByteArray()))
        assertEquals(ImportRefusal.NotAKey, refusal(byteArrayOf(0, -1, 3)))
        val broken = String(chacha.readBytes()).replace("-----END", "AAAA-----END").toByteArray()
        assertTrue(refusal(broken) != null)
    }

    @Test
    fun aFileEditedByHandIsCorruptedNotAWrongPassphrase() = Tools.tempDir { dir ->
        Tools.require("ssh-keygen")
        val a = Tools.keygen(dir, "a", listOf("-t", "ed25519"))
        val b = Tools.keygen(dir, "b", listOf("-t", "ed25519"))
        // a's public header over b's private section: the inner key differs from the outer one.
        val fileA = OpenSshKeyFile.parse(a.readBytes()) as OpenSshKeyFile.Parsed.Ok
        val keyB = (OpenSshKeyFile.parse(b.readBytes()) as OpenSshKeyFile.Parsed.Ok).file.decrypt(null) as OpenSshKeyFile.Decrypted.Ok
        val franken = PrivateKey(fileA.file.publicKey, "x", keyB.key.fields.copy())
        val written = OpenSshKeyFile.write(franken, null)
        assertEquals(OpenSshKeyFile.Decrypted.Corrupted, (OpenSshKeyFile.parse(written) as OpenSshKeyFile.Parsed.Ok).file.decrypt(null))
    }
}
