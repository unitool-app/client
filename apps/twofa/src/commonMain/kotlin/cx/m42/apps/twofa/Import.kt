package cx.m42.apps.twofa

/**
 * What a batch of links turned out to hold: the export opened on a computer and shown as one QR
 * code, or pasted whole — one `otpauth://` link per line, as [Export.plaintext] writes them — or
 * Google Authenticator's transfer codes, each holding several accounts ([GoogleMigration]).
 */
internal data class ImportBatch(
    /** The accounts read, in the order the links came, the ones this device already has among them. */
    val found: List<Found> = emptyList(),
    /** The links that are not an account, and why. */
    val failed: List<Failed> = emptyList(),
    /** Every link read so far, so a failure in a second QR code is numbered after the first's. */
    val links: Int = 0,
    /** Google Authenticator transfers, by their batch id: how many codes each was shown as, and which were scanned. */
    val transfers: Map<Int, Transfer> = emptyMap(),
) {
    /** [account] has no id yet; it gets one when it is added. */
    data class Found(val account: TotpAccount, val known: Boolean)

    /**
     * [position] counts from 1, the way the lines of the file do. [title] names the account when the
     * link held several — a transfer code's position means nothing to the person who scanned it.
     */
    data class Failed(val position: Int, val error: DraftError, val title: String = "")

    /** [scanned] holds the codes' numbers, from 0, of [size]. */
    data class Transfer(val size: Int, val scanned: Set<Int>)

    /** The ones that can be added: everything but what the device already has. */
    val addable: List<Int> get() = found.indices.filter { !found[it].known }
}

/** Why a picked file gave no accounts. */
internal enum class FileProblem { NoKey, NotOpened, NoAccounts }

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
        val transfers = into.transfers.toMutableMap()
        fun take(draft: Result<AccountDraft>, position: Int, title: String) {
            val account = draft.mapCatching { it.validate(id = "").getOrThrow() }
            account.onSuccess { read ->
                if (found.none { sameCodes(it.account, read) }) {
                    found += ImportBatch.Found(read, known = have.any { sameCodes(it, read) })
                }
            }
            account.onFailure {
                failed += ImportBatch.Failed(position, (it as? DraftException)?.error ?: DraftError.NotOtpAuth, title)
            }
        }
        val links = links(text)
        links.forEachIndexed { i, link ->
            val position = into.links + i + 1
            val part = GoogleMigration.parse(link)
            if (part == null) {
                take(OtpAuthUri.parse(link), position, title = "")
                return@forEachIndexed
            }
            val seen = transfers[part.batch]?.scanned.orEmpty()
            // The same transfer code twice: its accounts are in already, and so are its failures.
            if (part.index in seen) return@forEachIndexed
            transfers[part.batch] = ImportBatch.Transfer(part.size, seen + part.index)
            part.accounts.forEach { take(it.draft, position, it.title) }
        }
        return ImportBatch(found, failed, into.links + links.size, transfers)
    }

    /** True when [text] holds many accounts, however few links: a whole export, or a readable transfer code. */
    fun isBatch(text: String): Boolean {
        val links = links(text)
        return links.size > 1 || links.any { GoogleMigration.parse(it) != null }
    }

    /**
     * Two accounts that would show the same code at every moment. The names do not count: the same
     * key under a name changed by hand is still the one account.
     */
    private fun sameCodes(a: TotpAccount, b: TotpAccount): Boolean =
        a.secret == b.secret && a.algorithm == b.algorithm && a.digits == b.digits && a.period == b.period
}
