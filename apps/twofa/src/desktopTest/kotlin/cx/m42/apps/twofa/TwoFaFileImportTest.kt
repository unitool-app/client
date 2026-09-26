package cx.m42.apps.twofa

import cx.m42.superizer.runtime.FilePicker
import cx.m42.superizer.runtime.PickedFile
import cx.m42.superizer.ssh.SshKeyring
import cx.m42.superizer.ssh.SshOutcome
import cx.m42.superizer.testing.FakeFilePicker
import cx.m42.superizer.testing.FakeSshKeyring
import cx.m42.superizer.testing.runAppTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Import from a file" (ssh-new 02 §6.1): the 2FA export opened on the phone with the person's SSH
 * key — the keyring's first consumer — or already opened on a computer and read as it is. Closing
 * the keyring's sheet changes nothing, and the accounts never reach a snapshot before they are added.
 */
class TwoFaFileImportTest {

    private val three = listOf(
        TotpAccount("", "GitHub", "octocat", "JBSWY3DPEHPK3PXP"),
        TotpAccount("", "Госуслуги", "ivan", "GEZDGNBVGY3TQOJQ", HashAlgorithm.SHA256, digits = 8),
        TotpAccount("", "", "bank 42", "MFRGGZDFMZTWQ2LK", period = 60),
    )

    /** Armored age as the export writes it; the fake keyring opens it to the printed links. */
    private val encrypted = "-----BEGIN AGE ENCRYPTED FILE-----\nYWdlLWVuY3J5cHRpb24ub3JnL3YxCg==\n-----END AGE ENCRYPTED FILE-----\n"

    private fun opensTo(accounts: List<TotpAccount>) = FakeSshKeyring().apply {
        onDecrypt = { SshOutcome.Ok(Export.plaintext(accounts).encodeToByteArray()) }
    }

    @Test
    fun theExportOpensWithTheKeyringAndEveryAccountIsOffered() {
        val file = PickedFile("unitool-2fa-2026-09-26.age", encrypted.encodeToByteArray())
        val keyring = opensTo(three)
        runAppTest(TwoFaApp(), prepare = {
            it.provide(FilePicker.Key, FakeFilePicker(file))
            it.provide(SshKeyring.Key, keyring)
        }) {
            val twofa = instance as TwoFaInstance
            click("2fa:add")
            click("2fa:import-file")
            assertShown("2fa:import-page")
            assertEquals(listOf("decrypt"), keyring.calls)
            assertEquals(setOf(0, 1, 2), twofa.importSelected.value)
            assertTrue(file.bytes.all { it == 0.toByte() }, "the picked file is wiped once read")
            for (account in three) assertFalse(account.secret in twofa.saveState().toString(), "not in a snapshot before it is added")
            click("2fa:import-add")
            assertEquals(three, twofa.accounts.value.map { it.copy(id = "") })
        }
    }

    @Test
    fun closingTheKeyringsSheetChangesNothing() {
        val keyring = FakeSshKeyring().apply { onDecrypt = { SshOutcome.Cancelled } }
        runAppTest(TwoFaApp(), prepare = {
            it.provide(FilePicker.Key, FakeFilePicker(PickedFile("x.age", encrypted.encodeToByteArray())))
            it.provide(SshKeyring.Key, keyring)
        }) {
            val twofa = instance as TwoFaInstance
            click("2fa:add")
            click("2fa:import-file")
            assertShown("2fa:add-page")
            assertAbsent("2fa:file-problem")
            assertNull(twofa.importBatch.value)
            assertEquals(emptyList(), twofa.accounts.value)
        }
    }

    @Test
    fun aFileNoKeyHereOpensSaysSo() {
        runAppTest(TwoFaApp(), prepare = {
            it.provide(FilePicker.Key, FakeFilePicker(PickedFile("x.age", encrypted.encodeToByteArray())))
            it.provide(SshKeyring.Key, FakeSshKeyring().apply { onDecrypt = { SshOutcome.NoKey } })
        }) {
            click("2fa:add")
            click("2fa:import-file")
            assertShown("2fa:file-problem")
            assertShown("2fa:add-page")
        }
    }

    @Test
    fun aFileAlreadyOpenedOnAComputerIsReadAsItIsWithoutTheKeyring() {
        val keyring = FakeSshKeyring()
        runAppTest(TwoFaApp(), prepare = {
            it.provide(FilePicker.Key, FakeFilePicker(PickedFile("accounts.txt", Export.plaintext(three).encodeToByteArray())))
            it.provide(SshKeyring.Key, keyring)
        }) {
            click("2fa:add")
            click("2fa:import-file")
            assertShown("2fa:import-page")
            assertEquals(emptyList(), keyring.calls, "nothing to decrypt")
        }
    }

    @Test
    fun withoutAKeyringOrAPickerThereIsNoButton() {
        runAppTest(TwoFaApp(), prepare = { it.provide(FilePicker.Key, FakeFilePicker()) }) {
            click("2fa:add")
            assertAbsent("2fa:import-file")
        }
    }
}
