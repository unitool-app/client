# 16 — Провижининг приложения: конфиг из QR как данные, хранимые хостом, экспорт файлом, автоматический режим по ссылке

Дата: 2026-09-21. План, не отчёт. Код по нему ещё не написан; всё ниже — результат чтения
`/prj/superizer` (core, host, ui, testing, fixture) и `/prj/unitool` в их текущем состоянии.
Ревизия 7а (тот же день, после голосовых уточнений владельца): хранилище — протокол в ядре,
реализацию даёт хост; автоматический режим через системный сканер QR и https-ссылку;
трёхсторонняя связка «сервер приложения → сервер Unitool → приложение в супераппе».

## 1. Задача словами владельца

Через ядро Superizer нужно **доставать конфиг приложения** — тот, что приходит через QR-код:
сервер, тип приложения, внутри — креды и прочее. Скрытые приложения будут ходить на разные
серверы, авторизоваться, и эти настройки должны **использоваться при каждом открытии**. Плюс
метод, который **экспортирует настройки в один файл** (отправить по почте), и **импорт** файла в
другом Super App на том же фреймворке. Файл типизирован: внутри указано, какой это тип
приложения (`appId`), и на встроенные приложения накладывается конфиг.

Уточнения:

- **Автоматический режим.** Сервер приложения выдаёт пользователю QR. Пользователь сканирует
  его *системным* сканером (камерой), это ссылка; она открывает Unitool, приложение
  разворачивается и настраивается само, без экрана Activate. Приложение — только UI; контракт
  работы с его API у каждого свой и публикуется отдельно.
- **Трёхсторонняя связка.** Отдельный сервер Unitool принимает пуши от сервера приложения и
  доставляет их приложению внутри супераппа; приложение по пушу открывает нужный экран.
- **Ядро не знает, куда сохранять.** Базу данных в Super App не тащим. В ядре — *протокол*:
  «вот эти данные пришли», «при следующей загрузке хост эти данные предоставляет». Где и как
  хост их держит — его дело.

## 2. Что есть сейчас и где дыра

Что уже сделано и переиспользуется без изменений:

| Есть | Где | Что даёт |
|---|---|---|
| `AppConfig` = `JsonObject`, `AppConfigSpec<C>` с `decode`/`encode`, `schemaVersion`, `migrate` | `core/app/AppConfig.kt` | Типизация внутри приложения, JSON снаружи (D2, D18) |
| QR-payload `{"schemaVersion":1,"type":"app_activation","appId":…,"config":{…}}` и URL-форма `unitool://activate?a=…&c=…` | `host/activation/Activation.kt` `QrPayloadParser` | Парсер с типизированными отказами, ничего не бросает |
| `Activation(appId, config, unlock)` → `ActivationService.apply` → `reveal` + `handler.launch(force=true)` | `host/activation`, `host/SuperizerHost.kt` | Одна дверь для QR / промокода / deep link / тапа по пушу |
| `PendingRoute` + `route.deliver(url)` из `MainActivity.onCreate/onNewIntent`, `--activate=`, `?activate=` | `host/push/PushRouter.kt`, входы Unitool | Ссылка, пришедшая до первой композиции, доходит до shell |
| `intent-filter` на `unitool://` с `singleTask` | `composeApp/androidMain/AndroidManifest.xml` | Своя схема уже открывает хост |
| `UnlockStore`, `HomeStore`, `PrefsSnapshotStore`, `TopicStore` на `SafePrefs` | `host/storage/Stores.kt` | Образец маленького store поверх `PrefsStorage`, ключи `host.*` |
| `PrefsStorageService(appId)` с префиксом `app.<id>.` и `erase(id)` | там же | «Своя директория» приложения (D35) |
| `SessionSnapshot(appId, config, state, savedAt)` | `core/registry/AppHandler.kt` | Конфиг уже сериализуется и переживает процесс — но на сутки и только для открытого приложения |
| `PushRouter` + payload схемы 1 (`appId`, `link`, `data`), `DeviceRegistrar` (пока лог) | `host/push/PushRouter.kt`, 13 | Дорога «пуш → deep link → экран приложения» готова, сервера нет |

**Дыра — одна, и она ровно про то, что просит владелец.** Конфиг активации используется один раз:

1. `ActivationService.apply` → `handler.launch(appId, config, force = true)` — конфиг ушёл в `launch`.
2. Пользователь закрывает приложение (back) → `SuperizerShell.closeCurrent` → `handler.close()`
   → `closeLocked(keepSnapshot = false)` → снимок **стёрт**.
3. Пользователь открывает приложение с Home → `SuperizerShell.open(id)` → `handler.launch(id)`
   → `config = AppConfig.Empty` → приложение стартует **на дефолтах**.

Скрытое приложение, активированное QR-кодом с сервером и кредами, после первого же закрытия
ничего о сервере не знает. Единственное место, где конфиг переживает процесс, — `SessionSnapshot`,
и это снимок *экрана*, а не настройки. Экспорта нет.

Вторая дыра — **для автоматического режима**: системный сканер открывает только `https://`,
а парсер и intent-filter знают только `unitool://`. QR с `unitool://activate?…` из камеры
Android/iOS не откроется, и с сайта его не запустишь.

Третья — **для связки**: `DeviceRegistrar` шлёт (в лог) `appIds` всей сборки, а не то, что на
устройстве *провижинено* и *для кого*; сервер Unitool не сможет адресовать пуш «приложению X
клиента Y на этом устройстве».

## 3. Решения

Нумерация продолжает 01/15 (последнее — D48).

- **D49. Провижининг — запись, которую хранит хост по протоколу ядра.** В `core` —
  `data class Provisioning(appId, config, audience, at)` и интерфейс `ProvisioningStore`
  (`save`/`load`/`all`/`clear`, все `suspend`). Ядро знает *что* и *когда* — «данные пришли»,
  «дай данные при запуске» — и не знает *где*. Реализацию отдаёт хост через
  `SuperizerBuilder.provisioning(store)`. В `host` лежит реализация по умолчанию
  `PrefsProvisioningStore` (ключ `host.provisioning.<appId>`), чтобы Unitool ничего не писал;
  хост с keychain или своей БД подставляет свою. Это `SnapshotStore` один в один: интерфейс в
  `core`, `PrefsSnapshotStore` в `host`, `InMemorySnapshotStore` в `testing`.

- **D50. Три слоя конфига при запуске: `default ⊕ stored ⊕ transient`.** Каждый `launch`
  проходит через `AppHandler.launchLocked`; там эффективный конфиг = сохранённый (D49) с
  наложенным поверх тем, что передали в `launch(id, config)`. Слияние поверхностное — то же
  правило, что `mergeWithDefault` в `AppConfigSpec`, и по той же причине (вложенный объект
  иначе нельзя очистить). Так `unitool://app/currency-converter/rate?pair=USD-RUB` кладёт
  `from`/`to` **поверх** сохранённого `ratesUrl`, а не вместо него.

- **D51. Что провижинит, а что нет.** Провижинит только *активация с `unlock = true`* — QR,
  промокод, `activate`-ссылка, импорт (D52): это «провижининг», и он **заменяет** запись целиком,
  не сливает — QR несёт полный набор, и слить старый сервер с новым — худшее из возможного.
  Не провижинят: deep link `app/<id>/<path>` (`unlock = false`), пуш, `navigation.openApp(id, config)`
  из другого приложения, «Run with config» из Service Menu — всё это разовые наложения.
  Провижининг приложения X **стирает снимок сессии X** (`forgetSnapshot`): снимок хранит свой
  `config`, и вчерашний сервер не должен вернуться через вчерашний экран.

- **D52. Файл экспорта = пачка активаций.** Формат `app_config_bundle`, schema 1 (§5). Каждая
  запись — ровно `appId` + `config` + `audience` + `unlock` из `Activation`. Импорт = для каждой
  записи: приложение зарегистрировано → `save` + `reveal` (если `unlock`), **без запуска**;
  не зарегистрировано → `skipped(UnknownApp)`; конфиг не декодируется спекой приложения →
  записывается всё равно, `ConfigRejected` при первом запуске — как при активации (D18). Поле
  `host` в файле **информационное**: «развернуть в другом супераппе» — требование, и сверять
  хост значит его нарушить. Матчинг только по `appId`.

- **D53. Один вход для текста.** Бандл принимается везде, где принимается QR-текст: поле
  «Вставить содержимое QR» на Activate, `?activate=` в web, `--activate=` на desktop, ссылка из
  камеры. `QrPayloadParser` различает `type`: `app_activation` → `Success`, `app_config_bundle` →
  новый вариант `ActivationResult.Bundle(bundle)`. Activate на `Bundle` вызывает импорт и
  показывает отчёт; shell на `Bundle` из `route` — тоже.

- **D54. Файл — через `expect`/`actual` в `host`, с текстовым запасным путём.**
  `expect object FileExchange { suspend fun saveText(name, mime, text): Boolean; suspend fun openText(): String? }`.
  Android — SAF через launcher из `MainActivity`; desktop — `java.awt.FileDialog`; web — `Blob` +
  `<a download>` и `<input type="file">`. В desktop-тестах не вызывается: экран экспорта
  **всегда** показывает текст и «Скопировать», импорт **всегда** умеет «Вставить». Файл — второй
  кнопкой, как Activate живёт без камеры (D7).

- **D55. Секреты: в первой версии файл открытый, и экран об этом говорит.** В `commonMain` нет
  криптографии; тащить зависимость ради v1 — рано. Шифрование паролем — вторая фаза (§9),
  формат зарезервирован (`"encrypted": {…}` вместо `"apps"`). Хранение на устройстве — как у
  всего хоста (`MODE_PRIVATE`, `~/.superizer/settings.properties`, `localStorage`); для desktop и
  web это открытый текст — и именно поэтому D49 делает store заменяемым.

- **D56. `reset` стирает провижининг, `lock` — нет.** D35: reset — «всё, что приложение
  когда-либо имело, стёрто», теперь это пять хранилищ. Lock — «обратно за активацию»; запись
  остаётся, и `unlock` из Service Menu без нового QR открывает приложение на прежнем сервере.

- **D57. Приложение получает конфиг тем же каналом, что и сейчас.** `launch(runtime, config: C)`
  — единственный вход, и после D50 в него *всегда* приходит провижинённый конфиг. `AppRuntime`
  не меняется, `SuperizerContract.VERSION` остаётся 1. Авторизация у своего сервера — дело
  приложения по его контракту: адрес и креды из `config`, токен сессии — в `runtime.storage`
  (рецепт в docs/09). Конфиг в `setup(ctx)` — вопрос 23, по умолчанию нет; D60 делает его ненужным
  для пушей.

- **D58. События.** `Provisioned(appId, audience)`, `Deprovisioned(appId)`,
  `BundleImported(applied, skipped)`, `BundleExported(count)`. Через `SuperizerEvent`.

- **D59. Автоматический режим = та же `activate`-ссылка, но `https://`.** Хост объявляет
  `SuperizerBuilder.linkDomain("app.unitool.example")`; парсер принимает
  `https://<domain>/activate?a=…&c=…` и `https://<domain>/app/<id>/<path>?…` как эквивалент
  `unitool://…` (нормализация в `fromDeepLink` и `QrPayloadParser`). На Android — второй
  `intent-filter` с `autoVerify="true"` на `https` + домен + `pathPrefix` `/activate` и `/app/`,
  и `/.well-known/assetlinks.json` на домене. Тогда **одна и та же ссылка**: установлен Unitool →
  открывается он (App Link, без диалога выбора); не установлен → браузер открывает по этому URL
  **wasm-сборку Unitool**, `main.kt` читает `pathname` + `search` и подаёт в `route.deliver` — то же
  приложение с тем же конфигом, в браузере. Ничего в ядре, кроме нормализации, менять не нужно:
  `apply` → провижининг (D51) → `reveal` → `launch` — «развернулось и настроилось само».
  Закрывает вопрос 20 из 09; нужен домен (§9).

- **D60. Адресат пуша — `audience` в конверте активации, не в конфиге.** Хост конфиг не читает
  (D2), но серверу Unitool надо знать, «приложению X какого клиента» слать пуш. Поэтому в
  payload активации рядом с `config` — необязательное поле `audience` (строка, непрозрачная для
  хоста: tenant, account, что угодно, что сервер приложения сам зашил в QR). Хранится в
  `Provisioning`, уходит в регистрации устройства (D61), в экспорт/импорт. Приложению не
  показывается и не нужно. В URL-форме — параметр `au`.

- **D61. Регистрация устройства сообщает, что провижинено.** `DeviceRegistrar` объединяет
  `PushTransport.token`, `AuthService.session` и **`ProvisioningPort.all`**: тело регистрации —
  `{token, platform, lang, hostVersion, apps: [{appId, audience}]}` только по провижинённым
  приложениям; повтор при смене токена **и при смене провижининга**. Сервера по-прежнему нет —
  остаётся лог, но форма тела и триггеры фиксируются сейчас, чтобы сервер Unitool писался под
  них. Сама дорога «пуш → `link` → экран приложения» — 13, без изменений.

Версии: библиотека `0.1.0 → 0.2.0`, contract остаётся **1**: `Superizer` реализует только `host`,
`ActivationResult` матчат только `ui` и тесты, контракт приложения (`SuperizerApp`,
`AppInstance`, `AppSetupContext`, `AppRuntime`) не тронут. ABI-дампы — `updateLegacyAbi`.

## 4. API

### `core`

```kotlin
// core/src/commonMain/kotlin/cx/m42/superizer/provisioning/Provisioning.kt
package cx.m42.superizer.provisioning

/** What arrived for one app and must be there at the next launch (D49). The host keeps it; the core only says when. */
@Serializable
public data class Provisioning(
    val appId: AppId,
    val config: AppConfig,
    /** Who this install is, as far as the app's server is concerned (D60). Opaque to the host and to the app. */
    val audience: String? = null,
    val at: Long,
)

/**
 * The protocol between the core and wherever a host keeps provisioning (D49): prefs today, a
 * keychain or a database tomorrow, a map in a test. Every call is main-safe, like every service.
 */
public interface ProvisioningStore {
    public suspend fun save(record: Provisioning)
    public suspend fun load(id: AppId): Provisioning?
    public suspend fun all(): List<Provisioning>
    public suspend fun clear(id: AppId)
}

/** N activations in one document (D52). `host` is informational: matching is by app id only. */
@Serializable
public data class ConfigBundle(
    val schemaVersion: Int = SCHEMA_VERSION,
    val type: String = TYPE,
    val host: String? = null,
    val exportedAt: Long,
    val apps: List<BundledApp>,
) {
    public companion object {
        public const val SCHEMA_VERSION: Int = 1
        public const val TYPE: String = "app_config_bundle"
        public const val FILE_EXTENSION: String = "superizer.json"
        public const val MIME: String = "application/json"
        /** Typed rejection, never a throw: the input is a file somebody else produced. */
        public fun parse(text: String, json: Json = Json): Result<ConfigBundle>
        public fun ConfigBundle.encode(json: Json = Json): String
    }
}

@Serializable
public data class BundledApp(
    val appId: AppId,
    val config: AppConfig,
    val audience: String? = null,
    val unlock: Boolean = true,
)

public sealed interface ImportResult {
    public data class Applied(val applied: List<AppId>, val skipped: Map<AppId, Reason>) : ImportResult
    public data class Rejected(val reason: ActivationResult.Reason) : ImportResult
    public enum class Reason { UnknownApp }
}
```

```kotlin
// core/src/commonMain/kotlin/cx/m42/superizer/Superizer.kt — новый порт
public interface Superizer {
    // …
    public val provisioning: ProvisioningPort
}

/** What the host keeps about each app's provisioning (D49) and the file it turns into (D52). */
public interface ProvisioningPort {
    /** A cache over the store, refreshed by every write here; the shell and the Service Menu observe it. */
    public val records: StateFlow<Map<AppId, Provisioning>>
    /** Replaces, never merges (D51). Also forgets the app's session snapshot. */
    public suspend fun provision(id: AppId, config: AppConfig, audience: String? = null)
    public suspend fun deprovision(id: AppId)

    /** Every provisioned app, or only [ids]. `unlock = true` iff the app is unlocked now. */
    public suspend fun export(ids: Set<AppId>? = null): ConfigBundle
    public suspend fun import(bundle: ConfigBundle): ImportResult.Applied
    /** parse + import — the Activate screen and the platform entry points. */
    public suspend fun import(text: String): ImportResult
}
```

```kotlin
// core/src/commonMain/kotlin/cx/m42/superizer/activation/Activation.kt
public data class Activation(
    val appId: AppId,
    val config: AppConfig = AppConfig.Empty,
    val unlock: Boolean = true,
    /** D60. Carried next to the config, never inside it. */
    val audience: String? = null,
)

public sealed interface ActivationResult {
    public data class Success(val activation: Activation) : ActivationResult
    public data class HostCommand(val command: Command) : ActivationResult
    /** Text that provisions several apps at once (D53). */
    public data class Bundle(val bundle: ConfigBundle) : ActivationResult
    public data class Rejected(val reason: Reason) : ActivationResult
    // …
}
```

```kotlin
// core/src/commonMain/kotlin/cx/m42/superizer/registry/AppHandler.kt
public class AppHandler(
    // …
    private val unlocked: () -> Set<AppId> = { emptySet() },
    /** The host's stored config for an app (D49); the launch config is laid over it (D50). */
    private val storedConfig: suspend (AppId) -> AppConfig? = { null },
)

private suspend fun launchLocked(id, config, restoreFrom, force): Result<AppSession> {
    // …
    val effective = storedConfig(id)?.overlaidWith(config) ?: config
    val typed = decode(app, effective)
    events.tryEmit(SuperizerEvent.Configured(id, effective))
    // …AppSession(app, effective, …) — снимок хранит эффективный конфиг, как и сейчас
}
```

```kotlin
// core/src/commonMain/kotlin/cx/m42/superizer/app/AppConfig.kt
/** Shallow, [other] wins — the same rule as AppConfigSpec.mergeWithDefault, for the same reason. */
public fun AppConfig.overlaidWith(other: AppConfig): AppConfig =
    if (other.json.isEmpty()) this else AppConfig(JsonObject(json + other.json))
```

`SuperizerEvent` — четыре события из D58.

### `host`

```kotlin
// host/src/commonMain/kotlin/cx/m42/superizer/host/storage/Stores.kt
public object HostKeys { /* … */ public const val PROVISIONING_PREFIX: String = "host.provisioning." }

/** The default store: one prefs key per app, so a reset erases one and not all (D49). */
public class PrefsProvisioningStore(json: Json = Json) : ProvisioningStore
```

`SuperizerBuilder`:

```kotlin
/** Where provisioning lives (D49). Prefs unless the host has somewhere better — a keychain, its own database. */
public fun provisioning(store: ProvisioningStore)
/** The https host this build answers to as App Links (D59); null keeps the custom scheme only. */
public fun linkDomain(domain: String?)
```

Изменения в `SuperizerHost`:

- `store = builder.provisioningStore ?: PrefsProvisioningStore(json)`; `handler = AppHandler(…, storedConfig = { store.load(it)?.config })`.
- `override val provisioning: ProvisioningPort` — объект поверх `store`, `registry`, `unlockStore`,
  `handler`, `clock`: `provision` = `store.save` + `handler.forgetSnapshot` + `Provisioned`;
  `records` — `MutableStateFlow`, заполняется из `store.all()` в `init` и после каждой записи;
  `export` — по `records`; `import` — цикл из D52, `reveal` при `unlock`, `BundleImported` в конце.
- `diagnostics.reset(id)` — плюс `provisioning.deprovision(id)` (D56).
- `ActivationService(…, onProvision = { a -> provisioning.provision(a.appId, a.config, a.audience) })`
  — из `apply`, **только** при `activation.unlock` (D51), до `launch`.
- `DeviceRegistrar(auth, logger, provisioned = { provisioning.records.value.values })` — тело из D61,
  `attach` собирает `PushTransport.token` **и** `provisioning.records`.

`QrPayloadParser` и `ActivationService.fromDeepLink`: `normalise(url)` — если `linkDomain != null`
и URL начинается с `https://<linkDomain>/`, заменить префикс на `<scheme>://`; дальше как сейчас.
`parseJson`: `type == app_config_bundle` → `ConfigBundle.parse` → `Bundle`/`Rejected`; в
`app_activation` читать `audience` (строка, иначе игнорировать); URL-форма — `au`.

```kotlin
// host/src/commonMain/kotlin/cx/m42/superizer/host/platform/FileExchange.kt
/** Save/open one text file through whatever the platform has (D54). False/null: cancelled, or the platform cannot. */
public expect object FileExchange {
    public suspend fun saveText(suggestedName: String, mime: String, text: String): Boolean
    public suspend fun openText(): String?
}
```

### `testing`

`InMemoryProvisioningStore : ProvisioningStore` (рядом с `InMemorySnapshotStore`, карта видна тесту).

### `ui`

- **Settings** — секция «Настройки приложений» (`settings:provisioning`) между секциями приложений
  и «О программе»: сколько приложений сконфигурировано, кнопки «Экспортировать»
  (`settings:provisioning-export`) и «Импортировать» (`settings:provisioning-import` → Activate).
- **`ExportScreen`** (`ShellDestination.Export(from)`): предупреждение D55, текст бандла
  (`export:text`, моно), «Скопировать» (`export:copy`, `LocalClipboardManager`), «Сохранить файл»
  (`export:save` → `FileExchange.saveText`).
- **Activate** — на `ActivationResult.Bundle` → `superizer.provisioning.import(bundle)` → строка
  отчёта `activate:report` → «На главную». Кнопка «Открыть файл» (`activate:open-file`).
- **Shell** — `LaunchedEffect` на `route`: ветка `is Bundle` → import → `go(Home)`.
- **Service Menu** — в развёрнутой строке: сохранённые `config` и `audience`
  (`service:provisioning-<id>`), кнопка «Сохранить как конфиг» рядом с «Run with config».
- `HostStrings`: ~14 новых строк (ru/en).

### Unitool

- `AndroidManifest.xml`: второй `intent-filter` (`autoVerify="true"`, `https`, домен,
  `pathPrefix="/activate"`, `pathPrefix="/app/"`); `assetlinks.json` — в репозиторий сайта.
- `MainActivity`: launcher-ы SAF для `FileExchange`; `deliver(intent)` не меняется — `intent.data`
  теперь бывает `https://…`, и это нормализует ядро.
- `main.kt` (wasm): кроме `?activate=`/`?link=` — если `pathname` начинается с `/activate` или
  `/app/`, отдать `window.location.href` целиком в `route.deliver`.
- `UnitoolHost.kt`: `linkDomain(LINK_DOMAIN)`; `provisioning(...)` не вызывается — prefs по умолчанию.

## 5. Форматы

QR / ссылка активации (расширение схемы 1, обратно совместимое):

```json
{ "schemaVersion": 1, "type": "app_activation", "appId": "orders",
  "audience": "tenant-42",
  "config": { "server": "https://api.tenant42.example", "clientId": "…", "secret": "…" } }
```

```
https://app.unitool.example/activate?a=orders&au=tenant-42&c=<base64url config>
unitool://activate?a=orders&au=tenant-42&c=<base64url config>
```

Файл (`unitool-configs-2026-09-21.superizer.json`):

```json
{
  "schemaVersion": 1,
  "type": "app_config_bundle",
  "host": "Unitool",
  "exportedAt": 1758400000000,
  "apps": [
    { "appId": "orders", "audience": "tenant-42", "unlock": true,
      "config": { "schemaVersion": 1, "server": "https://api.tenant42.example", "clientId": "…", "secret": "…" } },
    { "appId": "calculator", "unlock": true, "config": { "mode": "scientific" } }
  ]
}
```

Запись `apps[i]` ↔ QR-payload: те же `appId`, `audience`, `config` — «конфиг из QR» и «конфиг из
файла» буквально одни байты.

Регистрация устройства (D61, тело для будущего сервера Unitool):

```json
{ "token": "<fcm>", "platform": "android", "lang": "ru", "hostVersion": "1.2.0",
  "apps": [ { "appId": "orders", "audience": "tenant-42" } ] }
```

Хранение (по умолчанию): `host.provisioning.orders` = `{"appId":"orders","config":{…},"audience":"tenant-42","at":…}`.
Битое значение при чтении = «записи нет», как у всех store.

Таблица мусора для парсера (по образцу 12 §4.7, всё → `Rejected`, ничего не бросает):

| Вход | Результат |
|---|---|
| `""`, `not json`, `[]`, `42` | `Malformed` |
| `{"type":"app_config_bundle"}` без `apps` | `Malformed` |
| `{"type":"app_config_bundle","schemaVersion":2,"apps":[]}` | `UnsupportedSchema` |
| `apps: [{"appId": 7}]` | `Malformed` (число — не id, как в `contentOrNullSafe`) |
| `apps: [{"appId":"ghost","config":{}}]`, `ghost` не зарегистрирован | парсится; импорт → `skipped = {ghost: UnknownApp}` |
| `apps: []` | парсится; импорт → `Applied([], {})` — пустой файл не ошибка |
| `"audience": 42` в активации | поле игнорируется, активация валидна |
| `https://other.example/activate?a=x` при `linkDomain = app.unitool.example` | `Malformed` — чужой домен не наш вход |
| `{"schemaVersion":1,"type":"app_activation",…}` без `audience` | как сейчас — `Success`, `audience = null` |

## 6. Сценарии, которые должны работать

1. **QR → закрыл → открыл с Home.** Активация `test-app` с `{"mode":"promo","server":…}`; back;
   тап по плитке → тот же конфиг. Сейчас — падает (§2); это первый тест.
2. **Перезапуск процесса.** Тот же store, новый `buildUnitool` → плитка на Home, открытие →
   тот же конфиг. Без снимка сессии.
3. **Deep link поверх.** Провижинено `{"ratesUrl":X}`; ссылка `rate?pair=USD-RUB` → эффективно
   `{ratesUrl:X, from:USD, to:RUB}`; после закрытия в store по-прежнему `{ratesUrl:X}`.
4. **Экспорт → другой хост → импорт.** Хост A (Unitool) с двумя провижинёнными приложениями и
   одним без; `export()` → текст; хост B (fixture: `probe` + `test-app`) на `InMemoryProvisioningStore`;
   `import(text)` → `Applied([test-app], {calculator: UnknownApp})`, `test-app` разблокирован,
   на Home, открывается с конфигом A и тем же `audience`.
5. **Вставка бандла в Activate.** Тот же текст в поле QR → отчёт → Home с новой плиткой.
6. **Reset / lock.** `reset(test-app)` → `records` без него, `store.load == null`; `lock` → запись на месте.
7. **Новый QR заменяет.** `{server: A}`, затем `{server: B, extra: 1}` → в store ровно второй.
8. **Битый конфиг в файле.** `{"appId":"test-app","config":{"mode":7}}` (`fallbackToDefault = false`)
   → импорт `Applied([test-app])`, первый запуск → `ConfigRejected` + `LaunchFailed` — D18, не молчание.
9. **Автоматический режим (D59).** `route.deliver("https://app.unitool.example/activate?a=test-app&au=t1&c=…")`
   до первой композиции → без экрана Activate: `Provisioned(test-app, t1)` → `Unlocked` → `AddedToHome`
   → `Launched`; экран стенда показывает конфиг. Тот же URL с `unitool://` — тот же результат.
   Чужой домен — `Rejected(Malformed)`, ничего не открылось.
10. **Свой store у хоста.** `Superizer.build { provisioning(InMemoryProvisioningStore()) }` → все
    сценарии выше проходят, `host.provisioning.*` в prefs не появляется.
11. **Регистрация после провижининга (D61).** Фейковый токен в `PushTransport` + активация →
    в логе регистрация с `apps=[{test-app, t1}]`; вторая активация другого приложения → повтор.

## 7. Тесты

Первым — воспроизведение дыры (сценарий 1) в `composeApp/desktopTest/ActivationTest`, красный до
кода. Дальше по модулям, в стиле существующих имён:

- `core` (`ConfigBundleTest`, `AppConfigSpecTest`, `AppHandlerTest`):
  `aBundleRoundTripsThroughText`, `rubbishIsRejectedRatherThanThrown` (таблица §5),
  `anOverlayWinsKeyByKeyAndLeavesTheRestAlone`, `anEmptyOverlayIsTheStoredConfigItself`,
  `aLaunchWithNoConfigRunsOnTheStoredOne`, `aLaunchConfigIsLaidOverTheStoredOne`,
  `aRestoredSnapshotKeepsItsOwnConfig`, `theHandlerAsksTheStoreOnEveryLaunchNotOnce`.
- `host` (`StoresTest`, `QrPayloadParserTest`, `ActivationServiceTest`, новый `ProvisioningPortTest`,
  `PushRouterTest`):
  `aProvisioningSurvivesARestart`, `aCorruptProvisioningMeansNone`,
  `anActivationThatUnlocksAlsoProvisions`, `aDeepLinkDoesNotProvision`,
  `provisioningForgetsTheAppsSnapshot`, `aSecondActivationReplacesTheFirstWhole`,
  `anAudienceTravelsNextToTheConfigAndNeverInsideIt`,
  `anHttpsLinkOnTheHostsDomainIsTheSchemeLink`, `anHttpsLinkOnAnotherDomainIsNotAnEntrance`,
  `exportListsOnlyProvisionedApps`, `importAppliesWhatThisBuildHasAndNamesTheRest`,
  `importRevealsAHiddenAppWithoutLaunchingIt`, `resetErasesTheProvisioningAndLockDoesNot`,
  `theParserTellsABundleFromAnActivation`, `aHostSuppliedStoreIsTheOnlyPlaceProvisioningGoes`,
  `theRegistrationNamesProvisionedAppsAndRepeatsWhenTheyChange`.
- `fixture` (`FixtureShellTest`): сценарий 4 целиком — два хоста, два store, один текст; сценарий 9.
- `composeApp` (`ActivationTest`, `ShellTest`, `Screenshots`): сценарии 1, 2, 5, 9; секция в
  Settings; экран экспорта; кадр `13-export`.
- `testing`: `InMemoryProvisioningStore`. `FileExchange` — `expect object`, в тестах не зовётся (D54).
- web-smoke: `/activate?a=test-app&c=…` как **путь** (не `?activate=`) → `currentApp == "test-app"`;
  `?activate=<urlencoded bundle>` → `events` содержит `BundleImported`, Home с плиткой.

## 8. Шаги реализации

После каждого шага `./scripts/verify.sh` в обеих папках зелёный.

1. **Красный тест** — сценарий 1 в Unitool.
2. **`core`:** `Provisioning`, `ProvisioningStore`, `overlaidWith`, `ConfigBundle`/`BundledApp`/`ImportResult`,
   `Activation.audience`, `ActivationResult.Bundle`, `ProvisioningPort` в `Superizer`,
   `storedConfig` в `AppHandler` + слияние, события. `InMemoryProvisioningStore` в `testing`.
   Тесты `core`. `updateLegacyAbi`.
3. **`host`:** `PrefsProvisioningStore`, builder `provisioning(...)`/`linkDomain(...)`, порт в
   `SuperizerHost`, `onProvision` в `ActivationService.apply`, `reset`, парсер (бандл, `audience`,
   `au`, https-нормализация), `DeviceRegistrar` по D61. Тесты `host`. Шаг 1 зелёный.
4. **`host` платформа:** `FileExchange` expect + три actual. Без автотестов — ручная проверка на
   трёх таргетах, результат в 15.
5. **`ui`:** `ExportScreen`, секция Settings, ветка `Bundle` на Activate и в shell, Service Menu,
   строки. Тесты `fixture`/`composeApp`, скриншот.
6. **Unitool:** `linkDomain`, второй intent-filter, `assetlinks.json` (нужен домен — §9),
   launcher-ы SAF в `MainActivity`, `pathname` в wasm `main.kt`, web-smoke.
7. **Документация библиотеки** (`/prj/superizer/docs`): 04 (`Provisioning*`, `ConfigBundle`,
   `overlaidWith`, `Activation.audience`), 05 (`provisioning(...)`, `linkDomain(...)`, `FileExchange`,
   что инициализировать на Android), 06 (экран экспорта, тест-теги), 08 — новые §7 «Provisioning:
   what a launch runs on, and the bundle file» и §8 «The automatic road: https links from the
   system scanner», §4 — `audience` в регистрации, 09 — рецепт «сервер и креды в конфиге: адрес из
   `config`, токен сессии в `storage`», 11 — «импорт сказал skipped», «после импорта приложение
   на дефолтах» (= `ConfigRejected`), «ссылка из камеры открывает браузер, а не Unitool»
   (= assetlinks). `CHANGELOG` 0.2.0. README — строка в списке возможностей.
8. **Заметки здесь:** 15 — §7 «Ревизия 7 (D49–D61)» по факту; 01 — D49–D61 в список; 06 — абзац
   про провижининг и https; 13 — §6 регистрация по D61; 09 — закрыть 20, добавить 22–27.

Оценка: 1–3 — ядро задачи, полдня; 4 — самое ручное; 5–6 — день; 7–8 — часы. Сервер Unitool
(§10) — отдельный проект, в эту оценку не входит.

## 9. Открытые вопросы и что осознанно не делаем

Добавляются в 09 как 22–27; 20 закрывается D59 при наличии домена.

20. **Домен для App Links** — нужен *сейчас*: без него автоматический режим работает только со
    своей схемой, которую системный сканер не открывает. Предложение: `app.unitool.<домен m42>`,
    на нём же хостится wasm-сборка (D59 делает это одной ссылкой). Ждёт владельца.
22. **Шифрование бандла паролем** — в первой версии? По умолчанию — нет (D55). Кандидат —
    `dev.whyoleg.cryptography` (AES-GCM + PBKDF2; JVM/Android есть, wasmJs через WebCrypto —
    проверить при подключении); альтернатива — `expect`/`actual` на `javax.crypto` и WebCrypto.
23. **Конфиг в `setup(ctx)`** — по умолчанию нет; D60 снимает главный повод (тема пуша по
    клиенту): адресацию делает `audience`, а не подписка на тему.
24. **Что ещё класть в файл** — только `apps` (+ `audience`, `unlock`); Home, язык, хаптика — про
    устройство, не про приложения.
25. **Выбор приложений при экспорте** — в v1 всё сконфигурированное; чекбоксы позже.
26. **Шифрование хранилища на desktop/web** — открытый текст принят для Unitool; хост с настоящими
    кредами подставляет свой `ProvisioningStore` (D49) — keychain через `expect`/`actual`, API
    менять не нужно.
27. **Срок жизни провижининга и отзыв.** Сервер приложения может захотеть отозвать доступ
    (сотрудник уволен). Через пуш `silent` с `data.revoke = true`? Тогда приложение само чистит
    `storage`, а хост — ничего не знает. Или host-level пуш `type: deprovision`? По умолчанию —
    ничего: отзыв делает сервер приложения, отклоняя токен; приложение показывает «доступ отозван».

Не делаем и не планируем в этой итерации: remote config как источник (01, «не в MVP»), поля-секреты
с маскировкой в Service Menu, камера внутри Unitool (D7 — системная камера с D59 делает её
ненужной для сценария владельца), проверка `host` при импорте (D52), сервер Unitool (§10).

## 10. Трёхсторонняя связка — что фиксируется здесь, что нет

```
сервер приложения ──QR/ссылка──▶ пользователь ──камера──▶ Unitool (D59) ──apply──▶ Provisioning (D49)
        │                                                        │
        │                                                        └─ DeviceRegistrar → сервер Unitool: {token, apps:[{appId, audience}]} (D61)
        │
        └──POST /push {appId, audience, link, data, …}──▶ сервер Unitool ──FCM──▶ устройство ──PushRouter──▶ приложение (13)
                                                                                                    └─ link → экран
```

В этой заметке фиксируется **сторона устройства**: конверт активации с `audience` (D60), тело
регистрации и её триггеры (D61), payload пуша схемы 1 (13 §2, без изменений — `appId` + `link` +
`data` уже достаточно, `audience` серверу Unitool нужен для выбора токенов, в payload на
устройство он не идёт). Приложение ничего из этого не видит: оно получает `config` в `launch`,
`PushMessage` в `push.messages` и открывается по `link` — три вещи, которые у него уже есть.

**Не здесь**: сервер Unitool (регистрация устройств, авторизация серверов приложений по ключу на
`appId`, `POST /push`, хранение токенов по `(appId, audience)`), формат «контракта приложения»
для публикации, отзыв доступа (вопрос 27). Это отдельная заметка и отдельный репозиторий; из
ядра ей нужны ровно два JSON из §5 — регистрация и payload пуша.
