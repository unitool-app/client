# 02 — Модули Gradle и «npm-пакет» для Kotlin

## Задача

В Node аналог решается `package.json` + `npm publish`, а на время разработки — `npm link`.
Для Kotlin Multiplatform это: **KMP-библиотечные модули + `maven-publish`**, а на время
разработки — **Gradle composite build (`includeBuild`)** или прямые `project(...)`-зависимости в
монорепо.

| Node | Gradle/KMP | Примечание |
|---|---|---|
| `package.json` `name`/`version` | `group` + `version` + имя модуля (`artifactId`) | `cx.m42.superizer:core:0.1.0` |
| `exports` в package.json | `api` vs `implementation` в `build.gradle.kts` | Что видит потребитель транзитивно |
| `npm publish` | `./gradlew publishToMavenLocal` → `publish` (GitHub Packages / Maven Central) | |
| `npm install adminizer` | `implementation("cx.m42.superizer:core:0.1.0")` + `repositories { mavenLocal() / maven(url) }` | |
| `npm link` / workspaces | `includeBuild("../superizer")` — composite build с автоматической подстановкой координат | Потребитель пишет maven-координаты, Gradle подставляет локальный исходник |
| `peerDependencies` (react) | `compileOnly`/`api` на Compose с единой версией из каталога | Compose-компилятор и runtime должны совпадать у lib и хоста |
| `dist/*.d.ts` | `.klib`/`.jar` + `-sources.jar`; Kotlin-метаданные — это и есть «типы» | |
| `fixture/` — демо-хост | `composeApp/` — Unitool как демо-хост | |

## Структура репозитория (целевая)

> Ревизия D9 (12): дерево ниже делится на две папки — `/prj/superizer` (всё под `superizer/` плюс
> `apps/test-app`, `fixture/`, `samples/consumer`, `scripts/`) и `/prj/unitool` (`composeApp/`,
> `apps/calculator`, `apps/currency-converter`). Модули, группы и правила зависимостей не меняются.

```
unitool/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml            единый каталог версий для всего
├── build-logic/                         convention plugins (см. ниже)
│   └── src/main/kotlin/superizer.kmp-library.gradle.kts
├── superizer/                           ← БИБЛИОТЕКА («пакет»); будущий отдельный репозиторий
│   ├── core/       cx.m42.superizer:core      контракт, runtime-интерфейсы, registry, handler, activation-модель
│   ├── ui-theme/   cx.m42.superizer:ui-theme  только дизайн-токены (цвета, отступы, шрифты) и AppTheme (D23)
│   ├── ui/         cx.m42.superizer:ui        компоненты, AppScaffold, экраны хоста (AllApps, ServiceMenu, Settings-frame), shell
│   ├── host/       cx.m42.superizer:host      реализации сервисов + expect/actual (prefs, haptics, back, language, ktor)
│   └── testing/    cx.m42.superizer:testing   FakeAppRuntime, in-memory сервисы, хелперы для UI-тестов
├── apps/                                ← ПРИЛОЖЕНИЯ, каждое — отдельный KMP-модуль
│   ├── calculator/          cx.m42.superizer.apps:calculator
│   ├── currency-converter/  cx.m42.superizer.apps:currency-converter
│   └── test-app/            cx.m42.superizer.apps:test-app
└── composeApp/                          ← SUPER APP (Unitool): регистрация, Android/desktop/wasm entrypoints, release
```

`settings.gradle.kts`:

```kotlin
rootProject.name = "Unitool"
pluginManagement { includeBuild("build-logic"); repositories { … } }
include(":superizer:core", ":superizer:ui-theme", ":superizer:ui", ":superizer:host", ":superizer:testing")
include(":apps:calculator", ":apps:currency-converter", ":apps:test-app")
include(":composeApp")
```

## Граф зависимостей

```
composeApp ──► apps:calculator ─────┐
    │      ──► apps:currency-conv ──┼──► superizer:ui ──► superizer:ui-theme ──► superizer:core
    │      ──► apps:test-app ───────┘         ▲
    ├──► superizer:host ──────────────────────┼──► superizer:core
    └──► superizer:testing (desktopTest) ─────┘
```

Правила проверяются **сборкой с шага 1** (D21): задача `checkDependencyRules` в `build-logic`
читает `configurations.*.allDependencies` каждого модуля и падает на запрещённом ребре. Это
~30 строк Kotlin DSL; ревью такое не ловит через полгода.

- `apps/*` → только `superizer:core`, `superizer:ui-theme`, `superizer:ui` (по желанию),
  стандартные библиотеки (coroutines, serialization). **Не** `host`, **не** `composeApp`, **не** другие `apps/*`.
- `superizer:ui-theme` → `core` + compose foundation (для `CompositionLocal`/`Dp`). Никаких компонентов.
  Экспортирует `Tokens` (цвета, отступы, типографика) и `LocalTokens` (D37); `AppTheme(tokens = Light) { … }`.
  Тёмная тема = второй объект `Tokens`.
  Сторонний набор компонентов зависит от `ui-theme`, чтобы совпадать с хостом по цветам и отступам,
  не таща весь `ui`.
- `superizer:core` → `compose.runtime`, coroutines, serialization-json. **Без** `foundation`/`ui`.
  Это делает контракт независимым от способа отрисовки (в теории можно рендерить не Compose).
  Единственное, что core берёт из Compose — аннотацию `@Composable` и `State`.
- `superizer:ui` → `core` + `ui-theme` + compose foundation/ui/unstyled. Экспортирует Compose как `api`, чтобы
  приложения не дублировали версии.
- `superizer:host` → `core` + ktor + платформенные API. Ничего из `ui`.
- `superizer:testing` → `core` (+ `ui` для хелперов UI-тестов).

## Таргеты библиотечных модулей

Как у `composeApp` сегодня: `androidTarget()` (через `com.android.library`), `jvm("desktop")`,
`wasmJs { browser() }`. iOS не добавляем (сборка на Linux), но именование source-set-ов оставляет место.

Для Android-библиотек в KMP нужен плагин `com.android.library` (в каталог добавить
`androidLibrary = { id = "com.android.library", version.ref = "agp" }`). Каждому модулю — свой
`namespace` (`cx.m42.superizer.core`, `cx.m42.apps.calculator`, …).

## Convention plugin вместо копипасты

Семь библиотечных модулей с одинаковыми 40 строками KMP-конфига — повод для `build-logic`
(included build с precompiled script plugin). Два плагина:

- `superizer.kmp-library` — таргеты android/desktop/wasm, jvmTarget 11, compileSdk/minSdk из
  каталога, `maven-publish` с `group`/`version`, `publishLibraryVariants("release")`,
  **`explicitApi()`** и `binary-compatibility-validator` (D14): дампы `api/<module>.api` лежат в
  репозитории, `apiCheck` входит в `check`. Для `apps/*` explicit API тоже включён (один публичный
  класс на модуль — правило из 03), BCV — нет.
- `superizer.compose-library` — то же + `org.jetbrains.compose` + `kotlin.plugin.compose`.

Модуль приложения тогда выглядит так:

```kotlin
plugins {
    id("superizer.compose-library")
    alias(libs.plugins.kotlinxSerialization)
}
kotlin.sourceSets.commonMain.dependencies {
    api(project(":superizer:core"))
    implementation(project(":superizer:ui"))
    implementation(libs.kotlinx.serialization.json)
}
android.namespace = "cx.m42.apps.calculator"
```

При выделении библиотеки в отдельный репозиторий `build-logic` уезжает вместе с ней, а
`project(":superizer:core")` меняется на `libs.superizer.core` (координаты из каталога).

## Лицензия и CI

`LICENSE` — MIT (D41), в корне `/prj/superizer` с шага 1; `pom` через `maven-publish` несёт
`licenses`/`scm`. CI (D40): `.github/workflows/verify.yml` в обоих репозиториях — ubuntu-latest,
JDK 21, Android SDK через `android-actions/setup-android`, Chromium из Playwright, кэш Gradle;
шаг — `./scripts/verify.sh`; артефакты — `build/verify/`. В библиотеке второй workflow
`publish.yml` по тегу `v*` → GitHub Packages (`GITHUB_TOKEN`).

## Публикация

- Версия — одна на всю библиотеку, в `superizer/gradle.properties` (`superizer.version=0.1.0`),
  читается convention-плагином. Приложения версионируются независимо (каждое — своя `version`).
- `contractVersion` (D14) — отдельное целое в `core` (`SuperizerContract.VERSION`), не semver
  библиотеки: библиотека может выпускать 0.2, 0.3 с тем же контрактом 1. Поднимается только когда
  `apiCheck` показывает несовместимое изменение в `core`.
- MVP: `./gradlew publishToMavenLocal`. Потребитель добавляет `mavenLocal()` в
  `dependencyResolutionManagement.repositories`.
- Следующий шаг: GitHub Packages (`maven("https://maven.pkg.github.com/<org>/superizer")` с
  `GITHUB_ACTOR`/`GITHUB_TOKEN` из env, как keystore сейчас). Maven Central — когда появится смысл.
- Sources jar включён (`withSourcesJar()` KMP делает сам). Dokka — не в MVP.

## Три режима работы с «пакетом»

| Режим | Когда | Как |
|---|---|---|
| ~~Монорепо, `project(":superizer:core")`~~ | отменён ревизией D9 (12) | — |
| Composite build (`npm link`) | режим B в 12: библиотека в `/prj/superizer`, правится параллельно с хостом | в хосте: `includeBuild("../superizer")` если папка есть; зависимости пишутся как `cx.m42.superizer:core:0.1.0`, Gradle подставит исходники |
| Maven-координаты (`npm install`) | режим C в 12: проверка публикации, сторонний хост, standalone | `mavenLocal()` (`SUPERIZER_FROM_MAVEN=1` отключает includeBuild) или GitHub Packages |

Важно: composite build требует, чтобы `group`/`artifactId` в `superizer` совпадали с
координатами, которые пишет потребитель — иначе подстановки не будет. Поэтому `group` задаётся
convention-плагином, а не руками в каждом модуле.

## Версии и каталог

Добавить в `gradle/libs.versions.toml` (значения — из `agentiz/mobile-client`, они собираются в
этом окружении):

```toml
[versions]
kotlinxCoroutines = "1.10.2"
kotlinxSerializationJson = "1.9.0"
ktor = "3.4.0"

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinxCoroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerializationJson" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }   # android
ktor-client-cio    = { module = "io.ktor:ktor-client-cio",    version.ref = "ktor" }   # desktop
ktor-client-js     = { module = "io.ktor:ktor-client-js",     version.ref = "ktor" }   # wasmJs

[plugins]
androidLibrary = { id = "com.android.library", version.ref = "agp" }
kotlinxSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
binaryCompatibilityValidator = { id = "org.jetbrains.kotlinx.binary-compatibility-validator", version = "0.18.1" }  # версию сверить при шаге 1
```

Ktor нужен только `host`-модулю (реализация `NetworkService`). `core` и приложения видят лишь
интерфейс — это и есть смысл «App не знает, REST там или mock».

## Что остаётся в `composeApp`

Только хостовое: `MainActivity`, `main.kt` (desktop/wasm), `index.html`, Android-ресурсы
(иконка, темы, backup rules, locales_config), `generateBuildInfo`, подпись/R8/Play. Плюс один
файл `UnitoolHost.kt` — сборка `Superizer` и регистрация приложений. `proguard-rules.pro` остаётся
почти пустым: регистрация явная, рефлексии нет; kotlinx-serialization на Android с R8 требует
стандартные правила, которые библиотека несёт сама (`consumer-rules`).
