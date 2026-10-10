# 03 — код в kit, приложение `split` в CLI, тесты, шаги

> **Версия:** 1.2 · **Дата:** 2026-10-08 · Решения D365–D374 · 1.1: мастер, n и k от 2 до 5, адрес без умолчания, только kit и CLI · 1.2: `ucrypt` ядром первым, правила мастера в kit, сначала текст

## 1. `unitool-kit`

### 1.1. Что добавляется

| Пакет | Что | Строк, примерно |
|---|---|---|
Всё, кроме терминала, живёт здесь (D373): CLI задаёт вопросы и рисует экраны, а правила, формат и
криптография — общие с будущим телефоном.

| Пакет | Что | Строк, примерно |
|---|---|---|
| `kit.split` | `Gf256` (internal): умножение без таблиц, обратный элемент; `Shamir`: `split`/`combine`; `SplitPart`: 48 байт туда и обратно; `SplitLink`: ссылка туда и обратно; `SplitPlan`: правила мастера | 300 |
| `kit.crypt` | ядро `ucrypt` 1.0 (crypt/01 без получателей `0x0001`/`0x0002`): преамбула, записи, MAC заголовка, STREAM, армор, метаданные; получатель `0x0008`; `Ucrypt.encryptSplit`, `Ucrypt.decryptSplit` | 450 |

`kit.split` — чистый `commonMain` без зависимостей. Из переноса cli/02 §3.1 ему нужны только
`secureRandomBytes` и `SecretBytes`. Ядру `ucrypt` — ещё ChaCha20-Poly1305 и HKDF.

SSH-получатели (`Hpke`, RSA-OAEP, `SshDecryptionKey`) приходят в `kit.crypt` следом, по crypt/03,
с теми же номерами записей (D372).

### 1.2. Набросок API

```kotlin
package cx.m42.unitool.kit.split

/** One share of a split key (02 §1). [x] is 1..[n]; any [k] shares of one [setId] give the key back. */
public class SplitPart internal constructor(
    public val setId: ByteArray,
    public val k: Int,
    public val n: Int,
    public val x: Int,
    internal val share: SecretBytes,
) {
    /** The 48-byte wire form. */
    public fun bytes(): ByteArray
}

public object Shamir {
    /** Splits [secret] into [n] parts, any [k] of which give it back; 2 <= k <= n <= 255. */
    public fun split(secret: SecretBytes, k: Int, n: Int): List<SplitPart>

    /** The secret from parts of one set; throws [SplitException] on mixed sets, repeated x, too few parts. */
    public fun combine(parts: List<SplitPart>): SecretBytes
}

public object SplitLink {
    /** [base] comes from whoever generates the parts; there is no default (question 172). */
    public fun format(part: SplitPart, base: String): String

    /** Null when [base] can carry a part: absolute http(s), no '#'; otherwise why not. */
    public fun checkBase(base: String): String?

    /** The part inside a link on any domain or a bare 64-character string, or null when there is none (02 §3). */
    public fun parse(text: String): SplitPart?
}

/** The wizard's rules, shared by the CLI now and the phone later (D373). Texts stay with each UI. */
public object SplitPlan {
    public val PARTS: IntRange = 2..5                 // D369
    public const val SUGGESTED_N: Int = 3
    public const val SUGGESTED_K: Int = 2

    public fun thresholds(n: Int): IntRange = 2..n

    /** What a choice means, as numbers the UI turns into words: "any 2 of 3; 1 may be lost". */
    public fun meaning(n: Int, k: Int): Meaning

    /** Why these parts cannot open [container] yet, or null when they can: other set, same x, too few. */
    public fun check(container: SplitRecord, parts: List<SplitPart>): Problem?
}
```

```kotlin
package cx.m42.unitool.kit.crypt

/** A container whose file key is split; returned once by [Ucrypt.encryptSplit]. */
public class SplitSealed(public val container: ByteArray, public val parts: List<SplitPart>)

public object Ucrypt {
    // …
    public fun encryptSplit(plaintext: ByteArray, k: Int, n: Int, meta: Meta? = null): SplitSealed
    public fun decryptSplit(container: ByteArray, parts: List<SplitPart>): Opened
}
```

Потоковые варианты для больших файлов — по образцу обычных `encrypt`/`decrypt` из crypt/03 §1.3.

## 2. Приложение `split` в CLI (D366, D370)

Одно приложение `unitool-cli/apps/split`. В терминале `unitool split` открывает мастер (§2.1).
Под мастером — команды, их можно звать и напрямую, из скриптов и тестов:

```
unitool split                  # terminal: the wizard; otherwise help and exit code 2
unitool split make    -n N -k K --base URL [-i FILE] [-o FILE] [--qr ansi|png|svg] [--out-dir DIR]
unitool split join    [FILE] [-p PARTFILE]... [-o FILE]
unitool split inspect [FILE]
```

Все строки CLI — по-английски (D294).

### 2.1. Мастер (D370)

Общее меню каркаса (D271) задаёт вопросы по параметрам по одному и не умеет того, что тут нужно:
- пояснять, что значит выбор;
- проверять k ≤ n;
- показывать сводку перед запуском;
- выдавать части по одной на экран.

Поэтому у `split` свой мастер поверх `Io.ask`, `Io.choose`, `Io.secret` и `showScreens`. Шагов
«назад» нет: Ctrl-C отменяет, ничего не записав.

**Создать:**

```
$ unitool split
Split: keep a secret in parts. Any K of N parts open it; fewer tell nothing.
  1) make     Encrypt a secret and split its key into parts
  2) join     Put parts together and decrypt
  3) inspect  Show what a container or a part is
> 1
Step 1 of 5 · How many parts in all? (2-5) [3]: 3
Step 2 of 5 · How many parts open the secret? (2-3) [2]: 2
  Any 2 of the 3 parts open it. You can lose 1 part; losing 2 loses the secret for good.
Step 3 of 5 · Link address for the parts, e.g. https://example.com/p: https://ex.com/p
  The page at this address must not run scripts: a script there can read the part after '#'.
Step 4 of 5 · Where to save the encrypted container? [secret.ucrypt]:
Step 5 of 5 · How to hand out the parts?  1) here, one per screen  2) PNG files  3) SVG files
> 1

  2 of 3 parts · links on https://ex.com/p · container secret.ucrypt · parts on screen
Go on? [y/n]: y
Secret text (end with Ctrl-D):
…                                          ← parts, one per screen (§2.2)
Done. Keep the parts in different places. Any 2 of 3 open the secret; losing 2 loses it for good.
The same as one command: unitool split make -n 3 -k 2 --base https://ex.com/p -o secret.ucrypt
```

- Пределы и предложения — из `SplitPlan`. Шаг 2 даёт выбрать от 2 до n, пояснение под ним
  строится из `SplitPlan.meaning`.
- Шаг 3 проверяет адрес через `SplitLink.checkBase` и спрашивает снова при ошибке.
- В первой версии шифруется только текст. Шаг «что шифровать: текст или файл» добавится вместе с
  файлами (D374).
- В строке «The same as one command» нет секретов: текст и части в argv не бывают никогда.

**Собрать:** контейнер (файл; пусто — вставить армор) → части по одной без эха, пока не
наберётся k (§2.3) → куда сохранить (пусто — показать на экране).

**Посмотреть:** контейнер или часть → что это (§2.4).

### 2.2. `make`

- **Что шифровать:** текст из stdin (`-i FILE` — вместе с файлами, D374). Если stdin — терминал,
  спросить без эха до Ctrl-D:
  `Secret text (end with Ctrl-D):`. Из argv текст не берётся: иначе он попадёт в историю shell и
  в `ps` (как D272).
- **Контейнер:** в `-o FILE` с правами 0600, иначе армором в stdout.
- **Части:**
  - `--qr ansi` (по умолчанию): по одной на экран — QR, ссылка и
    `Part 2 of 3. Press Enter for the next one.` После каждой экран стирается
    (`Io.showScreens`/`CLEAR_SCREEN`).
  - `--qr png|svg --out-dir DIR`: файл на часть, `part-1-of-3.png`, права 0600.
  - Без терминала и без `--out-dir` — отказ: иначе все части ушли бы одним потоком.
  - Две части никогда не попадают в один файл или на один экран.
- **В конце:**
  > `Keep the parts in different places. Any 2 of 3 open the file; losing 2 loses it for good.`

### 2.3. `join`

- **Контейнер:** `FILE` или stdin. Армор узнаётся сам.
- **Части:**
  - `-p FILE`, по одной на файл; в файле ссылка или голая строка;
  - иначе ввод без эха, по одной строке, пока не наберётся `k` годных частей.
  - Картинки с QR CLI не читает: в kit есть только кодировщик QR.
- **Ошибки называют часть и причину и просят ещё:**
  > `part 2: from another set (a1b2c3d4…), this file needs set 9f8e7d6c…`

  > `part 3: same number as part 1`
- **Выход:** `-o FILE` с правами 0600, иначе stdout.

### 2.4. `inspect`

- Для контейнера: версия, `set_id`, `k`, `n`, размеры.
- Для части (stdin или ввод без эха): `set_id`, `k`, `n`, `x`, `check ok`. Сам `share` не
  печатается никогда.

### 2.5. Что нужно каркасу

- `io.secret()` обещан в D272 и D305, файлы с 0600 — в D305, `showScreens` уже есть.
- Приложение должно уметь перехватить «`unitool split` без команды в терминале»: сегодня это
  всегда общее меню (D271). Нужна точка входа `CliApp.wizard`: если она есть, каркас зовёт её
  вместо меню команд. Остальным приложениям ничего не меняется.

## 3. Тесты (D367)

| Что | Как |
|---|---|
| `Gf256` | умножение против табличной реализации из теста — все 65 536 пар; обратный элемент — все 255 |
| `Shamir`, сборка | для всех k ≤ n ≤ 5 каждое подмножество из k частей даёт ключ; из k−1 — `SplitException` |
| `Shamir`, стойкость | при фиксированном секрете и x отображение «коэффициент → часть» — биекция: перебор всех 256 значений даёт каждую часть ровно один раз (2 из 2 и 2 из 3) |
| независимая сборка | Лагранж, написанный отдельно в `jvmTest`, на случайных наборах |
| `SplitPart` | туда и обратно; порча каждого байта ловится `check` или проверкой полей |
| `SplitLink` | разные `base`, query, хвостовые пробелы и `\n`, голая строка, чужой фрагмент → `null`; замена каждого знака → `null` |
| контейнер | `k−1` частей → «need k»; часть другого набора → отказ до криптографии; подменённый `share` → отказ по MAC; при n > k с одной плохой частью `join` её называет |
| CLI на `FakeIo` | `make` → `join` туда и обратно; ни один файл не содержит двух частей; при `-o` и `--out-dir` в stdout нет частей; `-n 6` и `-k 1` → код 2 |
| мастер на `FakeIo` | ответы по шагам дают те же файлы, что команда из строки «The same as one command»; k > n и плохой адрес переспрашиваются; ответ `n` на сводке ничего не записывает |
| бинарь | `NativeBinaryTest`: `make` нативным бинарём, `join` на JVM и наоборот |

## 4. Шаги (D368)

Сейчас только `unitool-kit` и CLI (D371).

1. Ответы crypt 139 (имена и магия) и 140 («сколько бит»): без них не зафиксировать преамбулу.
2. Перенос cli/02 §3.1: ChaCha20-Poly1305, HKDF, `SecretBytes`, `secureRandomBytes` из
   `services/ssh` в kit. Это код телефона: полный `verify.sh`, проверка на эмуляторе, в `main`
   — только по слову владельца (как crypt/03 §4, шаг 1).
3. `kit.split`: `Shamir`, `SplitPart`, `SplitLink`, `SplitPlan` и их тесты.
4. Ядро `ucrypt` 1.0 в `kit.crypt` с получателем `0x0008`, эталонные файлы 1.0.
5. CLI `split`: команды, затем мастер и `CliApp.wizard` в каркасе, `NativeBinaryTest`.
6. Файлы (D374).
7. SSH-получатели `ucrypt` — по crypt/03, когда будут ответы 141–145.
8. Потом, отдельной заметкой (вопросы 175, 176): телефон — сканер частей, `join`, хранение
   части, App Links.
