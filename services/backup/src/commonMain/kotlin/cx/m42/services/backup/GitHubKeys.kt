package cx.m42.services.backup

import cx.m42.services.ssh.Age
import cx.m42.services.ssh.SshPublicKey
import cx.m42.superizer.runtime.NetworkException
import cx.m42.superizer.runtime.NetworkService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * A GitHub account's public SSH keys — what anyone reads at `github.com/<login>.keys` — through the
 * REST API, which answers a browser's CORS check where `github.com` does not. The same rules as the
 * 2FA export's copy (apps/twofa `Export.kt`), which an app keeps for itself: an app cannot reach a
 * host module.
 */
public object GitHubKeys {

    public fun url(login: String): String = "https://api.github.com/users/$login/keys"

    /** `octocat`, `@octocat`, `github.com/octocat`, the `.keys` address; null when what is left cannot be a login. */
    public fun login(text: String): String? {
        val login = text.trim()
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .removePrefix("github.com/")
            .removePrefix("@")
            .removeSuffix("/")
            .removeSuffix(".keys")
        return login.takeIf { LOGIN.matches(it) }
    }

    public suspend fun fetch(network: NetworkService, login: String): Fetched {
        val response = try {
            network.get(url(login))
        } catch (_: NetworkException) {
            return Fetched.Failed(Problem.Offline)
        }
        return when {
            response.status == 404 -> Fetched.Failed(Problem.NoSuchUser)
            // 403 with nothing left is GitHub's rate limit; 429 is the newer spelling.
            response.status == 403 || response.status == 429 -> Fetched.Failed(Problem.RateLimited)
            !response.isSuccess -> Fetched.Failed(Problem.Server)
            else -> {
                val keys = runCatching {
                    Json.parseToJsonElement(response.body).jsonArray.mapNotNull { (it as? JsonObject)?.get("key")?.jsonPrimitive?.content }
                }.getOrElse { return Fetched.Failed(Problem.Server) }
                    .mapNotNull { SshPublicKey.parse(it) }
                when {
                    keys.isEmpty() -> Fetched.Failed(Problem.NoKeys)
                    keys.none { Age.supports(it) } -> Fetched.Failed(Problem.NoUsableKeys)
                    else -> Fetched.Keys(login, keys.map { Recipient(it, Age.supports(it)) }.sortedBy { !it.usable })
                }
            }
        }
    }

    /** One published key, and whether age can encrypt to it (ECDSA and short RSA it cannot). */
    public class Recipient(public val key: SshPublicKey, public val usable: Boolean) {
        /** The `authorized_keys` line the cipher is handed. */
        public val line: String get() = key.authorizedKeysLine()
    }

    public sealed interface Fetched {
        public class Keys(public val login: String, public val keys: List<Recipient>) : Fetched
        public class Failed(public val problem: Problem) : Fetched
    }

    public enum class Problem { NoSuchUser, NoKeys, NoUsableKeys, RateLimited, Offline, Server }

    private val LOGIN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$")
}
