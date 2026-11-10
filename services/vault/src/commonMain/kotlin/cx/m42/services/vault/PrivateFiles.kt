package cx.m42.services.vault

import cx.m42.superizer.runtime.ServiceKey

/**
 * A directory of the app's own, of bytes (.ai-notes/cr-note D662): not `runtime.storage`'s strings,
 * which the host may cache and lists in its backup. Outside every backup — Android's
 * `noBackupFilesDir`, an iOS directory marked excluded — and inside the app's sandbox.
 *
 * Caller-bound: each app gets a directory of its own and sees nothing else. Names are flat:
 * letters, digits, `.`, `-` and `_`, at most 64; anything else is refused.
 */
public interface PrivateFiles {

    public suspend fun list(): List<String>

    /** Null when there is no such file. */
    public suspend fun read(name: String): ByteArray?

    /** The whole file, through a temporary one and a rename: never half-written. */
    public suspend fun write(name: String, bytes: ByteArray)

    /** No such file is not an error. */
    public suspend fun delete(name: String)

    public companion object {
        public val Key: ServiceKey<PrivateFiles> = ServiceKey("private-files")

        private val NAME = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}$")

        public fun checkName(name: String): String {
            require(NAME.matches(name)) { "not a private file name" }
            return name
        }
    }
}
