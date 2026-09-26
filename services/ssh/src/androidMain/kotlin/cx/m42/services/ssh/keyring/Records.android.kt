package cx.m42.services.ssh.keyring

import android.content.Context

/**
 * The keyring's records in a prefs file of their own, `cx.m42.services.ssh` (D159) — excluded by
 * name from cloud backup and device transfer in Unitool's backup rules, so a copy of the device
 * carries none of it (I10). What is in it is public halves and ciphertext; the chip holds the rest.
 */
public class PrefsRecordStore(context: Context) : RecordStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY, null)

    override fun save(text: String) {
        prefs.edit().putString(KEY, text).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    override val location: String get() = "shared_prefs/$FILE.xml"

    public companion object {
        public const val FILE: String = "cx.m42.services.ssh"
        private const val KEY = "records"
    }
}
