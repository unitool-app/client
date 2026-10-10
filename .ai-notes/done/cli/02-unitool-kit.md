# 02 — `unitool-kit`: общий модуль телефона и CLI в монорепозитории

> **Версия:** 1.3 · **Статус:** модуль создан (§0); из §3.1 перенесён `crypto` (1.3); остальное в §3 — план · **Дата:** 2026-10-08. Решения и вопросы — в
> [README](README.md).
>
> **История:**
> - 1.0 — kit как отдельный репозиторий с публикацией в GitHub Packages.
> - 1.1 — монорепозиторий (D280). §2, §5 и §6 переписаны, отдельного репозитория и публикации нет.
>   Перечень переносимого (§3) и тесты (§4) не изменились.
> - 1.2 — kit создан (D293), но первым в него поехал не §3, а QR-сервис из CLI (§0). §3 остаётся
>   планом на перевод телефона; цели модуля — те же плюс `linuxX64`.

## 0. Что в kit уже есть (1.2)

`cx.m42.unitool.kit.qr`, из CLI, по просьбе владельца вынести общие методы в одну библиотеку:

| API | Что |
|---|---|
| `Qr.encode(text, correction = L): QrCode?` | код или `null`, если не влез в версию 40; байтовый режим, ECI для UTF-8 |
| `Qr.capacity(correction, utf8)` | 2953 при L; на байт меньше с UTF-8 |
| `Qr.split(text, correction): List<String>` | по строкам; `LineTooLong(line, bytes, capacity)`, если строка не влезает одна |
| `QrCode.size/version/mask/isDark(x, y)` | матрица без тихой зоны: из неё можно рисовать самому, например на Compose `Canvas` |
| `QrImage.png(code, margin = 4, scale = 8)` | 1-битный PNG; свой deflate (`Deflate`), zlib не нужен |
| `QrImage.svg(code, margin = 4, scale = 8)` | один `path` горизонтальных отрезков |
| `QrTerminal.ansi(code, margin = 2)` | `▀` с явными цветами текста и фона |

Тесты: `commonTest` (обратный разбор каждого рендера, ёмкость, `split`) идёт на desktop и natively;
`desktopTest` — `QrZxingTest` (матрица = ZXing для всех 40 версий × 4 уровней, UTF-8, все маски;
каждый рендер читает декодер ZXing) и `DeflateTest` (`java.util.zip.Inflater`).

Телефон этим пока не пользуется: показывать QR ему негде. Когда понадобится, он добавит
`project(":unitool-kit")`, и код уже на месте.

## 1. Зачем

Владелец: «нам нужна единая кодовая база, чтобы мы не разошлись».

Сегодня всё, что нужно CLI для `age`, лежит внутри модулей, привязанных к телефону. `services/ssh`
зависит от `superizer:core`, а тот тянет Compose runtime. Многое там помечено `internal`. Kit
вынимает чистую часть в свой модуль того же репозитория, и у неё становится два потребителя:

```
:unitool-kit  ◄── :services:ssh, :apps:twofa            (телефон)
      ▲
      └────────── :unitool-cli:apps:age, позже :…:otp   (CLI)
```

Kit ни от кого из них не зависит. Superizer тоже ни от чего из этого не зависит.

## 2. Модуль (D280, D281)

```
/prj/unitool/
  settings.gradle.kts           // + include(":unitool-kit"), include(":unitool-cli:…")
  gradle/libs.versions.toml     // + kotlinJvm plugin alias, zxing is already there
  unitool-kit/
    build.gradle.kts            // kotlinMultiplatform + androidLibrary, explicitApi, api(serialization-core)
    src/commonMain/kotlin/cx/m42/unitool/kit/{crypto,ssh,age,otp}/
    src/{androidMain,desktopMain,iosMain,wasmJsMain}/…/crypto/Platform.*.kt
    src/desktopTest/            // the interop tests (§4)
```

- Сборка — такая же, как у `services/ssh` сегодня: те же цели, `explicitApi()`,
  `group = "cx.m42.unitool"`. Своей конвенции и build-logic не заводится: в Unitool их нет.
- CLI (`kotlin("jvm")`) получает JVM-вариант kit (цель `desktop`) обычным подбором вариантов
  Gradle. Плагин `org.jetbrains.kotlin.jvm` объявляется в корневом `build.gradle.kts` с
  `apply false`, рядом с мультиплатформенным, той же версии 2.4.10.
- Сборка CLI конфигурирует весь проект, Android-модули тоже. Значит, ей нужно то же окружение, что
  телефону: Android SDK из `local.properties`. Запуск готового CLI (`bin/unitool`) Gradle не трогает.

## 3. Что переезжает (D277)

### 3.1. Из `services/ssh` → `cx.m42.unitool.kit.{crypto,ssh,age}`

> **1.3 (2026-10-08, для split):** перенесены `crypto/*` и `SecretBytes` (в `kit.crypto`, а не
> `kit.ssh`) и `secureRandomBytes` с `actual` для linuxX64. `PlatformEcdsa` и `EcdsaDer` остались в
> `services/ssh`, как и `wire`, `SshPublicKey`, `SshSig`, `key/*` и `Age`: они переедут, когда их
> потребуют SSH-получатели `ucrypt` или CLI age. Подробно — split/README § «Построено».

| Сейчас | В kit | Изменения |
|---|---|---|
| `crypto/Sha.kt` (SHA-1/256/384/512, HMAC, HKDF), `ChaCha20Poly1305.kt`, `Aes.kt` (CTR, GCM), `Curve25519.kt`, `Field25519.kt`, `Ed25519.kt`, `Rsa.kt`, `Bcrypt.kt`, `EcdsaDer.kt` | `kit.crypto` | `internal` → `public` там, где ими пользуются связка ключей, twofa и CLI |
| `crypto/Platform.kt` + `Platform.{android,desktop,ios,wasmJs}.kt` (`secureRandomBytes`, `PlatformEcdsa`) | `kit.crypto` + `actual` в четырёх source set | — |
| `wire/SshWire.kt` | `kit.ssh` | — |
| `SshPublicKey.kt`, `SshSig.kt`, `SecretBytes.kt`, `key/OpenSshKeyFile.kt`, `key/SoftwareOps.kt` | `kit.ssh` | `SshPublicKey.id: KeyId` уходит. В `services/ssh` его заменяет расширение `val SshPublicKey.id get() = KeyId(fingerprint)`. `ImportRefusal` → `KeyFileRefusal`, в `services/ssh` есть отображение один к одному |
| `Age.kt` (`encrypt`, `decrypt`, `armor`, `dearmor`, `stanzas`, `AgeIdentity`, `StanzaUnwrapper`) | `kit.age` | `decrypt`, `Opened`, `AgeIdentity` → `public`. `decrypt` остаётся `suspend`: связка ключей разворачивает ключ после вопроса пользователю |

**Остаётся в `services/ssh`:** `keyring/*`, `agent/*`, `vault/AgeVault.kt`, `diagnostics/*`,
`SshKeyringHost.kt`, `desktopMain/keyring`. Это реализация контрактов Superizer, и CLI она не
нужна. Запрет сети (I-net) у `services/ssh` остаётся в силе и распространяется на kit.

### 3.2. Из `apps/twofa` → `cx.m42.unitool.kit.otp`

| Сейчас | В kit | Изменения |
|---|---|---|
| `Totp.kt`: `TotpAccount`, `Totp`, `Base32`, `AccountDraft`, `OtpAuthUri` | `kit.otp` | `@Serializable` остаётся. Форма JSON не меняется: имя класса в JSON не пишется, поэтому `accounts.sealed` читается как раньше. Это проверяет тест на прежнем образце |
| `GoogleMigration.kt` | `kit.otp` | `internal` → `public` |
| `Crypto.kt`: `HashAlgorithm` (SHA1/256/512), `hmac`, неиспользуемый `hkdfSha256` | `HashAlgorithm` → `kit.otp`, остальное **удаляется** | `HashAlgorithm` — поле `TotpAccount` и набор, который допускает `otpauth`. Поэтому он остаётся со старыми именами констант, и JSON не меняется. Внутри он считает через `kit.crypto.Hash` и `hmac`. Второй SHA и HMAC из twofa удаляются |

**Остаётся в twofa:** `Import.kt`, `Export.kt` (логика страниц и команды для shell), `AccountStore`,
`GitHubKeys` (ходит в сеть через Superizer) и весь UI.

### 3.3. Пока не переезжает

| Что | Почему |
|---|---|
| QR | В Unitool нет кодировщика. Чтение — ZXing в `androidMain` у телефона, ZXing в CLI. Чистый Kotlin-кодировщик появится в kit, когда QR понадобится показать на телефоне |
| `backup/GitHubKeys.kt` | ходит в сеть через `NetworkService`. CLI берёт ключи из `https://github.com/<login>.keys` (строки `authorized_keys`) и разбирает их `SshPublicKey.parseAll` |
| `currency-converter/Rates.kt` | нужен CLI только для `rate` (вопрос 132). Переедет по правилу D273, когда понадобится |

## 4. Тесты

Тесты переезжают вместе с кодом в `unitool-kit/src/desktopTest`:
- `AgeTest`, `PrimitivesTest`, `SshSigAndAgeInteropTest`, `OpenSshKeyFileInteropTest`;
- `BcryptTimingTest`, `JcaAge`, `Tools`;
- `TotpTest`, `TwoFaGoogleMigrationTest`.

Без `age` и `ssh-keygen` в `PATH` они пропускаются, и `verify.sh` перечисляет пропуски. Тесты
связки ключей, агента и UI twofa остаются на месте и работают уже поверх kit.

Ещё два теста:
- **границы** (D283) — в `verify.sh`, по дереву зависимостей (`:unitool-kit:dependencies` и
  `:unitool-cli:*:dependencies`);
- **перенос twofa** — `accounts.sealed` из образца, снятого до переноса, открывается и даёт те же
  коды.

## 5. Подключение (D281)

```kotlin
// services/ssh/build.gradle.kts, apps/twofa/build.gradle.kts
commonMain.dependencies { api(project(":unitool-kit")) }   // ssh re-exports SshPublicKey to keyring users

// unitool-cli/apps/age/build.gradle.kts
dependencies { implementation(project(":unitool-kit")) }
```

- `services/ssh` подключает kit через `api`: `SshPublicKey` и `Age` видны в его публичных типах
  (`Keyring`, `AgeVault`) и у `services/backup`. Остальные пишут `implementation`.
- Импорты `cx.m42.services.ssh.crypto.*` и `cx.m42.services.ssh.SshPublicKey` во всём репозитории
  меняются на `cx.m42.unitool.kit.*` механической заменой в том же коммите.
- Версии kit нет и бампать нечего: потребители всегда видят kit из того же коммита.

## 6. Шаги

1. **Kit и перевод телефона — один коммит** (D282), ветка `cli`.
   - `git mv` файлов §3 в `unitool-kit/`, смена пакетов, `internal` → `public` по необходимости.
   - Зависимости §5, расширение `KeyId` и отображение `KeyFileRefusal`, замена импортов, удаление
     `twofa/Crypto.kt`, проверки границ в `verify.sh`.
   - Проверка:
     - `./scripts/verify.sh` зелёный целиком: desktop, wasm, скриншоты, Android, тесты на
       устройстве, если эмулятор поднят;
     - на эмуляторе 2FA показывает старые аккаунты, а экспорт открывается `age -d`;
     - `git log --follow unitool-kit/…/Age.kt` доходит до истории в `services/ssh`.
2. **CLI** — 01 §8, следующие коммиты той же ветки.
3. **CI** — `paths-ignore` в `android-play.yml` (D284).

iOS собирается прогоном CI с меткой `testflight` на PR из ветки `cli`. В `main` ветка уходит только
по слову владельца.

Шаг 1 не меняет ничего видимого пользователю: тот же код, другое место. Если в шаге 1 тесты
расходятся, виноват перенос, а не новая логика. Поэтому новой логики в шаг 1 не добавляется.
