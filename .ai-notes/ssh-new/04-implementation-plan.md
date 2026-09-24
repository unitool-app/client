# 04 — План работ

> **Версия:** 1.0 · **Статус:** на ревью у владельца · **Дата:** 2026-09-24
>
> **История:** 1.0 — первая редакция.

---

## 1. Порядок и оценка

Каждый шаг начинается с красного теста и заканчивается зелёным `./scripts/verify.sh` в обоих
репозиториях.

| Шаг | Что | Где | Оценка |
|---|---|---|---|
| 0 | Спайк: что Keystore умеет на `unitool35` и на телефоне (§2) | superizer, временный модуль или `ssh` | 0,5 дня |
| 1 | Модуль `ssh`, переезд криптографии из 2FA, `SshPublicKey` с ECDSA | superizer, unitool/apps/twofa | 1 день |
| 2 | Форматы и примитивы: `openssh-key-v1`, bcrypt_pbkdf, AES, Ed25519, SSHSIG, расшифровка age | superizer/ssh | 2 дня |
| 3 | Агент, программный бэкенд, фейк, сканер, JVM-тесты утечек и интероп с `ssh-add` | superizer/ssh, testing | 1,5 дня |
| 4 | Хост: сервисы, привязанные к вызывающему; секции Settings уровня хоста | superizer/host, ui | 0,5 дня |
| 5 | Android: Keystore, конверт, `KeyPromptActivity`, записи, инструментальные тесты | superizer/ssh androidMain | 2,5 дня |
| 6 | UI: секция «SSH-ключи», карточка, мастер импорта, скриншоты | superizer/ssh-ui | 1,5 дня |
| 7 | Выпуск 0.3.0 и Unitool: регистрация, импорт экспорта в 2FA, bench, бэкап, PRIVACY, ручная приёмка | оба | 1,5 дня |
| | **Итого** | | **≈ 11 дней** |

Шаги 1–3 не требуют ни устройства, ни ответов на вопросы, кроме 69 (где код). Шаг 5 ждёт
результата шага 0 и ответов на 70–72. Шаг 6 ждёт ответов на 74–75.

## 2. Шаг 0: спайк

Один инструментальный тест `KeystoreProbeTest`. Он ничего не утверждает, а только пишет в лог
ответ на каждый вопрос. Результаты вносятся в таблицу ниже **до** шага 5 (D160).

| # | Вопрос | Как проверяем | `unitool35` | телефон |
|---|---|---|---|---|
| P1 | Импортируется ли Ed25519 в Keystore (API 33+)? Какой объект `PrivateKey` для этого нужен: `KeyFactory("Ed25519")` / `("XDH")` из JCA Android или свой класс с PKCS#8 в `getEncoded()`? | `KeyStore.setEntry` с `KeyProtection(PURPOSE_SIGN)`, затем `Signature("Ed25519", AndroidKeyStore)` и проверка подписи на JVM | | |
| P2 | Импортируется ли X25519 с `PURPOSE_AGREE_KEY`, и считает ли `KeyAgreement("XDH")` общий секрет, как наш `Curve25519.x25519`? | импорт скаляра из seed канарейки, сравнение с commonMain | | |
| P3 | Принимает ли Keystore RSA OAEP с непустой меткой (`PSource.PSpecified`)? | `Cipher("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")` с меткой age | | |
| P4 | Работает ли «сырое» RSA (`RSA/ECB/NoPadding`, `ENCRYPTION_PADDING_NONE`) на импортированном ключе? | расшифровка шифртекста, сделанного `RsaOaep.encrypt`, и снятие OAEP в коде | | |
| P5 | Проверяет ли Keystore подпись сертификата в `PrivateKeyEntry`? | импорт с сертификатом с подписью-заглушкой | | |
| P6 | Импорт P-256 с `setIsStrongBoxBacked(true)` | на телефоне с `FEATURE_STRONGBOX_KEYSTORE` | — | |
| P7 | Какое исключение и на каком вызове бросает ключ «каждое использование» без промпта (`initSign`, `update` или `sign`)? | для `Signature` и `Cipher` | | |
| P8 | `KeyInfo.securityLevel` и `isUserAuthenticationRequirementEnforcedBySecureHardware` для каждого типа | лог | | |
| P9 | Сколько идёт bcrypt_pbkdf с 24 раундами в нашем Kotlin на телефоне? | замер после шага 2, туда же | | |

Правило решения по Ed25519:

- **P1 и P2 да** на API 33+ → Ed25519 там резидентный: два alias, `.sign` и `.agree`. На API
  30–32 он запечатанный.
- **P1 да, P2 нет** → резидентный только для подписи. Для расшифровки age владелец выбирает
  одно из двух: Ed25519 запечатанный целиком, или у такого ключа нет расшифровки (новый
  вопрос).
- **P1 нет** → Ed25519 запечатанный везде. Модель от этого не меняется, меняется одна строка
  таблицы 01 §4.1.

Спайк в таком виде проверялся бы и на телефоне владельца: это один `connectedAndroidTest`.

## 3. Шаги

### Шаг 1. Модуль `ssh` и переезд криптографии (1 день)

Перед началом: `git log` в `/prj/superizer`. Туда коммитит ещё кто-то (память
superizer-implemented).

- **Красный тест.** `AgeTest` и `TwoFaImportTest` 2FA, перенесённые в `ssh/src/desktopTest`
  (`AgeTest` целиком, с `JcaAge`), не компилируются, потому что модуля нет.
- `settings.gradle.kts` библиотеки: `include(":ssh")`. Сборка по шаблону `host` (KMP android,
  desktop, wasmJs; `explicitApi()`; BCV; публикация `cx.m42.superizer:ssh`).
- Переезд из `apps/twofa`: `SshKey.kt` → `key/SshPublicKey.kt` (ECDSA получает свой класс),
  `Age.kt`, `Curve25519.kt`, `ChaCha20Poly1305.kt`, `RsaOaep.kt`, копия `Crypto.kt`. 2FA
  зависит от `libs.superizer.ssh` — это Maven-координата, а не `project(…)`, иначе режим C
  сломан.
- `verify.sh` библиотеки: статическая проверка «в зависимостях `ssh` нет `ktor`» (I-сеть).
- **Готово.** 2FA собирается на `superizer:ssh`, экспорт на эмуляторе по-прежнему
  открывается `age -d -i ~/.ssh/id_ed25519`. Проверено в режимах `includeBuild` и
  `SUPERIZER_FROM_MAVEN=1`.

### Шаг 2. Форматы и примитивы (2 дня)

- **Красные тесты:** `BcryptPbkdfTest`, `AesTest`, `Ed25519Test`, `RsaOaepDecodeTest`, затем
  `OpenSshKeyFileInteropTest`, `SshSigInteropTest`, `AgeDecryptInteropTest` (03 §4).
- `wire/SshWire.kt`: писатель к существующему читателю.
- `crypto/Bcrypt.kt`: Blowfish и `bcrypt_pbkdf` построчно по OpenBSD. Таблицы S-блоков —
  константа на ~1 000 чисел; сгенерировать скриптом из цифр π и проверить векторами.
- `crypto/Aes.kt`: AES (CTR, GCM) — только для файлов ключей, один раз при импорте. Табличная
  реализация допустима: операция локальная и однократная, канала по времени нет.
- `crypto/Ed25519.kt`: TweetNaCl `crypto_sign` поверх арифметики поля из `Curve25519.kt`.
- `key/OpenSshKeyFile.kt`: разбор, `inspect` без пароля, расшифровка в `SecretBytes`,
  проверка `checkint` и совпадения открытого ключа, отказы §8 из 02 с командами.
- `sig/SshSig.kt`: подписываемые данные, армор, разбор, проверка (Ed25519 и RSA — commonMain;
  ECDSA — `expect/actual` на JCA).
- `age/Age.kt`: расшифровка — разбор заголовка, поиск стансы по тегу, `ssh-ed25519` (X25519 +
  tweak + HKDF), `ssh-rsa` (OAEP), MAC заголовка, STREAM. Операция над закрытым ключом
  вынесена за интерфейс `Unwrapper`, чтобы на Android её выполнил Keystore (P2, P4).
- **Готово.** Все интероп-тесты зелёные против OpenSSH 9.6 и `age` на этой машине. Замер
  bcrypt с 24 раундами на JVM записан в §2 (P9 — после телефона).

### Шаг 3. Агент, фейк, тесты утечек на JVM (1,5 дня)

- **Красные тесты:** `AgentInteropTest`, затем `NoSecretAtRestTest`, `NoSecretInLogsTest`,
  `ApiSurfaceTest`, `ZeroizationTest`, `SessionTest`, `GrantTest`, `NamespaceTest`,
  `DeleteTest`, `ImportFormatTest` (03 §5).
- Контракты: `SshKeyring`, `SshAgent`, `SshKeyAdmin`, `SshOutcome`, `ImportOutcome`,
  `SshKeyInfo`, `Grant`, `UseRecord` (02).
- `KeyringCore` в commonMain: всё, что не зависит от платформы. Разрешения, namespace,
  журнал, сессии по `Clock`, записи через `StorageService`. Платформа даёт только `Backend`
  (import, sign, unwrap, delete) и `Prompt` (показать лист, вернуть решение).
- `SoftwareBackend` (desktopTest и `testing`) и `FakeSshKeyring` со сценарием промпта.
- `agent/AgentCodec.kt`, `agent/AgentServer.kt`. Транспорт — UNIX-сокет только в тесте.
- `LeakScanner` и `Canary` в `superizer:testing`.
- **Готово.** `ssh-add -l`, `ssh-add key`, `ssh-keygen -Y sign` через наш агент работают. Все
  JVM-инварианты зелёные.

### Шаг 4. Хост (0,5 дня)

- **Красный тест.** Два приложения берут `service(K)` и получают экземпляры с разным
  `caller`. Секция хоста видна в `SettingsScreen` (тест на `runComposeUiTest`, тег
  `settings:host:ssh`).
- `SuperizerBuilder.service(key) { caller -> … }`, кэш на время жизни `AppRuntime`.
  `ServiceRegistry.keys` и `HostInfo.services` работают как раньше: проверка
  `manifest.requires` не меняется.
- `SuperizerBuilder.settingsSection(id, title, content)` и отрисовка перед секциями
  приложений.
- **Готово.** Дамп BCV `host` и `ui` обновлён. Контракт приложения (`SuperizerContract.VERSION`)
  не меняется.

### Шаг 5. Android (2,5 дня)

- **Красные тесты:** инструментальные из 03 §6.2, по одному на каждое свойство.
- Зависимость `androidx.biometric`.
- `AndroidSshKeyring(context, activity: () -> Activity?)` — `Backend` и `Prompt` для
  `KeyringCore`.
- `KeystoreResidence`: импорт через `KeyProtection` по таблице 01 §5.5, alias
  `cx.m42.superizer.ssh.<id8>.{sign,decrypt,agree}`, подпись с перекладкой DER → SSH для ECDSA,
  «сырое» RSA для age.
- `SealedResidence`: alias `.wrap`, AES-GCM, `SecretBytes`, окно сессии, стирание при
  выключении экрана (`ACTION_SCREEN_OFF`).
- `SelfSignedCert`: минимальный DER. Нужна ли настоящая подпись — по P5.
- `KeyPromptActivity : FragmentActivity`: лист разрешения (свой Compose-экран с
  `FLAG_SECURE`), затем `BiometricPrompt` с `CryptoObject` и текстом из 01 §5.2. Результат
  возвращается через `CompletableDeferred`, как у `AndroidQrScanner`. Один промпт за раз,
  второй получает `Busy`.
- `KeyRecords`: prefs-файл `cx.m42.superizer.ssh` (D159).
- Генерация (если вопрос 74 — да): P-256 с `setAttestationChallenge`, StrongBox, если есть.
- **Готово.** Инструментальные тесты зелёные на `unitool35`. `KeystoreProbeTest` и отчёт
  уровня безопасности записаны. Тест кучи для Ed25519 — 0 находок.

### Шаг 6. UI (1,5 дня)

- **Красный тест.** Скриншот-тесты (D43) секции на desktop с фейковым `SshKeyAdmin`: пустое
  состояние; три ключа разных мест хранения; карточка; каждый шаг мастера; каждый отказ
  формата.
- `ssh-ui`: `SshKeysSection(admin, sources)`, `KeyCard`, `ImportWizard`, строки ru/en. Тексты
  о месте хранения и политике — дословно из 01 §4.3 и §5.3.
- Поле пароля: `KeyboardType.Password`, `autoCorrectEnabled = false`, `CharArray` собирается
  из поля в момент «Добавить», а поле сразу очищается.
- Вставка: предупреждение, после импорта `clearPrimaryClip()`, если в буфере был ключ.
- **Готово.** Скриншоты приняты владельцем.

### Шаг 7. Выпуск и Unitool (1,5 дня)

- Superizer: `CHANGELOG`, docs, тег `v0.3.0` → публикация в CI по обычной схеме.
- Unitool, `gradle/libs.versions.toml`: `superizer = "0.3.0"`, `superizer-ssh`,
  `superizer-ssh-ui`. Отдельный коммит «bump superizer to 0.3.0».
- `MainActivity`: на API ≥ 30 — `service(SshKeyring.Key) { caller -> keyring.forApp(caller) }`
  и `settingsSection("ssh", …) { SshKeysSection(keyring.admin, sources) }`. Источник «QR» —
  `AndroidQrScanner`, если камера есть.
- 2FA: «Импорт из файла» через `decrypt` (02 §6.1). Помнить ловушку `collectLatest`:
  загрузка в `runtime.scope`, сохранение под `NonCancellable` (память 2FA). Тест на
  `FakeSshKeyring`: отмена листа ничего не меняет, снапшот не содержит `secret=`.
- Bench test-app: «Подписать» и «Проверить» — видимая проверка `sign`/`verify`.
- `backup_rules.xml`, `data_extraction_rules.xml`: `exclude` для `cx.m42.superizer.ssh.xml`,
  `BackupRulesTest`.
- `play/PRIVACY.md` и оба описания: разрешения читаем **из собранного APK** через `aapt2 dump
  xmltree`, а не из манифеста (память push-fcm). `androidx.biometric` добавит
  `USE_BIOMETRIC` и, скорее всего, `USE_FINGERPRINT`. Абзац о ключах: где хранятся, что
  никуда не отправляются (у модуля нет сети), что отпечаток проверяет система, а приложению
  приходит только «да» или «нет». Формулировки — по D149.
- Ручная приёмка 03 §7.
- **Готово.** Оба `verify.sh` зелёные, приёмка пройдена, скриншоты секции в
  `play/listing` не нужны.

## 4. Что меняется где

```
/prj/superizer
  settings.gradle.kts                   + :ssh, :ssh-ui
  ssh/                                  NEW (commonMain, androidMain, desktopTest, androidInstrumentedTest)
  ssh-ui/                               NEW
  host/…/SuperizerHost.kt               + service(key) { caller -> }, settingsSection(…)
  host/…/HostRuntime.kt                 caller-bound lookup
  ui/…/SettingsScreen.kt                host sections first
  testing/                              + FakeSshKeyring, LeakScanner, Canary
  scripts/verify.sh                     + "ssh has no ktor", interop skips by name
  .github/workflows/*                   + apt-get install openssh-client openssh-server age
  CHANGELOG.md, docs/                   0.3.0
/prj/unitool
  gradle/libs.versions.toml             superizer 0.3.0, ssh, ssh-ui, (biometric comes transitively)
  composeApp/…/MainActivity.kt          register keyring (API ≥ 30), section, QR source
  composeApp/src/androidMain/res/xml/*  exclude cx.m42.superizer.ssh.xml
  composeApp/src/desktopTest/…          BackupRulesTest
  apps/twofa/                           − SshKey/Age/Curve25519/ChaCha/RsaOaep (moved), + import from file
  play/PRIVACY.md, play/listing/*       permissions from the APK, keys paragraph
```

## 5. Выпуск и совместимость

- Новый модуль и новые методы `SuperizerBuilder` — это **минорный** релиз библиотеки 0.3.0.
  Контракт приложения не меняется: `SshKeyring` приходит через `ServiceKey`, как и задумано
  D19.
- Если idea/06 (замок) принята, выпускать вместе. Обеим нужен `androidx.biometric`, а флаг
  «идёт свой промпт» у них общий. Контракт 2 из 06 D138 — её собственный, этой работе он не
  нужен.
- Приложение, которое без ключей не может (гипотетический git-кирпич), пишет
  `SshKeyring.Key` в `manifest.requires`. На хосте без кольца (desktop, web, API < 30) его
  отклонят при регистрации, как любое приложение без нужного сервиса.

## 6. Риски

| Риск | Что делаем |
|---|---|
| Ed25519 не импортируется в Keystore (P1) | запечатанный вариант спроектирован как равноправный. Меняется одна строка таблицы, а не модель |
| bcrypt 24 раунда медленный на слабом телефоне (P9) | это только импорт, с индикатором. `keepPassphrase` (01 §5.4) — с предупреждением о задержке при каждом использовании |
| UiAutomator не набирает PIN устойчиво | успешный путь проверяется на JVM и вручную (03 §6.4) |
| Производитель сломал KeyMint (бывает на дешёвых устройствах) | `KeystoreProbeTest` на таком устройстве покажет. Кольцо тогда предлагает только запечатанный вариант, если `securityLevel` «software» — с честной надписью |
| Metaspace или OOM Kotlin-демона при двух новых модулях | память 2FA: `gradle.properties` уже 3g; при OOM — `./gradlew --stop`, **не** `pkill` |
| Кто-то параллельно коммитит в `/prj/superizer` | `git log` перед каждым шагом |

## 7. Не входит в v1

- Встроенный SSH-клиент и сырая подпись для входа (вопрос 76, 02 §6.6).
- Клиент `SSH_AUTH_SOCK` на desktop (вопрос 77).
- Мост агента в Termux.
- Доверенные ключи, `allowed_signers` и подписанные активации (вопрос 78, 02 §6.4).
- Вход на сервер ключом (02 §6.3) — шов есть, протокол проектирует idea/01.
- Сертификаты SSH (`*-cert-v01@openssh.com`) — хранить рядом с ключом и отдавать агентом
  дёшево, но спроса пока нет.
- FIDO-ключи `sk-*`, обёрнутый импорт `WrappedKeyEntry`, экспорт закрытого ключа (вопрос 73).

## 8. Решения

- **D160.** Шаг 0 делается первым, до кода Android. Его ответы P1–P9 вписываются в таблицу §2
  и решают, где живёт Ed25519. Это единственная развилка, которую решает устройство, а не
  владелец.
- **D161.** Выпуск — Superizer 0.3.0 (вместе с idea/06, если она принята). Контракт
  приложения не меняется. Первый потребитель — импорт экспорта 2FA, второй — bench test-app.
