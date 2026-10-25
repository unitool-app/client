package cx.m42.services.ssh

import cx.m42.unitool.kit.ssh.SshPublicKey
import cx.m42.unitool.kit.ssh.OpenSshKeyFile
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.ssh.ImportOutcome
import cx.m42.superizer.ssh.SshOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Other devices' public keys (.ai-notes/public-keys, D506–D512): kept, refused, and what is sent to them opens on the other side. */
class PublicKeyBookTest {

    private val pass = "a passphrase long enough"

    @Test
    fun aPastedLineIsSavedUnderItsCommentAndSurvivesARestart() = runTest {
        val f = KeyringFixture(backgroundScope)
        val book = f.keyring.publicKeys
        val seen = assertIs<AddKnownOutcome.Ok>(book.inspect("\n  ${Fixtures.ED25519}  \n"))
        assertEquals("fixture", seen.comment)
        assertTrue(book.keys.value.isEmpty(), "inspect stores nothing")

        assertIs<AddKnownOutcome.Ok>(book.add(seen.key, ""))
        val saved = book.keys.value.single()
        assertEquals("ED25519", saved.name, "a blank name falls back to the kind")
        book.rename(saved.id, "  Ivan's phone ")
        assertEquals("Ivan's phone", book.keys.value.single().name)
        assertEquals(Fixtures.ED25519.substringBeforeLast(' ') + " Ivan's phone", book.keys.value.single().line)

        val again = cx.m42.services.ssh.keyring.Keyring(f.records, f.prompter, null, f.clock, f.platform, backgroundScope)
        assertEquals(listOf(saved.id), again.publicKeys.keys.value.map { it.id })
        assertEquals(listOf("Ivan's phone"), again.publicKeys.readOnly.keys.value.map { it.name })
    }

    @Test
    fun whatAgeCannotEncryptToIsRefusedAndSoIsAPrivateKey() = runTest {
        val book = KeyringFixture(backgroundScope).keyring.publicKeys
        assertIs<AddKnownOutcome.CannotEncryptTo>(book.inspect(Fixtures.ECDSA))
        assertIs<AddKnownOutcome.CannotEncryptTo>(book.inspect(Fixtures.RSA_1024))
        assertIs<AddKnownOutcome.Ok>(book.inspect(Fixtures.RSA_3072))
        assertIs<AddKnownOutcome.NotAPublicKey>(book.inspect(plainFile().decodeToString()))
        assertIs<AddKnownOutcome.NotAPublicKey>(book.inspect("hello"))
        assertIs<AddKnownOutcome.NotAPublicKey>(book.inspect(Fixtures.ED25519 + "\n" + Fixtures.RSA_3072), "one key at a time")
        val ecdsa = SshPublicKey.parse(Fixtures.ECDSA)!!
        assertIs<AddKnownOutcome.CannotEncryptTo>(book.add(ecdsa, "enclave"))
        assertTrue(book.keys.value.isEmpty())
    }

    @Test
    fun anOwnKeyAndASecondCopyAreNotAdded() = runTest {
        val f = KeyringFixture(backgroundScope)
        val imported = assertIs<ImportOutcome.Imported>(f.admin.import(encryptedFile(pass, comment = "mine"), pass.toCharArray(), ImportOptions()))
        val book = f.keyring.publicKeys
        assertEquals("mine", assertIs<AddKnownOutcome.OwnKey>(book.inspect(imported.key.publicKey)).comment)

        book.add(SshPublicKey.parse(Fixtures.ED25519)!!, "phone")
        assertEquals("phone", assertIs<AddKnownOutcome.AlreadySaved>(book.inspect(Fixtures.ED25519)).name)
        assertIs<AddKnownOutcome.AlreadySaved>(book.add(SshPublicKey.parse(Fixtures.ED25519)!!, "twice"))
        assertEquals(1, book.keys.value.size)
    }

    @Test
    fun theFileStaysWhileAPublicKeyIsLeftAndGoesWithTheLast() = runTest {
        val f = KeyringFixture(backgroundScope)
        val imported = assertIs<ImportOutcome.Imported>(f.admin.import(encryptedFile(pass), pass.toCharArray(), ImportOptions()))
        val book = f.keyring.publicKeys
        book.add(SshPublicKey.parse(Fixtures.ED25519)!!, "phone")
        assertIs<SshOutcome.Ok<Unit>>(f.admin.delete(imported.key.id))
        assertNotNull(f.records.text, "the saved public key keeps the file")
        assertEquals(1, book.keys.value.size)

        book.remove(book.keys.value.single().id)
        assertNull(f.records.text, "nothing left, no file")
    }

    @Test
    fun whatOnePhoneEncryptsToTheOthersSavedKeyTheOtherOpens() = runTest {
        // Phone B has the key; phone A only the line B showed as a QR code.
        val b = KeyringFixture(backgroundScope)
        val own = assertIs<ImportOutcome.Imported>(b.admin.import(encryptedFile(pass, comment = "phone-b"), pass.toCharArray(), ImportOptions()))
        val shown = own.key.publicKey + " " + own.key.comment

        val a = KeyringFixture(backgroundScope)
        val seen = assertIs<AddKnownOutcome.Ok>(a.keyring.publicKeys.inspect(shown))
        a.keyring.publicKeys.add(seen.key, seen.comment)
        val recipient = a.keyring.publicKeys.readOnly.keys.value.single()
        val message = "accounts".encodeToByteArray()
        val sealed = Age.encrypt(message, listOf(recipient.key))

        b.prompter.passphrases.add(pass)
        val opened = b.app("2fa").decrypt(sealed)
        assertContentEquals(message, assertIs<SshOutcome.Ok<ByteArray>>(opened).value)
        assertFalse(OpenSshKeyFile.parse(a.records.text!!.encodeToByteArray()) is OpenSshKeyFile.Parsed.Ok, "A holds no private key")
    }
}
