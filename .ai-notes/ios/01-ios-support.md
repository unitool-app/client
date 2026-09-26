# 01 — iOS: Superizer и Unitool на iPhone

> **Версия:** 1.1 · **Дата:** 2026-09-26 · **Статус:** собрано на CI-Mac agentiz для устройства
> (unsigned IPA) и для симулятора (§6). Ещё не запускалось: IPA ждёт подписи, а Firebase ждёт
> шагов владельца (§5).
>
> Задание владельца: «сделай везде поддержку ios», «пуши надо делать через fcm», «паттерны есть в
> agentiz mobile client». Затем: bundle ID `cx.m42.unitool`, iOS-приложение в Firebase, ключ APNs
> тот же, что у agentiz.

## 1. Что сделано

**Superizer 0.3.0.** Во всех публикуемых модулях добавлены таргеты `iosArm64` и
`iosSimulatorArm64`. В контракт 2 добавлен `Platform.Ios`. iOS-реализации:

| Что | Android | iOS |
|---|---|---|
| Prefs | SharedPreferences | `NSUserDefaults(suiteName = "cx.m42.superizer.store")` |
| Сеть | OkHttp | Ktor Darwin (NSURLSession) |
| Жизненный цикл | `ON_START`/`ON_STOP` | `willEnterForeground`/`didEnterBackground` (не resign-active) |
| Связь | ConnectivityManager | `NWPathMonitor` |
| Вибрация | Vibrator | `UIImpactFeedbackGenerator` |
| Язык | `Locale.getDefault()` | `NSLocale.preferredLanguages` |
| Замок | `BiometricPrompt` | `LAContext`, `deviceOwnerAuthentication` (Face ID / Touch ID → код-пароль) |
| «Экран погас» | `ACTION_SCREEN_OFF` | `protectedDataWillBecomeUnavailable` (телефон заблокирован) |
| `FLAG_SECURE` | флаг окна | заглушка поверх окна при resign-active и во время записи экрана |
| Пуши | FCM в библиотеке | FCM в Swift-таргете приложения, мост `PushTransport.messaging` |

**Unitool:**
- iOS-таргеты во всех модулях.
- `composeApp`: статический фреймворк `ComposeApp`, в `iosMain` лежат `MainViewController()` и
  `UnitoolIos` — единственный объект, с которым говорит Swift.
- Сервисы:
  - `IosQrScanner` — AVFoundation, детектор QR самой системы;
  - `IosFileSharer` — `UIActivityViewController`;
  - `IosKeychainVault` — ECIES под P-256 ключом Secure Enclave, на симуляторе программный ключ в
    Keychain.
- 2FA:
  - `SecRandomCopyBytes`;
  - буфер обмена с `expirationDate` 60 с и `localOnly`.
- `iosApp/` — проект Xcode по образцу agentiz:
  - SwiftUI-обёртка, `onOpenURL` для `unitool://`;
  - `AppDelegate` с FirebaseMessaging через SPM;
  - Info.plist: камера, Face ID, схема `unitool`, `remote-notification`;
  - entitlements `aps-environment`;
  - `PrivacyInfo.xcprivacy`, в нём UserDefaults с причиной CA92.1;
  - иконка 1024 из `play/generate-assets.py`.
- `verify.sh` в обоих репозиториях компилирует iOS-klib на Linux. На Mac Unitool дополнительно
  собирает приложение для симулятора.
- `.github/workflows/ios.yml` — сборка для симулятора на `macos-15`, пока только вручную.

## 2. Решения

- **D145.** iOS-klib собираются и публикуются с Linux: Kotlin 2.4 кросс-компилирует Apple-таргеты,
  а в дистрибутиве Kotlin/Native для Linux есть платформенные библиотеки `ios_arm64` и
  `ios_simulator_arm64`. Mac нужен только для линковки фреймворка, сборки Xcode и iOS-тестов.
- **D146.** Пуши на iOS идут через FCM, как в agentiz. Firebase iOS SDK подключён в Swift-таргет,
  потому что Kotlin-библиотека не может его слинковать. Swift передаёт в Kotlin:
  - токен, из `MessagingDelegate`;
  - сообщения, из `didReceiveRemoteNotification`;
  - нажатия;
  - мост для подписки на топики.

  Сервер шлёт одно и то же на обе платформы, Google сам говорит с APNs.
- **D147.** Firebase на iOS необязателен, как `google-services.json` на Android.
  `GoogleService-Info.plist` не лежит в репозитории: фаза «Copy Firebase Config» копирует его, только
  если файл есть, и только тогда Swift вызывает `FirebaseApp.configure()`.
- **D148.** Скриншот на iOS запретить нельзя, и код этого не изображает. Закрыты переключатель
  приложений и запись, зеркалирование и AirPlay (`UIScreen.isCaptured`).
- **D149.** Swift видит только `UnitoolIos` и протокол `FirebaseMessagingBridge` из `composeApp`.
  Superizer в фреймворк не экспортируется.
- **D150.** Ссылки и нажатия, пришедшие до создания хоста (холодный старт по уведомлению),
  `UnitoolIos` придерживает. Нажатие отдаётся роутеру в очереди main после запуска его
  коллектора: у `SharedFlow` с `replay = 0` нет подписчика до этого момента.
- **D151.** `ITSAppUsesNonExemptEncryption` в Info.plist не выставлен. Экспорт 2FA шифрует age
  собственной реализацией, и объявление об экспортном контроле — решение владельца в App Store
  Connect.

## 3. Проверено

- Все модули обоих репозиториев компилируются под `iosArm64` и `iosSimulatorArm64`.
- `publishToMavenLocal` выкладывает `*-iosarm64` и `*-iossimulatorarm64`.
- Дампы ABI покрывают iOS.
- `superizer/scripts/verify.sh` — PASS. `unitool/scripts/verify.sh` — см. §6.
- Plist-файлы проходят `plistlib`.

**Не проверено ничего, что требует Mac:** линковка фреймворка, сборка Xcode, запуск на симуляторе и
iPhone, Face ID, камера, Secure Enclave, FCM на iOS. Код написан по API iOS SDK и проходит
компилятор, но ни разу не выполнялся.

## 4. Риски, которые покажет самопроверка на iPhone (ssh-new/06 §3–§4) или первая сборка на Mac

- Линковка: `UIViewLayoutRegion` на SDK старше iOS 26. Флаг есть в `BUILD-IOS.md` и `ios.yml`.
- `IosKeychainVault`: самый объёмный код на CoreFoundation. Если Secure Enclave откажет с
  `kSecAccessControlPrivateKeyUsage`, сработает откат на программный ключ; проверить на устройстве.
- `SecureWindow`: `keyWindow` устарел, но работает; на iPad с несколькими окнами — не проверено.
- `IosQrScanner`: вёрстка на фреймах, только портрет.

## 5. Что нужно от владельца

1. **Firebase, проект `unitool-b1529`.** Ни один сервисный аккаунт на машине туда не пускает:
   `firebase-adminsdk-fbsvc@agentiz-de114` получает 403, у Play-аккаунта не включён Firebase
   Management API.
   - Add app → iOS → bundle `cx.m42.unitool` → скачать `GoogleService-Info.plist` и положить в
     `iosApp/iosApp/` (он в `.gitignore`). Второй вариант — дать сервисному аккаунту роль
     Firebase Admin в `unitool-b1529`, тогда приложение создам я через API.
   - Project settings → Cloud Messaging → Apple app configuration → APNs Authentication Key:
     `AuthKey_9SSWKZ9HJ9.p8` из `/prj/agentiz/data/Agentiz-push-config/`, Key ID `9SSWKZ9HJ9`,
     Team ID `5K5GDFV386`. Публичного API для загрузки ключа нет, только консоль.
2. **App ID `cx.m42.unitool`** в Apple Developer с Push Notifications и профиль для него.
3. ~~Mac для сборки.~~ Владелец разрешил 2026-09-26 («можно отправлять на сборку»), сборка — §6.
   Осталась **подпись и установка**: на CI-Mac нет сертификата, подписывает Mac с ключом по
   `sign-and-install.sh` agentiz (профиль `5K5GDFV386.*` подходит, но без Push Notifications).
4. Экспортный контроль (D151) при первой загрузке в App Store Connect.

## 6. Первая сборка на Mac (2026-09-26)

CI-Mac agentiz (`ci@10.10.0.115` через `xziy@ny.m42.cx:2522`): macOS 15.7.7, Xcode 16.3, SDK
iOS 18.4, JDK 17. Каталог `~/build/unitool-ios/{superizer,unitool}`. Туда скопированы только файлы
из git, включая незакоммиченные, без `.secrets/`, `local.properties` и `google-services.json`.
Ничего не коммитилось и не пушилось.

- **Устройство:** `Release`, `iphoneos`, без подписи. **BUILD SUCCEEDED**. Бинарник arm64, 40 МБ,
  minOS 15.0, `cx.m42.unitool` 1.0 (1). Unsigned IPA: `build/ios/Unitool-unsigned.ipa` (11 МБ),
  в `.gitignore`.
- **Симулятор:** `Debug`, `iphonesimulator`. **BUILD SUCCEEDED**, но запустить нельзя: этот Mac
  на Intel, его симулятор принимает только x86_64, а таргета `iosX64` у нас нет. Запуск на
  симуляторе возможен на Mac с Apple Silicon (раннер `macos-15` в `ios.yml` как раз такой).
- **Исправлено по ходу:**
  - первая сборка для симулятора упала на `validateArchitecturesForEmbedAndSign…`: Xcode для
    `generic/platform=iOS Simulator` просит и `x86_64`. В `Config.xcconfig` добавлено
    `EXCLUDED_ARCHS[sdk=iphonesimulator*]=x86_64`;
  - лишнее приведение в `IosQrScanner` (единственное предупреждение Kotlin).
- Флаг `-U _OBJC_CLASS_$_UIViewLayoutRegion` из риска §4 нужен: SDK 18.4.
- Без `GoogleService-Info.plist` сборка предупреждает, пуши выключены (D147).
- Сборка с нуля занимает ~12 мин Gradle + SPM Firebase, повторная — несколько минут.
