# 04 — AppRuntime и сервисы

Пакет `cx.m42.superizer.runtime` в `core` — только интерфейсы. Реализации — в `host`
(`cx.m42.superizer.host`), подделки — в `testing`.

## Сам runtime — два уровня (D15)

```kotlin
/**
 * The app's whole view of the platform at the *app* level: created once per enable, scoped to one
 * app id (storage namespaced, logger tagged), alive until disable. This is what `setup(ctx)` sees
 * and what background work (push topics, listeners, periodic refresh) runs on.
 *
 * Apps hold this and nothing else of the host. Adminizer's rule, kept verbatim.
 */
interface AppRuntime {
    val appId: AppId
    val host: HostInfo

    // Core services: every host provides them, so they are fields.
    val storage: StorageService
    val network: NetworkService
    val logger: Logger
    val analytics: AnalyticsService
    val locale: LocaleService
    val haptics: HapticsService
    val push: PushService
    val auth: AuthService
    val apps: AppsService
    val events: SharedFlow<SuperizerEvent>
    /** Foreground/Background of the host process (D17). */
    val lifecycle: StateFlow<HostLifecycle>
    /** Wall clock (D30). Injected so snapshots, push timestamps and "older than a day" are testable. */
    val clock: Clock

    /**
     * Optional services by key (D19): camera, biometrics, share, whatever a host adds later without
     * breaking every AppRuntime implementation. Null when the host has none; apps that cannot live
     * without one list it in metadata.requires and never see null.
     */
    fun <T : Any> service(key: ServiceKey<T>): T?

    /** Cancelled by the handler on disable. */
    val scope: CoroutineScope
}

/**
 * What a live screen gets (D15): everything above plus navigation and a scope that dies with the
 * instance. Created by the handler per launch. `Content()` composables reach it through LocalAppRuntime.
 */
interface InstanceRuntime : AppRuntime {
    val navigation: NavigationService
    /** Instance-scoped: cancelled on dispose. Shadows AppRuntime.scope on purpose — screen work must not outlive the screen. */
    override val scope: CoroutineScope
}

/** Typed key for optional services. Declared next to the service interface; hosts register implementations under it. */
class ServiceKey<T : Any>(val name: String)   // e.g. `val CameraService.Key = ServiceKey<CameraService>("camera")`

enum class HostLifecycle { Foreground, Background }

fun interface Clock { fun now(): Long }   // epoch millis; SystemClock in host, FakeClock in testing

/** Read-only facts about whoever is hosting. */
data class HostInfo(
    val name: String,        // "Unitool"
    val version: String,     // BuildInfo.VERSION
    val build: String,       // BuildInfo.COMMIT (+ "*")
    val platform: Platform,  // Android / Desktop / Web
    /** Contract revision this host implements (D14). Bumped on any incompatible change to core; apps declare minHostContract. */
    val contractVersion: Int,
    /** Debug build of the host (D34): unlocks the seven-tap Service Menu gesture and verbose console logging. */
    val debug: Boolean,
    /** Optional services this host provides; the registry checks metadata.requires against it. */
    val services: Set<ServiceKey<*>>,
)

/** For composables inside AppInstance.Content: `LocalAppRuntime.current`. Provided by the container. */
val LocalAppRuntime = staticCompositionLocalOf<InstanceRuntime> { error("No AppRuntime: not inside an app container") }
```

**Ошибки в работе (D31).** Scope экземпляра создаётся с `SupervisorJob` +
`CoroutineExceptionHandler`, который переводит сессию в `Failed`, эмитит `Crashed(appId, cause)`
и показывает `HostErrorScreen` с «Назад» и «Перезапустить». Исключение внутри композиции
`Content()` перехватить нельзя — процесс упадёт; это известное ограничение Compose, и
`AppContractTest` композит `Content()` на дефолтном конфиге, чтобы поймать хотя бы это.

**Main-safety.** Все `suspend`-методы сервисов main-safe: реализация сама уходит на IO, приложение
зовёт их из `Dispatchers.Main` без `withContext`. Это правило контракта, чтобы подделки и мост
вели себя одинаково с настоящими сервисами.

## Сервисы

Минимальные интерфейсы. Каждый — своя `fun interface`/`interface`, чтобы подменялся по одному.

```kotlin
/** Key/value that survives restarts. Namespaced by app id in the host; the app sees plain keys. */
interface StorageService {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String)
    suspend fun remove(key: String)
    suspend fun keys(): Set<String>
}
/** Typed sugar over JSON strings, in core, so every app does not rewrite LocalStore. */
suspend inline fun <reified T> StorageService.getJson(key: String, json: Json = Json.Default): T?
suspend inline fun <reified T> StorageService.setJson(key: String, value: T, json: Json = Json.Default)

/**
 * Strings in, strings out; no auth injected (D32): an app that needs a token reads auth.session and
 * sets the header itself, so the host never guesses which of an app's hosts wants credentials.
 * Timeout 15 s, no retries — retry policy is the app's, it knows whether the call is idempotent.
 */
interface NetworkService {
    /** Best-effort connectivity (D32): ConnectivityManager / navigator.onLine / assumed true on desktop. Show "offline" from this, not from the first failure. */
    val online: StateFlow<Boolean>
    suspend fun request(request: NetworkRequest): NetworkResponse
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): NetworkResponse = request(NetworkRequest("GET", url, headers))
    suspend fun post(url: String, body: String, contentType: String = "application/json", headers: Map<String, String> = emptyMap()): NetworkResponse
}
data class NetworkRequest(val method: String, val url: String, val headers: Map<String, String> = emptyMap(), val body: String? = null, val contentType: String? = null)
data class NetworkResponse(val status: Int, val body: String, val headers: Map<String, List<String>>) {
    val isSuccess get() = status in 200..299
}
class NetworkException(val status: Int?, message: String, cause: Throwable? = null) : Exception(message, cause)
/** JSON helpers in core: `response.decode<T>()`; apps never import Ktor. */

/** Tagged `app:<id>` by the host (D33). Release: console gets warn/error only; LogBuffer (500 lines) keeps every level for the Service Menu. */
interface Logger {
    fun debug(message: String, throwable: Throwable? = null)
    fun info(message: String, throwable: Throwable? = null)
    fun warn(message: String, throwable: Throwable? = null)
    fun error(message: String, throwable: Throwable? = null)
}

/** Names are the app's own ("error"); the host prefixes the app id ("calculator.error") before it leaves the process (D33). */
interface AnalyticsService {
    fun event(name: String, params: Map<String, String> = emptyMap())
    fun screen(name: String)
}

/** Everything an app may ask the host to do with navigation — and nothing more. Only on InstanceRuntime. */
interface NavigationService {
    /**
     * Opens another app; the current one is closed first (D4), its snapshot kept (D16).
     * Failure is typed, not a boolean (D22). `AppResult.payload` is always null in MVP: the shape is
     * reserved for "open X, get a value back" so the signature never changes.
     */
    suspend fun openApp(id: AppId, config: AppConfig = AppConfig.Empty): Result<AppResult>
    /** Closes this app and returns to where it was opened from. */
    suspend fun close()
    fun openSettings()
    /** Opens an external URL in the platform's browser. */
    fun openUrl(url: String): Boolean
}
data class AppResult(val payload: JsonObject? = null)
sealed class NavigationError(message: String) : Exception(message) {
    class UnknownApp(val id: AppId) : NavigationError("unknown app $id")
    class Locked(val id: AppId) : NavigationError("hidden app $id is not unlocked")
    class Vetoed(val id: AppId) : NavigationError("current app refused to close")
}

interface LocaleService {
    /** BCP-47 tag in effect ("ru", "en"). Observable: switching in Settings repaints the app. */
    val langTag: StateFlow<String>
    /** Picks a table for the current language by primary subtag; see 03 for use. */
    fun <S> pick(tables: Map<String, S>, fallback: S): S
}

/** No-op when the host's haptics setting is off (D38); the app never checks the setting. */
interface HapticsService { fun tick() }

/**
 * What an app sees of push (13). Topics are the app's own names; the host prefixes them with the
 * app id and persists them, so one subscribe() in setup() survives token refreshes. Messages arrive
 * on the app-level runtime whether or not a screen is open (D15). No token, no notification drawing,
 * no navigation here — those are the host's (D27).
 */
interface PushService {
    /** Transport present and permission granted. False on desktop/wasm in MVP. */
    val enabled: StateFlow<Boolean>
    /** System dialog where one exists (Android 13+); no-op true elsewhere. */
    suspend fun requestPermission(): Boolean
    suspend fun subscribe(topic: String)
    suspend fun unsubscribe(topic: String)
    val subscriptions: StateFlow<Set<String>>
    /** Payloads addressed to this app id (schema 1, D24). Replay 0: state belongs in storage, not in the flow. */
    val messages: SharedFlow<PushMessage>
}
data class PushMessage(val topic: String?, val data: JsonObject, val link: String?, val receivedAt: Long)

/** Stubbed in MVP: always anonymous. The shape is fixed now so apps can be written against it. */
interface AuthService {
    val session: StateFlow<AuthSession?>
    suspend fun signIn(): AuthSession?
    suspend fun signOut()
}
data class AuthSession(val userId: String, val displayName: String?, val token: String?)

/** Read-only view of the registry. No register/unregister here — D6, §19. */
interface AppsService {
    fun list(): List<AppSummary>
    fun get(id: AppId): AppSummary?
}
data class AppSummary(val id: AppId, val version: String, val metadata: AppMetadata, val state: AppState, val unlocked: Boolean)
```

Что оставлено намеренно узким: `NetworkService` работает со строками, не с `KSerializer`. Это
нижняя граница; `decode<T>()` — extension в core. Заголовки/тело — простые типы, поэтому подделка
в `testing` — 20 строк.

**Правило ревью:** в интерфейсы сервисов не добавлять несериализуемые типы (`KSerializer`,
`Painter`, платформенные классы). Каждый вызов должен укладываться в JSON — это условие для
моста к web-расширениям (см. 10), и оно бесплатно, пока его соблюдают с первого дня.

**Правило расширения:** новый сервис не добавляется полем в `AppRuntime`. Он получает
`ServiceKey`, хост регистрирует реализацию, приложение берёт через `service(key)` или объявляет в
`requires`. Поля `AppRuntime` — замороженный набор MVP; менять его = поднимать `contractVersion`.

## Реализации в `host`

| Сервис | Реализация | Откуда берём |
|---|---|---|
| `StorageService` | `PrefsStorageService(appId)` — префикс `app.<id>.` поверх `PrefsStorage` (expect/actual: SharedPreferences / properties-файл / localStorage) | текущий `data/PrefsStorage.*` переезжает в `host` |
| `NetworkService` | `KtorNetworkService(HttpClient)` — `expectSuccess=false`, таймауты, один клиент на хост | `AgentizApi` (agentiz) как образец; движки: okhttp/cio/js по таргету |
| `Logger` | `ConsoleLogger(tag = "app:<id>")` + кольцевой буфер `LogBuffer` для Service Menu | новое |
| `AnalyticsService` | `LoggingAnalytics` (пишет в Logger и в буфер, который читает Service Menu/Test App) | новое; Firebase — позже, за этим же интерфейсом |
| `NavigationService` | `ShellNavigation(handler, shell)` — только в `InstanceRuntime` | новое (см. 05) |
| `lifecycle` | `PlatformLifecycle` expect/actual: `ProcessLifecycleOwner` / фокус окна / `visibilitychange` | новое |
| `PushRouter`, `PendingRoute`, `TopicStore`, `DeviceRegistrar` | хостовые, приложению не видны | 13 |
| `service(key)` | `ServiceRegistry` в `host`: `Map<ServiceKey<*>, Any>`, заполняется в `Superizer.build { service(Key, impl) }` | новое; в MVP пусто |
| `LocaleService` | `AppLocaleService` поверх существующего `AppLocale` | `i18n/AppLocale.kt` переезжает в `host`, обобщается (`Lang` → тег) |
| `HapticsService` | `PlatformHaptics(hostSettings)` поверх `hapticActionComplete()`; no-op при выключенной настройке (D38) | `platform/Haptics.*` переезжает |
| `clock` | `SystemClock` (`currentTimeMillis` / `Date.now()`) | D30 |
| `network.online` | expect/actual: `ConnectivityManager` / `navigator.onLine` + события / desktop всегда true | D32 |
| `PushService` | `RoutedPush(appId, router, topicStore)` поверх `expect PushTransport` (no-op actual на всех таргетах в MVP) | 13; FCM `actual` — шаг 15, по образцу `agentiz/push` |
| `AuthService` | `AnonymousAuth` | позже |
| `AppsService` | `RegistryView(registry, unlockStore)` | новое |

Сборка — `HostRuntimeFactory`:

```kotlin
class DefaultHostRuntimeFactory(
    private val hostInfo: HostInfo,
    private val storage: PrefsStorage,           // platform actual, initialised by the entrypoint
    private val http: HttpClient,
    private val registryView: AppsService,
    private val navigation: (AppId) -> NavigationService,
    private val events: SharedFlow<SuperizerEvent>,
    private val logBuffer: LogBuffer,
    private val services: ServiceRegistry,
    private val lifecycle: StateFlow<HostLifecycle>,
) : HostRuntimeFactory {
    override fun createApp(appId: AppId, scope: CoroutineScope): AppRuntime = DefaultAppRuntime(...)
    override fun createInstance(app: AppRuntime, scope: CoroutineScope): InstanceRuntime = DefaultInstanceRuntime(app, navigation(app.appId), scope)
}
```

`HostRuntimeFactory` — интерфейс в `core`; хост может дать свой (standalone-хост, тесты).
Оба уровня создаёт фабрика, чтобы standalone-хост и мост (10) подменяли их вместе.

## `testing`: FakeAppRuntime

```kotlin
class FakeAppRuntime(appId: AppId = AppId("test"), config: …) : InstanceRuntime {
    val storage = InMemoryStorage()
    val push = FakePush()                // .deliver(topic, data, link); .subscriptions
    val network = FakeNetwork()          // .respond("https://…") { NetworkResponse(200, "...") }
    val analytics = RecordingAnalytics() // .events: List<Pair<String, Map>>
    val navigation = RecordingNavigation()
    val locale = FakeLocale("ru")
    val lifecycle = MutableStateFlow(HostLifecycle.Foreground)   // тест переключает и проверяет onBackground
    val clock = FakeClock(start = 1_700_000_000_000)             // .advance(hours = 25) → снимок просрочен
    val online = MutableStateFlow(true)                          // конвертер показывает «офлайн» до запроса
    val services = mutableMapOf<ServiceKey<*>, Any>()            // .provide(CameraService.Key, FakeCamera())
    …
}
```

Плюс `runAppTest(app, config) { … }` — поднимает `FakeAppRuntime`, вызывает `launch`,
`onLaunch()`, композит `Content()` через `runComposeUiTest` с `LocalAppRuntime`. Это то, что
тест приложения импортирует вместо всего хоста — аналог того, как сегодня `RussianUiTest` пинит
язык и память.

И `AppContractTest` (D21) — абстрактный класс с готовыми тестами, который каждое приложение
наследует одной строкой: decode дефолта и битого payload, `launch` → `onLaunch` → `Content` без
исключений, `saveState` → `restore` round-trip, `onCloseRequested` по умолчанию true, повторный
`dispose` безопасен, подделка хоста предоставляет **ровно** `manifest.requires` — использование
необъявленного ключа через `require()` падает здесь (D47), `setup` регистрирует только объявленные
`deepLinks`, корневой `testTag` найден (D39),
старые фикстуры `config-v1.json`/`state-v1.json` читаются текущей спекой, `onLaunch` ≤ 200 мс
виртуального времени и без сетевых вызовов (12 §4.7). Один файл на приложение плюс две
JSON-фикстуры, ноль своего кода.

## Что приложение НЕ получает

- `AppRegistry`, `AppHandler`, `Superizer` — их нет в `AppRuntime`.
- `ActivationSource` — источник конфига остаётся в `host`.
- Другие `AppRuntime` — runtime выдаётся на app id и хранится только у приложения/экземпляра.
- `NavigationService` на уровне приложения — открывать экраны из фонового listener-а нельзя; для
  этого есть deep link через `ctx.deepLink` и push с маршрутом, который решает хост.
