package cx.m42.unitool.kit.ssh

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The kit's synchronous age path — the one the browser host uses (passwords-browser D545) —
 * against the real `age` and `ssh-keygen`, with fresh keys every run: what `age` seals to a key
 * `ssh-keygen` made, the kit opens with that key's file; what the kit seals, `age -d -i` opens.
 */
class AgeInteropTest {

    private fun load(file: File): PrivateKey {
        val parsed = assertIs<OpenSshKeyFile.Parsed.Ok>(OpenSshKeyFile.parse(file.readBytes())).file
        return assertIs<OpenSshKeyFile.Decrypted.Ok>(parsed.decrypt(null)).key
    }

    @Test
    fun bothWaysForEd25519AndRsa() = Tools.tempDir { dir ->
        Tools.require("ssh-keygen", "age")
        for ((name, args) in listOf("ed" to listOf("-t", "ed25519"), "rsa" to listOf("-t", "rsa", "-b", "3072"))) {
            val keyFile = Tools.keygen(dir, name, args)
            val pub = File(keyFile.path + ".pub")
            val message = "password for $name: p@ss\"wörd".encodeToByteArray()
            val sealedByAge = Tools.ok(listOf("age", "-R", pub.path), input = message).out
            load(keyFile).use { key ->
                val opened = assertIs<Age.Opened.Ok>(Age.decrypt(sealedByAge, listOf(Age.identity(key)!!)))
                assertEquals(message.decodeToString(), opened.plaintext.decodeToString(), name)
                val sealedByKit = Age.encrypt(message, listOf(key.publicKey))
                val openedByAge = Tools.ok(listOf("age", "-d", "-i", keyFile.path), input = sealedByKit).out
                assertEquals(message.decodeToString(), openedByAge.decodeToString(), name)
            }
        }
    }
}
