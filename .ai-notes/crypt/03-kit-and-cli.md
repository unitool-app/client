# 03 — код в kit, приложение `crypt` в CLI, тесты, шаги

> **Версия:** 1.0 · **Дата:** 2026-10-06 · Решения D303–D306

## 1. `unitool-kit` (D303)

### 1.1. На чём стоит

Перенос cli/02 §3.1 делается первым и без новой логики (cli/02 §6, шаг 1):
- `crypto/*`, `SshPublicKey`, `OpenSshKeyFile`, `SoftwareOps`, `SecretBytes`, `SshWire` и `Age`
  переходят из `services/ssh` в `kit.crypto`, `kit.ssh` и `kit.age`;
- телефон переходит на них.

С тех пор kit собирается и для `linuxX64` (D293), и в нём не хватает двух `actual`:

| `expect` | `actual` для linuxX64 |
|---|---|
| `secureRandomBytes` | `syscall(SYS_getrandom, …)`, 02 §1.1 |
| `PlatformEcdsa` | `supports() = false`. ECDSA бинарю CLI не нужна, а подписи SSHSIG от Ed25519 и RSA считаются в commonMain |

### 1.2. Что добавляется

| Пакет | Что | Строк, примерно |
|---|---|---|
| `kit.crypto` | `hkdfExtract`, `hkdfExpand` (старый `hkdfSha256` — обёртка над ними); `Hpke`: DHKEM(X25519), base mode, `seal`/`open` одного сообщения | 150 |
| `kit.ssh` | `SshDecryptionKey` — закрытая половина ключа, где бы она ни жила; `SoftwareOps.decryptionKey(PrivateKey)` | 60 |
| `kit.age` | `AgeIdentity`/`StanzaUnwrapper` строятся над `SshDecryptionKey`. Связка ключей реализует закрытый шаг один раз, для age и `ucrypt` сразу | −30 |
| `kit.crypt` | формат `ucrypt` 1.0 (01): запись, разбор, `inspect`, потоковые шифратор и дешифратор, армор | 600 |
| `kit.github` | без сети: `login(text)`, `keysUrl(login)`, разбор ответа `.keys` и классификация ключей. Копии этих правил в `apps/twofa/Export.kt` и `services/backup/GitHubKeys.kt` переходят на kit — сегодня это две копии одного, о чём говорит и их KDoc | 80 |

Kit по-прежнему без сети, Superizer, Compose и coroutines (D275). `suspend` — средство языка, а не
`kotlinx.coroutines`, и в kit его можно: так уже устроен `Age.decrypt` (cli/02 §3.1).

### 1.3. Набросок API

```kotlin
package cx.m42.unitool.kit.ssh

/** The private half of an SSH key: in memory for the CLI, in the chip for the phone's keyring. */
public interface SshDecryptionKey {
    public val publicKey: SshPublicKey
    /** X25519 of this Ed25519 key's scalar with [peer]; null when the key refuses or is not Ed25519. */
    public suspend fun x25519(peer: ByteArray): ByteArray?
    /** c^d mod n, unpadded; null when the key refuses or is not RSA. */
    public suspend fun rsaRaw(c: ByteArray): ByteArray?
}
```

```kotlin
package cx.m42.unitool.kit.crypt

public object Ucrypt {
    public const val MAJOR: Int = 1
    public const val MINOR: Int = 0
    public const val EXTENSION: String = ".ucrypt"

    /** Whether [key] can receive a file, and how strong it is (02 §4). */
    public fun recipient(key: SshPublicKey): Recipient

    /** The whole file at once: the phone, tests, stdin. */
    public fun encrypt(plaintext: ByteArray, to: List<SshPublicKey>, meta: Meta = Meta()): ByteArray

    /** Header first, then one chunk at a time: the CLI's path for files of any size. */
    public fun encryptor(plaintextSize: Long, to: List<SshPublicKey>, meta: Meta = Meta()): Encryptor

    /** Reads a header without any key. Nothing in it is verified until [Header.unlock]. */
    public fun inspect(header: ByteArray): Inspected

    public fun armor(file: ByteArray): String
    public fun dearmor(file: ByteArray): ByteArray?
}

public sealed interface Recipient {
    public val key: SshPublicKey
    public class Usable(override val key: SshPublicKey, public val securityBits: Int) : Recipient
    public class Unusable(override val key: SshPublicKey, public val reason: Unsupported) : Recipient
}
public enum class Unsupported { Ecdsa, SecurityKey, ShortRsa, Dsa, Unknown }

public sealed interface Inspected {
    public class Ok(public val header: Header) : Inspected
    public data object NotUcrypt : Inspected
    public class Newer(public val major: Int, public val minor: Int, public val missing: String?) : Inspected
    public class Damaged(public val what: String) : Inspected
}

/** Everything 01 §1 lists, plus the recipients' fingerprints; offsets and sizes as Long. */
public class Header { /* major, minor, headerSize, payloadOffset, payloadSize, plaintextSize,
                         chunkSize, chunkCount, kdf, aead, keyBits, fileId, recipients, skippedTypes */ }

/** The first of [keys] a record is for opens the file key, then the header MAC is checked. */
public suspend fun Header.unlock(keys: List<SshDecryptionKey>): Unlocked   // Ok(Decryptor, opener) | NoKey(for) | Damaged

public class Decryptor : AutoCloseable {
    public val meta: Meta
    /** Where chunk [index] lies in the file (01 §3), for reading only what is needed. */
    public fun location(index: Long): LongRange
    /** The plaintext of chunk [index], or null when it fails its tag. */
    public fun chunk(index: Long, sealed: ByteArray): ByteArray?
}
```

Чистые функции, файлов и сети нет. Читать и писать файлы — дело вызывающего: CLI делает это
своим `Io`, телефон — своим. Kit знает только байты и места.

## 2. Приложение `crypt` в CLI (D304)

Модуль `:unitool-cli:apps:crypt`, как и `apps/qr`, тонкий слой над kit. Тексты на английском
(D294).

### 2.1. Команды

| Команда | Параметры | Результат |
|---|---|---|
| `keys LOGIN` | — | в stdout — таблица ключей пользователя: номер, тип, биты ключа, стойкость, отпечаток. Неподходящие ключи — с причиной |
| `encrypt [FILE]` | `-u LOGIN` (можно несколько); `-k KEYS` — номера из `keys` или начала отпечатков `SHA256:…`, через запятую; `-R FILE` — файл `authorized_keys`, без сети; `-r "ssh-ed25519 …"` (можно несколько); `-o OUT`; `-a` — армор; `--force` | `FILE.ucrypt`. Из stdin — в stdout. Двоичный вывод в терминал запрещён, если нет `-a`, как PNG у `qr` |
| `decrypt [FILE]` | `-i KEY` (можно несколько; по умолчанию `~/.ssh/id_ed25519`, потом `~/.ssh/id_rsa`); `-o OUT` (`-` — stdout); `--force`; `--keep-mode` | файл с именем из метаданных, в той же папке. Нет имени — без `.ucrypt`. Есть только `FILE` — `FILE.out` |
| `inspect [FILE]` | `-i KEY` — проверить MAC и показать зашифрованное имя | преамбула и получатели человеческими словами (§2.4) |

Ключи телефона по проводу (usb-sign/06, D335): `-r phone:…`, `-R`/`-r` с приставками `gh:`,
`file:`, `agent`, `decrypt -i phone`. В меню выбор ключей — общий шаг CLI, где телефон стоит
рядом с GitHub. Это добавится после `crypt` 1.0 и не меняет формат.

Если ответ на вопрос 141 — «да», `decrypt` узнаёт и age: по строке `age-encryption.org/v1` или по
армору age. Тогда он открывает файл через `kit.age` с теми же ключами.

### 2.2. Меню

Начало — обычное меню каркаса: приложение, команда, вопросы по параметрам. Выбор ключей из
списка — свой шаг команды. Каркас не может задать его сам: список приходит с GitHub уже после
ответа на `-u`.

```
$ unitool
Unitool 0.1.0: what would you like to do?
  1) qr     Make a QR code from text: to the terminal, PNG or SVG
  2) crypt  Encrypt files to someone's GitHub keys, decrypt them with yours
> 2
Crypt: which command?
  1) encrypt  Encrypt a file to a GitHub user's keys
  2) decrypt  Decrypt a .ucrypt file with your SSH key
  3) keys     List a GitHub user's keys
  4) inspect  Show what a .ucrypt file says about itself
> 1
Crypt · encrypt: Encrypt a file to a GitHub user's keys
FILE (file to encrypt): report.pdf
-u LOGIN (GitHub user): octocat
Keys of octocat (github.com/octocat.keys):
  1) ED25519  256   128-bit   SHA256:Ab3dE…
  2) RSA      4096  ~140-bit  SHA256:Xy9Lm…
  -  ECDSA    256             SHA256:Qq1Rt…  ECDSA keys cannot receive files
Keys to encrypt to, numbers like 1,2 [Enter: all]: 1
report.pdf → report.pdf.ucrypt, 100 KB, for 1 key of octocat; read back and checked
The same as one command: unitool crypt encrypt report.pdf -u octocat -k SHA256:Ab3dE
```

Строку «The same as one command» каркас печатает сам. Поэтому команда записывает выбранные ключи
обратно в `-k`, и для этого каркасу нужен небольшой новый вход (§2.6). Ключи в этой строке
названы началом отпечатка, а не номером: номера в списке GitHub могут сместиться.

### 2.3. Без терминала

- `encrypt` без `-k` шифрует всем подходящим ключам — так же, как `age -R` с файлом `.keys`. В
  stderr перечисляются отпечатки и самое слабое звено.
- Пароль ключа — `io.secret()` без эха через `/dev/tty`. Без терминала он берётся из
  `UNITOOL_PASSPHRASE` (D272).
- Ключ для `decrypt` подбирается по открытой части, и пароль спрашивается только у ключа, который
  подходит к файлу. Открытая часть есть и в `openssh-key-v1` без пароля, и в `KEY.pub` рядом.

### 2.4. `inspect`

```
report.pdf.ucrypt
  format      ucrypt 1.0
  header      832 bytes (0–831); MAC not checked: no key given
  payload     100032 bytes at 832, 2 chunks of 64 KiB
  plaintext   100000 bytes
  file key    256-bit, HKDF-SHA-256, ChaCha20-Poly1305
  file id     4f1c9a02d7e83b55
  recipients  ED25519   SHA256:Ab3dE…
              RSA 4096  SHA256:Xy9Lm…
```

С `-i` добавляются строки `MAC ok` и `name report.pdf`.

### 2.5. Ошибки

Ошибка — одна строка в stderr и код 1, как у `qr`. Коды 2, 130 и 141 — по D270.

```
none of your keys opens report.pdf.ucrypt; it is for SHA256:Ab3dE… (ED25519), SHA256:Xy9Lm… (RSA 4096)
report.pdf.ucrypt is damaged or was changed: the header check failed
report.pdf.ucrypt is damaged: chunk 2 of 2 failed its check; nothing was written
report.pdf.ucrypt needs a newer unitool: it is ucrypt 2.0, this one reads 1.0
octocat has no keys a file can be encrypted to: ECDSA and security keys (sk-) cannot receive files
report.pdf exists; use --force to replace it
cannot reach github.com: Could not resolve host
```

### 2.6. Что добавляется в каркас (D305)

| Что | Зачем |
|---|---|
| `Io.secret(prompt)` | пароль ключа без эха: `termios` на `/dev/tty`, эхо возвращается в `finally`. `FakeIo` берёт его из своих ответов и ничего не пишет в `errText` |
| `Io.openRead(path)`, `Io.openWrite(path, exclusive, mode)`, `rename`, `size`, `remove` | потоковые файлы: в памяти один кусок. `.part` с правами 0600 (01 §6). `FakeIo` держит файлы в памяти, как сейчас |
| повторяемые параметры и списки | `-u`, `-r`, `-i`, `-k` |
| ответ, который команда дала сама | выбор ключей в меню (§2.2) попадает в строку «The same as one command» |
| `Http.get(url)` в `core`, `FakeHttp` в `testing` | ключи GitHub. В бинаре — по ответу на вопрос 143. Предложение: libcurl через `dlopen("libcurl.so.4")` только в момент запроса. Проверка TLS включена, таймаут 15 с, `User-Agent: unitool/<version>`. URL строится только из логина, который прошёл `kit.github.login` |
| вызов `suspend` из обычной команды | 10 строк на `startCoroutine` из stdlib. Ключи CLI лежат в памяти и не приостанавливаются, поэтому `kotlinx.coroutines` в бинарь не нужен |

Ключи берутся из `https://github.com/<login>.keys`: это текст `authorized_keys`, без лимита API в
60 запросов в час (cli/02 §3.3). Телефон продолжает ходить в API ради CORS в браузере.

## 3. Тесты (D306)

**Kit, `commonTest`** — запускается и на JVM, и на linuxX64:
- векторы:
  - RFC 9180 A.2: DHKEM(X25519), HKDF-SHA256, ChaCha20Poly1305, base mode;
  - RFC 5869 для разделённых `hkdfExtract`/`hkdfExpand`;
  - RFC 7748 и RFC 8439 уже есть в `PrimitivesTest`.
- **Эталонные файлы 1.0** в `resources/ucrypt/1.0/`, с тестовыми ключами:
  - Ed25519 без пароля и с паролем;
  - RSA-2048 и RSA-3072.
  - Запись с подменённым CSPRNG должна совпасть байт в байт, чтение — открыть всё.
- Размеры: 0, 1, 65 535, 65 536, 65 537, 131 072, 200 000 байт.
- **Подмена:**
  - каждый бит каждого байта заголовка по очереди даёт отказ, а не другой открытый текст;
  - бит в каждом куске;
  - обрезка на каждой границе кусков и посередине;
  - лишний байт в конце;
  - переставленные и повторённые куски;
  - выброшенная, переставленная или перенесённая из другого файла запись получателя.
- **Версии:**
  - 1.9 с незнакомой некритичной записью открывается;
  - незнакомая критичная запись, мажорная 2, незнакомый `aead` и поднятый `flags` дают отказ, и
    сообщение называет, чего не хватает;
  - 1.0 с незнакомым типом записи — испорченный файл.
- `location(i)` совпадает с фактическими смещениями.

**Kit, `desktopTest`:**
- независимый читатель на JCA (`JcaUcrypt`, как `JcaAge`): `XDH`, `HmacSHA256`,
  `ChaCha20-Poly1305` и `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` с `PSource.PSpecified(label)`.
  Он открывает то, что пишет kit;
- ключи от настоящего `ssh-keygen`, если он есть в `PATH`, — пропуск перечисляет `verify.sh`.

**CLI:**
- `commonTest` на `FakeIo` и `FakeHttp`:
  - меню целиком;
  - без терминала;
  - `-k` номером и отпечатком;
  - отказ перезаписать;
  - `.part` удаляется после ошибки;
  - пароль спрашивается только у подходящего ключа;
  - тексты ошибок §2.5.
- `jvmTest` `NativeBinaryTest`: бинарь шифрует — JVM открывает, и наоборот.
- `verify.sh`, шаг 2b: `encrypt -R test.pub` → `inspect` → `decrypt -i test` → `cmp`. Испорченный
  байт → код 1.

## 4. Шаги

0. **Ревью владельца** и ответы на вопросы 139–145.
1. **Перенос cli/02 §6, шаг 1**, плюс два `actual` для linuxX64 (§1.1). Это код телефона, поэтому:
   - полный `verify.sh` и проверка на эмуляторе: старые аккаунты 2FA на месте, экспорт
     открывается `age -d`, бэкап открывается;
   - в `main` (а значит, в альфу) — только по слову владельца.
2. **Kit, криптография:** `hkdfExtract`/`hkdfExpand`, `Hpke` с векторами RFC 9180, а
   `SshDecryptionKey` — под age и связку ключей. Поведение телефона не меняется, и тесты age это
   подтверждают.
3. **Kit, формат:** `kit.crypt` 1.0 со всеми тестами §3.
4. **Kit, GitHub:** `kit.github`; twofa и backup переходят на него, их копии удаляются.
5. **Каркас CLI:** `secret`, потоковые файлы, повторяемые параметры, `Http`.
6. **`apps/crypt`:** команды, меню, тесты, `NativeBinaryTest`, `verify.sh`.
7. **Заметки:** здесь «построено» и что вышло иначе; в `cli/README` — новое приложение.

**split (D372) меняет порядок:** ядро `kit.crypt` 1.0 — преамбула, записи, MAC, STREAM, армор,
метаданные — делается раньше шага 2, с первым получателем `0x0008` (части ключа, split/02 §4).
Для этого нужны только ответы 139 и 140. Шаги 2 и 4 и получатели `0x0001`/`0x0002` идут следом,
номера записей не меняются.

Шаги 2–7 не трогают видимого поведения телефона. Новая функция для телефона, «Открыть файл» в
связке ключей, — отдельная заметка после ответа на вопрос 144.
