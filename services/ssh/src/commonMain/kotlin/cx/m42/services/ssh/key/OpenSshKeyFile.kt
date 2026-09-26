package cx.m42.services.ssh.key

import cx.m42.services.ssh.SecretBytes
import cx.m42.services.ssh.SshPublicKey
import cx.m42.services.ssh.crypto.AesCtr
import cx.m42.services.ssh.crypto.AesGcm
import cx.m42.services.ssh.crypto.BcryptPbkdf
import cx.m42.services.ssh.crypto.secureRandomBytes
import cx.m42.services.ssh.wire.WireReader
import cx.m42.services.ssh.wire.WireWriter
import cx.m42.superizer.ssh.ImportRefusal
import kotlin.io.encoding.Base64

/**
 * `openssh-key-v1`, the format `ssh-keygen` has written since OpenSSH 7.8 (02 §8, D154): read,
 * inspected before any passphrase, decrypted, and written — which is also `ssh-keygen -p` (05 §2.4).
 *
 * ```
 * "openssh-key-v1\0"
 * string ciphername   none | aes{128,192,256}-ctr | aes{128,256}-gcm@openssh.com
 * string kdfname      none | bcrypt
 * string kdfoptions   string salt, uint32 rounds
 * uint32 N = 1
 * string publickey    ← in the clear: type and fingerprint before the passphrase
 * string private      ← encrypted: uint32 check ×2, key fields, string comment, padding 1,2,3…
 * [16-byte tag for GCM, after the string]
 * ```
 *
 * The level-3 keyring stores exactly such a file, byte for byte what the person gave or what
 * [write] made from it (05 §2.2). Everything decrypted lands in [SecretBytes].
 */
internal class OpenSshKeyFile private constructor(
    val cipher: String,
    val kdf: String,
    private val salt: ByteArray,
    val rounds: Int,
    val publicKey: SshPublicKey,
    private val encrypted: ByteArray,
    private val tag: ByteArray?,
) {
    val isEncrypted: Boolean get() = cipher != NONE

    /** The private section zeroed — it is the key itself when the file had no passphrase. */
    fun wipe() {
        encrypted.fill(0)
        tag?.fill(0)
    }

    /** A comment is readable before the passphrase only when there is no passphrase. */
    fun commentWithoutPassphrase(): String? = if (isEncrypted) null else (decrypt(null) as? Decrypted.Ok)?.key?.use { it.comment }

    sealed interface Decrypted {
        class Ok(val key: PrivateKey) : Decrypted
        data object WrongPassphrase : Decrypted
        data object Corrupted : Decrypted
    }

    /** With [passphrase] as UTF-8 bytes; null for a file without one. [passphrase] is the caller's to close. */
    fun decrypt(passphrase: SecretBytes?): Decrypted {
        val plain: ByteArray = if (!isEncrypted) {
            encrypted.copyOf()
        } else {
            val secret = passphrase ?: return Decrypted.WrongPassphrase
            if (secret.size == 0) return Decrypted.WrongPassphrase
            val spec = CIPHERS.getValue(cipher)
            val derived = BcryptPbkdf.derive(secret.bytes, salt, rounds, spec.keyLength + spec.ivLength)
            try {
                val key = derived.copyOfRange(0, spec.keyLength)
                val iv = derived.copyOfRange(spec.keyLength, derived.size)
                val out = if (spec.gcm) {
                    AesGcm.open(key, iv, encrypted + (tag ?: return Decrypted.Corrupted))
                } else {
                    AesCtr.apply(key, iv, encrypted)
                }
                key.fill(0)
                out ?: return Decrypted.WrongPassphrase
            } finally {
                derived.fill(0)
            }
        }
        try {
            val reader = WireReader(plain)
            val check1 = reader.uint32() ?: return Decrypted.Corrupted
            val check2 = reader.uint32() ?: return Decrypted.Corrupted
            if (check1 != check2) return Decrypted.WrongPassphrase
            val type = reader.text() ?: return Decrypted.Corrupted
            if (type != publicKey.type) return Decrypted.Corrupted
            val fieldsStart = reader.position
            val inner = when (val kind = publicKey.kind) {
                is SshPublicKey.Kind.Ed25519 -> {
                    val pub = reader.string() ?: return Decrypted.Corrupted
                    val priv = reader.string() ?: return Decrypted.Corrupted
                    if (priv.size != 64 || !pub.contentEquals(kind.point) || !priv.copyOfRange(32, 64).contentEquals(pub)) {
                        priv.fill(0)
                        return Decrypted.Corrupted
                    }
                    priv.fill(0)
                    pub
                }
                is SshPublicKey.Kind.Rsa -> {
                    val n = reader.mpint() ?: return Decrypted.Corrupted
                    val e = reader.mpint() ?: return Decrypted.Corrupted
                    repeat(4) { reader.mpint()?.fill(0) ?: return Decrypted.Corrupted } // d, iqmp, p, q
                    if (!n.contentEquals(kind.modulus) || !e.contentEquals(kind.exponent)) return Decrypted.Corrupted
                    n
                }
                is SshPublicKey.Kind.Ecdsa -> {
                    val curve = reader.text() ?: return Decrypted.Corrupted
                    val q = reader.string() ?: return Decrypted.Corrupted
                    reader.mpint()?.fill(0) ?: return Decrypted.Corrupted
                    if (curve != kind.curve.sshName || !q.contentEquals(kind.point)) return Decrypted.Corrupted
                    q
                }
                SshPublicKey.Kind.Other -> return Decrypted.Corrupted
            }
            if (inner.isEmpty()) return Decrypted.Corrupted
            val fields = plain.copyOfRange(fieldsStart, reader.position)
            val comment = reader.text() ?: run { fields.fill(0); return Decrypted.Corrupted }
            // Padding 1, 2, 3, …: anything else was edited by hand.
            var expected = 1
            while (!reader.done) {
                if (reader.byte() != expected++) {
                    fields.fill(0)
                    return Decrypted.Corrupted
                }
            }
            return Decrypted.Ok(PrivateKey(publicKey, comment, SecretBytes(fields)))
        } finally {
            plain.fill(0)
        }
    }

    sealed interface Parsed {
        class Ok(val file: OpenSshKeyFile) : Parsed
        class Refused(val refusal: ImportRefusal) : Parsed
    }

    companion object {
        const val NONE = "none"
        const val BCRYPT = "bcrypt"
        const val DEFAULT_CIPHER = "aes256-ctr"

        /** OpenSSH 9.6's default for `ssh-keygen -a`, and what [write] uses (05 §2.4). */
        const val DEFAULT_ROUNDS = 24

        private const val BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----"
        private const val END = "-----END OPENSSH PRIVATE KEY-----"
        private val MAGIC = "openssh-key-v1\u0000".encodeToByteArray()

        private class CipherSpec(val keyLength: Int, val ivLength: Int, val block: Int, val gcm: Boolean)

        private val CIPHERS = mapOf(
            "aes128-ctr" to CipherSpec(16, 16, 16, false),
            "aes192-ctr" to CipherSpec(24, 16, 16, false),
            "aes256-ctr" to CipherSpec(32, 16, 16, false),
            "aes128-gcm@openssh.com" to CipherSpec(16, 12, 16, true),
            "aes256-gcm@openssh.com" to CipherSpec(32, 12, 16, true),
        )

        /**
         * What [file] is — or why it will not be taken, with the refusal that names the fix (02 §8).
         *
         * Bytes all the way: the file may be a key in the clear (the "Set passphrase" path, 05 §2.4),
         * and a `String` of it could never be wiped (D155). Only a first line that looks like a
         * public key, or the header of a format being refused, is ever read as text.
         */
        fun parse(file: ByteArray): Parsed {
            var from = 0
            while (from < file.size && isSpace(file[from])) from++
            fun startsWith(prefix: String): Boolean {
                val p = prefix.encodeToByteArray()
                if (file.size - from < p.size) return false
                for (i in p.indices) if (file[from + i] != p[i]) return false
                return true
            }
            when {
                startsWith("-----BEGIN DSA PRIVATE KEY-----") -> return Parsed.Refused(ImportRefusal.Dsa)
                startsWith("-----BEGIN RSA PRIVATE KEY-----") || startsWith("-----BEGIN EC PRIVATE KEY-----") ||
                    startsWith("-----BEGIN PRIVATE KEY-----") || startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----") ->
                    return Parsed.Refused(ImportRefusal.OldFormat)
                startsWith("PuTTY-User-Key-File-") -> return Parsed.Refused(ImportRefusal.PuttyFormat)
                startsWith("ssh-") || startsWith("ecdsa-") || startsWith("sk-") -> {
                    var to = from
                    while (to < file.size && file[to] != '\n'.code.toByte() && to - from < 16_384) to++
                    val line = runCatching { file.decodeToString(from, to, throwOnInvalidSequence = true) }.getOrNull()
                    return Parsed.Refused(if (line != null && SshPublicKey.parse(line) != null) ImportRefusal.PublicKeyOnly else ImportRefusal.NotAKey)
                }
                !startsWith(BEGIN) -> return Parsed.Refused(ImportRefusal.NotAKey)
            }
            val bodyStart = from + BEGIN.length
            val end = indexOf(file, END.encodeToByteArray(), bodyStart)
            if (end < 0) return Parsed.Refused(ImportRefusal.Corrupted)
            val body = ByteArray(end - bodyStart)
            var n = 0
            for (i in bodyStart until end) if (!isSpace(file[i])) body[n++] = file[i]
            val bytes = runCatching { Base64.Default.decode(body, 0, n) }.getOrNull()
            body.fill(0)
            bytes ?: return Parsed.Refused(ImportRefusal.Corrupted)
            return try {
                parseBinary(bytes)
            } finally {
                bytes.fill(0)
            }
        }

        private fun isSpace(b: Byte): Boolean = b == ' '.code.toByte() || b == '\n'.code.toByte() || b == '\r'.code.toByte() || b == '\t'.code.toByte()

        private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
            outer@ for (i in from..haystack.size - needle.size) {
                for (k in needle.indices) if (haystack[i + k] != needle[k]) continue@outer
                return i
            }
            return -1
        }

        private fun parseBinary(bytes: ByteArray): Parsed {
            if (bytes.size < MAGIC.size || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return Parsed.Refused(ImportRefusal.NotAKey)
            val reader = WireReader(bytes, MAGIC.size)
            val cipher = reader.text() ?: return Parsed.Refused(ImportRefusal.Corrupted)
            val kdf = reader.text() ?: return Parsed.Refused(ImportRefusal.Corrupted)
            val kdfOptions = reader.string() ?: return Parsed.Refused(ImportRefusal.Corrupted)
            val count = reader.uint32() ?: return Parsed.Refused(ImportRefusal.Corrupted)
            if (count != 1L) return Parsed.Refused(ImportRefusal.Corrupted)
            val publicBlob = reader.string() ?: return Parsed.Refused(ImportRefusal.Corrupted)
            val encrypted = reader.string() ?: return Parsed.Refused(ImportRefusal.Corrupted)

            val publicKey = SshPublicKey.fromBlob(publicBlob) ?: return Parsed.Refused(ImportRefusal.Corrupted)
            refusalFor(publicKey)?.let { return Parsed.Refused(it) }

            if (cipher != NONE && cipher !in CIPHERS) return Parsed.Refused(ImportRefusal.UnsupportedCipher)
            if (kdf != NONE && kdf != BCRYPT) return Parsed.Refused(ImportRefusal.UnsupportedKdf)
            if ((cipher == NONE) != (kdf == NONE)) return Parsed.Refused(ImportRefusal.Corrupted)

            var salt = ByteArray(0)
            var rounds = 0
            if (kdf == BCRYPT) {
                val options = WireReader(kdfOptions)
                salt = options.string() ?: return Parsed.Refused(ImportRefusal.Corrupted)
                rounds = options.uint32()?.toInt() ?: return Parsed.Refused(ImportRefusal.Corrupted)
                if (salt.isEmpty() || rounds < 1 || !options.done) return Parsed.Refused(ImportRefusal.Corrupted)
            }
            val spec = CIPHERS[cipher]
            val block = spec?.block ?: 8
            if (encrypted.size % block != 0 || encrypted.isEmpty()) return Parsed.Refused(ImportRefusal.Corrupted)
            val tag = if (spec?.gcm == true) reader.raw(AesGcm.TAG_SIZE) ?: return Parsed.Refused(ImportRefusal.Corrupted) else null
            if (!reader.done) return Parsed.Refused(ImportRefusal.Corrupted)
            return Parsed.Ok(OpenSshKeyFile(cipher, kdf, salt, rounds, publicKey, encrypted, tag))
        }

        /** The types the keyring takes (D154): ed25519, rsa ≥ 2048, ecdsa p256/384/521. */
        fun refusalFor(key: SshPublicKey): ImportRefusal? = when (val kind = key.kind) {
            is SshPublicKey.Kind.Ed25519, is SshPublicKey.Kind.Ecdsa -> null
            is SshPublicKey.Kind.Rsa -> if (kind.bits < MIN_RSA_BITS) ImportRefusal.RsaTooShort else null
            SshPublicKey.Kind.Other -> when {
                key.type.startsWith("sk-") -> ImportRefusal.HardwareToken
                key.type == "ssh-dss" -> ImportRefusal.Dsa
                else -> ImportRefusal.UnsupportedType
            }
        }

        const val MIN_RSA_BITS = 2048

        /**
         * [key] as a PEM `openssh-key-v1` file: under [passphrase] with bcrypt and aes256-ctr, or in
         * the clear when it is null (only the tests' canary fixtures are ever written so, before a real
         * `ssh-keygen -p` encrypts them — 03 §3.1).
         */
        fun write(key: PrivateKey, passphrase: SecretBytes?, rounds: Int = DEFAULT_ROUNDS, random: (Int) -> ByteArray = ::secureRandomBytes): ByteArray {
            val cipher = if (passphrase == null) NONE else DEFAULT_CIPHER
            val spec = CIPHERS[cipher]
            val block = spec?.block ?: 8
            val check = random(4)
            val section = WireWriter()
            try {
                section.raw(check).raw(check).string(key.publicKey.type).raw(key.fields.bytes).string(key.comment)
                var pad = 1
                while (section.size % block != 0) section.byte(pad++)
                val plain = section.toByteArray()
                val salt = if (passphrase == null) ByteArray(0) else random(16)
                val sealed = if (passphrase == null) {
                    plain
                } else {
                    val derived = BcryptPbkdf.derive(passphrase.bytes, salt, rounds, spec!!.keyLength + spec.ivLength)
                    try {
                        AesCtr.apply(derived.copyOfRange(0, spec.keyLength), derived.copyOfRange(spec.keyLength, derived.size), plain)
                    } finally {
                        derived.fill(0)
                        plain.fill(0)
                    }
                }
                val out = WireWriter()
                    .raw(MAGIC)
                    .string(cipher)
                    .string(if (passphrase == null) NONE else BCRYPT)
                    .string(if (passphrase == null) ByteArray(0) else WireWriter().string(salt).uint32(rounds.toLong()).toByteArray())
                    .uint32(1)
                    .string(key.publicKey.blob)
                    .string(sealed)
                    .toByteArray()
                if (passphrase == null) sealed.fill(0)
                val encoded = Base64.Default.encodeToByteArray(out)
                out.fill(0)
                val lines = (encoded.size + 69) / 70
                val pem = ByteArray(BEGIN.length + 1 + encoded.size + lines + END.length + 1)
                var at = 0
                BEGIN.encodeToByteArray().copyInto(pem, at).also { at += BEGIN.length }
                pem[at++] = '\n'.code.toByte()
                for (line in 0 until lines) {
                    val a = line * 70
                    val b = minOf(a + 70, encoded.size)
                    encoded.copyInto(pem, at, a, b)
                    at += b - a
                    pem[at++] = '\n'.code.toByte()
                }
                encoded.fill(0)
                END.encodeToByteArray().copyInto(pem, at).also { at += END.length }
                pem[at] = '\n'.code.toByte()
                return pem
            } finally {
                section.wipe()
                check.fill(0)
            }
        }
    }
}

/**
 * A decrypted private key: the public key, the comment, and the key fields exactly as the file
 * carried them after the type — in [SecretBytes], closed by whoever holds this.
 */
internal class PrivateKey(
    val publicKey: SshPublicKey,
    val comment: String,
    val fields: SecretBytes,
) : AutoCloseable {

    /** Ed25519: the 32-byte seed. */
    fun ed25519Seed(): SecretBytes {
        val reader = WireReader(fields.bytes)
        reader.string() ?: error("no public")
        val priv = reader.string() ?: error("no private")
        return SecretBytes(priv.copyOfRange(0, 32)).also { priv.fill(0) }
    }

    /** RSA: the private exponent d. */
    fun rsaPrivateExponent(): SecretBytes {
        val reader = WireReader(fields.bytes)
        reader.mpint() ?: error("no n")
        reader.mpint() ?: error("no e")
        return SecretBytes(reader.mpint() ?: error("no d"))
    }

    /** ECDSA: the private scalar d, big-endian, sign byte stripped. */
    fun ecdsaPrivateScalar(): SecretBytes {
        val reader = WireReader(fields.bytes)
        reader.string() ?: error("no curve")
        reader.string() ?: error("no point")
        val d = reader.mpint() ?: error("no d")
        var start = 0
        while (start < d.size - 1 && d[start] == 0.toByte()) start++
        return SecretBytes(d.copyOfRange(start, d.size)).also { d.fill(0) }
    }

    override fun close() = fields.close()

    override fun toString(): String = "PrivateKey(${publicKey.label} ${publicKey.fingerprint})"

    companion object {
        /** An Ed25519 key from its seed — how a key made on the device, or a test's canary, becomes one. */
        fun ed25519(seed: ByteArray, comment: String): PrivateKey {
            val point = cx.m42.services.ssh.crypto.Ed25519.publicKey(seed)
            val fields = WireWriter().string(point).string(seed + point)
            val bytes = fields.toByteArray()
            fields.wipe()
            return PrivateKey(SshPublicKey.ed25519(point), comment, SecretBytes(bytes))
        }

        /** An RSA key from its components, all big-endian magnitudes. */
        fun rsa(n: ByteArray, e: ByteArray, d: ByteArray, iqmp: ByteArray, p: ByteArray, q: ByteArray, comment: String): PrivateKey {
            val fields = WireWriter().mpint(n).mpint(e).mpint(d).mpint(iqmp).mpint(p).mpint(q)
            val bytes = fields.toByteArray()
            fields.wipe()
            return PrivateKey(SshPublicKey.rsa(e, n), comment, SecretBytes(bytes))
        }

        /** An ECDSA key from its curve, public point and private scalar. */
        fun ecdsa(curve: SshPublicKey.Curve, q: ByteArray, d: ByteArray, comment: String): PrivateKey {
            val fields = WireWriter().string(curve.sshName).string(q).mpint(d)
            val bytes = fields.toByteArray()
            fields.wipe()
            return PrivateKey(SshPublicKey.ecdsa(curve, q), comment, SecretBytes(bytes))
        }
    }
}
