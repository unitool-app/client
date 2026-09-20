package cx.m42.unitool.platform

import java.util.Locale

/**
 * `Locale.getDefault()` follows the system setting, including a per-app language chosen in
 * Android 13's own settings — which is exactly the switch a reader would expect to work.
 *
 * `toLanguageTag()` rather than `getLanguage()`: the region matters to nobody here, but the tag is
 * the one spelling [cx.m42.unitool.i18n.Lang.fromTag] is written against.
 */
actual fun deviceLanguageTag(): String = Locale.getDefault().toLanguageTag()
