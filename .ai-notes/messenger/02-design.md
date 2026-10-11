# 02 — Устройство: модель, сервер, пуши, ссылки, приложение

> **Версия:** 1.0 · **Дата:** 2026-10-10 · Это эскиз для ревью, а не контракт: точные схемы и фикстуры
> пишутся первым шагом (03, шаг 1), когда владелец ответит на вопросы.

## 1. Сущности и id

Id — base58 с префиксом, как в `lib/ids.ts` сервера.

| Id | Что | Где живёт |
|---|---|---|
| `pa_` / `pas_` | адрес приложения «Звонки» на этом устройстве (Superizer 0.7.0, план `twofa-browser/04-app.md` §1.2) | сервер `unitool_addresses`; секрет — `HostSecrets.forApp("calls")` |
| `u_` | аккаунт в «Звонках»: имя, буква-аватар, ссылка на `pa_` | `calls_users` |
| `inv_` | действующее приглашение аккаунта (одно; новое отзывает старое) | `calls_invites` (хранится SHA-256 токена) |
| — | контакт: пара `(u_owner, u_peer)`, флаг `blocked` | `calls_contacts`, две строки на пару |
| `call_` | звонок: `from`, `to`, `video`, `state`, времена | `calls_calls` (чистится через 30 дней) |

Аккаунт удаляется вместе с адресом `pa_` (каскад): удалил «Звонки» из Unitool → удалён аккаунт,
его приглашение и контакты у обоих.

## 2. API сервера (`/api/unitool/v1/calls`, `Authorization: Bearer pa_….pas_…`)

| Метод | Путь | Что |
|---|---|---|
| `PUT` | `/me` | создать/изменить аккаунт: `{name}` → `{userId, name}` |
| `GET` | `/me` | свой аккаунт |
| `DELETE` | `/me` | удалить аккаунт и контакты |
| `POST` | `/me/invite` | выпустить новую ссылку (старая перестаёт работать) → `{url}` |
| `GET` | `/invites/{inv}` | кто пригласил: `{name}` (только имя; без авторизации не отвечаем) |
| `POST` | `/contacts` | `{inv}` → контакт у обоих; пуш автору ссылки «X добавил вас» |
| `GET` | `/contacts` | список: `userId`, `name`, `blocked`, `available` |
| `DELETE` | `/contacts/{u_}` | убрать у себя + запретить звонки от него (D495) |
| `GET` | `/turn` | `{iceServers:[{urls, username, credential}], ttl}` — временный пароль coturn (D499) |
| `GET` | `/ws` | WebSocket сигналинга (D497) |

Ошибки — `{error:{code,message}}`, как везде. Лимиты: `POST /contacts` и `GET /invites` — по IP и по
аккаунту (подбор `inv` бессмыслен при 128 битах, но дешёвый лимит не помешает).

Сервер: новый слой `layers/app-calls` в `/prj/layers-fw/unitool-server` (по `AGENTS.md`: добавить в
`tsconfig*.json` и `vitest.config.ts`), миграции с `1791400000004`. WebSocket — пакет `ws` на том же
HTTP-сервере Express, путь `/api/unitool/v1/calls/ws`; Nginx Proxy Manager должен пропускать
`Upgrade` (галочка «Websockets Support»).

## 3. Сигналинг — сообщения WS

JSON, поле `t`. Сервер проверяет, что собеседник в контактах и не заблокировал, и пересылает
`sdp`/`ice`, не разбирая.

```
→ call.start   {to:"u_…", video:true}            ← call.created {callId}
                                                  (сервер: state=ringing, пуш собеседнику §4)
← call.incoming {callId, from:{userId,name}, video}   (тому, у кого открыт WS)
→ call.accept  {callId}  / call.decline {callId}  ← call.state {callId, state}
→ sdp          {callId, type:"offer"|"answer", sdp}
→ ice          {callId, candidate, sdpMid, sdpMLineIndex}
→ call.end     {callId}                            ← call.state {callId, "ended"}
→ call.stats   {callId, relay:true|false, ...}     (после звонка; доля TURN, §4 в 01)
← ping / → pong каждые 20 с
```

Машина состояний (D498): `ringing` → `accepted` | `declined` | `cancelled` (звонящий передумал) |
`missed` (40 с без ответа) | `busy` (у вызываемого уже идёт звонок) → `ended`. Оферту делает
звонящий после `accepted` (не раньше — не тратим TURN на неотвеченные). Обрыв WS во время звонка —
30 с на переподключение и ICE restart, потом `ended`.

## 4. Пуши

Все через `PushService.send` сервера, адресат — устройство адреса `pa_`, только если `calls ∈ apps`
(D429). Ссылка открывает `unitool://app/calls/…`.

| Событие | Этап A (D500) | Этап B |
|---|---|---|
| входящий звонок | видимый пуш FCM high: «Аня звонит» / «Аня: видеозвонок», канал `unitool.calls` (рингтон), iOS `interruption-level: time-sensitive`, `sound`; collapse/tag = `callId`; ссылка `unitool://app/calls/incoming?call=…` | Android: data high, `kind=call` → хост рисует `CallStyle` + полноэкранный интент; iOS: VoIP-пуш в APNs → CallKit |
| отменён / пропущен | видимый пуш с тем же tag/collapse-id «Пропущенный звонок от Ани» — заменяет «звонит» | Android: data → снять CallStyle, показать «пропущенный»; iOS: CallKit получает отбой по WS (§5 в 01), «пропущенный» — обычный пуш |
| «Борис добавил вас» | видимый пуш, ссылка `unitool://app/calls/contacts` | то же |

Для iOS этапа A `time-sensitive` нужна capability «Time Sensitive Notifications» (без ревью Apple).
Сервер получает поле `options` в `sendPush` для канала, звука, collapse-id; провайдер `apns` (D501)
появляется на этапе B.

## 5. Приглашение и App Links (D494, D496)

1. «Поделиться» → `POST /me/invite` → системное «Поделиться» с текстом
   «Позвони мне в Unitool: https://unitool.m42.cx/c/inv_…».
2. Сервер отдаёт:
   - `/.well-known/assetlinks.json` — пакет `cx.m42.unitool`, отпечатки ключа Play App Signing и
     upload-ключа;
   - `/.well-known/apple-app-site-association` — `applinks` для `TEAMID.cx.m42.unitool`, путь `/c/*`;
   - `/c/{inv}` — страница: «Аня приглашает вас в Unitool» + ссылки на Google Play и App Store +
     кнопка `unitool://app/calls/add?inv=…` (на случай, если приложение есть, но ссылка не перехвачена).
3. Android: intent-filter `https://unitool.m42.cx/c/*` с `autoVerify="true"`. iOS: entitlement
   `com.apple.developer.associated-domains = applinks:unitool.m42.cx`, `onContinueUserActivity`.
4. Хост переводит `https://unitool.m42.cx/c/{inv}` в `unitool://app/calls/add?inv={inv}` и кладёт в
   `PendingRoute`. Это маленькое правило в Unitool (не общий `linkDomain` D59 — тот пусть строится
   отдельно, если нужен).
5. «Звонки» на `add`: если аккаунта нет — сначала «Как вас зовут?», потом `GET /invites/{inv}` →
   «Добавить Аню?» → `POST /contacts`.

Если человек ставит Unitool по ссылке, приглашение теряется на установке (deferred deep link
сторы не дают без сторонних SDK). Страница `/c/` говорит: «Установите и откройте ссылку ещё раз».

## 6. Мини-приложение `apps/calls`

```kotlin
AppManifest(
    id = AppId("calls"), minHostContract = 6,
    metadata = AppMetadata(title = …"Звонки", icon = …),
    requires = setOf(CallSystem.Key /* этап B */),
    deepLinks = setOf("add", "incoming", "contacts"),
    push = PushUse.OnRequest,              // разрешение — при регистрации, не при добавлении из каталога
    networkHosts = setOf("unitool.m42.cx"),
)
```

Модули (по образцу `apps/twofa`): `CallsApp`, `CallsInstance`, `CallsApi` (Ktor HTTP + WS),
`CallEngine` (машина состояний клиента + Ktor WebRTC), `VideoView` (`expect/actual`: Android
`SurfaceViewRenderer`, iOS `RTCMTLVideoView`, desktop/web — заглушка), `ContactsStore`, `CallLog`
(локально), `CallsStrings`.

Экраны:
1. **Регистрация** — поле «Имя», «Готово». Здесь же системный вопрос про уведомления (`optIn()`).
2. **Контакты** — список (имя, буква), у каждого «аудио» и «видео»; сверху «Поделиться моей
   ссылкой». Долгий тап — «Удалить». Пусто → «Отправьте свою ссылку тому, кому хотите звонить».
3. **Исходящий / входящий** — имя, «Ответить / Отклонить» или «Отменить».
4. **Звонок** — видео собеседника на весь экран, своё — в углу; «Микрофон», «Камера»,
   «Повернуть камеру», «Динамик», «Завершить»; таймер.
5. **Журнал** — последние звонки (входящие/исходящие/пропущенные), только на телефоне.

Разрешения у человека спрашиваются по месту: микрофон — при первом звонке, камера — при первом
видео, полноэкранный показ (Android, этап B) — один раз с объяснением.

## 7. Изменения вне `apps/calls`

| Где | Что | Этап |
|---|---|---|
| Superizer 0.7.0 / контракт 6 | `pa_`-адрес + `PushUse.OnRequest` (`twofa-browser/04-app.md` §1 — общий кусок, D502) | A |
| Superizer | крючок срочного пуша: `SuperizerMessagingService` отдаёт `kind=call` обработчику хоста до роутера; `DeviceApi` несёт `voipToken` | B |
| Unitool `composeApp` (Android) | `minSdk 28` (D489), Ktor 3.6.x, канал `unitool.calls`, App Links intent-filter, разрешение `RECORD_AUDIO`; этап B — `CallSystem` на `core-telecom`, `CallStyle`, полноэкранная `IncomingCallActivity` (`showWhenLocked`, `turnScreenOn`), FGS `phoneCall` → `microphone|camera` | A, B |
| Unitool `iosApp` (Swift) | SPM `WebRTC.xcframework`, `NSMicrophoneUsageDescription`, `associated-domains`, Time Sensitive; этап B — `PushKit` + `CallKit` + `RTCAudioSession`, `UIBackgroundModes += voip, audio`, мост в Kotlin `CallSystem` | A, B |
| unitool-server | слой `app-calls`, WS, `/.well-known/*`, страница `/c/`, `sendPush` с `options`; этап B — провайдер `apns` (VoIP) и `voipToken` в `unitool_devices` | A, B |
| coturn | отдельный контейнер на хосте с белым IP, общий секрет с сервером (env `TURN_SECRET`, `TURN_URLS`) | A |
| Сторы | Play: Data safety (имя, контакты, метаданные звонков, IP), декларации FGS `phoneCall`/`microphone`/`camera` с видео, `USE_FULL_SCREEN_INTENT`; App Store: privacy labels, описание звонков, Китай (D504) | A (данные), B (декларации) |
