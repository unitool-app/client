# 01 — `unitool-cli`: каркас `cli-core`, приложения `qr` и `age`, тесты, шаги

> **Версия:** 1.1 · **Статус:** на ревью у владельца · **Дата:** 2026-10-06. Решения и вопросы —
> в [README](README.md).
>
> **История:**
> - 1.0 — первая редакция.
> - 1.1 — `cli-kit` переименован в `cli-core` (D279). Логика age берётся из `unitool-kit`
>   ([02](02-unitool-kit.md)), а не из копии `libs/ssh-age` (D267 отменено).
> - 1.2 — монорепозиторий (D280). §6 и §8 переписаны: CLI — папка `unitool-cli/` в `/prj/unitool`,
>   модули `:unitool-cli:*`, kit подключается как проект.
> - 1.3 — построено (README, «Что построено»). Код отошёл от эскиза в четырёх местах:
>   - `Io.readLine()` стал `Io.ask(prompt)`: вопрос и ответ в одной строке;
>   - `Io.fail` стал функциями `fail()` и `usage()` верхнего уровня;
>   - ansi рисуется одним `▀` с явными цветами текста и фона вместо `▀▄█`, так что межстрочный зазор
>     не режет тёмные модули;
>   - меню пропускает выбор, когда приложение или команда одна.
>
>   Из §3 построен только `encode` (D288), §4 отложен (D289).
> - 1.4 — нативный бинарь (D291, D292). §6 «Место и сборка» устарело в части JVM: модули CLI —
>   Kotlin Multiplatform (`jvm` + `linuxX64`), бинарь — `:unitool-cli:app:install` →
>   `unitool-cli/app/build/dist/unitool`, `FakeIo` переехал в модуль `:unitool-cli:testing`, у `Io` нет
>   `java.io`: `readStdin`, `write`, `flush`, `env(name)`, `writeFile`. Актуальная раскладка — в
>   `unitool-cli/README.md`.

## 1. Что взято от телефонного хоста, а что нет (D263)

| Superizer | В CLI |
|---|---|
| `AppId`, `AppMetadata.title/description` | `CliApp.id`, `title`, `summary`: простые строки. `Localized` не нужен; тексты на английском (D294, в 1.0–1.5 были на русском) |
| `SuperizerApp` с `manifest`, `configSpec`, `launch` → `AppInstance` | `CliApp` со списком `Command`. Конфиг заменяют параметры команды |
| `AppRegistry`, `SuperizerBuilder.register` | `Cli(apps = listOf(...))`: регистрация явная, сканирования classpath нет, как и в Unitool |
| `ServiceKey`, `runtime.service(key)` | **нет.** Сервисы — это `Io` (§2.3) и обычные зависимости модуля |
| активации, промокоды, `hidden`, `secret`, deep links, пуши, жизненный цикл, хранилище, резервная копия, защита, контракт 1–4 | **нет** |

Если когда-нибудь понадобится «скрытая» команда (для отладки), это будет `hidden = true` в
`Command`, то есть её просто нет в справке и меню. Это не механизм безопасности.

## 2. Каркас `cli-core`

### 2.1. Модель

```kotlin
package cx.m42.unitool.cli

/** One word on the command line: `unitool qr …`. */
public abstract class CliApp(
    public val id: String,        // kebab-case, checked like Superizer's AppId
    public val title: String,     // the menu line: "QR-коды"
    public val summary: String,   // one sentence for `unitool --help`
) {
    public abstract val commands: List<Command>
    /** Runs when the second word is not a command name: `unitool qr hello` = `unitool qr encode hello`. */
    public open val default: Command? get() = null
}

public abstract class Command(public val name: String, public val summary: String) {
    /** Filled by the delegates below, in declaration order — the one source for argv, help and menu. */
    public val params: List<Param<*>>

    protected fun argument(name: String, help: String): Arg<String>
    protected fun option(vararg names: String, help: String): Opt<String?>
    protected fun flag(vararg names: String, help: String): Param<Boolean>

    public abstract fun run(io: Io): Int
}
```

Типы и ограничения цепочкой, как в Clikt: `.int()`, `.choice("ansi", "png", "svg")`, `.path()`
(файл должен существовать), `.input()` (файл или `-`/ничего = stdin), `.output()` (файл или
ничего = stdout), `.default(v)`, `.required()`, `.multiple()`. Ошибки разбора всегда дают код 2 и
одну строку `unitool qr encode: --ec: ожидается L, M, Q или H`.

### 2.2. Хост

```kotlin
// unitool-cli/app/src/main/kotlin/cx/m42/unitool/cli/Main.kt
fun main(args: Array<String>) {
    val code = Cli(
        name = "unitool",
        version = BuildInfo.VERSION,
        apps = listOf(QrApp(), AgeApp()),
    ).run(args, SystemIo())
    exitProcess(code)
}
```

`Cli.run` разбирает так:

| Вход | Что происходит |
|---|---|
| `unitool` | терминал → меню (§2.4); иначе справка и код 2 |
| `unitool --help` / `--version` | список приложений / версия |
| `unitool qr` | терминал → меню команд `qr`; иначе справка `qr` и код 2 |
| `unitool qr encode …` | разбор параметров и `run` |
| `unitool qr hello` | `hello` не имя команды → `default` (если есть), иначе код 2 |
| `unitool qr encode --help` | справка команды из `params` |

### 2.3. `Io` — всё, что команда видит снаружи

```kotlin
public interface Io {
    public val stdin: InputStream
    public val stdout: OutputStream      // results only (D269)
    public fun say(text: String)         // stderr, for the human
    public val interactive: Boolean      // stdin and stderr are both a terminal
    public val env: Map<String, String>
    public fun ask(prompt: String, default: String? = null): String
    public fun secret(prompt: String): CharArray   // no echo; D272
    public fun choose(prompt: String, options: List<String>): Int
    public fun fail(message: String, code: Int = 1): Nothing
}
```

`SystemIo` — настоящий процесс: `System.console()` для `secret`, `isatty` через
`System.console() != null`. `FakeIo` лежит в `testFixtures` и даёт stdin байтами, заранее заданные
ответы на `ask`/`choose`/`secret` и захват stdout/stderr.

### 2.4. Меню (D271)

Меню показывает пронумерованные строки и читает номер из stdin. Стрелок и JLine нет: так меню
работает в любом терминале и проверяется `FakeIo`. Все вопросы идут в stderr, результат — в stdout.

```
$ unitool
Unitool 0.1.0 — что сделать?
  1) qr    QR-коды: сделать из текста, прочитать с картинки
  2) age   Шифрование age ключами SSH
> 1
  1) encode  Сделать QR-код из текста
  2) decode  Прочитать QR-код с картинки
> 1
TEXT — текст; пусто — читать из stdin: https://m42.cx
--format — 1) ansi  2) png  3) svg [ansi]:
█▀▀▀▀▀█ ▄▀ ▄ █▀▀▀▀▀█
…
То же одной командой: unitool qr encode https://m42.cx
```

Вопросы задаются по `params` в порядке объявления: сначала обязательные, потом необязательные с
показом значения по умолчанию (Enter его принимает). Значение `choice` выбирается номером.

## 3. Приложение `qr` (`apps/qr`, ZXing core 3.5.4)

| Команда | Параметры | Результат |
|---|---|---|
| `encode [TEXT]` | `TEXT` или stdin; `--format ansi\|png\|svg` (без него — по расширению `-o`, иначе ansi); `--ec L\|M\|Q\|H` (L); `-o FILE`; `--scale N` для png и svg (8); `--margin N` (2 для ansi, 4 для png и svg) | ansi — `▀` с цветом текста для верхнего модуля и цветом фона для нижнего, чёрное на белом при любой теме; png и svg — в файл или stdout; PNG на терминал не выводится (код 2) |
| `encode --split` | то же | если байт больше, чем помещается (2953 при L), режет **по строкам** на части, каждая — свой код. В терминале между частями ждёт Enter и чистит экран; в файл пишет `name-1.png`, `name-2.png`… |
| ~~`decode IMAGE…`~~ | png/jpg/gif, `-` = stdin | текст каждого найденного кода, по строке на код. **Не в первой версии (D288)** |

`--split` по строкам нужен потому, что импорт 2FA склеивает `otpauth://`-строки из нескольких
сканов (`Import.kt`). Разрез посреди строки сломал бы ссылку. Так страница экспорта 2FA сможет
показывать `unitool age decrypt -i ~/.ssh/id_ed25519 F.age | unitool qr encode --split` вместо
GNU-only `split --filter` — это правка Unitool, в этот этап она не входит.

## 4. Приложение `age` (`apps/age` поверх `unitool-kit`) — отложено (D288, D289)

| Команда | Параметры | Результат |
|---|---|---|
| `decrypt [FILE]` | `-i KEY` (по умолчанию `~/.ssh/id_ed25519`, потом `~/.ssh/id_rsa`; можно несколько); `FILE` или stdin; `-o FILE` | открытый текст в stdout. Если ключ под паролем — `io.secret` или `UNITOOL_PASSPHRASE` (D272). Армор и бинарный формат распознаются сами |
| `encrypt [FILE]` | `-r "ssh-ed25519 …"` (несколько), `-R FILE` (authorized_keys), `--github LOGIN` (ключи из `https://github.com/<login>.keys`); `-a` — армор; `-o FILE` | файл age, который открывает настоящий `age -d -i` |

Ограничения — те же, что у кода в Unitool, и об этом говорит `--help`:
- получатели только `ssh-ed25519` и `ssh-rsa` от 2048 бит;
- нет `age1…`/`AGE-SECRET-KEY-` и паролей scrypt. Добавить X25519-получателей просто (примитивы
  есть, нужен bech32), но в Unitool их нет. Нужны ли они — часть вопроса 132;
- ключи PEM, PKCS#8, PuTTY, DSA и `sk-` отклоняются с названной причиной, как в
  `OpenSshKeyFile.refusalFor`.

Ошибки: неверный пароль, «ни один ключ не подходит», битый файл — код 1 и одна строка, без stack
trace. Stack trace печатается только с `UNITOOL_DEBUG=1`.

Сама логика — в `unitool-kit` (02 §3.1): `Age`, `OpenSshKeyFile`, `SoftwareOps.ageIdentity` и
`SshPublicKey`. В `apps/age` остаются только параметры, чтение файлов, вопрос пароля и сеть для
`--github`. Цепочка `decrypt`: `OpenSshKeyFile.parse` → `decrypt(passphrase)` → `ageIdentity` →
`Age.decrypt`, всё это внутри `runBlocking`. Тесты совместимости самого age живут в kit, а здесь
проверяются команды (§7).

## 5. Кандидаты на потом (вопрос 132)

| Приложение | Что | Откуда |
|---|---|---|
| `otp` | `code URI\|SECRET` — текущий TOTP; `migration 'otpauth-migration://…'` — ссылки `otpauth://` из переноса Google Authenticator. Вместе с `qr decode screenshot.png` переносит аккаунты с экрана | `unitool-kit` `kit.otp` — переезжает туда в первой партии (02 §3.2), так что для CLI это только команды |
| `ssh` | `fingerprint`, `github LOGIN` (ключи пользователя), `keygen` ed25519 с паролем | `kit.ssh`: `SshPublicKey`, `OpenSshKeyFile.write` |
| `rate` | `rate 100 USD EUR` | `apps/currency-converter/Rates.kt` (`parse`, `convert`) — по правилу D273 переедет в kit, когда понадобится CLI |

Калькулятор не переносится: в Unitool это автомат клавиатуры, а не вычислитель выражений.

## 6. Место в монорепозитории и сборка (D280)

```
/prj/unitool/
  settings.gradle.kts         // + :unitool-cli:core, :unitool-cli:apps:qr, :unitool-cli:apps:age, :unitool-cli:app
  unitool-cli/
    core/                     // the command framework (D279); kotlin("jvm") + java-test-fixtures; no dependencies
    apps/qr/                  // core + zxing core (already in libs.versions.toml)
    apps/age/                 // core + project(":unitool-kit") + kotlinx-coroutines-core (runBlocking only)
    app/                      // application { mainClass = "cx.m42.unitool.cli.MainKt" }, applicationName = "unitool"
    bin/unitool               // runs app/build/install/unitool/bin/unitool, building it if missing
    README.md                 // examples
```

- Wrapper, каталог версий и `verify.sh` — общие с телефоном. Своих у CLI нет.
- `cli-core` не публикуется (D279). Общий код — это `unitool-kit`, а CLI его только потребляет.
- Сборка: `./gradlew :unitool-cli:app:installDist`. Ей нужно то же окружение, что и телефону
  (02 §2), а `bin/unitool` после сборки работает без Gradle.

## 7. Тесты

| Что | Как |
|---|---|
| разбор argv | все формы GNU, `--`, `-`, неизвестный флаг, нет обязательного, повтор, `choice`, `default` — коды 2 и текст ошибки |
| справка | golden-файлы `--help` для `unitool`, `qr`, `qr encode`; тест падает, если текст сменился без обновления |
| меню | `FakeIo` с ответами проходит путь `1 → 1 → текст → Enter` и даёт тот же stdout, что прямой вызов. Строка «то же одной командой» при запуске снова даёт тот же результат |
| `qr` | encode → decode через ZXing для png и svg (svg растеризуется). ansi разбирается обратно в матрицу и сравнивается с матрицей ZXing. `--split` на 2952, 2953, 2954 байтах и на одной строке длиннее предела (код 1) |
| `age` | `decrypt` файла от настоящего `age -R`; вывод `encrypt` открывает `age -d`; ключ под паролем (`ssh-keygen -N`); неверный пароль → 1; `--github` через подменённый HTTP |
| вывод | stdout чист от человеческого текста: `unitool age decrypt … \| unitool qr decode -` — в конвейере |
| бинарь | `scripts/verify.sh` собирает `:unitool-cli:app:installDist` и гоняет `unitool-cli/bin/unitool` из shell: `--version`, `qr encode` → `qr decode` через файл, `age encrypt -r` → `age -d` |

`verify.sh` завершается с ненулевым кодом, если что-то упало, и перечисляет каждый пропуск,
как в Unitool.

## 8. Шаги

1. Скелет: модули `:unitool-cli:*` в `settings.gradle.kts`, плагин `kotlinJvm` в каталоге и в
   корневом `build.gradle.kts`, симлинк и строка `.gitignore` убраны (D285). Проверка —
   `./gradlew :unitool-cli:app:installDist` зелёный, а `verify.sh` телефона не изменился в
   результатах.
2. `cli-core`: модель, делегаты, разбор, справка, ошибки, `SystemIo`/`FakeIo`, меню и тесты §7.
   Проверка — тестовое приложение `echo` в тестах проходит argv, справку и меню.
3. `apps/qr` с тестами.
4. `apps/age` с тестами поверх `project(":unitool-kit")`.
5. `app`: `Main.kt`, `bin/unitool`, `README.md` с примерами, проверки границ CLI и прогон
   бинаря в `verify.sh` (D283), `paths-ignore` в `android-play.yml` (D284). Проверка:
   - `verify.sh` зелёный целиком;
   - руками пройдено меню в настоящем терминале;
   - экспорт 2FA с эмулятора расшифрован `unitool age decrypt` и показан `unitool qr encode --split`.

Шаги идут после 02 §6, шага 1 (kit и перевод телефона одним коммитом), в той же ветке `cli`
(D287). Шаги 1–3 от kit не зависят и могут идти параллельно с ним. После каждого шага — коммит. В
`main` — только по слову владельца.
