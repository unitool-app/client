package cx.m42.apps.twofa

import cx.m42.superizer.runtime.LenientJson
import cx.m42.superizer.runtime.NetworkException
import cx.m42.superizer.runtime.NetworkService
import kotlinx.serialization.Serializable

/** One key of the GitHub account, and whether the export can encrypt to it. */
internal data class GitHubKey(val key: SshKey) {
    val usable: Boolean get() = Age.supports(key)
}

/** Where the export page is in asking GitHub. */
internal sealed interface KeysState {
    data object Idle : KeysState
    data object Loading : KeysState
    data class Loaded(val login: String, val keys: List<GitHubKey>) : KeysState
    data class Failed(val error: KeysError) : KeysState
}

internal enum class KeysError { BadLogin, NoSuchUser, NoKeys, NoUsableKeys, RateLimited, Offline, Server }

/**
 * A GitHub account's public SSH keys — what anyone can read at `github.com/<login>.keys`.
 *
 * Through the REST API rather than that address: the same list, but the API answers a browser's
 * CORS check and `github.com` does not, so the web build asks the same URL the phone does.
 * Unauthenticated, which GitHub allows sixty times an hour per address.
 */
internal object GitHubKeys {

    fun url(login: String): String = "https://api.github.com/users/$login/keys"

    /**
     * What people paste: `octocat`, `@octocat`, `github.com/octocat`, the `.keys` address itself.
     * Null when what is left cannot be a GitHub login (letters, digits and single inner hyphens,
     * up to 39) — which also keeps anything odd out of the URL.
     */
    fun login(text: String): String? {
        val login = text.trim()
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .removePrefix("github.com/")
            .removePrefix("@")
            .removeSuffix("/")
            .removeSuffix(".keys")
        return login.takeIf { LOGIN.matches(it) }
    }

    private val LOGIN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$")

    suspend fun fetch(network: NetworkService, login: String): KeysState {
        val response = try {
            network.get(url(login))
        } catch (_: NetworkException) {
            return KeysState.Failed(KeysError.Offline)
        }
        return when {
            response.status == 404 -> KeysState.Failed(KeysError.NoSuchUser)
            // 403 with a zero remaining count is GitHub's rate limit; 429 is the newer spelling.
            response.status == 403 || response.status == 429 -> KeysState.Failed(KeysError.RateLimited)
            !response.isSuccess -> KeysState.Failed(KeysError.Server)
            else -> {
                val keys = runCatching { LenientJson.decodeFromString<List<ApiKey>>(response.body) }
                    .getOrElse { return KeysState.Failed(KeysError.Server) }
                    .mapNotNull { SshKey.parse(it.key) }
                    .map { GitHubKey(it) }
                when {
                    keys.isEmpty() -> KeysState.Failed(KeysError.NoKeys)
                    keys.none { it.usable } -> KeysState.Failed(KeysError.NoUsableKeys)
                    // Usable first, in GitHub's order, so the ticked rows are the ones at the top.
                    else -> KeysState.Loaded(login, keys.sortedBy { !it.usable })
                }
            }
        }
    }

    @Serializable
    private data class ApiKey(val key: String)
}

/** The export itself: what goes in the file, what the file is called, and how to open it. */
internal object Export {

    /**
     * One `otpauth://` link per line — the format every authenticator imports and `qrencode` turns
     * back into the QR code a site showed. Nothing Unitool-specific, on purpose: the file has to be
     * useful on a machine that has never heard of this app.
     */
    fun plaintext(accounts: List<TotpAccount>): String = accounts.joinToString("") { OtpAuthUri.format(it) + "\n" }

    /** `unitool-2fa-2026-09-24.age`: dated, so two exports on a computer are not one overwritten. */
    fun fileName(epochMillis: Long): String = "unitool-2fa-${isoDate(epochMillis)}.age"

    /**
     * The command that opens the file, one per kind of key it was encrypted to. The identity path is
     * ssh-keygen's default for that kind; someone who keeps keys elsewhere knows where theirs is.
     * The links go to the terminal rather than into a file, so opening the export does not leave
     * the secrets on disk in the clear.
     */
    fun commands(keys: List<SshKey>, fileName: String): List<String> =
        keys.map { identityFile(it) }.distinct().map { "age -d -i ~/.ssh/$it $fileName" }

    /**
     * The same, drawn as a QR code in the terminal: the new phone scans it on its add page and gets
     * every account at once (see [Import]). The links go from `age` straight into `qrencode`, so
     * this too leaves nothing on disk.
     *
     * One code while [plaintext] fits in one; past that, `split` cuts it into codes that do, shown
     * one after another on Enter, and the phone scans them onto one list.
     */
    fun transferCommands(keys: List<SshKey>, fileName: String, plaintext: String): List<String> {
        val qr = if (plaintext.encodeToByteArray().size <= QR_BYTES) {
            QRENCODE
        } else {
            val longest = plaintext.lines().maxOf { it.encodeToByteArray().size + 1 }
            "split -l ${(QR_BYTES / longest).coerceAtLeast(1)} --filter='clear; $QRENCODE; read _ </dev/tty'"
        }
        return commands(keys, fileName).map { "$it | $qr" }
    }

    /** Colours spelled out, so the code is dark on light in a dark terminal too. */
    private const val QRENCODE = "qrencode -t ansiutf8"

    /**
     * What one QR code holds as bytes: version 40 at the lowest error correction. `qrencode` fits
     * a little more by switching modes, but not by enough to count on.
     */
    internal const val QR_BYTES = 2953

    private fun identityFile(key: SshKey): String = when (key) {
        is SshKey.Rsa -> "id_rsa"
        else -> "id_ed25519"
    }

    /** UTC calendar date without a date library: Howard Hinnant's `civil_from_days`. */
    internal fun isoDate(epochMillis: Long): String {
        val days = floorDiv(epochMillis, 86_400_000L)
        val z = days + 719_468
        val era = floorDiv(z, 146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    private fun floorDiv(a: Long, b: Long): Long = a / b - if (a % b < 0) 1 else 0
}

/** An export ready to hand over: armored age text and the name to give it. */
internal data class ExportFile(val name: String, val text: String, val recipients: List<SshKey>, val login: String)

/** An export that left the app, and which way. */
internal data class ExportDone(val fileName: String, val via: Via) {
    enum class Via { Shared, Copied }
}
