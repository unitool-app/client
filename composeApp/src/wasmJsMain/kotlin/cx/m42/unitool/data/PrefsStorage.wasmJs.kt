package cx.m42.unitool.data

import kotlinx.browser.localStorage
import org.w3c.dom.get

/**
 * The browser's `localStorage`, scoped to the page's origin, under a prefix so the app's keys
 * cannot collide with anything else served from the same host.
 *
 * Every access may throw — a browser with site data blocked, or a page in a partitioned
 * third-party context, fails on the very first read. [AppSettings] is what guards it.
 */
actual object PrefsStorage {
    private const val PREFIX = "cx.m42.unitool."

    actual fun get(key: String): String? = localStorage[PREFIX + key]

    actual fun put(key: String, value: String) {
        localStorage.setItem(PREFIX + key, value)
    }

    actual fun remove(key: String) {
        localStorage.removeItem(PREFIX + key)
    }
}
