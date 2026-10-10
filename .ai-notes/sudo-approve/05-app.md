# 05 — Unitool: регистрация на сервере и мини-апп `apps/sudo-approve`

> **Версия:** 1.2 · **Дата:** 2026-10-10 · Шаги 0 и 4 в 00-TASK. Решения D392, D393, D396, D397,
> D409. Superizer не меняется: всё ниже — код Unitool (`done/ssh-new` D181: реализации живут в
> Unitool).

## 1. Шаг 0 — устройство на сервере (D392, D397)

Сейчас Unitool на сервер не ходит: `DeviceRegistrar` из Superizer (`host/…/push/PushRouter.kt:261`)
только пишет токен в лог. Его не трогаем. Рядом встаёт свой регистратор Unitool. Он слушает тот же
публичный `PushTransport.token` и делает то, что описано в README сервера.

### 1.1. Модуль `services/unitool-server`

```
services/unitool-server/
  build.gradle.kts                 как у services/qr-scanner: android, desktop, ios, wasmJs; deps superizer.core, ktor-client-core
  src/commonMain/kotlin/cx/m42/services/unitoolserver/
    UnitoolServer.kt               контракт для приложений (ниже)
    DeviceState.kt                 NotConfigured | NotRegistered | Registering | Registered(deviceId) | Failed(code)
    UnitoolServerClient.kt         HTTP: POST /devices, PUT /devices/me, DELETE /devices/me, call(...)
    UnitoolDeviceRegistrar.kt      секрет, регистрация, обновление токена, 401 → заново
    AppServerAccess.kt             UnitoolServer для конкретного приложения: префикс путей
  src/desktopTest/…                тесты §1.4 на ktor MockEngine
```

```kotlin
public interface UnitoolServer {
    public val device: StateFlow<DeviceState>
    /** [path] относительно /api/unitool/v1 и обязан начинаться с префикса, выданного приложению. */
    public suspend fun call(method: String, path: String, body: ByteArray? = null): ServerResponse
    public companion object { public val Key: ServiceKey<UnitoolServer> = ServiceKey("unitool-server") }
}
public class ServerResponse(public val status: Int, public val body: ByteArray)
```

- Хост подставляет `Authorization: Bearer d_….ds_…` сам. Приложение секрета не видит.
- Префикс по приложению задаётся в `buildUnitool`: `sudo-approve` → `/sudo/`. Чужой путь даёт
  `ServerResponse(403, …)` без сетевого запроса. Приложению без строки в таблице сервис не выдаётся.
- Пока устройство не `Registered`, `call` ждёт регистрации до 10 с, потом возвращает `503`.

### 1.2. Регистратор

1. Секрет `ds_` + 32 base58 рождается один раз и лежит в `HostSecrets` (`superizer.secrets`) под
   ключом хоста `unitool-server.device`. Там же `deviceId`. Если хосту нельзя держать свои значения
   в `HostSecrets` без правки Superizer — стоп, вопрос в README.
2. На каждом старте, когда есть и секрет, и токен (или токена нет совсем, как на desktop):
   - нет `deviceId` → `POST /devices {secretHash, platform, pushToken, name, locale, timeZone,
     appVersion}`;
   - есть → `PUT /devices/me` с изменившимися полями;
   - `401` → забыть `deviceId`, зарегистрироваться заново тем же секретом;
   - сеть → повтор с паузой 5 с, 30 с, 2 мин, 10 мин, потом при следующем старте.
3. Смена токена (`PushTransport.token`) → `PUT /devices/me {pushToken}`.
4. Базовый URL — константа сборки `UnitoolServerConfig.BASE_URL` из свойства Gradle
   `unitool.server.url` (в `gradle.properties` пусто). Пусто → `DeviceState.NotConfigured`, ничего
   не делается. Адрес — вопрос 114.
5. Сервисное меню (`Service Menu`) показывает `DeviceState` и хвост `deviceId`, без секрета.

### 1.3. Каналы и iOS

- `AndroidNotifier` создаёт второй канал `unitool.sudo`: `IMPORTANCE_HIGH`, название «Запросы sudo» /
  «sudo requests» (`UnitoolStrings`). Канал создаётся всегда, но сервер шлёт в него только пуши
  `sudo-approve`.
- iOS: `time-sensitive` требует entitlement `com.apple.developer.usernotifications.time-sensitive` и
  профиль с этой возможностью. Профиль ручной и лежит в секретах GitHub (`done/ios`). ⏸ Вопрос 182:
  до ответа entitlement не добавляется, и сервер шлёт `interruption-level` только если в устройстве
  записано `capabilities.timeSensitive` (поле придёт с регистрацией позже). В v1 iOS получает обычный
  видимый пуш.

### 1.4. Тесты шага 0

| Тест | Что |
|---|---|
| `UnitoolDeviceRegistrarTest` | первая регистрация; повторный старт → `PUT`; смена токена; 401 → новая регистрация тем же секретом; сеть → повторы по расписанию (FakeClock); `NotConfigured` без URL |
| `AppServerAccessTest` | префикс: `/sudo/inbox` проходит, `/devices/me` и `/sudo/../devices` → 403 без сети; `Authorization` подставлен; секрета нет в `toString`, исключениях и логах |
| `UnitoolServerWiringTest` (composeApp desktopTest) | `buildUnitool` выдаёт `UnitoolServer.Key` приложению `sudo-approve` и не выдаёт `2fa` |

## 2. Манифест (D393, D396)

```kotlin
public object SudoApproveApp : SuperizerApp<SudoApproveInstance> {
    override val manifest: AppManifest = AppManifest(
        id = AppId("sudo-approve"),
        minHostContract = 3,
        metadata = AppMetadata(title = "sudo", description = …, icon = …, hidden = true, category = …),
        requires = setOf(SshKeyring.Key, UnitoolServer.Key),
        deepLinks = setOf("request", "inbox", "keys"),
        pushTopics = setOf("sudo"),
        protection = AppProtection(lock = LockPolicy.Required, secureWindow = true),
        backup = BackupPolicy.None,
    )
}
```

- `hidden = true`, но **не** `secret`. Секретному приложению Superizer запрещает `deepLinks` и
  `pushTopics` (`AppRegistry.kt:111`), а здесь без них нельзя (D396). Открывается промокодом
  `{sudo}`: хэш — `scripts/promo-hash.sh '{sudo}'`, строка в `HashedPromoCodes` в `UnitoolHost.kt` и
  в `.secrets/promo-codes.md`. Пока приложение не открыто, `PushRouter` отбрасывает его пуши, и это
  правильно: без открытого приложения хэшей на сервере нет.
- Регистрация: `register(SudoApproveApp)` в `buildUnitool`, `include(":apps:sudo-approve")` в
  `settings.gradle.kts`, зависимость в `composeApp/build.gradle.kts`.
- `BackupPolicy.None`: пины хостов и история — на этом телефоне. Новый телефон знакомится с хостами
  заново (TOFU), и это показано на экране «Хосты».

## 3. Устройство модуля

```
apps/sudo-approve/src/commonMain/kotlin/cx/m42/apps/sudo/
  SudoApproveApp.kt        манифест, onLaunch, deep links (request?id=, inbox, keys)
  SudoApproveInstance.kt   состояние приложения: ключи, входящие, хосты; связывает всё ниже
  SudoApi.kt               обёртка над UnitoolServer: keys, inbox, get, answer, mutes (01 §7.2)
  Published.kt             какие ключи включены → PUT /sudo/keys; хранение в runtime.storage
  Inbox.kt                 загрузка и обновление входящих (§5)
  Approver.kt              разбор R → проверки 01 §3.3 → sign → seal → POST answer
  HostPins.kt              TOFU: (clientId, host, hostKey) в runtime.storage
  DenyStreak.kt            три отказа подряд от хоста → предложить заглушку
  History.kt               последние 200 решений на этом телефоне
  SudoStrings.kt           ru + en
  ui/KeysScreen.kt, ui/InboxScreen.kt, ui/RequestScreen.kt, ui/HostsScreen.kt
apps/sudo-approve/src/desktopTest/kotlin/cx/m42/apps/sudo/…   тесты §8
```

Образцы: `apps/twofa` (манифест с замком, deep link, `runtime.secrets`, экраны),
`/prj/superizer/apps/test-app/.../TestAppInstance.kt:179` (выбор ключа и подпись).

## 4. Экраны

### 4.1. Ключи

- Список: `keyring.keys` — только ключи, на которые у приложения уже есть грант. Для каждого:
  метка, тип, `SHA256:…`, первые 12 символов `keyHash` (для сверки с `unitool-sudo keyhash`) и
  переключатель «Получать запросы sudo».
- «Добавить ключ» → `keyring.choose(KeyPurpose.Sign(SudoEnvelope.NAMESPACE))`. Лист кольца сам
  спросит человека о гранте (обычный грант, ssh-new 02 §3.2).
- Переключатель → `Published` пересчитывает набор и делает `PUT /sudo/keys` со **всеми** включёнными
  хэшами (`KeyHash.of(SshPublicKey.parse(info.publicKey).blob)`). Ошибка сети → переключатель
  возвращается, строка «не удалось, повторите».
- При первом включении любого ключа — `runtime.push.requestPermission()` (D409). Отказ → жёлтая
  плашка «уведомлений не будет, запросы видны только во входящих».
- У ключа есть меню:
  - «Скопировать строку для authorized_keys» (`SshPublicKey.authorizedKeysLine("unitool-sudo")`);
  - «Как поставить на сервер». Это лист с тремя командами: `install.sh`, `enroll` и строкой для
    `/etc/unitool-sudo/authorized_keys`. Пример — 04 §9.
- Предупреждения:
  - устройство не зарегистрировано (`DeviceState` ≠ `Registered`) — красная плашка с причиной;
  - ключ уровня 3 (только пароль) — «на этом ключе каждое подтверждение спросит пароль ключа».

### 4.2. Входящие (главный экран)

- Ожидающие из `GET /sudo/inbox`. В каждой строке: крупно код, хост (`label`), команда одной строкой
  и обратный отсчёт по `serverNow`, а не по часам телефона.
- Ниже — «История на этом телефоне» (`History`): время, хост, команда, решение. Без сервера.
- Пуш `sudo-notice` (вход по паролю на хосте, 1.2, D416) ложится в историю строкой с ⚠: «mu6: dev0
  вошёл по паролю, 12:04». Тап по уведомлению открывает входящие. Если это были не вы — подсказка
  «смените пароль на хосте».
- Пусто → «Запросов нет. Когда на сервере наберут sudo, запрос появится здесь».

### 4.3. Запрос (`unitool://app/sudo-approve/request?id=sr_…`)

```
┌──────────────────────────────────────┐
│ K7QX                       осталось 2:41 │
│ mu6  ·  ✓ известный хост                 │   / ⚠ новый хост / ⛔ ключ хоста изменился
│ dev0 → root    /root    pts/3            │
│ ┌──────────────────────────────────────┐ │
│ │ /usr/bin/apt upgrade                 │ │   моноширинный, с переносами, целиком
│ └──────────────────────────────────────┘ │
│ ключ: work-laptop (ed25519)              │
│                                          │
│ [ Отклонить ]                            │
│ [ Один раз ] [ 15 мин ] [ 1 ч ]          │
│ [ До выхода из сессии ]                  │
└──────────────────────────────────────┘
```

- Перед показом — проверки 01 §3.3. Каждый провал — понятная строка вместо кнопок.
- Оболочки (`sudo -s`, `sudo -i`) на телефон не приходят вовсе: на хосте их открывает только пароль
  (1.2, D413). Поле `shell` в R — ошибка разбора.
- Нет `session` в R → кнопки «15 мин», «1 ч» и «До выхода» серые, подпись «на этом хосте нет
  сессии логина — только один раз».
- `argvTruncated` → то же, плюс «команда обрезана, полностью её видно только на хосте».
- **TOFU (D390).** Пин — `(clientId, R.host, R.hostKey)`:
  - нет пина → «новый хост», подтверждение возможно, пин пишется после успешного ответа;
  - пин совпал → «известный хост»;
  - `hostKey` другой при том же `clientId` → «ключ хоста изменился», все кнопки, кроме
    «Отклонить», заблокированы. Разблокирует «Доверять новому ключу» с повторным подтверждением
    (диалог с обоими отпечатками).
- **Ключ для подписи** — включённый ключ с хэшем из `Req.keyHashes`. Если таких несколько, берётся
  первый по порядку на экране «Ключи».
- **Цепочка «разрешить»:**
  `SudoAnswer.build(R, decision, until)` → `keyring.sign(key, NAMESPACE, answer.bytes)` (системный
  промпт или пароль ключа) → `SudoEnvelope.seal(answer, sig, R.hostKey)` →
  `POST /sudo/requests/{id}/answer {decision:"approve", sealed}` → «Готово: хост получил ответ».
  - `SshOutcome.Cancelled` → ничего не отправлено, экран остаётся;
  - другой не-`Ok` → строка ошибки кольца, ничего не отправлено;
  - `409` → «На этот запрос уже ответили»; `410` → «Запрос истёк или отменён».
- **«Отклонить»** → `{decision:"deny"}` без подписи. `DenyStreak` +1 для хоста; на третьем подряд —
  лист «Заглушить mu6 на 10 минут?» → `PUT /sudo/mutes/{clientId}`.
- Промпт кольца показывает только 8 символов дайджеста (вывод из `ssh-new`). Поэтому смысл запроса
  человек видит **на этом экране**, а промпт только подтверждает, что подписывает именно он.

### 4.4. Хосты

- Пины TOFU: `label`, `host`, отпечаток ключа хоста, когда впервые и когда последний раз.
- «Заглушить на 10 мин / 1 ч / до завтра» → `PUT /sudo/mutes/{clientId}`; список живых заглушек —
  из `GET /sudo/mutes`.
- «Забыть хост» → удалить пин. Следующий запрос будет «новый хост».

## 5. Пуши и обновление (D388)

- Входящие обновляются:
  - при `onLaunch` и deep link;
  - при возврате на экран;
  - по `runtime.push.messages` с `data.kind ∈ {sudo, sudo-gone}`;
  - раз в 5 с, пока экран «Входящие» или «Запрос» на виду.
- Пришёл `sudo`, а приложение на экране → лист запроса открывается поверх, без уведомления.
- `sudo-notice` → запись в историю (§4.2); пуш видимый, его рисует хост.
- `sudo-gone` → если открыт этот запрос, экран меняется на «уже отвечено / истекло». Снимать
  уведомление по тегу — задача хоста, а не мини-аппа (D27). Если оно останется, тап по нему
  покажет «истёк». Отдельная правка `AndroidNotifier` — не в v1.
- Кнопок «Разрешить» в самом уведомлении нет и не будет: подписывать вслепую нельзя, а промпт
  Android требует активити на переднем плане (D354).

## 6. Тексты

`SudoStrings` (ru + en) по образцу `TwoFaStrings`. Полноту проверяет тест: у каждой строки есть
оба языка. Пуши сервер пишет сам (02 §5).

## 7. Скриншоты

По образцу `KeysScreensTest` / `Screenshots.kt`: «Ключи», «Входящие» с двумя запросами, «Запрос» в
трёх состояниях (известный хост, новый, ключ изменился), история с записью «вход по паролю», «Хосты». Только ru и en в
`docs/review/sudo-approve/`. В `play/` — не в этом задании.

## 8. Тесты

desktopTest. Поддельный `UnitoolServer` (запись запросов, ответы по сценарию). Кольцо —
настоящее, уровня 3, на тестовых ключах из `unitool-sudo/testdata/keys` (как `KeysScreensTest`
строит `SshKeyringHost.passphraseOnly(…)`).

| Тест | Что |
|---|---|
| `PublishedTest` | включение и выключение → правильный `PUT /sudo/keys`; ошибка → откат; разрешение на уведомления спрашивается один раз |
| `InboxTest` | загрузка, обновление по пушу, `sudo-gone`, `sudo-notice` → запись в истории, обратный отсчёт по `serverNow` |
| `ApproverTest` | для каждого типа ключа: `sealed` открывается настоящим `age -d -i host_ed25519`, подпись проходит настоящий `ssh-keygen -Y verify -n sudo-approve@superizer`, и `Verdict.check` даёт `Approved` (образец — `SshSigAndAgeInteropTest`) |
| `ApproverRefusalTest` | неканонический R, истёкший `exp`, хэш не мой, `argvTruncated` + `ttl` → кнопка недоступна; `Cancelled` кольца → ничего не отправлено; 409/410 |
| `HostPinsTest` | новый / известный / изменился; «Доверять новому» перезаписывает пин |
| `DenyStreakTest` | три отказа → лист; разрешение сбрасывает счёт |
| `SudoScreensTest` | UI-тесты экранов §4 и скриншоты §7 |
| `SudoStringsTest` | ru и en полные |
| `FixturesTest` | фикстуры `unitool-sudo/testdata` читаются, `SHA256SUMS` сходится |

## 9. Готово, когда

- `scripts/verify.sh` зелёный, тесты §1.4 и §8 есть и проходят.
- На эмуляторе `unitool35` с локальным сервером (`10.0.2.2:17290`, сборка с
  `-Punitool.server.url=http://10.0.2.2:17290/api/unitool/v1`; открытый HTTP разрешён только в
  debug-сборке и только для `10.0.2.2`, через `network_security_config`): промокод `{sudo}` открывает
  приложение, ключ включается, устройство видно в админке сервера, `curl` от «хоста» даёт пуш, экран
  запроса открывается, ответ уходит.
- Скриншоты в `docs/review/sudo-approve/` — ⏸ на просмотр владельцу.
