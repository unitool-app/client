# 04 — План работ

> **Обновлено 2026-09-26 по заметке 05:** шаг 0 с iOS-пробами, `PassphraseBackend` и `AgeVault`,
> контракт 3 в шаге 4, шаг 5b iOS, «Задать пароль» в шаге 6, бэкап и переезд 2FA на
> `runtime.secrets` в шаге 7. Выпуск — Superizer **0.4.0**, после 0.3.0 (замок + iOS).
> Оценка ≈ 20,5 дней с диагностикой (06). Изменённые места помечены «05» и «06».

> **Версия:** 1.0 · **Статус:** на ревью у владельца · **Дата:** 2026-09-24
>
> **История:** 1.0 — первая редакция.

---

## 1. Порядок и оценка

> **(07)** Таблица шагов заменена: актуальная — 07 §4 (≈ 20 дней, один шаг в библиотеке,
> остальное в Unitool). Ниже — прежняя раскладка, оставлена ради ссылок на номера шагов;
> нумерация в 07 совпадает по смыслу (0 спайк, 1 библиотека, 2 форматы, 3 бэкенды, 4 хост и
> 2FA, 5 Android, 5b iOS, 6 UI, 7 бэкап и релиз).

Каждый шаг начинается с красного теста и заканчивается зелёным `./scripts/verify.sh` в обоих
репозиториях.

| Шаг | Что | Где | Оценка |
|---|---|---|---|
| 0 | Спайк: что Keystore умеет на `unitool35` и на телефоне; (05) P10–P14 на Mac и iPhone (§2) | superizer, временный модуль или `ssh` | 1 день |
| 1 | Модуль `ssh`, переезд криптографии из 2FA, `SshPublicKey` с ECDSA | superizer, unitool/apps/twofa | 1 день |
| 2 | Форматы и примитивы: `openssh-key-v1`, bcrypt_pbkdf, AES, Ed25519, SSHSIG, расшифровка age | superizer/ssh | 2 дня |
| 3 | Агент, фейк, сканер, JVM-тесты утечек и интероп с `ssh-add`; (05) `PassphraseBackend` (уровень 3), `rekey`, `AgeVault` | superizer/ssh, testing | 2,5 дня |
| 4 | Хост: сервисы, привязанные к вызывающему; секции Settings уровня хоста; (05) контракт 3: `runtime.secrets`, `BackupPolicy`, «ключ для секретов»; (06) `StorageDiagnostics`: осмотр, пульс, самопроверка | superizer/host, ui, core | 2,5 дня |
| 5 | Android: Keystore, конверт, `KeyPromptActivity`, записи, инструментальные тесты | superizer/ssh androidMain | 2,5 дня |
| 5b | (05) iOS: `SecureEnclaveBackend`, записи с `NSFileProtectionComplete`, тесты на симуляторе; общие CoreFoundation-обёртки; (06) первый артефакт — самопроверка в TestFlight, отчёт с iPhone | superizer/ssh iosMain, unitool ios.yml | 1,5 дня |
| 6 | UI: секция «SSH-ключи», карточка, мастер импорта, (05) шаг «Задать пароль» и счётчик, скриншоты; (06) экран Storage & security в Unitool, баннер Home, бейджи в 2FA и карточках, таблицы `DiagnosticsStrings` en/ru | superizer/ssh-ui, unitool composeApp | 3,5 дня |
| 7 | Выпуск 0.4.0 и Unitool: регистрация на всех платформах, 2FA на `runtime.secrets` с миграцией, импорт экспорта, (05) `HostBackup` и восстановление, PRIVACY, ручная приёмка | оба | 4 дня |
| | **Итого** | | **≈ 20,5 дней** |

Шаги 1–4 не требуют устройства. Шаг 5 ждёт результата шага 0 (P1–P9). Шаг 5b ждёт Mac и
P10–P13; без Mac он откладывается, а Android, desktop и web выпускаются без него (§6). Ответы
на вопросы 69–87 приняты как в README «Рабочие допущения».

## 2. Шаг 0: спайк

Один инструментальный тест `KeystoreProbeTest`. Он ничего не утверждает, а только пишет в лог
ответ на каждый вопрос. Результаты вносятся в таблицу ниже **до** шага 5 (D160).

| # | Вопрос | Как проверяем | `unitool35` | телефон |
|---|---|---|---|---|
| P1 | Импортируется ли Ed25519 в Keystore (API 33+)? Какой объект `PrivateKey` для этого нужен: `KeyFactory("Ed25519")` / `("XDH")` из JCA Android или свой класс с PKCS#8 в `getEncoded()`? | `KeyStore.setEntry` с `KeyProtection(PURPOSE_SIGN)`, затем `Signature("Ed25519", AndroidKeyStore)` и проверка подписи на JVM | **да** (2026-09-26, API 35, программный KeyMint): импорт — только своим `PrivateKey` с именем алгоритма `XDH` и PKCS#8 Ed25519 (OID 1.3.101.112) плюс свой объект сертификата; `KeyFactory("Ed25519")` у Android нет, имена `Ed25519`/`EdDSA` → «Unsupported key algorithm», `EC` → «Unexpected Key». Подпись `Signature("Ed25519")` совпадает с вектором RFC 8032 байт в байт. `KeyInfo` для импортированного Curve25519 не читается («Key size not available»). Генерация Ed25519 в Keystore тоже работает | не подключён |
| P2 | Импортируется ли X25519 с `PURPOSE_AGREE_KEY`, и считает ли `KeyAgreement("XDH")` общий секрет, как наш `Curve25519.x25519`? | импорт скаляра из seed канарейки, сравнение с commonMain | **да**: импорт X25519 своим ключом `XDH` с `PURPOSE_AGREE_KEY`; `KeyAgreement("XDH", AndroidKeyStore)` с пиром из `KeyFactory("XDH")` даёт общий секрет вектора RFC 7748 | не подключён |
| P3 | Принимает ли Keystore RSA OAEP с непустой меткой (`PSource.PSpecified`)? | `Cipher("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")` с меткой age | **нет**: «Only pSpecifiedEmpty (PSource.PSpecified.DEFAULT) supported» — OAEP с меткой age Keystore не принимает ни с MGF1-SHA-256, ни с SHA-1. Шифр — `Cipher.getInstance(…)` **без** имени провайдера: с `AndroidKeyStore` его нет вовсе | не подключён |
| P4 | Работает ли «сырое» RSA (`RSA/ECB/NoPadding`, `ENCRYPTION_PADDING_NONE`) на импортированном ключе? | расшифровка шифртекста, сделанного `RsaOaep.encrypt`, и снятие OAEP в коде | **да**: `RSA/ECB/NoPadding` с `ENCRYPTION_PADDING_NONE` расшифровывает; OAEP age снимаем в коде (01 §4.1) | не подключён |
| P5 | Проверяет ли Keystore подпись сертификата в `PrivateKeyEntry`? | импорт с сертификатом с подписью-заглушкой | **нет**: импорт с сертификатом, у которого подпись — мусор, проходит, ключ подписывает | не подключён |
| P6 | Импорт P-256 с `setIsStrongBoxBacked(true)` | на телефоне с `FEATURE_STRONGBOX_KEYSTORE` | — (нет `FEATURE_STRONGBOX_KEYSTORE`) | не подключён |
| P7 | Какое исключение и на каком вызове бросает ключ «каждое использование» без промпта (`initSign`, `update` или `sign`)? | для `Signature` и `Cipher` | `initSign` и `update` проходят, `sign()` бросает `SignatureException` ← `KeyStoreException` «Key user not authenticated» (код −26, `KEY_USER_NOT_AUTHENTICATED`); AES-GCM: `init` проходит, `doFinal` бросает `IllegalBlockSizeException` ← тот же −26. **После снятия PIN ключи с привязкой к аутентификации система удаляет** (alias пропадает), а не оставляет с `KeyPermanentlyInvalidatedException` | не подключён |
| P8 | `KeyInfo.securityLevel` и `isUserAuthenticationRequirementEnforcedBySecureHardware` для каждого типа | лог | все ключи `SECURITY_LEVEL_SOFTWARE` (программный KeyMint эмулятора), `isUserAuthenticationRequirementEnforcedBySecureHardware = false`; `KEYSTORE` feature 300 | не подключён |
| P9 | Сколько идёт bcrypt_pbkdf с 24 раундами в нашем Kotlin на телефоне? | замер после шага 2, туда же | JVM (эта машина, JDK 21): **264 мс** на 48 байт ключа и IV (`BcryptTimingTest`); эмулятор `unitool35` (x86_64, API 35): **1,4–2,3 с** на весь `passphrase.roundtrip` самопроверки (разбор файла + bcrypt + расшифровка), пять прогонов 2026-09-26 — ниже порога 3 с (`passphrase.slow`) | не подключён |

(05 §5) На Mac, в `iosSimulatorArm64Test` и на iPhone:

| # | Вопрос | Как | симулятор | iPhone |
|---|---|---|---|---|
| P10 | Есть ли Secure Enclave в симуляторе на этом Mac? | `SecKeyCreateRandomKey` с `kSecAttrTokenIDSecureEnclave` | | — |
| P11 | Требует ли `SecKeyCreateDecryptedData` Face ID для ключа с `UserPresence`, какой `CFError` без него | без `LAContext`; на симуляторе `Features → Face ID` | | |
| P12 | `WhenPasscodeSetThisDeviceOnly` без кода и после снятия кода | лог | | |
| P13 | Подпись P-256 из enclave проходит `ssh-keygen -Y verify` после DER → mpint | интероп | | |

**(impl) Кто отвечает на P10–P13 без Mac (D180):** `SecureEnclaveSelfTest` (`services/ssh`
iosMain) в самопроверке iOS: P10 — есть ли enclave (`enclave.sign` проваливается с «made no
key»), P11 — `wrap.auth-required` с `interactionNotAllowed` пишет `CFError` отказа, P12 —
`wrap.passcode-class`, P13 — `enclave.sign` проверяет SSHSIG нашим `SshSig.verify`, который
на JVM сверен с `ssh-keygen -Y verify`. Написано, не выполнялось: столбцы заполнит первый отчёт
с iPhone.
| P14 | bcrypt_pbkdf 24 раунда в Kotlin/Native на iPhone и в wasm в браузере | замер, как P9 | | |

**(impl) Прогон шага 0, 2026-09-26:** `KeystoreProbeTest` (`services/ssh`, `connectedDebugAndroidTest`) на
`unitool35`. Телефон владельца к машине не подключён — его столбец пуст, и это не «нет», а «не
спрашивали»: на программном KeyMint эмулятора P1–P2 говорят о том, что умеет *Android 15*, а не
о том, что умеет TEE конкретного телефона. P9 — после шага 2 (нужен bcrypt).

Что это меняет. Эмулятор — `SOFTWARE`, значит по 05 §2.1 всё на нём — уровень 3, и ответы P1–P7
нужны не для него, а как проверенный путь для телефона: Ed25519 и X25519 импортируются одним и
тем же приёмом (`XDH` + свой сертификат), RSA для age — только «сырой» (P3 нет, P4 да), подпись
сертификата не проверяется (P5), и снятие PIN **удаляет** ключ, а не ломает его: карточка ключа
узнаёт о потере по отсутствию alias (01 §5.7 уточнён).

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

> **(07)** Пути модулей ниже — прежние (`superizer:ssh`, `ssh-ui`). Читать как: `ssh` →
> `unitool/services/ssh`, `ssh-ui` → `unitool/composeApp/settings`, публикация библиотеки —
> только в шаге 1. Содержание шагов в силе.

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
- **(impl) Как сделано, 2026-09-26.** Отступления от плана выше, каждое с причиной:
  - `androidx.biometric` не нужен: системный `BiometricPrompt` на API 30+ умеет PIN как
    запасной вход, а `KeyAgreement` в `CryptoObject` не держит ни он, ни AndroidX (01 §5.1,
    impl: у X25519-близнеца окно 5 с).
  - Отдельной `KeyPromptActivity` нет: лист разрешения и пароля — `KeyPromptSheet` хоста над
    оболочкой (шаг 4, `SecureWindow` = `FLAG_SECURE`), системный промпт показывает бэкенд над
    текущей активностью. `Busy` даёт keyring (один лист за раз), а не активность.
  - `AndroidSshKeyring` + `KeystoreResidence` + `SealedResidence` = один `KeystoreBackend`
    (`ChipBackend` из шага 3) за фабрикой `SshKeyringHost.android(...)`; ниже API 30 фабрика
    не даёт чипа, и всё — уровень 3 (D145 без отдельной ветки).
  - `setUserAuthenticationParameters` принимает `KeyProperties.AUTH_*`, а промпт —
    `BiometricManager.Authenticators`: это два разных набора чисел; тест читает `KeyInfo` и
    проверяет, что ключ записал именно STRONG | DEVICE_CREDENTIAL и окно 0.
  - Сессии стираются по `ACTION_SCREEN_OFF` (`ScreenOffTest`).
  - Самопроверка на устройстве — `SelfTestOnDeviceTest` в composeApp (без блокировки и с PIN
    1111): до этого `:composeApp:connectedDebugAndroidTest` в `verify.sh` проходил, потому что
    тестов в нём не было вовсе.
  - Итог на `unitool35`: `KeystoreBackendTest` (5), `ScreenOffTest`, `KeystoreProbeTest`,
    `AndroidKeystoreVaultTest` (+`hardware()`), `SelfTestOnDeviceTest` (2) — зелёные; тест кучи
    для Ed25519 — 0 находок. Телефон владельца не подключался: его столбец пуст.
- **(impl) Шаг 5b без Mac.** `SecureEnclaveBackend` и `SshKeyringHost.ios(...)` написаны и
  компилируются в klib (iosArm64, iosSimulatorArm64); **не выполнялись**. По D175 iOS-хост
  остаётся на уровне 3 (`passphraseOnly`), пока отчёт самопроверки с iPhone не зелёный;
  самопроверка iOS уже гоняет шаги enclave (`SecureEnclaveSelfTest`), чтобы этот отчёт было
  чем сделать.

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
- `UnitoolHost`/`MainActivity` (05: на всех платформах): `service(SshKeyring.Key) { caller -> keyring.forApp(caller) }`
  и `settingsSection("ssh", …) { SshKeysSection(keyring.admin, sources) }`. Источник «QR» —
  `AndroidQrScanner`, если камера есть.
- 2FA: «Импорт из файла» через `decrypt` (02 §6.1). Помнить ловушку `collectLatest`:
  загрузка в `runtime.scope`, сохранение под `NonCancellable` (память 2FA). Тест на
  `FakeSshKeyring`: отмена листа ничего не меняет, снапшот не содержит `secret=`.
- Bench test-app: «Подписать» и «Проверить» — видимая проверка `sign`/`verify`.
- `backup_rules.xml`, `data_extraction_rules.xml`: `exclude` для `cx.m42.superizer.ssh.xml`,
  `BackupRulesTest`. **(impl)** `exclude` запрещает release-lint (03 §2): правила остаются
  «только `include`», файл `cx.m42.services.ssh.xml` не включён, тест держит оба условия.
- `play/PRIVACY.md` и оба описания: разрешения читаем **из собранного APK** через `aapt2 dump
  xmltree`, а не из манифеста (память push-fcm). `androidx.biometric` добавит
  `USE_BIOMETRIC` и, скорее всего, `USE_FINGERPRINT`. Абзац о ключах: где хранятся, что
  никуда не отправляются (у модуля нет сети), что отпечаток проверяет система, а приложению
  приходит только «да» или «нет». Формулировки — по D149.
- Ручная приёмка 03 §7.
- **Готово.** Оба `verify.sh` зелёные, приёмка пройдена, скриншоты секции в
  `play/listing` не нужны.

## 4. Что меняется где

> **(07)** Актуальная карта — 07 §2. Ниже — прежняя.

```
/prj/superizer
  settings.gradle.kts                   + :ssh, :ssh-ui
  ssh/                                  NEW (commonMain, androidMain, iosMain, desktopMain, wasmJsMain, desktopTest, androidInstrumentedTest, iosSimulatorArm64Test)
  ssh-ui/                               NEW
  host/…/SuperizerHost.kt               + service(key) { caller -> }, settingsSection(…), secrets store, HostBackup (05)
  core/…/AppRuntime.kt, AppManifest.kt  + secrets, diagnostics, backup: BackupPolicy — контракт 3 (05, 06)
  host/…/HostRuntime.kt                 caller-bound lookup
  ui/…/SettingsScreen.kt                host sections first
  testing/                              + FakeSshKeyring, LeakScanner, Canary
  scripts/verify.sh                     + "ssh has no ktor", interop skips by name
  .github/workflows/*                   + apt-get install openssh-client openssh-server age
  CHANGELOG.md, docs/                   0.4.0 (05)
/prj/unitool
  gradle/libs.versions.toml             superizer 0.4.0, ssh, ssh-ui, (biometric comes transitively)
  composeApp/…/MainActivity.kt          register keyring, section, QR source; vault → host (05)
  composeApp/src/iosMain/…/UnitoolIos.kt  register SecureEnclaveBackend (05)
  composeApp/src/desktopMain, wasmJsMain  register PassphraseBackend + AgeVault (05)
  services/vault                        host-side only; CoreFoundation helpers from ssh (05)
  composeApp/src/androidMain/res/xml/*  include-only, cx.m42.services.ssh.xml not included (impl, 03 §2)
  composeApp/src/desktopTest/…          BackupRulesTest
  apps/twofa/                           − SshKey/Age/Curve25519/ChaCha/RsaOaep (moved), + import from file, accounts → runtime.secrets with one-release migration (05)
  play/PRIVACY.md, play/listing/*       permissions from the APK, keys paragraph
```

## 5. Выпуск и совместимость

- (07) Superizer 0.4.0 — **только** контракт 3, механика хоста и фейки (D183); после него
  библиотека до конца работы не меняется, все релизы — Unitool.
- (05) Выпуск — **0.4.0**, после 0.3.0 (замок idea/06 + iOS), который сейчас не закоммичен.
  Контракт приложения растёт до 3: `runtime.secrets`, `runtime.diagnostics`,
  `manifest.backup`. По D138 приложение, использующее их, пишет `minHostContract = 3`.
  `SshKeyring` по-прежнему приходит через `ServiceKey` (D19).
- (05) Миграция 2FA: один релиз `SecretVault.Key` остаётся зарегистрирован для приложений;
  2FA при первом запуске читает `accounts.sealed` старым сейфом, пишет в `secrets.accounts`,
  удаляет старое. Следующий релиз убирает `SecretVault` из контракта. Тестеры Play alpha уже
  держат запечатанные аккаунты, поэтому без этого шага нельзя.
- Если idea/06 (замок) принята, выпускать вместе. Обеим нужен `androidx.biometric`, а флаг
  «идёт свой промпт» у них общий. Контракт 2 из 06 D138 — её собственный, этой работе он не
  нужен.
- Приложение, которое без ключей не может (гипотетический git-кирпич), пишет
  `SshKeyring.Key` в `manifest.requires`. (05) Кольцо теперь есть на всех платформах, так что
  отклонения не будет; а приложение, которому нужен именно чип, смотрит `device.levels`.

## 6. Риски

| Риск | Что делаем |
|---|---|
| Ed25519 не импортируется в Keystore (P1) | запечатанный вариант спроектирован как равноправный. Меняется одна строка таблицы, а не модель |
| bcrypt 24 раунда медленный на слабом телефоне (P9) | это только импорт, с индикатором. `keepPassphrase` (01 §5.4) — с предупреждением о задержке при каждом использовании |
| UiAutomator не набирает PIN устойчиво | успешный путь проверяется на JVM и вручную (03 §6.4) |
| Производитель сломал KeyMint (бывает на дешёвых устройствах) | `KeystoreProbeTest` на таком устройстве покажет. Кольцо тогда предлагает только запечатанный вариант, если `securityLevel` «software» — с честной надписью |
| Metaspace или OOM Kotlin-демона при двух новых модулях | память 2FA: `gradle.properties` уже 3g; при OOM — `./gradlew --stop`, **не** `pkill` |
| Кто-то параллельно коммитит в `/prj/superizer` | `git log` перед каждым шагом |
| (05) Mac недоступен | шаг 5b откладывается; 0.4.0 выходит для Android, desktop и web; iOS-кольцо и iOS-сейф остаются «написано, не выполнялось», как в ios/01 |
| (05) Secure Enclave нет в симуляторе (P10) | iOS-тесты уровня 2 только на iPhone, вручную по 03 §7 |
| (05) bcrypt в wasm медленный (P14) | индикатор при открытии секретов; раунды не снижаем |
| (05) Удаление ключа, который запечатывает секреты | запрещено до перезапечатывания (D173); тест I18 |

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
- **D161** (переписано 05). Выпуск — Superizer 0.4.0, после 0.3.0 (idea/06 + iOS). Контракт
  приложения — 3. Первый потребитель — 2FA на `runtime.secrets` и импорт экспорта, второй —
  `HostBackup`, третий — bench test-app.
- **D176–D180** (06). Диагностика, самопроверка, каталог кодов, TestFlight-цикл вместо Mac.
- **D181–D183** (07). Контракты в Superizer, реализации в Unitool; 0.4.0 — только контракт.
- **D175** (05). Порядок: шаги 0–4 и 6–7 не ждут Mac. Шаг 5b идёт, когда Mac есть; до этого
  iOS в 0.4.0 — только уровень 3 через `PassphraseBackend` (он commonMain и компилируется под
  iOS), с честной надписью.

## 9. Что решить до первой строки кода (2026-09-26)

Не вопросы вкуса, а места, где без ответа шаг остановится.

| # | Проблема | Что блокирует | Предложение |
|---|---|---|---|
| Б1 | Superizer 0.3.0 (замок idea/06, iOS) не закоммичен и не опубликован; Unitool с 2FA-замком и iOS тоже | шаг 1: `ssh` строится на контракте 2 и iOS-таргетах 0.3.0 | сначала закоммитить и опубликовать 0.3.0, потом ветка под 0.4.0 |
| Б2 | Ed25519 в Keystore (P1–P2) неизвестен; от него зависит, где живёт самый частый ключ | шаг 5 | шаг 0 на `unitool35` и телефоне владельца первым; модель не меняется при любом ответе |
| Б3 | Mac нет (ios/01 §5): iOS-сейф не выполнялся, P10–P13 некому прогнать | шаг 5b, iOS-тесты сейфа | **Решено, 06 §4, D180:** самопроверка в приложении + `ios.yml` в TestFlight; отчёт с iPhone заменяет Mac. Нужны секреты подписи (вопрос 88). До первого зелёного отчёта iOS — уровень 3 из commonMain (D175) |
| Б4 | Миграция 2FA: тестеры Play alpha держат `accounts.sealed` под ключом `cx.m42.services.vault`; после переезда на `runtime.secrets` 2FA не имеет сейфа, чтобы это прочитать | шаг 7 | сейф хоста использует тот же alias и формат `v1:`/`e1:`; `SecretVault.Key` живёт для приложений ещё один релиз; 2FA переносит при первом запуске и удаляет старое; тест на JVM с `SoftwareVault` |
| Б4 (impl, шаг 4) | **Сделано:** сейф хоста на Android — тот же `AndroidKeystoreVault` с alias `cx.m42.services.vault`, на iOS — тот же `IosKeychainVault`; `SecretVault.Key` зарегистрирован ещё на релиз; 2FA при первом чтении открывает `accounts.sealed` старым сейфом, пишет в `runtime.secrets`, удаляет старое только после записи нового; открытый `accounts` переносится так же. Проверено: `TwoFaVaultTest` (JVM, `SoftwareVault`) и `SecurityWiringTest` (весь хост Unitool: `app.2fa.accounts.sealed` → `secret.2fa.accounts`, запечатан сейфом хоста) | — | — |
| Б5 | Ключ, который запечатывает секреты хоста: какой из нескольких, что при удалении, что при `usable = false` | шаг 4 | настройка «ключ для секретов», по умолчанию первый Ed25519/RSA; удаление запрещено до перезапечатывания (D173); `usable = false` → секреты «Unreadable», как сегодня в 2FA, и подсказка импортировать ключ заново |
| Б6 | На desktop и web нет сервиса выбора файла: импорт ключа и восстановление из бэкапа нечем открыть; `FileSharer` есть только для отдачи | шаги 6–7 | новый сервис `FilePicker` в 0.4.0 (Android SAF, iOS `UIDocumentPicker`, desktop `FileDialog`, web `<input type=file>`); без него desktop и web остаются со вставкой и QR |
| Б7 | Конец сессии пароля на desktop и web: `ACTION_SCREEN_OFF` и `protectedDataWillBecomeUnavailable` там нет | шаг 3 | сессия = процесс хоста плюс `lockAll()` и таймер бездействия из настройки; на web — ещё `visibilitychange` в hidden дольше N минут |
| Б8 | Сессия секретов хоста «одна на запуск» и правило кольца «каждое использование» — два разных таймера с одним ключом | шаг 3 | **Решено, D176:** `AgeVault` держит расшифрованные секреты, а не ключ; ключ после `open` стирается; подпись — снова пароль |
| Б9 | Бэкап включает конфиги приложений из superizer/16, которого нет | шаг 7 | v1 бэкапа: `app.*`, `secret.*`, настройки хоста; конфиги 16 добавятся, когда появятся, схема с `schema: 1` это переживёт |
| Б10 | Контракт 3 и правило D138: приложение с `backup` или `secrets` на хосте контракта 2 | шаг 4 | `minHostContract = 3` обязателен при любом из полей; реестр отклоняет несогласованный манифест, как для `protection` |
| Б11 | CI не гоняет ни `AndroidKeystoreVaultTest`, ни будущие инструментальные тесты `ssh` | шаг 5 | job с эмулятором `api-33` в `verify.yml` для `:services:vault` и `:ssh` `connectedDebugAndroidTest`; самопроверка 06 §3 и есть этот тест. **(impl)** job `device` написан (плюс `:composeApp:connectedDebugAndroidTest` — самопроверка), в CI не запускался: пуш за владельцем |
| Б12 | `SecretBytes` на wasm и Kotlin/Native: обнуление `ByteArray` не гарантирует отсутствие копий | шаг 3 | принять и записать в надписи (02 §9); тест I15 на JVM, на wasm — только отчёт |
| Б13 | Экспорт 2FA в otpauth и полный бэкап — два формата, оба на ключи GitHub; человек может спутать | шаг 7 | разные расширения и заголовки: `*.2fa.age` и `unitool-backup-*.age`; импорт распознаёт по заголовку и говорит, что это |
| Б14 | `IosKeychainVault` и `SecureEnclaveBackend` делят CoreFoundation-код | шаг 5b | **Снято 07:** оба в Unitool; обёртки в `services/ssh` iosMain, `services/vault` зависит от него |

Порядок: Б1 → шаг 0 (Б2, и Б3 если Mac есть) → шаги 1–4 (Б5, Б7, Б8, Б10) → шаг 5 с Б11 →
шаг 6 с Б6 → шаг 7 с Б4, Б9, Б13.
