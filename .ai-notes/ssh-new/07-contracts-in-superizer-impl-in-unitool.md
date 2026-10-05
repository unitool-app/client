# 07 — Разделение: контракты в Superizer, реализация в Unitool

> **Версия:** 1.0 · **Дата:** 2026-09-26 · **Статус:** решение владельца, внесено в 01–06.
>
> Владелец: «только методы в Superizer; само хост-приложение Unitool реализует интерфейсы для
> всего». Это отменяет D139 (модули `superizer:ssh` и `ssh-ui` в библиотеке) и вопрос 69 и
> возвращает к тому, как Unitool уже устроен: `services/vault`, `services/qr-scanner`,
> `services/share` реализуют контракты Superizer и регистрируются в `buildUnitool`.

## 1. Правило

| В Superizer | В Unitool |
|---|---|
| Интерфейсы, типы данных, `ServiceKey`, коды диагностики, версия контракта | Всё, что их реализует: криптография, форматы, Keystore, Secure Enclave, пароль, агент, бэкап-шифрование, выбор файла, экраны, таблицы строк |
| Механика хоста, которая не зависит от платформы и криптографии и работает через эти интерфейсы: обёртка `runtime.secrets` над `SecretVault`, обход префиксов для бэкапа, агрегатор диагностики, слоты секций Settings, проверка `minHostContract` | Реализации, которые хост в эту механику подставляет |
| Фейки интерфейсов и сканер канареек в `superizer:testing` — инструмент для любого реализатора | Тесты реализаций, интероп с `ssh-keygen`/`age`, инструментальные и iOS-тесты |

Что это даёт. Библиотека не тянет `androidx.biometric`, CoreFoundation и 15 тысяч строк
криптографии; релиз 0.4.0 — это контракт 3 и механика, маленький и быстрый. Вся тяжёлая
работа идёт в Unitool без цикла «опубликовать библиотеку → поднять версию», который сегодня
блокирует даже пуш 2FA-замка. Чего это стоит: другому хосту пришлось бы реализовать кольцо
заново. Владелец это принял. Серверная привязка (idea/01) и подписанные активации (02 §6.4)
в библиотеке остаются возможны: им нужен только `SshKeyring` через `ServiceKey`, а не код.

## 2. Что где

### 2.1. Superizer 0.4.0 (контракт 3)

```
core
  runtime/Services.kt        + SecretVault (переезжает из unitool/services/vault),
                               FilePicker, HostBackupCipher (age на получателей — тоже реализация),
                               SshKeyring, SshKeyAdmin, SshAgent, StorageDiagnostics, DiagnosticsStrings
  runtime/AppRuntime.kt      + secrets: StorageService, diagnostics: AppDiagnostics
  app/AppManifest.kt         + backup: BackupPolicy (D138: любое поле → minHostContract = 3)
  ssh/Types.kt               KeyId, SshKeyInfo, KeyStorage, KeyringDevice, Inspection, ImportOptions,
                             UsePolicy, Grant, UseRecord, SshOutcome, SshSig-типы — данные без логики
  diagnostics/Types.kt       DiagnosticsReport, Finding, Status, Subject, Action, SelfTestStep, каталог кодов как константы
host
  SecretsStore               runtime.secrets = префикс secret.<id>. + SecretVault хоста (05 §3.2)
  HostBackup                 обход app.*/secret.*, бандл, вызывает HostBackupCipher и FilePicker/FileSharer (05 §3.3)
  DiagnosticsHub             собирает Finding от зарегистрированных DiagnosticsSource, хранит отчёт, пульс (06 §1–§2)
  SuperizerBuilder           + service(key) { caller -> }, settingsSection(host-level), diagnosticsSource(…), secretsKey(…)
ui
  HostSection, HostScreen    слоты (§2.4): секции Settings уровня хоста и целые экраны хоста; ShellDestination.Host(id)
  HomeBanner                 слот баннера над плитками Home; LocalDiagnosticsStrings
testing
  FakeSshKeyring, FakeSecretVault, FakeStorageDiagnostics, LeakScanner, Canary
```

Ни одной строки текста для человека, ни одного криптопримитива.

### 2.2. Unitool

```
services/vault             реализации SecretVault: AndroidKeystoreVault, IosKeychainVault (как сейчас); интерфейс уехал в core
services/ssh        NEW    cx.m42.services.ssh — commonMain, androidMain, iosMain, desktopMain, wasmJsMain
  commonMain/crypto          Ed25519, X25519, ChaCha20-Poly1305, AES-CTR/GCM, bcrypt_pbkdf, RSA, SHA/HMAC/HKDF — из 2FA и новое (02 §10)
  commonMain/format          SshWire, SshPublicKey, OpenSshKeyFile, SshSig, Age, AgentCodec
  commonMain/backend         PassphraseBackend (уровень 3), AgeVault, сессия, SecretBytes
  commonMain/keyring         SshKeyringImpl, SshKeyAdminImpl, AgentServer — над Backend'ами
  commonMain/diagnostics     SelfTest (06 §3), KeyringDiagnosticsSource
  androidMain                KeystoreBackend (уровни 1–2), KeyPromptActivity, записи
  iosMain                    SecureEnclaveBackend, CoreFoundation-обёртки (общие с vault), записи с NSFileProtectionComplete
  desktopMain / wasmJsMain   регистрация PassphraseBackend; ECDSA-проверка через JCA / Unsupported
services/file-picker NEW    FilePicker: SAF, UIDocumentPicker, FileDialog, <input type=file>
services/backup      NEW    HostBackupCipher на age (получатели github.com/<login>.keys), формат бандла, восстановление через SshKeyring.decrypt
composeApp
  i18n/DiagnosticsStrings.{en,ru}.kt   единственное место с текстами (06 §6)
  settings/SshKeysSection.kt           секция «SSH keys», карточка, мастер импорта, «Задать пароль»
  settings/StorageSecuritySection.kt   экран Storage & security, Run self-test, Share report
  settings/BackupSection.kt            бэкап и восстановление
  UnitoolHost.kt                       register(...) сервисов на каждой платформе, diagnosticsSource, settingsSection
apps/twofa                  − крипто и SshKey (в services/ssh), accounts → runtime.secrets, импорт экспорта, бейдж диагностики
```

Строки секций и экранов — в `composeApp` по образцу `HostStrings`: интерфейс с таблицами en и
ru, en — источник. `services/*` текстов не содержат: только коды и `SshOutcome`.

### 2.3. Что в Superizer уже есть и как план на это ложится

Проверено по `core/src/commonMain` 2026-09-26. Контракты уже есть, и новые встают рядом с ними.

| Уже есть | Где | Как используется в этой работе |
|---|---|---|
| `ServiceKey<T>` и `AppRuntime.service(key)` (D19) | `runtime/ServiceKey.kt` | `SshKeyring.Key`, `FilePicker.Key` — необязательные сервисы, как `QrScanner`, `FileSharer` |
| `StorageService` (`get/set/remove/keys`) | `runtime/Services.kt` | `runtime.secrets` — тот же интерфейс, второй экземпляр |
| `SecretVault` с `ServiceKey("secret-vault")` | сегодня в `unitool/services/vault` | переезжает в `core` без изменения сигнатуры (D182); реализации остаются в Unitool |
| `UserPresence` (`confirm(reason)`) и `DeviceAuthenticator` (`authenticate`, `availability`) | `lock/Lock.kt`, контракт 2 | подтверждение уровней 1–2 идёт **не** через них, а через `CryptoObject`/`LAContext`, привязанные к ключу (01 §5); но `availability()` — источник finding `device.no-screen-lock`, и тексты листа берутся тем же путём |
| `AppLockPort`, `HostSettingsPort` | `Superizer.kt` | образец для нового порта хоста `StorageDiagnostics`; экран Storage & security читает его так же, как экран замка читает `AppLockPort` |
| `DiagnosticsPort` (лог, события, `reset`, `unlock` — Service Menu) | `Superizer.kt` | **не то же самое**, что `StorageDiagnostics` из 06: тот — про хранилище и ключи для человека, этот — сервисное меню. Имена оставляем оба; Service Menu показывает `exportText` через `DiagnosticsPort.log`-подобный раздел (06 §7) |
| `AppSetupContext.settingsSection { }` | `app/…` | секции приложений; для секций уровня хоста нужен слот в `SuperizerBuilder` (шаг 1) |
| `HostStrings` + `LocalHostStrings` | `ui/i18n` | образец для `DiagnosticsStrings`: интерфейс в библиотеке, таблицы в Unitool, `CompositionLocal` |
| `Notifier`, `PromoCodeResolver` (`fun interface`, host) | `host/` | образец «библиотека объявляет, Unitool подставляет в `buildUnitool`» — так же регистрируются `SecretVault`, `HostBackupCipher`, `DiagnosticsSource` |
| `Logger`, `Clock`, `SuperizerEvent` | `runtime/` | журнал использования ключей — события; время бэкапа и самопроверки — `Clock` |
| `HostInfo.services` | `runtime/AppRuntime.kt` | реестр проверяет `manifest.requires` против него; `SshKeyring.Key` попадает туда, когда Unitool его регистрирует |

Нового в контракте 3, чего нет сегодня: `SshKeyring`, `SshKeyAdmin`, `SshAgent`, `FilePicker`,
`HostBackupCipher`, `StorageDiagnostics` и `DiagnosticsSource`, `DiagnosticsStrings`,
`AppRuntime.secrets`, `AppRuntime.diagnostics`, `AppManifest.backup`, типы `ssh/` и
`diagnostics/`.

### 2.4. UI: что в `superizer:ui` есть и чего нет

Проверено по `ui/src/commonMain` 2026-09-26. **Интерфейсов для UI, которые хост мог бы
реализовать, в библиотеке нет.** `ui` — это готовый Compose-код: `SuperizerShell`, Home,
Catalog, Settings, Activate, ServiceMenu, шторка замка, компоненты (`Section`, `ToggleRow`,
`ChoiceRow`, `AppScaffold`, иконки). Unitool сам не рисует ничего хостового:
`UnitoolHost.kt` вызывает `SuperizerShell(superizer)` и всё. Точки расширения сегодня:

| Есть | Что это | Хватает ли |
|---|---|---|
| `AppSetupContext.settingsSection { runtime -> }` | секция Settings **приложения**, composable-лямбда, под заголовком приложения | нет: у хоста нет такого слота, а SSH keys и Storage & security — секции хоста |
| `HostStrings` + `hostStringsFor(langTag)` | интерфейс слов хоста; таблицы en/ru **внутри библиотеки** | для новых экранов не нужен: их слова — в Unitool (`DiagnosticsStrings`, `SshStrings`) |
| `ShellDestination` — `sealed` | Home, App, Catalog, Settings, Activate, ServiceMenu | нет: хост не может добавить экран |
| публичные компоненты `Section`, `ToggleRow`, `ChoiceRow`, `AppButton`, `AppTextField`, `AppScaffold` | строительные блоки | да: экраны Unitool собираются из них, чтобы выглядеть как хост |
| `ProtectionSection(superizer)` в Settings | секция замка, приватная | образец: так же выглядит секция диагностики, но живёт в Unitool |

Чтобы правило 07 работало и для UI, контракт 3 добавляет в библиотеку **слоты, а не экраны**:

```kotlin
// core — интерфейсы, которые Unitool реализует
public interface HostSection {                      // блок в Settings, между секциями хоста и секциями приложений
    public val id: String
    public fun title(langTag: String): String        // слова — у реализации
    @Composable public fun Content(superizer: Superizer, open: (HostScreen) -> Unit)
}
public interface HostScreen {                       // целый экран хоста: SSH keys, Storage & security, Backup, карточка ключа
    public val id: String
    public fun title(langTag: String): String
    @Composable public fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit)
}
public fun interface HomeBanner {                   // одна строка над плитками, null = ничего
    @Composable public fun Content(superizer: Superizer, open: (HostScreen) -> Unit): Boolean
}
// SuperizerBuilder
public fun hostSection(section: HostSection)
public fun homeBanner(banner: HomeBanner)
```

`ui` получает `ShellDestination.Host(screen: HostScreen, from: ShellDestination)`, рисует
`AppScaffold` с заголовком и кнопкой назад и внутри вызывает `Content`. Settings рисует
`hostSections` после `ProtectionSection` и до секций приложений. Home рисует баннер первым.
Строки заголовков — из реализации, по `langTag` хоста; библиотека их не знает.

(impl, 2026-10-05: `SshKeysSection` снят — список ключей открывается промокодом `{ssh-keys}`, см.
02 §5.) Что реализует Unitool: `SshKeysSection` + `SshKeysScreen`, `KeyCardScreen`, `ImportKeyScreen`,
`StorageSecuritySection` + `StorageSecurityScreen`, `BackupSection` + `BackupScreen`,
`DiagnosticsBanner`. Всё в `composeApp/…/settings/`, из компонентов библиотеки, со своими
таблицами строк en/ru.

## 3. Что меняется в 01–06

- **01 §6.1** — таблица модулей заменена на §2 этой заметки; абзац «Почему в библиотеке» →
  «Почему в Unitool». **D139** переписан.
- **02 §10** «Где будет криптография» — те же файлы, но в `unitool/services/ssh`; `internal`
  внутри модуля Unitool. **D156**: крипто переезжает из 2FA в `services/ssh`, а не в библиотеку.
- **03**: JVM-тесты и интероп — `services/ssh/src/desktopTest`; инструментальные —
  `services/ssh/src/androidInstrumentedTest`; iOS — `services/ssh/src/iosSimulatorArm64Test`;
  `LeakScanner` и `Canary` остаются в `superizer:testing`. **D159**: файл записей —
  `cx.m42.services.ssh`.
- **04**: шаги перераспределены (§4 ниже); «Что меняется где» переписано; выпуск — Superizer
  0.4.0 только с контрактом и механикой, всё остальное — релизы Unitool.
- **05**: `superizer:ssh` → `unitool/services/ssh`; вопрос 84 снимается: CoreFoundation-обёртки
  общие внутри одного репозитория (`services/ssh` iosMain, `services/vault` зависит от него).
- **06**: интерфейс `DiagnosticsStrings` и коды — в `core`; `DiagnosticsHub` — в `host`;
  `SelfTest` и экраны — в Unitool. Уже так и написано, уточнены только имена модулей.

## 4. Шаги плана после разделения

| Шаг | Что | Где | Оценка |
|---|---|---|---|
| 0 | Спайк P1–P14: `KeystoreProbeTest` = `SelfTest` под тест-раннером | unitool/services/ssh | 1 день |
| 1 | **Superizer 0.4.0**: контракт 3 — интерфейсы, типы, `runtime.secrets`, `BackupPolicy`, `DiagnosticsHub`, `HostBackup`-механика; UI-слоты `HostSection`, `HostScreen`, `HomeBanner`, `ShellDestination.Host` (§2.4); фейки; BCV, CHANGELOG, публикация | superizer core/host/ui/testing | 2,5 дня |
| 2 | `services/ssh` commonMain: переезд крипто из 2FA, форматы, bcrypt, AES, Ed25519, SSHSIG, расшифровка age, `rekey` | unitool | 2,5 дня |
| 3 | `PassphraseBackend`, `AgeVault` (D176), `SshKeyringImpl`, агент, `SelfTest`, JVM-тесты утечек, интероп | unitool | 2,5 дня |
| 4 | `services/vault` на интерфейс из core; `runtime.secrets` в `buildUnitool`; 2FA на `secrets` с миграцией (04 §9 Б4) | unitool | 1 день |
| 5 | Android: `KeystoreBackend`, `KeyPromptActivity`, записи, инструментальные тесты, job с эмулятором в CI | unitool | 2,5 дня |
| 5b | iOS: `SecureEnclaveBackend`, записи, `SelfTest` на iPhone по кабелю или через TestFlight (`ios.yml` с обеими лентами уже написан), отчёт с iPhone | unitool | 1,5 дня |
| 6 | UI в `composeApp`: секция SSH keys, мастер, «Задать пароль», Storage & security, баннер, бейджи, `DiagnosticsStrings` en/ru, скриншоты | unitool | 4 дня |
| 7 | `services/file-picker`, `services/backup`, экран бэкапа и восстановления, PRIVACY и листинг по каталогу кодов, ручная приёмка, релиз Unitool | unitool | 3 дня |
| | **Итого** | | **≈ 20,5 дней** |

Шаг 1 — единственный, который трогает библиотеку, и он идёт первым после публикации 0.3.0
(04 §9 Б1). Шаги 2–7 — только Unitool, библиотека подключена как `includeBuild` и не требует
публикации между шагами. Порядок блокеров 04 §9 не меняется.

## 5. Решения

- **D181** (решение владельца, отменяет D139). В Superizer — только контракты (интерфейсы,
  типы, коды, версия контракта) и платформо-независимая механика хоста над ними. Все
  реализации — в Unitool: `services/vault`, `services/ssh`, `services/file-picker`,
  `services/backup`, экраны и таблицы строк в `composeApp`.
- **D182.** `SecretVault` переезжает из `unitool/services/vault` в `superizer:core` как контракт
  3; реализации остаются в Unitool.
- **D183.** Superizer 0.4.0 = контракт 3 + механика + фейки, без криптографии, без текстов, без
  платформенных зависимостей. Дальше библиотека не меняется до конца этой работы.
- **D184.** В `superizer:ui` — только слоты (`HostSection`, `HostScreen`, `HomeBanner`,
  `ShellDestination.Host`) и компоненты; экраны ключей, диагностики и бэкапа со своими
  таблицами строк — в Unitool `composeApp`.

## 6. (impl) Контракт 3 в точности — до кода шага 1

> **Сделано 2026-09-26:** superizer `6020d37` (контракт) и `f79c93a` (0.4.0), ветка `contract-3`,
> `verify.sh` PASS. Всё ниже построено как написано; сверх написанного — `RawDiagnosticsStrings`
> (сырые коды по умолчанию), `HostScreen.secure` (флаг окна для мастера импорта, T10),
> `RestoreReport.keysToImport`, карточки «Secrets» и «SSH keyring» в бенч-приложении (подпись под
> `test-app@superizer` для ручной приёмки 03 §7 п. 3 — после этого шага библиотеку не трогаем, так
> что сейчас или никогда). Не запушено: публикация в GitHub Packages ждёт владельца.

> Дата: 2026-09-26. Пишется до первой строки шага 1, потому что после него библиотека не
> меняется (D183), и всё, что понадобится шагам 2–7, должно быть здесь. Отличия от §2.1 и 02/05/06
> помечены **Отличие** с причиной; ни одно не расширяет контракт сверх §2.1 по смыслу, но два
> добавляют по одному члену с умолчанием в уже названные типы.

### 6.1. Пакеты `core`

| Пакет | Что | Кто реализует |
|---|---|---|
| `secrets` | `SecretVault` (переехал, `Key = "secret-vault"` остаётся на релиз ради Б4), `SecretsPort` (порт хоста), `SecretsUnavailableException` | Unitool: `AndroidKeystoreVault`, `IosKeychainVault`, `AgeVault` |
| `backup` | `BackupPolicy` (манифест), `HostBackupCipher`, `BackupPort` (порт хоста), `BackupBundle`, `AppBackup`, `BackupKeyName`, `BackupPreview`, `RestorePlan`, `RestoreReport`, `BackupOutcome` | Unitool: `AgeBackupCipher` в `services/backup` |
| `ssh` | `SshKeyring`, `SshKeyAdmin`, `SshAgent` и данные: `KeyId`, `SshKeyInfo`, `KeyStorage`, `Chip`, `KeyringDevice`, `Inspection`, `ImportRefusal`, `ImportOptions`, `ImportOutcome`, `UsePolicy`, `KeyOrigin`, `KeyCapability`, `KeyPurpose`, `GeneratedKind`, `Grant`, `UseRecord`, `KeyOperation`, `SshOutcome`, `SshSignature`, `AgentIdentity`, `AgentConstraint`, `MIN_PASSPHRASE = 12` | Unitool: `services/ssh` |
| `diagnostics` | `StorageDiagnostics` (порт хоста), `DiagnosticsSource`, `SelfTestRunner`, `AppDiagnostics`, `DiagnosticsStrings`; данные `DiagnosticsReport`, `Finding`, `Status`, `Subject`, `DiagnosticsAction`, `DeviceFacts`, `SelfTestStep`, `SelfTestResult`; каталог `DiagnosticsCodes` (константы 06 §6.1 и список `ALL`) | Unitool: источники, самопроверка, таблицы слов |
| `runtime` | `FilePicker`, `PickedFile`; `AppRuntime.secrets`, `AppRuntime.diagnostics` | Unitool: `services/file-picker` |
| корень | `HostSection`, `HostScreen`, `HomeBanner`; `Superizer.secrets/backup/storage/hostSections/homeBanners` | Unitool: экраны в `composeApp` |

**Отличие (пакеты).** §2.1 кладёт всё в `runtime/Services.kt`; разнесено по пакетам по теме:
ни одно имя не меняется, а файл на пятьсот строк из шести тем читать никто не будет.

### 6.2. Секреты

```kotlin
public interface SecretVault {                         // без изменений сигнатуры (D182), плюс одно свойство
    public suspend fun seal(plaintext: String): String
    public suspend fun open(sealed: String): String?
    /** False when [open] would have to ask the person first (a passphrase not given this session). */
    public val opensSilently: Boolean get() = true
}
public interface SecretsPort {                         // Superizer.secrets
    public val vault: StateFlow<SecretVault?>          // null → значения лежат как `plain:` (06: vault.missing)
    public suspend fun reseal(next: SecretVault): Boolean   // всё или ничего: открыть текущим (может спросить), запечатать новым (не спрашивает)
    public suspend fun counts(): Map<AppId, SecretsCount>   // для диагностики: сколько у приложения запечатано и открыто
}
public class SecretsUnavailableException : Exception   // runtime.secrets.get: значение есть, но открыть нельзя сейчас
```

- **Отличие (`opensSilently`).** Пульс (06 §2) и самопроверка открывают значение на старте. У
  `AgeVault` открытие без открытой сессии — это диалог пароля, а на старте диалога быть не должно.
  Свойство с умолчанием `true` не ломает ни одну реализацию; хост открывает пульс, только когда
  оно `true`.
- `runtime.secrets` — `StorageService` приложения под префиксом `secret.<appId>.`. `get` бросает
  `SecretsUnavailableException`, если значение есть, а сейф его не открыл (отмена пароля, ключ
  потерян). Вернуть `null` было бы хуже: приложение сочло бы, что данных нет, и записало бы
  пустой список поверх (ровно ловушка, от которой `AccountStore` сегодня защищается).
- Без сейфа значение пишется как `plain:<значение>`; `reseal` запечатывает такие без вопросов (I16).
- Один диалог пароля за сессию (D176) обеспечивает `AgeVault` сам: при первом промахе кэша он
  открывает все `secret.*` разом. Список значений ему даёт Unitool (`SafePrefs` хоста), в контракт
  это не выходит.
- `DiagnosticsPort.reset(id)` (D35) стирает и `secret.<id>.`.

### 6.3. Бэкап

```kotlin
@Serializable public sealed interface BackupPolicy { All; None; Except(keys: Set<String>) }  // AppManifest.backup, умолчание All
public interface HostBackupCipher {                    // Unitool: age
    public suspend fun encrypt(bundle: ByteArray, recipients: List<String>): ByteArray   // строки authorized_keys
    public suspend fun decrypt(file: ByteArray): SshOutcome<ByteArray>                    // кольцом, с листом
}
public interface BackupPort {                          // Superizer.backup
    public val lastBackupAt: StateFlow<Long?>
    public suspend fun preview(): BackupPreview
    public suspend fun export(recipients: List<String>, reason: String): BackupOutcome<ByteArray>
    public suspend fun read(file: ByteArray): BackupOutcome<RestorePlan>
    public suspend fun restore(plan: RestorePlan, apps: Set<String>, reason: String): BackupOutcome<RestoreReport>
}
```

- **Отличие (без `FilePicker`/`FileSharer` в механике).** §2.1 пишет, что `HostBackup` сам зовёт
  выбор файла и отдачу. Здесь порт принимает и возвращает байты, а файл выбирает и отдаёт экран
  Unitool: `FileSharer` живёт в Unitool (`services/share`), и тянуть его интерфейс в библиотеку
  ради одного вызова — лишнее обязательство. `FilePicker` в `core` остаётся, как в §2.1: он нужен
  приложениям (импорт файла в 2FA).
- `reason` — слова листа подтверждения (D148, D169): текста в библиотеке нет, его даёт экран.
- `BackupBundle` — схема 05 §3.3: `schema = 1`, `host`, `settings` (ключи `host.*`, кроме сессии),
  `apps.<id> = { version, storage, secrets }`, `keys` (имена). Имена ключей даёт
  `SuperizerBuilder.backupKeys { … }`, потому что ключей хост не знает.
- Приложение, которого нет в сборке, восстанавливается под своим префиксом (05 §3.3).

### 6.4. Диагностика

```kotlin
public interface StorageDiagnostics {                  // Superizer.storage, как 06 §1
    public val report: StateFlow<DiagnosticsReport>
    public suspend fun inspect(): DiagnosticsReport
    public suspend fun selfTest(withConfirmation: Boolean): DiagnosticsReport
    public fun exportText(): String
}
public fun interface DiagnosticsSource { public suspend fun inspect(): Inspected }   // Unitool: сейф, кольцо, устройство
public fun interface SelfTestRunner { public suspend fun run(withConfirmation: Boolean): SelfTestResult }  // Unitool: SelfTest
public interface AppDiagnostics { public val findings: StateFlow<List<Finding>> }     // runtime.diagnostics
public interface DiagnosticsStrings { title(code); detail(code, facts); action(action); status(status) }
```

- **Отличие (`runtime.diagnostics.findings` вместо `forApp(appId)`).** `AppRuntime` уже привязан к
  приложению; второй раз называть `appId` значило бы позволить спросить про чужое.
- Хост сам даёт: `vault.missing`, пульс (`vault.key-lost`, `vault.broken`), `app.secrets.sealed` /
  `app.secrets.plain` по приложениям, `backup.never/stale/ok`, `selftest.never/ok/failed`.
  Остальные коды 06 §6.1 дают источники Unitool.
- Самопроверка один раз после установки и после смены версии хоста, без подтверждения (вопрос 89).
- `exportText()` — английский отчёт из кодов и фактов (06 §5), в библиотеке: это формат, а не
  слова для человека.

### 6.5. Хост и шелл

- `SuperizerBuilder`: `service(key) { caller -> }`, `secretVault(vault)`, `backupCipher(cipher)`,
  `backupKeys { }`, `diagnosticsSource(source)`, `selfTest(runner)`, `hostSection(section)`,
  `homeBanner(banner)`.
- `HostSection`, `HostScreen`, `HomeBanner` — как §2.4; `ShellDestination.Host(screen, from)`;
  Settings рисует секции хоста после секции замка; Home — баннеры первыми.
  `LocalDiagnosticsStrings` в `ui`, по умолчанию — сырые коды (06 §6).
- `AppRegistry`: `backup != All` при `minHostContract < 3` — отказ, как для `protection` (Б10).
- `testing`: `FakeSecretVault`, `FakeSshKeyring` (лист отвечает сценарий теста, операции — лямбды
  теста: крипто в библиотеке нет), `FakeStorageDiagnostics`, `LeakScanner`, `Canary`;
  `FakeAppRuntime` получает `secrets` и `diagnostics`.
