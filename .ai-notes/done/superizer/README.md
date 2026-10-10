# Superizer — заметки по проектированию

Superizer — это «Adminizer для Kotlin Multiplatform»: библиотека-хост, в которую независимые
приложения (Apps) подключаются по единому контракту и получают платформенные сервисы через
AppRuntime. Unitool становится первым хостом (Super App); Calculator, Currency Converter и
Test App — первыми приложениями.

Эти заметки — результат анализа спецификации, текущего кода Unitool, паттерна
`AbstractAdminizerApp`/`AppManager` из `/prj/adminizer` и сетевого/push-слоя из
`/prj/agentiz/mobile-client`. По ним делается библиотека.

| Файл | Что внутри |
|---|---|
| [01-overview.md](01-overview.md) | Цели, соответствие Adminizer ↔ Superizer, принятые решения, границы MVP |
| [02-modules-and-packaging.md](02-modules-and-packaging.md) | Gradle-модули, «npm-пакет» для Kotlin: публикация, composite build, конвенции |
| [03-app-contract.md](03-app-contract.md) | Контракт приложения: `SuperizerApp`, `AppInstance`, метаданные, конфиг, lifecycle, i18n |
| [04-runtime.md](04-runtime.md) | `AppRuntime` и интерфейсы сервисов; host-реализации; тестовый runtime |
| [05-registry-handler-shell.md](05-registry-handler-shell.md) | Registry, Handler (state machine), Shell: навигация, контейнер, All Apps, Settings, Service Menu |
| [06-activation.md](06-activation.md) | QR и промокоды, скрытые приложения, deep links |
| [07-mvp-apps.md](07-mvp-apps.md) | Calculator, Currency Converter, Test App — что именно они делают и проверяют |
| [08-migration-plan.md](08-migration-plan.md) | Что и куда переезжает из текущего Unitool; пошаговый план с чекпоинтами |
| [09-open-questions.md](09-open-questions.md) | Что не решено и требует ответа владельца |
| [10-dynamic-extensions.md](10-dynamic-extensions.md) | **Только идеи, не делаем.** Динамическая загрузка приложений: почему не «wasm в Compose», уровни, web-расширения через мост |
| [11-revision-1.md](11-revision-1.md) | Ревизия 1 (2026-09-20): что и почему изменено в контракте до начала кода — D14–D23 |
| [14-revision-3.md](14-revision-3.md) | Ревизия 3: четырнадцать оставшихся решений (иконки, часы, ошибки, сеть, логи, доступ к Service Menu, сброс, ширина, тема, testTag, CI, лицензия, бюджеты) — D29–D42 и явный список «не в MVP» |
| [13-push-and-routing.md](13-push-and-routing.md) | Ревизия 2: пуши и маршрутизация — payload схемы 1, две дороги (данные / тап), `PushRouter`/`PendingRoute`/`TopicStore`/`DeviceRegistrar`, приоритеты при старте, тесты без FCM — D24–D28 |
| [15-implementation.md](15-implementation.md) | **Отчёт о реализации (2026-09-21):** что построено, девять отличий кода от эскиза с причинами, как проверялось, и честный список того, что из 12–14 не сделано |
| [16-app-config-store-and-bundle.md](16-app-config-store-and-bundle.md) | **План (2026-09-21, не реализовано):** провижининг — конфиг из QR как запись, которую хост хранит по протоколу ядра `ProvisioningStore` и подаёт в каждый `launch` (D49–D51); экспорт в один файл `app_config_bundle` и импорт в другом Super App (D52–D58); автоматический режим по https-ссылке из системного сканера (D59); `audience` и регистрация устройства для связки «сервер приложения → сервер Unitool → приложение» (D60–D61); API, форматы, сценарии, тесты, шаги |
| [12-dev-guide.md](12-dev-guide.md) | **Гайд для агента.** Две папки (`/prj/superizer`, `/prj/unitool`), цикл A/B/C, публикация в mavenLocal, `scripts/verify.sh` за один запуск (desktop, web-smoke через Playwright, Android), как выглядит итоговый Unitool, список скриншотов |

Статус: **реализовано** (2026-09-21) — библиотека в `/prj/superizer`, Unitool переведён на неё;
шаги 1–14 плана 08 закрыты, что осталось — в 15 §4. Заметки ниже описывают *проект*; там, где код
отошёл от него, единственный источник правды — [15](15-implementation.md).
Ревизия 1 внесена в 01–06, 08, 09; сводка — в 11.
Ревизия D9 (отдельная папка библиотеки) — в 12; начинать с §6 там. Ревизия 2 (пуши, deep links) — в 13. Ревизия 3 (остаток решений) — в 14. Ревизия 4 (покрытие тестами, D43–D46) — в 12 §4.2 и §4.7. Ревизия 5 (`AppManifest`, D47) — в 03.
Ревизия 6 (Home — выбранные приложения, каталог, D48) — в 01, 05 и 15 §6. Ревизия 7 (провижининг: хранение конфига по протоколу ядра, экспорт/импорт файлом, https-ссылки, связка с сервером Unitool, D49–D61) — **план** в 16, код ещё не написан. Открытых решений, блокирующих шаги 1–7, нет. Версии библиотек взяты из рабочих сборок этого окружения
(`unitool` и `agentiz/mobile-client`): Kotlin 2.4.10, Compose MP 1.11.1, AGP 8.13.2,
Ktor 3.4.0, kotlinx-serialization 1.9.0, coroutines 1.10.2.
