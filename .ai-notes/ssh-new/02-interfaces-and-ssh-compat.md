# 02 — Интерфейсы, совместимость с ssh, как ключом пользуется Superizer

> **Обновлено 2026-09-26 по заметке 05:** `KeyStorage` с тремя уровнями, `rekey`, шаг «Задать
> пароль», кольцо на всех платформах, новые §6.7 (сейф хоста на ssh-ключе) и §6.8 (бэкап),
> контракт 3 (`runtime.secrets`, `BackupPolicy`). Изменённые места помечены «05».

> **Версия:** 1.0 · **Статус:** на ревью у владельца · **Дата:** 2026-09-24
>
> **История:** 1.0 — первая редакция.

---

## 1. Три интерфейса и кто что получает

| Интерфейс | Что это | Кто получает | Как |
|---|---|---|---|
| `SshKeyring` | операции закрытым ключом, безопасные для приложений: SSHSIG с namespace, расшифровка age | приложения | `runtime.service(SshKeyring.Key)`, экземпляр привязан к вызывающему (01 §6.2) |
| `SshAgent` | протокол ssh-agent один в один | только хост: будущий SSH-клиент, мост на desktop или в Termux, тесты совместимости | хост создаёт сам; в реестре сервисов его **нет** |
| `SshKeyAdmin` | импорт, генерация, удаление, разрешения, журнал | только секция Settings | хост создаёт сам и передаёт в `ssh-ui` конструктором |
| чистые функции | всё, что делается открытым ключом: разбор, отпечаток, проверка подписи, шифрование age | кто угодно, на всех трёх платформах | обычный импорт из `cx.m42.superizer.ssh` |

Все три интерфейса — одна реализация (`AndroidSshKeyring`), повёрнутая разными сторонами. Все
операции закрытым ключом, из какой бы двери они ни пришли, проходят одну точку: промпт,
Keystore, журнал.

## 2. `SshAgent` — «все методы интерфейса ssh»

Протокол — `draft-ietf-sshm-ssh-agent` (бывший `draft-miller-ssh-agent`), то, что говорят
`ssh-add`, `ssh` и `ssh-keygen` с `SSH_AUTH_SOCK`.

| Сообщение (номер) | Что шлёт OpenSSH | Метод | v1 |
|---|---|---|---|
| `REQUEST_IDENTITIES` (11) → `IDENTITIES_ANSWER` (12) | `ssh-add -l`, `ssh-add -L`, `ssh` перед входом | `identities()` | да |
| `SIGN_REQUEST` (13) → `SIGN_RESPONSE` (14); флаги 2 = `rsa-sha2-256`, 4 = `rsa-sha2-512` | вход по SSH; `ssh-keygen -Y sign -f key.pub` через агента | `sign(blob, data, flags)` | да, с промптом. Флаг 0 для RSA (SHA-1) → `FAILURE` |
| `ADD_IDENTITY` (17) | `ssh-add key` | `add(file, passphrase, constraints = ∅)` | да, это и есть импорт |
| `ADD_ID_CONSTRAINED` (25): 1 = lifetime, 2 = confirm, 255 = extension | `ssh-add -t N`, `-c`, `-h host` | `add(…, constraints)` | `-c` у нас всегда: каждое использование подтверждается. `-t N` делает временный ключ: `setKeyValidityEnd` и удаление записи через N. `-h` принимаем и храним, применяем вместе с `session-bind` (§6.6) |
| `REMOVE_IDENTITY` (18) | `ssh-add -d key` | `remove(blob)` | да, с подтверждением (D148) |
| `REMOVE_ALL_IDENTITIES` (19) | `ssh-add -D` | `removeAll()` | да, с подтверждением |
| `LOCK` (22) / `UNLOCK` (23) | `ssh-add -x` / `ssh-add -X` | `lock(pass)` / `unlock(pass)` | да. Пока заперто, всё кроме `UNLOCK` → `FAILURE`. Хранится только хеш пароля, только в памяти |
| `ADD_SMARTCARD_KEY` (20), `REMOVE_SMARTCARD_KEY` (21), `ADD_SMARTCARD_KEY_CONSTRAINED` (26) | `ssh-add -s/-e` (PKCS#11) | — | `FAILURE` |
| `EXTENSION` (27): `query`, `session-bind@openssh.com` | `ssh` 8.9+ шлёт `session-bind` при каждом входе | `extension(name, body)` | `query` отвечает списком. `session-bind` принимаем и запоминаем на соединение. Неизвестное → `EXTENSION_FAILURE` (28) |

```kotlin
/**
 * ssh-agent, one method per message (draft-ietf-sshm-ssh-agent). Every private-key operation
 * still goes through the prompt: "confirm" is not a constraint here, it is the only mode.
 */
public interface SshAgent {
    public suspend fun identities(): List<AgentIdentity>
    public suspend fun sign(publicBlob: ByteArray, data: ByteArray, flags: Int): SshOutcome<ByteArray>
    /** [file] and [passphrase] are zeroed before this returns, whatever it returns. */
    public suspend fun add(file: ByteArray, passphrase: CharArray?, constraints: Set<AgentConstraint>): ImportOutcome
    public suspend fun remove(publicBlob: ByteArray): SshOutcome<Unit>
    public suspend fun removeAll(): SshOutcome<Unit>
    public suspend fun lock(passphrase: CharArray): Boolean
    public suspend fun unlock(passphrase: CharArray): Boolean
    public suspend fun extension(name: String, body: ByteArray): SshOutcome<ByteArray>
}

public data class AgentIdentity(val publicBlob: ByteArray, val comment: String)

public sealed interface AgentConstraint {
    public data class Lifetime(val seconds: Long) : AgentConstraint
    public data object Confirm : AgentConstraint
    public data class Extension(val name: String, val body: ByteArray) : AgentConstraint
}
```

**(impl, шаг 3)** `ADD_IDENTITY` приносит ключ уже расшифрованным — пароля в протоколе нет, а
хранить ключ без пароля запрещает D167. Поэтому ключ, добавленный через агента, живёт только в
памяти агента, как в OpenSSH `ssh-agent`, до `ssh-add -d/-D`, `-t` или конца процесса; постоянные
ключи импортируются в Settings под паролем. `ssh-add -D` снимает ключи агента и не трогает ключи
кольца: каждый из них удаляется своим подтверждением (D148). Проверено настоящим `ssh-add`
(`AgentInteropTest`): `-l/-L`, добавление с `SSH_ASKPASS`, `ssh-keygen -Y sign` через агента для
Ed25519 из кольца и RSA из памяти, `-x/-X`, `-d`, `-t 60 -c`, `-D`.

Байтовый слой отделён от этого интерфейса: `AgentCodec` (разбор и сборка сообщений) и
`AgentServer.handle(request: ByteArray): ByteArray` в commonMain, без транспорта. Транспорт
подключается снаружи:

- в тестах — UNIX-сокет JDK 16+ (`UnixDomainSocketAddress`). Через него настоящие `ssh-add` и
  `ssh-keygen` говорят с нашим агентом (03 §4). Это и есть доказательство «работает так же,
  как в ssh»;
- позже — встроенный SSH-клиент (вопрос 76), мост в Termux, клиент `SSH_AUTH_SOCK` на desktop
  (вопрос 77).

Кроме агента, у `ssh` есть и другие команды. Их соответствие:

| Команда | Где у нас |
|---|---|
| `ssh-keygen -l -f key` (отпечаток) | `SshPublicKey.fingerprint`, чистая функция |
| `ssh-keygen -y -f key` (открытый из закрытого) | `SshKeyAdmin.inspect(file)`. Для зашифрованного файла пароль не нужен: открытая часть в заголовке `openssh-key-v1` не зашифрована |
| `ssh-keygen -Y sign -n ns` | `SshKeyring.sign(key, ns, message)` |
| `ssh-keygen -Y verify` / `check-novalidate` / `find-principals` | `SshSig.verify`, `SshSig.signer`; `find-principals` — вместе с доверенными ключами (§6.4) |
| `ssh-keygen -t … ` (создать) | `SshKeyAdmin.generate(…)` (вопрос 74) |
| `ssh-keygen -p` (сменить пароль) | не нужен: после импорта пароль не хранится (кроме опции 01 §5.4) |
| `age -R key.pub` | `Age.encrypt(plaintext, recipients)`, чистая функция (переезжает из 2FA) |
| `age -d -i key` | `SshKeyring.decrypt(file)` |

## 3. `SshKeyring` — что получают приложения

```kotlin
public interface SshKeyring {

    /** Public halves of the keys this app holds a grant for. Nothing else is listed. */
    public val keys: StateFlow<List<SshKeyInfo>>

    /**
     * The person picks one of their keys for [purpose] in the host's sheet and confirms with the
     * device. From then on this app holds a grant for that key and that purpose.
     */
    public suspend fun choose(purpose: KeyPurpose): SshOutcome<SshKeyInfo>

    /**
     * `ssh-keygen -Y sign -n <namespace>`: an armored SSH SIGNATURE over [message]. Asks for the
     * grant first if this app has none for [key] and [namespace], then for the device, per the
     * key's policy.
     */
    public suspend fun sign(key: KeyId, namespace: String, message: ByteArray): SshOutcome<SshSignature>

    /**
     * `age -d -i <key>`: the plaintext of [file], binary or armored. The key is whichever of the
     * person's keys one of the file's stanzas is for; the grant is asked for when this app has
     * none for it.
     */
    public suspend fun decrypt(file: ByteArray): SshOutcome<ByteArray>

    public companion object {
        public val Key: ServiceKey<SshKeyring> = ServiceKey("ssh-keyring")
    }
}

public sealed interface KeyPurpose {
    public data class Sign(val namespace: String) : KeyPurpose
    public data object Decrypt : KeyPurpose
}
```

### 3.1. Почему у приложений нет «подписать эти байты»

При входе по SSH клиент подписывает структуру RFC 4252 §7:

```
string session_identifier ‖ byte 50 ‖ string user ‖ string "ssh-connection" ‖ string "publickey" ‖ …
```

Если бы у приложения был метод «подписать произвольные байты», подменённая сборка или скрипт
(idea/04, idea/05) мог бы передать ему как раз такую структуру для сессии, которую
злоумышленник открыл с `github.com`. Тогда он вошёл бы на GitHub вашим ключом, и лист показал
бы только «Приложение X просит подпись» (T6).

SSHSIG подписывает другое:

```
"SSHSIG" ‖ string namespace ‖ string reserved ‖ string hash_alg ‖ string H(message)
```

Начало `"SSHSIG"` — 6 байт. Структура входа начинается с `uint32` длины идентификатора сессии,
то есть с `00 00 00 20` у sha256. Одно другим стать не может. Это доменное разделение, и
именно ради него OpenSSH ввёл SSHSIG вместо подписи сырых данных. Сырая подпись есть только у
`SshAgent`, и в v1 его не получает никто.

### 3.2. Пространства имён (namespace)

| Namespace | Кому | Условие |
|---|---|---|
| `<appId>@superizer` | самому приложению | разрешения достаточно |
| `git`, `file`, `email` и другие общеизвестные | приложению, которому человек дал **именно этот** namespace | в листе разрешения отдельная строка, что это значит: «подписи коммитов git от вашего имени» |
| `*@m42.cx` | только хосту (вход на сервер §6.3, активации §6.4) | приложению — `NotAllowed` всегда |
| произвольный | как общеизвестный | формат `[A-Za-z0-9@._-]{1,64}`, иначе `NotAllowed` |

## 4. Чистые функции открытого ключа (android, desktop, wasmJs)

Всё, что не требует закрытой половины. Здесь нет сервиса, промпта и разрешений, и всё работает
в браузере так же, как на телефоне. Экспорт 2FA в веб-сборке уже сейчас шифрует age на ключи
GitHub, и это не должно сломаться.

```kotlin
public class SshPublicKey {                           // was twofa's SshKey, plus ECDSA types
    public val type: String                           // "ssh-ed25519", "ecdsa-sha2-nistp256", …
    public val blob: ByteArray
    public val fingerprint: String                    // "SHA256:…", as ssh-keygen -l prints it
    public val label: String                          // "ED25519", "RSA 3072", "ECDSA-SK"
    public fun authorizedKeysLine(comment: String = ""): String
    public companion object {
        public fun parse(line: String): SshPublicKey?
        public fun parseAll(text: String): List<SshPublicKey>   // authorized_keys, github.com/<login>.keys
    }
}

public object SshSig {
    public fun verify(signature: String, message: ByteArray, namespace: String, signer: SshPublicKey): SigCheck
    public fun signer(signature: String): SshPublicKey?     // ssh-keygen -Y check-novalidate
}

public object Age {
    public fun encrypt(plaintext: ByteArray, recipients: List<SshPublicKey>): ByteArray
    public fun armor(file: ByteArray): String
    public fun supports(key: SshPublicKey): Boolean
}
```

Проверка ECDSA в commonMain потребовала бы писать арифметику кривых руками. На android и
desktop её делает JCA через `expect/actual`, а на wasm `SigCheck.Unsupported`. Ed25519 и RSA
проверяются в commonMain.

## 5. `SshKeyAdmin` — то, что рисует секция Settings

```kotlin
public interface SshKeyAdmin {
    public val keys: StateFlow<List<SshKeyInfo>>
    public val device: KeyringDevice      // platform, strongBox, secureEnclave, curve25519InKeystore, screenLockSet, sdk, levels (05)

    /** What [file] is, before any passphrase: type, fingerprint, encrypted?, where it would live — or why not. */
    public fun inspect(file: ByteArray): Inspection   // + encrypted: Boolean; the wizard refuses encrypted = false (D167)

    /**
     * (05) Re-encrypts the stored `openssh-key-v1` under [newPassphrase] — `ssh-keygen -p`. Level 3
     * only; the "Set passphrase" step of the wizard and "Change passphrase" on the key card.
     * [newPassphrase] is zeroed before this returns; shorter than MIN_PASSPHRASE is refused.
     */
    public suspend fun rekey(id: KeyId, newPassphrase: CharArray): SshOutcome<Unit>

    /** [file] and [passphrase] are zeroed before this returns, whatever it returns. */
    public suspend fun import(file: ByteArray, passphrase: CharArray?, options: ImportOptions): ImportOutcome
    public suspend fun generate(kind: GeneratedKind, options: ImportOptions): ImportOutcome

    public suspend fun rename(id: KeyId, comment: String)
    /** Asks the device first (D148). Deletes the record and every Keystore alias under the key's prefix. */
    public suspend fun delete(id: KeyId): SshOutcome<Unit>

    public fun grants(id: KeyId): StateFlow<List<Grant>>
    public suspend fun revoke(grant: Grant)
    public fun journal(id: KeyId): StateFlow<List<UseRecord>>

    /** ssh-add -x without a passphrase: forget every open session now. */
    public fun lockAll()
}

public data class ImportOptions(
    val policy: UsePolicy = UsePolicy.EveryUse,
    val comment: String? = null,
    /** Level 2 only (01 §5.4): keep the file's own passphrase as a second lock on every use. Level 3 has no other lock, so it is ignored there (05). */
    val keepPassphrase: Boolean = false,
)

public data class SshKeyInfo(
    val id: KeyId,                        // the SHA-256 fingerprint: a second import of the same key is "already here"
    val publicKey: SshPublicKey,
    val comment: String,
    val storage: KeyStorage,              // (05) StrongBox | Tee | SecureEnclave | SealedByChip(under) | SealedByPassphrase — no Plain: a key without a passphrase is never accepted
    val policy: UsePolicy,
    val origin: KeyOrigin,                // Imported, Generated(attested)
    val can: Set<KeyCapability>,          // Sign, DecryptAge
    val createdAt: Long,
    val usable: Boolean,                  // false once the screen lock was removed (01 §5.7)
)

public sealed interface UsePolicy {
    public data object EveryUse : UsePolicy
    public data class Session(val seconds: Int) : UsePolicy
}
```

Секция в Settings — три части.

1. **Список.** Иконка типа, комментарий, `ED25519 SHA256:nThb…`, значок места хранения
   («чип» / «TEE» / «зашифрован»). Для непригодного ключа — предупреждение.
2. **Карточка ключа.** Открытый ключ: «Скопировать», QR, «Поделиться». Место хранения и
   политика — словами из 01 §4.3 и §5.3. Разрешения с кнопкой «Отозвать». Журнал: последние
   использования. «Удалить».
3. **«Добавить ключ».** Источник: файл, QR, вставка (вопрос 75), «Создать» (вопрос 74). Затем
   `inspect`: тип, отпечаток и где будет жить, ещё до пароля. Затем пароль, если файл
   зашифрован, и политика. Затем «Добавить», и системный лист для конверта. Весь мастер под
   `FLAG_SECURE`. Поле пароля — `KeyboardType.Password`, `autoCorrectEnabled = false`.
   (05 §2.4) Рядом с полем — счётчик символов. Файл без пароля или пароль короче 12 символов
   → шаг «Задать пароль»: два поля, счётчик, кнопка активна от 12; результат — новый
   `openssh-key-v1`, старый файл и пароль обнулены; подсказка «копия на компьютере осталась
   со старым паролем». Тот же диалог — «Сменить пароль» в карточке ключа уровня 3.

Источник «QR» дают снаружи. Секция ключей (07: в `composeApp`) не знает о `services/qr-scanner` Unitool: хост
передаёт список источников, `SshKeysSection(admin, sources = listOf(FileSource, PasteSource,
TextSource("QR") { qr.scan() }))`. Для QR секция показывает команду для компьютера, как
экспорт 2FA показывает свою:

```
qrencode -t ansiutf8 < ~/.ssh/id_ed25519; read _; clear
```

и предупреждает, что QR ключа без пароля — это ключ на экране.

**(impl, шаг 6)** Как это вошло в код (`composeApp/…/settings/`):

- Источники — не список `sources`, а `HostTools(files, qr, sharer)`: те же объекты, что хост
  регистрирует для приложений как сервисы. В Superizer нет способа достать сервис хоста из его
  же экрана, поэтому платформа отдаёт их экранам напрямую. Строки «Файл» и «QR» есть только там,
  где есть сервис (D19).
- Экраны берут не `SshKeyringHost`, а узкий `KeysUi` (admin, якорь секретов, «запечатывать ли на
  ключ», браузер ли это). Так скриншоты рисуют три уровня на desktop через фейк.
- Мастер держит свою копию байтов файла и отдаёт в `import` копию: `import` обнуляет вход при
  любом исходе, а после неверного пароля файл выбирать заново нельзя. Копия обнуляется при уходе
  с экрана. Длину пароля мастер проверяет до вызова: при длине < 12 сначала шаг «Задать пароль»,
  потом один `import` с `newPassphrase`. `NeedsNewPassphrase` остаётся запасным ответом.
- В карточке нет QR открытого ключа: кодировщика QR в проекте нет, есть «Скопировать» и
  «Поделиться». Переименования в интерфейсе нет: имя задаётся при «Создать ключ», при импорте
  берётся комментарий файла. `rename` в `SshKeyAdmin` остаётся.
- Строка уровня в списке, карточке и мастере — `KeyFindings` в `services/ssh`: одно место, где
  `KeyStorage` становится кодом каталога. Диагностика кольца зовёт его же.

## 6. Как Superizer пользуется ключом: шифровать, подключаться, проверять, подписывать

### 6.1. 2FA: импорт своего экспорта — первый потребитель (вопрос 79)

Сейчас 2FA экспортирует age на ключи GitHub, а импортировать может только QR из терминала
(`age -d … | qrencode`). Файл на телефоне открыть нечем. С кольцом:

```
2FA «Импорт из файла» → FileSharer/SAF → bytes
   → runtime.service(SshKeyring.Key)?.decrypt(bytes)
   → лист: «Расшифровать файл ключом id_ed25519 · Приложение 2FA»
   → otpauth-строки → Import.read(…) → тот же экран «проверить и добавить»
```

Кнопка видна, только если сервис есть, а с 05 он есть везде. Это закрывает пункт «No import
of the export» из памяти 2FA, и это самый короткий путь проверить `decrypt` целиком на
настоящем файле. На iOS для этого нужен импортированный Ed25519 или RSA (уровень 2):
созданный P-256 age не открывает.

### 6.2. Бандл конфигов (superizer/16) на свой ключ

`app_config_bundle` содержит учётные данные серверов. Экспорт шифруется `Age.encrypt(bundle,
мои ключи)`, импорт на другом устройстве — через `decrypt`. Для этого не нужно ничего нового,
только два вызова в 16 §5.

### 6.3. Вход на сервер (idea/01): ключ вместо пароля и токена

```
POST /bind/challenge                  → { nonce, serverId }
host: keyring.sign(key, "superizer-auth@m42.cx", nonce ‖ serverId ‖ deviceId)   // namespace хоста
POST /bind/verify { publicKey, signature }
server: SshSig.verify(…) и publicKey ∈ ключи учётки (например github.com/<login>.keys)
```

Это операция хоста, а не приложения: namespace `*@m42.cx` приложениям не выдаётся (§3.2). Для
ключа, созданного в StrongBox (01 §4.4), сервер может потребовать аппаратную аттестацию:
«этот вход подписан ключом, который не покидал чип этого телефона». Проектировать это надо в
idea/01, здесь только шов.

### 6.4. Проверка: подписанные активации и документы (вопрос 78)

QR активации или документ сборки (idea/04) несёт SSHSIG ключа администратора с namespace
`superizer-activation@m42.cx`. Хост проверяет подпись по списку «Доверенные ключи» в той же
секции. Формат списка — `allowed_signers` OpenSSH: `principal namespaces="…" ssh-ed25519
AAAA…`. Это прямо закрывает угрозу «подложный конфиг» из idea/01 §12. Нужна только открытая
половина, а значит, работает и на web. Отдельная заметка.

### 6.5. Подпись git и файлов

Namespace `git` и `file`. Результат проверяет настоящий `git verify-commit` с
`gpg.format=ssh`. Кирпичу «git» (если он появится) достаточно `SshKeyring.sign`.

### 6.6. SSH-подключение (вопрос 76)

Встроенному SSH-клиенту нужна сырая подпись, то есть `SshAgent.sign`. Защищать его надо так
же, как OpenSSH защищает переброску агента:

- `session-bind@openssh.com` связывает запрос подписи с ключом хоста сервера;
- ограничения `restrict-destination-v00@openssh.com` из `ssh-add -h` разрешают ключу только
  заданные хосты;
- лист показывает `user@host` и отпечаток ключа хоста, разобранные из структуры RFC 4252.

Не v1.

### 6.7. Сейф хоста на ssh-ключе и `runtime.secrets` (05 §3, §3.2)

Контракт 3. Приложение получает второе хранилище с тем же интерфейсом:

```kotlin
public val storage: StorageService   // открытые данные, как было
public val secrets: StorageService   // значения хост запечатывает сам; prefs-ключ `secret.<appId>.<key>`
```

Хост запечатывает тем сейфом, что есть на платформе: `AndroidKeystoreVault` и
`IosKeychainVault` (без аутентификации, как сегодня) или, где чипа нет, `AgeVault(keyring,
keyId)`: `seal = Age.encrypt(value, [открытая половина ключа])` без промпта, `open` — пароль
один раз за сессию хоста. **D176 (06 §8):** сейф кэширует *расшифрованные секреты*, а не ключ:
после `open` ключ стирается из памяти, и любая операция кольца спрашивает пароль снова. Чем
защищены секреты, приложение узнаёт из `runtime.diagnostics` (06 §1), и хост показывает
«секреты не защищены: добавьте ssh-ключ», пока ключа нет. `SecretVault` из контракта приложений уходит:
`services/vault` Unitool остаётся реализацией для хоста; `SecretVault.Key` регистрируется ещё
один релиз, только чтобы 2FA перенёс `accounts.sealed` в `secrets` (04 §5).

Какой ключ запечатывает секреты, выбирает хост: настройка «ключ для секретов», по умолчанию
первый пригодный (Ed25519 или RSA: age не знает ECDSA). **Удалить этот ключ нельзя, пока
секреты не перезапечатаны другим** (D148 плюс проверка): иначе секреты всех приложений
становятся нечитаемыми.

### 6.8. Полный бэкап и восстановление (05 §3.1, §3.3, §3.4)

`HostBackup` в `superizer:host`. Бэкап = обход префиксов `app.<id>.` и `secret.<id>.` без
чтения значений, секреты распечатаны сейфом хоста; плюс настройки хоста и список отпечатков
ключей как подсказка. **Закрытых ключей в бэкапе нет** (D170). Бандл → `Age.encrypt` на
`github.com/<login>.keys` → `FileSharer`. Под подтверждением (D148, D169), в журнале.

Приложение участвует только манифестом: `backup: BackupPolicy = All | None | Except(keys)`,
контракт 3. Восстановление — замена по приложению под подтверждением с перечнем; секреты
запечатываются заново сейфом нового устройства; приложение, которого ещё нет, ждёт под своим
префиксом. Слияние — не в v1 (вопрос 86).

**(impl, шаг 7)** Как это вошло в код:

- `services/backup`: `AgeBackupCipher` (age на строки `authorized_keys`, armor — файл идёт через
  любой share sheet как текст; «ни на один ключ нельзя» — `IllegalArgumentException`, библиотека
  читает это как `NoRecipients`) и `GitHubKeys`. Копия `GitHubKeys` из экспорта 2FA, а не общий
  код: приложение не может зависеть от модуля хоста.
- Расшифровка — `SshKeyringHost.openForHost`: `decryptForHost` с исходом, чтобы «отказался» и
  «нет вашего ключа» различались; журнал пишет `Decrypt` без приложения.
- Сеть для ключей GitHub экраны хоста берут из `HostTools.network`: `buildUnitool` сам создаёт
  `KtorNetworkService` и отдаёт его и библиотеке, и экранам (иначе хосту не достать сервис,
  который получают приложения). Отсюда `ktor-client-core` в `composeApp` — только API.
- На desktop «поделиться» — диалог «Сохранить как», в браузере — скачивание (`services/share`).
  Экспорт 2FA там тоже заработал.
- Экран «Резервная копия»: что войдёт, логин GitHub → ключи (ECDSA и короткий RSA показаны, но не
  берутся) → «Сделать копию» → share sheet; ниже «Восстановить»: файл → перечень приложений с
  переключателями и «И настройки хоста» → «Восстановить» → сколько восстановлено и какие ключи
  импортировать заново. Действие каталога MakeBackup ведёт сюда.
- 2FA: «Импорт из файла» на странице добавления (02 §6.1) — `FilePicker` + `SshKeyring.decrypt`;
  файл, уже открытый на компьютере (строки `otpauth://`), читается без ключа.
- Кнопок «Подписать» / «Проверить» в тестовом приложении нет: оно в библиотеке, а она после
  шага 1 заморожена (07 §4). `sign` проверен тестами кольца и интеропом с `ssh-keygen`.

## 7. Разрешения и журнал

```kotlin
public data class Grant(
    val app: AppId,
    val key: KeyId,
    val purpose: KeyPurpose,
    /** The brick behind a composed screen (idea/04); a changed brick is a different grantee. */
    val brick: String?,
    val grantedAt: Long,
)

public data class UseRecord(
    val at: Long,
    val app: AppId,
    val key: KeyId,
    val operation: Operation,             // Sign, Decrypt, AgentSign, Import, Delete
    val namespace: String?,
    /** First 8 hex of SHA-256 of what was signed or decrypted — to recognise it, not to recover it. */
    val digest: String?,
    val outcome: String,                  // Ok, Cancelled, Refused, LockedOut, …
)
```

Правила:

- **Разрешение создаёт только человек**, в листе `KeyPromptActivity`. Ни `AppConfig`, ни QR
  активации, ни документ сервера, ни пуш, ни deep link разрешения не создают и не несут. Это
  та же мысль, что D25 «пуш ничего не открывает».
- Разрешение — на (приложение, ключ, цель). Для подписи цель включает namespace. Разрешение
  «подписывать для 2FA» не даёт «подписывать для git».
- Экран сборки из idea/04 имеет производный `AppId`, стабильный между версиями документа. Если
  сервер оставил id прежним, но подставил под экран другой кирпич, разрешение не
  наследуется: в нём записан `brick`.
- Разрешения и журнал хранятся в отдельном файле кольца `cx.m42.superizer.ssh` (03 §2, D159).
  В журнале нет ни данных, ни подписи, только первые 8 hex хеша. Кольцевой буфер на 200
  записей.
- Удаление ключа удаляет его разрешения. Удаление приложения из сборки удаляет разрешения этого
  приложения.

## 8. Форматы импорта

**Принимаем** `openssh-key-v1`: всё, что пишет `ssh-keygen` начиная с OpenSSH 7.8.

```
"openssh-key-v1\0"
string ciphername   none | aes256-ctr (по умолчанию) | aes128-ctr | aes192-ctr | aes256-gcm@openssh.com
string kdfname      none | bcrypt
string kdfoptions   string salt(16), uint32 rounds (24 по умолчанию в OpenSSH 9.6 — проверено здесь)
uint32 N = 1
string publickey    ← открыт: inspect() показывает тип и отпечаток до пароля
string private      ← зашифрован: uint32 check, uint32 check, key fields, string comment, padding 1,2,3…
```

Совпадение `checkint1 == checkint2` означает, что пароль верный. Иначе — `WrongPassphrase`, и
ничего не записано. После расшифровки открытый ключ внутри обязан совпасть с открытым в
заголовке. Иначе файл правили руками, и `Corrupted`.

**Отказываем с командой, которая чинит:**

| Что пришло | Ответ |
|---|---|
| `-----BEGIN RSA PRIVATE KEY-----`, `EC PRIVATE KEY`, `PRIVATE KEY`, `ENCRYPTED PRIVATE KEY` (PEM / PKCS#8) | «Старый формат. На компьютере: `ssh-keygen -p -f ~/.ssh/id_rsa` перепишет его в новый, пароль можно оставить прежним» |
| `PuTTY-User-Key-File-…` | `puttygen key.ppk -O private-openssh-new -o id_key` |
| шифр `chacha20-poly1305@openssh.com`, `aes*-cbc`, `3des-cbc` | `ssh-keygen -p -Z aes256-ctr -f key` |
| `ssh-ed25519 AAAA…` (открытый ключ) | «Это открытый ключ (`.pub`). Нужен файл без `.pub`» |
| `sk-ssh-ed25519@openssh.com`, `sk-ecdsa-…` | «Ключ живёт на аппаратном токене; в файле только ссылка на него» (01 §7) |
| `ssh-dss` | «DSA отключён в OpenSSH 7.0» |
| RSA < 2048 | «Слишком короткий; `ssh-keygen -t ed25519`» |
| тот же отпечаток уже есть | «Этот ключ уже добавлен» и переход к его карточке |

## 9. Типы для секретов

- Вход с закрытым материалом — только `ByteArray` (файл) и `CharArray` (пароль). `String` нельзя:
  его не обнулить, и он остаётся в куче до сборки мусора. Каждый публичный метод, который
  принимает такой вход, обнуляет его в `finally`. Это записано в KDoc и проверено тестом (03,
  I6).
- Внутри модуля закрытый материал живёт в `internal class SecretBytes : AutoCloseable`:
  `close()` обнуляет, `toString()` = `"SecretBytes(redacted)"`, `equals` по ссылке. Он не
  `@Serializable` и не `data class`, поэтому сгенерированные `toString` и `copy` его не
  выдадут.
- Исключения модуля никогда не включают входные данные в `message`: ни байты, ни пароль, ни
  имя файла с путём.
- (05) На wasm `SecretBytes.close()` обнуляет `ByteArray`, но копии в куче JS никто не
  обещает. Это записано в KDoc и в надписи «в браузере».

## 10. Где будет криптография

(07) `unitool/services/ssh` commonMain, всё `internal`, кроме §4, которое становится `public`
API этого модуля; в библиотеке — только интерфейсы и типы:

| Файл | Откуда |
|---|---|
| `wire/SshWire.kt` — чтение и запись `string`, `mpint`, `uint32` | `WireReader` из 2FA `SshKey.kt` плюс писатель |
| `key/SshPublicKey.kt` | 2FA `SshKey.kt`, ECDSA перестаёт быть `Other` |
| `key/OpenSshKeyFile.kt` — разбор и расшифровка `openssh-key-v1` | новое |
| `crypto/Bcrypt.kt` — Blowfish, `bcrypt_pbkdf` | новое, по `openbsd/lib/libutil/bcrypt_pbkdf.c` |
| `crypto/Aes.kt` — AES-128/192/256, CTR, GCM | новое; только для файлов ключей, один раз при импорте |
| `crypto/Ed25519.kt` — подпись и проверка | новое, порт TweetNaCl на ту же арифметику поля, что уже есть в `Curve25519.kt` |
| `crypto/Curve25519.kt` — X25519 | 2FA |
| `crypto/ChaCha20Poly1305.kt` | 2FA |
| `crypto/Rsa.kt` — OAEP шифрование, **снятие** OAEP, проверка PKCS#1 v1.5 | 2FA `RsaOaep.kt` плюс новое |
| `crypto/Sha.kt`, `Hmac.kt`, `Hkdf.kt` | копия из 2FA `Crypto.kt` |
| `age/Age.kt` — шифрование и **расшифровка** | 2FA плюс новое |
| `sig/SshSig.kt` | новое |
| `agent/AgentCodec.kt`, `agent/AgentServer.kt` | новое |
| `backend/PassphraseBackend.kt` — уровень 3, все платформы (05) | новое: `OpenSshKeyFile` + `SecretBytes` + сессия |
| `vault/AgeVault.kt` — сейф хоста на ssh-ключе (05) | новое, поверх `Age` и `SshKeyring.decrypt` |
| `iosMain/SecureEnclaveBackend.kt` (05) | новое; CoreFoundation-обёртки из `IosKeychainVault` Unitool переезжают сюда |

2FA после переезда зависит от `services/ssh` ради `SshPublicKey` и `Age` (07). `Crypto.kt` с HMAC
остаётся в 2FA для TOTP: делать SHA и HMAC публичным API библиотеки ради одного приложения —
лишнее обязательство. Общий модуль `superizer:crypto` — если появится третий потребитель.

## 11. Решения

- **D150.** Всё, что делается открытым ключом, — чистые функции модуля `ssh` на всех трёх
  платформах: разбор, отпечаток, `SshSig.verify`, `Age.encrypt`. Сервис нужен только для
  закрытой половины.
- **D151.** Три интерфейса над одной реализацией: `SshKeyring` для приложений, `SshAgent`
  (протокол ssh-agent один в один) и `SshKeyAdmin` для хоста. Последние два в реестр сервисов
  не попадают. Совместимость `SshAgent` с OpenSSH доказывает настоящий `ssh-add`.
- **D152.** Приложения подписывают только SSHSIG с namespace: `<appId>@superizer` или
  явно разрешённый человеком. `*@m42.cx` зарезервирован за хостом. Сырой подписи у
  приложений нет.
- **D153.** Разрешение — на (приложение, ключ, цель[, кирпич]). Создаёт его только человек в
  системном листе. Отзывается в карточке ключа. Журнал хранит, кто, когда, что и хеш, но не
  данные.
- **D154.** Импортируем `openssh-key-v1` (без шифра, `aes*-ctr`, `aes256-gcm`, KDF bcrypt).
  Остальное — отказ с командой, которая переводит файл в этот формат. Типы: ed25519, rsa ≥
  2048, ecdsa p256/384/521.
- **D155.** Секретные входы — `ByteArray` и `CharArray`, обнуляются в `finally`. Внутри —
  `SecretBytes` без сериализации и с `toString` = `redacted`. Исключения не несут входных
  данных.
- **D156** (07). age, X25519, ChaCha20-Poly1305, RSA и разбор SSH-ключей переезжают из 2FA в
  `unitool/services/ssh`.
  SHA и HMAC копируются: в 2FA остаются для TOTP. Криптопримитивы `internal`. ECDSA проверяет
  JCA через `expect/actual`, на wasm это `Unsupported`.
- **D171, D172** (05). `runtime.secrets` и `BackupPolicy` — контракт 3; сейф — дело хоста;
  бэкап — обход префиксов без знания приложений. Подробно в 05 §3.2–§3.4 и §6.7–§6.8 здесь.
- **D173** (05). Ключ, который запечатывает секреты хоста, нельзя удалить, пока секреты не
  перезапечатаны другим ключом; смена пароля уровня 3 секретов не трогает (открытая половина
  та же).
- **D176, D177** (06). `AgeVault` кэширует секреты, не ключ. `StorageDiagnostics` — сервис
  хоста контракта 3: коды и факты в библиотеке, тексты в Unitool.
