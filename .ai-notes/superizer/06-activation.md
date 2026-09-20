# 06 — Активация: QR, промокоды, скрытые приложения

Всё в `host` (`cx.m42.superizer.host.activation`), модель — в `core`. Приложение ничего из
этого не импортирует (§12–13).

## Модель (`core`)

```kotlin
/** What every activation path boils down to: which app, with what config. */
data class Activation(val appId: AppId, val config: AppConfig = AppConfig.Empty, val unlock: Boolean = true)

sealed interface ActivationResult {
    data class Success(val activation: Activation) : ActivationResult
    /** A code that drives the host itself, not an app (D20). The shell handles these; apps never see them. */
    data class HostCommand(val command: Command) : ActivationResult
    data class Rejected(val reason: Reason) : ActivationResult
    enum class Reason { Malformed, UnsupportedSchema, UnknownApp, UnknownCode, Expired }
    enum class Command { OpenServiceMenu }
}
```

## QR payload

Схема из спецификации, версия 1:

```json
{ "schemaVersion": 1, "type": "app_activation", "appId": "calculator", "config": { "mode": "scientific" } }
```

`QrPayloadParser.parse(text): ActivationResult`:
- не JSON → `Malformed`; `schemaVersion != 1` → `UnsupportedSchema`; `type != "app_activation"` →
  `Malformed`; `appId` не в registry → `UnknownApp`; `config` отсутствует → `Empty`.
- Формат в поле `appId` проверяется `AppId`-инвариантом (kebab-case).

Компактный вариант для длинных QR (позже): `unitool://activate?a=calculator&c=<base64url json>` —
тот же парсер, другой вход. В MVP парсер принимает и JSON-текст, и этот URL, чтобы deep link и QR
были одним кодом.

**Источник текста в MVP (D7):** поле «Вставить содержимое QR» на экране Activate + deep link.
Камера — отдельный шаг: `expect fun scanQr(): String?` в `host` с actual через CameraX + ML Kit
на Android, `null` на desktop/wasm (там остаётся текстовое поле). Контракт от этого не меняется.

## Промокоды

```kotlin
interface PromoCodeResolver { suspend fun resolve(code: String): ActivationResult }

/** MVP: a table shipped with the host. Normalises case and dashes so "test 2026" == "TEST-2026". */
class LocalPromoCodes(private val codes: Map<String, Activation>) : PromoCodeResolver
```

Позже — `RemotePromoCodes(network)` с тем же интерфейсом; хост выбирает при сборке. MVP-коды в
Unitool: `TEST-2026` → `test-app` (`{"mode":"promo"}`), `SCI` → `calculator`
(`{"mode":"scientific"}`), `SERVICE` → `HostCommand(OpenServiceMenu)`. Зарезервированных app id
нет (D20): команды хоста — отдельная ветка результата, и shell матчит `enum`, а не строку.

## ActivationService

```kotlin
class ActivationService(
    private val registry: AppRegistry,
    private val promo: PromoCodeResolver,
    private val unlocks: UnlockStore,
    private val handler: AppHandler,
    private val events: MutableSharedFlow<SuperizerEvent>,
) {
    suspend fun fromQr(text: String): ActivationResult
    suspend fun fromPromo(code: String): ActivationResult
    suspend fun fromDeepLink(url: String): ActivationResult

    /** Unlock (if hidden), emit Activated(source), launch. A broken config inside a valid activation surfaces as ConfigRejected (D18), not as Rejected here. */
    suspend fun apply(activation: Activation, source: String): Result<AppSession>
}
```

Поток: `QR / promo / deep link → ActivationService → Activation → (unlock) → AppHandler.launch(appId,
config) → AppRuntime → App`. Приложение получает только `config.mode == "scientific"`.

## Скрытые приложения (D8)

```kotlin
/** Which hidden apps this device has unlocked. Host storage, key "superizer.unlocked" = JSON array of ids. */
class UnlockStore(private val storage: StorageService) {
    val unlocked: StateFlow<Set<AppId>>
    suspend fun unlock(id: AppId); suspend fun lock(id: AppId)
}
```

- All Apps и drawer фильтруют по `registry.visible(unlocked)`.
- `handler.launch` скрытого и не разблокированного приложения **разрешён** только из
  `ActivationService.apply` и Service Menu; прямой `navigation.openApp` из другого приложения
  на такое — `false` + лог. Так скрытость не обходится через Test App.
- Service Menu показывает все и умеет lock/unlock — чтобы проверять сценарий без перестановки.

## Deep links

Единая точка входа для всех ссылок — `ActivationService.fromDeepLink(url)` — и для тапа по
уведомлению тоже (D26, 13). Доставка через `PendingRoute` (consume-once `StateFlow`, как
`Push.route` в agentiz): ссылка может прийти до первой композиции, при холодном старте из
уведомления, или во время работы (`onNewIntent`). Приоритет при старте: ссылка → снимок сессии →
Home (05).

Формы ссылок:

| Ссылка | Что делает |
|---|---|
| `unitool://activate?a=<id>&c=<base64url json>` | активация с конфигом (QR-эквивалент) |
| `unitool://app/<id>` | открыть приложение с `AppConfig.Empty` |
| `unitool://app/<id>/<path>?k=v` | путь, зарегистрированный приложением через `ctx.deepLink(path, toConfig)`; `toConfig(params)` → конфиг |
| `unitool://app/<id>/<неизвестный путь>` | открыть с `Empty` + событие `DeepLinkUnmatched` — мягкая деградация, как незнакомый `type` в agentiz |
| web: `?activate=<urlencoded>` / `?link=<urlencoded unitool://…>` | то же, из `window.location.search` |
| desktop: `--activate=<…>` / `--link=<…>` | то же, из аргументов |

Скрытое и не разблокированное приложение по `unitool://app/<id>…` **не** открывается (D25):
разблокирует только `activate` с валидным payload или промокод. Это отличие между «ссылка знает
секрет» и «ссылка просто называет id».

- Android: `intent-filter` на `unitool://activate` и `unitool://app/<id>/<path>`; `MainActivity`
  передаёт `intent.data` в `superizer.activation.fromDeepLink()` в `onCreate`/`onNewIntent`.
  Доставка — через `StateFlow<String?>` + `consume()`, как `Push.route` в agentiz: ссылка может
  прийти до первой композиции.
- Web: `?activate=<urlencoded>` из `window.location.search` при старте.
- Desktop: аргумент командной строки `--activate=<…>` (для тестов руками).

Пути объявляются в `manifest.deepLinks` (D47), а обработчики — `ctx.deepLink(path, toConfig)`
в `setup`; расхождение → `SetupMismatch`. `fromDeepLink` матчит по манифесту, поэтому Service Menu
показывает, какие ссылки приложение обслуживает, ещё до его запуска. `ctx.deepLink` регистрирует
пути под `unitool://app/<id>/…`; `fromDeepLink`
сначала ищет их, затем `activate`.

## Что проверяет Test App

Открытие с QR-текстом, с промокодом, из Service Menu с произвольным JSON, из deep link — и в
каждом случае Test App показывает **одинаковый** экран «config = {…}». Отсутствие поля «откуда»
на этом экране — намеренное, это и есть §12–13.
