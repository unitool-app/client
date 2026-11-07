package cx.m42.services.ssh

import cx.m42.services.ssh.key.SoftwareOps
import cx.m42.services.ssh.keyring.HostAction
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.ssh.ImportOutcome
import cx.m42.superizer.ssh.KeyCapability
import cx.m42.superizer.ssh.KeyOperation
import cx.m42.superizer.ssh.SshOutcome
import cx.m42.superizer.ssh.UsePolicy
import cx.m42.superizer.testing.Canary
import cx.m42.superizer.testing.LeakScanner
import cx.m42.unitool.kit.crypto.SecretBytes
import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.ssh.OpenSshKeyFile
import cx.m42.unitool.kit.ssh.PrivateKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Sending a key to another device of the person's, and checking that a key's lock still opens it
 * (.ai-notes/key-share): what leaves is age to the other device's public key, never level 1, and
 * level 3's file is sent as stored; the check asks the lock even inside a session and keeps nothing.
 */
class KeyShareTest {

    private val canaries = listOf(Canary.ED25519_SEED, Canary.PASSPHRASE)
    private val pass = Canary.PASSPHRASE.reveal().decodeToString().let { it + "-and-more" }

    /** The other phone: its private half here only to open what was sent to it. */
    private val phone = PrivateKey.ed25519(secureRandomBytes(32), "phone")
    private val phoneKey = KnownKey(phone.publicKey, "Pixel", addedAt = 0)

    private suspend fun open(sealed: ByteArray): ByteArray =
        assertIs<Age.Opened.Ok>(Age.decrypt(sealed, listOf(SoftwareOps.ageIdentity(phone)!!))).plaintext

    private suspend fun KeyringFixture.importCanary(options: ImportOptions = ImportOptions()) =
        assertIs<ImportOutcome.Imported>(admin.import(canaryFile(pass), pass.toCharArray(), options)).key

    // ------------------------------------------------------------ export

    @Test
    fun levelThreeSendsItsFileAsStoredAfterAConfirmation() = runTest {
        val f = KeyringFixture(backgroundScope)
        val key = f.importCanary()
        assertEquals(3, key.storage.level)

        f.prompter.confirmAnswer = false
        assertEquals(SshOutcome.Cancelled, f.keyring.export(key.id, phoneKey))
        f.prompter.confirmAnswer = true
        val sealed = assertIs<SshOutcome.Ok<ByteArray>>(f.keyring.export(key.id, phoneKey)).value
        assertEquals(listOf("confirm:Decrypt", "confirm:Decrypt"), f.prompter.asked, "asked like a delete, and never the passphrase: nothing is decrypted")

        val sent = open(sealed)
        assertContentEquals(RecordsText(f.records.text!!).file(key.id), sent, "the stored file, byte for byte")
        assertTrue(assertIs<OpenSshKeyFile.Parsed.Ok>(OpenSshKeyFile.parse(sent)).file.isEncrypted, "still under its passphrase")
        assertEquals(emptyList(), LeakScanner.scan(f.everything() + sealed, canaries))

        val lines = f.admin.journal(key.id).value.filter { it.namespace == HostAction.EXPORT }
        assertEquals(listOf("cancelled", "ok"), lines.map { it.outcome })
        assertTrue(lines.all { it.app == null && it.operation == KeyOperation.Decrypt })
    }

    @Test
    fun levelTwoSendsWhatTheChipUnsealsAndTheDevicesPromptIsTheConfirmation() = runTest {
        // The key's passphrase kept as a second lock: what is sent is still under it.
        val kept = SoftwareChip(level = 2)
        val f = KeyringFixture(backgroundScope, kept)
        val key = f.importCanary(ImportOptions(keepPassphrase = true))
        val asks = kept.asks
        val sent = open(assertIs<SshOutcome.Ok<ByteArray>>(f.keyring.export(key.id, phoneKey)).value)
        assertEquals(asks + 1, kept.asks, "the device, once")
        assertEquals(0, f.prompter.asked.size, "and no sheet besides it")
        assertTrue(assertIs<OpenSshKeyFile.Parsed.Ok>(OpenSshKeyFile.parse(sent)).file.isEncrypted)
        kept.answer = false
        assertEquals(SshOutcome.Cancelled, f.keyring.export(key.id, phoneKey))

        // A fingerprint alone: the sealed file has no passphrase, and age to the phone is its only lock.
        val bare = SoftwareChip(level = 2, strongBiometric = true)
        val g = KeyringFixture(backgroundScope, bare)
        val other = g.importCanary(ImportOptions(biometricOnly = true))
        val plain = open(assertIs<SshOutcome.Ok<ByteArray>>(g.keyring.export(other.id, phoneKey)).value)
        val file = assertIs<OpenSshKeyFile.Parsed.Ok>(OpenSshKeyFile.parse(plain)).file
        assertTrue(!file.isEncrypted)
        assertEquals(other.publicKey, file.publicKey.authorizedKeysLine())
        file.wipe()
        plain.fill(0)
        assertEquals(emptyList(), LeakScanner.scan(g.everything(), canaries), "nothing of it at rest on this side")
    }

    @Test
    fun aKeyInTheChipIsNeverSentAndNoOneIsAsked() = runTest {
        val chip = SoftwareChip(level = 1, strongBiometric = true)
        val f = KeyringFixture(backgroundScope, chip)
        val key = f.importCanary(ImportOptions(biometricOnly = true))
        val asks = chip.asks
        assertEquals(SshOutcome.NotAllowed("in-chip"), f.keyring.export(key.id, phoneKey))
        assertEquals(asks, chip.asks)
        assertEquals(0, f.prompter.asked.size)
        assertTrue(f.admin.journal(key.id).value.none { it.namespace == HostAction.EXPORT })
    }

    // ------------------------------------------------------------ verify

    @Test
    fun verifyAsksTheLockEvenInsideASessionAndKeepsNothing() = runTest {
        val f = KeyringFixture(backgroundScope)
        val key = f.importCanary(ImportOptions(policy = UsePolicy.Session(60)))
        f.prompter.passphrases.addAll(listOf(pass, pass))
        assertIs<SshOutcome.Ok<*>>(f.app("git").sign(key.id, "git", "x".toByteArray()))
        val open = f.keyring.openSessionsForTest().single()

        assertEquals(SshOutcome.Ok(true), f.keyring.verify(key.id))
        assertEquals(2, f.prompter.count("passphrase"), "the session did not answer for the lock")
        assertEquals(listOf(open), f.keyring.openSessionsForTest(), "and the check opened no session of its own")
        assertTrue(!open.closed, "nor closed the person's")

        f.prompter.passphrases.addAll(listOf("wrong one 1", "wrong one 2", "wrong one 3"))
        assertEquals(SshOutcome.Failed("wrong-passphrase"), f.keyring.verify(key.id))
        f.prompter.passphrases.add(null)
        assertEquals(SshOutcome.Cancelled, f.keyring.verify(key.id))

        val lines = f.admin.journal(key.id).value.filter { it.namespace == HostAction.VERIFY }
        assertEquals(listOf("ok", "failed:wrong-passphrase", "cancelled"), lines.map { it.outcome })
        assertEquals(emptyList(), LeakScanner.scan(f.everything(), canaries))
    }

    @Test
    fun verifyUsesTheChipForAKeyInsideIt() = runTest {
        val chip = SoftwareChip(level = 1, strongBiometric = true)
        val f = KeyringFixture(backgroundScope, chip)
        val key = f.importCanary(ImportOptions(biometricOnly = true))
        val asks = chip.asks
        assertEquals(SshOutcome.Ok(true), f.keyring.verify(key.id))
        assertEquals(asks + 1, chip.asks)
        chip.answer = false
        assertEquals(SshOutcome.Cancelled, f.keyring.verify(key.id))
        chip.dropEverything()
        assertEquals(SshOutcome.Unusable, f.keyring.verify(key.id))
    }

    @Test
    fun verifySignsAndChecksAKeyThatOnlySigns() = runTest {
        val f = KeyringFixture(backgroundScope)
        val key = assertIs<ImportOutcome.Imported>(f.admin.import(ecdsaFile(GOOD), GOOD.toCharArray(), ImportOptions())).key
        assertTrue(KeyCapability.DecryptAge !in key.can)
        f.prompter.passphrases.add(GOOD)
        assertEquals(SshOutcome.Ok(true), f.keyring.verify(key.id))
        assertEquals(listOf("passphrase:Sign:0"), f.prompter.asked)
        assertEquals(KeyOperation.Sign, f.admin.journal(key.id).value.last { it.namespace == HostAction.VERIFY }.operation)
    }

    /** A P-256 key under [passphrase], made by the JVM: the kind a chip makes, here at level 3. */
    private fun ecdsaFile(passphrase: String): ByteArray {
        val pair = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        fun fixed(n: java.math.BigInteger): ByteArray {
            val b = n.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
            return ByteArray(32 - b.size) + b
        }
        val point = (pair.public as java.security.interfaces.ECPublicKey).w
        val q = byteArrayOf(4) + fixed(point.affineX) + fixed(point.affineY)
        val d = fixed((pair.private as java.security.interfaces.ECPrivateKey).s)
        return PrivateKey.ecdsa(cx.m42.unitool.kit.ssh.SshPublicKey.Curve.P256, q, d, "signer").use { key ->
            SecretBytes.utf8(passphrase.toCharArray()).use { OpenSshKeyFile.write(key, it, rounds = 4) }
        }
    }
}
