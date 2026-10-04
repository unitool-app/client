# 06 — Диагностика хранилища секретов и самопроверка на устройстве

> **Версия:** 1.0 · **Дата:** 2026-09-26 · **Статус:** принято к реализации вместе с 05.
>
> Задание владельца. (1) Mac нет — значит, пусть код сам проверит себя на iPhone во время
> исполнения и напишет результат: «вот это работает, вот это нет», чтобы мы знали по реальному
> результату. (2) Везде честные пометки в интерфейсе, если ключи не зашифрованы или небезопасны.
> (3) Отдельной ниточкой через всё — диагностика: в каком состоянии хранилище, как устроено, где
> лежит, что надо переделать; если хранение не реализовано — так и сказать и дать рекомендацию.
> Интерфейс получает это из самого приложения. (4) Тексты — английские, внутри Unitool там, где
> показываются, с возможностью перевода.

## 1. Одна ниточка: `StorageDiagnostics`

Сервис хоста, контракт 3, в `superizer:core`. Не текст, а **коды и факты**; слова — у того,
кто показывает (§6).

```kotlin
public interface StorageDiagnostics {
    /** Latest report; refreshed by [inspect] at host start and by [selfTest] on demand. */
    public val report: StateFlow<DiagnosticsReport>
    /** Cheap, no prompts, < 100 ms: capabilities, key attributes, heartbeat. Runs at every host start. */
    public suspend fun inspect(): DiagnosticsReport
    /** Full run: exercises the real crypto with throwaway keys (§3). May prompt once if [withConfirmation]. */
    public suspend fun selfTest(withConfirmation: Boolean): DiagnosticsReport
    /** Plain-text report for sharing (§5): English, codes and facts, raw platform errors, never secrets. */
    public fun exportText(): String
}

public data class DiagnosticsReport(
    val at: Long, val hostVersion: String, val platform: Platform, val device: DeviceFacts,
    val findings: List<Finding>,
    val selfTest: SelfTestResult?,                // null: never run on this install
) { val worst: Status get() = findings.maxOf { it.status } }

public data class Finding(
    val subject: Subject,                         // HostSecrets | Key(id) | AppSecrets(appId) | Backup | Device | SelfTest
    val status: Status,                           // Ok < Info < Attention < Unsafe < Missing < Broken   (ordered)
    val code: String,                             // stable id, e.g. "vault.software-key" — the key into the words table
    val facts: Map<String, String>,               // "location" = "android-keystore", "level" = "2", "hardware" = "tee", ...
    val actions: List<Action>,                    // AddSshKey, SetPassphrase, Rekey, EnableScreenLock, RunSelfTest, MakeBackup, ShareReport, ImportKeyAgain
    val evidence: String? = null,                 // raw error text from the platform, untranslated, for the report
)
```

Статусы, по возрастанию: **Ok** всё как обещано · **Info** факт без действия (уровень 3 на
desktop) · **Attention** есть что сделать (бэкапа не было 30 дней) · **Unsafe** данные
защищены слабее, чем написано в листинге (программный ключ, ключ уровня 3 в браузере) ·
**Missing** хранение не реализовано или не настроено, данные открыты · **Broken** самопроверка
упала, данные могут быть нечитаемы.

Приложения получают то же через `runtime.diagnostics.forApp(appId): StateFlow<List<Finding>>`
— только свои `AppSecrets` и общие `HostSecrets`/`Device`. `HostInfo.secretsLevel` из 05
становится ненужным: уровень — это факт `level` в finding `HostSecrets`.

## 2. Откуда берутся находки

| Источник | Когда | Что даёт |
|---|---|---|
| **Осмотр** (`inspect`) | каждый старт хоста, после обновления | возможности устройства (`KeyringDevice`), атрибуты существующих ключей (`KeyInfo.securityLevel`, `kSecAttrTokenID`), где лежат файлы и исключены ли из бэкапа, есть ли блокировка экрана, дата последнего бэкапа, дата и итог последней самопроверки |
| **Пульс** (`heartbeat`) | в осмотре | при первом запуске хост запечатывает константу `superizer-heartbeat` своим сейфом и хранит; осмотр её открывает. Не открылась → `vault.key-lost` или `vault.broken`, **до** того, как человек откроет 2FA и увидит «Unreadable». Пользовательских данных не трогает |
| **Самопроверка** (`selfTest`) | по кнопке; автоматически один раз после установки и после обновления, без подтверждения | §3 |

## 3. Самопроверка: код, который проверяет себя на устройстве

Одна реализация, два запуска: из интерфейса (Settings → Storage & security → Run self-test)
и из тестов (`KeystoreProbeTest` шага 0 и `iosSimulatorArm64Test` — это те же шаги под
тест-раннером, D160 остаётся в силе). Каждый шаг — `SelfTestStep(code, passed, millis,
evidence)`. Все ключи — временные, со своим alias/tag `selftest.<nanoTime>`, удаляются в
`finally`; продуктовый ключ используется только в пульсе.

| Шаг | Что делает | Проверяет |
|---|---|---|
| `vault.roundtrip` | seal → open константы временным ключом сейфа платформы | сейф вообще работает (на iOS — что `IosKeychainVault` выполняется: ios/01 §4) |
| `vault.hardware` | атрибуты временного ключа | `securityLevel` / `kSecAttrTokenID`; Software → `vault.software-key` |
| `vault.tamper` | бит в шифртексте → open должен вернуть null | GCM/ECIES не пропускает подмену |
| `vault.nonce` | два seal одного текста различны | нонс не повторяется |
| `wrap.auth-required` | временный ключ-обёртка уровня 2 с привязкой к аутентификации; попытка операции без промпта (`LAContext.interactionNotAllowed = true` / без `CryptoObject`) | чип **отказывает** без человека (I7); ожидаемая ошибка записывается как evidence — это P7 и P11 |
| `wrap.confirmed` | только при `withConfirmation`: та же операция с промптом | «с подтверждением получается» (03 §6.4) |
| `wrap.passcode-class` | создать элемент `WhenPasscodeSetThisDeviceOnly` / ключ с `setUnlockedDeviceRequired` | P12; без кода → `device.no-screen-lock` |
| `enclave.sign` | iOS: временный P-256 в Secure Enclave, подпись, проверка своим `SshSig.verify` после DER → mpint | P10, P13 |
| `passphrase.roundtrip` | `OpenSshKeyFile` с временным Ed25519: зашифровать паролем, расшифровать, время bcrypt 24 раунда | уровень 3 работает; P9, P14; медленнее 3 с → `passphrase.slow` |
| `age.roundtrip` | age на временный ключ и обратно в commonMain | сейф хоста на ssh-ключе работает без чипа |
| `storage.location` | где лежат prefs/файлы записей; Android — файл не в правилах бэкапа, iOS — `isExcludedFromBackup` читается назад `true`, web — origin | I10; факты `location` |
| `memory.zeroed` | `SecretBytes` после `close()` — нули | I6; на wasm только отчёт |
| `report.clean` | сканер канареек по собственному отчёту | отчёт не несёт секретов (I5) |

Итог: `SelfTestResult(steps, passed: Boolean, at, hostVersion, deviceFacts)`, хранится в
prefs хоста. Упавший шаг → finding `selftest.failed` со статусом Broken и evidence — сырым
текстом ошибки платформы.

## 4. Как это заменяет Mac

Цикл без Mac у нас под рукой:

1. `ios.yml` собирает **для устройства** и отдаёт в TestFlight. Нужны секреты репозитория:
   сертификат разработчика и профиль (или ключ App Store Connect API), bundle
   `cx.m42.unitool` с профилем — это шаг 2 из ios/01 §5, **действие владельца** (вопрос 88).
   Пока их нет, лента даёт только симуляторную сборку, как сейчас.
2. Владелец ставит сборку, открывает Settings → Storage & security → **Run self-test** (с
   подтверждением: один Face ID), затем **Share report** и отправляет текст.
3. Отчёт говорит по шагам, что прошло, а что нет, с сырыми `CFError`. Это ответы на P10–P13 и
   на «выполняется ли `IosKeychainVault`» из ios/01 §4 — по реальному результату, а не по
   компилятору.
4. Правка → новая сборка → снова п. 2. Один оборот — минуты macOS-раннера, не покупка Mac.

Так же на Android у тестеров Play alpha: тот же экран, тот же отчёт.

### 4.1. Вопрос 88 подробно: два пути доставить сборку на iPhone

Сегодня: `ios.yml` собирает только для симулятора без подписи; у agentiz — обратный порядок:
удалённый CI-Mac собирает **без подписи**, а Mac владельца подписывает своим development-
сертификатом (SHA-1 `60A1A941…`) по wildcard-профилю `5K5GDFV386.*` и ставит по кабелю
(`agentiz/mobile-client/BUILD-IOS.md`, `sign-and-install.sh`). Wildcard-профиль не несёт
Push, а `iosApp.entitlements` требует `aps-environment`, поэтому с ним сборка Unitool упадёт.

**Общее для обоих путей, в Apple Developer, делает владелец:**
1. Identifiers → App IDs → `cx.m42.unitool` (explicit) с capability **Push Notifications**.
   Это же шаг 2 из ios/01 §5.

**Решение владельца 2026-09-26: делаем оба.** Оба — задания в одном `ios.yml` (сделано:
`simulator`, `device`, `testflight`; последнее само пропускается, пока нет секретов).
`scripts/ios-sign-and-install.sh` и `ExportOptions.plist` написаны, BUILD-IOS.md обновлён.

**Путь B — как agentiz, без секретов в репозитории** (сначала):
2. Profiles → **Development**-профиль для `cx.m42.unitool`: dev-сертификат владельца + UDID
   iPhone. Скачать на Mac владельца.
3. `ios.yml` получает вторую задачу: `-sdk iphoneos -configuration Release
   CODE_SIGNING_ALLOWED=NO` → артефакт `Unitool.app` без подписи.
4. Владелец скачивает артефакт, `sign-and-install.sh` (копия из agentiz с профилем из п. 2) →
   iPhone по кабелю → Settings → Storage & security → Run self-test → Share report.
   Цена оборота: минуты macOS-раннера плюс две минуты на Mac владельца. Секретов в GitHub —
   ноль, кроме уже известного `GOOGLE_SERVICE_INFO_PLIST_BASE64` (нужен только для пуша, не
   для самопроверки).

**Путь A — TestFlight из GitHub Actions** (включается сам, когда секреты появятся):
2. Certificates → **Apple Distribution**: создать на Mac владельца, экспортировать `.p12` с
   паролем.
3. Profiles → **App Store**-профиль для `cx.m42.unitool` (или доверить `xcodebuild
   -allowProvisioningUpdates` с ключом API создавать профили самому).
4. App Store Connect → Users and Access → Integrations → **API key** (роль App Manager):
   Key ID, Issuer ID, файл `.p8` (скачивается один раз).
5. App Store Connect → My Apps → **новое приложение** `cx.m42.unitool` (имя, SKU, язык).
   TestFlight → внутренняя группа с Apple ID владельца; на iPhone — приложение TestFlight.
6. Секреты репозитория: `IOS_DIST_CERT_P12_BASE64`, `IOS_DIST_CERT_PASSWORD`,
   `IOS_PROFILE_BASE64`, `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8_BASE64`.
7. `ios.yml`: временный keychain с сертификатом → `xcodebuild archive` → `-exportArchive` с
   `method: app-store-connect`, `destination: upload`, ключ API → TestFlight. Первая загрузка
   спросит **export compliance** (`ITSAppUsesNonExemptEncryption`, D151): для внутренних
   тестеров отвечается в консоли на каждую сборку, для внешних — декларация владельца.
8. Бюджет: macOS-минуты на приватном репозитории считаются ×10; лента остаётся
   `workflow_dispatch`.

Что даёт A сверх B: установка по воздуху без кабеля и Mac, тестеры кроме владельца, тот же
путь для релиза в App Store. Что стоит: шесть секретов, запись приложения в App Store Connect,
сертификат распространения. Для самопроверки и отчёта достаточно B.

## 5. Отчёт для отправки (`exportText`)

Английский, фиксированный, не переводится: он для нас, а не для человека. Что внутри: дата,
версия хоста и приложения, платформа, модель, версия ОС, `KeyringDevice`, все finding с кодом,
статусом и фактами, все шаги самопроверки с временем и evidence, список ключей (отпечаток,
уровень, `usable`), дата последнего бэкапа. Чего внутри нет: ни значений секретов, ни имён
аккаунтов, ни паролей, ни закрытых половин — проверяется сканером (шаг `report.clean` и тест
I5 на JVM). Кнопка «Share report» → `FileSharer` как текст.

## 6. Слова: коды в библиотеке, тексты в Unitool

- Библиотека (`superizer:core`) знает **коды** и объявляет интерфейс
  `DiagnosticsStrings { fun title(code): String; fun detail(code, facts): String; fun
  action(action): String; fun status(status): String }` — без единого текста.
- **Unitool** держит таблицы: `composeApp/…/i18n/DiagnosticsStrings.en.kt` — источник, и
  `DiagnosticsStrings.ru.kt`; выбор по `Lang`, как у остальных строк (D11, `HostStrings`).
  Английская таблица — единственное место, где текст пишется; перевод — вторая таблица того же
  интерфейса, и компилятор не даст пропустить код.
- Хост отдаёт таблицу через `CompositionLocal`, как `HostStrings`. Все экраны (07: они в
  Unitool) и баннер берут слова оттуда. Если таблицы нет — показывается сырой код, никогда не
  пустота.
- Приложения (2FA) показывают свои бейджи теми же словами через `runtime.diagnostics` и
  `LocalDiagnosticsStrings`; своих текстов про хранилище у приложения нет, чтобы они не
  разошлись с хостом.

### 6.1. Каталог кодов и английские тексты (источник для `DiagnosticsStrings.en`)

| Код | Статус | Title / Detail | Actions |
|---|---|---|---|
| `vault.chip.ok` | Ok | **Secrets are protected by this device's secure hardware.** Sealed by a key that never leaves the {hardware} and cannot be copied with the app's files. | — |
| `vault.software-key` | Unsafe | **Secrets are sealed by a software key.** This device has no secure hardware for the key, so anyone with root access can read the secrets. | AddSshKey (switch secrets to an SSH key with a passphrase) |
| `vault.passphrase.ok` | Info | **Secrets are protected by the passphrase of your SSH key {comment}.** There is no secure hardware on this platform; the passphrase is the only lock. Choose it well and keep a backup. | MakeBackup |
| `vault.passphrase.browser` | Unsafe | **Secrets are protected only by a passphrase, inside the browser.** Any script on this site can read the storage and the tab's memory. Prefer the phone or desktop app for secrets. | — |
| `vault.missing` | Missing | **Secret storage is not set up on this platform. Secrets are stored as plain text.** Add an SSH key with a passphrase; the host will seal all app secrets with it. | AddSshKey |
| `vault.not-implemented` | Missing | **This platform has no secret storage yet.** Secrets are stored as plain text until one is implemented. Report this so it gets built. | ShareReport |
| `vault.key-lost` | Broken | **The key that sealed your secrets is gone.** The screen lock was removed or the device was restored. Sealed data on this device cannot be opened; restore it from a backup. | ImportKeyAgain, ShareReport |
| `vault.broken` | Broken | **Secret storage failed its self-test at step {step}.** Secrets may be unreadable. Share the report so this can be fixed. | RunSelfTest, ShareReport |
| `vault.session-open` | Info | **Secrets are unlocked for this session.** They lock again when the app closes or you lock them. | — |
| `key.level1` | Ok | **Stored in the secure chip ({hardware}); never leaves it.** | — |
| `key.level2` | Ok | **Encrypted with a key from the secure chip ({hardware}).** Decrypted into memory only for one operation, after you confirm. | — |
| `key.level3` | Info | **Stored in the app's files, protected only by its passphrase.** | Rekey |
| `key.level3.browser` | Unsafe | **Stored in the browser, protected only by its passphrase.** The decrypted key lives in the tab's memory while unlocked. | — |
| `key.level3.chip-available` (09) | Info | **Stored in the app's files, protected only by its passphrase.** This device has a secure chip. To keep the key in it, delete the key and add it again. | — |
| `key.no-second-lock` (09) | Attention | **Anyone who can unlock this device can use this key.** The screen lock — PIN, pattern, passcode, fingerprint or face — is enough. The key's own passphrase is a second lock the device does not have. | — |
| `key.biometric-only` (09) | Ok | **Opens only with your fingerprint, not with the screen lock.** A face of the strongest class counts as a fingerprint. If a fingerprint is added or all are removed, the key stops working here: import it again from your computer. | — |
| `key.unusable` | Broken | **This key can no longer be used here:** the screen lock was removed, or — for a key only a fingerprint opens — the fingerprints changed (09). Import it again from your computer. | ImportKeyAgain |
| `key.secrets-anchor` | Info | **This key seals the host's secrets.** It cannot be deleted until secrets are re-sealed with another key. | — |
| `key.session-policy` | Attention | **Confirmation is remembered for {minutes} minutes.** Anyone holding the unlocked device can use this key within that window. | — |
| `device.no-screen-lock` | Unsafe | **No screen lock.** Keys that need your confirmation cannot be created; secret storage is weaker. Set a PIN, pattern or passcode. | EnableScreenLock |
| `device.no-secure-hardware` | Unsafe | **This device has no secure hardware ({reason}).** Keys and secrets can only be protected by a passphrase here. | AddSshKey |
| `device.emulator` | Info | **Running on an emulator.** Hardware protection is simulated; nothing here should be trusted with real keys. | — |
| `backup.never` | Attention | **No backup has been made.** If this device is lost, sealed secrets are lost with it. | MakeBackup |
| `backup.stale` | Attention | **Last backup was {days} days ago.** | MakeBackup |
| `backup.ok` | Ok | **Last backup: {date}.** Keys are not in backups; only app data. | — |
| `selftest.never` | Attention | **The self-test has not been run on this device.** | RunSelfTest |
| `selftest.ok` | Ok | **Self-test passed on {date}** ({n} checks). | — |
| `selftest.failed` | Broken | **Self-test failed: {step}.** {evidence} | ShareReport |
| `app.secrets.sealed` | Ok | **{n} secrets, sealed by the host.** | — |
| `app.secrets.plain` | Missing | **{n} secrets are stored as plain text** because the host has no secret storage here. | AddSshKey |

**(impl, шаг 1).** В библиотеке каталог — `DiagnosticsCode` (enum: код, статус, действия) и
`SelfTestCode`. Два отличия от таблицы выше, оба ради I21 и §3:

- `vault.passphrase.browser` и `key.level3.browser` получили действие **ShareReport**: статус Unsafe
  без единого действия нарушал бы I21, а честного «исправить» в браузере нет.
- Новый код **`passphrase.slow`** (Attention): его называет §3 (`passphrase.roundtrip` дольше 3 с),
  а таблица забыла. Текст: **Unlocking with a passphrase takes {seconds} s on this device.**
  bcrypt with 24 rounds is slow here; the key is as safe as before, only slower to use. — без
  действий.

Тексты действий: AddSshKey «Add an SSH key», SetPassphrase «Set a passphrase», Rekey
«Change passphrase», EnableScreenLock «Open security settings», RunSelfTest «Run self-test»,
MakeBackup «Make a backup», ShareReport «Share report», ImportKeyAgain «Import the key again».
Статусы: Ok «OK», Info «Note», Attention «Needs attention», Unsafe «Unsafe», Missing «Not
protected», Broken «Broken».

## 7. Где показывается (честные пометки везде)

| Место | Что | Откуда |
|---|---|---|
| **Settings → Storage & security** (экран Unitool) | всё: сводка `worst`, список finding по темам, «How storage works here» — факты `location`/`level`/`hardware` словами, кнопки Run self-test / Share report / Make a backup, дата последней проверки | `StorageDiagnostics.report` |
| **Баннер на Home** | одна строка, если `worst ≥ Unsafe`: «Secrets are not protected on this device — tap to see why»; закрывается только устранением | `report.worst` |
| **Карточка ключа** (Unitool, 07) | строка уровня и finding этого ключа | `Finding(subject = Key(id))` |
| **Список ключей** | бейдж уровня у каждого ключа | то же |
| **2FA, шапка списка** | «Protected by secure hardware» / «Protected by SSH key passphrase» / **«NOT protected — stored as plain text. Add an SSH key.»** | `runtime.diagnostics.forApp` |
| **Мастер импорта** | уровень до ввода пароля, счётчик символов (05 §2.4) | `inspect()` кольца |
| **Экран бэкапа** | дата последнего, что войдёт, что нет («keys are not included») | `Backup` finding |
| **Service Menu** (debug) | сырой отчёт `exportText` | — |
| **Листинг и PRIVACY** | та же формула, что `vault.chip.ok` / `vault.passphrase.ok`, без обещаний сверх кодов | вручную, по каталогу §6.1 |

Правило: ни одна из этих надписей не пишется в интерфейсе от руки — только через код из
каталога. Тест на JVM проверяет, что каждый код каталога есть в `en` и `ru` (компилятор) и
что у каждого статуса ≥ Unsafe есть хотя бы одно действие или ShareReport.

**(impl, шаг 6)** Как это вошло в код:

- Баннер — статус и заголовок худшей находки словами каталога, а не отдельная фраза «Secrets are
  not protected…»: такой фразы нет в каталоге, а D179 запрещает писать от руки. Тап открывает
  Storage & security.
- 2FA: одна строка над списком — заголовок находки о секретах хоста (`vault.*`) или, если она
  хуже, `app.secrets.plain` этого приложения; при статусе ≥ Unsafe красным и с деталью. Кнопки
  «Добавить SSH-ключ» в 2FA нет: приложение не может открыть экран хоста, деталь говорит, что
  делать.
- Отчёт обновляется при старте, при открытии Storage & security, после самопроверки и после
  любой смены ключей (`UnitoolSecurity.attach`). Запись секрета приложением его не обновляет:
  `app.secrets.plain` появится при следующем из этих событий.
- «Make a backup» кнопки не имеет до шага 7: экрана бэкапа ещё нет.
- Самопроверка у себя в блоке: её находка сверху, под ней шаги (✓ / ✗ / «не на этой платформе»).
- Скриншоты (§9) — шесть статусов по кадрам 34–39: чип (Ok), пароль ключа (Info), нет бэкапа
  (Attention, во всех), программный ключ (Unsafe), нет сейфа (Missing), ключ пропал (Broken).
  «Телефонные» кадры рисуются с фейковым сейфом в StrongBox и `FakeKeys`: на desktop чипа нет.

## 8. Исправление по 04 §9 Б8 (владелец: «верно, исправь»)

**D176.** Сейф хоста на ssh-ключе (`AgeVault`) держит в памяти **расшифрованные секреты**, а
не ключ: `open` спрашивает пароль, расшифровывает ключ в `SecretBytes`, открывает age,
**стирает ключ**, кэширует открытые значения до конца сессии хоста или `lockAll()`. Любая
операция кольца (подпись, `decrypt` файла) спрашивает пароль снова, по правилу «каждое
использование». Два таймера не пересекаются, потому что ключа в памяти между операциями нет.
Внесено в 02 §6.7 и 04 §9.

## 9. Тесты

- Самопроверка на JVM с `SoftwareVault`, `PassphraseBackend` и фейками: каждый шаг зелёный;
  подложенный сломанный сейф → `vault.broken` с evidence; пульс с потерянным ключом →
  `vault.key-lost`.
- Сканер канареек по `exportText` (I5) и по `DiagnosticsReport.toString()`.
- Скриншоты экрана Storage & security во всех шести статусах и 2FA-шапки в трёх состояниях
  (D43), на desktop, для листинга и для проверки глазами.
- Android: самопроверка как `connectedDebugAndroidTest` (это и есть `KeystoreProbeTest`).
- iOS: те же шаги в `iosSimulatorArm64Test`, когда P10 — да; иначе по §4 на iPhone.

## 10. Что меняется в плане

- 02 §5: `KeyringDevice` и `Inspection` отдают факты в форме `Finding`; §6.7 — D176.
- 03: инвариант **I20** «отчёт диагностики не содержит секретов»; **I21** «каждый код каталога
  имеет текст в каждой таблице и действие при статусе ≥ Unsafe».
- 04: шаг 4 + `StorageDiagnostics`, пульс, осмотр (+1 день); шаг 6 + экран Storage & security
  в Unitool, баннер, бейджи, таблицы en/ru (+1,5 дня); шаг 5b начинается с самопроверки как
  первого iOS-артефакта; `ios.yml` — лента в TestFlight (владелец даёт секреты, вопрос 88).
  Итого ≈ 18 → **≈ 20,5 дней**.
- ios/01 §4 «риски, которые покажет первая сборка на Mac» → «которые покажет самопроверка на
  iPhone».

## 11. Решения

- **D176.** Сейф на ssh-ключе кэширует секреты, не ключ (§8).
- **D177.** `StorageDiagnostics` — сервис хоста контракта 3: осмотр на каждом старте, пульс,
  самопроверка по кнопке и один раз после установки/обновления. Коды и факты — в библиотеке,
  тексты — в Unitool, en как источник, ru как перевод, компилятор проверяет полноту.
- **D178.** Самопроверка использует только временные ключи и продуктовый ключ только в пульсе;
  одна реализация служит и продукту, и шагу 0, и iOS-тестам.
- **D179.** Каждая надпись о защите в интерфейсе, листинге и PRIVACY — код из каталога §6.1;
  надписей от руки нет. Статус ≥ Unsafe всегда виден на Home, пока не устранён.
- **D180.** iOS проверяется по реальному отчёту с iPhone, а не по компилятору: сборка
  приходит либо по кабелю (лента `device` + подпись на Mac владельца), либо через TestFlight
  (лента `testflight`); до первого зелёного отчёта iOS-сейф и кольцо считаются непроверенными.

## 12. Вопросы владельцу

- ~~88.~~ **Решено: оба пути** (§4.1), обе дорожки в `ios.yml`. От владельца: App ID
  `cx.m42.unitool` с Push; dev-профиль с UDID для пути B; для пути A — сертификат
  распространения, ключ App Store Connect API, запись приложения, шесть секретов.
- **89. Самопроверка при каждом старте.** Полная — только после установки и обновления
  (*предлагаю*), или при каждом запуске (≈ 1–3 с из-за bcrypt)?
