# 01 — Контракт: id, QR, ключи, запрос, ответ, API, пуш, фикстуры

> Часть задания [twofa-browser](README.md). Это контракт между тремя частями: расширением,
> сервером и 2FA. Меняется только целиком, в одном шаге: заметка → фикстуры → `kit.otplink` →
> расширение → сервер → 2FA. Если код и заметка расходятся, сначала правится заметка с пометкой
> «(impl)» и причиной.

## 1. Слова

| Слово | Что это |
|---|---|
| **клиент** | одна установка расширения в одном профиле браузера, `c_` |
| **адрес** | пара «устройство × приложение» на сервере, `pa_` (D445). Для этой задачи — 2FA на одном телефоне |
| **привязка** | связь клиента и адреса с общими ключами `k_req`/`k_ans`, `l_` |
| **сессия привязки** | 10 минут, пока QR на экране, `op_` + токен `pt_` |
| **запрос** | «дай код для этого сайта», `or_` |
| **`R`, `A`** | открытый текст запроса и ответа, до шифрования |
| **`sealedR`, `sealedA`** | они же в конверте (§6), base64url без `=` |

## 2. Идентификаторы и секреты

Base58 — алфавит Bitcoin, байты из `crypto.getRandomValues` / `secureRandomBytes`. Хеши секретов —
SHA-256 hex, сравнение постоянного времени (`sameDigest` сервера).

| Что | Формат | Кто рождает | Где живёт |
|---|---|---|---|
| клиент | `c_` + 12 | сервер | сервер; расширение `storage.local` |
| секрет клиента | `cs_` + 32 | расширение | расширение; сервер — хеш |
| адрес | `pa_` + 12 | сервер | сервер; хост Superizer (по приложению) |
| секрет адреса | `pas_` + 32 | хост Superizer | `HostSecrets.forApp(appId)`, запечатан; сервер — хеш |
| сессия привязки | `op_` + 12 | сервер | сервер, 10 мин |
| токен привязки | `pt_` + 22 | сервер | только в QR и в строке сервера (хеш) |
| привязка | `l_` + 12 | сервер | сервер; обе стороны |
| запрос | `or_` + 16 | сервер | сервер; обе стороны |

Аутентификация — как в remote-progress/01 §2: `Authorization: Bearer <id>.<secret>`. Сервер режет по
первой точке и по префиксу id выбирает таблицу. `c_` не открывает ручки адреса и наоборот (`403`),
неверный секрет или удалённая запись — `401`.

## 3. QR привязки (D434)

```
unitool://app/2fa/link?v=1&srv=<https-url>&pt=<pt_…>&op=<op_…>&b=<B>&s=<s>&n=<имя браузера>
```

- `srv` — адрес сервера, urlencoded. Телефон принимает **только** адрес, совпадающий с
  `pushServer` хоста (`https://unitool.m42.cx`). Иначе — «этот браузер настроен на другой сервер».
  Адрес приложения заведён на сервере хоста, другого сервера 2FA не знает.
- `b` — 32 байта X25519, base64url. `s` — 32 случайных байта, base64url.
- `n` — имя браузера, ≤ 40 символов, по умолчанию `"<Браузер> · <ОС>"` («Chrome · Linux»),
  человек может поменять в настройках расширения до показа QR.
- Длина — около 230 символов; QR версии 10, уровень M. Рисует расширение (03 §5).
- Обработчик ссылки в 2FA не пишет её в журнал и не кладёт в снапшот: `s` — секрет на 10 минут
  (то же правило, что для `add?uri=`, idea/06 шаг 0).

## 4. Ключи привязки (D434, D435)

```
th    = SHA-256( "unitool-2fa-link/v1" ‖ 0x00 ‖ B ‖ P ‖ utf8(op) )
okm   = HKDF-SHA256( ikm = X25519(p, B), salt = s,
                     info = "unitool-2fa-link/v1 keys" ‖ 0x00 ‖ th, L = 64 )
k_req = okm[0:32]
k_ans = okm[32:64]
```

- `X25519` выдаёт все нули (малая подгруппа) → привязка отвергается на обеих сторонах.
- `hello` (телефон → расширение, через сервер):
  ```json
  {"label":"Pixel 7","platform":"android","v":1}
  ```
  `helloSealed = seal(k_ans, canonical(hello), ad = "unitool-2fa/hello" ‖ 0x00 ‖ utf8(op))`.
- Расширение открывает `helloSealed`. Не открылось — `DELETE /otp/links/{id}` и «привязка не
  удалась, попробуйте ещё раз». Открылось — `POST /otp/links/{id}/accept`, сохраняет `k_req`,
  `k_ans`, `linkId`, `hello.label`, стирает `b` и `s`.
- Телефон после claim хранит `k_req`, `k_ans`, `linkId`, имя браузера из QR и стирает `p`.

## 5. Запрос `R` (D436, D438)

Канонический JSON: ключи по алфавиту, без пробелов, UTF-8, целые числа без `+` и ведущих нулей,
строки с экранированием только `"`, `\` и управляющих символов. Тот же писатель, что
`CanonicalJson` в sudo-approve (03 §4): кто строится первым, кладёт его в `kit.json`, второй
переиспользует.

```json
{"iat":1791612345,"n":47,"nonce":"q2Vd…16 байт b64url","origin":"https://github.com",
 "ua":"Chrome 141 · Linux","v":1}
```

| Поле | Правило |
|---|---|
| `origin` | `scheme://host[:port]` активной вкладки, без пути и query. Только `https:`, кроме `http://localhost` и `http://127.0.0.1` для отладки. Длина ≤ 253 + 16 |
| `n` | 10–99, `getRandomValues`, без смещения (отбрасывание) |
| `nonce` | 16 случайных байт |
| `iat` | часы браузера, секунды |
| `ua` | ≤ 64 символа, из `navigator.userAgentData` / `runtime.getBrowserInfo` |

`sealedR = seal(k_req, canonical(R), ad = "unitool-2fa/req" ‖ 0x00 ‖ utf8(linkId))`, ≤ 2 КБ.

Телефон, открыв `R`, проверяет:
1. конверт открылся ключом этой привязки (иначе запрос не от этого браузера — в списке не показывается,
   в журнал `bad_seal`);
2. `v == 1`, поля по таблице;
3. `|iat − now| ≤ 300`;
4. `nonce` не встречался в этой привязке за 10 мин (кольцо последних 64).

## 6. Конверт

```
seal(k, m, ad)  = base64url( nonce12 ‖ ChaCha20-Poly1305_k(nonce12, m, ad) )
open(k, x, ad)  = разбор, проверка тега; любая ошибка — одна и та же «не открылось»
```

`nonce12` — случайный. Один ключ шифрует десятки сообщений, вероятность повтора пренебрежима.
Доменные `ad` разные для `hello`, `req`, `ans`: конверт одного вида не выдать за другой.

## 7. Ответ `A` (D437, D443)

```json
{"account":"GitHub · xziy","code":"492039","decision":"code",
 "req":"<hex sha256 байтов sealedR после base64url-декодирования>","v":1,
 "validFrom":1791612330,"validTo":1791612360}
```

`sealedA = seal(k_ans, canonical(A), ad = "unitool-2fa/ans" ‖ 0x00 ‖ utf8(linkId) ‖ 0x00 ‖ utf8(requestId))`.

- `account` — подпись, которую человек видит в 2FA (issuer · name), ≤ 64 символа. Нужна, чтобы
  расширение написало, чей код вставлено.
- `validFrom/validTo` — границы шага TOTP по часам телефона. Код выбирается по D443.
- Отказ — без конверта: тело ответа `{decision:"deny"}`, `reason` в нём нет.

Расширение принимает ответ, только если:
1. `sealedA` открылся `k_ans` с `ad` этой привязки и **этого** `requestId`;
2. `A.req` совпал с хэшем `sealedR`, который расширение отправило само и держит в памяти;
3. `code` — 6–8 цифр.

Иначе — «ответ не прошёл проверку», в журнал расширения `bad_answer` (без тела).

## 8. HTTP API (`/api/unitool/v1`)

Ошибки — `{error:{code,message}}` сервера. Новые коды: `gone` (410), `conflict` (409),
`no_link` (404).

### 8.1. Адрес (хост Superizer, `Bearer d_….ds_…`)

| Метод | Путь | Тело → ответ |
|---|---|---|
| POST | `/devices/me/addresses` | `{appId, secretHash}` → `201 {addressId}`. Повтор с тем же `appId` заменяет секрет, id прежний |
| DELETE | `/devices/me/addresses/{appId}` | → `204`; привязки адреса удаляются, ожидающие запросы отменяются |

Удаление устройства (`DELETE /devices/me`) каскадом удаляет адреса.

### 8.2. Клиент — расширение (`Bearer c_….cs_…`)

| Метод | Путь | Тело → ответ |
|---|---|---|
| POST | `/clients` | без auth: `{label, secretHash}` → `201 {clientId}`. Лимит по IP, как у `/devices` |
| PATCH | `/clients/me` | `{label}` → `200` |
| DELETE | `/clients/me` | → `204`; привязки удалены |
| POST | `/otp/pairings` | `{}` → `201 {pairingId, token, expiresAt}`. Открытая сессия этого клиента закрывается |
| GET | `/otp/pairings/{op}?wait=0..25` | → `{state: open\|claimed\|expired, expiresAt, link?: {linkId, phonePub, hello}}`. Держит до `wait` с, пока `open` |
| DELETE | `/otp/pairings/{op}` | → `204` |
| POST | `/otp/links/{l}/accept` | → `200`; `pending` → `active`. Старая активная привязка клиента удаляется (D456) |
| DELETE | `/otp/links/{l}` | → `204` |
| GET | `/otp/links` | → `{links: [{id, state, createdAt, lastUsedAt}]}` |
| POST | `/otp/requests` | `{linkId, sealed}` → `201 {id, expiresAt}`; `410 gone` — привязки нет или не `active`; `429` |
| GET | `/otp/requests/{or}?wait=0..25` | → `{state: pending\|answered\|denied\|expired\|cancelled, sealed?}`; `sealed` отдаётся один раз и стирается |
| DELETE | `/otp/requests/{or}` | → `204`; `cancelled` |

### 8.3. Адрес — 2FA (`Bearer pa_….pas_…`)

| Метод | Путь | Тело → ответ |
|---|---|---|
| POST | `/otp/pairings/claim` | `{token, phonePub, hello}` → `201 {linkId, client: {label}}`; `410 gone` — истёк или уже занят |
| GET | `/otp/links` | → `{links: [{id, state, clientLabel, createdAt, lastUsedAt, lastIp}]}` |
| DELETE | `/otp/links/{l}` | → `204` |
| GET | `/otp/inbox` | → `{requests: [Req…], serverNow}` — `pending` моих привязок |
| GET | `/otp/requests/{or}` | → `Req` в любом состоянии, пока строка жива |
| POST | `/otp/requests/{or}/answer` | `{decision:"deny"}` или `{decision:"code", sealed}` → `200`; `409` — уже отвечен; `410` — истёк или отменён |

`Req = {id, linkId, sealed, ip, sameNetwork, createdAt, expiresAt, state}`. `sameNetwork` — IP
запроса браузера равен IP этого вызова `/otp/inbox`.

### 8.4. Состояния

```
сессия привязки:  open ──claim──▶ claimed ──(accept)──▶ закрыта      open/claimed ──10 мин──▶ expired
привязка:         pending ──accept──▶ active ──DELETE──▶ (удалена)    pending ──10 мин──▶ (удалена)
запрос:           pending ──answer code──▶ answered ──выдан──▶ (sealed стёрт)
                  pending ──answer deny──▶ denied
                  pending ──DELETE / новый запрос──▶ cancelled
                  pending ──120 с──▶ expired
```

### 8.5. Пределы (D442)

| Что | Предел |
|---|---|
| `POST /clients` | 30 в час с IP (общий лимит с `/devices`) |
| `POST /otp/pairings` | 5 в час на клиента |
| `POST /otp/pairings/claim` | 10 в час на адрес; неверный токен — тот же `410`, что и истёкший |
| `POST /otp/requests` | 6 в минуту на клиента, 1 ожидающий на привязку (новый отменяет старый) |
| `sealed` запроса / ответа | ≤ 2 КБ / ≤ 1 КБ |
| `wait` | ≤ 25 с; не больше 4 висящих long-poll на клиента |

## 9. Пуш (D444)

Через `deliver()` сервера (02 §5) на адрес → устройство:

| Поле | Значение |
|---|---|
| `appId` | `2fa` |
| `link` | `unitool://app/2fa/browser?req=or_…` |
| `silent` | `false` |
| title | ru «Браузер просит код 2FA» / en «A browser asks for a 2FA code» (по `locale` устройства) |
| body | `«<label клиента> · IP <ip>»` |
| `collapseKey` / tag | `otp-<linkId>`: новый запрос той же привязки заменяет старое уведомление |
| `data` | `{"kind":"otp-request","id":"or_…"}` |

Без сайта, без числа, без кода: уведомление видно на заблокированном экране.

Тихий пуш `{"kind":"otp-link","id":"l_…"}` уходит, когда браузер принял привязку: 2FA обновляет
список, если открыта.

## 10. Фикстуры (`unitool-browser/testdata/`)

Генератор — `testdata/gen.py` на Python `cryptography` (X25519, HKDF, ChaCha20Poly1305) и `hashlib`.
Это третья, независимая реализация. Запуск: `python3 -I testdata/gen.py > testdata/vectors.json`.
Случайность в генераторе фиксирована (ключи и nonce заданы в файле), результат детерминирован и
закоммичен.

| Набор | Что | Кто читает |
|---|---|---|
| `link` | `b, B, p, P, s, op` → `th, k_req, k_ans` | vitest, `kit.otplink` |
| `hello` | `hello`, `nonce12` → `helloSealed` | оба |
| `req` | `R` (объект) → `canonical(R)` байты → `sealedR`, `sha256(sealedR)` | оба |
| `ans` | `A` → `sealedA` с `linkId`, `requestId` | оба |
| `canonical` | 12 пар «объект → байты»: юникод, экранирование, вложенность, большие целые | оба |
| `bad` | испорченный тег, чужой `ad`, нулевой X25519, лишнее поле в `R`, `iat` вне окна | оба: ожидаемая ошибка |
| `code` | секрет, время → какой шаг и код по D443 (граница 8 с) | `kit.otplink`, 2FA |

Kotlin читает их в `desktopTest` `:unitool-kit`, TypeScript — в vitest расширения. Правка протокола
без перегенерации — красный тест на обеих сторонах.
