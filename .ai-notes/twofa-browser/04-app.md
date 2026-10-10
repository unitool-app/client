# 04 — Телефон: Superizer 0.7.0 и 2FA

> Шаги 0 (Superizer), 1 (`kit.otplink`) и 4 (2FA) в 00-TASK. Контракт — 01.

## 1. Шаг 0 — Superizer 0.7.0 / contract 6 (D445–D447)

### 1.1. Почему без Superizer не обойтись

Мини-аппа видит `runtime.push` (`PushService`: `enabled`, `requestPermission`, `subscribe`,
`messages`). Id устройства, его секрет и токен видит только оболочка (`PushPort`,
`StoredDevice` в `PushRouter.kt`). Значит, 2FA не может:
- сказать серверу «запросы этой привязки — мне»;
- сама сходить на сервер от имени устройства.

Давать приложениям `ds_` нельзя: это ключ от всего устройства. Нужен ключ поменьше — от пары
«устройство × приложение».

Вторая причина — момент вопроса о пушах. По push-opt-in `Alerts`-приложение спрашивает при первом
открытии. Для 2FA это значит спросить каждого, хотя браузер привяжут немногие (вопрос 193).

### 1.2. Что добавить

`core` (`cx.m42.superizer`):

```kotlin
public enum class PushUse { None, Silent, Alerts,
    /** Visible pushes, but only after the app itself calls [PushService.optIn] (contract 6). */
    OnRequest }

public interface PushService {
    // … как было
    /** Puts this app into `used`: the system question, a token, the server (D446). True if notifications will show. */
    public suspend fun optIn(): Boolean
    /** Takes it out again; the host deletes the app's address on the server. */
    public suspend fun optOut()
    /**
     * This app's address on the push server (D445): created on first call, then cached. Null with no
     * server, no transport, or while the app is not in `used`.
     */
    public suspend fun address(): PushAddress?
}

public class PushAddress(public val server: String, public val id: String, secret: String) {
    /** `Bearer pa_….pas_…` — the only way the secret leaves this object. */
    public fun authorization(): String
    override fun toString(): String = "PushAddress($id)"
}
```

`host`:
- `PushGate`: для `OnRequest` вход в `used` — только `optIn`, выход — `optOut`, удаление с Home и
  сброс приложения (D35). Каталог, запуск, восстановление из бэкапа в `used` не вводят.
- `optIn` = то же, что сейчас делает вход `Alerts`-приложения: `activate()`, системный вопрос,
  регистрация с новым `apps`.
- `address()`: устройство зарегистрировано (иначе ждать регистрации до 15 с, потом null) →
  секрет `pas_` из `HostSecrets.forApp(appId)` или новый (32 base58) → `POST /devices/me/addresses`
  → id в SafePrefs `host.push.address.<appId>`.
- `optOut` → `DELETE /devices/me/addresses/{appId}`, секрет стирается.
- Устройство перерегистрировалось (401 → новый `d_`) → адреса на сервере пропали каскадом;
  кэш адресов сбрасывается, следующий `address()` заводит новый. Привязки 2FA при этом потеряны,
  2FA узнаёт об этом по `401` на `pa_` (§3.6).
- `DeviceApi` += `createAddress`, `deleteAddress`. `NetworkDeviceApi` — по 01 §8.1.
- Реестр: `push = OnRequest` требует `minHostContract = 6`; секретному приложению — нельзя, как и
  другим значениям, кроме `None`.
- Service Menu → «Уведомления»: у приложения с адресом показывать `pa_` (первые 6 символов).
- Попутно: `SuperizerShell` — `filterNotNull()` перед `collectLatest` маршрута (ловушка из памяти
  2FA: маршрут ссылки отменяет `onLaunch`, если тот засыпает). Здесь это важно: тап по пушу
  открывает 2FA с холодного старта.

Тесты Superizer: `PushGateTest` (`OnRequest` не входит в `used` ни каталогом, ни запуском, ни
бэкапом; `optIn`/`optOut`), `AddressTest` (поддельный `DeviceApi`: создание, кэш, повтор после
перерегистрации, `optOut` удаляет, `toString` без секрета), `AppRegistryTest` (контракт 6). Версия
0.7.0, `SuperizerContract` = 6, CHANGELOG. Выпуск — как обычно для Superizer, перед пушем Unitool.

## 2. Шаг 1 — `kit.otplink` в `:unitool-kit`

`unitool-kit/src/commonMain/kotlin/cx/m42/unitool/kit/otplink/`:

| Файл | Что |
|---|---|
| `LinkKeys.kt` | `LinkKeys.derive(myPriv, theirPub, s, B, P, pairingId)` → `k_req`, `k_ans` (01 §4); отказ на нулевой X25519 |
| `Envelope.kt` | `seal(k, m, ad)`, `open(k, x, ad)` (01 §6) на `ChaCha20Poly1305` кита |
| `CanonicalJson.kt` | писатель и строгий разборщик (01 §5). Если sudo-approve уже положила свой в `kit.json` — взять его |
| `OtpRequest.kt` | `R`: разбор, проверки 01 §5 (кроме окна `nonce`: его держит 2FA) |
| `OtpAnswer.kt` | сборка `A` и `sealedA` |
| `LinkUri.kt` | разбор `unitool://app/2fa/link?…` (01 §3) |
| `CodeChoice.kt` | какой шаг TOTP отдать по D443: `choose(nowMs, period) → (counter, validFrom, validTo)` |

Всё в `explicitApi()`, ключи — в `SecretBytes`, `toString` без содержимого. Тесты —
`desktopTest`, читают `../unitool-browser/testdata/vectors.json` (01 §10).

## 3. Шаг 4 — 2FA (`apps/twofa`, `AppId("2fa")`)

### 3.1. Манифест

- `minHostContract = 6`, `push = PushUse.OnRequest`.
- `deepLinks = setOf("add", "link", "browser")`.
- `networkHosts += "unitool.m42.cx"` (справочно).
- `TwoFaConfig` += `link: String?`, `request: String?`: ссылка с холодного старта должна дойти до
  страницы.
- Обработчик ссылок не пишет `link?…` в журнал и снапшот: в ней `s` (01 §3).

### 3.2. Новые файлы (`cx.m42.apps.twofa.browser`)

| Файл | Что |
|---|---|
| `BrowserLinkStore.kt` | `runtime.secrets` ключ `browser.links`: `[{linkId, kReq, kAns, browser, createdAt, sites: {origin → accountId}, nonces: [..64], denials}]`. Те же правила, что у `AccountStore`: мьютекс, `Locked`/`Unreadable`, при ошибке печати не писать ничего |
| `OtpServer.kt` | HTTP к серверу через `runtime.network` с `PushAddress.authorization()`; ответы 01 §8.3; ошибки → `ServerError.Gone / Conflict / Unauthorized / Offline` |
| `BrowserLinks.kt` | логика без UI: `link(uri)`, `links()`, `unlink(id)`, `inbox()`, `open(req)`, `approve(req, account)`, `deny(req)`. Тестируется без Compose |
| `BrowserPages.kt` | экраны §3.4 |

Ключи привязок, как и аккаунты, лежат в хранилище (`AndroidKeystoreVault` / Secure Enclave) и в
бэкап не попадают: привязка — свойство этого телефона. После восстановления браузеры привязываются
заново.

### 3.3. Привязка

1. Вход: «⋮ → Браузеры → Привязать браузер» → сканер (`services/qr-scanner`) или вставка ссылки;
   либо системная камера открыла `unitool://app/2fa/link?…`.
2. Проверки ссылки: `v = 1`, `srv` == `PushAddress.server` хоста (иначе «браузер настроен на другой
   сервер»), поля по 01 §3.
3. Экран «Привязать браузер «Chrome · Linux»? Он сможет просить у этого телефона коды 2FA. Каждый
   код вы подтвердите здесь, секреты аккаунтов браузер не получит». [Привязать] [Отмена].
4. [Привязать] → `runtime.push.optIn()` (системный вопрос, если ещё не спрашивали) → `address()`.
   Пуши запрещены — привязка всё равно возможна, с предупреждением «без уведомлений вы узнаете о
   запросе, только открыв 2FA». `address()` = null → «нет связи с сервером Unitool», ничего не
   сохранять.
5. `p, P` → ключи (01 §4) → `hello` → `POST /otp/pairings/claim` → запись в `BrowserLinkStore` →
   `p` стёрт.
6. «Ждём подтверждения в браузере…» → `GET /otp/links` раз в 2 с или тихий пуш `otp-link` → `active`
   → «Готово, браузер привязан». Через 10 мин без `active` — запись удаляется, «браузер не
   подтвердил привязку».

### 3.4. Экраны

| Экран | Что |
|---|---|
| **Список аккаунтов** (есть) | если во входящих есть запрос — плашка сверху «Браузер просит код · Chrome · Linux» → экран запроса. Пункт «⋮ → Браузеры» |
| **Браузеры** | по строке на привязку: имя, «привязан 3 окт», «последний запрос 10:42 · IP 203.0.113.7». [Отвязать] (с подтверждением). [Привязать браузер]. Пусто — объяснение в двух фразах и кнопка. На desktop и web (`PushPermission.Unavailable`) — «доступно на телефоне» |
| **Запрос кода** (`unitool://app/2fa/browser?req=or_…`) | см. ниже |

Экран запроса, сверху вниз:
1. «Код для сайта» и `origin` крупно: `github.com` (схема `https://` мелко). IDN — Unicode и под ним
   `xn--…` (D439).
2. «Браузер: Chrome · Linux» (из привязки, D455), «IP 203.0.113.7 — по данным сервера», «та же сеть,
   что телефон» зелёным или «другая сеть» серым. Время запроса и обратный отсчёт до `expiresAt`.
3. **Шаг 1 — число.** «Какое число показывает браузер?» — три кнопки (`n` и два случайных
   различных из 10–99, порядок случайный, D438). Не то → `deny`, экран «Число не совпало. Если вы не
   запрашивали код — отвяжите этот браузер» [Отвязать] [Закрыть].
4. **Шаг 2 — аккаунт.** Запомненный для этого `origin` — выбран. Иначе — подсказки по совпадению
   домена с `issuer`/`name` (`github.com` ↔ «GitHub»), остальные ниже; для незапомненного сайта
   жёлтое «вы ещё не отправляли коды этому сайту». Галочка «Запомнить для github.com» (вкл.).
5. [Отправить код] → `UserPresence.confirm("Отправить код GitHub в браузер")` (D441) → `CodeChoice`
   → `A` → `answer` → «Код отправлен» и закрыть экран через 2 с. [Отклонить] → `deny`.
6. Запрос истёк, отменён или отвечен с другого места → экран говорит об этом и закрывается.

Три отказа подряд по одной привязке (включая «не то число») — плашка «Этот браузер просит коды, а вы
отказываете. Отвязать?».

### 3.5. Входящие и пуш (D444)

- `GET /otp/inbox` — при каждом запуске 2FA, возврате на экран и приходе пуша `otp-request` (данные
  через `runtime.push.messages`). Пуш может потеряться, входящие — нет.
- Каждый `Req` открывается `k_req` своей привязки (01 §5). Не открылся, протух или повтор `nonce` —
  в списке не показывается, `deny` не отправляется (это мог быть мусор от сервера), счётчик в журнале.
- Тап по уведомлению → ссылка `browser?req=…` → экран запроса после замка 2FA.

### 3.6. Ошибки

| Что | Что видит человек |
|---|---|
| `401` на `pa_` | «Связь с сервером сброшена, привязки браузеров потеряны» — список очищается, кнопка «Привязать снова» |
| `410` на `answer` | «Запрос уже не ждёт ответа» |
| `409` на `answer` | «На запрос уже ответили» |
| нет сети | «Нет связи с сервером Unitool», [Повторить] |
| хранилище заперто | как у аккаунтов: сначала разблокировка |

### 3.7. Тексты

`TwoFaStrings` += ru/en для всех экранов §3.3–§3.6. Пример тона: «Браузер «Chrome · Linux» просит код
для github.com. Если это не вы — отклоните».

### 3.8. Скриншоты (`composeApp/.../Screenshots.kt`)

`21-2fa-browsers`, `22-2fa-browser-link`, `23-2fa-request-number`, `24-2fa-request-account`,
`25-2fa-request-wrong-number`. Данные — подделки (`FakeOtpServer`, `FakeSecretVault`).

### 3.9. Тесты

| Тест | Что |
|---|---|
| `kit` `OtpLinkVectorsTest` | все наборы фикстур |
| `BrowserLinksTest` | поддельный сервер: привязка целиком; чужой `srv`; `optIn` отказал → привязка с предупреждением; `address()` null → ничего не записано; `hello` открывается расширением (общая фикстура) |
| `BrowserRequestTest` | открыть `R`; плохой конверт, протухший `iat`, повтор `nonce` — скрыт; неверное число → `deny`; `A.req` = хэш `sealedR`; `CodeChoice` на границе 8 с; запоминание сайта; три отказа → плашка |
| `BrowserStoreTest` | запечатано хранилищем; ключи не в бэкапе; `Unreadable` не перезаписывается |
| `TwoFaPrivacyTest` | канарейка кода и ключей не попадает в журнал, `toString`, снапшот, `saveState` |
| `TwoFaProtectionTest` (есть) | экран запроса за замком; `confirm` перед ответом |
| Скриншоты §3.8 | собираются |

## 4. Готово, когда

- Superizer 0.7.0 собран и проверен его тестами; Unitool на нём, `verify.sh` зелёный;
- `kit.otplink` проходит фикстуры;
- тесты §3.9 зелёные, скриншоты приняты владельцем;
- на эмуляторе: привязка через `-camera-back imagefile:` с QR из расширения, запрос, код вставлен
  (05 §3, проход A).
