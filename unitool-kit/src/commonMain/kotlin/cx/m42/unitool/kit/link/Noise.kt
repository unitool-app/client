package cx.m42.unitool.kit.link

import cx.m42.unitool.kit.crypto.ChaCha20Poly1305
import cx.m42.unitool.kit.crypto.Curve25519
import cx.m42.unitool.kit.crypto.Hash
import cx.m42.unitool.kit.crypto.SecretBytes
import cx.m42.unitool.kit.crypto.hmac
import cx.m42.unitool.kit.crypto.secureRandomBytes

/**
 * The Noise Protocol Framework (revision 34) for the two handshakes the link uses (usb-sign D310,
 * passwords-browser D536): `Noise_KK_25519_ChaChaPoly_SHA256` for a session between a computer and
 * a phone that know each other, `Noise_XXpsk3_25519_ChaChaPoly_SHA256` for pairing them with the
 * one-time `psk` from the QR code.
 *
 * Only what those two need: X25519, ChaCha20-Poly1305 and SHA-256 from `kit.crypto`, no rekey, no
 * fallback. Sans-IO like the rest of the kit — bytes in, bytes out; the socket is the caller's.
 * Held to cacophony's vectors for both patterns (`NoiseVectorsTest`).
 */
public enum class NoisePattern(public val protocolName: String) {
    KK("Noise_KK_25519_ChaChaPoly_SHA256"),
    XXpsk3("Noise_XXpsk3_25519_ChaChaPoly_SHA256"),
}

/** An X25519 key pair. The private half is [SecretBytes]: closed by whoever owns the pair. */
public class NoiseKeyPair(public val private: SecretBytes, public val public: ByteArray) : AutoCloseable {
    init {
        require(private.size == 32 && public.size == 32)
    }

    override fun close(): Unit = private.close()

    override fun toString(): String = "NoiseKeyPair(redacted)"

    public companion object {
        public fun generate(): NoiseKeyPair = fromPrivate(secureRandomBytes(32))

        /** From a 32-byte private key, which the pair now owns. */
        public fun fromPrivate(private: ByteArray): NoiseKeyPair =
            NoiseKeyPair(SecretBytes(private), Curve25519.x25519(private, Curve25519.BASE))
    }
}

/** k and n of the spec's CipherState (§5.1). Nonce: 32 zero bits, then n as 64 bits little-endian. */
public class NoiseCipher internal constructor(private var key: ByteArray?) {
    private var nonce = 0L

    internal val hasKey: Boolean get() = key != null

    internal fun encrypt(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key ?: return plaintext.copyOf()
        check(nonce != -1L) { "nonce exhausted" }
        return ChaCha20Poly1305.seal(k, nonceBytes(nonce++), plaintext, ad)
    }

    internal fun decrypt(ad: ByteArray, ciphertext: ByteArray): ByteArray? {
        val k = key ?: return ciphertext.copyOf()
        if (nonce == -1L) return null
        // A failed open leaves n where it was (§5.1): the next good message still opens.
        val plain = ChaCha20Poly1305.open(k, nonceBytes(nonce), ciphertext, ad) ?: return null
        nonce++
        return plain
    }

    internal fun replaceKey(next: ByteArray) {
        key?.fill(0)
        key = next
        nonce = 0
    }

    internal fun wipe() {
        key?.fill(0)
        key = null
    }

    private fun nonceBytes(n: Long): ByteArray {
        val bytes = ByteArray(12)
        for (i in 0 until 8) bytes[4 + i] = (n ushr (8 * i)).toByte()
        return bytes
    }
}

/**
 * A finished handshake: one cipher each way. Messages are opened in order and exactly once — that
 * is what makes a recorded message useless in this session or any other (usb-sign 02 §5).
 */
public class NoiseTransport internal constructor(
    private val sending: NoiseCipher,
    private val receiving: NoiseCipher,
    /** The handshake hash: the same on both ends, unique to this session (§11.2). */
    public val handshakeHash: ByteArray,
    /** The other side's static key, as the handshake proved it. */
    public val remoteStatic: ByteArray,
) : AutoCloseable {

    public fun seal(plaintext: ByteArray): ByteArray {
        require(plaintext.size <= Noise.MAX_MESSAGE - ChaCha20Poly1305.TAG_SIZE) { "message too long" }
        return sending.encrypt(ByteArray(0), plaintext)
    }

    /** Null when the message was not sealed by the other side for this place in the stream. */
    public fun open(ciphertext: ByteArray): ByteArray? = receiving.decrypt(ByteArray(0), ciphertext)

    override fun close() {
        sending.wipe()
        receiving.wipe()
    }
}

/**
 * HandshakeState (§5.3) for [NoisePattern.KK] and [NoisePattern.XXpsk3].
 *
 * [write] and [read] alternate as the pattern says, starting with the initiator's [write]. A
 * failed [read] answers null and leaves the handshake dead: a session that saw one forged byte is
 * not one to continue. After the last message, [split] gives the transport.
 */
public class NoiseHandshake private constructor(
    private val pattern: NoisePattern,
    private val initiator: Boolean,
    private val s: NoiseKeyPair,
    private var rs: ByteArray?,
    private val psk: ByteArray?,
    private val fixedEphemeral: NoiseKeyPair?,
    prologue: ByteArray,
) {
    private var ck: ByteArray
    private var h: ByteArray
    private val cipher = NoiseCipher(null)
    private var e: NoiseKeyPair? = null
    private var re: ByteArray? = null
    private var step = 0
    private var failed = false

    private val messages: List<List<Token>> = when (pattern) {
        NoisePattern.KK -> listOf(listOf(Token.E, Token.ES, Token.SS), listOf(Token.E, Token.EE, Token.SE))
        NoisePattern.XXpsk3 -> listOf(
            listOf(Token.E),
            listOf(Token.E, Token.EE, Token.S, Token.ES),
            listOf(Token.S, Token.SE, Token.PSK),
        )
    }

    init {
        val name = pattern.protocolName.encodeToByteArray()
        h = if (name.size <= HASH_LEN) name.copyOf(HASH_LEN) else Hash.SHA256.digest(name)
        ck = h.copyOf()
        mixHash(prologue)
        if (pattern == NoisePattern.KK) {
            // Pre-messages: -> s, <- s. The initiator's static first, whoever is hashing.
            val remote = requireNotNull(rs) { "KK needs the other side's static key" }
            if (initiator) {
                mixHash(s.public)
                mixHash(remote)
            } else {
                mixHash(remote)
                mixHash(s.public)
            }
        } else {
            require(psk != null && psk.size == 32) { "XXpsk3 needs a 32-byte psk" }
        }
    }

    /** Whether the next message is ours to write. */
    public val isMyTurn: Boolean get() = !failed && step < messages.size && (step % 2 == 0) == initiator

    public val isComplete: Boolean get() = !failed && step == messages.size

    /** The other side's static key once a message has carried it (or from the start, for KK). */
    public val remoteStatic: ByteArray? get() = rs?.copyOf()

    /** The next handshake message, [payload] inside (encrypted once there is a key). */
    public fun write(payload: ByteArray = ByteArray(0)): ByteArray {
        check(isMyTurn) { "not our message to write" }
        val out = ArrayList<ByteArray>()
        for (token in messages[step]) {
            when (token) {
                Token.E -> {
                    val pair = fixedEphemeral ?: NoiseKeyPair.generate()
                    e = pair
                    out += pair.public.copyOf()
                    mixHash(pair.public)
                    if (psk != null) mixKey(pair.public)
                }
                Token.S -> out += encryptAndHash(s.public)
                Token.EE -> mixKey(dh(e!!, re!!))
                Token.ES -> mixKey(if (initiator) dh(e!!, rs!!) else dh(s, re!!))
                Token.SE -> mixKey(if (initiator) dh(s, re!!) else dh(e!!, rs!!))
                Token.SS -> mixKey(dh(s, rs!!))
                Token.PSK -> mixKeyAndHash(psk!!)
            }
        }
        out += encryptAndHash(payload)
        step++
        val message = concat(out)
        check(message.size <= Noise.MAX_MESSAGE) { "handshake message too long" }
        return message
    }

    /** The payload of the other side's next handshake message, or null — and a dead handshake — if it is not one. */
    public fun read(message: ByteArray): ByteArray? {
        if (failed || step >= messages.size || (step % 2 == 0) == initiator) return null
        val result = runCatching { readTokens(message) }.getOrNull()
        if (result == null) {
            failed = true
            wipe()
            return null
        }
        step++
        return result
    }

    private fun readTokens(message: ByteArray): ByteArray? {
        var at = 0
        for (token in messages[step]) {
            when (token) {
                Token.E -> {
                    if (message.size - at < DH_LEN) return null
                    val key = message.copyOfRange(at, at + DH_LEN)
                    at += DH_LEN
                    re = key
                    mixHash(key)
                    if (psk != null) mixKey(key)
                }
                Token.S -> {
                    val length = DH_LEN + if (cipher.hasKey) ChaCha20Poly1305.TAG_SIZE else 0
                    if (message.size - at < length) return null
                    val key = decryptAndHash(message.copyOfRange(at, at + length)) ?: return null
                    at += length
                    rs = key
                }
                Token.EE -> mixKey(dh(e!!, re!!))
                Token.ES -> mixKey(if (initiator) dh(e!!, rs!!) else dh(s, re!!))
                Token.SE -> mixKey(if (initiator) dh(s, re!!) else dh(e!!, rs!!))
                Token.SS -> mixKey(dh(s, rs!!))
                Token.PSK -> mixKeyAndHash(psk!!)
            }
        }
        return decryptAndHash(message.copyOfRange(at, message.size))
    }

    /** The transport, once every message has gone; the handshake's own secrets are wiped. */
    public fun split(): NoiseTransport {
        check(isComplete) { "handshake not complete" }
        val (k1, k2) = hkdf(ck, ByteArray(0), 2)
        val first = NoiseCipher(k1)
        val second = NoiseCipher(k2)
        val transport = if (initiator) {
            NoiseTransport(first, second, h.copyOf(), rs!!.copyOf())
        } else {
            NoiseTransport(second, first, h.copyOf(), rs!!.copyOf())
        }
        wipe()
        return transport
    }

    private fun wipe() {
        ck.fill(0)
        cipher.wipe()
        if (fixedEphemeral == null) e?.close()
    }

    // ------------------------------------------------------------ SymmetricState (§5.2)

    private fun mixHash(data: ByteArray) {
        h = Hash.SHA256.digest(h + data)
    }

    private fun mixKey(ikm: ByteArray) {
        val (nextCk, key) = hkdf(ck, ikm, 2)
        ck.fill(0)
        ck = nextCk
        cipher.replaceKey(key)
    }

    private fun mixKeyAndHash(ikm: ByteArray) {
        val (nextCk, tempH, key) = hkdf(ck, ikm, 3)
        ck.fill(0)
        ck = nextCk
        mixHash(tempH)
        cipher.replaceKey(key)
    }

    private fun encryptAndHash(plaintext: ByteArray): ByteArray = cipher.encrypt(h, plaintext).also { mixHash(it) }

    private fun decryptAndHash(ciphertext: ByteArray): ByteArray? {
        val plain = cipher.decrypt(h, ciphertext) ?: return null
        mixHash(ciphertext)
        return plain
    }

    private fun dh(pair: NoiseKeyPair, public: ByteArray): ByteArray {
        val shared = Curve25519.x25519(pair.private.bytes, public)
        // An all-zero result means a low-order point: no secret was agreed (RFC 7748 §6.1).
        require(shared.any { it != 0.toByte() }) { "low-order key" }
        return shared
    }

    private enum class Token { E, S, EE, ES, SE, SS, PSK }

    public companion object {
        private const val HASH_LEN = 32
        private const val DH_LEN = 32

        public fun initiator(
            pattern: NoisePattern,
            static: NoiseKeyPair,
            remoteStatic: ByteArray? = null,
            psk: ByteArray? = null,
            prologue: ByteArray = ByteArray(0),
        ): NoiseHandshake = NoiseHandshake(pattern, true, static, remoteStatic?.copyOf(), psk?.copyOf(), null, prologue)

        public fun responder(
            pattern: NoisePattern,
            static: NoiseKeyPair,
            remoteStatic: ByteArray? = null,
            psk: ByteArray? = null,
            prologue: ByteArray = ByteArray(0),
        ): NoiseHandshake = NoiseHandshake(pattern, false, static, remoteStatic?.copyOf(), psk?.copyOf(), null, prologue)

        /** With a fixed ephemeral key — only for the published test vectors. */
        internal fun forVector(
            pattern: NoisePattern,
            initiator: Boolean,
            static: NoiseKeyPair,
            remoteStatic: ByteArray?,
            psk: ByteArray?,
            ephemeral: NoiseKeyPair,
            prologue: ByteArray,
        ): NoiseHandshake = NoiseHandshake(pattern, initiator, static, remoteStatic, psk, ephemeral, prologue)

        /** HKDF as Noise defines it (§4.3): HMAC-SHA256 chained, [outputs] of 32 bytes. */
        private fun hkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
            val tempKey = hmac(Hash.SHA256, chainingKey, ikm)
            val result = ArrayList<ByteArray>(outputs)
            var previous = ByteArray(0)
            for (i in 1..outputs) {
                previous = hmac(Hash.SHA256, tempKey, previous + byteArrayOf(i.toByte()))
                result += previous
            }
            tempKey.fill(0)
            return result
        }

        private fun concat(parts: List<ByteArray>): ByteArray {
            val out = ByteArray(parts.sumOf { it.size })
            var at = 0
            parts.forEach { it.copyInto(out, at); at += it.size }
            return out
        }
    }
}

public object Noise {
    /** The largest Noise message (§3); the frame's 16-bit length can say no more. */
    public const val MAX_MESSAGE: Int = 65535
}
