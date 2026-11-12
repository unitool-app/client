# app-learn в Unitool: модуль, которого нет при разработке

v0.2, 2026-11-07. Построено: сабмодуль на app-learn e372bd6, Unitool на Superizer 0.8.1, learn на Android,
desktop и iOS, не в web. Решения D640–D648; Q276 (без web) и Q277 (bump, теперь 0.8.1) отвечены; Q274
и Q275 сделаны по предложенному (папка, закреплённый коммит), пока владелец не скажет иначе.

Дальше: [01-all-apps-head.md](01-all-apps-head.md) (v0.1, 2026-11-11, план) — все приложения из
app-learn и голова `main` в CI; ответ на Q275 «голова», D671–D678, Q289–Q293.

Обновить app-learn здесь (до 01): `git -C external/app-learn pull` (после `git submodule update --init`) и
коммит изменившегося gitlink'а — «Bump app-learn to <sha>».

## Что хотим

Приложение «Learning English» разрабатывается отдельно, в https://github.com/xziy/app-learn
(свой хост `cx.m42.english` и одно приложение `apps/learn`, `:apps:learn`, `LearnApp`). В Unitool оно
попадает только при сборке в CI: в репозитории оно лежит сабмодулем, локально папка пустая, и Unitool
собирается и работает без него, как сейчас.

## Как это устроено

**D640. Сабмодуль в `external/app-learn`, не в `apps/`.** Сабмодуль — это весь репозиторий app-learn
(со своим `settings.gradle.kts`, `composeApp`, `iosApp`), а Unitool нужен из него только `apps/learn`.
Поэтому сабмодуль лежит отдельно, а Gradle берёт из него одну папку:

```kotlin
// settings.gradle.kts
val learnDir = file("external/app-learn/apps/learn")
if (learnDir.resolve("build.gradle.kts").exists()) {
    include(":apps:learn")
    project(":apps:learn").projectDir = learnDir
}
```

URL в `.gitmodules` — https (репозиторий публичный, в CI токен не нужен). `git clone` без
`--recursive` сабмодуль не скачивает, поэтому при разработке папка пустая сама.

**D641. «Есть или нет» определяется по наличию папки, ничего не раскомментируется.** Ты просил, чтобы
action раскомментировал подключение; предлагаю иначе: условие выше и в `composeApp` срабатывает само,
когда сабмодуль скачан, и CI делает только `submodules: true` в `actions/checkout`. Тогда текст сборки
один и тот же в обоих режимах (как с `../superizer`), `sed` по Kotlin не нужен и сломаться нечему.
Локально собрать с ним: `git submodule update --init external/app-learn`; убрать:
`git submodule deinit external/app-learn`. → Q274.

**D642. Регистрация — через два файла-заглушки, а не через `if` в Kotlin.** Common-код не может
сослаться на `LearnApp`, если модуля нет. В `composeApp` две папки с одной и той же функцией:

```
composeApp/src/optional-learn/kotlin/.../OptionalApps.kt   // register(LearnApp())
composeApp/src/optional-none/kotlin/.../OptionalApps.kt    // ничего
```

```kotlin
internal fun HostBuilder.registerOptionalApps() { register(LearnApp()) }   // или пустая
```

`composeApp/build.gradle.kts` по `findProject(":apps:learn") != null` добавляет к `commonMain` одну из
двух папок и `implementation(project(":apps:learn"))`. В `UnitoolHost.kt` одна строка
`registerOptionalApps()` после остальных `register`. В каталоге, не на свежем Home (D48).

**D643. Версия сабмодуля закреплена коммитом.** Gitlink указывает на конкретный коммит app-learn, и
сборка alpha воспроизводима; новая версия приходит коммитом «bump app-learn» (как «bump superizer»).
Альтернатива — CI берёт голову `main` (`git submodule update --remote`), тогда каждое изменение
app-learn попадает в следующую сборку Unitool без коммита здесь. → Q275.

**D644. CI.** `submodules: true` у `actions/checkout` самого Unitool в `android-play.yml`, `ios.yml`,
`verify.yml`. `cli.yml` не трогаем: CLI приложений не собирает. Пути `external/app-learn/**`
в `paths-ignore` не добавляем: bump сабмодуля должен выпускать сборку.

## Что мешало (сделано: app-learn e372bd6, Superizer 0.8.1)

**D645. Нет Android.** У `apps/learn` цели `desktop`, `iosArm64`, `iosSimulatorArm64`, а у Unitool
главная платформа — Android. Нужно добавить в app-learn `androidTarget` + `android { namespace =
"cx.m42.english.learn" … }` и `androidMain/Speech.android.kt` (`TextToSpeech`). Для этого в каталог
app-learn — `androidLibrary`, `agp`, `androidCompileSdk`, `androidMinSdk` с теми же именами, что в
Unitool: при сборке внутри Unitool `libs` — это каталог Unitool, и build-файл должен читаться в обоих.

**D646. Web.** У `composeApp` есть `wasmJs`, у learn нет. Предлагаю не делать learn для web: в
`wasmJsMain` идёт `optional-none`, зависимость — только в `androidMain`/`iosMain`/`desktopMain`. Иначе
— `wasmJs` в learn и речь через Web Speech API. → Q276.

**D647. Superizer 0.8.0.** app-learn собран на 0.8.0 (контексты, ScreenSteps, back по своим экранам),
Unitool закреплён на 0.7.1. Внутри Unitool learn соберётся против 0.7.1 и, если использует API 0.8.0,
не скомпилируется. Значит, сначала отдельный коммит «bump superizer to 0.8.0» в Unitool (опубликован
ли 0.8.0 как релиз, проверить) — или learn держится API 0.7.1. → Q277.

**D648. Каталог Unitool.** Добавить алиасы, которые learn использует, а Unitool нет:
`compose-components-resources`, `kotlinx-coroutines-test`. Проверка зависимостей в корневом
`build.gradle.kts` (никакого `superizer:host`, `:composeApp`, других `:apps:`) learn уже соблюдает.

## Порядок

1. app-learn: Android-цель и речь на Android, алиасы каталога (D645).
2. Unitool: bump Superizer до 0.8.0, если ответ на Q277 «да» (D647).
3. Unitool: сабмодуль, `settings.gradle.kts`, `OptionalApps.kt` ×2, `composeApp`, каталог (D640–D642, D648).
4. Unitool: `submodules: true` в трёх workflow (D644); проверить локально с инициализированным
   сабмодулем и без него (`./gradlew :composeApp:assembleDebug desktopTest`).

## Вопросы

- **Q274.** Определять наличие по папке (D641) — или всё-таки закомментированные строки, которые
  раскомментирует action?
- **Q275.** Закреплённый коммит app-learn (D643) или всегда голова `main`?
- **Q276.** Learn в web-версии не нужен (D646)?
- **Q277.** Бампим Unitool на Superizer 0.8.0 перед этим (D647)?
