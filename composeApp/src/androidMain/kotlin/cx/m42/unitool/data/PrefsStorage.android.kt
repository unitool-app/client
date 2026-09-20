package cx.m42.unitool.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Android's private per-app preferences — [Context.MODE_PRIVATE], so only this app's UID can read
 * the file. [initPrefs] must run before the first access; `MainActivity` does it.
 */
actual object PrefsStorage {
    private const val PREFS = "cx.m42.unitool.settings"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        // The application context, not the activity: this object outlives any one activity, and
        // holding an activity here would leak it across a rotation.
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    actual fun get(key: String): String? = prefs?.getString(key, null)

    actual fun put(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply()
    }

    actual fun remove(key: String) {
        prefs?.edit()?.remove(key)?.apply()
    }
}

/** Wires the store to a context. Call once, before [cx.m42.unitool.App] first composes. */
fun initPrefs(context: Context) = PrefsStorage.init(context)
