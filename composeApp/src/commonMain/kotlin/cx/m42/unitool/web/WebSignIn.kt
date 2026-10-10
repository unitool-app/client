package cx.m42.unitool.web

import cx.m42.superizer.Superizer
import cx.m42.superizer.event.SuperizerEvent
import cx.m42.superizer.host.storage.SafePrefs
import cx.m42.superizer.runtime.NetworkRequest
import cx.m42.superizer.runtime.NetworkService
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.secrets.SecretVault
import cx.m42.unitool.kit.crypto.Sha256
import cx.m42.unitool.kit.crypto.secureRandomBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Signing in to the website with this app (.ai-notes/site/02).
 *
 * The site shows a QR; this app reads it, asks the server what is behind it (which browser, from
 * which address), and on the person's tap claims it with a device key. The browser is then signed in
 * as this installation — no name, no account — and sees the docs of the hidden parts this install has
 * opened: its `sections`.
 *
 * The device key is the server's ordinary `d_….ds_…` (unitool-server README), kept by the host and
 * not the push registrar's: that one lives inside Superizer, and exists only once a push app is used.
 * It is registered the first time the person signs a browser in, never before.
 */

/** What the server told about a browser waiting to be signed in. Times are epoch seconds. */
@Serializable
public data class WebLogin(
    val loginId: String,
    val browser: WebBrowserInfo,
    val createdAt: Long,
    val expiresAt: Long,
)

@Serializable
public data class WebBrowserInfo(val userAgent: String? = null, val ip: String? = null)

/** A browser signed in as this install. */
@Serializable
public data class WebSession(
    val id: String,
    val userAgent: String? = null,
    val ip: String? = null,
    val createdAt: Long,
    val lastSeenAt: Long? = null,
)

public sealed interface WebResult<out T> {
    public data class Ok<T>(val value: T) : WebResult<T>

    /** 410: the code expired or someone already used it. A new one is on the browser's page. */
    public data object Gone : WebResult<Nothing>

    /** No network, a 5xx, a body that did not parse. */
    public data class Failed(val reason: String) : WebResult<Nothing>
}

/** The two strings this keeps. A test swaps it for a map. */
public interface WebStore {
    public fun get(key: String): String?
    public fun put(key: String, value: String)
    public fun remove(key: String)
}

public object PrefsWebStore : WebStore {
    override fun get(key: String): String? = SafePrefs.get(key)
    override fun put(key: String, value: String): Unit = SafePrefs.put(key, value)
    override fun remove(key: String): Unit = SafePrefs.remove(key)
}

/**
 * Sections this install has opened that [Superizer.unlocked] does not show: a host screen opened by
 * its code (SSH keys) and secret apps, which are opened for one visit and never stay unlocked.
 * Kept for good — a part once opened keeps its docs.
 */
public class OpenedSections(private val store: WebStore = PrefsWebStore) {
    private val _value = MutableStateFlow(load())
    public val value: StateFlow<Set<String>> = _value.asStateFlow()

    public fun add(section: String) {
        if (section in _value.value) return
        _value.value = _value.value + section
        store.put(KEY, _value.value.sorted().joinToString(","))
    }

    private fun load(): Set<String> =
        store.get(KEY)?.split(',')?.filter { SECTION.matches(it) }?.toSet().orEmpty()

    private companion object {
        const val KEY = "unitool.web.opened"
        val SECTION = Regex("^[a-z0-9][a-z0-9-]*$")
    }
}

public class WebSignIn(
    private val network: NetworkService,
    server: String,
    private val platform: Platform,
    private val appVersion: String,
    private val store: WebStore = PrefsWebStore,
    public val opened: OpenedSections = OpenedSections(store),
) {
    /** `https://unitool.m42.cx` — the only server whose codes this app accepts. */
    public val server: String = server.trimEnd('/')
    private val api = "${this.server}/api/unitool/v1"
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    private var vault: () -> SecretVault? = { null }
    private var unlocked: () -> Set<String> = { emptySet() }

    private val _sections = MutableStateFlow(opened.value.value)

    /** What a browser signed in with this app may read: unlocked hidden apps and opened parts. */
    public val sections: StateFlow<Set<String>> = _sections.asStateFlow()

    private val _registered = MutableStateFlow(store.get(KEY_DEVICE) != null)
    public val registered: StateFlow<Boolean> = _registered.asStateFlow()

    /**
     * After the host is built: follow what is unlocked and opened, remember secret apps as they are
     * opened, and tell the server when the list changes — only once this install signed a browser in.
     */
    public fun attach(superizer: Superizer, scope: CoroutineScope) {
        vault = { superizer.secrets.vault.value }
        unlocked = { superizer.unlocked.value.map { it.value }.toSet() }
        scope.launch {
            superizer.events.collect { event ->
                if (event is SuperizerEvent.Launched && superizer.registry.isSecret(event.appId)) opened.add(event.appId.value)
            }
        }
        scope.launch {
            combine(superizer.unlocked, opened.value) { apps, parts -> apps.map { it.value }.toSet() + parts }
                .distinctUntilChanged()
                .collect { now ->
                    _sections.value = now
                    if (store.get(KEY_DEVICE) != null && store.get(KEY_SYNCED) != encode(now)) pushSections(now)
                }
        }
    }

    /** The `wlt_…` token of a sign-in link from [server], or null for anything else a camera reads. */
    public fun tokenOf(scanned: String): String? {
        val text = scanned.trim()
        val prefix = "$server/login/app/#"
        if (!text.startsWith(prefix)) return null
        return text.removePrefix(prefix).takeIf { TOKEN.matches(it) }
    }

    /** What is asking to be signed in. Registers this install first if it never was. */
    public suspend fun inspect(token: String): WebResult<WebLogin> = withDevice { key ->
        call("POST", "/web/logins/inspect", key, json.encodeToString(TokenBody.serializer(), TokenBody(token))) {
            json.decodeFromString(WebLogin.serializer(), it)
        }
    }

    /** The person tapped Sign in: the browser is signed in as this install, with today's sections. */
    public suspend fun claim(token: String): WebResult<WebLogin> = withDevice { key ->
        val current = currentSections()
        val result = call("POST", "/web/logins/claim", key, json.encodeToString(ClaimBody.serializer(), ClaimBody(token, current.sorted()))) {
            json.decodeFromString(WebLogin.serializer(), it)
        }
        if (result is WebResult.Ok) store.put(KEY_SYNCED, encode(current))
        result
    }

    /** Browsers signed in as this install; none before the first sign-in. */
    public suspend fun sessions(): WebResult<List<WebSession>> {
        if (store.get(KEY_DEVICE) == null) return WebResult.Ok(emptyList())
        return withDevice { key ->
            call("GET", "/web/sessions", key, null) { json.decodeFromString(SessionsBody.serializer(), it).sessions }
        }
    }

    /** Signs one browser out, or every one when [id] is null. */
    public suspend fun signOut(id: String?): WebResult<Unit> {
        if (store.get(KEY_DEVICE) == null) return WebResult.Ok(Unit)
        return withDevice { key -> call("DELETE", if (id == null) "/web/sessions" else "/web/sessions/$id", key, null) { } }
    }

    private fun currentSections(): Set<String> = unlocked() + opened.value.value

    private suspend fun pushSections(now: Set<String>) {
        val result = withDevice { key ->
            call("PUT", "/devices/me", key, json.encodeToString(DeviceBody.serializer(), DeviceBody(sections = now.sorted()))) { }
        }
        if (result is WebResult.Ok) store.put(KEY_SYNCED, encode(now))
    }

    /**
     * Runs [block] with `d_….ds_…`, registering first when there is none. A 401 means the server forgot
     * this install: register again, with a new secret, and try once more.
     */
    private suspend fun <T> withDevice(block: suspend (key: String) -> WebResult<T>): WebResult<T> {
        val key = deviceKey() ?: when (val made = register()) {
            is WebResult.Ok -> made.value
            WebResult.Gone -> return WebResult.Failed("register")
            is WebResult.Failed -> return made
        }
        val first = runCatching { block(key) }.getOrElse { return WebResult.Failed("network") }
        if (first !is WebResult.Failed || first.reason != UNAUTHORIZED) return first
        store.remove(KEY_DEVICE)
        _registered.value = false
        val again = when (val made = register()) {
            is WebResult.Ok -> made.value
            WebResult.Gone -> return WebResult.Failed("register")
            is WebResult.Failed -> return made
        }
        return runCatching { block(again) }.getOrElse { WebResult.Failed("network") }
    }

    private suspend fun register(): WebResult<String> {
        val secret = newSecret()
        val body = DeviceBody(
            secretHash = Sha256.digest(secret.encodeToByteArray()).toHex(),
            platform = when (platform) {
                Platform.Android -> "android"
                Platform.Ios -> "ios"
                Platform.Desktop -> "desktop"
                Platform.Web -> "web"
            },
            appVersion = appVersion,
            sections = currentSections().sorted(),
        )
        val result = call("POST", "/devices", null, json.encodeToString(DeviceBody.serializer(), body)) {
            json.decodeFromString(Registered.serializer(), it).deviceId
        }
        if (result !is WebResult.Ok) return result
        val sealed = vault()?.let { v -> runCatching { v.seal(secret) }.getOrNull() }?.let { SEALED + it } ?: (PLAIN + secret)
        store.put(KEY_DEVICE, json.encodeToString(StoredDevice.serializer(), StoredDevice(result.value, sealed)))
        store.put(KEY_SYNCED, encode(currentSections()))
        _registered.value = true
        return WebResult.Ok("${result.value}.$secret")
    }

    private suspend fun deviceKey(): String? {
        val raw = store.get(KEY_DEVICE) ?: return null
        val stored = runCatching { json.decodeFromString(StoredDevice.serializer(), raw) }.getOrNull() ?: return null
        val secret = when {
            stored.secret.startsWith(PLAIN) -> stored.secret.removePrefix(PLAIN)
            stored.secret.startsWith(SEALED) -> vault()?.let { v -> runCatching { v.open(stored.secret.removePrefix(SEALED)) }.getOrNull() }
            else -> null
        }
        // Unreadable — the vault changed under it: a new registration is all a lost key costs.
        if (secret == null) {
            store.remove(KEY_DEVICE)
            _registered.value = false
            return null
        }
        return "${stored.id}.$secret"
    }

    private suspend fun <T> call(method: String, path: String, key: String?, body: String?, read: (String) -> T): WebResult<T> {
        val response = try {
            network.request(
                NetworkRequest(
                    method = method,
                    url = api + path,
                    headers = if (key != null) mapOf("Authorization" to "Bearer $key") else emptyMap(),
                    body = body,
                    contentType = if (body != null) "application/json" else null,
                ),
            )
        } catch (cause: Exception) {
            return WebResult.Failed("network")
        }
        return when {
            response.status == 401 -> WebResult.Failed(UNAUTHORIZED)
            response.status == 410 -> WebResult.Gone
            !response.isSuccess -> WebResult.Failed("http-${response.status}")
            else -> runCatching { WebResult.Ok(read(response.body)) }.getOrElse { WebResult.Failed("unreadable") }
        }
    }

    private fun encode(sections: Set<String>): String = sections.sorted().joinToString(",")

    @Serializable
    private data class TokenBody(val token: String)

    @Serializable
    private data class ClaimBody(val token: String, val sections: List<String>)

    @Serializable
    private data class SessionsBody(val sessions: List<WebSession>)

    @Serializable
    private data class Registered(val deviceId: String)

    @Serializable
    private data class DeviceBody(
        val secretHash: String? = null,
        val platform: String? = null,
        val appVersion: String? = null,
        val sections: List<String>? = null,
    )

    /** `secret` is `sealed:` + the vault's output, or `plain:` + the secret where there is no vault. */
    @Serializable
    private data class StoredDevice(val id: String, val secret: String)

    private companion object {
        const val KEY_DEVICE = "unitool.web.device"
        const val KEY_SYNCED = "unitool.web.synced"
        const val PLAIN = "plain:"
        const val SEALED = "sealed:"
        const val UNAUTHORIZED = "unauthorized"
        val TOKEN = Regex("^wlt_[1-9A-HJ-NP-Za-km-z]{24}$")
        const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        /** `ds_` + 32 base58, the server's device secret; bytes ≥ 232 (= 58 × 4) are dropped, not wrapped. */
        fun newSecret(): String {
            val out = StringBuilder("ds_")
            while (out.length < 3 + 32) {
                for (byte in secureRandomBytes(64)) {
                    val value = byte.toInt() and 0xff
                    if (value >= 232) continue
                    out.append(BASE58[value % 58])
                    if (out.length == 3 + 32) break
                }
            }
            return out.toString()
        }

        fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
