package cx.m42.services.ssh

import cx.m42.unitool.kit.ssh.PrivateKey
import cx.m42.unitool.kit.ssh.SshPublicKey
import cx.m42.services.ssh.diagnostics.KeyFindings
import cx.m42.unitool.kit.ssh.OpenSshKeyFile
import cx.m42.superizer.app.AppId
import cx.m42.superizer.diagnostics.DiagnosticsCode
import cx.m42.superizer.ssh.Chip
import cx.m42.superizer.ssh.GeneratedKind
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.ssh.ImportOutcome
import cx.m42.superizer.ssh.ImportRefusal
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.ssh.KeyPurpose
import cx.m42.superizer.ssh.KeyStorage
import cx.m42.superizer.ssh.SshAgent
import cx.m42.superizer.ssh.SshKeyAdmin
import cx.m42.superizer.ssh.SshKeyring
import cx.m42.superizer.ssh.SshOutcome
import cx.m42.superizer.ssh.UsePolicy
import cx.m42.superizer.testing.Canary
import cx.m42.superizer.testing.LeakScanner
import cx.m42.unitool.kit.crypto.SecretBytes
import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.crypto.wiped
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The keyring's invariants on the JVM (03 §1, §5): I1 nothing at rest, I4 no method hands out a
 * private key, I5 nothing in the logs, I6 inputs zeroed, I8 grants, I9 namespaces, I11 delete
 * leaves nothing, I15 a session key is gone after lockAll and after its window, I19 no key without
 * a twelve-character passphrase.
 */
class KeyringTest {

    private val canaries = listOf(Canary.ED25519_SEED, Canary.PASSPHRASE)
    private val pass = Canary.PASSPHRASE.reveal().decodeToString().let { it + "-and-more" } // ≥ 12 and still the canary inside

    private suspend fun KeyringFixture.importCanary(options: ImportOptions = ImportOptions()): ImportOutcome.Imported {
        val file = canaryFile(pass)
        return assertIs(admin.import(file, pass.toCharArray(), options))
    }

    // ------------------------------------------------------------ I1, I5: nothing at rest, nothing said

    @Test
    fun theCanaryIsNowhereAfterImportSignDecryptAndDelete() = runTest {
        for (chip in listOf(null, SoftwareChip(level = 2, strongBiometric = true), SoftwareChip(level = 1, strongBiometric = true))) {
            val f = KeyringFixture(backgroundScope, chip)
            // In the chip with a lock of its own (D247): the finger.
            val imported = f.importCanary(ImportOptions(biometricOnly = chip != null))
            assertEquals(if (chip == null) 3 else chip.device.levels.single(), imported.key.storage.level)
            f.prompter.passphrases.addAll(List(4) { pass })

            val app = f.app("git")
            val signed = app.sign(imported.key.id, "git", "tree 1".toByteArray())
            assertIs<SshOutcome.Ok<*>>(signed, "sign at level ${imported.key.storage.level}")
            val publicKey = SshPublicKey.parse(imported.key.publicKey)!!
            val sealed = Age.encrypt(Canary.PLAINTEXT.reveal(), listOf(publicKey))
            val opened = app.decrypt(sealed)
            assertContentEquals(Canary.PLAINTEXT.reveal(), assertIs<SshOutcome.Ok<ByteArray>>(opened, "decrypt at level ${imported.key.storage.level}").value)
            val found = LeakScanner.scan(f.everything(), canaries)
            assertEquals(emptyList(), found, "at level ${imported.key.storage.level}")
            // Every object handed back says nothing either.
            assertEquals(emptyList(), LeakScanner.scan(listOf(imported, signed, imported.key).joinToString().encodeToByteArray(), canaries))

            assertIs<SshOutcome.Ok<Unit>>(f.admin.delete(imported.key.id))
            assertEquals(null, f.records.text, "the last key took the file with it")
        }
    }

    @Test
    fun everyFailureIsSilentToo() = runTest {
        val f = KeyringFixture(backgroundScope)
        assertEquals(ImportOutcome.WrongPassphrase, f.admin.import(canaryFile(pass), "wrong passphrase!".toCharArray(), ImportOptions()))
        assertIs<ImportOutcome.Rejected>(f.admin.import("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----".toByteArray(), pass.toCharArray(), ImportOptions()))
        val imported = f.importCanary()
        f.prompter.passphrases.addAll(listOf("wrong one 1", "wrong one 2", "wrong one 3"))
        val failed = f.app("git").sign(imported.key.id, "git", "x".toByteArray())
        assertEquals(SshOutcome.Failed("wrong-passphrase"), failed)
        f.prompter.passphrases.add(null)
        assertEquals(SshOutcome.Cancelled, f.app("git").sign(imported.key.id, "git", "x".toByteArray()))
        assertEquals(emptyList(), LeakScanner.scan(f.everything(), canaries))
        assertTrue(f.records.text!!.contains("\"outcome\":\"cancelled\""), "the journal says what happened, not what was signed")
    }

    // ------------------------------------------------------------ I6: inputs zeroed whatever happens

    @Test
    fun theFileAndThePassphraseAreZeroedOnEveryPath() = runTest {
        val f = KeyringFixture(backgroundScope)
        fun check(file: ByteArray, passphrase: CharArray?, newPassphrase: CharArray? = null) {
            assertTrue(file.all { it == 0.toByte() }, "file")
            passphrase?.let { p -> assertTrue(p.all { it == '\u0000' }, "passphrase") }
            newPassphrase?.let { p -> assertTrue(p.all { it == '\u0000' }, "new passphrase") }
        }
        var file = canaryFile(pass); var p = pass.toCharArray()
        f.admin.import(file, p, ImportOptions()); check(file, p)
        file = canaryFile(pass); p = "wrong wrong wrong".toCharArray()
        f.admin.import(file, p, ImportOptions()); check(file, p)
        file = "not a key".toByteArray(); p = pass.toCharArray()
        f.admin.import(file, p, ImportOptions()); check(file, p)
        file = plainFile(); val n = "twelve chars!".toCharArray()
        f.admin.import(file, null, ImportOptions(), n); check(file, null, n)
    }

    // ------------------------------------------------------------ D167 / I19: the passphrase rule

    @Test
    fun noKeyWithoutATwelveCharacterPassphrase() = runTest {
        val f = KeyringFixture(backgroundScope)
        assertEquals(ImportOutcome.NeedsNewPassphrase(0), f.admin.import(plainFile(), null, ImportOptions()))
        assertEquals(ImportOutcome.NeedsNewPassphrase(11), f.admin.import(encryptedFile("elevenchars"), "elevenchars".toCharArray(), ImportOptions()))
        assertEquals(ImportOutcome.NeedsNewPassphrase(0), f.admin.import(plainFile(), null, ImportOptions(), "too short".toCharArray()))
        assertEquals(null, f.records.text, "nothing was stored")

        val imported = assertIs<ImportOutcome.Imported>(f.admin.import(encryptedFile("elevenchars"), "elevenchars".toCharArray(), ImportOptions(), GOOD.toCharArray()))
        val stored = RecordsText(f.records.text!!).file(imported.key.id)
        val parsed = OpenSshKeyFile.parse(stored) as OpenSshKeyFile.Parsed.Ok
        assertEquals(OpenSshKeyFile.Decrypted.WrongPassphrase, parsed.file.decrypt(SecretBytes.utf8("elevenchars".toCharArray())))
        assertIs<OpenSshKeyFile.Decrypted.Ok>(parsed.file.decrypt(SecretBytes.utf8(GOOD.toCharArray()))).key.close()
        assertEquals(24, parsed.file.rounds, "written with OpenSSH's default rounds")
    }

    @Test
    fun rekeyAsksForTheOldPassphraseAndStoresTheNew() = runTest {
        val f = KeyringFixture(backgroundScope)
        val key = assertIs<ImportOutcome.Imported>(f.admin.import(encryptedFile(GOOD), GOOD.toCharArray(), ImportOptions())).key
        assertEquals(SshOutcome.NotAllowed("short-passphrase"), f.admin.rekey(key.id, "short".toCharArray()))
        f.prompter.passphrases.add(GOOD)
        assertEquals(SshOutcome.Ok(Unit), f.admin.rekey(key.id, "another good passphrase".toCharArray()))
        f.prompter.passphrases.add("another good passphrase")
        assertIs<SshOutcome.Ok<*>>(f.app("git").sign(key.id, "git", "x".toByteArray()))
    }

    // ------------------------------------------------------------ I8, I9: grants and namespaces

    @Test
    fun grantsArePerAppKeyAndPurposeAndOnlyThePersonMakesThem() = runTest {
        val f = KeyringFixture(backgroundScope)
        val key = f.importCanary().key
        f.prompter.passphrases.addAll(List(5) { pass })

        f.prompter.grantAnswer = false
        assertEquals(SshOutcome.Refused, f.app("git").sign(key.id, "git", "x".toByteArray()))
        assertEquals(0, f.prompter.count("passphrase"), "no key was opened for an app the person refused")

        f.prompter.grantAnswer = true
        assertIs<SshOutcome.Ok<*>>(f.app("git").sign(key.id, "git", "x".toByteArray()))
        assertEquals(2, f.prompter.count("grant:git"), "asked again after the refusal, and granted")
        val grantsBefore = f.prompter.count("grant")
        assertIs<SshOutcome.Ok<*>>(f.app("git").sign(key.id, "git", "y".toByteArray()))
        assertEquals(grantsBefore, f.prompter.count("grant"), "a grant held is not asked again")
        assertEquals(listOf(key.id), f.app("git").keys.value.map { it.id })

        // Another app, another namespace: each is its own grant.
        assertEquals(emptyList(), f.app("calculator").keys.value)
        f.prompter.grantAnswer = false
        assertEquals(SshOutcome.Refused, f.app("calculator").sign(key.id, "git", "x".toByteArray()))
        assertEquals(SshOutcome.Refused, f.app("git").sign(key.id, "git@superizer", "x".toByteArray()))
    }

    @Test
    fun theHostsNamespacesAndMalformedOnesAreNeverAnAppsToSign() = runTest {
        val f = KeyringFixture(backgroundScope)
        val key = f.importCanary().key
        for (ns in listOf("superizer-auth@m42.cx", "anything@m42.cx", "", "has space", "x".repeat(65), "slash/no")) {
            assertIs<SshOutcome.NotAllowed>(f.app("git").sign(key.id, ns, "x".toByteArray()), ns)
        }
        assertEquals(0, f.prompter.asked.size, "refused before anyone was asked")
    }

    @Test
    fun theApiHasNoMethodThatReturnsPrivateMaterial() {
        // I4: every public method of the three doors, by name and signature. A new one is a red test
        // until it is listed here with a reason.
        val expected = mapOf(
            SshKeyring::class to setOf("choose", "decrypt", "getKeys", "sign"),
            SshKeyAdmin::class to setOf("delete", "generate", "getDevice", "getKeys", "grants", "import", "inspect", "journal", "lockAll", "rekey", "rename", "revoke"),
            SshAgent::class to setOf("add", "extension", "identities", "lock", "remove", "removeAll", "sign", "unlock"),
        )
        for ((type, names) in expected) {
            // A value class in the signature mangles the JVM name (`sign-dCUM3xA`); the name is what counts.
            val actual = type.java.methods.filter { it.declaringClass == type.java && !it.isSynthetic && !it.name.endsWith("\$default") }
                .map { it.name.substringBefore('-') }.toSet()
            assertEquals(names, actual, "${type.simpleName}")
        }
        // No public type of this module or of the contract has a SecretBytes or a PrivateKey in it.
        val publicTypes = listOf(
            SshPublicKey::class, cx.m42.superizer.ssh.SshKeyInfo::class, cx.m42.superizer.ssh.Inspection::class,
            cx.m42.superizer.ssh.SshSignature::class, cx.m42.superizer.ssh.AgentIdentity::class,
        )
        for (type in publicTypes) {
            type.java.declaredFields.forEach { field ->
                val name = field.type.name
                assertTrue("SecretBytes" !in name && "PrivateKey" !in name, "${type.simpleName}.${field.name}: $name")
            }
        }
        val all = listOf(SshKeyring::class, SshKeyAdmin::class, SshAgent::class).flatMap { it.java.methods.toList() }
        assertTrue(all.none { it.name.contains("export", ignoreCase = true) }, "there is no export of a private key (question 73)")
        assertTrue(all.none { m -> "SecretBytes" in m.returnType.name || "PrivateKey" in m.returnType.name })
    }

    // ------------------------------------------------------------ I15: sessions

    @Test
    fun aSessionKeyIsUsedWithoutAskingThenGoneAfterItsWindowAndAfterLockAll() = runTest {
        val f = KeyringFixture(backgroundScope)
        val key = assertIs<ImportOutcome.Imported>(f.admin.import(canaryFile(pass), pass.toCharArray(), ImportOptions(policy = UsePolicy.Session(60)))).key
        f.prompter.passphrases.addAll(List(3) { pass })
        val git = f.app("git")
        assertIs<SshOutcome.Ok<*>>(git.sign(key.id, "git", "1".toByteArray()))
        assertIs<SshOutcome.Ok<*>>(git.sign(key.id, "git", "2".toByteArray()))
        assertEquals(1, f.prompter.count("passphrase"), "inside the window, asked once")
        assertEquals(1, f.keyring.openSessionsForTest().size)

        f.clock.advance(seconds = 61)
        advanceTimeBy(61_000)
        assertEquals(0, f.keyring.openSessionsForTest().count { !it.closed }, "the window closed and wiped the key")
        assertIs<SshOutcome.Ok<*>>(git.sign(key.id, "git", "3".toByteArray()))
        assertEquals(2, f.prompter.count("passphrase"))

        val open = f.keyring.openSessionsForTest().single()
        f.admin.lockAll()
        assertTrue(open.closed, "lockAll wiped it at once")
        assertIs<SshOutcome.Ok<*>>(git.sign(key.id, "git", "4".toByteArray()))
        assertEquals(3, f.prompter.count("passphrase"))
    }

    // ------------------------------------------------------------ I11, D173: delete

    @Test
    fun deleteAsksAndLeavesNothingAndTheSecretsKeyCannotGo() = runTest {
        val chip = SoftwareChip(level = 1, strongBiometric = true)
        val f = KeyringFixture(backgroundScope, chip)
        val a = f.importCanary(ImportOptions(biometricOnly = true)).key
        val b = assertIs<ImportOutcome.Imported>(f.admin.import(encryptedFile(GOOD), GOOD.toCharArray(), ImportOptions(biometricOnly = true))).key
        f.prompter.passphrases.add(pass)
        f.app("git").sign(a.id, "git", "x".toByteArray())

        f.keyring.setSecretsKey(b.id)
        assertEquals(SshOutcome.NotAllowed("secrets-anchor"), f.admin.delete(b.id))

        f.prompter.confirmAnswer = false
        assertEquals(SshOutcome.Cancelled, f.admin.delete(a.id))
        f.prompter.confirmAnswer = true
        assertEquals(SshOutcome.Ok(Unit), f.admin.delete(a.id))
        val text = f.records.text!!
        assertTrue(a.id.fingerprint !in text, "no record, grant or journal line of the deleted key")
        assertTrue(!chip.exists("cx.m42.services.ssh." + a.id.fingerprint.removePrefix("SHA256:").filter { it.isLetterOrDigit() }.take(16)))
        assertEquals(emptyList(), LeakScanner.scan(f.everything(), canaries))
    }

    @Test
    fun aKeyTheChipDroppedIsUnusableAndStillShown() = runTest {
        val chip = SoftwareChip(level = 1, strongBiometric = true)
        val f = KeyringFixture(backgroundScope, chip)
        val key = f.importCanary(ImportOptions(biometricOnly = true)).key
        chip.dropEverything()
        assertEquals(SshOutcome.Unusable, f.app("git").sign(key.id, "git", "x".toByteArray()))
        val shown = f.admin.keys.value.single()
        assertEquals(false, shown.usable)
        assertEquals(key.publicKey, shown.publicKey, "the public half stays, to find the line in authorized_keys")
    }

    // ------------------------------------------------------------ formats and duplicates

    @Test
    fun everyRefusalAndADuplicateSayWhy() = runTest {
        val f = KeyringFixture(backgroundScope)
        val file = encryptedFile(GOOD)
        assertIs<ImportOutcome.Imported>(f.admin.import(file.copyOf(), GOOD.toCharArray(), ImportOptions()))
        assertIs<ImportOutcome.AlreadyThere>(f.admin.import(file.copyOf(), GOOD.toCharArray(), ImportOptions()))
        assertEquals(ImportOutcome.Rejected(ImportRefusal.PublicKeyOnly), f.admin.import(Fixtures.ED25519.toByteArray(), null, ImportOptions()))
        assertEquals(ImportOutcome.Rejected(ImportRefusal.OldFormat), f.admin.import("-----BEGIN RSA PRIVATE KEY-----\n".toByteArray(), null, ImportOptions()))
        val inspected = f.admin.inspect(file.copyOf())
        assertEquals(true, inspected.encrypted)
        assertEquals(true, inspected.alreadyThere)
        assertEquals(KeyStorage.SealedByPassphrase, inspected.wouldBe)
        assertEquals(null, inspected.comment, "the comment is inside the encrypted part")
    }

    @Test
    fun levelTwoWithTheFilesPassphraseKeptAsksForBoth() = runTest {
        val chip = SoftwareChip(level = 2)
        val f = KeyringFixture(backgroundScope, chip)
        val key = assertIs<ImportOutcome.Imported>(f.admin.import(canaryFile(pass), pass.toCharArray(), ImportOptions(keepPassphrase = true))).key
        val asksAfterImport = chip.asks
        f.prompter.passphrases.add(pass)
        assertIs<SshOutcome.Ok<*>>(f.app("git").sign(key.id, "git", "x".toByteArray()))
        assertEquals(asksAfterImport + 1, chip.asks, "the device, once")
        assertEquals(1, f.prompter.count("passphrase"), "and the passphrase")
        chip.answer = false
        assertEquals(SshOutcome.Cancelled, f.app("git").sign(key.id, "git", "y".toByteArray()))
    }

    // ------------------------------------------------------------ the level the person chose (09, D237–D244)

    @Test
    fun levelThreeOnADeviceWithAChipStaysOutOfTheChip() = runTest {
        val chip = SoftwareChip(level = 1)
        val f = KeyringFixture(backgroundScope, chip)
        val key = f.importCanary(ImportOptions(level = 3)).key
        assertEquals(KeyStorage.SealedByPassphrase, key.storage)
        assertTrue(!chip.exists(aliasOf(key.id)), "nothing of it in the chip")
        val stored = assertIs<OpenSshKeyFile.Parsed.Ok>(OpenSshKeyFile.parse(RecordsText(f.records.text!!).file(key.id))).file
        assertTrue(stored.isEncrypted, "the file the person gave, under its passphrase")
        f.prompter.passphrases.add(pass)
        assertIs<SshOutcome.Ok<*>>(f.app("git").sign(key.id, "git", "x".toByteArray()))
        assertEquals(0, chip.asks, "the passphrase, not the device")
        assertEquals(emptyList(), LeakScanner.scan(f.everything(), canaries))
    }

    @Test
    fun aLevelTheDeviceOrTheKeyCannotHaveIsRefusedAndNothingIsStored() = runTest {
        val bare = KeyringFixture(backgroundScope)
        for (level in listOf(1, 2, 0, 4)) {
            assertEquals(ImportOutcome.Failed("level.unavailable"), bare.admin.import(canaryFile(pass), pass.toCharArray(), ImportOptions(level = level)), "level $level without a chip")
        }
        assertEquals(ImportOutcome.Failed("level.unavailable"), bare.admin.generate(GeneratedKind.Ed25519, ImportOptions(level = 2), GOOD.toCharArray()))
        assertEquals(null, bare.records.text)

        val sealing = KeyringFixture(backgroundScope, SoftwareChip(level = 2))
        assertEquals(ImportOutcome.Failed("level.unavailable"), sealing.admin.import(canaryFile(pass), pass.toCharArray(), ImportOptions(level = 1)), "a chip that only seals")
        // P-256 is born in the chip or not at all: there is no file to seal or to keep under a passphrase.
        val resident = KeyringFixture(backgroundScope, SoftwareChip(level = 1))
        for (level in listOf(2, 3)) {
            assertEquals(ImportOutcome.Failed("level.unavailable"), resident.admin.generate(GeneratedKind.EcdsaP256, ImportOptions(level = level), GOOD.toCharArray()))
        }
        assertEquals(null, sealing.records.text)
        assertEquals(null, resident.records.text)
    }

    @Test
    fun levelTwoUnderAChipThatCouldHoldTheKeyKeepsThePassphraseAsASecondLock() = runTest {
        // Android's default (D244): the key could live inside the chip, and is sealed instead, so its passphrase still counts.
        val chip = SoftwareChip(level = 1)
        val f = KeyringFixture(backgroundScope, chip)
        val key = f.importCanary(ImportOptions(level = 2, keepPassphrase = true)).key
        assertEquals(KeyStorage.SealedByChip(Chip.Tee), key.storage)
        val asksAfterImport = chip.asks
        f.prompter.passphrases.add(pass)
        assertIs<SshOutcome.Ok<*>>(f.app("git").sign(key.id, "git", "x".toByteArray()))
        assertEquals(asksAfterImport + 1, chip.asks, "the device, once")
        assertEquals(1, f.prompter.count("passphrase"), "and the passphrase")
        f.prompter.passphrases.addAll(listOf("wrong one 1", "wrong one 2", "wrong one 3"))
        assertEquals(SshOutcome.Failed("wrong-passphrase"), f.app("git").sign(key.id, "git", "y".toByteArray()), "the device alone does not open it")
        assertEquals(emptyList(), LeakScanner.scan(f.everything(), canaries))

        // "Keep the passphrase" alone, with no level, is the same level 2.
        val g = KeyringFixture(backgroundScope, SoftwareChip(level = 1))
        assertEquals(KeyStorage.SealedByChip(Chip.Tee), g.importCanary(ImportOptions(keepPassphrase = true)).key.storage)
    }

    @Test
    fun aKeyCreatedUnderTheChipCanKeepAPassphrase() = runTest {
        val chip = SoftwareChip(level = 1)
        val f = KeyringFixture(backgroundScope, chip)
        val options = ImportOptions(level = 2, keepPassphrase = true)
        assertEquals(ImportOutcome.NeedsNewPassphrase(0), f.admin.generate(GeneratedKind.Ed25519, options, null))
        assertEquals(ImportOutcome.NeedsNewPassphrase(5), f.admin.generate(GeneratedKind.Ed25519, options, "short".toCharArray()))
        val key = assertIs<ImportOutcome.Imported>(f.admin.generate(GeneratedKind.Ed25519, options, GOOD.toCharArray())).key
        assertEquals(2, key.storage.level)
        f.prompter.passphrases.add(GOOD)
        assertIs<SshOutcome.Ok<*>>(f.app("git").sign(key.id, "git", "x".toByteArray()))
        assertEquals(1, f.prompter.count("passphrase"))

        val chosenThree = assertIs<ImportOutcome.Imported>(f.admin.generate(GeneratedKind.Ed25519, ImportOptions(level = 3), GOOD.toCharArray())).key
        assertEquals(KeyStorage.SealedByPassphrase, chosenThree.storage)
        assertTrue(!chip.exists(aliasOf(chosenThree.id)))
    }

    @Test
    fun onlyAFingerprintIsAskedForWhenThePersonChoseIt() = runTest {
        val chip = SoftwareChip(level = 1, strongBiometric = true)
        val f = KeyringFixture(backgroundScope, chip)
        val key = f.importCanary(ImportOptions(biometricOnly = true)).key
        assertEquals(true, key.biometricOnly)
        assertEquals(true, f.admin.keys.value.single().biometricOnly, "and it stays so in the records")
        assertIs<SshOutcome.Ok<*>>(f.app("git").sign(key.id, "git", "x".toByteArray()))
        assertEquals(listOf(true, true), chip.biometricOnlyAsked, "the import and the use, both bound to the finger")
        assertTrue(DiagnosticsCode.KeyBiometricOnly.id in KeyFindings.of(key, null, inBrowser = false).map { it.code })

        // The same under the chip's seal, with the passphrase as well.
        val sealed = KeyringFixture(backgroundScope, SoftwareChip(level = 1, strongBiometric = true))
        val both = sealed.importCanary(ImportOptions(level = 2, keepPassphrase = true, biometricOnly = true)).key
        assertEquals(2, both.storage.level)
        assertEquals(true, both.biometricOnly)
    }

    @Test
    fun onlyAFingerprintIsRefusedWhereThereIsNoneOrNoChip() = runTest {
        val noFinger = KeyringFixture(backgroundScope, SoftwareChip(level = 1, strongBiometric = false))
        assertEquals(ImportOutcome.Failed("biometric.unavailable"), noFinger.admin.import(canaryFile(pass), pass.toCharArray(), ImportOptions(biometricOnly = true)))
        assertEquals(ImportOutcome.Failed("biometric.unavailable"), noFinger.admin.generate(GeneratedKind.Ed25519, ImportOptions(biometricOnly = true), GOOD.toCharArray()))
        val levelThree = KeyringFixture(backgroundScope, SoftwareChip(level = 1, strongBiometric = true))
        assertEquals(ImportOutcome.Failed("biometric.unavailable"), levelThree.admin.import(canaryFile(pass), pass.toCharArray(), ImportOptions(level = 3, biometricOnly = true)))
        val desktop = KeyringFixture(backgroundScope)
        assertEquals(ImportOutcome.Failed("biometric.unavailable"), desktop.admin.import(canaryFile(pass), pass.toCharArray(), ImportOptions(biometricOnly = true)))
        assertEquals(null, noFinger.records.text)
        assertEquals(null, levelThree.records.text)
        assertEquals(null, desktop.records.text)
    }

    @Test
    fun aKeyInTheChipNeedsALockOfItsOwnNeverTheScreenLockAlone() = runTest {
        // D247: without a fingerprint or face, its passphrase; never the PIN or passcode by itself.
        for (chip in listOf(SoftwareChip(level = 1), SoftwareChip(level = 2), SoftwareChip(level = 1, strongBiometric = true))) {
            val f = KeyringFixture(backgroundScope, chip)
            assertEquals(ImportOutcome.Failed("own-lock-required"), f.admin.import(canaryFile(pass), pass.toCharArray(), ImportOptions()))
            val best = chip.device.levels.single()
            assertEquals(ImportOutcome.Failed("own-lock-required"), f.admin.import(canaryFile(pass), pass.toCharArray(), ImportOptions(level = best)))
            assertEquals(ImportOutcome.Failed("own-lock-required"), f.admin.generate(GeneratedKind.Ed25519, ImportOptions(), GOOD.toCharArray()))
            // A P-256 made in the chip has no file to keep a passphrase on: a biometric or nothing.
            assertEquals(ImportOutcome.Failed("own-lock-required"), f.admin.generate(GeneratedKind.EcdsaP256, ImportOptions(), null))
            assertEquals(null, f.records.text, "nothing stored")
            assertEquals(0, chip.biometricOnlyAsked.size, "and the chip was never asked")
        }
        // Either lock will do, and level 3 is its passphrase already.
        val f = KeyringFixture(backgroundScope, SoftwareChip(level = 1, strongBiometric = true))
        assertEquals(true, f.importCanary(ImportOptions(biometricOnly = true)).key.biometricOnly)
        val kept = assertIs<ImportOutcome.Imported>(f.admin.import(encryptedFile(GOOD), GOOD.toCharArray(), ImportOptions(level = 2, keepPassphrase = true))).key
        assertEquals(true, kept.passphraseKept)
        assertIs<ImportOutcome.Imported>(f.admin.import(encryptedFile(GOOD + "!"), (GOOD + "!").toCharArray(), ImportOptions(level = 3)))
        assertTrue(f.admin.keys.value.none { key ->
            DiagnosticsCode.KeyNoSecondLock.id in KeyFindings.of(key, null, inBrowser = false).map { it.code }
        }, "none of them is left to the screen lock")
    }

    @Test
    fun aKeyFromBeforeTheRuleThatOnlyTheScreenLockGuardsSaysSo() {
        val legacy = cx.m42.superizer.ssh.SshKeyInfo(
            KeyId("SHA256:old"), "ssh-ed25519", "ssh-ed25519 AAAA", "ED25519", "old", KeyStorage.Tee, UsePolicy.EveryUse,
            cx.m42.superizer.ssh.KeyOrigin.Imported, emptySet(), 0,
        )
        assertTrue(DiagnosticsCode.KeyNoSecondLock.id in KeyFindings.of(legacy, null, inBrowser = false).map { it.code })
        assertTrue(DiagnosticsCode.KeyNoSecondLock.id !in KeyFindings.of(legacy.copy(passphraseKept = true, storage = KeyStorage.SealedByChip(Chip.Tee)), null, false).map { it.code })
        assertTrue(DiagnosticsCode.KeyNoSecondLock.id !in KeyFindings.of(legacy.copy(storage = KeyStorage.SealedByPassphrase), null, false).map { it.code })
    }

    private fun aliasOf(id: KeyId) = "cx.m42.services.ssh." + id.fingerprint.removePrefix("SHA256:").filter { it.isLetterOrDigit() }.take(16)

    @Test
    fun anAppChoosesAKeyAndHoldsAGrantForIt() = runTest {
        val f = KeyringFixture(backgroundScope)
        val key = f.importCanary().key
        val chosen = assertIs<SshOutcome.Ok<cx.m42.superizer.ssh.SshKeyInfo>>(f.app("2fa").choose(KeyPurpose.Decrypt)).value
        assertEquals(key.id, chosen.id)
        assertEquals(listOf(key.id), f.app("2fa").keys.value.map { it.id })
        f.prompter.chooseAnswer = { null }
        assertEquals(SshOutcome.Refused, f.app("notes").choose(KeyPurpose.Sign("notes@superizer")))
        assertEquals(SshOutcome.NoKey, KeyringFixture(backgroundScope).app("x").choose(KeyPurpose.Decrypt))
    }
}

/** The records as a test reads them — the stored file of a key, decoded. */
internal class RecordsText(private val text: String) {
    fun file(id: KeyId): ByteArray {
        val records = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObjectOrNull()!!
        val key = (records["keys"] as kotlinx.serialization.json.JsonArray).map { it as kotlinx.serialization.json.JsonObject }
            .first { (it["id"] as kotlinx.serialization.json.JsonPrimitive).content == id.fingerprint }
        return Base64.decode((key["file"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull() = this as? kotlinx.serialization.json.JsonObject
}
