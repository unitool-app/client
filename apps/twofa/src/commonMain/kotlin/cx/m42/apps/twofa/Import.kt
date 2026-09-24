package cx.m42.apps.twofa

/**
 * What a batch of links turned out to hold: the export opened on a computer and shown as one QR
 * code, or pasted whole — one `otpauth://` link per line, as [Export.plaintext] writes them.
 */
internal data class ImportBatch(
    /** The accounts read, in the order the links came, the ones this device already has among them. */
    val found: List<Found> = emptyList(),
    /** The links that are not an account, and why. */
    val failed: List<Failed> = emptyList(),
    /** Every link read so far, so a failure in a second QR code is numbered after the first's. */
    val links: Int = 0,
) {
    /** [account] has no id yet; it gets one when it is added. */
    data class Found(val account: TotpAccount, val known: Boolean)

    /** [position] counts from 1, the way the lines of the file do. */
    data class Failed(val position: Int, val error: DraftError)

    /** The ones that can be added: everything but what the device already has. */
    val addable: List<Int> get() = found.indices.filter { !found[it].known }
}

internal object Import {

    private val SCHEME = Regex("otpauth(-migration)?://", RegexOption.IGNORE_CASE)

    /**
     * Every link in [text], each cut off where the next begins — so it does not matter whether a
     * file's newlines survived the trip, came as `\r\n`, or were turned into spaces by a text field.
     * Whatever comes before the first link is not one.
     */
    fun links(text: String): List<String> {
        val starts = SCHEME.findAll(text).map { it.range.first }.toList()
        return starts.mapIndexed { i, start -> text.substring(start, starts.getOrElse(i + 1) { text.length }).trim() }
    }

    /**
     * [text]'s links, added to [into]. An account with the same codes as one on the device is
     * marked known; one already in the batch — the same QR code scanned twice — is left out.
     */
    fun read(text: String, have: List<TotpAccount>, into: ImportBatch = ImportBatch()): ImportBatch {
        val found = into.found.toMutableList()
        val failed = into.failed.toMutableList()
        val links = links(text)
        links.forEachIndexed { i, link ->
            val account = OtpAuthUri.parse(link).mapCatching { it.validate(id = "").getOrThrow() }
            account.onSuccess { read ->
                if (found.none { sameCodes(it.account, read) }) {
                    found += ImportBatch.Found(read, known = have.any { sameCodes(it, read) })
                }
            }
            account.onFailure {
                failed += ImportBatch.Failed(into.links + i + 1, (it as? DraftException)?.error ?: DraftError.NotOtpAuth)
            }
        }
        return ImportBatch(found, failed, into.links + links.size)
    }

    /**
     * Two accounts that would show the same code at every moment. The names do not count: the same
     * key under a name changed by hand is still the one account.
     */
    private fun sameCodes(a: TotpAccount, b: TotpAccount): Boolean =
        a.secret == b.secret && a.algorithm == b.algorithm && a.digits == b.digits && a.period == b.period
}
