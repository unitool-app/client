package cx.m42.apps.twofa

import kotlin.io.encoding.Base64

/**
 * The encrypting half of age v1 (age-encryption.org/v1) for `ssh-ed25519` and `ssh-rsa` keys.
 *
 * age, because it is the one tool that already encrypts to the SSH keys GitHub publishes:
 * whoever gets the file runs `age -d -i ~/.ssh/id_ed25519` with the key they already sign in to
 * GitHub with, and there is no key exchange and nothing Unitool-specific to install. The format
 * is small and fully specified, which is what made writing it by hand reasonable:
 *
 *  - a random 16-byte file key, wrapped once per recipient in a *stanza*;
 *  - a header MAC under a key derived from the file key, so no stanza can be swapped;
 *  - the payload in 64 KiB ChaCha20-Poly1305 chunks (STREAM), the last one flagged.
 *
 * The stanza recipes are age's `agessh` package, step for step. `desktopTest` opens what this
 * writes with an independent reader built on the JVM's own crypto, and with the real `age` binary
 * when there is one on the machine.
 */
internal object Age {

    private const val INTRO = "age-encryption.org/v1"
    private const val ED25519_LABEL = "age-encryption.org/v1/ssh-ed25519"
    private const val RSA_LABEL = "age-encryption.org/v1/ssh-rsa"
    private const val CHUNK = 64 * 1024

    /** age refuses anything shorter, and a file it cannot open is worse than no file. */
    const val MIN_RSA_BITS: Int = 2048

    /** Whether age can encrypt to [key] at all. */
    fun supports(key: SshKey): Boolean = when (key) {
        is SshKey.Ed25519 -> true
        is SshKey.Rsa -> key.bits >= MIN_RSA_BITS
        is SshKey.Other -> false
    }

    /** The binary file; any one of [recipients] opens it. */
    fun encrypt(plaintext: ByteArray, recipients: List<SshKey>, random: (Int) -> ByteArray = ::secureRandomBytes): ByteArray {
        require(recipients.isNotEmpty()) { "no recipients" }
        val fileKey = random(16)

        val header = buildString {
            append(INTRO).append('\n')
            recipients.forEach { append(stanza(it, fileKey, random)) }
            append("---")
        }
        val macKey = hkdfSha256(fileKey, ByteArray(0), "header".encodeToByteArray(), 32)
        val mac = hmac(HashAlgorithm.SHA256, macKey, header.encodeToByteArray())

        val nonce = random(16)
        val payloadKey = hkdfSha256(fileKey, nonce, "payload".encodeToByteArray(), 32)
        return (header + " " + Base64Raw.encode(mac) + "\n").encodeToByteArray() + nonce + stream(payloadKey, plaintext)
    }

    /**
     * age's ASCII armor: PEM, standard Base64, 64 columns. `age -d` recognises it by itself, and
     * text survives every channel a share sheet leads to — including the ones that are not files.
     */
    fun armor(file: ByteArray): String = buildString {
        append("-----BEGIN AGE ENCRYPTED FILE-----\n")
        Base64.Default.encode(file).chunked(64).forEach { append(it).append('\n') }
        append("-----END AGE ENCRYPTED FILE-----\n")
    }

    private fun stanza(key: SshKey, fileKey: ByteArray, random: (Int) -> ByteArray): String = when (key) {
        is SshKey.Ed25519 -> {
            val label = ED25519_LABEL.encodeToByteArray()
            val theirs = Curve25519.edwardsToMontgomery(key.point)
            val ephemeral = random(32)
            val ours = Curve25519.x25519(ephemeral, Curve25519.BASE)
            val shared = Curve25519.x25519(ephemeral, theirs)
            check(shared.any { it != 0.toByte() }) { "low-order key ${key.fingerprint}" }
            // The tweak binds the secret to this exact SSH key, not merely to its curve point.
            val tweak = hkdfSha256(ByteArray(0), key.blob, label, 32)
            val tweaked = Curve25519.x25519(tweak, shared)
            val wrappingKey = hkdfSha256(tweaked, ours + theirs, label, 32)
            stanzaText(
                "ssh-ed25519",
                listOf(tag(key), Base64Raw.encode(ours)),
                ChaCha20Poly1305.seal(wrappingKey, ByteArray(12), fileKey),
            )
        }
        is SshKey.Rsa -> {
            require(key.bits >= MIN_RSA_BITS) { "RSA key under $MIN_RSA_BITS bits" }
            stanzaText(
                "ssh-rsa",
                listOf(tag(key)),
                RsaOaep.encrypt(key.modulus, key.exponent, fileKey, RSA_LABEL.encodeToByteArray(), random(32)),
            )
        }
        is SshKey.Other -> throw IllegalArgumentException("age cannot encrypt to ${key.type}")
    }

    /** First four bytes of the key's SHA-256: lets `age -d` skip stanzas meant for other keys. */
    private fun tag(key: SshKey): String = Base64Raw.encode(Sha256.digest(key.blob).copyOf(4))

    /** `-> type args…`, then the body in 64-column lines, the last always shorter — empty if need be. */
    private fun stanzaText(type: String, args: List<String>, body: ByteArray): String = buildString {
        append("-> ").append(type)
        args.forEach { append(' ').append(it) }
        append('\n')
        val encoded = Base64Raw.encode(body)
        var start = 0
        while (true) {
            val end = minOf(start + 64, encoded.length)
            append(encoded.substring(start, end)).append('\n')
            if (end - start < 64) break
            start = end
        }
    }

    /** STREAM: nonce = 11-byte big-endian chunk counter ‖ 1 on the last chunk, 0 before it. */
    private fun stream(key: ByteArray, plaintext: ByteArray): ByteArray {
        val chunks = ArrayList<ByteArray>()
        var offset = 0
        var counter = 0L
        do {
            val end = minOf(offset + CHUNK, plaintext.size)
            val last = end == plaintext.size
            val nonce = ByteArray(12)
            for (i in 0 until 8) nonce[10 - i] = (counter ushr (8 * i)).toByte()
            nonce[11] = if (last) 1 else 0
            chunks += ChaCha20Poly1305.seal(key, nonce, plaintext.copyOfRange(offset, end))
            offset = end
            counter++
        } while (!last)
        val out = ByteArray(chunks.sumOf { it.size })
        var position = 0
        chunks.forEach { it.copyInto(out, position); position += it.size }
        return out
    }
}
