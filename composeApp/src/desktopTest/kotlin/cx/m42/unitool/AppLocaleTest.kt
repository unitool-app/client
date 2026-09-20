package cx.m42.unitool

import cx.m42.unitool.data.AppSettings
import cx.m42.unitool.i18n.AppLocale
import cx.m42.unitool.i18n.Lang
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How the app decides which language it is speaking: the choice made in Settings, then the
 * device's own language, then English.
 *
 * Driven directly rather than through a composition — the resolver is a plain object, and the
 * order it consults its sources in is the whole of what is worth pinning down.
 */
class AppLocaleTest {

    private lateinit var systemLocale: Locale

    @BeforeTest
    fun isolate() {
        // The resolver is a process-wide global, which is the point of it; a test that did not
        // undo the previous one would be testing a used one.
        AppSettings.useInMemoryForTesting()
        AppLocale.resetForTesting()
        systemLocale = Locale.getDefault()
    }

    @AfterTest
    fun restore() {
        Locale.setDefault(systemLocale)
        AppLocale.resetForTesting()
    }

    @Test
    fun aDeviceSpeakingNeitherLanguageGetsEnglish() {
        Locale.setDefault(Locale.forLanguageTag("de-DE"))

        AppLocale.start()

        assertEquals(Lang.En, AppLocale.lang)
    }

    @Test
    fun aRussianDeviceGetsRussianWithoutAnyoneChoosingIt() {
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))

        AppLocale.start()

        assertEquals(Lang.Ru, AppLocale.lang)
    }

    @Test
    fun aStoredChoiceBeatsTheDevice() {
        Locale.setDefault(Locale.forLanguageTag("de-DE"))
        AppSettings.language = Lang.Ru.tag

        AppLocale.start()

        assertEquals(Lang.Ru, AppLocale.lang)
    }

    /**
     * The regression this test exists for: [AppLocale.refreshFromDevice] runs from `MainActivity`
     * *before* the first composition calls [AppLocale.start], and it sets the once-per-process
     * latch on its way through. An early return anywhere in it leaves the latch set and the
     * language unresolved, so `start()` finds nothing left to do and the app opens in the
     * fallback — silently ignoring the language its owner picked.
     */
    @Test
    fun refreshingFirstDoesNotCostTheStoredChoice() {
        Locale.setDefault(Locale.forLanguageTag("de-DE"))
        AppSettings.language = Lang.Ru.tag

        AppLocale.refreshFromDevice()
        AppLocale.start()

        assertEquals(Lang.Ru, AppLocale.lang)
    }

    /**
     * The case it exists to serve: nothing chosen in Settings, and the device's language changed
     * under a process that is still warm — Android 13's per-app language picker, which restarts
     * the activity without restarting the app.
     */
    @Test
    fun refreshingPicksUpALanguageChangedUnderAWarmProcess() {
        Locale.setDefault(Locale.forLanguageTag("en-GB"))
        AppLocale.start()
        assertEquals(Lang.En, AppLocale.lang)

        Locale.setDefault(Locale.forLanguageTag("ru-RU"))
        AppLocale.refreshFromDevice()

        assertEquals(Lang.Ru, AppLocale.lang)
    }
}
