# Все приложения из app-learn и всегда голова `main`

v0.2, 2026-11-11. Построено (не закоммичено), кроме D675. Решения D671–D678, вопросы Q289–Q293.

Итог сборки: app-learn 040d7a4 (learn + books), Superizer 0.8.1 из исходников — Android, desktop, web,
`:apps:books:desktopTest`, `:apps:learn:desktopTest`, `:composeApp:desktopTest` зелёные; без сабмодуля
тоже. Q289 — соглашение по имени + `superizer-app.properties` как переопределение; Q292 — books в
app-learn (`apps/books`, коммит 040d7a4); Q291 и Q293 — как предложено (падать; голова везде, и в
`verify.yml`). Q290 открыт. sha app-learn пишется только в summary job'а, `releaseName` не тронут.
Книги app-learn не в git (авторские права), поэтому в Unitool из CI у books пустая полка.

## Что хотим

Сейчас (README, D640–D648) Unitool берёт из app-learn ровно одно приложение `apps/learn`, а коммит
сабмодуля закреплён (D643), новая версия приходит коммитом «Bump app-learn». Нужно:

1. CI собирает Unitool со **всеми** приложениями из `apps/` репозитория app-learn — сейчас `learn`, следом
   `books` (на 2026-11-11 его в app-learn ещё нет ни в одной ветке), дальше любое новое — без правок в
   Unitool.
2. CI берёт app-learn с **головы `main`**, а не с закреплённого коммита. Это ответ на Q275: «голова».

## Как это устроено

**D671. Все папки `apps/*` с `build.gradle.kts` — проекты Unitool.** `settings.gradle.kts` вместо одной
папки обходит `external/app-learn/apps/`:

```kotlin
val externalApps = file("external/app-learn/apps").listFiles().orEmpty()
    .filter { it.resolve("build.gradle.kts").exists() }
    .sortedBy { it.name }
externalApps.forEach { dir ->
    val path = ":apps:${dir.name}"
    check(!file("apps/${dir.name}").exists()) { "app-learn/apps/${dir.name} clashes with Unitool's own apps/${dir.name}" }
    include(path)
    project(path).projectDir = dir
}
```

Имя проекта то же `:apps:<папка>`, что и в самом app-learn: если `books` когда-нибудь сошлётся на
`project(":apps:learn")`, ссылка сработает и здесь. Совпадение имени с собственным приложением Unitool —
ошибка сборки с понятным текстом, а не тихая подмена.

**D672. Какой класс регистрировать.** Построено: по умолчанию `cx.m42.english.<папка>.<Папка>App`
(learn → `LearnApp`, books → `BooksApp`), файл ниже — только переопределение. Исходный вариант:
Common-код Unitool не знает классов заранее, а угадывать по исходникам («публичный класс на `App`») —
хрупко. Соглашение для app-learn: в каждой `apps/<name>/` лежит `superizer-app.properties`:

```properties
# The one class a host registers. Needs a public no-argument constructor.
class=cx.m42.english.learn.LearnApp
```

Папка без этого файла в Unitool не попадает (в `settings.gradle.kts` она пропускается с `logger.warn`),
так app-learn может держать в `apps/` и модули-библиотеки. → Q289.

**D673. `OptionalApps.kt` генерируется, две папки `src/optional/{learn,none}` уходят.**
`composeApp/build.gradle.kts` по списку из D671–D672 пишет в `build/generated/optionalApps/` один файл:

```kotlin
internal actual fun SuperizerBuilder.registerOptionalApps() {
    register(cx.m42.english.learn.LearnApp())
    register(cx.m42.english.books.BooksApp())
}
```

и добавляет `implementation(project(path))` для каждого в `androidMain`/`desktopMain`/`iosMain` — так же,
как сейчас `generateBuildInfo`. Пустой список (локально, сабмодуль не скачан) даёт пустое тело — это и
есть нынешний `none`. Порядок — по имени папки, чтобы каталог не прыгал между сборками. Web по-прежнему
всегда пустой (D646): у learn нет `wasmJs`, и проверять цели каждого модуля ради web не стоит. → Q292.

**D674. В CI — голова `main` app-learn.** В `.gitmodules` у сабмодуля `branch = main`, и в каждом
workflow после `actions/checkout` (`submodules: true`) шаг:

```yaml
- name: app-learn at the head of main (.ai-notes/app-learn D674)
  run: |
    git submodule update --remote --depth 1 external/app-learn
    sha=$(git -C external/app-learn rev-parse --short HEAD)
    echo "APP_LEARN_SHA=$sha" >> "$GITHUB_ENV"
    echo "app-learn: \`$sha\` ($(git -C external/app-learn log -1 --format=%s))" >> "$GITHUB_STEP_SUMMARY"
```

Закреплённый gitlink остаётся только для локального `git submodule update --init`; коммиты «Bump
app-learn» больше не нужны. Чтобы сборку можно было повторить, sha app-learn пишется в summary job'а и в
`releaseName` Play (`<run_number> · learn <sha>`), а на iOS — в заметку TestFlight.

**D675. Пуш в app-learn сам выпускает Unitool.** Без этого голова `main` подхватится только при
следующем пуше в Unitool. В app-learn — workflow на `push` в `main`, который делает
`gh workflow run android-play.yml -R unitool-app/client --ref main` (и `ios.yml`). Нужен токен с правом
`actions:write` на `unitool-app/client` в секретах app-learn (fine-grained PAT, одна репа). → Q290.

**D676. Сломанная голова app-learn не выпускается.** Цена «всегда головы»: app-learn может уехать на
Superizer новее, чем у Unitool (D647), или взять алиас каталога, которого у Unitool нет (D648), — и
сборка Unitool упадёт. Решение: падает сборка, в Play/TestFlight ничего не уходит, в summary — sha
app-learn. Откат на закреплённый gitlink автоматически не делаем: тихо выпустить старую версию хуже, чем
громко упасть. → Q291.

**D677. Правила для app-learn, чтобы его приложения собирались внутри Unitool** (дописать в его README):
`androidTarget` + `android { namespace }` у каждого приложения (D645); только алиасы каталога, которые
есть и у Unitool (D648); Superizer не новее, чем у Unitool; никаких `superizer:host` и чужих хостов.
`verify.yml` Unitool, запущенный из D675, проверяет это на каждом пуше в app-learn.

**D678. Что меняется в Unitool.** `.gitmodules` (`branch = main`); `settings.gradle.kts` (D671–D672);
`composeApp/build.gradle.kts` (D673, вместо блока `findProject(":apps:learn")`); удалить
`src/optional/learn` и `src/optional/none`, `OptionalApps.kt` в commonMain остаётся (`expect`);
`android-play.yml`, `ios.yml`, `verify.yml` — шаг D674. В app-learn: `apps/learn/superizer-app.properties`
и workflow D675.

## Порядок

1. app-learn: `apps/learn/superizer-app.properties` (и такой же в `apps/books`, когда он появится).
2. Unitool: D671–D673, проверить `./gradlew :composeApp:assembleDebug desktopTest` с сабмодулем и без.
3. Unitool: D674 в трёх workflow. Пуш в `main` = Play alpha + TestFlight.
4. app-learn: D675, если Q290 «да».

## Вопросы

- **Q289.** Файл `superizer-app.properties` в каждой папке (D672) — или договоримся о другом способе
  (например, класс всегда `<Name>App` в пакете `cx.m42.english.<name>`)?
- **Q290.** Пуск сборки Unitool из app-learn (D675) нужен? Тогда нужен токен в секретах app-learn.
- **Q291.** Голова app-learn сломана — падать (D676) или откатываться на закреплённый коммит?
- **Q292.** Где app-books — тоже в `xziy/app-learn/apps/books`, или это отдельный репозиторий? Если
  отдельный — второй сабмодуль `external/app-books`, и D671 обходит `external/*/apps/*`.
- **Q293.** Голова и в `verify.yml` на PR, или в PR — закреплённый gitlink, а голова только в выпуске?
