package cx.m42.apps.twofa

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cx.m42.superizer.app.LockPolicy
import cx.m42.superizer.lock.UserPresence
import cx.m42.superizer.testing.FakeUserPresence
import cx.m42.superizer.testing.runAppTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope

/**
 * What the 2FA app asks of the host's lock (06 §5.5, §11): the three actions that give the accounts
 * away or take one from its owner do nothing until the device says it is the owner — and nothing
 * at all when the person cancels.
 */
class TwoFaProtectionTest {

    private val stored =
        """[{"id":"a","issuer":"GitHub","name":"octocat","secret":"JBSWY3DPEHPK3PXP"},""" +
            """{"id":"b","issuer":"Google","name":"me@gmail.com","secret":"GEZDGNBVGY3TQOJQ"}]"""

    @Test
    fun theManifestAsksForTheLockAndTheContractThatHasIt() {
        val manifest = TwoFaApp().manifest
        assertEquals(LockPolicy.Required, manifest.protection.lock)
        assertTrue(manifest.protection.secureWindow)
        assertEquals(3, manifest.minHostContract, "contract 3: runtime.secrets; it includes 2, the lock")
    }

    @Test
    fun exportOpensOnlyOnceConfirmed() {
        val presence = FakeUserPresence(answer = false)
        runAppTest(
            TwoFaApp(),
            prepare = {
                it.storage.entries[KEY_ACCOUNTS] = stored
                it.provide(UserPresence.Key, presence)
            },
        ) {
            click("2fa:export")
            assertShown("2fa:list")
            assertAbsent("2fa:export-page")

            presence.answer = true
            click("2fa:export")
            assertShown("2fa:export-page")
            assertEquals(listOf("Экспорт всех ключей", "Экспорт всех ключей"), presence.reasons)
        }
    }

    @Test
    fun theKeyIsShownOnlyOnceConfirmedAndHidingAsksNothing() {
        val presence = FakeUserPresence(answer = false)
        runAppTest(
            TwoFaApp(),
            prepare = {
                it.storage.entries[KEY_ACCOUNTS] = stored
                it.provide(UserPresence.Key, presence)
            },
        ) {
            click("2fa:details-0")
            click("2fa:toggle-secret")
            node("2fa:secret-value").assertTextEquals("•••• •••• •••• ••••")

            presence.answer = true
            click("2fa:toggle-secret")
            node("2fa:secret-value").assertTextEquals("JBSW Y3DP EHPK 3PXP")
            click("2fa:toggle-secret")
            node("2fa:secret-value").assertTextEquals("•••• •••• •••• ••••")
            assertEquals(2, presence.reasons.size)
        }
    }

    @Test
    fun aKeyOnScreenIsHiddenAgainOnLeavingThePage() {
        runAppTest(
            TwoFaApp(),
            prepare = {
                it.storage.entries[KEY_ACCOUNTS] = stored
                it.provide(UserPresence.Key, FakeUserPresence())
            },
        ) {
            val twofa = instance as TwoFaInstance
            click("2fa:details-0")
            click("2fa:toggle-secret")
            assertEquals("a", twofa.shownSecret.value)
            twofa.back()
            idle()
            click("2fa:details-0")
            node("2fa:secret-value").assertTextEquals("•••• •••• •••• ••••")
        }
    }

    @Test
    fun deleteGoesThroughOnlyOnceConfirmed() {
        val presence = FakeUserPresence(answer = false)
        runAppTest(
            TwoFaApp(),
            prepare = {
                it.storage.entries[KEY_ACCOUNTS] = stored
                it.provide(UserPresence.Key, presence)
            },
        ) {
            val twofa = instance as TwoFaInstance
            click("2fa:details-0")
            node("2fa:delete").performScrollTo().performClick()
            node("2fa:delete-confirm").performScrollTo().performClick()
            idle()
            assertEquals(listOf("a", "b"), twofa.accounts.value.map { it.id })
            assertShown("2fa:details-page")

            presence.answer = true
            node("2fa:delete-confirm").performScrollTo().performClick()
            idle()
            assertEquals(listOf("b"), twofa.accounts.value.map { it.id })
            assertShown("2fa:list")
            assertEquals(listOf("Удалить аккаунт", "Удалить аккаунт"), presence.reasons)
        }
    }

    @Test
    fun aCopiedCodeIsClearedFromTheClipboardAMinuteLater() {
        runAppTest(TwoFaApp(), prepare = { it.storage.entries[KEY_ACCOUNTS] = stored }) {
            val twofa = instance as TwoFaInstance
            val time = (runtime.scope as TestScope).testScheduler
            val clipboard = RecordingClipboard()
            twofa.copyTo(clipboard, twofa.accounts.value[0])
            assertEquals(listOf(twofa.codeOf(twofa.accounts.value[0])), clipboard.puts)

            time.advanceTimeBy(59_000)
            time.runCurrent()
            assertEquals(emptyList(), clipboard.cleared)
            time.advanceTimeBy(2_000)
            time.runCurrent()
            assertEquals(clipboard.puts, clipboard.cleared)
        }
    }

    @Test
    fun aClipboardThatCannotBeReadIsSettledOnTheWayBack() {
        // Android: with another app in front the clipboard cannot be read, and a code is copied to
        // be pasted in another app — so the minute usually runs out there.
        runAppTest(TwoFaApp(), prepare = { it.storage.entries[KEY_ACCOUNTS] = stored }) {
            val twofa = instance as TwoFaInstance
            val time = (runtime.scope as TestScope).testScheduler
            val clipboard = RecordingClipboard(readable = false)
            twofa.copyTo(clipboard, twofa.accounts.value[0])
            time.advanceTimeBy(61_000)
            time.runCurrent()
            assertEquals(1, clipboard.asked)

            clipboard.readable = true
            twofa.onForeground()
            time.advanceTimeBy(2_000)
            time.runCurrent()
            assertEquals(clipboard.puts, clipboard.cleared)

            // Settled, so the next return asks nothing.
            twofa.onForeground()
            time.advanceTimeBy(2_000)
            time.runCurrent()
            assertEquals(2, clipboard.asked)
        }
    }

    /** Stands in for the platform clipboard; the timing itself is the instance's. */
    private class RecordingClipboard(var readable: Boolean = true) : CodeClipboard {
        val puts = mutableListOf<String>()
        val cleared = mutableListOf<String>()
        var asked = 0

        override fun put(text: String) {
            puts += text
        }

        override fun clearIfStill(text: String): Boolean? {
            asked++
            if (!readable) return null
            cleared += text
            return true
        }
    }
}
