package cx.m42.services.ssh.keyring

import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.get

/**
 * The keyring's records in the page's `localStorage` (D159). Any script of the same origin reads
 * it, which is why a key here is labelled "in the browser" (05 §2.3, T15).
 */
public class LocalStorageRecordStore : RecordStore {
    override fun load(): String? = runCatching { localStorage[KEY] }.getOrNull()

    override fun save(text: String) {
        runCatching { localStorage.setItem(KEY, text) }
    }

    override fun clear() {
        runCatching { localStorage.removeItem(KEY) }
    }

    override val location: String get() = "localStorage:" + runCatching { window.location.origin }.getOrDefault("?")

    private companion object {
        const val KEY = "cx.m42.services.ssh"
    }
}
