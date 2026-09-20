package cx.m42.unitool.data

/**
 * The one thing each platform has to supply for [AppSettings] to work: a string keyed by a string,
 * surviving a restart. Deliberately untyped and tiny — every preference the app has is a short
 * string, and an interface any wider would have to be re-implemented four times for no gain.
 *
 * Implementations may throw (a browser with storage blocked does, on the very first read);
 * [AppSettings] is what guards against that, so no caller has to.
 */
expect object PrefsStorage {
    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)
}
