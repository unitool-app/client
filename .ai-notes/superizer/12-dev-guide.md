# 12 — Гайд по разработке: цикл, папки, публикация, проверка за один запуск, что получим

Это инструкция для агента (и человека), который будет писать код по заметкам 01–11. Она
отвечает на четыре вопроса: **где** лежит код, **как** течёт цикл «правка → публикация →
потребление», **как одной командой** убедиться, что всё работает на desktop, в браузере и на
Android, и **что именно** должно получиться на экране в конце.

Все ссылки на решения — на 01 (D1–D23). Эта заметка меняет одно из них: D9 (см. §1).

---

## 1. Две папки, два репозитория

Ревизия D9: библиотека живёт **не** внутри Unitool, а в соседней папке со своим git и своей
сборкой. Причина — цикл из §2: библиотека должна уметь проверять себя без Unitool, а Unitool
должен уметь потреблять её как чужой пакет. В одном репозитории второе не проверяется никогда.

```
/prj/superizer/                       ← БИБЛИОТЕКА, свой git, свой ./gradlew, свой CI
├── settings.gradle.kts               rootProject "superizer"; include core, ui-theme, ui, host, testing, apps:test-app, fixture
├── build-logic/                      convention plugins (02): kmp-library, compose-library, checkDependencyRules
├── gradle/libs.versions.toml         версии из 02
├── core/  ui-theme/  ui/  host/  testing/
├── apps/test-app/                    скрытое приложение-стенд (07); живёт здесь, потому что тестирует runtime, а не Unitool
├── fixture/                          МИНИМАЛЬНЫЙ ХОСТ — аналог adminizer/fixture: регистрирует test-app и ProbeApp,
│                                     нужен библиотеке для UI-тестов, скриншотов и web-smoke без Unitool
├── samples/consumer/                 ОТДЕЛЬНАЯ Gradle-сборка (свой settings.gradle.kts), которая берёт библиотеку
│                                     ТОЛЬКО из mavenLocal — проверка публикации (§4, шаг 6)
├── scripts/verify.sh                 «один запуск» (§4)
├── scripts/web-smoke.mjs             Playwright-проверка wasm-сборки fixture (§4.3)
├── api/                              дампы BCV (D14)
├── README.md                         «как написать приложение», на английском, один экран
└── CHANGELOG.md                      по релизам библиотеки; contractVersion упоминается явно

/prj/unitool/                         ← SUPER APP, этот репозиторий
├── settings.gradle.kts               include composeApp, apps:calculator, apps:currency-converter
│                                     + includeBuild("../superizer") ЕСЛИ папка есть (§2, режим B)
├── composeApp/                       хост: UnitoolHost.kt, entrypoints, Android-ресурсы, release
├── apps/calculator/                  приложения Unitool — здесь, не в библиотеке: это продукт, а не фреймворк
├── apps/currency-converter/
├── scripts/verify.sh                 тот же формат, что у библиотеки
└── .ai-notes/superizer/              эти заметки
```

Почему Test App в библиотеке, а калькулятор в Unitool: Test App существует, чтобы показать каждый
сервис runtime, — это часть фреймворка, как `module-manager` в fixture Adminizer. Калькулятор и
конвертер — то, ради чего существует Unitool. Если завтра появится второй хост, он получит Test
App из пакета и напишет свои приложения.

Ссылка `adminizer/` в Unitool остаётся как справочник. У библиотеки своей ссылки не нужно.

## 2. Цикл разработки

Три режима потребления (02) превращаются в три фазы одного цикла. Агент всегда знает, в какой он.

```
   ┌──────────────────────────────────────────────────────────────────────────────────┐
   │  A. Библиотека сама по себе        /prj/superizer:  ./scripts/verify.sh           │
   │     core → host → testing → test-app → fixture; unit + UI + screenshots + web     │
   └──────────────────────────────────────────────────────────────────────────────────┘
                                          │ зелёный
                                          ▼
   ┌──────────────────────────────────────────────────────────────────────────────────┐
   │  B. Совместная правка (npm link)   /prj/unitool с includeBuild("../superizer")    │
   │     Unitool собирается из исходников библиотеки; правка в core видна сразу        │
   │     ./scripts/verify.sh в Unitool                                                 │
   └──────────────────────────────────────────────────────────────────────────────────┘
                                          │ зелёный, контракт устоялся
                                          ▼
   ┌──────────────────────────────────────────────────────────────────────────────────┐
   │  C. Публикация и потребление (npm publish/install)                                │
   │     superizer: apiCheck → bump версии → publishToMavenLocal → samples/consumer    │
   │     unitool:   SUPERIZER_FROM_MAVEN=1 ./scripts/verify.sh  (без includeBuild)     │
   └──────────────────────────────────────────────────────────────────────────────────┘
```

**Режим A** — по умолчанию для шагов 1–5 плана (08): контракт, runtime, handler, Test App,
fixture. Unitool не участвует. Единица работы — шаг плана; после каждого — `verify.sh` зелёный и
коммит в `/prj/superizer`.

**Режим B** — шаги 6–12: калькулятор, shell в Unitool, активация, конвертер. В
`unitool/settings.gradle.kts`:

```kotlin
// Composite build when the library checkout is next door; Maven coordinates otherwise (CI, a
// clone without the library). SUPERIZER_FROM_MAVEN=1 forces the Maven path to prove publishing.
val superizerDir = file("../superizer")
if (superizerDir.exists() && System.getenv("SUPERIZER_FROM_MAVEN") != "1") includeBuild(superizerDir)
dependencyResolutionManagement.repositories { mavenLocal(); google { … }; mavenCentral() }
```

Зависимости в Unitool **всегда** пишутся координатами (`libs.superizer.core` →
`cx.m42.superizer:core:0.1.0`), никогда `project(":superizer:core")`. Gradle подставляет
исходники сам, если includeBuild есть. Это единственный способ, при котором режим C не требует
правки build-файлов.

Правка контракта в режиме B: меняется `core` в `/prj/superizer`, тут же видна в Unitool, оба
`verify.sh` зелёные, **два коммита** — сначала в библиотеке, потом в Unitool. `apiDump`
перегенерируется в коммите библиотеки; если BCV показал несовместимое изменение — поднять
`SuperizerContract.VERSION` и записать в CHANGELOG.

**Режим C** — конец каждого шага, где менялась библиотека, и обязательно перед шагом 13 плана:

```bash
cd /prj/superizer
./gradlew apiCheck                              # дампы совпадают с кодом
./gradlew publishToMavenLocal                   # ~/.m2/repository/cx/m42/superizer/{core,ui-theme,ui,host,testing}/0.1.0
./gradlew -p samples/consumer build             # отдельная сборка, mavenLocal only — упадёт, если артефакт неполный
cd /prj/unitool
SUPERIZER_FROM_MAVEN=1 ./scripts/verify.sh      # Unitool против опубликованного пакета
```

`samples/consumer` — это крошечный desktop-хост из одного файла, который регистрирует `TestApp`
из `cx.m42.superizer.apps:test-app` и запускает `AppContractTest`. Он ловит то, что composite
build прощает: недостающие `api`-зависимости, `consumer-rules.pro`, sources-jar, wasm-klib
без metadata.

**Версии.** `superizer.version` в `gradle.properties` библиотеки. До первого внешнего
потребителя — `0.x`, поднимается на каждом шаге плана, где менялся публичный API (по дифу
`api/*.api`). `contractVersion` — отдельное целое (02), меняется только на несовместимых правках
`core`. Unitool держит версию в каталоге и обновляет её явным коммитом «bump superizer to 0.3.0».

## 3. Порядок работы агента над шагом

1. Прочитать `README.md` заметок, шаг в 08, и те файлы 01–07, на которые шаг ссылается. Не
   переизобретать решения D1–D23; если решение мешает — записать в 09 и идти по умолчанию.
2. Определить режим (A/B/C) и папку. Сказать это в первой строке ответа.
3. Написать код и тесты шага. Тесты — до «зелёного» прогона, а не после: у каждого шага в 08 они
   перечислены.
4. `./scripts/verify.sh` в затронутой папке (обеих — в режиме B). Ничего не считается сделанным,
   пока в `build/verify/report.md` нет строки `RESULT: PASS`.
5. Посмотреть скриншоты в `build/verify/screenshots/` и `build/verify/web/` глазами (Read на
   png). Тест может быть зелёным при пустом белом экране — картинка нет.
6. Коммит с сообщением `step N: <что>`; в теле — что проверено и что пропущено (например,
   «Android: SKIPPED, нет устройства»).
7. Если шаг менял публичный API библиотеки — режим C до коммита в Unitool.

Тесты и web-мост ищут узлы по testTag `<appId>:<element>` (D39), а не по тексту — текст меняется
с языком, тег нет. Русские тексты в ассертах остаются только в сквозных тестах shell-а, где
проверяется именно перевод.

Что агент **не делает**: не добавляет Material, не добавляет навигационную библиотеку, не
тянет Ktor в `core`/`apps`, не пишет `project(":superizer:*")` в Unitool, не делает динамическую
загрузку (10), не правит `api/*.api` руками (только `apiDump`), не отключает упавший тест, не
обновляет эталон скриншота в одном коммите с кодом (§4.2).

## 4. Проверка за один запуск: `scripts/verify.sh`

Один скрипт, одинаковый по форме в обеих папках. Запускается без аргументов, пишет
`build/verify/report.md`, завершает с кодом 0 только при полном PASS. Пропуски — явные строки
`SKIPPED: <причина>`, никогда молча.

```bash
#!/usr/bin/env bash
# One run, every target, one report. Exit 0 only on PASS.
set -euo pipefail
out=build/verify; mkdir -p "$out"; report="$out/report.md"; : > "$report"
step() { echo "## $1" | tee -a "$report"; }
ok()   { echo "- PASS: $1" | tee -a "$report"; }
skip() { echo "- SKIPPED: $1" | tee -a "$report"; }

step "1. Static"
./gradlew --quiet checkDependencyRules apiCheck && ok "dependency rules, API dumps"

step "2. Unit + UI (desktop, wasm, Robolectric)"
./gradlew --quiet allTests desktopTest wasmJsTest testDebugUnitTest --continue && ok "junit → $out/junit"
cp -r build/test-results "$out/junit" 2>/dev/null || true
./gradlew --quiet koverXmlReport koverVerify && cp build/reports/kover/report.xml "$out/coverage.xml" && ok "coverage ≥ thresholds (§4.7)"

step "3. Screenshots + baseline diff"
./gradlew --quiet :fixture:desktopTest --tests '*Screenshots*'      # в Unitool: :composeApp:desktopTest
cp -r fixture/build/screenshots "$out/screenshots" && ok "$(ls "$out/screenshots" | wc -l) images"
node scripts/screenshot-diff.mjs screenshots/baseline "$out/screenshots/phone" "$out/screenshots/diff" && ok "no visual regressions"   # §4.2; FAIL при > 0.5 % пикселей

step "4. Web (wasm)"
./gradlew --quiet :fixture:wasmJsBrowserDistribution
node scripts/web-smoke.mjs fixture/build/dist/wasmJs/productionExecutable "$out/web" && ok "web-smoke"
scripts/size-budget.sh fixture/build/dist/wasmJs/productionExecutable/*.wasm wasm | tee -a "$report"   # D42: WARN при > budget×1.25

step "5. Android"
if [ -n "${ANDROID_HOME:-}" ] || grep -q sdk.dir local.properties 2>/dev/null; then
  ./gradlew --quiet :fixture:assembleDebug && ok "assembleDebug"
  if adb get-state >/dev/null 2>&1; then ./gradlew --quiet :fixture:connectedDebugAndroidTest && ok "connected"; else skip "Android device: none attached"; fi
else skip "Android SDK: not configured"; fi

step "6. Publish"
./gradlew --quiet publishToMavenLocal && ./gradlew --quiet -p samples/consumer build && ok "mavenLocal + consumer"   # только в библиотеке

echo "RESULT: PASS" | tee -a "$report"
```

Gradle-задача `verify` в корне обоих проектов делает шаги 1–4 без скрипта (для IDE и CI);
скрипт добавляет web-smoke, Android-ветвление, копирование артефактов и отчёт.

### 4.1 Что проверяют тесты по слоям

| Слой | Где | Что |
|---|---|---|
| `core` | `core/src/commonTest` (jvm + wasm) | `AppId`, `AppConfigSpec.decode` (дефолт, битый, migrate), `AppRegistry` (дубликат, `minHostContract`, `requires`), `AppHandler` (последовательность событий, Failed, ConfigRejected, снимок, restore, background) — всё на `FakeAppRuntime`, без UI. **Гоняется и на wasmJs** (`wasmJsTest` с Karma/Chromium headless) — ловит `java.*` и ServiceLoader |
| `host` | `host/src/desktopTest` | `PrefsStorageService` префиксы, `KtorNetworkService` против встроенного Ktor `MockEngine`, `QrPayloadParser`, `LocalPromoCodes`, `UnlockStore`, `ActivationService` |
| `ui` | `ui/src/desktopTest` | `AllAppsScreen` с фейковым реестром, `ServiceMenuScreen`, drawer (перенос `MenuDrawerTest`) |
| `apps/*` | `apps/<id>/src/desktopTest` | `XContractTest : AppContractTest`, логика без UI, `XInstanceTest` через `runAppTest` с `FakeNetwork`/`InMemoryStorage` |
| `fixture` / `composeApp` | `desktopTest` | сквозные: All Apps → тап → приложение → back; активация промокодом; Service Menu; `ExtensibilityTest` с `ProbeApp` (§24); `Screenshots` |
| web | `scripts/web-smoke.mjs` | см. 4.3 |
| Android | `androidInstrumentedTest` в `fixture`/`composeApp` | один тест: Activity стартует, All Apps виден, deep link `unitool://activate?…` открывает приложение. Запускается только при устройстве |

### 4.2 Скриншоты

`Screenshots.kt` переносится в `fixture` (библиотека) и остаётся в `composeApp` (Unitool), тот же
`DriveScope`. Список кадров — данные, как сейчас; целевой список для Unitool в §5. Оба viewport
(phone, play) сохраняются. Агент **смотрит** кадры после прогона, потому что проверка «есть узел
с текстом» не ловит съехавшую вёрстку.

**Эталоны (D43).** Кадры phone-viewport коммитятся в `screenshots/baseline/` обоих репозиториев.
`scripts/screenshot-diff.mjs` (pixelmatch) сравнивает свежие кадры с эталоном: расхождение больше
0,5 % пикселей — FAIL, diff-картинка кладётся в `build/verify/screenshots/diff/`. Новый или
намеренно изменённый экран — агент обновляет эталон **отдельным** коммитом «screenshots: …» с
кадром в описании, чтобы ревью видело именно картинку. Play-viewport не сравнивается: он
производный. Рендер детерминирован (headless Skia, фиксированные шрифты из `ui-theme`,
`FakeClock`) — иначе эталоны были бы бесполезны; если diff «мигает», чинится источник
недетерминизма, а не порог.

### 4.3 Web-smoke: как проверить wasm, если в DOM ничего нет

Compose рисует в canvas, Playwright не видит текста. Поэтому у хоста есть **тестовый мост**,
только в wasmJs и только когда страница открыта с `?test=1`:

```kotlin
// composeApp/src/wasmJsMain/kotlin/…/TestBridge.kt (и то же в fixture)
// window.__superizer = { destination(): string, currentApp(): string|null, events(): string[] , tap(x,y) }
```

Мост читает `Superizer.handler.states`/`shell.destination` и отдаёт JSON-строки; это ≈40 строк
`@JsExport`-функций. В production-сборке без `?test=1` объект не создаётся.

`scripts/web-smoke.mjs` (Playwright, chromium уже есть в `~/.cache/ms-playwright`):

1. Поднимает статический сервер на `dist/` (порт 8082, как dev-server).
2. Открывает Chromium с `--use-gl=angle --use-angle=swiftshader` (без этого — белый экран, README).
3. Ждёт `window.__superizer`, timeout 60 с (wasm + Skiko грузятся долго на swiftshader).
4. Проверяет: `destination() == "Home"`; консоль без `error`; скриншот `web/01-home.png`
   **не белый** (доля не-белых пикселей > 5%).
5. Открывает `?test=1&activate=<промокод SCI>` → ждёт `currentApp() == "calculator"` →
   скриншот `web/02-calculator-scientific.png`.
6. `?test=1&activate=<QR-JSON test-app>` → `currentApp() == "test-app"` → `events()` содержит
   `Activated`, `Configured`, `Active` в этом порядке.
7. Пишет `web/summary.json`; ненулевой выход при любом провале.

Этого достаточно, чтобы поймать три класса wasm-регрессий, которые JVM не видит: сборка не
стартует (нет `__superizer`), рендер сломан (белый кадр), маршрут активации не дошёл до handler-а.
Тапы по координатам оставлены в мосте (`tap(x,y)`) на случай, когда понадобится пройти сценарий
руками через Playwright MCP, но в smoke не используются — координаты хрупкие.

### 4.4 Android

В этой среде SDK есть (`local.properties`), устройства нет. `assembleDebug` — всегда;
`connectedDebugAndroidTest` — при `adb get-state`. Release-проверка (R8, подпись) остаётся в
`RELEASING.md`, она ручная и не входит в `verify.sh`. Одно, что `verify.sh` обязан ловить и на
Android без устройства: `:composeApp:assembleRelease` без keystore проходит (R8 + consumer-rules
сериализации из `core`). Добавляется в шаг 5 скрипта в Unitool.

### 4.5 Что агент оставляет после прогона

```
build/verify/
├── report.md          шаги, PASS/SKIPPED, RESULT
├── junit/             xml-результаты всех модулей
├── screenshots/       phone/ и play/ — из desktopTest
└── web/               01-home.png, 02-…png, summary.json, console.log
```

В ответе пользователю агент цитирует `report.md` целиком и прикладывает 1–2 кадра.

### 4.7 Политика покрытия (D43–D46)

**Что считается покрытым.** Kover по модулям, пороги в `koverVerify` (D44):

| Модуль | Порог (line) | Чем достигается |
|---|---|---|
| `core` | 90 % | юнит-тесты на JVM **и** wasmJs; логика без UI |
| `host` | 70 % | desktopTest на MockEngine/подделках + Robolectric (`testDebugUnitTest`) для Android-actual + wasmJsTest для wasm-actual |
| `ui`, `ui-theme` | без порога | UI-тесты и эталоны скриншотов; число не показательно |
| `apps/*` | 80 % по логике (`Calculator.kt`, `Rates`), без порога по экранам | `AppContractTest` + `XInstanceTest` |
| `fixture`, `composeApp` | без порога | сквозные тесты и скриншоты |

До шага 7 пороги — WARN в отчёте, после — FAIL. Число не цель: тест ради процента не пишется,
но модуль, ушедший под порог, объясняется в коммите.

**Платформенные actual без устройства (D45).** Android-логика тестируется Robolectric-ом в
`androidUnitTest`: разбор intent для deep link и пуш-тапа, `PrefsStorage.android`,
`ConnectivityManager` → `online`, `PushTransport.deliverTap`. Wasm-actual — в `wasmJsTest`
(Karma + headless Chromium): `PrefsStorage.wasmJs` на `localStorage`, `navigator.onLine`,
`?link=`/`?activate=` из `location.search`. Instrumented-тест на устройстве остаётся один и
дымовой. Compose-композиции под Robolectric не гоняются — только логика actual-ов.

**Обязательные наборы тестов**, которых нет в таблице 4.1:

- **Гонки handler-а** (`AppHandlerRaceTest`, `runTest` + виртуальное время): второй `launch` во
  время `Launching`; `close` во время `onLaunch`; двойной back; `PendingRoute` во время veto;
  `Background` в момент `Creating`; `reset(id)` открытого приложения. Каждый — один ожидаемый
  порядок событий, никаких «или».
- **Таблицы мусора для парсеров** (`QrPayloadParser`, deep link, промокод, payload пуша):
  пустая строка, не JSON, JSON-массив, глубокая вложенность, unicode и RTL, 64 КБ строка,
  лишние поля, `appId` с пробелами и заглавными, `schemaVersion` строкой/числом/отрицательное.
  Ожидание — конкретный `Rejected(reason)`, никогда исключение.
- **Миграции** в `AppContractTest`: фикстуры `fixtures/config-v1.json`, `fixtures/state-v1.json`
  в каждом приложении; тест «старый JSON читается текущей спекой». Приложение без миграций
  кладёт фикстуру текущей версии — тест всё равно есть.
- **Бюджет `onLaunch`** в `AppContractTest`: на подделках укладывается в 200 мс виртуального
  времени и не делает ни одного сетевого вызова до первого кадра (сеть — из `Content()`/scope).
- **Свойства i18n**: для каждого приложения таблицы ru/en имеют одинаковый набор ключей — это
  даёт интерфейс на этапе компиляции; тест проверяет только, что `pick` с незнакомым тегом
  возвращает fallback, а не бросает.

**Правила (D46), которые агент соблюдает без напоминания:**

1. Баг сначала воспроизводится падающим тестом, потом чинится; в коммите — оба.
2. `@Ignore` — это FAIL: `verify.sh` грепает `@Ignore` и `.skip(` и падает.
3. Flaky-тест чинится или удаляется в том же коммите, в котором замечен; «перезапустить» — не ответ.
4. Сквозные тесты ищут по `testTag` (D39); текст — только в тестах перевода.
5. Тест не спит (`Thread.sleep`, `delay` без виртуального времени) и не ходит в сеть.
6. Один тест — одно утверждение о поведении; имя — предложение (`aSecondLaunchWhileLaunchingIsQueuedNotDropped`).

**Чего не делаем**: мутационное тестирование, property-based для калькулятора,
instrumented-тест на каждую фичу, покрытие композиций числом.

### 4.6 CI

Тот же `verify.sh`, в GitHub Actions (D40): `verify.yml` на push/PR в обоих репозиториях;
`publish.yml` в библиотеке по тегу `v*`. Локальный PASS и CI PASS — одно и то же, скрипт один.
Бюджеты размера (D42): `scripts/size-budget.sh` сравнивает с `scripts/size-budget.json`
(первое измерение × 1.25), пишет WARN, не FAIL — агент видит рост в отчёте и объясняет его в коммите.

## 5. Что получится: Unitool после шага 14

Всё ниже — на тех же токенах `AppTheme`: белая поверхность, чёрный текст, один чёрный акцент,
цвет только для состояния. Никакого Material. Drawer «лежит под экраном», как сейчас.

**Home — «Все приложения».** Сетка плиток 2 в ряд: иконка из рукописного пака (`calculator`,
`currency`), название, под сеткой — заголовок категории («Инструменты», «Финансы»). Test App не
виден. Верхняя панель: гамбургер, «Unitool». Тап по плитке — слайд вправо в приложение. При
первом старте после смерти процесса вместо Home открывается то, что было открыто (D16).

**Drawer.** Заголовок «Меню», пункт «Все приложения», ниже — по пункту на видимое приложение,
текущее — серым (disabled, не скрыто). Футер: «Активировать» (иконка `qr`), шестерёнка,
«Версия 1.0 (abc1234)». Тапы и свайп — как сейчас.

**Калькулятор.** Тот же экран, что сегодня, внутри рамки хоста. С конфигом `mode=scientific`
(промокод `SCI`, QR или Service Menu) — подзаголовок «Инженерный» в панели и ряд `x²`, `√`,
`1/x` над цифрами. Набранное выражение переживает уход в фон и убийство процесса. Последний
результат хранится между сессиями.

**Конвертер валют.** Сумма крупно сверху (как дисплей калькулятора), под ней две плитки «из» /
«в» с кодами валют, тап по плитке — список favorites из конфига. Ниже результат и строка «Курс
ЕЦБ на 19.09.2026 · сеть» или «· кэш, офлайн» серым. Кнопка «Повторить» при полном отсутствии
данных. В фоне не опрашивает сеть (D17).

**Активировать.** Два поля: «Промокод» и «Содержимое QR» (многострочное), кнопка «Применить».
Ошибка — красной строкой под полем («Код не найден», «Это не QR активации»). Успех — переход в
приложение. Камеры нет, написано прямо: «Сканирование появится позже; вставьте текст». Deep link
`unitool://activate?…` и `?activate=` в web ведут сюда же, минуя экран.

**Настройки.** Как сейчас (язык, вибрация, о приложении) + секции приложений под их
заголовками (у конвертера: базовая валюта и favorites; у калькулятора — ничего в MVP). Семь тапов
по «Сборка …» — тост «Service Menu открыт» и переход.

**Service Menu.** В release открывается только промокодом `SERVICE`, семь тапов — в debug (D34).
Таблица приложений: id, версия, `minHostContract`, состояние, unlocked; строки
отклонённых при регистрации — с причиной серым. Под каждой — «Запустить», «С конфигом…» (JSON-
поле), «Разблокировать/Скрыть», «Disable/Enable». Ниже: поле промокода, поле QR-текста, лента
`SuperizerEvent` (последние 100, новые сверху, `ConfigRejected` — красным), лог из `LogBuffer`.
Это экран для агента и владельца, не для пользователя, и он выглядит как таблица, а не как
витрина.

**Test App** (скрыт, промокод `TEST-2026`). Одна прокручиваемая страница карточек по 07:
Identity, Config (JSON, без источника), Lifecycle (лента + `Foreground`/`Background` + счётчик),
State (текстовое поле, переживающее убийство процесса), Storage, Network («Ping» → статус/мс),
Analytics, Navigation (включая «Open unknown» → `NavigationError`), Services (список ключей хоста
и `null` для незарегистрированного), Locale, Haptics, Push/Auth (заглушки), Veto (переключатель
«Block close» → диалог хоста при back). Спека с `fallbackToDefault = false`: битый JSON из Service
Menu даёт экран ошибки хоста, а не тихий default.

**Целевой список скриншотов** (`Screenshots.kt` в Unitool, оба viewport):

| Кадр | Язык | Драйв |
|---|---|---|
| 01-all-apps | ru | — |
| 02-menu | ru | открыть drawer |
| 03-calculator | ru | тап «Калькулятор» |
| 04-calculator-scientific | ru | Активировать → `SCI` |
| 05-converter | ru | тап «Конвертер валют» (FakeNetwork с фиксированными курсами) |
| 06-converter-offline | ru | то же, `FakeNetwork.fail` + кэш |
| 07-activate | ru | drawer → Активировать |
| 08-settings | ru | drawer → шестерёнка |
| 09-settings-en | en | то же |
| 10-service-menu | ru | 7 тапов по сборке |
| 11-test-app | ru | Service Menu → test-app → Запустить |

Из них 01, 03, 05, 08 идут в листинг Play (замена нынешних пяти).

**Definition of Done всего проекта** — четыре факта, каждый проверяется `verify.sh`:

1. `ExtensibilityTest`: `ProbeApp` виден и запускается без правок в библиотеке.
2. `samples/consumer` собирается из mavenLocal.
3. Unitool с `SUPERIZER_FROM_MAVEN=1` зелёный.
4. Web-smoke проходит активацию через `?activate=` в wasm.

## 6. Первые команды (шаг 1 плана, режим A)

```bash
mkdir -p /prj/superizer && cd /prj/superizer && git init
# скелет: settings, build-logic с тремя плагинами, пустые модули, fixture с одним экраном «Home»,
# samples/consumer, scripts/verify.sh, scripts/web-smoke.mjs (npm i -D playwright в scripts/ или
# npx с закэшированным chromium-1243), api/ с пустыми дампами
./scripts/verify.sh            # PASS на пустом скелете — точка отсчёта
git commit -m "step 1: skeleton, verify pipeline"
```

Пока библиотека пуста, Unitool не трогается: его `verify.sh` появляется на шаге 7 вместе с
`SuperizerShell`, до этого действует нынешний `./gradlew :composeApp:desktopTest`.
