# 01 — Обзор, соответствие Adminizer и принятые решения

## Что строим

Библиотека `superizer` (пакет `cx.m42.superizer`) + хост `composeApp` (Unitool) + три приложения
в `apps/`. Ключевой принцип спецификации и Adminizer совпадают:

> Приложение пишется так, как будто оно уже standalone. Хост — лишь один из возможных
> runtime-провайдеров. Приложение никогда не видит хост, только `AppRuntime`.

В Adminizer это правило сформулировано жёстко (`docs/BuildingModules.md`, «App Boundary»):
*«An app must not accept, store, or depend on an `Adminizer` instance»*. Переносим дословно:
приложение не принимает и не хранит `Superizer`, `AppHandler`, `AppRegistry`.

## Соответствие Adminizer → Superizer

| Adminizer (Node/TS) | Superizer (Kotlin/KMP) | Комментарий |
|---|---|---|
| `AbstractAdminizerApp<TConfig>` (`name`, `version`, `config`, `setup(ctx)`) | `SuperizerApp<C>` (`id`, `version`, `metadata`, `configSpec`, `setup(ctx)`, `launch(runtime, config)`) | У нас приложение — ещё и экран, поэтому добавляется фабрика живого экземпляра `AppInstance` |
| `AppManager.register / enable / disable / unregister` | `AppRegistry.register` + `AppHandler.launch / close / dispose` | В Adminizer «enable» = поднять ресурсы на сервере; у нас «launch» = открыть экран |
| `AppSetupContext` (`ctx.controller`, `ctx.asset`, `ctx.widget`, `ctx.config`, `ctx.listener`) | `AppSetupContext` (`ctx.menuEntry`, `ctx.settingsSection`, `ctx.deepLink`, `ctx.listener`) | Регистрация статических ресурсов при `enable`; всё, что зарегистрировано, снимается disposer-ами |
| `AppRuntime` (`models`, `config`, `accessRights`, `notifications`, `apps`) | `AppRuntime` (`storage`, `network`, `config`, `navigation`, `analytics`, `logger`, `locale`, `haptics`, `push`, `auth`, `apps`, `events`, `scope`) | Тот же смысл: единственная точка доступа к платформе |
| `req.runtime` в контроллере | `runtime` в `launch()` + `LocalAppRuntime` в Compose | |
| `fixture/index.ts` — сборка хоста, `appManager.enable(new XApp())` | `composeApp/.../UnitoolHost.kt` — `Superizer.build { register(CalculatorApp()) … }` | Явная регистрация; никакого сканирования classpath |
| `adminizer.emitter` (`app:registered`, `app:enabled`, …) | `SuperizerEvents: SharedFlow<SuperizerEvent>` | Sealed-иерархия событий вместо строк |
| `ModuleManagerApp` (список модулей, вкл/выкл) | Service Menu (скрытый экран хоста) | |
| `disposers[]` — откат ресурсов в обратном порядке | `Disposable` + `DisposerList` | Тот же приём |
| `npm publish` / `dist/` | `maven-publish` → `mavenLocal` → GitHub Packages | См. 02 |
| `npm link`, workspaces | `includeBuild(...)` (composite build) / `project(":superizer:core")` | См. 02 |

Чего из Adminizer **не берём** в MVP: models/ORM, access rights, widgets, catalogs, media manager,
AI-assistant. Это серверные вещи. Их аналоги (auth, push, analytics) в MVP — интерфейсы с
no-op/консольной реализацией, чтобы контракт был полным, а хост — минимальным.

## Что уже есть в Unitool и переиспользуется

Текущий `composeApp` — хороший стартер: дизайн-токены без Material (`AppTheme`), drawer-scaffold,
`Strings`-интерфейс вместо ресурсов, expect/actual для haptics/back/language/prefs, чистая логика
калькулятора (`CalculatorState.press`), desktop-UI-тесты и скриншоты. Всё это становится
`superizer-ui` / `superizer-host`, а калькулятор — первым приложением. Подробно — в 08.

Из `agentiz/mobile-client` берём проверенный стек для runtime-сервисов: Ktor 3 client +
kotlinx-serialization (NetworkService), `Push`-объект с `StateFlow` как образец для PushService,
`LocalStore`/`LocalCache` как образец типизированного StorageService.

## Принятые решения

- **D1. Явная регистрация, без ServiceLoader/рефлексии.** На wasmJs нет ServiceLoader, R8 при
  явной регистрации ничего не требует keep-ать. Ровно как `fixture/index.ts` в Adminizer.
- **D2. Конфиг приложения — JSON** (`kotlinx.serialization.json.JsonObject`), типизируется в самом
  приложении через `@Serializable data class`. QR-payload, промокод, remote-config, Service Menu —
  всё сводится к `JsonObject`. Приложение не знает источник.
- **D3. Две сущности: определение и экземпляр.** `SuperizerApp` — зарегистрированный синглтон
  (id, метаданные, `setup`), `AppInstance` — живой экран, создаваемый на каждый `launch`. Это
  примиряет Adminizer (синглтон + `setup`) со спецификацией («Create App instance» на каждый запуск).
- **D4. Один активный экземпляр за раз.** Контейнер хоста показывает одно приложение;
  `AppHandler` — конечный автомат с одним текущим `AppSession`. Стек/мультиокна — позже, контракт
  этого не запрещает.
- **D5. Слои библиотеки:** `core` (контракт + интерфейсы runtime, зависит только от
  `compose.runtime`, coroutines, serialization), `ui` (тема, компоненты, scaffold, экраны хоста),
  `host` (реализации сервисов, expect/actual), `testing` (fake runtime). Приложение зависит от
  `core` (обязательно) и `ui` (по желанию, ради общего вида). Приложение **никогда** не зависит от
  `host` и от `composeApp`.
- **D6. Направление зависимостей строго вниз:** `composeApp → apps/* → superizer:ui → superizer:core`,
  `composeApp → superizer:host → superizer:core`. `apps/*` не знают друг о друге; общение только через
  `runtime.navigation.openApp(id, config)`.
- **D7. QR в MVP — без камеры.** Парсер payload + ввод текстом + deep link (`unitool://activate?…`,
  `?activate=` в web). Камера (CameraX/ML Kit на Android) — отдельный шаг, платформенный и тяжёлый
  по зависимостям; контракт активации от него не зависит.
- **D8. Скрытые приложения.** `metadata.hidden = true` → не показываются на All Apps, пока не
  разблокированы активацией (QR/промокод). Факт разблокировки хранится в storage хоста. Service Menu
  показывает всё независимо от скрытости. Разблокированное скрытое приложение попадает на Home
  (D48); убрать его с Home — обычный жест, а вернуть под замок может только Service Menu.
- **D9 (ревизия 2, см. 12). Библиотека живёт в отдельной папке и репозитории `/prj/superizer`**
  со своей сборкой, fixture-хостом и `samples/consumer`. Unitool потребляет её **только по
  maven-координатам**: через `includeBuild("../superizer")` при разработке и из `mavenLocal` при
  проверке (`SUPERIZER_FROM_MAVEN=1`). `project(":superizer:*")` в Unitool не пишется никогда.
  Test App едет в библиотеку, калькулятор и конвертер остаются приложениями Unitool.
- **D10. Имена:** пакет `cx.m42.superizer`, group `cx.m42.superizer`, артефакты `core`, `ui`, `host`,
  `testing`. Приложения: `cx.m42.superizer.apps:<id>`, пакет `cx.m42.apps.<id>`.
- **D11. Приложение владеет своей i18n.** У каждого приложения свой `interface XStrings` и таблицы;
  текущий язык приходит из `runtime.locale`. Общие строки хоста (Меню, Назад, Настройки) — в `ui`.
- **D13. Динамическая загрузка приложений (плагины, wasm, web-расширения) — не делаем.** Разбор
  вариантов лежит в 10 как идеи на будущее; в контракт входят только два бесплатных задела
  (наблюдаемый Registry, JSON-совместимые интерфейсы runtime).
- **D12. Back — через существующий `SystemBackHandler`** (`ui`), правило вложенности сохраняется:
  drawer → приложение → shell. Приложение просто использует композабл, ничего не регистрирует в хосте.

Решения ревизии 1 (см. 11; приняты до начала кода, потому что после шага 7 плана они дорогие):

- **D14. Контракт версионируется.** `HostInfo.contractVersion: Int` и `SuperizerApp.minHostContract: Int`;
  реестр не регистрирует приложение, которому нужен более новый хост (событие `RegistrationRejected`,
  не исключение). Все модули `superizer:*` — в explicit API mode, публичный API зафиксирован
  `binary-compatibility-validator` (`api/*.api` в репозитории; `apiCheck` в сборке).
- **D15. Два уровня runtime.** `AppRuntime` живёт от enable до disable (listeners, push, deep links,
  фоновые задачи), `InstanceRuntime : AppRuntime` — от launch до dispose (плюс `navigation` и
  scope экрана). Это enable/request из Adminizer; без него push для закрытого приложения некому доставить.
- **D16. Экземпляр умеет сохранять и восстанавливать состояние.** `AppInstance.saveState(): JsonObject?`
  и `restore(state)`. Handler снимает снимок при close и при уходе в фон, кладёт в storage хоста и
  восстанавливает после смерти процесса. Будущий стек приложений ложится на тот же механизм.
- **D17. Lifecycle хоста наблюдаем.** `runtime.lifecycle: StateFlow<HostLifecycle>` (`Foreground`/`Background`)
  и хуки `onForeground`/`onBackground` на экземпляре. Конвертер не опрашивает сеть в фоне.
- **D18. Ошибки конфига не глотаются.** `AppConfigSpec.decode` возвращает `Result<C>`; handler публикует
  `ConfigRejected(reason)` и запускает на дефолте только если спека это разрешает
  (`fallbackToDefault = true` по умолчанию). В конфиге и в сохранённом состоянии — `schemaVersion`.
- **D19. Runtime — ядро плюс реестр сервисов по ключу.** Обязательные сервисы остаются полями;
  всё необязательное — `runtime.service(ServiceKey<T>)`. Приложение объявляет `metadata.requires`,
  реестр сверяет с тем, что умеет хост, при регистрации. Новый сервис не ломает `FakeAppRuntime` и мост;
  `requires` без изменений становится `permissions` манифеста расширения (10).
- **D20. Нет зарезервированных app id.** Промокод `SERVICE` даёт `ActivationResult.HostCommand`, а не
  `Activation(AppId("service-menu"))`. Shell знает команды, а не магические имена.
- **D21. Правила зависимостей проверяет сборка с шага 1** (`checkDependencyRules`), а не ревью.
  В `testing` — `AppContractTest`: абстрактный тест, который наследует каждое приложение.
- **D22. `openApp` возвращает `Result<AppResult>`**, а не `Boolean`. В MVP `AppResult.payload` всегда null;
  сигнатура зарезервирована под обмен результатами между приложениями, чтобы потом её не менять.
- **D23. Токены темы — отдельный модуль `superizer:ui-theme`.** Сторонние наборы компонентов зависят от
  токенов, а не от всего `ui`.

Решения ревизии 2 — пуши и маршрутизация (см. 13):

- **D24. Payload пуша — data-only, схема 1:** `appId` обязателен, `link` — deep link того же приложения,
  `data` — JSON-строкой. Без `notification`-блока FCM, уведомление рисует хост.
- **D25. Пуш не активирует скрытое приложение.** Locked → drop с событием; разблокировать может только QR/промокод.
- **D26. Тап по уведомлению = deep link.** Единственный вход — `ActivationService.fromDeepLink`; тот же
  код, что для QR, промокода и `?link=` в web. `PendingRoute` consume-once, как `Push.consumeRoute()` в agentiz.
- **D27. Приложение не рисует уведомления и не навигирует из обработчика сообщения.** Оно подписывается
  на темы, читает `messages` на runtime уровня приложения и получает конфиг при запуске.
- **D28. Транспорт — `expect object PushTransport` с no-op actual на всех таргетах.** Роутер, темы,
  регистрация устройства, Test App и Service Menu работают на фейке; FCM — один `actual` после MVP.

Решения ревизии 3 — то, что оставалось нерешённым (см. 14):

- **D29. `AppIcon.Path(svgPath)`** — приложение везёт свою иконку SVG-path строкой; `ui` рисует через
  `PathParser`. Без этого своя иконка = правка хоста, что ломает §24.
- **D30. `runtime.clock`** — поле `Clock` (`fun interface { fun now(): Long }`) в `AppRuntime` с первого
  дня; снимки, пуши и «старше суток» тестируются без реального времени.
- **D31. Ошибка в корутине экземпляра → `Failed` + `HostErrorScreen` + событие `Crashed`.**
  `CoroutineExceptionHandler` на scope экземпляра. Исключение в композиции роняет процесс — ограничение
  Compose, фиксируется как известное, `AppContractTest` ловит очевидное.
- **D32. Сеть: явные заголовки, таймаут 15 с, без повторов, `online: StateFlow<Boolean>`.** Хост
  не подмешивает авторизацию; приложение берёт токен из `auth.session` и кладёт в заголовки само.
- **D33. Префиксы хоста в аналитике и логах.** Приложение пишет `"error"`, хост шлёт
  `"calculator.error"`; тег логгера `app:<id>`. `LogBuffer` — 500 строк; в release консоль только
  `warn`/`error`, буфер — все уровни.
- **D34. Service Menu в release — только по промокоду.** Семь тапов работают лишь при
  `HostInfo.debug = true`; код промокода задаётся в `Superizer.build { serviceCode("…") }`. Иначе скрытые
  приложения не скрыты ни от кого.
- **D35. Disable не трогает данные; «Сбросить» — отдельная операция.** `Superizer.reset(id)` чистит
  namespace хранилища, темы пушей, снимок и unlock; только из Service Menu; событие `Reset`.
- **D36. Ширина контента shell ≤ 480 dp, по центру.** Desktop-окно и браузер шире телефона; адаптивная
  раскладка — не в MVP. Drawer и AppScaffold как есть.
- **D37. Тема светлая, токены через `LocalTokens`.** `ui-theme` отдаёт `Tokens` объектом через
  `CompositionLocal` с первого дня; тёмный набор — второй объект без правки компонентов.
- **D38. `HapticsService` уважает переключатель хоста.** `tick()` — no-op, когда вибрация выключена
  в настройках; приложение об этом не знает.
- **D39. testTag-конвенция `<appId>:<element>`.** Корневой узел `Content()` — `<appId>:root`
  (ставит контейнер, не приложение); ключевые элементы приложения — свои теги. `AppContractTest`
  проверяет корневой тег; сквозные тесты и web-мост ищут по тегам, а не по тексту.
- **D40. CI — GitHub Actions в обоих репозиториях** запускает `scripts/verify.sh` на каждый push
  (ubuntu, Chromium, Android SDK без устройства); библиотека публикуется в GitHub Packages по тегу `v*`.
- **D41. Лицензия библиотеки — MIT**, как у Adminizer. `LICENSE` в корне `/prj/superizer` с шага 1.
- **D42. Бюджеты размера в отчёте.** `verify.sh` пишет размер wasm-бандла и debug-APK; порог —
  первое измерение × 1.25, превышение = WARN в отчёте (не FAIL).

Решения ревизии 4 — покрытие тестами (см. 12 §4.7):

- **D43. Эталоны скриншотов в репозитории.** Phone-viewport, сравнение pixelmatch, > 0,5 % = FAIL с
  diff-картинкой; обновление эталона — отдельный коммит.
- **D44. Kover с порогами по модулям:** core 90 %, host 70 %, логика приложений 80 %; ui и хосты без
  числа. WARN до шага 7, FAIL после.
- **D45. Platform-actual тестируются без устройства:** Robolectric в `androidUnitTest` для Android,
  `wasmJsTest` (Karma) для wasm. Instrumented — один дымовой.
- **D46. Правила тестов:** баг = сначала падающий тест; `@Ignore` = FAIL; flaky чинится в том же
  коммите; поиск по testTag; без сна и сети; одно утверждение на тест.

Решение ревизии 5 — манифест (см. 03):

- **D47. `AppManifest` — один сериализуемый объект вместо россыпи полей.** id, version,
  minHostContract, metadata, `requires`, `deepLinks`, `pushTopics`, `networkHosts`. Реестр проверяет
  его целиком при регистрации; после `setup` сверяет, что зарегистрировано только объявленное
  (`SetupMismatch`); Service Menu показывает манифест до запуска; манифест web-расширения (10) —
  это он же. `runtime.require(key)` для объявленных ключей падает на необъявленном в первом же
  тесте. Декларация и поведение разведены: манифест говорит «что», `setup` — «как».
- **D48 (ревизия 6). Home — это выбранные приложения, а не «всё видимое».** Свежая установка
  показывает на Home только то, что хост назвал в `SuperizerBuilder.home("calculator")`; всё
  остальное лежит в каталоге «Все приложения» (`ShellDestination.Catalog`) и попадает на Home по
  тапу. Скрытое приложение (D8) в каталоге не показывается, пока его не разблокировала
  активация, и любая разблокировка — QR, промокод, Service Menu — сразу кладёт его на Home
  (`reveal` = `unlock` + `addToHome`, одно место в хосте). Долгий тап по **любой** плитке убирает
  её с Home без диалога: жест одинаков для всех, потому что каждая плитка попала туда по выбору,
  а дорога назад — один тап в каталоге. Убрать с Home ≠ запереть: приложение остаётся
  разблокированным, остаётся в каталоге и не теряет данных; `DiagnosticsPort.lock` (Service Menu)
  и `reset` (D35) — единственное, что снимает разблокировку, и они же убирают плитку и закрывают
  открытую сессию. Хранится упорядоченный список `host.home`; «ключа нет» = первый запуск и
  применяются умолчания хоста, «пустой список» = выбор пользователя, который следующая сборка
  не перебивает. Drawer показывает то же, что Home, в том же порядке. `restoreLastSession`
  больше не запускает снимок с `force`: запертое приложение не возвращается через вчерашний
  экран. События: `AddedToHome`, `RemovedFromHome`, `Locked` (парное к `Unlocked`).

Явно **не в MVP** (чтобы не всплывало как «забыли»): remote config как источник конфига (D2
допускает, но не проектируется); общий `PermissionService` (разрешение есть только у пушей);
стек приложений и обмен результатами (11); адаптивная раскладка (D36); тёмная тема (D37).

## Границы MVP (чек-лист спецификации, §23)

| Пункт | Где реализуется | MVP |
|---|---|---|
| Abstract App contract | `core` — `SuperizerApp`, `AppInstance` | ✔ |
| App Registry | `core` — `AppRegistry` | ✔ |
| App Handler | `core` — `AppHandler` | ✔ |
| App Runtime | `core` интерфейсы, `host` реализации | ✔ |
| DI через Runtime | `launch(runtime, config)`; `LocalAppRuntime` | ✔ |
| Shared service interfaces | `core/runtime/*` | ✔ (push/auth/analytics — заглушки) |
| App lifecycle | `AppHandler` state machine + hooks | ✔ |
| App configuration | `AppConfigSpec<C>` + `JsonObject`; `Result` + `ConfigRejected` (D18) | ✔ |
| State restore / process death | `saveState`/`restore` + снимок сессии в storage хоста (D16) | ✔ |
| Host lifecycle | `runtime.lifecycle`, `onForeground`/`onBackground` (D17) | ✔ |
| Contract versioning | `contractVersion`/`minHostContract`, BCV (D14) | ✔ |
| Push routing | `PushRouter`, `PendingRoute`, `TopicStore`, `DeviceRegistrar` на фейковом транспорте (13) | ✔ (FCM actual — шаг 15) |
| QR activation | `host` — `QrPayloadParser` + `ActivationService`; ввод текстом / deep link | ✔ (камера — позже) |
| Promo-code activation | `host` — `PromoCodeResolver` (локальная таблица) | ✔ (remote — позже) |
| Hidden Apps | `metadata.hidden` + `UnlockStore` | ✔ |
| Home как выбранный набор + каталог (D48) | `Superizer.home/addToHome/removeFromHome`; `HomeScreen`, `CatalogScreen`; долгий тап убирает плитку | ✔ |
| All Apps screen | `ui` — `CatalogScreen` (список всего доступного, тап добавляет на Home) | ✔ |
| Service Menu | `ui` — `ServiceMenuScreen` | ✔ |
| Calculator App | `apps/calculator` | ✔ (перенос существующего) |
| Currency Converter App | `apps/currency-converter` | ✔ |
| Test App | `apps/test-app` | ✔ |

Обновлённый список файлов: 11 — что изменилось в ревизии 1 и почему.

Definition of Done (§24) проверяется так: добавить четвёртое приложение (`apps/weather`-заглушку)
можно, изменив только `settings.gradle.kts`, `composeApp/build.gradle.kts` (зависимость) и одну
строку `register(WeatherApp())` в хосте. Ни `core`, ни `ui`, ни `host` не трогаются. Это станет
тестом в `composeApp/src/desktopTest` — см. 05.
