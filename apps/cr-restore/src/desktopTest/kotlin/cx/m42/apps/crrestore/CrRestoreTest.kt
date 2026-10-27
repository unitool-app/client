package cx.m42.apps.crrestore

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import cx.m42.services.files.FileSaver
import cx.m42.services.qr.QrScanner
import cx.m42.superizer.lock.SecretHold
import cx.m42.superizer.runtime.FilePicker
import cx.m42.superizer.runtime.PickedFile
import cx.m42.superizer.ssh.SshKeyring
import cx.m42.superizer.ssh.SshOutcome
import cx.m42.superizer.testing.AppTestScope
import cx.m42.superizer.testing.FakeFilePicker
import cx.m42.superizer.testing.FakeSshKeyring
import cx.m42.superizer.testing.runAppTest
import cx.m42.unitool.kit.crypt.Ucrypt
import cx.m42.unitool.kit.openssl.OpensslEnc
import cx.m42.unitool.kit.split.SplitLink
import cx.m42.unitool.kit.ssh.Age
import cx.m42.unitool.kit.ssh.AgeKeys
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * cr-restore on fakes (D612): each method from a paste, QR codes and a file, to text and to a
 * file; what goes wrong; and that nothing is kept — not in storage, not in a snapshot, not in the
 * arrays once the person starts over.
 */
@OptIn(ExperimentalTestApi::class)
class CrRestoreTest {

    private class FakeHold : SecretHold {
        var held = false
        var outsides = 0
        override fun holding(held: Boolean) {
            this.held = held
        }

        override suspend fun <T> outside(block: suspend () -> T): T {
            outsides++
            return block()
        }
    }

    private class FakeSaver : FileSaver {
        var name: String? = null
        var bytes: ByteArray? = null
        override suspend fun save(suggestedName: String, bytes: ByteArray, mimeType: String): Boolean {
            name = suggestedName
            this.bytes = bytes.copyOf()
            return true
        }
    }

    /** A camera that reads [codes] one per scan. */
    private class Camera(vararg codes: String) : QrScanner {
        private val queue = ArrayDeque(codes.toList())
        override suspend fun scan(): String? = queue.removeFirstOrNull()
    }

    private val hold = FakeHold()
    private val saver = FakeSaver()

    private fun AppTestScope<*>.app() = instance as CrRestoreInstance

    private fun AppTestScope<*>.waitFor(condition: () -> Boolean) {
        ui.waitUntil(timeoutMillis = 30_000) { condition() }
        idle()
    }

    /** Inside the tappable card, whose semantics merge: looked for in the unmerged tree. */
    private fun AppTestScope<*>.assertInside(tag: String) {
        ui.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun AppTestScope<*>.assertNotInside(tag: String) {
        ui.onNodeWithTag(tag, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun AppTestScope<*>.paste(text: String) {
        app().pasteBox.value = text
        idle()
        click("cr-restore:paste")
    }

    private fun AppTestScope<*>.assertNothingKept() {
        assertTrue(runtime.storage.entries.isEmpty(), "nothing in storage: ${runtime.storage.entries.keys}")
        assertNull(instance.saveState())
    }

    private fun run(
        camera: QrScanner? = null,
        picker: FilePicker? = null,
        keyring: SshKeyring? = null,
        block: AppTestScope<Unit>.() -> Unit,
    ) = runAppTest(CrRestoreApp(), prepare = {
        it.provide(SecretHold.Key, hold)
        it.provide(FileSaver.Key, saver)
        camera?.let { c -> it.provide(QrScanner.Key, c) }
        picker?.let { p -> it.provide(FilePicker.Key, p) }
        keyring?.let { k -> it.provide(SshKeyring.Key, k) }
    }, block = block)

    // ------------------------------------------------------------ openssl

    @Test
    fun opensslArmorPastedOpensHiddenUntilTappedAndHolds() {
        val sealed = OpensslEnc.armor(OpensslEnc.encrypt("Секрет ✓".encodeToByteArray(), OpensslEnc.Key.Password("pw".encodeToByteArray())))
        run {
            click("cr-restore:choose-openssl")
            paste(sealed)
            assertShown("cr-restore:input")
            type("cr-restore:password", "pw")
            click("cr-restore:decrypt")
            waitFor { app().result.value != null }
            assertInside("cr-restore:hidden")
            assertNotInside("cr-restore:text")
            assertTrue(hold.held, "a result on screen is held (D598)")

            click("cr-restore:reveal")
            assertInside("cr-restore:text")
            assertEquals("Секрет ✓", (app().result.value as Output.Text).text)

            app().onBackground()
            idle()
            assertInside("cr-restore:hidden")
            assertNothingKept()
        }
    }

    @Test
    fun aWrongPasswordSaysSoAndHoldsNothing() {
        val sealed = OpensslEnc.armor(OpensslEnc.encrypt("x".encodeToByteArray(), OpensslEnc.Key.Password("pw".encodeToByteArray())))
        run {
            click("cr-restore:choose-openssl")
            paste(sealed)
            type("cr-restore:password", "nope")
            click("cr-restore:decrypt")
            waitFor { app().problem.value != null || app().result.value != null }
            if (app().result.value == null) {
                assertShown("cr-restore:problem")
                assertFalse(hold.held)
            }
        }
    }

    @Test
    fun opensslSettingsBehindMoreAndTheCommandFollowsThem() {
        val params = OpensslEnc.Params(cipher = "aes-128-ctr", iterations = 2000, digest = "sha512")
        val sealed = OpensslEnc.encrypt("ctr".encodeToByteArray(), OpensslEnc.Key.Password("pw".encodeToByteArray()), params)
        run(picker = FakeFilePicker(PickedFile("note.txt.enc", sealed))) {
            click("cr-restore:choose-openssl")
            click("cr-restore:open-file")
            app().params.value = app().params.value.copy(cipher = "aes-128-ctr", digest = "sha512")
            app().iterations.value = "2000"
            type("cr-restore:password", "pw")
            idle()
            assertEquals(
                "openssl enc -aes-128-ctr -pbkdf2 -iter 2000 -md sha512 -a -in FILE | unitool qr encode --split",
                command(app().params.value, app().iterations.value, raw = false),
            )
            click("cr-restore:decrypt")
            waitFor { app().result.value != null }
            assertShown("cr-restore:warning")
            assertEquals("note.txt", app().result.value!!.name, "named after the file, without .enc")
        }
    }

    // ------------------------------------------------------------ age

    @Test
    fun ageWithThePhonesKey() {
        // A file whose one stanza is for an SSH key: the fake keyring answers as the phone would.
        val keyring = FakeSshKeyring().apply { onDecrypt = { SshOutcome.Ok("from the phone".encodeToByteArray()) } }
        val sshFile = "-----BEGIN AGE ENCRYPTED FILE-----\n" +
            java.util.Base64.getEncoder().encodeToString(
                ("age-encryption.org/v1\n-> ssh-ed25519 AbCdEf ZXBoZW1lcmFsZXBoZW1lcmFsZXBoZW1lcmFsZXBoZW0\n" +
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n--- AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n").encodeToByteArray() + ByteArray(32),
            ).chunked(64).joinToString("\n") + "\n-----END AGE ENCRYPTED FILE-----\n"
        run(keyring = keyring) {
            click("cr-restore:choose-age")
            paste(sshFile)
            assertShown("cr-restore:age-for")
            click("cr-restore:phone-key")
            waitFor { app().result.value != null }
            assertEquals("from the phone", (app().result.value as Output.Text).text)
            assertEquals(listOf("decrypt"), keyring.calls)
        }
    }

    @Test
    fun ageWithAPastedKeyAcrossTwoQrCodes() {
        val identity = AgeKeys.generate()
        val armored = Age.armor(AgeKeys.encryptTo("two codes".encodeToByteArray(), listOf(AgeKeys.parseRecipient(identity.recipient)!!)))
        val lines = armored.trimEnd().lines()
        val first = lines.take(lines.size / 2).joinToString("\n")
        val second = lines.drop(lines.size / 2).joinToString("\n")
        // The test keeps the key's text the way age-keygen prints it.
        val secret = cx.m42.unitool.kit.crypto.Bech32.encode("age-secret-key-", identitySecret(identity)).uppercase()
        run(camera = Camera(first, second)) {
            click("cr-restore:choose-age")
            click("cr-restore:scan")
            click("cr-restore:scan")
            assertEquals(2, app().input.value!!.codes)
            type("cr-restore:age-key", secret)
            click("cr-restore:decrypt")
            waitFor { app().result.value != null }
            assertEquals("two codes", (app().result.value as Output.Text).text)
        }
    }

    @Test
    fun ageWithAPassphrase() {
        val sealed = AgeKeys.encryptWithPassphrase("by passphrase".encodeToByteArray(), "pw".encodeToByteArray(), logN = 10)
        run(picker = FakeFilePicker(PickedFile("secret.age", sealed))) {
            click("cr-restore:choose-age")
            click("cr-restore:open-file")
            type("cr-restore:age-passphrase", "pw")
            click("cr-restore:decrypt")
            waitFor { app().result.value != null }
            assertEquals("by passphrase", (app().result.value as Output.Text).text)
            assertEquals("secret.txt", app().result.value!!.name)
        }
    }

    // ------------------------------------------------------------ split

    @Test
    fun splitOpensByItselfOnceThereAreEnoughParts() {
        val sealed = Ucrypt.encryptSplit("split secret".encodeToByteArray(), k = 2, n = 3)
        val links = sealed.parts.map { SplitLink.format(it, "https://example.org/x") }
        run {
            click("cr-restore:choose-split")
            paste(links[2])
            paste(links[2])
            assertShown("cr-restore:notice")
            paste(Ucrypt.armor(sealed.file))
            assertShown("cr-restore:container")
            assertNull(app().result.value)
            paste(links[0])
            waitFor { app().result.value != null }
            assertEquals("split secret", (app().result.value as Output.Text).text)
        }
    }

    @Test
    fun aPartOfAnotherSecretIsTurnedAway() {
        val one = Ucrypt.encryptSplit("a".encodeToByteArray(), k = 2, n = 2)
        val other = Ucrypt.encryptSplit("b".encodeToByteArray(), k = 2, n = 2)
        run {
            click("cr-restore:choose-split")
            paste(Ucrypt.armor(one.file))
            paste(SplitLink.format(other.parts[0], "https://example.org"))
            assertEquals(0, app().split.value.parts.size)
            assertShown("cr-restore:notice")
        }
    }

    // ------------------------------------------------------------ the result

    @Test
    fun aBinaryResultIsAFileToSaveAndStartingOverWipesIt() {
        val binary = ByteArray(300) { (it * 7).toByte() }
        val sealed = OpensslEnc.encrypt(binary, OpensslEnc.Key.Password("pw".encodeToByteArray()))
        run(picker = FakeFilePicker(PickedFile("photo.jpg.enc", sealed))) {
            click("cr-restore:choose-openssl")
            click("cr-restore:open-file")
            type("cr-restore:password", "pw")
            click("cr-restore:decrypt")
            waitFor { app().result.value != null }
            assertShown("cr-restore:file")
            click("cr-restore:save")
            waitFor { app().saved.value != null }
            assertEquals("photo.jpg", saver.name)
            assertContentEquals(binary, saver.bytes)
            assertTrue(hold.outsides >= 2, "the picker and the save are outside, not walking away")

            val kept = app().result.value!!.bytes
            click("cr-restore:start-over")
            assertTrue(kept.all { it == 0.toByte() }, "the result's bytes are zeroed")
            assertFalse(hold.held)
            assertShown("cr-restore:choose-age")
            assertNothingKept()
        }
    }

    @Test
    fun theWrongMethodPointsToTheRightOne() {
        val age = Age.armor(AgeKeys.encryptTo("x".encodeToByteArray(), listOf(AgeKeys.parseRecipient(AgeKeys.generate().recipient)!!)))
        run {
            click("cr-restore:choose-openssl")
            paste(age)
            assertShown("cr-restore:open-there")
            click("cr-restore:open-there")
            assertEquals(Method.Age, app().method.value)
        }
    }

    @Test
    fun closingWipesEverything() {
        val sealed = OpensslEnc.armor(OpensslEnc.encrypt("bye".encodeToByteArray(), OpensslEnc.Key.Password("pw".encodeToByteArray())))
        run {
            click("cr-restore:choose-openssl")
            paste(sealed)
            type("cr-restore:password", "pw")
            click("cr-restore:decrypt")
            waitFor { app().result.value != null }
            val kept = app().result.value!!.bytes
            instance.onClose()
            assertTrue(kept.all { it == 0.toByte() })
            assertNull(app().result.value)
            assertEquals("", app().password.value)
            assertFalse(hold.held)
            assertNothingKept()
        }
    }

    /** The scalar back out of an identity, for the test only: the kit keeps it private. */
    private fun identitySecret(identity: AgeKeys.X25519Identity): ByteArray {
        val field = identity.javaClass.getDeclaredField("secret").apply { isAccessible = true }
        return (field.get(identity) as ByteArray).copyOf()
    }
}
