# 15 — Реализация: что построено и где отошли от проекта

Дата: 2026-09-21. Это отчёт по итогам написания кода по заметкам 01–14, а не новая ревизия
проекта. Решения D1–D47 реализованы как записаны; ниже — только то, где код отличается от эскиза,
и почему, плюс состояние по шагам плана 08.

## 1. Что получилось

Две папки, как требует D9:

```
/prj/superizer/     библиотека: core, ui-theme, ui, host, testing, apps/test-app, fixture,
                    samples/consumer, scripts/verify.sh, scripts/web-smoke.mjs, CI, MIT
/prj/unitool/       Super App: composeApp (хост), apps/calculator, apps/currency-converter
```

Unitool потребляет библиотеку **только по maven-координатам**; `includeBuild("../superizer")`
подставляет исходники, когда папка есть. `project(":superizer:*")` в Unitool нет ни разу, и это
проверяет `checkDependencyRules` и `scripts/verify.sh`.

Шаги 08: 1–12 сделаны; 13 (режим C как штатный) — скрипты и `samples/consumer` на месте, прогон
`SUPERIZER_FROM_MAVEN=1` выполняется тем же `verify.sh`; 14 (скриншоты, README, RELEASING) сделан.
Шаг 15 (FCM) не начат и не должен быть — он ждёт ответа на вопрос 18 из 09.

## 2. Отличия от эскиза, которые стоит знать

| # | Что в заметках | Что в коде | Почему |
|---|---|---|---|
| 1 | `Localized` — `fun interface` (03) | `@Serializable class Localized(entries, fallback)` | D47 требует, чтобы `AppManifest` ходил через JSON целиком. Лямбда не сериализуется; таблица — да. `localized("en" to …, "ru" to …)` не изменился |
| 2 | `Superizer` — фасад в `host` (05) | интерфейс `Superizer` + порты (`HostSettingsPort`, `ActivationPort`, `RoutePort`, `DiagnosticsPort`, `ShellCommand`) в `core`, реализация в `host` | Иначе `ui` (где живёт shell) пришлось бы зависеть от `host`, а от `ui` зависят приложения — и запрет §19 стал бы фикцией. Порты в `core` убирают это ребро из графа, и `checkDependencyRules` это держит |
| 3 | `NavigationService` → shell напрямую (05) | `NavigationService` эмитит `ShellCommand`, shell их собирает | То же самое ребро. Приложение не трогает состояние shell-а — оно просит |
| 4 | BCV (`binary-compatibility-validator`) для дампов API (D14) | встроенная в Kotlin 2.4 ABI-валидация: `checkLegacyAbi` / `updateLegacyAbi`, дампы в `<module>/api/` | Плагин BCV 0.18.1 на Gradle 9.5 **молча обрывает выполнение корневого build-скрипта**: всё после блока `apiValidation {}` не выполняется, задачи не регистрируются, сборка при этом «успешна». Встроенная валидация делает то же и понимает klib |
| 5 | фикстуры миграций — файлы `fixtures/config-v1.json` (12 §4.7) | `configFixtures`/`stateFixtures` — списки строк в подклассе `AppContractTest` | Загрузчика ресурсов, одинакового в desktopTest, Robolectric и Karma, не существует. Тест миграций, который нельзя запустить на wasm, сгниёт |
| 6 | `AppContractTest` в `commonMain` библиотеки `testing` | в `desktopMain` | `kotlin.test.Test` в *main*-сорсете на JVM есть только во «вкусе» JUnit. Тесты приложений и так живут в `desktopTest` (12 §4.1) |
| 7 | `fixture` собирается и под Android | только desktop + wasmJs | Android покрывает настоящий хост; вторая `AndroidManifest.xml` — это второй файл, который расходится с первым |
| 8 | Kover с порогами (D44) | не подключён | Не сделано; см. §5 |
| 9 | эталоны скриншотов + pixelmatch (D43) | скриншоты снимаются и складываются, сравнения с эталоном нет | Не сделано; см. §5 |

Ловушки, которые стоили времени и записаны, чтобы не стоить его снова:

- **BCV на Gradle 9.5 обрывает выполнение корневого скрипта молча.** Сборка «успешна», задачи из
  корневого `build.gradle.kts` просто не зарегистрированы. Диагностируется только бисекцией
  `println`-ами.
- **`runTest` ждёт всех детей своего scope.** Хост и приложение оба запускают бесконечные
  коллекторы; если отдать им scope от `runTest`, каждый тест падает по таймауту в минуту. Scope
  для handler-а и для `FakeAppRuntime` в TCK — отдельный, с `UnconfinedTestDispatcher(testScheduler)`.
- **Каталоги во временной папке переживают JVM.** Нумерованное имя (`unitool-test-1`) означает, что
  второй прогон читает то, что записал первый: три теста падали в зависимости от того, что
  происходило вчера. Только `Files.createTempDirectory`.
- **`testTag` на рамке поля ввода — это узел без text-input-семантики**, и `performTextInput` на нём
  падает. `AppTextField` вешает modifier вызывающего на сам `BasicTextField`.
- **Комментарий — не зависимость.** `checkDependencyRules` сначала вырезает комментарии: KDoc
  каждого модуля говорит словами ровно то, что задача проверяет кодом, и проверка, которая ловит
  собственную документацию, удовлетворяется только её удалением.

Мелочи, которые стоит знать при чтении кода:

- `AppHandler` получает `scope` хоста снаружи, а не берёт `Dispatchers.Main` сам — иначе юнит-тест
  ядра не поднимается. Побочный эффект: в тестах scope должен быть **не** scope от `runTest`,
  иначе бесконечные коллекторы держат тест до таймаута (это записано комментарием в `AppHandlerTest`).
- `kotlinx.browser` в Kotlin 2.x выехал из stdlib: `host` (который намеренно не зависит от Compose)
  просит `org.jetbrains.kotlinx:kotlinx-browser` явно.
- `kotlin.js.Date` на wasmJs нет — `SystemClock.wasmJs` зовёт `js("Date.now()")`.
- `AppTextField` вешает `modifier` вызывающего на сам `BasicTextField`, а не на рамку: `testTag` на
  рамке даёт узел без text-input-семантики, и `performTextInput` на нём падает.

## 3. Definition of Done (12 §5) — четыре факта

Все четыре проверены, каждый своей командой:

| Факт | Чем доказан |
|---|---|
| Четвёртое приложение добавляется одной строкой | `ExtensibilityTest` в `composeApp/desktopTest`: `WeatherApp` живёт только в тестовом сорсете, появляется на Home и открывается; `core`, `ui`, `host` не тронуты |
| `samples/consumer` собирается из mavenLocal | `./scripts/verify.sh` в библиотеке — шаг 5, PASS |
| Unitool зелёный против опубликованного пакета | `SUPERIZER_FROM_MAVEN=1 ./scripts/verify.sh` — RESULT: PASS, включая `assembleDebug` и `assembleRelease` |
| web-smoke проходит активацию через `?activate=` в wasm | `scripts/web-smoke.mjs` — «an activation payload unlocks and opens the hidden app», и лента событий в браузере даёт `Activated → RuntimeCreated → Configured → Created → Launched → Active` |

Отчёты: `superizer/build/verify/report.md` и `unitool/build/verify/report.md`, оба `RESULT: PASS`.

## 4. Как проверялось

- `core`: 21 юнит-тест — `AppId`, `AppConfigSpec` (слияние, битый payload, миграция,
  `schemaVersion`), `AppRegistry` (дубликат, `minHostContract`, `requires`, скрытые), `AppHandler`
  (порядок событий, `ConfigRejected` в обе стороны, `Crashed` и живучесть хоста, veto, снимок при
  уходе в фон, восстановление новым handler-ом на том же storage, просроченный снимок,
  `SetupMismatch`, гонка двух launch, disable).
- `host`: парсер QR (включая таблицу мусора из 12 §4.7), промокоды, `ActivationService` (все четыре
  входа и правила разблокировки), `PushRouter` (таблица 13 §4 построчно), `PendingRoute`,
  `TopicStore`, storage/unlock/snapshot, `KtorNetworkService` на `MockEngine`, locale, логгер.
- `apps/*`: арифметика калькулятора (перенесена без изменений), конвертация и парсер курсов,
  `AppContractTest` у каждого приложения, `runAppTest` для экранов.
- `composeApp`: сквозные — Home, drawer, открытие приложения в рамке хоста, язык, back, конвертер
  онлайн и офлайн; активация промокодом, QR и deep link; `ExtensibilityTest` (§24).
- Счёт по модулям: `core` 36, `host` 59, `apps/test-app` 17, `fixture` 8 (библиотека — 120);
  `composeApp` 27, `apps/calculator` 27, `apps/currency-converter` 27 (Unitool — 81). Ноль падений.
  (Прирост против первой сдачи — D48, см. §6.)
- Скриншоты: 12 кадров × 2 viewport, просмотрены глазами.
- Android: `assembleDebug` и `assembleRelease` (R8, без keystore) зелёные; устройства в этой среде нет.
- wasm: `wasmJsTest` проходит (Karma на системном Chromium); web-smoke проверяет, что бандл
  стартует, **рисует** (доля не-белых пикселей на скриншоте, а не чтение canvas — у WebGL-канваса
  нет `getImageData`, и он вежливо отвечает нулём, что выглядит ровно как сломанный рендер),
  и что маршрут дошёл до handler-а.

## 5. Что из 12–14 осталось не сделанным

Честный список, чтобы не всплыло как «забыли»:

1. **Kover и пороги покрытия (D44).** Не подключены. Тесты есть и перечислены выше, числа нет.
2. **Эталоны скриншотов и pixelmatch (D43).** Кадры снимаются; `screenshots/baseline/` пуст,
   `scripts/screenshot-diff.mjs` не написан.
3. **`scripts/size-budget.sh` и `size-budget.json` (D42).** `verify.sh` печатает размер wasm-бандла
   и APK в отчёт, но с порогом ×1.25 не сравнивает.
4. **Robolectric в `androidUnitTest` и `wasmJsTest` для platform-actual (D45).** `verify.sh` умеет
   гонять `wasmJsTest` при наличии Chromium, но самих тестов для actual-ов не написано.
5. **Instrumented-дымовой тест на Android (12 §4.1).** Не написан — устройства в этой среде нет,
   и тест, который никто ни разу не запускал, хуже отсутствующего.
6. **Отдельных тестов в `ui`** (12 §4.1 предлагал перенести туда `MenuDrawerTest` и др.) нет: drawer,
   All Apps, Settings и Service Menu проверяются сквозными тестами в `composeApp` и `fixture`.
   Это сознательно — они проверяют собранную вещь, а не компонент в вакууме, — но означает, что
   `ui` в одиночку не тестируется.
7. **FCM (шаг 15).** Намеренно: транспорт — `expect object` с no-op `actual` на всех трёх таргетах,
   всё выше него работает и протестировано на фейке.

Первые три и шестой — работа на несколько часов и не влияют на то, работает ли приложение. Четвёртый и пятый
требуют решения, где именно они должны жить, чтобы не дублировать сквозные тесты.

## 6. Ревизия 6 (D48): Home — выбранные приложения

Дописано 2026-09-21. Сначала вопрос был «как убрать приложение из выбранных — они же могут быть
скрытыми», и первый ответ (коммит `8695f2a` в библиотеке) сделал обратный ход только для скрытых:
долгий тап по разблокированной плитке снимал разблокировку через диалог. Владелец уточнил модель:
**не все приложения показываются сразу** — обычное надо выбрать из списка, скрытое активировать, и
тогда *любое* можно убрать одним жестом. Это и реализовано; первый вариант заменён целиком.

Что изменилось в коде:

| Где | Что |
|---|---|
| `core/Superizer.kt` | `home: StateFlow<List<AppId>>`, `addToHome(id)`, `removeFromHome(id)`. `DiagnosticsPort.lock` вернулся: замок — диагностика, а не жест пользователя |
| `core/SuperizerEvent.kt` | `AddedToHome`, `RemovedFromHome`, `Locked` (у `Unlocked` не было пары — лента событий не видела замок) |
| `core/AppHandler.kt` | `restoreLastSession` запускает снимок без `force`; неудача стирает снимок. Дыра: запертое приложение возвращалось через вчерашний экран |
| `host/Stores.kt` | `HomeStore` (ключ `host.home`): «ключа нет» → умолчания хоста, «пустой список» → выбор пользователя; `UnlockStore.lock` эмитит `Locked` |
| `host/SuperizerHost.kt` | `SuperizerBuilder.home("calculator", …)`; `reveal` = unlock + addToHome — одно место для QR, промокода и Service Menu; `conceal` = removeFromHome + lock + закрыть сессию + забыть снимок, им пользуются `lock` и `reset` |
| `host/Activation.kt` | `ActivationService(onUnlock = …)` — хост подставляет `reveal` |
| `ui/HomeScreen.kt` | бывший `AllAppsScreen`: плитки выбранных, плитка «Добавить», долгий тап на любой плитке → `onRemove` без диалога; пустое состояние |
| `ui/CatalogScreen.kt` | новый: всё доступное по категориям, галочка у того, что на Home, тап переключает |
| `ui/SuperizerShell.kt` | `ShellDestination.Catalog(from)`; drawer = «Главная» + приложения с Home + «Все приложения»; диалог скрытия удалён, `CloseVetoDialog` обобщён в `HostDialog` |
| `ui/HostStrings.kt` | `home`, `homeEmpty`, `addApp`, `removeFromHome`, `catalogHint`, `onHome`; `errorBack` → «На главную» |
| `unitool/UnitoolHost.kt` | `home("calculator")`: свежий Unitool — калькулятор на Home, конвертер в каталоге |

Тесты:

- `core`: `aSnapshotOfAnAppLockedAgainSinceIsDroppedNotRestored`.
- `host`: четыре на `HomeStore` — умолчания только на первом запуске, пустой список сохраняется,
  порядок переживает перезапуск, битый JSON → умолчания.
- `fixture`: активация кладёт стенд на Home и долгий тап убирает (каталог его по-прежнему
  предлагает); каталог добавляет и выбор переживает второй хост на том же каталоге настроек;
  запертого приложения в каталоге нет.
- `composeApp`: свежая установка — только калькулятор и плитка «+»; каталог добавляет конвертер и
  это переживает перезапуск; долгий тап убирает калькулятор, Home пустой, вернуть — один тап;
  активация стенда кладёт его на Home, долгий тап убирает, каталог его предлагает без второго кода.
  Тесты конвертера сначала добавляют его из каталога — это и есть путь пользователя.
- Скриншоты: `01-all-apps` → `01-home`, добавлен `12-all-apps` (каталог); `05`/`06` идут через
  каталог.

ABI-дампы обновлены `updateLegacyAbi`. Полный `verify.sh` в обеих папках зелёный.

Что осознанно не сделано: перестановка плиток перетаскиванием (порядок = порядок добавления; убрать
и добавить снова переносит в конец), и тест-мост wasm не публикует `home` — web-smoke проверяет
`?link=`/`?activate=`, которым Home не нужен.
