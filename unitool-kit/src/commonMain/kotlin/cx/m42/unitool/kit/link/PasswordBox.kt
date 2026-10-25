package cx.m42.unitool.kit.link

import cx.m42.unitool.kit.crypto.Hash
import cx.m42.unitool.kit.crypto.SecretBytes
import cx.m42.unitool.kit.json.FlatJson
import cx.m42.unitool.kit.json.JsonWriter

/**
 * What the phone seals in age to the computer's SSH key (README §4, D542, 01 §4): which request it
 * answers, for which site, and the login. The note never goes (D542). The age itself is the
 * caller's: this is only the JSON inside it, in bytes so the password is never a `String` here.
 */
public object PasswordBox {

    /** `hex(SHA-256(PW_REQ))` — the bytes of the whole encoded request, type byte included. */
    public fun requestDigest(request: ByteArray): String = PairingCode.hexOf(Hash.SHA256.digest(request))

    /** The JSON for [request]; the caller wipes it once it is sealed. */
    public fun plaintext(request: ByteArray, origin: String, username: String, password: ByteArray): ByteArray {
        val w = JsonWriter().beginObject()
            .name("req").string(requestDigest(request))
            .name("origin").string(origin)
            .name("username").string(username)
            .name("password").string(password)
            .endObject()
        return w.toByteArray().also { w.wipe() }
    }

    public class Opened(public val request: String, public val origin: String, public val username: String, public val password: SecretBytes) : AutoCloseable {
        override fun close(): Unit = password.close()
        override fun toString(): String = "PasswordBox.Opened($origin, redacted)"
    }

    /** The box's fields, or null when it is not one; the password stays bytes. */
    public fun open(plaintext: ByteArray): Opened? {
        val fields = FlatJson.parse(plaintext) ?: return null
        val password = fields["password"]
        val others = fields.filterKeys { it != "password" }
        try {
            val req = others["req"]?.decodeToString() ?: return null
            val origin = others["origin"]?.decodeToString() ?: return null
            val username = others["username"]?.decodeToString() ?: return null
            if (password == null) return null
            return Opened(req, origin, username, SecretBytes(password.copyOf()))
        } finally {
            password?.fill(0)
            others.values.forEach { it.fill(0) }
        }
    }
}
