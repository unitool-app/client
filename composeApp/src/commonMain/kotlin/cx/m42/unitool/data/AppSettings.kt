package cx.m42.unitool.data

/**
 * The preferences that belong to this device.
 *
 * Backed by [PrefsStorage], whose four-line actual per target is the whole of "settings persist" —
 * SharedPreferences on Android, a file in the home directory on desktop, `localStorage` in the
 * browser. Every read is guarded: a browser with site data blocked throws on the first access, and
 * a preference nobody can store is a preference nobody set.
 */
object AppSettings {

    /**
     * The BCP-47 tag last chosen on this device, or null when nobody has chosen. Null and "the
     * device's language" are different answers — see [cx.m42.unitool.i18n.AppLocale].
     */
    var language: String?
        get() = read(LANGUAGE)
        set(value) = write(LANGUAGE, value)

    /** Whether a key press answers with a short vibration. On by default, as the platform is. */
    var haptics: Boolean
        get() = read(HAPTICS) != "off"
        set(value) = write(HAPTICS, if (value) "on" else "off")

    private const val LANGUAGE = "language"
    private const val HAPTICS = "haptics"

    /**
     * Swapped in by tests so a test never reads — or writes — the developer's real preferences.
     * Null means "use the platform store", which is every non-test run.
     */
    private var override: MutableMap<String, String>? = null

    /** Redirects preferences to memory for the duration of a test, and empties them. */
    fun useInMemoryForTesting() {
        override = mutableMapOf()
    }

    private fun read(key: String): String? = try {
        (override?.get(key) ?: PrefsStorage.get(key))?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    private fun write(key: String, value: String?) {
        try {
            val slot = override
            when {
                value == null -> if (slot != null) slot.remove(key) else PrefsStorage.remove(key)
                slot != null -> slot[key] = value
                else -> PrefsStorage.put(key, value)
            }
        } catch (_: Throwable) {
            // Storing a preference is a convenience; a full disk or disabled storage must not take
            // the screen that wrote it down with it.
        }
    }
}
