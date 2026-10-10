# 13 — Пуши и маршрутизация: как сообщение находит приложение, а приложение — экран

Ревизия 2 (2026-09-20). Закрывает дыры, найденные при разборе пушей: нет регистрации
устройства, нет формата payload, не описан путь «тап по уведомлению → экран», нет правил для
неизвестного/скрытого приложения, нет тестов без FCM. Образец — `agentiz/mobile-client/push`
(`Push`-объект с `StateFlow` и `consumeRoute()`, `pushRouteFrom(data)` со строгим `type`).

Принцип тот же, что везде: **приложение не знает, что такое пуш.** Оно знает подписку на тему,
поток сообщений и конфиг при запуске. Всё остальное — хост.

## 1. Две дороги

```
FCM / APNs / Web Push
        │
        ▼
PushTransport (host, expect/actual)  ──► IncomingPush(data: Map<String,String>, tapped: Boolean)
        │
        ▼
PushRouter (host)
   ├─ schemaVersion != "1" / нет appId / appId не в registry / скрыто и не разблокировано
   │        → PushDropped(reason), лог, ничего не показываем
   ├─ tapped == false  (сообщение пришло, процесс жив)
   │        → appRuntime(appId).push.messages.emit(PushMessage)      ← дорога ДАННЫХ
   │        → если silent != "true": хост показывает уведомление, в intent — link
   └─ tapped == true   (пользователь нажал; процесс мог быть мёртв)
            → PendingRoute.deliver(link ?: "unitool://app/<appId>")  ← дорога ЭКРАНА
                     │
                     ▼ shell забирает ровно один раз при первой композиции
            ActivationService.fromDeepLink(link) → Activation(appId, config) → handler.launch
                     │
                     ▼
            app.launch(runtime, typedConfig) — приложение само ставит внутренний экран
```

Дорога данных работает через runtime **уровня приложения** (D15): подписка и поток живут от
enable до disable, экран не нужен. Дорога экрана всегда проходит через `ActivationService`, то
есть через тот же код, что QR, промокод и ссылка из браузера. Приложение получает конфиг и не
знает, что его открыл пуш (§12–13).

## 2. Payload (data-only message, схема 1)

FCM `data` — это `Map<String, String>`, поэтому всё строки; вложенное — JSON-строкой.

| Ключ | Обяз. | Что |
|---|---|---|
| `schemaVersion` | да | `"1"`; другое → Dropped(UnsupportedSchema) |
| `appId` | да | kebab-case id из registry; неизвестный → Dropped(UnknownApp) |
| `topic` | нет | тема, как её подписывало приложение (без префикса хоста) |
| `link` | нет | deep link `unitool://app/<appId>/<path>?k=v`; должен совпадать по `appId`, иначе Dropped(LinkMismatch) |
| `data` | нет | JSON-объект строкой; приложение получает его как `JsonObject` |
| `title`, `body` | нет | текст уведомления, уже на языке пользователя (сервер знает язык из регистрации) |
| `silent` | нет | `"true"` → в поток без уведомления (обновить кэш) |

Только data-only, без `notification`-блока FCM: иначе на Android в фоне уведомление рисует
система, и tap-intent не несёт `link`. Хост рисует уведомление сам (`NotificationCompat` на
Android; на desktop/wasm в MVP — не рисует).

Пример:

```json
{ "schemaVersion": "1", "appId": "currency-converter", "topic": "rates",
  "link": "unitool://app/currency-converter/rate?pair=USD-RUB",
  "title": "Курс обновлён", "body": "USD/RUB 92.10", "data": "{\"pair\":\"USD-RUB\",\"rate\":92.1}" }
```

## 3. Контракт для приложения (`core`)

```kotlin
/** What an app sees of push. Topics are its own names; the host prefixes them with the app id so apps cannot collide. */
interface PushService {
    /** Whether this install can receive push at all: transport present, permission granted (Android 13+ asks). */
    val enabled: StateFlow<Boolean>
    /** Shows the system permission dialog where one exists; true if granted. No-op true on desktop/wasm. */
    suspend fun requestPermission(): Boolean
    /** Persisted by the host and re-applied after a token refresh, so an app subscribes once in setup(). */
    suspend fun subscribe(topic: String)
    suspend fun unsubscribe(topic: String)
    val subscriptions: StateFlow<Set<String>>
    /** Messages addressed to this app id, whether or not a screen is open. Replay 0: state lives in storage, not in the flow. */
    val messages: SharedFlow<PushMessage>
}
data class PushMessage(val topic: String?, val data: JsonObject, val link: String?, val receivedAt: Long)
```

Чего в контракте **нет** и почему: токена (это дело хоста и сервера, приложению незачем);
`showNotification` (приложение не рисует уведомления — это глобальный UI хоста, §14); навигации
из обработчика сообщения (runtime уровня приложения без `navigation`, D15; «открыть экран» — это
`link` в payload, который отработает при тапе).

Deep links регистрируются, как и было:

```kotlin
override val manifest = AppManifest(…, deepLinks = setOf("rate"), pushTopics = setOf("rates"), networkHosts = setOf("api.frankfurter.app"))

override fun setup(ctx: AppSetupContext) {
    ctx.deepLink("rate") { params -> ConverterConfig(screen = "rate", pair = params["pair"]).encode() }   // объявлен в манифесте, иначе SetupMismatch
    ctx.runtime.scope.launch { ctx.runtime.push.subscribe("rates") }                                     // не объявлен → TopicStore отклонит + лог
    ctx.listener { … }
}
```

Приложение с внутренней навигацией читает `config.screen` в `launch`, ставит свой стек и
выставляет `chrome.canGoBack`. Хост об экране `rate` не знает ничего.

## 4. Хост (`host`)

```kotlin
/** Platform seam. Static entry points (a Firebase service) with no route to a composition — hence the flows, as in agentiz. */
expect object PushTransport {
    val token: StateFlow<PushRegistration?>          // PushRegistration(token, platform)
    val incoming: SharedFlow<IncomingPush>           // data messages while the process is alive
    fun deliverTap(data: Map<String, String>)        // from the tap intent / launch options
    suspend fun requestPermission(): Boolean
    suspend fun subscribeTopic(topic: String); suspend fun unsubscribeTopic(topic: String)
}
// actual: Android — FirebaseMessagingService + intent extras; desktop, wasmJs — no-op (token = null, enabled = false).

class PushRouter(registry, unlocks, runtimes: (AppId) -> AppRuntime?, pending: PendingRoute, notifier: Notifier, events)
class PendingRoute { val route: StateFlow<String?>; fun deliver(link: String); fun consume(): String? }   // consume-once, как Push.consumeRoute()
class TopicStore(storage)          // "superizer.push.topics" = { "<appId>": ["rates", …] }; префикс "<appId>." при вызове транспорта
class DeviceRegistrar(transport, auth, network, logger)   // token × session → POST на сервер; в MVP — только лог
```

Правила роутера, все — тесты в `host/src/desktopTest`:

| Ситуация | Действие | Событие |
|---|---|---|
| `schemaVersion` не `"1"` | drop | `PushDropped(UnsupportedSchema)` |
| `appId` нет / не в registry | drop | `PushDropped(UnknownApp)` |
| приложение скрыто и не разблокировано | drop — пуш **не** активация, разблокировать может только QR/промокод | `PushDropped(Locked)` |
| приложение `disabled` в Service Menu | drop | `PushDropped(Disabled)` |
| `link` есть, но id в нём ≠ `appId` | drop | `PushDropped(LinkMismatch)` |
| данные, `silent=true` | в поток, без уведомления | `PushReceived(appId, topic)` |
| данные, не silent | в поток + уведомление с `link` (или `unitool://app/<id>`) | `PushReceived` |
| тап | `PendingRoute.deliver(link)` | `Activated(appId, source = "push")` при запуске |
| тап, `link` без зарегистрированного пути | запуск с `AppConfig.Empty` | `DeepLinkUnmatched(appId, path)` |

Мягкая деградация как в agentiz: непонятный payload оставляет приложение там, где оно было;
понятный, но с незнакомым путём — открывает приложение «просто так».

## 5. Shell: что важнее при старте

```
1. PendingRoute (пуш-тап, deep link из intent / ?link= / --activate) — если есть, consume → fromDeepLink → launch
2. иначе SessionSnapshot (D16), если моложе суток
3. иначе Home
```

Ссылка, пришедшая **во время работы** (`onNewIntent`, второй тап), обрабатывается тем же
`LaunchedEffect` на `PendingRoute.route`, как в `App.kt` agentiz: текущее приложение
закрывается со снимком, открывается целевое. Назад из открытого по пушу приложения — Home
(D4, стека нет). При veto текущего (`onCloseRequested = false`) ссылка ждёт: хост показывает свой
диалог, после «Закрыть» — продолжает, после «Остаться» — `PendingRoute` очищается, событие
`RouteDiscarded`.

## 6. Регистрация устройства

`DeviceRegistrar` объединяет `PushTransport.token` и `AuthService.session`: есть оба → POST на
сервер (`{token, platform, lang, hostVersion, appIds: registry.ids}`); сменился токен → повтор;
`signOut` → DELETE. Сервера в MVP нет → реализация пишет в `LogBuffer`, Service Menu показывает
«token: …, registered: no server». `appIds` в регистрации — чтобы сервер не слал пуши
приложению, которого в этой сборке нет.

Темы после смены токена переподписываются из `TopicStore` — приложение об этом не знает.

## 7. Тесты и стенды

- `testing`: `FakePush : PushService` с `deliver(topic, data, link)`; `FakePushTransport` для
  тестов хоста с `emit(data)` и `tap(data)`.
- `host/desktopTest`: `PushRouterTest` — таблица из §4 построчно; `PendingRouteTest` — consume
  один раз; `TopicStoreTest` — префикс и переподписка.
- `fixture`/`composeApp` `desktopTest`: `PushRoutingTest` — «тап до первой композиции открывает
  test-app с конфигом», «тап во время калькулятора закрывает его со снимком», «veto держит ссылку».
- `web-smoke`: `?test=1&link=unitool://app/test-app/echo?x=1` → `currentApp() == "test-app"`,
  `events()` содержит `Activated(source=deeplink)`.
- **Test App, секция Push**: `enabled`, кнопка «Request permission», поле темы + Subscribe/Unsubscribe,
  список `subscriptions`, последнее `PushMessage` (topic, data, link, время). Service Menu: кнопка
  «Simulate push» с JSON-полем → `PushTransport`-фейк на desktop/wasm → проходит весь роутер.
  Так вся цепочка проверяется без Firebase.
- Android instrumented (при устройстве): `adb shell am start -a android.intent.action.VIEW -d
  "unitool://app/test-app/echo?x=1"` → приложение открыто с `x=1`.

## 8. Что входит в MVP, что после

| В MVP (шаг 9 плана) | После (шаг 15) |
|---|---|
| `PushService` с `enabled`/`requestPermission`/`subscriptions`, `PushMessage` с `data: JsonObject` и `link` | `actual PushTransport` на Android: Firebase Messaging, `google-services.json`, канал уведомлений, `POST_NOTIFICATIONS` |
| `PushRouter`, `PendingRoute`, `TopicStore`, `DeviceRegistrar` (лог вместо сервера) | сервер регистрации и отправки |
| `expect PushTransport` с no-op actual на всех трёх таргетах + фейк для Service Menu | Web Push для wasm (Service Worker) — только если появится потребность |
| события `PushReceived`/`PushDropped`/`DeepLinkUnmatched`/`RouteDiscarded` | App Links (`https://…/app/…`) с `assetlinks.json` — когда будет домен |
| все тесты из §7 | |

Добавить FCM после этого = один `actual`-файл и `google-services.json`; роутер, тесты, Test App и
Service Menu уже работают на фейке.

## 9. Решения (в 01 как D24–D28)

- **D24.** Payload схемы 1 — data-only, `appId` обязателен, `link` — deep link того же приложения.
- **D25.** Пуш не активирует скрытое приложение; Locked → drop.
- **D26.** Тап по уведомлению = deep link; единственный вход — `ActivationService.fromDeepLink`.
- **D27.** Приложение не рисует уведомления и не навигирует из обработчика сообщения.
- **D28.** Транспорт — `expect object` с no-op actual; всё выше транспорта тестируется на фейке.
