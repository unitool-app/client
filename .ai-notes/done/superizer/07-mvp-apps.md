# 07 — Приложения MVP

## Calculator (`apps/calculator`, id `calculator`)

Перенос существующего без изменения арифметики.

- `Calculator.kt` — как есть (`CalculatorState`, `Key`, `press`, `format`). Тест `CalculatorTest`
  переезжает вместе с ним.
- `CalculatorScreen.kt` — как есть, минус `AppScaffold` (его даёт контейнер) и минус `menu/onOpenSettings`
  параметры. `strings.*` → `calculatorStrings()` (свои таблицы). `hapticActionComplete()` →
  `LocalAppRuntime.current.haptics.tick()`.
- `CalculatorConfig(mode: String = "standard")`. `"scientific"` добавляет ряд `x²`, `√`, `1/x`
  (три унарные операции — ~15 строк в `press`) и подзаголовок «Инженерный» в `chrome`. Этого
  достаточно, чтобы конфиг был **видимым** результатом активации, а не текстом на экране.
- `saveState`/`restore` (D16): `entry`/`accumulator` как JSON — переживают смерть процесса и
  переход в другое приложение. Отдельно `runtime.storage`: последний результат (`"last"`), чтобы
  показать StorageService на чём-то, что живёт дольше сессии.
- Аналитика без шума: только `screen("calculator")`
  при запуске и `event("error")` при делении на ноль.
- `metadata`: title ru/en, icon `Named("calculator")` (новый глиф в пакете), category "Tools".
  Конвертер, напротив, везёт свою иконку как `AppIcon.Path(…)` (D29) — чтобы оба пути были в MVP.

## Currency Converter (`apps/currency-converter`, id `currency-converter`)

Показывает `NetworkService` и кэш.

- Источник курсов: `https://api.frankfurter.app/latest?from=USD` (без ключа, CORS открыт — нужен
  для wasm). Резерв: `https://open.er-api.com/v6/latest/USD`. URL — в `ConverterConfig.ratesUrl`,
  т.е. подменяем конфигом, а не кодом.
- `ConverterConfig(base: String = "USD", favorites: List<String> = listOf("EUR","RUB","GBP"), ratesUrl: String = …)`.
- Экран: сумма (клавиатура переиспользует `KeySurface`-подход, но проще — `BasicTextField` из
  unstyled), выбор «из/в» из favorites, результат, строка «курс на <дата> · из кэша/сети».
- Логика — чистая: `Rates(base, date, Map<String, Double>)`, `convert(amount, from, to)`; тест
  без сети. Загрузка — `RatesRepository(network, storage, logger)`: сеть → успех → в storage;
  ошибка → из storage + пометка «офлайн»; ничего нет → экран ошибки с «Повторить».
- Тест экземпляра с `FakeNetwork.respond(url) { 200, json }` и с `FakeNetwork.fail(url)` —
  показывает, что приложению всё равно, откуда курс.
- `metadata`: category "Finance", icon `AppIcon.Path("M…")` — своя иконка без правки хоста (D29).
- «Офлайн» показывается по `network.online == false` сразу, а не после первой ошибки (D32);
  `RatesRepository` при офлайне не ходит в сеть, берёт кэш.

## Test App (`apps/test-app`, id `test-app`, `hidden = true`)

Скрытое приложение-стенд. Одна прокручиваемая страница из секций-карточек (тот же вокабуляр,
что Settings):

| Секция | Что показывает / что проверяет |
|---|---|
| Identity | `runtime.appId`, `host.name/version/build/platform` |
| Config | `config` как pretty-JSON. **Без источника.** Проверка §11–13 |
| Lifecycle | лента `SuperizerEvent` для этого appId с момента `Created`; текущее `runtime.lifecycle`; счётчик `onBackground`/`onForeground`; кнопка «Close me» → `navigation.close()` |
| State | поле текста, которое идёт в `saveState`/`restore`; после сворачивания и убийства процесса текст на месте (D16) |
| Storage | счётчик запусков (`launches` в storage, инкремент в `onLaunch`), кнопка «Clear» |
| Network | кнопка «Ping» → `GET https://api.frankfurter.app/latest?from=USD&to=EUR` → статус/время |
| Analytics | кнопка «Send event» → `analytics.event("test", …)`; Service Menu покажет в буфере |
| Navigation | «Open Calculator (scientific)» → `openApp(AppId("calculator"), {mode: scientific})`; «Open unknown» → показывает `NavigationError.UnknownApp` (D22); «Open Settings» |
| Services | список `host.services` и результат `service(key)` для незарегистрированного ключа — null (D19) |
| Locale | текущий `langTag`, свои две таблицы строк |
| Haptics | кнопка «Tick» |
| Push | `enabled`, «Request permission», поле темы + Subscribe/Unsubscribe, список `subscriptions`, последнее `PushMessage` (topic, data, link, время). Проверяется через «Simulate push» в Service Menu без Firebase (13 §7) |
| Auth | вызовы заглушки и ответ (`session == null`) |
| Veto | переключатель «Block close» → `onCloseRequested()` возвращает false → хост показывает диалог |

Deep link `echo` (`ctx.deepLink("echo") { params -> TestConfig(label = params["x"]).encode() }`) —
цель для `PushRoutingTest`, web-smoke `?link=` и `adb shell am start` (13 §7).

`TestConfig(mode: String = "default", label: String? = null)`, спека с `fallbackToDefault = false`
(D18): битый JSON из Service Menu даёт `ConfigRejected` и экран ошибки хоста, а не тихий default —
второй путь, который калькулятор не показывает. Промокод `TEST-2026` даёт `mode = "promo"`;
QR — что угодно; Service Menu — произвольный JSON. Все три пути должны дать один и тот же экран,
различающийся только содержимым `Config`.

## Общее для всех трёх

- Один публичный класс на модуль (`XApp`), остальное `internal`.
- Свои `Strings`-таблицы (ru/en), выбор через `runtime.locale.pick`.
- Каждое наследует `AppContractTest` из `testing` (D21).
- testTag по D39: `calculator:display`, `calculator:key-=`, `currency-converter:amount`,
  `currency-converter:from`, `test-app:section-<name>`. Тесты и web-мост ищут по тегам.
- Нет `AppScaffold`, нет `SystemBackHandler` на корне (это делает shell). У конвертера
  внутренней навигации нет; у Test App — нет. Многоэкранное приложение появится позже (Weather в
  §21 — хороший кандидат) и проверит `chrome.canGoBack`.
- Тесты в `desktopTest` каждого модуля с `superizer:testing`; ни один тест приложения не
  поднимает `composeApp`.
