package cx.m42.unitool.data

import java.io.File
import java.util.Properties

/**
 * A single properties file under the user's home directory. Loaded once and written back on every
 * change: this holds a handful of short strings, so there is nothing to gain from a real store and
 * a plain file is one a person can read and delete.
 */
actual object PrefsStorage {
    private val file: File by lazy {
        File(System.getProperty("user.home"), ".unitool/settings.properties")
    }

    private val properties: Properties by lazy {
        Properties().apply {
            try {
                if (file.isFile) file.inputStream().use { load(it) }
            } catch (_: Throwable) {
                // An unreadable file is a store nobody has written to yet.
            }
        }
    }

    actual fun get(key: String): String? = properties.getProperty(key)

    actual fun put(key: String, value: String) {
        properties.setProperty(key, value)
        flush()
    }

    actual fun remove(key: String) {
        properties.remove(key)
        flush()
    }

    private fun flush() {
        try {
            file.parentFile?.mkdirs()
            file.outputStream().use { properties.store(it, "Unitool settings") }
        } catch (_: Throwable) {
            // A read-only home directory costs the user their preferences, not the app.
        }
    }
}
