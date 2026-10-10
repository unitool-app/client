# 03 — Общий код: `kit.ssh` и `kit.sudo` в `:unitool-kit`

> **Версия:** 1.2 · **Дата:** 2026-10-10 · Шаг 1 в 00-TASK. Зачем: хэш ключа, разбор R,
> конверт и проверка ответа должны быть **одним кодом** на телефоне и на хосте (D401, правило
> владельца из `done/cli` D293: общие сервисы — в одну либу, копий нет).

## 1. Что есть и чего не хватает

На 2026-10-10:
- `:unitool-kit` собирается для android, `jvm("desktop")`, iosArm64, iosSimulatorArm64, wasmJs и
  **linuxX64**. Зависимостей нет, это стережёт `checkDependencyRules` в корневом `build.gradle.kts`.
  В `kit.crypto` лежат SHA-1/256/384/512, `hmac`, HKDF, ChaCha20-Poly1305, X25519, Ed25519
  (подпись и проверка), RSA (в том числе `verify`), AES и bcrypt-pbkdf.
- Всё SSH-шное — `SshPublicKey`, разбор wire-формата, `SshSig.verify`, `Age.encrypt/decrypt`,
  `OpenSshKeyFile` — лежит в `:services:ssh`. У этого модуля нет `linuxX64`, и он зависит от
  `superizer.core`.
- Проверка ECDSA идёт через `expect object PlatformEcdsa` (`services/ssh/.../crypto/Platform.kt`):
  JCA на android и desktop, Security.framework на iOS, `null` в wasm. Для Linux её нет, а ключи
  P-256 из чипа телефона (уровень 1) подписывают именно ECDSA.

## 2. Переезд `kit.ssh` (D402)

Тем же способом, каким `kit.crypto` переехал из `services/ssh` для `split` (2026-10-08): файлы
переносятся `git mv`, пакет меняется, импорты правятся по всему репозиторию, поведение не меняется.

| Из `services/ssh/src/commonMain/kotlin/cx/m42/services/ssh/` | В `unitool-kit/src/commonMain/kotlin/cx/m42/unitool/kit/ssh/` | Что меняется |
|---|---|---|
| `wire/SshWire.kt` | `SshWire.kt` | `internal` → `public`, если нужно снаружи |
| `SshPublicKey.kt` | `SshPublicKey.kt` | свойство `id: KeyId` уходит. В `services/ssh` остаётся расширение `val SshPublicKey.id: KeyId get() = KeyId(fingerprint)` в `SshPublicKeyIds.kt` |
| `SshSig.kt` | `SshSig.kt` | `signedData`, `signatureBlob`, `ecdsaSignatureBlob` и `armor` становятся `public`: ими пользуется кольцо в `services/ssh`. KDoc объясняет, почему они открыты |
| `crypto/Platform.kt` + `Platform.{android,desktop,ios,wasmJs}.kt` | `kit/crypto/Ecdsa.kt` + те же actual | `PlatformEcdsa` переезжает в `kit.crypto`, добавляется actual `linuxX64` (§3) |
| `Age.kt` (с `AgeIdentity`, `StanzaUnwrapper`) | `Age.kt` | `decrypt` становится `public`. `suspend` остаётся: агенту не мешает, кольцу нужен |
| `key/OpenSshKeyFile.kt` | `OpenSshKeyFile.kt` | `ImportRefusal` (тип Superizer) заменяется своим `KeyFileRefusal` с теми же значениями. `services/ssh` переводит его в `ImportRefusal` одной функцией `toImportRefusal()` |
| из `key/SoftwareOps.kt`: `ageIdentity(PrivateKey)` | `SoftwareIdentity.kt` | только программная идентичность age (Ed25519 → X25519, RSA). Подпись и остальное из `SoftwareOps` остаются в `services/ssh` |

Проверки переезда:
- `./gradlew :services:ssh:desktopTest` даёт ровно тот же набор зелёных тестов, что до переезда.
  Список сохранить до начала и сравнить после. `SshSigAndAgeInteropTest` и `KeyringTest`
  обязательны.
- Тесты, которые проверяют только переехавший код (разбор ключей, SSHSIG, age), переезжают в
  `unitool-kit/src/commonTest/…/ssh/` и начинают гоняться ещё и в `linuxX64Test`. Тесты, которым
  нужны настоящие `ssh-keygen` и `age`, остаются в `desktopTest` своего модуля.
- `checkDependencyRules` зелёный: в `:unitool-kit` не появилось ни одной зависимости.
- `scripts/verify.sh` (весь Unitool) зелёный. Android и iOS собираются (`verify.sh` это проверяет).

Если переезд упрётся в тип Superizer, кроме `KeyId` и `ImportRefusal`, — стоп и строка в
`PROGRESS.md`. Правка Superizer в это задание не входит.

## 3. ECDSA verify на чистом Kotlin (D406)

Файл `unitool-kit/src/commonMain/kotlin/cx/m42/unitool/kit/crypto/EcdsaVerify.kt`.

- Только **проверка** подписи для `nistp256`, `nistp384`, `nistp521`. Подписи ECDSA в kit нет: секретов
  здесь нет, поэтому постоянное время не требуется. KDoc говорит это прямо.
- Арифметика поля и порядка — свой модульный тип на `IntArray`-конечностях. Образец — `Montgomery`
  в `Rsa.kt`, по возможности переиспользовать его. Точки — в якобиановых координатах, `u1·G + u2·Q`
  считается методом Шамира. Проверки:
  - `Q` лежит на кривой и не равна бесконечности;
  - `1 ≤ r, s < n`;
  - хэш обрезается до битности `n`.
- `actual object PlatformEcdsa` для `linuxX64`: `verify` → `EcdsaVerify.verify(...)`, `sign` → `null`,
  `supports(bits)` → `true` для 256, 384 и 521 (только для проверки, так и задокументировать).
- **Тесты:**
  - векторы Wycheproof `ecdsa_secp256r1_sha256_test.json`, `ecdsa_secp384r1_sha384_test.json`,
    `ecdsa_secp521r1_sha512_test.json`, сокращённые до ~200 случаев на кривую, включая все `invalid`
    с особыми `r`/`s`. Лежат в `commonTest` как Kotlin-константы; генератор
    `scripts/wycheproof-to-kotlin.sh` пишется в этом же шаге;
  - desktopTest: 1000 случайных подписей JCA → `EcdsaVerify` говорит `true`; испорченный бит → `false`;
  - SSHSIG от настоящего `ssh-keygen -Y sign` ключом P-256 проверяется через `SshSig.verify` в
    `linuxX64Test` (фикстура из 01 §9).

## 4. `kit.sudo` (D403)

Пакет `cx.m42.unitool.kit.sudo` в `:unitool-kit`, commonMain.

```kotlin
public object KeyHash {
    public const val DOMAIN: String = "unitool-sudo/v1"
    public fun of(blob: ByteArray): String                          // 01 §2
}

public object CanonicalJson {                                         // 01 §3.2
    public sealed interface Value                                     // Str, Int53, Bool, StrList, Obj
    public fun encode(obj: Map<String, Value>): ByteArray
    public fun parse(bytes: ByteArray): Map<String, Value>?          // null = не JSON или не канонический
}

public class SudoRequest private constructor(public val bytes: ByteArray, /* поля 01 §3.1 */) {
    public val digest: String                                         // hex(sha256(bytes))
    public companion object {
        public fun build(/* поля */): SudoRequest                    // агент
        public fun parse(bytes: ByteArray): SudoRequest?             // телефон, агент (свой R из файла гранта)
    }
}

public enum class Decision { Once, Ttl, Session }

public class SudoAnswer private constructor(public val bytes: ByteArray, public val req: String,
                                            public val decision: Decision, public val until: Long) {
    public companion object {
        public fun build(request: SudoRequest, decision: Decision, until: Long): SudoAnswer
        public fun parse(bytes: ByteArray): SudoAnswer?
    }
}

public object SudoEnvelope {                                          // 01 §5
    public const val NAMESPACE: String = "sudo-approve@superizer"
    public fun seal(answer: SudoAnswer, signature: String, hostKey: SshPublicKey): String
    public suspend fun open(sealed: String, hostKey: AgeIdentity): Opened?   // Opened(answerBytes, signature)
}

public sealed interface Verdict {                                     // 01 §6
    public data class Approved(val decision: Decision, val until: Long, val signer: SshPublicKey) : Verdict
    public data class Refused(val reason: String) : Verdict
    public companion object {
        public suspend fun check(request: SudoRequest, keyHashes: Set<String>, sealed: String,
                                 hostKey: AgeIdentity, allowed: List<SshPublicKey>,
                                 now: Long, maxSession: Long): Verdict
    }
}

public object SudoCode {                                              // 4 символа, 01 §3.1
    public const val ALPHABET: String = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    public fun random(): String
}
```

Подписи здесь нет: подписывает кольцо телефона (`SshKeyring.sign`). `kit.sudo` только готовит
байты A и упаковывает результат.

**Тесты** (`unitool-kit/src/commonTest/…/sudo/`, гоняются на JVM и в `linuxX64Test`):
- `KeyHashTest` — `keyhash.json`;
- `CanonicalJsonTest` — все канонические и неканонические случаи `requests.json`; круг
  `encode(parse(x)) == x`; не-ASCII; управляющие символы; `2^53` отвергается;
- `SudoRequestTest` — обязательные поля, лишнее поле, `argv` > 4096, `argvTruncated`; поле `shell` отвергается как лишнее (D413);
- `SudoEnvelopeTest` — `seal` → `open` тем же ключом; `answers.json` (запечатаны настоящим `age`)
  открываются; чужой ключ хоста → `null`;
- `VerdictTest` — каждый случай `verdicts.json`, причина отказа совпадает строкой;
- `SudoVectorsInSyncTest` (desktopTest) — сгенерированный `SudoVectors.kt` совпадает с JSON
  фикстур, а JSON — с `SHA256SUMS`.

## 5. Готово, когда

- `./gradlew :unitool-kit:desktopTest :unitool-kit:linuxX64Test :services:ssh:desktopTest` зелёные.
- `scripts/verify.sh` зелёный, `checkDependencyRules` зелёный.
- Набор тестов `services/ssh` до и после переезда совпадает (§2).
- Коммиты: (1) переезд `kit.ssh` без изменений поведения; (2) `EcdsaVerify` + actual `linuxX64`;
  (3) фикстуры и `gen.sh`; (4) `kit.sudo`.
