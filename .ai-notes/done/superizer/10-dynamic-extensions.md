# 10 — Динамическая загрузка приложений («пакеты расширения» в рантайме)

> **СТАТУС: ИДЕИ, НЕ ПЛАН.** Всё в этом файле — разбор возможностей и ограничений, записанный,
> чтобы не потерять. **Делать это мы пока не будем.** В MVP и ближайших фазах приложения
> подключаются только как compile-time Gradle-модули (Tier 0, см. 02–08). Единственное, что из
> этого файла влияет на текущую работу, — два бесплатных задела в разделе «Что это меняет»
> (пункты 1 и 3); остальное не проектируется и не оценивается по срокам.

Вопрос: можно ли вынести приложения в отдельные wasm-сборки (или иначе) и подгружать их в
хост как расширения, не пересобирая хост.

Короткий ответ: **в тот же процесс/Compose-дерево — нет** (Kotlin/Wasm не умеет динамически
линковать отдельно скомпилированные модули, а на Android Play запрещает докачивать исполняемый
код); **в изолированный web-контейнер с мостом — да**, и приложение может быть при этом
написано на Kotlin + Compose и собрано в wasmJs. Контракт `SuperizerApp`/`AppRuntime` для этого не
меняется: динамическое расширение — это просто ещё одна реализация `SuperizerApp` внутри хоста и
ещё одна реализация `AppRuntime` внутри расширения.

## Почему «просто загрузить wasm-модуль в Compose-хост» не работает

| Платформа хоста | Что мешает |
|---|---|
| **wasmJs (браузер)** | Kotlin/Wasm компилирует каждый проект в один самодостаточный `.wasm` + JS-glue. Динамического линкования/загрузки чужого Kotlin-модуля в работающий инстанс нет (Kotlin 2.4): у отдельно собранного приложения будет **свой** Compose runtime, свой Skiko, свой canvas. Два Compose-дерева в одном рантайме не объединить. |
| **Android** | Технически `DexClassLoader` грузит .dex/.apk из сети, и Compose через один classloader работает. Но Google Play Developer Policy («Device and Network Abuse») запрещает приложению скачивать и исполнять код (dex/jar/so) не из Play. Разрешённые исключения: **Play Feature Delivery** (dynamic feature modules) и **код в интерпретаторе/VM с ограниченным доступом** (JavaScript/wasm в WebView). Для sideload-сборок ограничения нет. |
| **Desktop JVM** | Работает: `URLClassLoader` + jar-плагин, скомпилированный против тех же `superizer:core`/Compose (`compileOnly`, без бандлинга runtime). Это модель плагинов IntelliJ. Ограничение только одно — Compose runtime и compiler одной версии у хоста и плагина. |

Вывод: «одна архитектура для всех трёх» с загрузкой нативного кода не существует. Есть три
уровня, и они комбинируются.

## Уровни

### Tier 0 — compile-time модули (MVP, уже спроектировано)

Gradle-модули `apps/*`, регистрация в хосте, один бинарник. Это ровно то, чем являются
backend-приложения Adminizer: npm-пакеты, устанавливаемые при сборке, а не скачиваемые в
рантайме. Работает на всех платформах, проходит Play, лучшая производительность.

### Tier 1 — платформенно-санкционированная динамика (тот же код, тот же contract)

- **Desktop**: `PluginLoader(dir)` — сканирует `~/.unitool/plugins/*.jar`, `URLClassLoader` с
  родителем = classloader хоста, `ServiceLoader<SuperizerApp>` (на JVM он есть) → `registry.register`.
  Плагин собирается из того же `apps/<id>` модуля задачей `pluginJar` (`compileOnly` на
  `superizer:*` и Compose). Стоимость: ~100 строк в `host` (jvm source set) + Gradle-задача.
- **Android**: Play Feature Delivery — `apps/<id>` как `com.android.dynamic-feature` модуль,
  `SplitInstallManager.startInstall("calculator")`, после установки — `Class.forName(
  "cx.m42.apps.calculator.CalculatorApp")` (одно keep-правило) или `ServiceLoader`. Тот же процесс,
  тот же Compose. **Но**: фичи входят в тот же App Bundle и версионируются вместе с хостом —
  это «скачать по требованию», а не «независимый релиз расширения».
- **Android вне Play (enterprise/sideload)** — Tier 1-enterprise: `plugin.zip` с `.dex`, собранным из
  того же `apps/<id>`, `DexClassLoader` с родителем = classloader хоста. Настоящий Compose, тот же
  контракт. Подробнее в разборе ниже.
- **wasmJs хост**: нет варианта в этом уровне.

Полезно для размера установки и enterprise-desktop, но не даёт независимого цикла релизов на
мобильных и не работает в браузере.

### Tier 2 — web-расширения: изолированный контейнер + мост (то, что спрашивается)

Расширение = статический web-бандл (`index.html` + `*.wasm` + `*.js` + манифест), лежащий на
CDN/сервере. Хост показывает его в **web-контейнере**: `WebView` на Android, `<iframe>` в
wasmJs-хосте, на desktop — встроенный браузер. `AppRuntime` для расширения реализован **внутри
бандла** как мост к хосту (JSON-RPC через `postMessage` / `addJavascriptInterface`), а хост
исполняет запросы своими сервисами.

```
хост (Compose)                                   расширение (в WebView / iframe)
─────────────────────────                        ─────────────────────────────────
WebExtensionApp : SuperizerApp<WebExtConfig>     CalculatorApp : SuperizerApp<CalculatorConfig>   ← тот же код, что в apps/calculator
   └ launch() → WebExtensionInstance                 └ launch(runtime = BridgeRuntime, config)
        └ Content() = WebContainer(url)                    └ Content() — Compose на canvas в бандле
        └ BridgeServer(runtime)  ◄── JSON-RPC ──►  BridgeRuntime : AppRuntime
              storage/network/navigation/…               storage.get → {"m":"storage.get","k":…}
```

Это буквально §16–18 спецификации: «Same App, Different Hosts». Бандл — это ещё один хост
(`WebExtensionHost`) с `HostRuntimeFactory = BridgeRuntimeFactory`. Приложение `apps/calculator`
собирается **дважды из одного исходника**: как Gradle-модуль в хост (Tier 0) и как wasmJs-бандл
(Tier 2) — задачей `extensionBundle` в convention-плагине. Разработчик приложения не пишет
ничего специального.

И это же — модель Adminizer на фронте: `moduleComponent` там — отдельно собранный `*.es.js`,
который панель подгружает `import()`-ом в рантайме. Здесь единица подгрузки — бандл в контейнере,
потому что у Compose-хоста нет DOM, в который можно «импортировать компонент».

#### Что даёт

- Независимый релиз расширений на **всех** платформах, без пересборки и без обновления в Play.
- Проходит политику Play (код в WebView — разрешённое исключение).
- Изоляция сильнее, чем у Tier 0: мост — естественная граница прав (allowlist сети на
  расширение, отдельный storage-namespace, никакого доступа к процессу).
- Расширение может быть и не на Kotlin — любой web-стек, реализующий протокол моста.

#### Чего стоит

| Цена | Подробнее |
|---|---|
| Второй Compose-рантайм на расширение | Kotlin/Wasm + Skiko ≈ несколько МБ на скачивание, десятки МБ памяти. Кэшировать бандлы (Service Worker в iframe / `WebView` cache), не держать больше одного открытого (D4 и так это даёт) |
| Desktop-контейнер | JCEF/KCEF — Chromium ≈ 100+ МБ, отдельная загрузка; JavaFX `WebView` (WebKit) — WebGL не гарантирован, Skiko может не подняться. В agentiz desktop просто открывает системный браузер — для расширений это неприемлемо. Реалистично: KCEF, скачиваемый при первом расширении, либо desktop без Tier 2 на первое время |
| Протокол моста | Версионировать (`bridgeVersion`), сериализовать все `AppRuntime`-вызовы в JSON. Интерфейсы в 04 намеренно строковые/JSON-дружественные (`NetworkResponse(status, body)`, `StorageService` по строкам) — это уже сделано с прицелом сюда |
| Пересечение с Compose-хостом | Нативный `WebView`/`iframe` рисуется **над** Compose (в agentiz это уже решено: `LocalDrawerProgress` прячет контейнер при открытом drawer). Drawer-анимация «карточка уезжает» с WebView не сработает — при открытии drawer контейнер скрывается, как в agentiz |
| Доверие к бандлу | Манифест с `sha256` каждого файла и подписью ключом хоста; хост проверяет перед загрузкой; `Content-Security-Policy` в контейнере; сеть — только через `runtime.network` (мост), прямые `fetch` из бандла блокируются allowlist-ом |
| Back, chrome, haptics | Всё через мост: `chrome.set(...)` от расширения → `AppChrome` у `WebExtensionInstance`; back от хоста → сообщение в расширение → его `SystemBackHandler`-эквивалент |

#### Манифест расширения

```json
{
  "schemaVersion": 1,
  "id": "calculator",
  "version": "1.2.0",
  "bridgeVersion": 1,
  "title": { "en": "Calculator", "ru": "Калькулятор" },
  "icon": { "letter": "C" },
  "hidden": false,
  "entry": "index.html",
  "files": { "index.html": "sha256-…", "composeApp.wasm": "sha256-…", "composeApp.js": "sha256-…" },
  "requires": ["qr-scanner"], "deepLinks": ["rate"], "pushTopics": ["rates"],
  "networkHosts": ["api.frankfurter.app"],
  "signature": "…"
}
```

Каталог расширений — JSON-список манифестов по URL (`extensions.json`); хост при старте
(и по кнопке в Service Menu) читает его и регистрирует по `WebExtensionApp(manifest)` на каждый.
Метаданные для All Apps берутся из манифеста, поэтому плитка есть до загрузки бандла.

#### Протокол моста (эскиз)

Запрос из расширения: `{"id": 7, "m": "storage.get", "p": {"key": "state"}}`;
ответ хоста: `{"id": 7, "r": "…"}` или `{"id": 7, "e": {"code": "denied", "message": "…"}}`.
События от хоста: `{"ev": "back"}`, `{"ev": "locale", "p": {"lang": "ru"}}`, `{"ev": "close"}`.
Методы — один к одному с интерфейсами из 04: `storage.get/set/remove/keys`, `network.request`,
`logger.*`, `analytics.event/screen`, `navigation.openApp/close/openSettings/openUrl`,
`locale.get`, `haptics.tick`, `push.subscribe/unsubscribe`, `auth.session`, `apps.list/get`,
`chrome.set`, `lifecycle.ready` (расширение сообщает, что `onLaunch` завершён → хост показывает контейнер).

Транспорт: Android — `WebView.addJavascriptInterface` + `evaluateJavascript`; wasmJs —
`window.postMessage` между родителем и iframe с проверкой `origin`; desktop — JS-биндинг KCEF.

### Tier 3 — wasm как sandbox для логики + декларативный UI, который рендерит хост

Единственный способ получить **нативный Compose** от динамически загруженного кода на Android в
рамках политики Play. Плагин — wasm-модуль без UI-фреймворка; хост исполняет его во встроенном
рантайме и даёт ему import-функции (`host.storage`, `host.network`, `host.ui.render`, …). UI —
JSON-дерево (Section / Text / Button / TextField / Toggle / List / Row), которое хост рисует
компонентами из `superizer:ui`. Модель Extism / Shopify Functions / Figma plugins.

Два подводных камня, которые надо знать до проектирования:

1. **`host.ui()` — это не функция, это продукт.** Декларативный UI-DSL с версионированием,
   состоянием, событиями (`onClick` → сообщение в плагин → новое дерево). Основная стоимость
   Tier 3 — здесь, а не в рантайме.
2. **Kotlin/Wasm ≠ «обычный wasm».** Kotlin эмитит WasmGC + exception-handling proposals.
   Браузеры это умеют; встраиваемые рантаймы — не все: wasmtime — да; **Chicory** (чистый JVM,
   единственный удобный для Android без JNI) — поддержку GC проверить на актуальной версии;
   wasmer — сомнительно. Rust/C/AssemblyScript дают «плоский» wasm (линейная память), который
   берёт любой рантайм. Значит «плагин на Kotlin → wasm → рантайм в Android-приложении» — самое
   слабое звено, проверяется прототипом (см. ниже), а не предполагается.

Где Tier 3 дешёв: **wasmJs-хост** — плагин грузится браузерным `WebAssembly.instantiate` с
импортами из хоста, никакого встроенного рантайма. Где дорог: Android/desktop (рантайм + JNI
или Chicory) и везде — UI-DSL.

Для `wasmWasi`-таргета Kotlin (experimental) — без Compose, без JS interop — это единственный
кандидат для Kotlin-плагинов такого рода; альтернатива — писать плагины на Rust.

### Разбор варианта «Kotlin/Native → `.so` + `dlopen`» (предложен отдельно)

Технически верно для Android вообще, но для нашего стека не подходит:

- Compose на Android живёт в ART, не в Kotlin/Native. В `.so` из Kotlin/Native **нет Compose** —
  плагин может быть только логикой, и мы опять в Tier 3 (нужен `host.ui()`), но с C-ABI вместо
  wasm-импортов: никаких Kotlin-объектов, Flow и корутин через границу, всё руками в C-структуры.
- В wasmJs-хосте `.so` не существует; на desktop — JNI с теми же ограничениями.
- В APK `.so` не даёт ничего по сравнению с Gradle-модулем, только ABI-разделение и потерю UI;
  скачиваемый `.so` — запрещён Play.
- Для sideload/enterprise на Android **гораздо лучше `.dex/.jar` через `DexClassLoader`**:
  плагин, собранный против `superizer:core` теми же версиями Compose, работает в том же
  classloader-дереве и рендерит настоящий Compose. Это desktop-модель jar-плагинов (Tier 1),
  перенесённая на Android — вне Play. Фиксируем как **Tier 1-enterprise**: та же задача
  `pluginJar` + `d8` → `plugin.dex` в `plugin.zip` с манифестом и подписью; загрузчик в
  `host` (android source set) — зеркало desktop `PluginLoader`.

Согласны с выводом того варианта: для динамических плагинов ABI — это «manifest + стабильный
host API», а не Kotlin-классы. Но для компилируемых приложений (Tier 0/1) привязка к Kotlin API
`superizer:core` — норма: он версионируется как npm-пакет.

## Унификация: один JSON-протокол хоста для всех динамических транспортов

Tier 2 и Tier 3 различаются **только транспортом и тем, кто рисует UI**. Протокол, манифест,
подпись и permissions делаем общими — `HostProtocol` в `superizer:core` как набор
`@Serializable` сообщений:

```
                       HostProtocol (JSON-RPC)
   storage.* · network.request · logger.* · analytics.* · navigation.* · locale.get ·
   haptics.tick · push.* · auth.session · apps.* · chrome.set · lifecycle.ready ·
   ui.render(tree) / ui.event(id, payload)      ← только для Tier 3
                          │
   ┌──────────────────────┼──────────────────────┬───────────────────────────┐
 Tier 0 / 1 / 1-ent    Tier 2                  Tier 3                     wasmJs-хост, Tier 3
 Kotlin API напрямую   postMessage /           wasm import-функции        WebAssembly.instantiate
 (протокол не нужен)   addJavascriptInterface  (Chicory / wasmtime)       с импортами из хоста
```

Что это даёт: `BridgeServer` в `host` — один, транспорты — адаптеры; `FakeAppRuntime` в
`testing` может гонять протокол без контейнера; расширение на любом языке реализует один
документ. `ui.render` — единственное расширение протокола, которого нет у `AppRuntime`, и оно
живёт отдельным версионируемым `UiSchema`.

## Что это меняет в текущем проекте (03–05)

Минимум, и всё — расширение контракта, не ломка:

1. **`AppRegistry` допускает позднюю регистрацию** (`register` после старта → событие
   `Registered`; All Apps наблюдает `registry.apps: StateFlow<List<…>>`). Было «пишется один раз
   при старте» — становится «обычно при старте».
2. **`SuperizerApp.manifest` уже `@Serializable`** (D47): манифест расширения ниже = `AppManifest`
   + поля доставки (`bridgeVersion`, `entry`, `files`, `signature`). `permissions.network` = `networkHosts`,
   `permissions.services` = `requires`. Отдельного формата и конвертера не нужно.
3. **`AppRuntime` остаётся JSON-дружественным** — правило для 04: не добавлять в интерфейсы
   сервисов типы, которые нельзя сериализовать (никаких `KSerializer`, `Painter`, платформенных
   классов). Это уже соблюдено; фиксируем как правило ревью.
4. **`ui` получает `WebContainer` expect/actual** — перенос `EmbeddedDashboard` из agentiz
   (Android `WebView`, wasm `iframe`, desktop — заглушка «откроется в браузере» до KCEF) плюс мост.
5. **Convention-плагин: задача `extensionBundle`** для `apps/*` — сборка wasmJs-дистрибутива +
   генерация манифеста с хэшами. Одна и та же `apps/calculator` → и `project(...)`-зависимость, и
   `build/extension/calculator/`.
6. **`host`: `WebExtensionApp`, `BridgeServer`, `ExtensionCatalog`** — новый пакет
   `cx.m42.superizer.host.extensions`. `BridgeRuntime` (клиентская сторона) — новый модуль
   `superizer:bridge` (wasmJs-only), от которого зависит бандл, а не приложение.

Ничего из этого не входит в MVP. Но пункты 1 и 3 — бесплатны сейчас и дороги потом, поэтому
делаем их в MVP; остальное — фаза «Extensions» после шага 14 плана в 08.

## Рекомендация (на будущее, не для текущей работы)

- MVP: Tier 0, с пунктами 1 и 3 выше.
- Следующая фаза: Tier 2 на Android + wasmJs-хосте (там контейнеры дешёвые и уже есть в agentiz),
  desktop — Tier 1 (jar-плагины) как временная замена, KCEF — когда понадобится.
- Проверка идеи до фазы (два прототипа по дню каждый, независимы):
  1. Tier 2: собрать `apps/test-app` в wasmJs-бандл, открыть в `WebView` внутри Unitool с мостом
     из трёх методов (`storage.get/set`, `chrome.set`) — размер/память на телефоне.
  2. Tier 3: `wasmWasi`-модуль на Kotlin с одной функцией + Chicory в Android-сборке — грузится ли
     WasmGC-модуль вообще. Если нет — Tier 3 либо на Rust, либо только в wasmJs-хосте.
  Результаты замеров решают: Tier 2 (память ок) → делаем его; Tier 3 (рантайм ок и нужен нативный
  UI) → проектируем `UiSchema`; иначе — Tier 1-enterprise для sideload и Tier 0 для Play.
