# 01 — Варианты, библиотеки, готовые мессенджеры, пуши звонка

> **Версия:** 1.0 · **Дата:** 2026-10-10 · Источники проверены 2026-10-10; что не подтвердилось —
> помечено **[не проверено]**, цифры только от продавцов — **[продавец]**.

## 1. Три варианта связи

| | (а) WebRTC P2P + свой сигналинг + STUN/TURN | (б) «другой P2P» (libp2p, DHT как Jami) | (в) через медиасервер (SFU: LiveKit, mediasoup, Janus, Jitsi) |
|---|---|---|---|
| Как идёт медиа | напрямую телефон↔телефон; при закрытом NAT — через TURN (≈ 20% звонков **[продавец]**) | data-каналы; аудио/видео поверх надо строить самим | всегда через сервер |
| Шифрование | DTLS-SRTP сквозное | своё | до сервера; сквозное — только с insertable streams |
| KMP | Ktor WebRTC, webrtc-kmp | нет медиа: `@libp2p/webrtc` — только data channels | официального KMP SDK у LiveKit нет; есть Android и Swift SDK, сторонний `compose-livekit` 0.1.0 (один автор, iOS пробовали только в симуляторе) |
| Пуши и «звонит при закрытом приложении» | своё | своё (Jami всё равно держит DHT-прокси + FCM/APNs) | своё |
| Сервер | наш unitool-server (сигналинг) + coturn | ретрансляторы всё равно нужны (Berty на libp2p упёрся в CGNAT и ушёл на реле) | SFU + TURN (у LiveKit встроен) |
| Трафик | платим только за реле-звонки | — | весь трафик ≈ 5× от (а) |
| Группы | плохо (mesh до 3–4) | — | да |
| Вывод | **берём** (D487) | нет: работы больше, выигрыша для 1:1 нет | только если понадобятся группы (Q217) |

Важно: **ни один вариант не решает пуш входящего звонка, CallKit и Android Telecom** — это одинаковая
работа везде, и она самая трудная (§5–§6).

## 2. Библиотека WebRTC для Kotlin Multiplatform

| | `io.ktor:ktor-client-webrtc` | `com.shepeliev:webrtc-kmp` |
|---|---|---|
| Версия | 3.6.0 (2026-09-16); Android с 3.3.0, рабочий iOS с 3.4.0, исправления iOS в 3.5.1, JVM в 3.6.0 | 0.125.11 (2025-09-07), последний коммит 2025-09-03 |
| Статус | `@ExperimentalKtorApi`, выпускается с каждым Ktor | не развивается 13 мес.; собран на Kotlin 2.1.21 (у нас 2.4.10 — совместимость klib **[не проверено]**) |
| Цели | Android, iOS (arm64, x64, simArm64), JS, wasmJs, JVM | Android, iOS, JS, wasmJs |
| Под капотом | Android: `io.getstream:stream-webrtc-android:1.3.10` (api-зависимость); iOS: `WebRTC-SDK` 137.7151.04 (линкуем сами); JVM: `webrtc-java` 0.16.0 | WebRTC M125: `io.github.webrtc-sdk:android:125.6422.06.1`, iOS pod `WebRTC-SDK` 125.6422.07 |
| Android minSdk | **28** (из манифеста AAR 3.6.0) — у нас 24 | 21 |
| Захват | `createAudioTrack` / `createVideoTrack` с `facingMode`; Camera2 / AVFoundation; разрешения — наши | `MediaDevices.getUserMedia`, `enumerateDevices`, `switchCamera()`, захват экрана на Android |
| Чего нет | экрана, выбора устройства, simulcast, transceivers (KTOR-9572) | — |
| Рендер в Compose | нет; в доке — `SurfaceViewRenderer`+`EglBase` в `AndroidView`, `RTCMTLVideoView` в `UIKitView` | нет; в примере то же самое |
| iOS без CocoaPods | в доке только CocoaPods; Music Assistant (658★) качает `WebRTC.xcframework` сам и линкует `-F…`; webrtc-sdk/Specs даёт `Package.swift` (SPM) | README: «CocoaPods или SPM», описан только CocoaPods; issue #188 про SPM закрыт молча |
| Известные проблемы | эксперимент; нет поворота камеры одним вызовом (новый трек + `replaceTrack` — **[не проверено]**) | #192: на реальном iPhone форматы камеры читаются мусором, видео замерзает — без ответа с 2026-07 |

**Выбор: Ktor** (D488). Поддерживается, новее WebRTC, Ktor у нас уже есть, API хватает на 1:1
(треки, `replaceTrack`, ICE restart, статистика, data channels). Unitool на SPM, Podfile нет — значит
спайк шага 0 обязан доказать сборку iOS с `WebRTC.xcframework` через SPM. Если провалится — webrtc-kmp
с тем же xcframework M125.

Ссылки: https://ktor.io/docs/client-webrtc.html ·
https://github.com/ktorio/ktor/tree/main/ktor-client/ktor-client-webrtc ·
https://github.com/shepeliev/webrtc-kmp · https://github.com/webrtc-sdk/Specs

## 3. Готовые мессенджеры и примеры — что брать

Лицензии по GitHub API 2026-10-10. AGPL/GPL — **только идеи, код не переносим** (иначе Unitool
становится AGPL).

| Проект | Язык, лицензия | Что там полезно нам |
|---|---|---|
| [ktorio/ktor-chat](https://github.com/ktorio/ktor-chat) | Kotlin, Compose MP; лицензию проверить перед копированием | ближе всего к нам: Ktor WebRTC + HTTP-сигналинг (`HttpSignalingClient.kt`, сервер `Signaling.kt`), `VideoRenderer` actual для Android/JVM/wasm. iOS — только в ветке `zibet27/ios` (2025-10, Podfile) |
| [GetStream/webrtc-in-jetpack-compose](https://github.com/GetStream/webrtc-in-jetpack-compose) | Kotlin, **Apache-2.0** | Android 1:1 с WebSocket-сигналингом: порядок offer/answer/ICE, рендер, аудио — можно копировать |
| [shepeliev/webrtc-kmp `sample/`](https://github.com/shepeliev/webrtc-kmp/tree/main/sample) | Kotlin, **Apache-2.0** | Compose MP рендер на iOS (`UIKitView`+`RTCMTLVideoView`) и Android; петля без сигналинга |
| [music-assistant/mobile-app](https://github.com/music-assistant/mobile-app) | Kotlin MP | как подключить `WebRTC.xcframework` к Ktor WebRTC без CocoaPods (`build.gradle.kts`, `check-pr-ios.yml`) |
| [Sheikh-Areeb/KMP-WebRTC](https://github.com/Sheikh-Areeb/KMP-WebRTC) | Kotlin MP, 0★ | форма целиком: комнаты, поворот камеры, mute, Node `ws`-сервер + Ktor WebSocket — свой `expect/actual` поверх Stream WebRTC (M137) |
| [Bouyahyaa/KMPCALL](https://github.com/Bouyahyaa/KMPCALL) | Kotlin MP, 24★ | webrtc-kmp + сокет-сигналинг + отдельный бэкенд; CocoaPods |
| [SimpleX Chat](https://github.com/simplex-chat/simplex-chat) | Haskell + Kotlin MP + Swift, AGPL | идеи: Android-звонок в WebView (`call.html`) — **не наш путь**; iOS нативно: CallKit, PushKit, NSE, CallKit выключен для `SKStorefront == "CHN"`; [спека звонков](https://github.com/simplex-chat/simplex-chat/blob/stable/apps/multiplatform/spec/services/calls.md) |
| [Signal-Android](https://github.com/signalapp/Signal-Android) | Kotlin, AGPL | `core-telecom` (`AndroidTelecomUtil`, `TelecomCallController`) — как правильно вести звонок через Telecom |
| [Element X Android](https://github.com/element-hq/element-x-android) / [iOS](https://github.com/element-hq/element-x-ios) | Kotlin / Swift, AGPL | `RingingCallNotificationCreator`: `CallStyle` + `setFullScreenIntent`, FGS microphone; iOS — CallKit/PushKit в `ElementCallService` |
| [Threema Android](https://github.com/threema-ch/threema-android), [Session](https://github.com/session-foundation/session-android) | Kotlin/Java, AGPL/GPL | нативный WebRTC 1:1 (`ch.threema.app.voip`, `webrtc.*` по образцу Signal) |
| Jami, Linphone, Briar | GPL | не подходят: C++-демон / SIP / нет звонков |

**Полного открытого Compose MP 1:1 звонка Android+iOS с CallKit нет.** Переносить целиком нечего;
переносим порядок сигналинга и рендер (Apache), остальное пишем по образцу идей.

## 4. NAT, STUN, TURN

- Без TURN не соединяется ≈ 10–25% звонков (выше в мобильных сетях с CGNAT и в офисах)
  **[продавец; независимого исследования нет]**. Закладываем 20% и меряем долю relay сами
  (статистика ICE-кандидатов после звонка → сервер).
- **coturn** (D499): `listening-port 3478` UDP/TCP, `tls-listening-port 443` (или 5349, если 443
  занят), `min-port/max-port` 49152–65535/UDP, `use-auth-secret` + `static-auth-secret`. Сервер Unitool
  выдаёт `username = "<expiry>:<u_>"`, `password = base64(HMAC-SHA1(secret, username))` и с coturn
  не общается. Нужен **хост с белым IP и открытыми UDP-портами** — Nginx Proxy Manager UDP не
  проксирует (Q221).
- Трафик реле: видео 720p ≈ 1,5 Мбит/с в каждую сторону → ≈ 1,35 ГБ на час звонка через TURN;
  аудио ≈ 50 кбит/с — пренебрежимо.
- Публичный `stun.l.google.com:19302` бесплатен, условий и гарантий не опубликовано — только запасной.
- Хостинг TURN: Cloudflare — $0.05/ГБ, первые 1000 ГБ/мес бесплатно, бесплатный STUN
  `stun.cloudflare.com`; Metered — от $99/мес; Twilio — $0.40–0.80/ГБ.

## 5. iOS: как телефон «звонит»

- **PushKit VoIP + CallKit.** С iOS 13 SDK каждый VoIP-пуш обязан закончиться
  `reportNewIncomingCall` в CallKit внутри обработчика; иначе iOS убивает приложение, а после
  повторов перестаёт доставлять VoIP-пуши. Приложение без CallKit не имеет права на PushKit.
  ([Apple](https://developer.apple.com/documentation/pushkit/responding-to-voip-notifications-from-pushkit))
- iOS 26.4 смягчил: `PKVoIPPushMetadata.mustReport == false` (приложение на переднем плане, уже идёт
  звонок, пуш опоздал) — можно не показывать. На старых iOS правило прежнее.
- **FCM VoIP не доставляет** — шлём сами в APNs: `apns-push-type: voip`,
  `apns-topic: cx.m42.unitool.voip`, `apns-expiration: 0`, тело до 5 КБ, ключ `.p8`
  (токен-авторизация годится для всех типов). VoIP-токен — отдельный от FCM.
- Отбой («звонок отменён») Apple советует слать по своему соединению, а не вторым VoIP-пушем.
  У нас: приложение, поднятое VoIP-пушем, открывает WS и узнаёт состояние; если `cancelled` —
  `reportCall(endedAt:reason:.remoteEnded)`.
- Путь Signal/Element/SimpleX (обычный пуш → Notification Service Extension →
  `reportNewIncomingVoIPPushPayload`) требует entitlement `usernotifications.filtering` — Apple выдаёт
  неохотно, VoIP-приложениям советует PushKit. **Нам — PushKit.**
- CallKit не работает в симуляторе — только реальные iPhone.
- Китай: с 2018 Apple не пропускает CallKit в китайский App Store (по требованию MIIT); на 2025 в силе
  **[официального обновления нет]** (D504, Q226).
- Фоновый режим `voip` без настоящих звонков отклоняют по 2.5.4 — у нас звонки настоящие.
- Делается в Swift в `iosApp` (PushKit, CallKit, `RTCAudioSession` в ручном режиме: аудио включается
  только в `provider(_:didActivate:)`), общий Kotlin получает события через интерфейс. CallKit из
  Kotlin/Native возможен, но отчёт должен быть синхронным в колбэке PushKit — Swift надёжнее.

## 6. Android: как телефон «звонит»

- **FCM high-priority data** (наш сервер уже шлёт `priority: HIGH`). Если такие сообщения не приводят
  к видимому уведомлению, FCM за 7 дней понижает приоритет приложению — каждый звонковый пуш обязан
  показать уведомление.
- **Уведомление `NotificationCompat.CallStyle.forIncomingCall(person, decline, answer)`** в отдельном
  канале `unitool.calls` (высокая важность, рингтон, вибрация). Система запрещает CallStyle без
  foreground service или полноэкранного интента — **полноэкранный интент ставим всегда**; без права
  уведомление всё равно постится как heads-up.
- **`USE_FULL_SCREEN_INTENT`**: с Android 14 Play даёт автоматически только приложениям, чья
  основная функция — звонки или будильник; декларация в Play Console обязательна. Unitool — нет;
  значит спрашиваем человека (`ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`, `canUseFullScreenIntent()`)
  и честно живём без него: на заблокированном экране — уведомление, а не экран звонка.
- **Foreground service**: с Android 14 сервис типа `microphone`/`camera` нельзя *создать* из фона.
  Схема: тип `phoneCall` (нужны `MANAGE_OWN_CALLS` + `FOREGROUND_SERVICE_PHONE_CALL`), а
  `microphone|camera` добавляем после «Ответить» (это действие пользователя). Каждый тип — декларация
  с видео в Play Console.
- **`androidx.core:core-telecom`** 1.0.1 стабильная (1.1.0-rc01 от 2026-10-07): `CallsManager`
  поверх ConnectionService, Bluetooth/громкая связь, приоритет на время звонка. Signal и Linphone
  уже на нём. Сырой ConnectionService не пишем.
- Разрешения: `RECORD_AUDIO`, `CAMERA` (есть), `MODIFY_AUDIO_SETTINGS`, `BLUETOOTH_CONNECT`,
  `POST_NOTIFICATIONS` (есть), `FOREGROUND_SERVICE*`, `MANAGE_OWN_CALLS`, `USE_FULL_SCREEN_INTENT`.

## 7. Сколько это обычно занимает

Честных разборов «соло-разработчик сделал за N недель» не нашлось. Ориентиры **[продавец]**:
«две недели только на iOS-обвязку» (CallKit/PushKit/аудио — самая долгая фаза), 8–12 недель на полный
1:1 с CallKit, сигналингом, TURN и записью у команды; CallKit отдельно — 2–3 недели. Наша оценка —
[03](03-plan-and-estimate.md): ≈ 27 рабочих дней, потому что сервер, пуши, регистрация устройства,
QR/камера, хранилище секретов и CI уже есть.
