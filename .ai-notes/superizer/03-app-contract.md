# 03 — Контракт приложения (`superizer:core`)

Пакет `cx.m42.superizer.app`. Всё ниже — эскиз публичного API, по которому пишется код.
Стиль комментариев — как в текущем Unitool (объяснять «почему», не «что»).

## Идентичность и метаданные

```kotlin
/** Stable, URL-safe identifier: what QR payloads, promo codes and deep links name. */
@JvmInline
value class AppId(val value: String) {
    init { require(value.matches(Regex("[a-z0-9][a-z0-9-]*"))) { "AppId must be kebab-case: $value" } }
    override fun toString() = value
}

/** A string that depends on the language. Apps ship their own tables; the host only needs the tag. */
fun interface Localized {
    fun resolve(langTag: String): String
}

/** `localized("en" to "Calculator", "ru" to "Калькулятор")` — first entry is the fallback. */
fun localized(vararg entries: Pair<String, String>): Localized

/** How All Apps draws the tile. Core describes it; `ui` draws it — core has no compose.ui dependency. */
sealed interface AppIcon {
    data class Letter(val char: Char) : AppIcon
    /** A glyph from the host's hand-drawn pack ("calculator", "currency"). Unknown names fall back to Letter. */
    data class Named(val name: String) : AppIcon
    /**
     * The app's own icon as an SVG path (`d` attribute, 24×24 viewBox), drawn by `ui` via PathParser (D29).
     * A string, not a Painter: core has no compose.ui, and a manifest (10) can carry it unchanged.
     */
    data class Path(val svgPath: String) : AppIcon
}

/** What the host shows about an app before launching it. */
data class AppMetadata(
    val title: Localized,
    val description: Localized? = null,
    val icon: AppIcon = AppIcon.Letter('?'),
    /** Hidden apps are not listed on All Apps until an activation unlocks them. See 06. */
    val hidden: Boolean = false,
    /** Free-form grouping for All Apps ("Tools", "Finance"). Null → ungrouped. */
    val category: String? = null,
)

/**
 * Everything static the host may want to know before running a line of the app (D47). One
 * serializable object: the registry validates it, the Service Menu shows it, an extension
 * manifest (10) is this serialized. Behaviour stays in setup(); declarations live here.
 */
@Serializable
data class AppManifest(
    val id: AppId,
    val version: String,
    /** Oldest host contract this app compiles against (D14). Newer than the host → RegistrationRejected. */
    val minHostContract: Int = 1,
    val metadata: AppMetadata,
    /**
     * Optional services this app cannot work without (D19). Checked at registration against
     * HostInfo.services; a host that lacks one rejects the app instead of letting it crash on first use.
     */
    val requires: Set<ServiceKey<*>> = emptySet(),
    /** Deep-link paths under `unitool://app/<id>/…` this app serves. setup() must register a handler for each — and nothing else. */
    val deepLinks: Set<String> = emptySet(),
    /** Push topics it subscribes to. Informational in Tier 0 (the Service Menu shows them); the host's TopicStore rejects a subscribe() to an undeclared topic. */
    val pushTopics: Set<String> = emptySet(),
    /** Hosts it talks to. Informational in Tier 0; the allowlist of a Tier 2 bridge (10). */
    val networkHosts: Set<String> = emptySet(),
)
```

`ServiceKey` сериализуется по `name`; при чтении манифеста из данных (10) ключ восстанавливается
из таблицы известных хосту ключей, неизвестный → `RegistrationRejected(UnknownService)`.

## Конфигурация

```kotlin
/** Raw config as it travels: from a QR, a promo code, remote config, the Service Menu. */
@JvmInline
value class AppConfig(val json: JsonObject) {
    companion object { val Empty = AppConfig(JsonObject(emptyMap())) }
}

/**
 * How an app reads its own config. Typed inside the app, JSON outside it, so the host never learns
 * the shape and the app never learns the source.
 */
class AppConfigSpec<C : Any>(
    val serializer: KSerializer<C>,
    val default: C,
    /** Bumped when the shape changes incompatibly; `migrate` brings older payloads up to date. */
    val schemaVersion: Int = 1,
    /** Whether a broken payload may still launch the app on `default`. False for apps where a wrong config is worse than no launch. */
    val fallbackToDefault: Boolean = true,
    private val migrate: (fromVersion: Int, raw: JsonObject) -> JsonObject = { _, raw -> raw },
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    /**
     * Missing keys keep the default. A broken payload is a failure, not a silent default (D18): the
     * handler turns it into a `ConfigRejected` event that the Service Menu shows, then applies
     * `fallbackToDefault`. Silently launching a plain calculator from a "scientific" QR is the bug this prevents.
     */
    fun decode(config: AppConfig): Result<C> = runCatching {
        val from = config.json["schemaVersion"]?.jsonPrimitive?.intOrNull ?: schemaVersion
        json.decodeFromJsonElement(serializer, mergeWithDefault(migrate(from, config.json)))
    }
    fun encode(value: C): AppConfig = AppConfig(json.encodeToJsonElement(serializer, value).jsonObject + ("schemaVersion" to schemaVersion))
    /** For apps that genuinely have no config. */
    companion object { val None = AppConfigSpec(Unit.serializer(), Unit) }
}
```

`mergeWithDefault` — поверхностное слияние `default`-JSON и входящего объекта: так
`{"mode":"scientific"}` не сбрасывает другие поля дефолта. Требует `@Serializable` у `C` с
default-значениями — это и есть «схема» приложения. `schemaVersion` в payload необязателен:
отсутствие = текущая версия. То же поле и тот же `migrate`-приём — для состояния из `saveState`.

## Определение приложения

```kotlin
/**
 * The registered thing: one per app id, created by the host at startup, alive for the process.
 * Holds no screen state — that belongs to the AppInstance a launch creates.
 *
 * Mirrors Adminizer's AbstractAdminizerApp: `id`/`version`/`setup(ctx)`; adds `launch` because
 * here an app is something you open, not a set of routes.
 */
abstract class SuperizerApp<C : Any> {
    /** The static half of the app (D47). Validated before setup(); shown before launch. */
    abstract val manifest: AppManifest
    abstract val configSpec: AppConfigSpec<C>

    // Convenience, so call sites read as before.
    val id: AppId get() = manifest.id
    val version: String get() = manifest.version
    val metadata: AppMetadata get() = manifest.metadata

    /**
     * Registration-time hook, once per enable. Wires the *behaviour* behind the manifest: a
     * handler per declared deep link, a settings section, event listeners. Everything registered
     * here is undone by the handler on disable — never by the app. Registering a deep link or a
     * topic the manifest does not declare is an error the registry reports (D47).
     * `ctx.runtime` is the app-level runtime (D15): alive until disable, for anything that must
     * work while no screen is open (push topics, background refresh).
     */
    open fun setup(ctx: AppSetupContext) {}

    /**
     * Creates the live instance. Called on every open with an instance runtime (app-level services
     * plus navigation and a screen-scoped coroutine scope) and the config already typed.
     * Must be cheap: heavy work belongs in AppInstance.onLaunch.
     */
    abstract fun launch(runtime: InstanceRuntime, config: C): AppInstance
}
```

Почему `launch(runtime, config)` а не `initialize(runtime)` + `configure(config)` + `launch()`
как в спецификации: три раздельных шага с `lateinit`-полями — это лишние состояния, которые
компилятор не проверит. Один вызов с обоими аргументами даёт то же самое без «а точно ли уже
initialize?». Сами шаги спецификации остаются — как **порядок действий Handler-а** (см. 05), а
не как три метода на приложении. Для отладки/Test App шаги видны через `SuperizerEvent`.

## Живой экземпляр

```kotlin
/**
 * One open app: its state, its screen, its end. Created by SuperizerApp.launch, driven by the
 * handler, rendered by the host container. There is at most one per app id at a time (D4).
 */
abstract class AppInstance {
    /** Called once, before the first frame. Start flows, load what you need from runtime.storage here. */
    open suspend fun onLaunch() {}

    /**
     * Snapshot of what the screen would lose if the process died now (D16): the calculator's entry,
     * the converter's amount. Null → nothing worth keeping. The handler stores it on close and on
     * background and hands it back through `restore` on the next launch after a process death or
     * when the app is re-entered from another app. Persistent data still goes to runtime.storage.
     */
    open fun saveState(): JsonObject? = null

    /** Called after onLaunch and before the first frame when a snapshot exists. Tolerate old shapes: it may predate an update. */
    open fun restore(state: JsonObject) {}

    /** The host went to background / came back (D17). Stop polling, pause timers; resume them. Also observable as runtime.lifecycle. */
    open fun onBackground() {}
    open fun onForeground() {}

    /** The whole of the app's UI. The host draws the frame (top bar, drawer); this is what goes inside. */
    @Composable
    abstract fun Content()

    /**
     * What the host's top bar should read for this app right now. Observable so a multi-screen
     * app can retitle the bar as it navigates inside itself.
     */
    open val chrome: StateFlow<AppChrome> get() = MutableStateFlow(AppChrome())

    /**
     * The host is about to close this app (user pressed back at the root, another app was opened,
     * the process is going away). Return false to veto — a form with unsaved edits — and the host
     * asks the user. Default: no objection.
     */
    open suspend fun onCloseRequested(): Boolean = true

    /** Save what must survive. After this the instance is never composed again. */
    open fun onClose() {}

    /** Release everything. runtime.scope is cancelled by the handler right after. */
    open fun dispose() {}
}

/** What an app tells the host frame about itself. */
data class AppChrome(
    /** Null → the host uses metadata.title. */
    val title: String? = null,
    val subtitle: String? = null,
    /** True when the app has somewhere to go back to inside itself; the host then shows ← and routes back to the app. */
    val canGoBack: Boolean = false,
)
```

`Content()` рисуется **внутри** `AppScaffold` хоста. Приложение не ставит свой top bar — это
правило §14 спецификации (Super App owns global navigation, App Container). Если приложению
нужен свой заголовок — оно меняет `chrome`. Если ему нужно перехватить back — оно ставит
`SystemBackHandler` (из `ui`) внутри `Content()`; вложенность даёт приоритет ему над shell-ом.

## Контекст регистрации

```kotlin
/**
 * Given to SuperizerApp.setup. Everything declared through it is owned by the handler and torn
 * down in reverse order on disable — the Adminizer disposers pattern.
 */
interface AppSetupContext {
    /** App-level runtime (D15): storage, network, push, events, a scope that lives until disable. No navigation — there is no screen yet. */
    val runtime: AppRuntime

    /** A block on the host's Settings screen, under the app's own title. */
    fun settingsSection(section: @Composable (runtime: AppRuntime) -> Unit)

    /** `unitool://app/<id>/<path>` → opens this app with the given config. */
    fun deepLink(path: String, toConfig: (params: Map<String, String>) -> AppConfig)

    /** Subscribe to host events; runs in `runtime.scope` of the app level, whether or not a screen is open. */
    fun listener(handler: suspend (event: SuperizerEvent) -> Unit)
}
```

Хелпер для объявленных ключей: `fun <T : Any> AppRuntime.require(key: ServiceKey<T>): T` —
возвращает сервис, если ключ есть в `manifest.requires` (реестр это уже гарантировал), иначе
бросает `IllegalStateException("$key is not declared in manifest.requires of $appId")`. Это
превращает «забыл объявить» в падение первого же теста, а не в null где-то в рантайме.
`service(key)` остаётся для действительно необязательного.

Минимальный набор для MVP. `menuEntry` не нужен: список приложений в drawer/All Apps строится
хостом из registry, как `menu` сейчас строится в `App.kt` из destinations. Что регистрировать
дальше (виджеты на Home, share-targets, push-topics) — появится вместе с потребностью, и это
единственное место, куда такие вещи добавляются.

## Жизненный цикл (как его видит приложение)

```
register(app)             хост, при старте; манифест: minHostContract, requires, ключи известны (D14, D19, D47)
   │
   ▼
setup(ctx)                один раз; ctx.runtime — app-level runtime (D15);
                          после — сверка: ctx.deepLink ⊆ manifest.deepLinks, иначе SetupMismatch
   │
   ▼  … пользователь открыл приложение / сработала активация …
configSpec.decode(config) Result; ошибка → ConfigRejected → default или LaunchFailed (D18)
   │
   ▼
launch(instanceRuntime, config) → AppInstance
   │
   ▼
instance.onLaunch()       suspend; host показывает пустой контейнер до завершения
   │
   ▼
instance.restore(state)   только если есть снимок (D16)
   │
   ▼
instance.Content()        Active; chrome наблюдается; back → приложение, затем shell
   │                      onBackground()/onForeground() по lifecycle хоста (D17); в фоне — saveState()
   ▼
onCloseRequested()        veto-point
   │
   ▼
saveState() → onClose() → dispose()     instanceRuntime.scope.cancel(); контейнер пуст
```

Состояния в терминах спецификации (§10): Registered → (setup) Enabled → Created → Configured →
Launched → Active → Closed → Disposed. Handler публикует их как `AppState` (см. 05); приложению
хватает хуков.

## i18n внутри приложения (D11)

Повторяем схему хоста, но локально:

```kotlin
interface CalculatorStrings { val title: String; val error: String; val backspace: String; val decimalSeparator: String }
internal object CalculatorStringsEn : CalculatorStrings { … }
internal object CalculatorStringsRu : CalculatorStrings { … }

@Composable
internal fun calculatorStrings(): CalculatorStrings {
    val lang by LocalAppRuntime.current.locale.langTag.collectAsState()
    return if (lang.startsWith("ru")) CalculatorStringsRu else CalculatorStringsEn
}
```

Общий хелпер в `core`: `fun <S> LocaleService.pick(tables: Map<String, S>, fallback: S): S`, чтобы
не писать `startsWith` в каждом приложении. Заголовок для All Apps — через `metadata.title:
Localized`, т.к. он нужен хосту до того, как приложение запущено.

## Правила изоляции (§19) — как они обеспечиваются

| Правило | Механизм |
|---|---|
| Не обращаться к другому приложению напрямую | `apps/*` не зависят друг от друга (Gradle); только `runtime.navigation.openApp(AppId("…"), config)` |
| Не создавать платформенные сервисы | `host` недоступен приложениям как зависимость; Ktor/prefs там |
| Не менять Registry | `AppRuntime.apps` — только чтение (`list()`, `get()`); мутирующих методов нет |
| Не управлять глобальной навигацией | `NavigationService` есть только у `InstanceRuntime` и предлагает `openApp`, `close`, `openSettings`; никакого доступа к shell-стеку |
| Не пользоваться сервисом, которого у хоста нет | `metadata.requires` сверяется при регистрации; `runtime.service(key)` для необязательных возвращает null |
| Не знать способ активации | В `launch` приходит только `config`; `ActivationSource` остаётся внутри `host` |
| Не зависеть от деталей Super App | `composeApp` не публикует ничего, от чего можно зависеть |

## Как выглядит приложение целиком

```
apps/calculator/src/commonMain/kotlin/cx/m42/apps/calculator/
├── CalculatorApp.kt        : SuperizerApp<CalculatorConfig>  — id, metadata, configSpec, launch
├── CalculatorConfig.kt     @Serializable data class CalculatorConfig(val mode: String = "standard")
├── CalculatorInstance.kt   : AppInstance — state holder, Content(), saveState/restore (entry, accumulator)
├── Calculator.kt           чистая логика (переносится как есть)
├── CalculatorScreen.kt     композабл экрана (без AppScaffold — его даёт хост)
└── i18n/CalculatorStrings.kt
apps/calculator/src/desktopTest/kotlin/…/CalculatorTest.kt          логика, без UI
apps/calculator/src/desktopTest/kotlin/…/CalculatorInstanceTest.kt  Content() c FakeAppRuntime
apps/calculator/src/desktopTest/kotlin/…/CalculatorContractTest.kt  : AppContractTest(CalculatorApp()) — общий TCK из testing (D21)
```

Точка входа модуля — один публичный класс `CalculatorApp`; всё остальное `internal`. Манифест
пишется рядом, как значение:

```kotlin
class CalculatorApp : SuperizerApp<CalculatorConfig>() {
    override val manifest = AppManifest(
        id = AppId("calculator"), version = "1.0.0",
        metadata = AppMetadata(title = localized("en" to "Calculator", "ru" to "Калькулятор"), icon = AppIcon.Named("calculator"), category = "Tools"),
    )
    override val configSpec = AppConfigSpec(CalculatorConfig.serializer(), CalculatorConfig())
    override fun launch(runtime: InstanceRuntime, config: CalculatorConfig) = CalculatorInstance(runtime, config)
}
```
Это аналог `index` из спецификации §4.

## Standalone-режим (§16–18)

Ничего в приложении не меняется. В `ui` есть:

```kotlin
/** A host that runs exactly one app, full-screen, with the host's frame around it. */
@Composable
fun SuperizerStandalone(app: SuperizerApp<*>, runtimeFactory: HostRuntimeFactory, config: AppConfig = AppConfig.Empty)
```

Standalone-приложение «Калькулятор» = новый `composeApp`-подобный модуль с `main.kt`, вызывающим
`SuperizerStandalone(CalculatorApp(), DefaultHostRuntime(...))`. Добавим как sample-модуль
`samples/standalone-calculator` после MVP — это лучшее доказательство §24, но оно не в чек-листе.
