package cx.m42.unitool.security

import kotlin.io.encoding.Base64

/**
 * A sealed value with one byte of its ciphertext changed, keeping the vault's `prefix:` so the
 * vault gets as far as its authentication check — which must then refuse it (`vault.tamper`, 06 §3).
 */
internal fun flipOneByte(sealed: String): String {
    val cut = sealed.indexOf(':') + 1
    val body = Base64.decode(sealed.substring(cut))
    body[body.size / 2] = (body[body.size / 2].toInt() xor 1).toByte()
    return sealed.substring(0, cut) + Base64.encode(body)
}
