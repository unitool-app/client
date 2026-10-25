package cx.m42.unitool.cli.browser

import cx.m42.unitool.cli.Io
import cx.m42.unitool.cli.fail
import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.link.NoiseKeyPair
import cx.m42.unitool.kit.ssh.Age
import cx.m42.unitool.kit.ssh.KeyFileRefusal
import cx.m42.unitool.kit.ssh.OpenSshKeyFile
import cx.m42.unitool.kit.ssh.PrivateKey
import cx.m42.unitool.kit.ssh.SshPublicKey
import kotlin.io.encoding.Base64

/** A paired phone, as the computer keeps it (D568). */
public class Phone(
    /** `P`, the phone's static key. */
    public val key: ByteArray,
    public val name: String,
    /** The mDNS name the phone answers to for this computer (01 §3). */
    public val linkId: String,
    public val pairedAt: Long,
    /** `address:port` it was last reached at — the fallback when mDNS is silent (D547). */
    public val lastAddress: String?,
) {
    /** What `devices` and `forget` call it: the start of the key, in hex. */
    public val id: String get() = key.copyOf(4).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/**
 * `~/.config/unitool/link/` (D568): `key` — this computer's link key, `phones` — the phones, one per
 * line, `settings` — which SSH key answers are sealed to. Everything 0600 in a 0700 directory; no
 * password, no psk, ever.
 */
public class LinkStore(private val io: Io, private val system: BrowserSystem) {

    public val dir: String = configHome(io) + "/unitool/link"
    private val keyFile = "$dir/key"
    private val phonesFile = "$dir/phones"
    private val settingsFile = "$dir/settings"

    /** This computer's link key `C`, made the first time it is needed. */
    public fun key(create: Boolean = true): NoiseKeyPair? {
        if (io.exists(keyFile)) {
            val text = io.readFile(keyFile).decodeToString().trim()
            val bytes = runCatching { Base64.decode(text) }.getOrNull()
            if (bytes == null || bytes.size != 32) fail("$keyFile is not a link key; remove it to make a new one (the phones will need pairing again)")
            return NoiseKeyPair.fromPrivate(bytes)
        }
        if (!create) return null
        system.mkdirs(dir)
        val pair = NoiseKeyPair.generate()
        io.writeFile(keyFile, (Base64.encode(pair.private.bytes) + "\n").encodeToByteArray(), private = true)
        return pair
    }

    public fun phones(): List<Phone> {
        if (!io.exists(phonesFile)) return emptyList()
        return io.readFile(phonesFile).decodeToString().lineSequence().mapNotNull(::parse).toList()
    }

    public fun savePhones(phones: List<Phone>) {
        system.mkdirs(dir)
        io.writeFile(phonesFile, phones.joinToString("") { format(it) + "\n" }.encodeToByteArray(), private = true)
    }

    public fun sshKeyPath(): String? = settings()["ssh"]

    public fun saveSshKeyPath(path: String) {
        system.mkdirs(dir)
        io.writeFile(settingsFile, "ssh=$path\n".encodeToByteArray(), private = true)
    }

    private fun settings(): Map<String, String> {
        if (!io.exists(settingsFile)) return emptyMap()
        return io.readFile(settingsFile).decodeToString().lineSequence()
            .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() } }
            .toMap()
    }

    // v1 <key b64> <linkId> <pairedAt> <lastAddress or -> <name, percent-encoded>
    private fun format(p: Phone): String =
        "v1 ${Base64.encode(p.key)} ${p.linkId} ${p.pairedAt} ${p.lastAddress ?: "-"} ${encode(p.name)}"

    private fun parse(line: String): Phone? {
        val parts = line.trim().split(' ')
        if (parts.size != 6 || parts[0] != "v1") return null
        val key = runCatching { Base64.decode(parts[1]) }.getOrNull()?.takeIf { it.size == 32 } ?: return null
        return Phone(key, decode(parts[5]), parts[2], parts[3].toLongOrNull() ?: 0, parts[4].takeIf { it != "-" })
    }

    private fun encode(text: String): String = buildString {
        for (b in text.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            if (c in 0x21..0x7e && c != '%'.code) append(c.toChar()) else append('%').append(c.toString(16).padStart(2, '0'))
        }
    }

    private fun decode(text: String): String {
        val out = ArrayList<Byte>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '%' && i + 3 <= text.length) {
                out += text.substring(i + 1, i + 3).toInt(16).toByte()
                i += 3
            } else {
                out += text[i].code.toByte()
                i++
            }
        }
        return out.toByteArray().decodeToString()
    }

    public companion object {
        public fun configHome(io: Io): String =
            io.env("XDG_CONFIG_HOME")?.takeIf { it.startsWith("/") } ?: ((io.env("HOME") ?: fail("HOME is not set")) + "/.config")

        public fun dataHome(io: Io): String =
            io.env("XDG_DATA_HOME")?.takeIf { it.startsWith("/") } ?: ((io.env("HOME") ?: fail("HOME is not set")) + "/.local/share")
    }
}

/**
 * The SSH key the answers are sealed to and opened with (D545): Ed25519 or RSA, `openssh-key-v1`,
 * without a passphrase. A key under a passphrase is turned away at pairing, so the person learns it
 * then and not at the first password (answer 235).
 */
public object SshKeyChoice {

    public sealed interface Result {
        public class Ok(public val path: String, public val publicKey: SshPublicKey) : Result
        public class Refused(public val message: String) : Result
    }

    /** [given] (`--key`), else the stored choice, else `~/.ssh/id_ed25519`, else `~/.ssh/id_rsa`. */
    public fun choose(io: Io, store: LinkStore, given: String?): Result {
        val home = io.env("HOME") ?: return Result.Refused("HOME is not set")
        val candidates = listOfNotNull(given?.let { expand(it, home) }, store.sshKeyPath()).ifEmpty {
            listOf("$home/.ssh/id_ed25519", "$home/.ssh/id_rsa").filter { io.exists(it) }
        }
        val path = candidates.firstOrNull()
            ?: return Result.Refused("No SSH key: neither ~/.ssh/id_ed25519 nor ~/.ssh/id_rsa is there. Make one with ssh-keygen -t ed25519, or name one: --key FILE")
        return check(io, path)
    }

    public fun check(io: Io, path: String): Result {
        if (!io.exists(path)) return Result.Refused("$path: no such file")
        val bytes = io.readFile(path)
        try {
            return when (val parsed = OpenSshKeyFile.parse(bytes)) {
                is OpenSshKeyFile.Parsed.Refused -> Result.Refused("$path: ${refusal(parsed.refusal)}")
                is OpenSshKeyFile.Parsed.Ok -> {
                    val file = parsed.file
                    when {
                        file.isEncrypted -> Result.Refused("The key $path is protected by a passphrase — such keys are not supported yet; name another one: --key FILE")
                        !Age.supports(file.publicKey) -> Result.Refused("$path is ${file.publicKey.label}: age can only use ssh-ed25519 or ssh-rsa of 2048 bits or more; name another one: --key FILE")
                        else -> Result.Ok(path, file.publicKey).also { file.wipe() }
                    }
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    /** The private key, open for one decryption; the caller closes it. */
    public fun open(io: Io, path: String): PrivateKey? {
        val bytes = io.readFile(path)
        try {
            val file = (OpenSshKeyFile.parse(bytes) as? OpenSshKeyFile.Parsed.Ok)?.file ?: return null
            return try {
                (file.decrypt(null) as? OpenSshKeyFile.Decrypted.Ok)?.key
            } finally {
                file.wipe()
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun expand(path: String, home: String): String = if (path.startsWith("~/")) home + path.substring(1) else path

    private fun refusal(r: KeyFileRefusal): String = when (r) {
        KeyFileRefusal.PublicKeyOnly -> "this is the public half; name the private key file"
        KeyFileRefusal.OldFormat -> "an old PEM key; rewrite it with ssh-keygen -p -f FILE"
        KeyFileRefusal.HardwareToken -> "a hardware key (sk-…): age cannot decrypt with it"
        KeyFileRefusal.RsaTooShort -> "RSA shorter than 2048 bits"
        KeyFileRefusal.Dsa -> "DSA, which age does not take"
        KeyFileRefusal.PuttyFormat -> "a PuTTY key; convert it with puttygen to OpenSSH"
        KeyFileRefusal.UnsupportedCipher, KeyFileRefusal.UnsupportedKdf -> "encrypted in a way this does not read"
        KeyFileRefusal.UnsupportedType -> "a key type age does not take"
        KeyFileRefusal.NotAKey, KeyFileRefusal.Corrupted -> "not an OpenSSH private key"
    }
}

/** 16 random bytes, the id of one request (README §4). */
internal fun requestId(): ByteArray = secureRandomBytes(16)
