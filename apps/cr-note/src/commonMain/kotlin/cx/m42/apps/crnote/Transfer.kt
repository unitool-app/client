package cx.m42.apps.crnote

import cx.m42.unitool.kit.openssl.OpensslEnc
import cx.m42.unitool.kit.ssh.Age
import cx.m42.unitool.kit.ssh.AgeKeys
import cx.m42.unitool.kit.ssh.SshPublicKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A note as it leaves the app (D660): no id, nothing of the directory or the PIN. */
@Serializable
internal class ExportNote(val text: String, val created: Long, val modified: Long)

@Serializable
internal class ExportFile(val format: String, val notes: List<ExportNote>)

/** The three ways out (D660): age with a password — the default — age to keys, `openssl enc`. */
internal enum class ExportMethod { AgePassword, AgeKeys, Openssl }

/** What an import file turned out to be, before anything is asked for. */
internal sealed interface Incoming {
    /** age; [password] when its one stanza is scrypt, otherwise a key of this phone opens it. */
    class Age(val file: ByteArray, val password: Boolean) : Incoming

    class Openssl(val file: ByteArray) : Incoming

    /** Not encrypted, or nothing this app knows: refused, plain text included (D661). */
    data object Refused : Incoming
}

/**
 * Export and import (D660, D661): what is inside, how it is sealed, and how a file that came in is
 * read. Pure functions; the screens are elsewhere.
 */
internal object Transfer {

    const val FORMAT = "cr-note/1"
    const val MIN_PASSWORD = 12

    /**
     * `openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 -salt -a`: OWASP's iterations for
     * PBKDF2-SHA256, and the digest said out loud, since OpenSSL 1.1's default is not 3's.
     */
    const val OPENSSL_ITERATIONS = 600_000
    val OPENSSL = OpensslEnc.Params(cipher = "aes-256-cbc", kdf = OpensslEnc.Kdf.Pbkdf2, iterations = OPENSSL_ITERATIONS, digest = "sha256")

    private val JSON = Json { ignoreUnknownKeys = true }
    private val PRETTY = Json { prettyPrint = true }

    /** `cr-note/1` JSON, readable on a computer after `age -d` (D660). */
    fun plaintext(notes: List<Note>): ByteArray =
        PRETTY.encodeToString(ExportFile.serializer(), ExportFile(FORMAT, notes.map { ExportNote(it.text, it.created, it.modified) }))
            .encodeToByteArray()

    fun agePassword(plain: ByteArray, password: String): String =
        Age.armor(AgeKeys.encryptWithPassphrase(plain, password.encodeToByteArray()))

    fun ageKeys(plain: ByteArray, ssh: List<SshPublicKey>, x25519: List<ByteArray>): String =
        Age.armor(Age.encrypt(plain, ssh, x25519))

    fun openssl(plain: ByteArray, password: String): String =
        OpensslEnc.armor(OpensslEnc.encrypt(plain, OpensslEnc.Key.Password(password.encodeToByteArray()), OPENSSL))

    fun fileName(epochMillis: Long, method: ExportMethod): String =
        "cr-note-${isoDate(epochMillis)}" + if (method == ExportMethod.Openssl) ".enc" else ".age"

    /** What opens it on a computer; the notes go to the terminal, not to a file. */
    fun command(method: ExportMethod, fileName: String): String = when (method) {
        ExportMethod.AgePassword -> "age -d $fileName"
        ExportMethod.AgeKeys -> "age -d -i ~/.ssh/id_ed25519 $fileName"
        ExportMethod.Openssl -> "openssl enc -d -aes-256-cbc -pbkdf2 -iter $OPENSSL_ITERATIONS -md sha256 -a -in $fileName"
    }

    // ------------------------------------------------------------ in

    fun detect(bytes: ByteArray): Incoming {
        val age = Age.dearmor(bytes)
        if (age != null && Age.header(age) != null) {
            val stanzas = Age.stanzas(age) ?: emptyList()
            return Incoming.Age(age, password = stanzas.size == 1 && stanzas[0].type == "scrypt")
        }
        if (startsWith(bytes, OpensslEnc.MAGIC)) return Incoming.Openssl(bytes)
        val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true).trim() }.getOrNull()
        if (text != null && text.startsWith(OpensslEnc.ARMORED_PREFIX)) {
            OpensslEnc.dearmor(text)?.takeIf { startsWith(it, OpensslEnc.MAGIC) }?.let { return Incoming.Openssl(it) }
        }
        return Incoming.Refused
    }

    /**
     * What was decrypted, as notes: a `cr-note/1` export as its notes, any other text as one note
     * (D661, question 286). Null for what is not text.
     */
    fun notes(plain: ByteArray, now: Long): List<ExportNote>? {
        val text = runCatching { plain.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return null
        val export = runCatching { JSON.decodeFromString(ExportFile.serializer(), text) }.getOrNull()
        if (export != null && export.format == FORMAT) return export.notes
        if (text.isBlank() || text.any { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }) return null
        return listOf(ExportNote(text, now, now))
    }

    /** [incoming] added to [existing]: what is already there by text and creation time is left out (D661). */
    fun fresh(existing: List<Note>, incoming: List<ExportNote>): List<ExportNote> {
        val have = existing.map { it.text to it.created }.toSet()
        return incoming.filter { (it.text to it.created) !in have }.distinctBy { it.text to it.created }
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && bytes.copyOfRange(0, prefix.size).contentEquals(prefix)

    /** yyyy-mm-dd, UTC, without a date library (Howard Hinnant's civil_from_days). */
    fun isoDate(epochMillis: Long): String {
        val days = epochMillis.floorDiv(86_400_000L)
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
        return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    /** yyyy-mm-dd hh:mm, UTC: the list's date (D657). */
    fun shortDate(epochMillis: Long): String {
        val minutes = epochMillis.floorDiv(60_000L).mod(1440L)
        return isoDate(epochMillis) + " " + (minutes / 60).toString().padStart(2, '0') + ":" + (minutes % 60).toString().padStart(2, '0')
    }
}
