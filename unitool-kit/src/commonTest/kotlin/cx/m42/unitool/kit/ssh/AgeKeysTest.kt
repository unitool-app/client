package cx.m42.unitool.kit.ssh

import cx.m42.unitool.kit.crypto.Bech32
import cx.m42.unitool.kit.crypto.scrypt
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * age's X25519 and scrypt on every target (cr-restore D604, D615, D618). The work factor 18 of a
 * real `age -p` file needs 256 MiB, so that one is opened in `desktopTest`; here scrypt runs small.
 */
class AgeKeysTest {

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun scryptOnTheRfc7914Vectors() {
        assertEquals(
            "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442" +
                "fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906",
            scrypt(ByteArray(0), ByteArray(0), 16, 1, 1, 64).hex(),
        )
        assertEquals(
            "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b373162" +
                "2eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
            scrypt("password".encodeToByteArray(), "NaCl".encodeToByteArray(), 1024, 8, 16, 64).hex(),
        )
    }

    @Test
    fun bech32RoundTripsAndRefusesATypo() {
        val data = ByteArray(32) { it.toByte() }
        val text = Bech32.encode("age", data)
        assertContentEquals(data, Bech32.decode(text)!!.data)
        assertContentEquals(data, Bech32.decode(text.uppercase())!!.data)
        val typo = text.dropLast(1) + (if (text.last() == 'q') 'p' else 'q')
        assertNull(Bech32.decode(typo))
        assertNull(Bech32.decode(text.substring(0, 10) + text.substring(10).uppercase()))
    }

    @Test
    fun theRealToolsIdentityFileAndBothFormsOpen() {
        val identities = assertNotNull(AgeKeys.parseIdentities(AgeKeysFixtures.IDENTITY_FILE))
        assertEquals(AgeKeysFixtures.RECIPIENT, identities.single().recipient)
        for (file in listOf(Base64.Default.decode(AgeKeysFixtures.TO_X25519), AgeKeysFixtures.TO_X25519_ARMORED.encodeToByteArray())) {
            val opened = assertIs<AgeKeys.Opened.Ok>(AgeKeys.decrypt(file, identities = identities))
            assertEquals(AgeKeysFixtures.PLAINTEXT, Base64.Default.encode(opened.plaintext))
        }
        assertEquals(AgeKeys.Opened.NoIdentity, AgeKeys.decrypt(Base64.Default.decode(AgeKeysFixtures.TO_X25519), identities = listOf(AgeKeys.generate())))
    }

    @Test
    fun whatAFileIsForIsReadWithoutAKey() {
        assertIs<AgeKeys.Recipient.X25519>(AgeKeys.recipients(Base64.Default.decode(AgeKeysFixtures.TO_X25519))!!.single())
        val scryptOne = assertIs<AgeKeys.Recipient.Scrypt>(AgeKeys.recipients(Base64.Default.decode(AgeKeysFixtures.WITH_PASSPHRASE))!!.single())
        assertEquals(18, scryptOne.logN)
        assertNull(AgeKeys.recipients("not age".encodeToByteArray()))
    }

    @Test
    fun x25519BothWaysOurselves() {
        val identity = AgeKeys.generate()
        val sealed = AgeKeys.encryptTo("hi".encodeToByteArray(), listOf(AgeKeys.parseRecipient(identity.recipient)!!))
        assertEquals("hi", assertIs<AgeKeys.Opened.Ok>(AgeKeys.decrypt(sealed, identities = listOf(identity))).plaintext.decodeToString())
    }

    @Test
    fun aPassphraseFileSmallEnoughForEveryTarget() {
        val sealed = AgeKeys.encryptWithPassphrase("secret".encodeToByteArray(), "pw".encodeToByteArray(), logN = 10)
        assertEquals("secret", assertIs<AgeKeys.Opened.Ok>(AgeKeys.decrypt(sealed, passphrase = "pw".encodeToByteArray())).plaintext.decodeToString())
        assertEquals(AgeKeys.Opened.NoIdentity, AgeKeys.decrypt(sealed, passphrase = "wrong".encodeToByteArray()))
        assertEquals(AgeKeys.Opened.NoIdentity, AgeKeys.decrypt(sealed))
    }

    @Test
    fun tooHardWorkIsRefusedBeforeItStarts() {
        val sealed = AgeKeys.encryptWithPassphrase("x".encodeToByteArray(), "pw".encodeToByteArray(), logN = 10)
        assertEquals(10, assertIs<AgeKeys.Opened.TooHard>(AgeKeys.decrypt(sealed, passphrase = "pw".encodeToByteArray(), maxLogN = 9)).logN)
        assertEquals(18, assertIs<AgeKeys.Opened.TooHard>(AgeKeys.decrypt(Base64.Default.decode(AgeKeysFixtures.WITH_PASSPHRASE), passphrase = "pw".encodeToByteArray(), maxLogN = 16)).logN)
    }

    @Test
    fun aDamagedPayloadIsDamagedNotAWrongKey() {
        val identity = AgeKeys.generate()
        val sealed = AgeKeys.encryptTo("hello".encodeToByteArray(), listOf(AgeKeys.parseRecipient(identity.recipient)!!))
        sealed[sealed.size - 1] = (sealed.last() + 1).toByte()
        assertIs<AgeKeys.Opened.Damaged>(AgeKeys.decrypt(sealed, identities = listOf(identity)))
    }
}
