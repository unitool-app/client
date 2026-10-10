# 01 — Пуши по требованию приложения

## 1. Манифест (D417)

```kotlin
// core/.../app/AppManifest.kt
@Serializable
public enum class PushUse {
    /** Push is not used. No token, no server, no dialog because of this app. */
    None,
    /** Data messages only (sync, wake-up). Needs no permission on either platform; never draws. */
    Silent,
    /** Visible notifications. Needs POST_NOTIFICATIONS (Android 13+) / UNAuthorization (iOS). */
    Alerts,
}

public data class AppManifest(
    // …
    val pushTopics: Set<String> = emptySet(),
    /** D417. Anything but None needs minHostContract = 5 (D138). */
    val push: PushUse = PushUse.None,
)
```

Поля с объяснением «зачем пуши» нет (v1.1): человеку ничего не объясняем.

Что проверяет `AppRegistry` (сейчас там уже есть `PROTECTION_CONTRACT` / `BACKUP_CONTRACT` /
`SECRET_CONTRACT`, рядом встаёт `PUSH_CONTRACT = 5`):

| Манифест | Результат |
|---|---|
| `push != None`, `minHostContract < 5` | отказ: манифест противоречив (D138). Хост contract 4 о поле не знает и молча не спросил бы |
| `pushTopics` не пуст, `push == None` | отказ: топики без пушей — противоречие |
| `secret == true`, `push != None` | отказ (секретные приложения уже не принимают пушей, idea/09) |

Роутер учитывает это поле. Если пуш с `silent != "true"` пришёл приложению `Silent`, данные
доставляются, но `notifier` не вызывается. Приложение объявило, что ничего не рисует, и сервер
не может это переопределить.

Ещё одно правило роутера (D430): после проверки `Disabled` данные пуша (не тап) приложению,
которого нет в `gate.used`, отбрасываются как `PushDropped(appId, "NotUsed")`. Тап проходит:
это вход в приложение, как по ссылке, и уведомление могла нарисовать сама ОС. Это защита на стороне телефона,
если сервер ошибся или пуш отправлен с `ignoreApps`.

## 2. Какими приложениями пользуются (D418, D419)

```kotlin
// host/.../push/PushGate.kt  (new)
public class PushGate(
    private val registry: AppRegistry,
    private val store: UsedStore,             // host.push.used: Set<AppId>
) {
    /** D418: push apps the person has started using. The only input to transport, registrar and topics. */
    public val used: StateFlow<Set<AppId>>

    /** Called by the host at each entry point below; asks at once if the app is new to `used` (D420). */
    public suspend fun startUsing(ids: Collection<AppId>)

    /** removeFromHome, reset (D35). */
    public fun stopUsing(id: AppId)
}
```

В `used` попадают только приложения с `push != None`, все остальные `startUsing` пропускает.

| Где приложением начинают пользоваться | Когда входит в `used` и спрашиваем |
|---|---|
| Каталог → «добавить» (`addToHome`, `SuperizerShell.kt:315`) | сразу после добавления |
| Каталог → открыть, не добавляя на Home | сразу при открытии |
| Скрытое приложение, раскрытое QR/промокодом | при **первом входе** в него. Само раскрытие (D8 кладёт на Home) в `used` не вводит |
| Стартовый Home свежей установки (`home("calculator")` в `UnitoolHost.kt:121`) | при первом открытии. При старте Unitool не спрашиваем |
| Вход по ссылке или тапу по пушу | при этом входе |
| Восстановление из бэкапа | сразу после восстановления, см. ниже |

Все пути с «открытием» сводятся к одному месту: `SuperizerHost` после успешного
`handler.launch(id)` вызывает `gate.startUsing(listOf(id))`. Этим же покрыты скрытые
приложения, ссылки и тапы. `addToHome` по действию человека вызывает его отдельно, а путь
раскрытия (`reveal`, `SuperizerHost.kt:401`) — нет.

**Бэкап.** `HostBackup.restore` (`host/.../backup/HostBackup.kt:137`) возвращает
`RestoreReport.restored`. Когда он вернул `Ok`, хост вызывает `startUsing` для push-приложений
из `restored` и, если восстанавливались настройки, для push-приложений с восстановленного
`HOME`. В этот момент сразу появляется системный запрос и регистрация (§4). Восстановление
пишет `HOME` прямо в `SafePrefs`, поэтому `HomeStore` нужно перечитать до этого вызова.

`host.push.used` в бэкап не входит: множество собирается заново при восстановлении по правилу
выше, а разрешения ОС на новом телефоне тоже новые.

## 3. Как спрашиваем (D420)

Когда `Alerts`-приложение впервые входит в `used`:

1. Если ОС уже разрешила (`areNotificationsEnabled()` / `authorizationStatus == authorized`),
   ничего не показываем и переходим к §4.
2. Иначе сразу `PushTransport.requestPermission()`, то есть системный диалог. Своего листа,
   объяснений и «Не сейчас» нет.
3. Ответ не сохраняем: решение хранит ОС. Следующее новое `Alerts`-приложение снова вызовет
   запрос. После окончательного отказа ОС диалог больше не показывает (Android 13+ после двух
   отказов, iOS после первого), и `requestPermission()` просто вернёт false.
4. Строка в Settings (D424) показывает состояние. Если отказ окончательный (Android:
   `shouldShowRequestPermissionRationale == false` после отказа; iOS: `denied`), кнопка
   открывает системные настройки приложения.

При первом открытии диалог появляется поверх экрана приложения, который уже открыт. Android
вызывает диалог через Activity хоста (`AndroidHost.currentActivity()`), а этот путь уже есть.

Для `Silent` шаги 1–4 пропускаются. Приложение попадает в `used`, и сразу выполняется §4.

## 4. Токен и сервер только при непустом `used` (D421, D422)

### 4.1 Транспорт

```kotlin
public expect object PushTransport {
    // …
    /** D421: first call wakes FCM (token, APNs). Idempotent. Nothing calls it while `used` is empty. */
    public suspend fun activate()
}
```

- **Android.** В манифест Unitool добавить
  `<meta-data android:name="firebase_messaging_auto_init_enabled" android:value="false"/>`.
  В `PushTransport.android.kt` убрать `init { refreshToken() }`. `activate()` ставит
  `FirebaseMessaging.getInstance().isAutoInitEnabled = true` и вызывает `refreshToken()`.
  Флаг Firebase запоминает сам, поэтому после первого раза токен будет и при следующих стартах.
  Пока `used` пусто, `activate()` всё равно никто не вызывает.
- **iOS.** В `Info.plist` поставить `FirebaseMessagingAutoInitEnabled = NO`.
  `registerForRemoteNotifications()` убрать из `didFinishLaunching` и перенести в новый метод
  моста `IosMessaging.activate()`: он включает `Messaging.messaging().isAutoInitEnabled` и
  регистрирует APNs на главном потоке.
- Desktop/wasm: no-op.

Хост вызывает `activate()` один раз на старте, если `used` не пусто, и в момент, когда `used`
становится непустым. Это не зависит от ответа на диалог: `Silent` и токен разрешения не требуют.

### 4.2 Регистрация на сервере

Сейчас `DeviceRegistrar` только пишет в лог. Теперь он работает так:

```kotlin
public class DeviceRegistrar(
    private val gate: PushGate,
    private val secrets: SecretStore,          // ds_… lives here (runtime.secrets)
    private val api: DeviceApi?,               // null → today's log line, no server configured
    private val logger: Logger,
) {
    // combine(PushTransport.token, gate.used):
    //   used became empty       → PUT /devices/me {apps: []} once; row, d_id, ds_ and token stay (D427)
    //   token != null, no d_id  → POST /api/unitool/v1/devices {secretHash, pushToken, platform, apps, …}
    //   token != null, have d_id→ PUT /devices/me {pushToken, apps, appVersion, locale, timeZone}
    //   401                     → forget d_id, POST again with the same ds_ secret
}
```

- `ds_…` создаётся при первой регистрации, а не при первом старте. Пока push-приложений нет,
  у Unitool нет ни секрета, ни строки на сервере.
- URL сервера задаёт Unitool через билдер хоста (`pushServer("https://unitool.m42.cx")`).
  Superizer адреса не знает. Без этого вызова получается сегодняшнее поведение: лог
  «no server configured».
- Полный сброс хоста — единственный путь, где телефон делает DELETE `/devices/me`,
  `FirebaseMessaging.deleteToken()` и стирает `ds_…` и `d_…` (D427).
- Протокол тот, что уже работает на сервере (remote-progress note 01 §1/§2/§6/§7,
  `Bearer d_….ds_…`).

### 4.3 Сервер (D429)

`layers/app-unitool`:
- миграция umzug: колонка `apps text[] not null default '{}'` в `unitool_devices`;
- `lib/schemas.ts`: в `deviceFields` поле
  `apps: z.array(z.string().regex(/^[a-z0-9][a-z0-9-]*$/)).max(64).optional()`;
- `PushService`: и для `target.all`, и для `target.devices` берутся только устройства с
  `appId = ANY(apps)`. В ответе `{sent, skipped}`. `pushRequestSchema` получает
  `ignoreApps: z.boolean().optional()` для отладки;
- `unitool.devices` (MCP) показывает `apps` каждого устройства.

## 5. Топики (D423)

`RoutedPush.subscribe()` пишет в `TopicStore`, как раньше. К транспорту обращается только
`PushGate`:

- приложение вошло в `used` → `subscribeTopic` для всех его топиков;
- вышло → `unsubscribeTopic` для всех топиков, а `TopicStore` не трогаем;
- `clear(appId)` (сброс, D35) не меняется.

Поэтому `subscribe()` в `setup()` у приложения, которым ещё не пользовались, ничего не отправляет в Google.

## 6. Settings (D424)

Новый раздел «Уведомления» в `SettingsScreen`. Он показывается только при непустом `used`.
Строка на каждое приложение: название, `Silent` («фоновая синхронизация») или состояние
разрешения для `Alerts` («включены» / «выключены — включить» / «запрещены в настройках
телефона — открыть»). Service Menu дополнительно показывает `d_…`, первые 12 символов токена и
время последней регистрации.

## 7. Что меняется, по файлам

**Superizer** (0.6.0, contract 5):

| Файл | Изменение |
|---|---|
| `core/.../app/AppManifest.kt` | `PushUse`, `push` |
| `core/.../registry/AppRegistry.kt` | `PUSH_CONTRACT = 5` и три правила из §1 |
| `core/.../runtime/Services.kt` | KDoc `requestPermission`: D425 |
| `host/.../push/PushGate.kt` | новый: `used`, `startUsing`/`stopUsing`, запрос по D420, топики (§5), вызов `activate()` |
| `host/.../push/PushRouter.kt` | `Silent` не рисует, `NotUsed` (D430). `DeviceRegistrar` переписан по §4.2. `RoutedPush.subscribe` больше не идёт в транспорт, `requestPermission` проверяет D425 |
| `host/.../push/PushTransport*.kt` | `activate()`. Android без `init { refreshToken() }` |
| `host/.../push/DeviceApi.kt` | новый: POST/PUT через Ktor, который у `host` уже подключён на всех таргетах. В тестах `ktor-client-mock` |
| `host/.../storage/Stores.kt` | `HostKeys.PUSH_USED`, `HostKeys.DEVICE` (`d_` id), не в `BACKED_UP` |
| `host/.../backup/HostBackup.kt` | после `Ok` — `startUsing` по §2 «Бэкап» |
| `host/.../SuperizerHost.kt` | билдер `pushServer(url)`, проводка gate/registrar; `startUsing` после `launch` и в `addToHome`, `stopUsing` в `removeFromHome` и сбросе |
| `ui/.../screens/SettingsScreen.kt`, `ServiceMenuScreen.kt` | §6 |
| `ui/.../i18n/HostStrings.kt` | строки раздела «Уведомления» |
| `apps/test-app` | `push = Alerts`, `minHostContract = 5`. Тест: добавил на Home → системный запрос → `activate()` |

**Unitool:**

| Файл | Изменение |
|---|---|
| `gradle/libs.versions.toml` | Superizer 0.6.0 |
| `composeApp/src/androidMain/AndroidManifest.xml` | `firebase_messaging_auto_init_enabled=false` |
| `iosApp/iosApp/Info.plist` | `FirebaseMessagingAutoInitEnabled = NO` |
| `iosApp/iosApp/iOSApp.swift` | `registerForRemoteNotifications()` перенести в `FirebaseTopics.activate()` |
| `composeApp/.../UnitoolHost.kt` | `pushServer("https://unitool.m42.cx")` |
| `apps/currency-converter/.../ConverterApp.kt` | убрать `pushTopics = setOf("rates")` и упоминание пушей в комментарии к `rate` (D428) |
| `play/PRIVACY.md`, листинги | «токен уведомлений отправляется на наш сервер только после того, как начали пользоваться приложением с уведомлениями». Проверять по собранному APK, как в push/01 |

**Сервер:** §4.3.

## 8. Тесты

- `AppRegistryTest`: три отказа из §1.
- `PushGateTest` (desktop, фейковый транспорт): ничем не пользовались → `activate()` не вызван,
  токена нет, регистратор молчит; каталог → Home с `Alerts` → `requestPermission` вызван сразу,
  без листа; открыли из каталога без Home → то же; раскрыли скрытое → запроса нет, первый
  `launch` → запрос; стартовый Home с `Alerts` → запроса нет до первого `launch`; ОС уже
  разрешила → запроса нет, `activate()` есть; второе `Alerts`-приложение после отказа → запрос
  снова; `Silent` → `activate()` без запроса; восстановление бэкапа с push-приложением → запрос и
  `activate()` сразу после `restore`; убрали с Home → `unsubscribeTopic` для всех топиков, а
  `TopicStore` сохранил их.
- `DeviceRegistrarTest` с фейковым `DeviceApi`: POST → PUT при новом токене → 401 → POST с тем
  же секретом; `used` опустел → один PUT `apps=[]`, без DELETE; вернулось приложение → PUT с тем же `d_…`; сброс хоста → DELETE и `deleteToken`.
- `PushRouterTest`: `Silent` + `silent=false` → данные доставлены, `notifier` не вызван;
  приложение зарегистрировано и включено, но не в `used` → `NotUsed`.
- Сервер (vitest): устройство с `apps=[]` не получает ни `all`, ни адресный пуш; `ignoreApps`
  получает; PUT без `apps` список не меняет.
- Ручная проверка на телефоне: после D428 в Unitool нет приложений с пушами, поэтому на
  свежей установке не должно быть ни диалога, ни токена (`adb logcat`), ни записи на сервере.
  Полная цепочка (запрос → токен → POST с `apps` → `unitool.sendPush` → уведомление)
  проверяется на первом настоящем приложении с пушами; до него — desktop-тесты на bench-
  приложении Superizer.
