package cx.m42.unitool.kit.ssh

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The key files and age files the real tools wrote, opened by the kit on every target — the native
 * binary's included, which is where the browser host does it (passwords-browser D545).
 */
class KitSshTest {

    private fun key(text: String): PrivateKey {
        val file = assertIs<OpenSshKeyFile.Parsed.Ok>(OpenSshKeyFile.parse(text.encodeToByteArray())).file
        assertTrue(!file.isEncrypted)
        return assertIs<OpenSshKeyFile.Decrypted.Ok>(file.decrypt(null)).key
    }

    private fun open(file: ByteArray, key: PrivateKey): String {
        val identity = assertNotNull(Age.identity(key))
        val opened = assertIs<Age.Opened.Ok>(Age.decrypt(file, listOf(identity)))
        assertEquals(key.publicKey, opened.identity)
        return opened.plaintext.decodeToString()
    }

    @Test
    fun theKeysReadAsSshKeygenWroteThem() {
        key(RealToolFixtures.ED25519_KEY).use { k ->
            assertEquals(SshPublicKey.parse(RealToolFixtures.ED25519_PUB), k.publicKey)
            assertEquals("fixture-ed25519", k.comment)
        }
        key(RealToolFixtures.RSA_KEY).use { k ->
            assertEquals(SshPublicKey.parse(RealToolFixtures.RSA_PUB), k.publicKey)
            assertEquals("RSA 2048", k.publicKey.label)
        }
    }

    @Test
    fun aKeyUnderAPassphraseIsSeenBeforeAnythingIsAsked() {
        val file = assertIs<OpenSshKeyFile.Parsed.Ok>(OpenSshKeyFile.parse(RealToolFixtures.LOCKED_KEY.encodeToByteArray())).file
        assertTrue(file.isEncrypted)
        assertEquals(SshPublicKey.parse(RealToolFixtures.LOCKED_PUB), file.publicKey)
        assertIs<OpenSshKeyFile.Decrypted.WrongPassphrase>(file.decrypt(null))
    }

    @Test
    fun ageCannotUseEcdsaSoTheKitSaysSo() {
        val ec = key(RealToolFixtures.ECDSA_KEY)
        assertTrue(!Age.supports(ec.publicKey))
        assertEquals(null, Age.identity(ec))
    }

    @Test
    fun whatRealAgeSealedOpensHere() {
        key(RealToolFixtures.ED25519_KEY).use { assertEquals("hello from real age", open(RealToolFixtures.ED25519_AGE_ARMORED.encodeToByteArray(), it)) }
        key(RealToolFixtures.RSA_KEY).use { assertEquals("hello from real age to rsa", open(Base64.decode(RealToolFixtures.RSA_AGE_BASE64), it)) }
        val both = Base64.decode(RealToolFixtures.BOTH_AGE_BASE64)
        key(RealToolFixtures.ED25519_KEY).use { assertEquals("both", open(both, it)) }
        key(RealToolFixtures.RSA_KEY).use { assertEquals("both", open(both, it)) }
    }

    @Test
    fun aRoundTripAndATamperedFile() {
        key(RealToolFixtures.ED25519_KEY).use { k ->
            val sealed = Age.encrypt("round".encodeToByteArray(), listOf(k.publicKey))
            assertEquals("round", open(sealed, k))
            val tampered = sealed.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }
            assertIs<Age.Opened.Failed>(Age.decrypt(tampered, listOf(Age.identity(k)!!)))
            key(RealToolFixtures.RSA_KEY).use { other -> assertIs<Age.Opened.NoIdentity>(Age.decrypt(sealed, listOf(Age.identity(other)!!))) }
        }
    }
}
