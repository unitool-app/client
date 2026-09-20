package cx.m42.unitool

import cx.m42.unitool.data.AppSettings
import cx.m42.unitool.i18n.AppLocale
import cx.m42.unitool.i18n.Lang
import kotlin.test.BeforeTest

/**
 * Pins the language and isolates the preferences for a UI test class.
 *
 * The language has to be *pinned* and not merely defaulted: without this the app resolves it from
 * the machine's own locale, and the same test would pass on a developer's laptop and fail on a CI
 * runner whose JVM reports `en_US` — the kind of failure that gets a suite disabled. Russian,
 * because that is the language these strings were written in and the one the other table is
 * translated from.
 *
 * The preferences are redirected to memory for the same class of reason: a test must never read —
 * or overwrite — the settings file of whoever is running it.
 *
 * Implemented as a class to extend rather than a function to remember to call.
 */
abstract class RussianUiTest {

    @BeforeTest
    fun pinLanguageAndIsolateSettings() {
        AppSettings.useInMemoryForTesting()
        AppLocale.useForTesting(Lang.Ru)
    }
}
