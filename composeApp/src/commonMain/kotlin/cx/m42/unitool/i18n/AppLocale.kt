package cx.m42.unitool.i18n

import androidx.compose.runtime.mutableStateOf
import cx.m42.unitool.data.AppSettings
import cx.m42.unitool.platform.deviceLanguageTag

/**
 * Which language the app is speaking right now, and where that decision comes from — the last
 * choice made on this device, then the device's own language, then English.
 *
 * ### Why a plain global rather than a CompositionLocal
 *
 * Some text is assembled outside composition by pure functions that screens and tests call
 * directly, and a CompositionLocal cannot answer those without every one of them becoming
 * `@Composable`. [current] is Compose snapshot state all the same, so reading [strings] *during*
 * composition still subscribes that composition to it: switching the language repaints the whole
 * app with no screen having to observe anything.
 */
object AppLocale {

    /**
     * What a device speaking neither of the app's languages gets. English rather than [Lang.Ru]:
     * the strings were written in Russian, but the app is listed worldwide, and English is the
     * language a reader who has neither is likeliest to have some of.
     */
    private val Fallback = Lang.En

    private val current = mutableStateOf(Fallback)

    /** True once [start] has run, so a test that sets a language outright is not overruled by it. */
    private var started = false

    /** The language in effect. Reading this inside a composition re-runs it on a change. */
    val lang: Lang get() = current.value

    /** The table every string in the app is read out of. */
    val strings: Strings get() = current.value.table()

    /**
     * Resolves the language from the stored choice, then the device, and does it once per process.
     * Called as the app starts, ahead of the first composition, so the first frame is already in
     * the right language rather than flipping to it.
     */
    fun start() {
        if (started) return
        resolveNow()
    }

    /**
     * Resolves again, latch or no latch, for a host that has reason to think the device's answer
     * has changed under it.
     *
     * Exists for Android 13's per-app language picker: choosing a language there restarts the
     * activity inside a process that is already warm, so [start] would find its once-per-process
     * latch already set and hold the app on the language it launched in.
     *
     * It re-runs the whole of [resolve], not just the device half — a stored choice still wins,
     * because that is the person's own answer and it outranks anything the platform reports.
     */
    fun refreshFromDevice() = resolveNow()

    private fun resolveNow() {
        started = true
        current.value = resolve()
    }

    /** The chosen language, then the device's, then [Fallback]. */
    private fun resolve(): Lang = Lang.fromTag(AppSettings.language)
        ?: Lang.fromTag(deviceLanguageTag())
        ?: Fallback

    /** The person picked a language. Takes effect immediately and is remembered on this device. */
    fun choose(lang: Lang) {
        current.value = lang
        AppSettings.language = lang.tag
    }

    /** Pins a language for a test, bypassing both the device and the stored choice. */
    fun useForTesting(lang: Lang) {
        started = true
        current.value = lang
    }

    /**
     * Puts the resolver back to how it is in a process that has just launched, so a test can watch
     * [start] make its decision instead of asserting against whatever the previous test left
     * behind. [AppLocale] is a global — which is the point of it — so undoing it has to be possible
     * or every test after the first is testing a used one.
     */
    fun resetForTesting() {
        started = false
        current.value = Fallback
    }
}

/**
 * Every string the app writes for itself. Short on purpose — it is read at every call site as
 * `strings.calculator` rather than `AppLocale.strings.calculator`.
 */
val strings: Strings get() = AppLocale.strings
