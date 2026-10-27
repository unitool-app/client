package cx.m42.unitool.kit.openssl

import cx.m42.unitool.kit.crypto.Aes
import cx.m42.unitool.kit.crypto.BlockCipher
import cx.m42.unitool.kit.crypto.BlockMode
import cx.m42.unitool.kit.crypto.Hash
import cx.m42.unitool.kit.crypto.Pkcs7
import cx.m42.unitool.kit.crypto.evpBytesToKey
import cx.m42.unitool.kit.crypto.pbkdf2
import cx.m42.unitool.kit.crypto.secureRandomBytes
import kotlin.io.encoding.Base64

/**
 * `openssl enc` (cr-restore D607–D610), both ways, by hand: no OpenSSL on the phone, and one
 * implementation for every target so there is one thing to check against the real tool.
 *
 * The file is `Salted__ ‖ salt(8) ‖ ciphertext`, or the ciphertext alone with `-nosalt` or `-K`.
 * Nothing else is in it — not the cipher, not the digest, not the iterations — so [Params] has to
 * say what the command line said, and a wrong guess is indistinguishable from a wrong password.
 */
public object OpensslEnc {

    /** What starts every salted file: `Salted__`. */
    public val MAGIC: ByteArray = "Salted__".encodeToByteArray()

    /** The same in `-a` form: base64 of `Salted__` starts with these ten characters. */
    public const val ARMORED_PREFIX: String = "U2FsdGVkX1"

    public const val SALT_SIZE: Int = 8

    /** `openssl enc -pbkdf2` without `-iter`. */
    public const val DEFAULT_ITERATIONS: Int = 10_000

    /** One name of `openssl enc -list`: its key and IV sizes, and how to make it. */
    public class Cipher internal constructor(
        public val name: String,
        public val keySize: Int,
        public val mode: BlockMode,
        internal val make: (ByteArray) -> BlockCipher,
        private val block: Int,
    ) {
        /** What `openssl enc` takes as IV: none for ECB, a block for the rest. */
        public val ivSize: Int get() = if (mode.needsIv) block else 0
        override fun toString(): String = name
    }

    /** By `openssl enc` name, lower case. Stage A: AES in every mode `enc` lists for it (D609). */
    public val ciphers: Map<String, Cipher> = buildMap {
        for (bits in listOf(128, 192, 256)) {
            for (mode in BlockMode.entries) {
                val name = "aes-$bits-${mode.name.lowercase()}"
                put(name, Cipher(name, bits / 8, mode, ::Aes, 16))
            }
        }
    }

    /** The aliases `openssl enc` also takes, as written on its command line. */
    public val aliases: Map<String, String> = mapOf(
        "aes128" to "aes-128-cbc", "aes192" to "aes-192-cbc", "aes256" to "aes-256-cbc",
    )

    public fun cipher(name: String): Cipher? = name.lowercase().removePrefix("-").let { ciphers[aliases[it] ?: it] }

    /** `-md` names. MD5 is not here on purpose (cr-restore Q261). */
    public val digests: Map<String, Hash> = mapOf(
        "sha1" to Hash.SHA1, "sha256" to Hash.SHA256, "sha384" to Hash.SHA384, "sha512" to Hash.SHA512,
    )

    public enum class Kdf {
        /** `-pbkdf2` (or `-iter`). */
        Pbkdf2,

        /** No `-pbkdf2`: `EVP_BytesToKey`, one round — what OpenSSL warns is deprecated. */
        Legacy,
    }

    /** What the command line said. The defaults are `openssl enc -aes-256-cbc -pbkdf2`. */
    public data class Params(
        val cipher: String = "aes-256-cbc",
        val kdf: Kdf = Kdf.Pbkdf2,
        val iterations: Int = DEFAULT_ITERATIONS,
        val digest: String = "sha256",
        /** False for `-nosalt`: no header, and the key derived with no salt. Ignored with a raw key. */
        val salted: Boolean = true,
        /** False for `-nopad`. Only ECB and CBC pad at all. */
        val padding: Boolean = true,
    )

    /** A password (`-k`, `-pass`) or `-K`/`-iv` in bytes. */
    public sealed interface Key {
        /** The bytes `openssl` read: the UTF-8 of what was typed, with no newline. */
        public class Password(public val bytes: ByteArray) : Key

        /** `-K` and `-iv`, already out of hex. */
        public class Raw(public val key: ByteArray, public val iv: ByteArray) : Key
    }

    public sealed interface Opened {
        /**
         * [checked]: the padding came off cleanly, which a wrong password passes about once in 256
         * times. False for the modes that have no padding to check: there anything comes out.
         */
        public class Ok(public val plaintext: ByteArray, public val checked: Boolean) : Opened

        /** The padding did not come off: a wrong password, digest, iterations or cipher. */
        public data object WrongKey : Opened

        /** Not `Salted__`, not base64 of it, and not a whole number of blocks either. */
        public data object NotOpenssl : Opened

        public class Unsupported(public val what: String) : Opened
    }

    /**
     * Opens [input] — the binary file or its `-a` text, with or without line breaks. Base64 is
     * tried when the bytes are not `Salted__` and are all base64 characters and white space.
     */
    public fun decrypt(input: ByteArray, key: Key, params: Params = Params()): Opened {
        val cipher = cipher(params.cipher) ?: return Opened.Unsupported("cipher ${params.cipher}")
        val hash = digests[params.digest.lowercase()] ?: return Opened.Unsupported("digest ${params.digest}")
        val file = when {
            input.startsWith(MAGIC) -> input
            looksBase64(input) -> dearmor(input.decodeToString()) ?: input
            else -> input
        }
        val salted = key is Key.Password && params.salted
        if (salted && !file.startsWith(MAGIC)) return Opened.NotOpenssl
        val salt = if (salted) file.copyOfRange(MAGIC.size, MAGIC.size + SALT_SIZE) else ByteArray(0)
        val body = if (salted) file.copyOfRange(MAGIC.size + SALT_SIZE, file.size) else file
        val padded = cipher.mode.padded && params.padding
        if (cipher.mode.padded && body.size % 16 != 0) return Opened.NotOpenssl
        val (k, iv) = keyAndIv(cipher, hash, key, salt, params) ?: return Opened.Unsupported("iterations ${params.iterations}")
        val engine = cipher.make(k)
        try {
            val plain = cipher.mode.decrypt(engine, iv, body)
            if (!padded) return Opened.Ok(plain, checked = false)
            val unpadded = Pkcs7.unpad(plain, 16)
            plain.fill(0)
            return if (unpadded == null) Opened.WrongKey else Opened.Ok(unpadded, checked = true)
        } finally {
            engine.wipe()
            k.fill(0)
            iv.fill(0)
        }
    }

    /**
     * The binary file `openssl enc -e` would write; [salt] fixed only by tests. With a raw key no
     * header is written, as `openssl` does.
     */
    public fun encrypt(
        plaintext: ByteArray,
        key: Key,
        params: Params = Params(),
        salt: ByteArray = secureRandomBytes(SALT_SIZE),
    ): ByteArray {
        val cipher = requireNotNull(cipher(params.cipher)) { "unknown cipher ${params.cipher}" }
        val hash = requireNotNull(digests[params.digest.lowercase()]) { "unknown digest ${params.digest}" }
        val salted = key is Key.Password && params.salted
        val usedSalt = if (salted) salt else ByteArray(0)
        val (k, iv) = requireNotNull(keyAndIv(cipher, hash, key, usedSalt, params)) { "bad iterations" }
        val engine = cipher.make(k)
        try {
            val input = if (cipher.mode.padded && params.padding) Pkcs7.pad(plaintext, 16) else plaintext
            val body = cipher.mode.encrypt(engine, iv, input)
            return if (salted) MAGIC + usedSalt + body else body
        } finally {
            engine.wipe()
            k.fill(0)
            iv.fill(0)
        }
    }

    /** `-a`: standard base64, 64 characters to a line, each line ended — byte for byte what `openssl` writes. */
    public fun armor(file: ByteArray): String = buildString {
        Base64.Default.encode(file).chunked(64).forEach { append(it).append('\n') }
    }

    /** `-a` with or without `-A`: the white space goes, the rest must be base64. */
    public fun dearmor(text: String): ByteArray? {
        val compact = text.filterNot { it == ' ' || it == '\n' || it == '\r' || it == '\t' }
        if (compact.isEmpty()) return null
        return try {
            Base64.Default.decode(compact)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** The key and IV for [cipher], each a fresh array the caller wipes; null for iterations below one. */
    private fun keyAndIv(cipher: Cipher, hash: Hash, key: Key, salt: ByteArray, params: Params): Pair<ByteArray, ByteArray>? =
        when (key) {
            // `openssl` pads a short hex key or IV with zeros and drops the excess of a long one.
            is Key.Raw -> key.key.copyOf(cipher.keySize) to key.iv.copyOf(cipher.ivSize)
            is Key.Password -> {
                val length = cipher.keySize + cipher.ivSize
                val material = when (params.kdf) {
                    Kdf.Pbkdf2 -> {
                        if (params.iterations < 1) return null
                        pbkdf2(hash, key.bytes, salt, params.iterations, length)
                    }
                    Kdf.Legacy -> evpBytesToKey(hash, key.bytes, salt, length)
                }
                (material.copyOfRange(0, cipher.keySize) to material.copyOfRange(cipher.keySize, length)).also { material.fill(0) }
            }
        }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && copyOf(prefix.size).contentEquals(prefix)

    private fun looksBase64(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes.all {
        val c = it.toInt().toChar()
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '=' || c == '\n' || c == '\r' || c == ' ' || c == '\t'
    }
}
