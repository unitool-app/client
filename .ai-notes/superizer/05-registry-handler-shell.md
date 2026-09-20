# 05 — Registry, Handler, Shell

## AppRegistry (`core`)

```kotlin
/**
 * Every app the host knows about, keyed by id. Usually filled at startup, but late registration is
 * allowed (a downloaded extension catalog, see 10) — `apps` is observable for that reason.
 * Not exposed to apps (they get the read-only AppsService).
 */
class AppRegistry(private val host: HostInfo) {
    val apps: StateFlow<List<SuperizerApp<*>>>
    /**
     * Throws on duplicate id — Adminizer does the same. Returns false (and emits RegistrationRejected
     * with a reason) when the manifest does not fit this host (D47): minHostContract > contractVersion,
     * requires ⊄ host.services, a ServiceKey the host has never heard of, a malformed deep-link path.
     * An app compiled against a newer contract is a deployment fact to show in the Service Menu, not a crash.
     */
    fun register(app: SuperizerApp<*>): Boolean
    /** Manifests of everything ever offered, accepted or not — what the Service Menu lists. */
    val manifests: StateFlow<List<Pair<AppManifest, RegistrationOutcome>>>
    fun get(id: AppId): SuperizerApp<*>?
    fun all(): List<SuperizerApp<*>>
    fun visible(unlocked: Set<AppId>): List<SuperizerApp<*>> = all().filter { !it.metadata.hidden || it.id in unlocked }
}
```

Регистрация — явная, в хосте (D1):

```kotlin
// composeApp/src/commonMain/kotlin/cx/m42/unitool/UnitoolHost.kt
fun buildSuperizer(platform: PlatformServices): Superizer = Superizer.build {
    host(HostInfo("Unitool", BuildInfo.VERSION, BuildInfo.label, platform.kind, SuperizerContract.VERSION, services = emptySet()))
    runtimeFactory(DefaultHostRuntimeFactory(...))
    // service(CameraService.Key, AndroidCamera())   ← так хост добавляет необязательный сервис (D19); в MVP ни одного
    register(CalculatorApp())
    register(CurrencyConverterApp())
    register(TestApp())
    promoCodes(LocalPromoCodes(mapOf("TEST-2026" to Activation(AppId("test-app"), config = …))))
}
```

`Superizer` — фасад: registry + handler + activation + events + unlock store. Один на процесс,
живёт в `remember` корневого композабла (как сейчас `AppLocale.start()`).

## AppHandler (`core`)

Конечный автомат с одним текущим сеансом (D4).

```kotlin
enum class AppState { Registered, Enabled, Creating, Launching, Active, Closing, Disposed, Failed }

class AppSession internal constructor(
    val app: SuperizerApp<*>,
    val config: AppConfig,
    val instance: AppInstance,
    val runtime: InstanceRuntime,
    val state: StateFlow<AppState>,
)

/** What survives a process death (D16): enough to relaunch and hand the instance its snapshot. Host storage, key "superizer.session". */
@Serializable data class SessionSnapshot(val appId: AppId, val config: AppConfig, val state: JsonObject?, val savedAt: Long)

class AppHandler(
    private val registry: AppRegistry,
    private val runtimeFactory: HostRuntimeFactory,
    private val events: MutableSharedFlow<SuperizerEvent>,
    private val hostLifecycle: StateFlow<HostLifecycle>,
    private val snapshots: SnapshotStore,
) {
    /** The open app, or null on the host's own screens. Observed by the container. */
    val current: StateFlow<AppSession?>
    val states: StateFlow<Map<AppId, AppState>>

    /**
     * setup(ctx) for every registered app with an app-level runtime (D15), collecting disposers. Once, at startup.
     * After each setup: what ctx registered is checked against the manifest (D47) — a deep link or topic not
     * declared → SetupMismatch, app disabled. Declared but not registered → warning only (a path may be config-driven).
     */
    suspend fun enableAll()

    /** The full sequence of §9. Closes the current session first. Never throws: failures become Failed + an event. */
    suspend fun launch(id: AppId, config: AppConfig = AppConfig.Empty, restoreFrom: JsonObject? = null): Result<AppSession>

    /** Asks the instance, then saveState → onClose → dispose → scope.cancel. */
    suspend fun close(force: Boolean = false): Boolean

    /** After a process death: relaunch the last session from its snapshot, if any. Called by the shell once at startup. */
    suspend fun restoreLastSession(): Result<AppSession>?
}
```

Последовательность `launch` — ровно как в спецификации, каждый шаг публикует событие:

```
registry.get(id) ?: Failed(UnknownApp)                     → SuperizerEvent.LaunchFailed
if (current != null) close()                               → AppClosed (снимок текущего сохранён)
scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
runtime = runtimeFactory.createInstance(appRuntime(id), scope)   → RuntimeCreated
typed = app.configSpec.decode(config)                      → Configured(config)
   .onFailure → ConfigRejected(reason); if (!spec.fallbackToDefault) Failed(BadConfig) else typed = spec.default
instance = app.launch(runtime, typed)                      → Created
state = Launching; instance.onLaunch()                     → Launched
restoreFrom?.let { instance.restore(it) }                  → Restored
state = Active; current = session                          → Active
```

Ошибка на любом шаге: `state = Failed`, `scope.cancel()`, `current = null`, событие с причиной.
Контейнер показывает экран ошибки хоста с кнопкой «назад» — приложение не участвует.

**Lifecycle хоста (D17).** Handler подписан на `hostLifecycle`: `Background` → `instance.onBackground()`
и `snapshots.save(SessionSnapshot(..., instance.saveState()))`; `Foreground` → `instance.onForeground()`.
Снимок пишется именно в фоне, потому что на Android после этого процесс может не вернуться.
`close()` удаляет снимок, если закрытие штатное, и оставляет, если приложение закрыто ради `openApp`
другого (так «назад» в будущем стеке вернёт состояние). `restoreLastSession` при старте:
снимок есть и приложение зарегистрировано → `launch(appId, config, restoreFrom = state)`; иначе Home.
Снимок старше суток игнорируется (`savedAt`) — пользователь не ждёт вчерашний экран.

`enableAll` вызывает `app.setup(ctx)` с `RuntimeAppSetupContext` — копия идеи Adminizer:
каждый `ctx.*` кладёт disposer в список; `disable(id)` (только из Service Menu) прогоняет их в
обратном порядке. В MVP disable нужен лишь чтобы показать, что механизм работает.

## События (`core`)

```kotlin
sealed interface SuperizerEvent {
    val appId: AppId?
    data class Registered(override val appId: AppId, val version: String) : SuperizerEvent
    /** Manifest does not fit this host (D47): contract too new, required service missing/unknown, bad path. Service Menu lists these. */
    data class RegistrationRejected(override val appId: AppId, val reason: String) : SuperizerEvent
    /** setup() registered something the manifest does not declare (D47). App stays disabled. */
    data class SetupMismatch(override val appId: AppId, val what: String) : SuperizerEvent
    data class Enabled(override val appId: AppId) : SuperizerEvent
    data class RuntimeCreated(override val appId: AppId) : SuperizerEvent
    data class Configured(override val appId: AppId, val config: AppConfig) : SuperizerEvent
    /** Payload did not decode (D18). Launch continues on default unless the spec forbids it. */
    data class ConfigRejected(override val appId: AppId, val config: AppConfig, val reason: String) : SuperizerEvent
    data class Created(override val appId: AppId) : SuperizerEvent
    data class Launched(override val appId: AppId) : SuperizerEvent
    data class Restored(override val appId: AppId) : SuperizerEvent
    data class Background(override val appId: AppId?) : SuperizerEvent
    data class Foreground(override val appId: AppId?) : SuperizerEvent
    data class Active(override val appId: AppId) : SuperizerEvent
    data class Closed(override val appId: AppId) : SuperizerEvent
    data class Disposed(override val appId: AppId) : SuperizerEvent
    data class LaunchFailed(override val appId: AppId?, val reason: String, val cause: Throwable?) : SuperizerEvent
    data class Activated(override val appId: AppId, val source: String) : SuperizerEvent   // source: "qr" | "promo" | "deeplink" | "push" | "service-menu"
    data class PushReceived(override val appId: AppId, val topic: String?) : SuperizerEvent          // 13
    data class PushDropped(override val appId: AppId?, val reason: String) : SuperizerEvent          // UnsupportedSchema | UnknownApp | Locked | Disabled | LinkMismatch
    data class DeepLinkUnmatched(override val appId: AppId, val path: String) : SuperizerEvent
    data class RouteDiscarded(override val appId: AppId, val link: String) : SuperizerEvent          // veto текущего, пользователь остался
    data class Crashed(override val appId: AppId, val cause: Throwable) : SuperizerEvent             // D31: исключение в scope экземпляра
    data class Reset(override val appId: AppId) : SuperizerEvent                                     // D35: данные приложения стёрты
    data class Unlocked(override val appId: AppId) : SuperizerEvent
}
```

Test App подписывается на `runtime.events` и показывает ленту — это и есть проверка lifecycle
из §22. `source` в `Activated` виден только хосту и Service Menu; в приложение он не попадает.

**Replay.** `events` — `MutableSharedFlow(replay = 0, extraBufferCapacity = 64)`. Listener-ы из
`setup` подписываются в `enableAll` до первого `launch`, поэтому ничего не теряют; текущее
состояние всегда доступно через `states`/`current` как `StateFlow`, лента — не источник истины.

## Shell (`ui`) — то, что сейчас `App.kt`

Текущая схема (sealed `Destination` + `AnimatedContent` + меню в drawer) сохраняется, но
destinations становятся хостовыми, а приложения — одним destination:

```kotlin
sealed interface ShellDestination {
    data object Home : ShellDestination                                  // All Apps
    data class App(val id: AppId) : ShellDestination                     // контейнер с current session
    data class Settings(val from: ShellDestination) : ShellDestination
    data class Activate(val from: ShellDestination) : ShellDestination   // ввод QR-текста / промокода
    data object ServiceMenu : ShellDestination
}

@Composable
fun SuperizerShell(superizer: Superizer, modifier: Modifier = Modifier)
```

- **Drawer-меню** строится из `registry.visible(unlocked)`: по `MenuEntry` на приложение +
  «Все приложения». Текущее приложение — disabled, как сейчас. Футер: шестерёнка (Settings) и
  build stamp (из `HostInfo`), как сейчас. Пункт «Активировать» — в футере рядом с шестерёнкой.
- **AppContainer** — `AnimatedContent`-ветка для `ShellDestination.App`: `AppScaffold(title =
  chrome.title ?: metadata.title, subtitle = chrome.subtitle, onBack = …)` и внутри
  `CompositionLocalProvider(LocalAppRuntime provides session.runtime) { Box(Modifier.testTag("<id>:root")) { session.instance.Content() } }`
  (тег ставит контейнер, D39).
  Состояния `Launching` → пустой контейнер с заголовком (без спиннера: `onLaunch` должен быть
  быстрым), `Failed` → `HostErrorScreen`.
- **Back**: `SystemBackHandler` shell-а активен на любом destination кроме Home. Порядок
  вложенности: drawer (в `AppScaffold`) → приложение (в `Content()`) → shell. При back из
  `App` shell вызывает `handler.close()`; veto из `onCloseRequested` показывает диалог хоста.
- **All Apps (Home)** — сетка плиток: `AppIcon`, `metadata.title`, `category`. Скрытые не
  показываются. Пустая категория → без заголовка. Тап → `handler.launch(id)`.
- **Settings** — существующий экран (язык, вибрация, о приложении) + секции, зарегистрированные
  через `ctx.settingsSection` (заголовок = `metadata.title`). Семь тапов по «Сборка …» открывают
  Service Menu **только при `HostInfo.debug`** (D34); в release единственный вход — промокод из
  `Superizer.build { serviceCode("SERVICE") }`. Иначе скрытость приложений — фикция.
- **Service Menu** — таблица всех приложений (в т.ч. скрытых и отклонённых при регистрации, с
  причиной): id, version, `minHostContract`, state, unlocked; последний `ConfigRejected` по приложению;
  раскрывающийся манифест (D47): `requires` с отметкой есть/нет у хоста, `deepLinks`, `pushTopics`,
  `networkHosts` — видно **до** запуска, что приложение просит;
  кнопки «Запустить», «Запустить с конфигом…» (JSON-поле), «Разблокировать/скрыть»,
  «Disable/Enable», «Сбросить» (D35: `Superizer.reset(id)` — storage-namespace, темы пушей, снимок,
  unlock; с подтверждением); поле ввода промокода; поле вставки QR-payload; «Simulate push» (13);
  лента `SuperizerEvent`; лог из `LogBuffer`. Это и есть аналог `ModuleManagerApp`.
- **Ширина** (D36): содержимое каждого destination — `Box(Modifier.widthIn(max = 480.dp).align(Center))`
  в `SuperizerShell`; на телефоне незаметно, на desktop/в браузере — колонка по центру. Приложение
  ничего не делает.
- **Activate** — два поля: промокод и текст QR (то, что покажет камера позже). Результат
  активации → `handler.launch(...)`; `HostCommand(OpenServiceMenu)` → `shell.go(ServiceMenu)` (D20).
- **Старт shell-а**: `PendingRoute.consume()` → `activation.fromDeepLink` → `App(id)`; иначе
  `handler.restoreLastSession()` → `App(id)`; иначе Home. Ссылка во время работы — тот же
  `LaunchedEffect` на `PendingRoute.route` (13 §5): текущее закрывается со снимком; при veto
  хост показывает диалог, «Остаться» → `RouteDiscarded`.

Переходы (slide GitHub-style) — из текущего `App.kt`, без изменений.

## NavigationService ↔ Shell

`ShellNavigation(appId)`:
- `openApp(id, config)` → shell.go(App(id)) + `handler.launch(id, config)`; `Result.failure(NavigationError.*)` при
  неизвестном/заблокированном id или veto текущего (D22);
- `close()` → shell.goBack(from) + `handler.close()`;
- `openSettings()` → shell.go(Settings(App(appId)));
- `openUrl` → expect/actual в `host` (Android `Intent.ACTION_VIEW`, desktop `Desktop.browse`,
  wasm `window.open`).

Приложение не получает `shell` — только эти четыре метода, и только на `InstanceRuntime`.

## Definition of Done как тест

`composeApp/src/desktopTest/…/ExtensibilityTest.kt`:

```kotlin
// A fourth app that lives only in the test source set. If it can be registered, discovered on
// All Apps, launched, configured and closed without touching core/ui/host, §24 holds.
class ProbeApp : SuperizerApp<ProbeConfig> { … }

@Test fun aNewAppNeedsOnlyRegistration() = runComposeUiTest {
    val superizer = buildSuperizer(testPlatform()) { register(ProbeApp()) }
    setContent { SuperizerShell(superizer) }
    onNodeWithText("Probe").performClick()
    onNodeWithText("probe: mode=default").assertIsDisplayed()
}
```
