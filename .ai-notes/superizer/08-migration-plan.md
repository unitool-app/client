# 08 — Миграция текущего Unitool и план реализации

## Куда переезжает существующий код

| Сейчас (`composeApp/src/commonMain/.../unitool/`) | Куда | Изменения |
|---|---|---|
| `theme/AppTheme.kt` | `superizer:ui-theme` — `cx.m42.superizer.theme` (D23) | без изменений; отдельный модуль, чтобы наборы компонентов зависели от токенов, а не от `ui` |
| `components/AppScaffold.kt`, `AppButton.kt`, `Icons.kt` | `superizer:ui.components` | `AppScaffold` теряет знание о `BuildInfo` → принимает `footer: @Composable`; добавляются глифы `calculator`, `currency`, `apps`, `qr` |
| `components/MenuEntry` | `superizer:ui` | как есть |
| `i18n/Strings*.kt`, `Lang.kt` | `superizer:ui.i18n` (`HostStrings`) — только строки хоста (menu, back, settings, allApps, activate, serviceMenu…) | калькуляторные строки уходят в `apps/calculator` |
| `i18n/AppLocale.kt` | `superizer:host` — реализация `LocaleService`; `Lang`-enum остаётся в `ui` для Settings | `langTag: StateFlow` поверх текущего `mutableStateOf` |
| `data/PrefsStorage.kt` + 3 actual | `superizer:host.storage` | как есть; `PrefsStorageService(appId)` сверху |
| `data/AppSettings.kt` | `superizer:host` (`HostSettings`) | ключи хоста: language, haptics, unlocked |
| `platform/Haptics*.kt`, `DeviceLanguage*.kt` | `superizer:host.platform` | как есть |
| `platform/BackHandler*.kt` | `superizer:ui.platform` | нужен приложениям → в `ui`, не в `host` |
| `calculator/Calculator.kt` | `apps/calculator` | как есть |
| `screens/CalculatorScreen.kt` | `apps/calculator` | без `AppScaffold`, свои строки |
| `screens/SettingsScreen.kt` | `superizer:ui.screens.SettingsScreen` | + слот для `settingsSection`-ов приложений; 7 тапов по сборке → Service Menu |
| `App.kt` | `superizer:ui.shell.SuperizerShell` | destinations хоста, контейнер приложения |
| `MainActivity.kt`, `main.kt` ×2, `index.html`, `res/` | `composeApp` | + `UnitoolHost.kt`, deep link intent-filter |
| `BuildInfo` (generated) | `composeApp` | в `HostInfo` |
| `desktopTest/*` | `MenuDrawerTest`, `SettingsScreenTest`, `AppLocaleTest` → `composeApp` (сквозные) или `superizer:ui` (компонентные); `CalculatorTest`, `CalculatorScreenTest` → `apps/calculator`; `Screenshots.kt` → `composeApp` (+ новые кадры: All Apps, Converter, Service Menu) | тексты ассертов те же |

Пакет `cx.m42.unitool` остаётся только в `composeApp`.

## План по шагам (каждый шаг — зелёная сборка и тесты)

Команда проверки после каждого шага: `./scripts/verify.sh` в затронутой папке (12, §4); до
шага 7 в Unitool — прежний `./gradlew :composeApp:desktopTest :composeApp:assembleDebug
:composeApp:wasmJsBrowserDistribution` (последнее — потому что wasm ловит ошибки, которые JVM
прощает: ServiceLoader, java.*). Шаги 1–5 и 10 — в `/prj/superizer` (режим A), 6–9, 11–14 — в
Unitool с `includeBuild` (режим B); режим C — на каждом шаге, где менялся публичный API.

1. **Скелет библиотеки в `/prj/superizer`** (12 §6). `build-logic` с convention-плагинами
   (explicit API, BCV с пустыми дампами, D14) и задачей `checkDependencyRules` (D21) — сразу, пока
   граф пустой и правила тривиально зелёные; пустые `core, ui-theme, ui, host, testing`,
   `apps/test-app`, `fixture` с одним экраном, `samples/consumer`, `scripts/verify.sh` и
   `scripts/web-smoke.mjs` с тестовым мостом `window.__superizer`, `LICENSE` (MIT, D41),
   `.github/workflows/{verify,publish}.yml` (D40), замер размеров в отчёте (D42), Kover с порогами
   в режиме WARN (D44), `scripts/screenshot-diff.mjs` и пустой `screenshots/baseline/` (D43),
   Robolectric в `androidUnitTest` `host` и `wasmJsTest` через Karma (D45), греп на `@Ignore`
   (D46). `verify.sh` PASS на пустом скелете, включая web-smoke и `publishToMavenLocal`. Коммит.
   Unitool на этом шаге не трогается.
2. **`ui-theme`, `ui` и `host` из существующего.** Копирование (не перенос: Unitool продолжает
   работать на своём коде до шага 7) по таблице выше в `/prj/superizer`; `fixture` собирается на
   новых пакетах; `MenuDrawerTest`/`SettingsScreenTest`/`AppLocaleTest` переезжают в `ui`/`host` с
   теми же ассертами. Коммит.
3. **`core`: контракт и runtime-интерфейсы** (03, 04, события из 05), включая `AppManifest` и
   `require(key)` (D47; тесты: отказ по неизвестному ключу, по пути с пробелом, сериализация
   манифеста туда-обратно), `Clock` (D30),
   `AppIcon.Path` (D29), `NetworkService.online` (D32), `HostInfo.debug` (D34). Только типы +
   `AppRegistry` + `AppConfigSpec` + `ServiceKey` + `SuperizerContract.VERSION` + unit-тесты на `AppId`, `decode`
   со слиянием дефолта и с битым payload (`Result.failure`), `migrate` по `schemaVersion`,
   registry-дубликаты, отказ по `minHostContract` и по `requires`. Первый `apiDump` — здесь.
4. **`host`: реализации сервисов** + `DefaultHostRuntimeFactory` (оба уровня, D15) +
   `PlatformLifecycle` expect/actual (D17) + `ServiceRegistry`; `testing`: `FakeAppRuntime`,
   `runAppTest`, `AppContractTest` (D21). Ktor подключается здесь; проверить wasm-движок.
5. **`AppHandler`** + state machine + `SnapshotStore` (D16) + `CoroutineExceptionHandler` → `Crashed`
   (D31) + `Superizer.reset(id)` (D35) + сверка `setup` с манифестом → `SetupMismatch` (D47) + тесты:
   последовательность событий; корутина экземпляра бросает →
   `Failed` + `Crashed`; reset чистит все четыре хранилища;
   Failed-путь (`onLaunch` бросает); `ConfigRejected` с `fallbackToDefault` true/false;
   background → `onBackground` + снимок; `restoreLastSession` после «смерти» (новый handler на том
   же storage); просроченный снимок игнорируется; **`AppHandlerRaceTest`** (12 §4.7): второй launch
   во время Launching, close во время onLaunch, двойной back, PendingRoute при veto, Background в
   Creating, reset открытого.
6. **`apps/calculator`** как первое приложение; `CalculatorTest` переезжает; новый
   `CalculatorInstanceTest` с `runAppTest` и `CalculatorContractTest : AppContractTest`
   (с фикстурами `config-v1.json`/`state-v1.json` и бюджетом `onLaunch`, 12 §4.7).
   `saveState`/`restore` для `entry`/`accumulator`. `composeApp` пока не использует.
7. **`SuperizerShell`** + `AppContainer` + All Apps в библиотеке; в Unitool появляются
   `includeBuild("../superizer")`, `apps/calculator`, `UnitoolHost.kt`, `scripts/verify.sh`;
   `App.kt` заменяется на `SuperizerShell(buildSuperizer(...))`, старые `theme/components/i18n/
   data/platform` удаляются из `composeApp`. Сквозные `MenuDrawerTest`/`CalculatorScreenTest`
   проходят с прежними ассертами (те же русские тексты). Первые эталоны скриншотов в обоих
   репозиториях (D43); пороги Kover переводятся из WARN в FAIL (D44). **Первая точка, где
   Unitool = Super App.**
8. **Settings** с секциями приложений + жест в Service Menu (только `debug`, D34); **Service Menu**
   с «Сбросить» и «Simulate push»; ширина 480 dp в shell (D36); `LocalTokens` в `ui-theme` (D37);
   `HapticsService` с настройкой (D38); testTag `<id>:root` в контейнере (D39).
9. **Activation и маршрутизация** (+ таблицы мусора для всех четырёх парсеров и Robolectric-тест
   разбора intent, 12 §4.7): модель (с `HostCommand`, D20), `QrPayloadParser`, `LocalPromoCodes`,
   `UnlockStore`, экран Activate, `PendingRoute`, deep links всех форм из 06 на Android/web/desktop,
   `DeepLinkTable` из `ctx.deepLink`. Тесты парсера и `ActivationService`; тест, что битый `config`
   в валидном QR даёт `ConfigRejected`, а не `Rejected`; `PendingRouteTest`; `PushRoutingTest` в
   fixture (тап до композиции, тап во время приложения, veto).
9b. **Push на фейковом транспорте** (13): `PushService` в `core` (уже с шага 3), `expect PushTransport`
   с no-op actual ×3, `PushRouter` + таблица правил как тесты, `TopicStore`, `DeviceRegistrar`
   (лог), «Simulate push» в Service Menu, секция Push в Test App, web-smoke `?link=`.
10. **`apps/test-app`** (скрытое) — закрывает чек-лист §22.
11. **`apps/currency-converter`** — сеть, кэш, офлайн.
12. **`ExtensibilityTest`** (§24) с `ProbeApp` в тестовом source set `composeApp`.
13. **Публикация как штатный режим C**: `SUPERIZER_FROM_MAVEN=1 ./scripts/verify.sh` в Unitool
    зелёный; `samples/consumer` зелёный; CHANGELOG библиотеки с `contractVersion`. README
    библиотеки — «как написать приложение» на 1 экран, по образцу `docs/BuildingModules.md`
    Adminizer.
14. Скриншоты (`Screenshots.kt`) для новых экранов; `README.md` репозитория и `RELEASING.md`
    обновить (структура, deep link, новые экраны в листинге Play).

После MVP:

15. **FCM `actual PushTransport`** на Android: Firebase Messaging, `google-services.json` (секрет, как
    keystore), канал уведомлений, `POST_NOTIFICATIONS`, `NotificationCompat` с `link` в intent.
    Ничего выше транспорта не меняется — это критерий, что шаг 9b сделан правильно. Сервер
    регистрации/отправки — вне этого репозитория.

Шаги 6 и 11 можно вести параллельно с 7–9: приложения тестируются через `testing`, хост им не нужен.

## Что ломается и как об этом помнить

- **R8**: kotlinx-serialization в release-сборке. `superizer:core` несёт `consumer-rules.pro` с
  правилами сериализации, чтобы `composeApp/proguard-rules.pro` остался пустым.
- **wasm + Ktor**: движок `ktor-client-js`; CORS у источника курсов — проверить руками в браузере
  (`chromium --use-gl=angle --use-angle=swiftshader`).
- **`AppLocale` — глобальный объект.** При выносе в `host` он остаётся глобальным (причины в его
  KDoc верны), но `LocaleService` отдаёт `StateFlow`, а тесты по-прежнему пинят через
  `useForTesting`.
- **`kotlin-js-store/yarn.lock`** — один на корень; появление новых wasm-модулей его перепишет,
  коммитить.
- **Configuration cache** включён; `generateBuildInfo` уже дружит с ним, новые задачи — тоже
  (никакого `project` в `doLast`). `checkDependencyRules` читает граф на этапе конфигурации и
  кладёт результат во входы задачи.
- **BCV и `apiDump`.** Каждое изменение публичного API `superizer:*` требует перегенерации
  дампов, иначе `check` красный. Это намеренно: диф `api/*.api` в PR — и есть ревью контракта.
  Поднимать `SuperizerContract.VERSION` только на несовместимых изменениях `core`.
- **Снимок и process death на Android.** `PlatformLifecycle` должен ловить `ON_STOP`, не `ON_PAUSE`:
  в `ON_PAUSE` попадают диалоги разрешений, и приложение будет писать снимок на каждую вибрацию.
