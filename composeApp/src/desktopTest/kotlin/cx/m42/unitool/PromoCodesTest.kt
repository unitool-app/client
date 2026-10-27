package cx.m42.unitool

import cx.m42.apps.testapp.SecretTestApp
import cx.m42.apps.testapp.TestApp
import cx.m42.superizer.activation.ActivationResult
import cx.m42.superizer.app.AppId
import cx.m42.unitool.settings.HostTools
import cx.m42.unitool.settings.SshKeysScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/**
 * The build's code table is hashes (done/idea/09, D256), so nothing in `UnitoolHost.kt` says which code
 * opens what. This is the one place that does: each code the tests, the README and the stores'
 * reviewers are given still matches a row. A row rehashed under another salt, or a code changed in
 * the library without a new row, fails here — not on a reviewer's phone.
 */
class PromoCodesTest : UnitoolTest() {

    @Test
    fun everyCodeWeGiveOutStillMatchesItsHash() = runBlocking<Unit> {
        val activation = testSuperizer().activation

        suspend fun opens(code: String): AppId =
            assertIs<ActivationResult.Success>(activation.fromPromo(code), code).activation.appId

        assertEquals(AppId("calculator"), opens("SCI"))
        assertEquals(AppId("test-app"), opens(TestApp.PROMO_CODE))
        assertEquals(AppId("secret-test"), opens(SecretTestApp.PROMO_CODE))
        assertEquals(
            ActivationResult.HostCommand(ActivationResult.Command.OpenServiceMenu),
            activation.fromPromo(SERVICE_CODE),
        )
        // SSH keys: a screen of the security layer's, which this test host was built without — so
        // here the code names nothing, as any wrong code does.
        assertEquals(ActivationResult.Rejected(ActivationResult.Reason.UnknownCode), activation.fromPromo("{ssh-keys}"))
        // Typed as people type them: case, spaces and dashes are not part of a code.
        assertEquals(AppId("secret-test"), opens("secret 2026"))
        assertEquals(ActivationResult.Rejected(ActivationResult.Reason.UnknownCode), activation.fromPromo("NOT-A-CODE"))
    }

    @Test
    fun theSshKeysCodeOpensTheKeysScreenWhereTheHostHasOne() = runBlocking<Unit> {
        val superizer = testSuperizer { hostScreen(SshKeysScreen(FakeKeys(), HostTools())) }
        // Bracketed, so exact: as written, in either bracket, with only the keyboard's spaces around it.
        listOf("{ssh-keys}", "[ssh-keys]", " [ssh-keys] ").forEach { code ->
            assertEquals(ActivationResult.OpenHostScreen(SshKeysScreen.ID), superizer.activation.fromPromo(code), code)
        }
        // And nothing else: not without brackets, not in capitals, not with a space inside.
        listOf("ssh-keys", "SSH KEYS", "sshkeys", "{SSH-KEYS}", "[ssh keys]", "(ssh-keys)").forEach { code ->
            assertEquals(ActivationResult.Rejected(ActivationResult.Reason.UnknownCode), superizer.activation.fromPromo(code), code)
        }
        assertIs<SshKeysScreen>(superizer.codeScreens[SshKeysScreen.ID])
    }

    @Test
    fun theCrRestoreCodeOpensItForOneVisitAndNothingNearItDoes() = runBlocking<Unit> {
        val activation = testSuperizer().activation
        // Exact, in either bracket (.ai-notes/cr-restore D596) — and a secret app's one visit: no unlock.
        listOf("[cr-restore]", "{cr-restore}", " [cr-restore] ").forEach { code ->
            val result = assertIs<ActivationResult.Success>(activation.fromPromo(code), code)
            assertEquals(AppId("cr-restore"), result.activation.appId)
            assertEquals(false, result.activation.unlock, code)
        }
        listOf("cr-restore", "CR-RESTORE", "crrestore", "[CR-RESTORE]", "[cr restore]", "(cr-restore)").forEach { code ->
            assertEquals(ActivationResult.Rejected(ActivationResult.Reason.UnknownCode), activation.fromPromo(code), code)
        }
    }
}
