package cx.m42.apps.crrestore

import cx.m42.unitool.kit.crypt.Inspected
import cx.m42.unitool.kit.crypt.Ucrypt
import cx.m42.unitool.kit.openssl.OpensslEnc
import cx.m42.unitool.kit.split.PartReading
import cx.m42.unitool.kit.split.SplitLink

/** The three ways back (D599). */
internal enum class Method { Age, Openssl, Split }

/**
 * What the person has put in so far for age or openssl: QR codes one after another (each a line of
 * what `unitool qr encode --split` cut), a pasted text, or a file. Bytes, so a file need not be text.
 */
internal class Input(val name: String?, val bytes: ByteArray, val codes: Int) {
    fun wipe() = bytes.fill(0)

    companion object {
        /** [scanned] after what [before] had, on a new line: the pieces of one text, in order (D600). */
        fun appendCode(before: Input?, scanned: String): Input {
            val previous = before?.takeIf { it.codes > 0 }
            val text = if (previous == null) scanned else previous.bytes.decodeToString().trimEnd('\n') + "\n" + scanned
            previous?.wipe()
            return Input(null, text.encodeToByteArray(), (previous?.codes ?: 0) + 1)
        }
    }
}

/** Formats by their first bytes — only to say "this looks like age" when another method is open (D601). */
internal object Detect {
    private val AGE_INTRO = "age-encryption.org/v1".encodeToByteArray()
    private const val AGE_ARMOR = "-----BEGIN AGE ENCRYPTED FILE-----"
    private const val AGE_ARMOR_END = "-----END AGE ENCRYPTED FILE-----"
    private const val UCRYPT_ARMOR_END = "-----END UCRYPT FILE-----"

    fun method(bytes: ByteArray): Method? {
        val text = runCatching { bytes.decodeToString().trimStart() }.getOrDefault("")
        return when {
            bytes.startsWith(AGE_INTRO) || text.startsWith(AGE_ARMOR) -> Method.Age
            bytes.startsWith(OpensslEnc.MAGIC) || text.startsWith(OpensslEnc.ARMORED_PREFIX) -> Method.Openssl
            Ucrypt.isUcrypt(bytes) || Ucrypt.isArmored(bytes) -> Method.Split
            SplitLink.read(text.trim()) is PartReading.Ok -> Method.Split
            else -> null
        }
    }

    /** An armored file whose END line has arrived: the last QR is in, open it without a button (D600). */
    fun armorComplete(bytes: ByteArray): Boolean {
        val text = runCatching { bytes.decodeToString() }.getOrDefault("")
        return text.contains(AGE_ARMOR_END) || text.contains(UCRYPT_ARMOR_END)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && copyOf(prefix.size).contentEquals(prefix)
}

/** What came out (D602): text to read on the screen, or a file to save. */
internal sealed interface Output {
    val bytes: ByteArray
    val name: String

    class Text(val text: String, override val bytes: ByteArray, override val name: String) : Output
    class File(override val bytes: ByteArray, override val name: String) : Output

    companion object {
        /** Text up to this size is shown; above it, a file (owner, Q258). */
        const val MAX_TEXT_BYTES: Int = 64 * 1024

        fun of(bytes: ByteArray, name: String): Output {
            val text = text(bytes)
            return if (text != null) Text(text, bytes, textName(name)) else File(bytes, name)
        }

        /** UTF-8 with no control characters but line breaks and tabs, and not too long; else null. */
        fun text(bytes: ByteArray): String? {
            if (bytes.isEmpty() || bytes.size > MAX_TEXT_BYTES) return null
            val decoded = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return null
            if (decoded.any { it.code < 32 && it != '\n' && it != '\r' && it != '\t' || it.code == 127 }) return null
            return decoded
        }

        private fun textName(name: String): String =
            if (name.endsWith(".txt") || name.substringAfterLast('/').contains('.')) name else "$name.txt"

        /**
         * The name to save under: the one the container carried (split), else the opened file's
         * without its encryption extension, else `cr-restore-<date>`.
         */
        fun name(inside: String?, opened: String?, today: String): String {
            inside?.takeIf { it.isNotBlank() }?.let { return it }
            opened?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { file ->
                for (ext in listOf(".age", ".enc", ".ucrypt", ".asc", ".b64")) {
                    if (file.endsWith(ext) && file.length > ext.length) return file.dropLast(ext.length)
                }
                return "$file.out"
            }
            return "cr-restore-$today"
        }
    }
}

/** What the split screen has gathered (D611): the container, if any, and the parts in scan order. */
internal class SplitGathering(val container: ByteArray?, val containerName: String?, val parts: List<cx.m42.unitool.kit.split.SplitPart>) {
    /** k and n, once the container says them. */
    val need: Int? get() = header()?.split?.k
    val total: Int? get() = header()?.split?.n

    fun header() = container?.let { (Ucrypt.inspect(it) as? Inspected.Ok)?.header }
}
