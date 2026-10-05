# 01 — Модель и протокол `/v1`

> Часть задания [remote-progress](README.md). Источник истины для кода — схемы zod в
> `protocol/src/` и фикстуры в `protocol/fixtures/` (D234). Этот файл описывает, что в них должно
> быть. Если код и файл расходятся, сначала правится файл с пометкой «(impl)» и причиной.

## 1. Сущности

| Сущность | ID | Секрет | Кто создаёт | Где живёт |
|---|---|---|---|---|
| **Клиент** — одна установка CLI (ноутбук, NAS, CI) | `c_` + 12 base58 | `cs_` + 32 base58, рождается в CLI | `login` | сервер: `id`, метка, хеш секрета; CLI: `credentials.json` |
| **Устройство** — один Unitool на телефоне | `d_` + 12 base58 | `ds_` + 32 base58, рождается на телефоне | первый пейринг: адрес сервера приходит из QR (D235) | сервер: `id`, платформа, FCM-токен, имя, язык, пояс, заглушка; телефон: `runtime.secrets` |
| **Сессия пейринга** | `pr_` + 12 base58 | `pt_` + 22 base58 — только в QR | `pair` | сервер, до Enter или 10 мин |
| **Грант** — связь «клиент ↔ устройство» | `g_` + 12 base58 | **токен устройства** `dt_` + 32 base58 | скан QR и подтверждение на телефоне | сервер: хеш токена; CLI: сам токен |
| **Задание Remote Progress** | `rp_` + 16 base58, **создаёт CLI** (D221) | — | `new`, `run` | сервер; снимки — на телефонах |
| **Событие задания** | `seq` внутри задания, растёт с 1 | — | CLI | сервер, журнал ≤ 200 |
| **Уведомление** — разовое, без прогресса | `n_` + 16 base58, создаёт CLI | — | `notify` | сервер |
| **Запись ленты** — что увидело устройство | `i_` + 16 base58 | — | сервер: итог задания или уведомление, по одной на устройство | сервер; телефон |

**Base58** — алфавит Bitcoin (`123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz`), байты из
`crypto.getRandomValues`. Число символов в таблице — после префикса.

**Хеши секретов.** SHA-256, hex, сравнение через `timingSafeEqual`. Секреты — 190 бит случайности,
поэтому медленный хеш не нужен.

**Время.** В протоколе все времена — целые секунды эпохи по часам сервера. Каждый ответ, который
несёт времена, несёт и `serverNow`: телефон считает «N назад» от него, а не от своих часов (08 §7.6).
Исключение — поле `at` в событиях от CLI: это часы машины клиента, они идут только в журнал.

## 2. Аутентификация

```
Authorization: Bearer <id>.<secret>
```

- Клиент: `Bearer c_8fK2mQ4xZt7p.cs_…`
- Устройство: `Bearer d_Hq3vN8wLr2Ks.ds_…`

Сервер разрезает строку по первой точке, находит запись по `id` и сравнивает хеш секрета. Ключ
клиента не открывает ручки устройства, и наоборот: на неверный вид — `403`, на неверный секрет или
отозванную запись — `401`.

Без аутентификации открыты только `POST /v1/clients`, `POST /v1/devices` и `GET /healthz`. Против
перебора и мусора их защищают пределы §7.

## 3. Задание: план, состояния, итог

### 3.1. План

Задание создаётся **сразу со всеми шагами** (D211). Шаг — `{id, name, timeoutS?}`:
- `id` — `[a-z0-9-]{1,32}`, уникален в задании;
- `name` — ≤ 40 символов;
- шагов — от 1 до 30.

Вид задания `kind` подсказывает отрисовку:

| `kind` | Что показывает | Откуда |
|---|---|---|
| `steps` | цепочку шагов «как доставка» | шаблоны `steps`, `build`, `deploy`, `release`, `agent` |
| `task` | один шаг и время «идёт 12:40» | шаблон `task`, команда `run` |
| `count` | полосу «312 из 540» | шаблон `count`; у задания один шаг, счётчик — поле `count` |

Счётчик `count` `{current, total?, unit?}` может быть у любого вида. В `steps` он относится к
текущему шагу и сбрасывается при смене шага.

### 3.2. Состояния

Сервер хранит пять состояний:

| `state` | Когда | Финал? |
|---|---|---|
| `waiting` | создано, ни один шаг не начат | нет |
| `running` | начат хотя бы один шаг | нет |
| `succeeded` | `done` | да |
| `failed` | `fail`, упавшая команда, таймаут, незакрытый скрипт | да |
| `cancelled` | `cancel` из CLI | да |

Три производных состояния телефон считает сам чистой функцией `health(progress, now, syncOk)`.
Таблица её случаев — `protocol/fixtures/health-cases.json`; её гоняют тесты сервера и `desktopTest`
приложения (08 §18.2):

| `health` | Условие | Вид |
|---|---|---|
| `ok` | не финал, `now < seenAt + effectiveTimeout / 2` | обычный |
| `quiet` | не финал, `now ≥ seenAt + effectiveTimeout / 2` | янтарный, «нет ответа N мин · таймаут через M» |
| `unverified` | не финал, `now > timeoutAt + 120` **и** последняя синхронизация не удалась | серый, «нет данных с …» (D232) |

Если синхронизация удалась, а `timeoutAt` прошёл, значит сторож ещё не отработал (он ходит раз в
5 с, 02 §6). Такое задание показывается как `quiet`, а не `unverified`.

### 3.3. Шаги

| `status` шага | Когда |
|---|---|
| `pending` | ещё не начат |
| `running` | текущий |
| `done` | начат следующий шаг, или задание закончилось успехом |
| `skipped` | CLI перепрыгнул через шаг (`step ID 4` при текущем 2 → шаг 3 `skipped`) |
| `failed` | задание упало, пока этот шаг был текущим |

Время шага: `startedAt`, `endedAt`. Повторный `step` на текущий шаг — ответ, а не ошибка. Возврат
на уже пройденный шаг: `409 invalid_step`.

### 3.4. Таймаут тишины (D213)

- `timeoutS` задания: CLI по умолчанию ставит 900. Пределы — от 60 (в dev-режиме от 1) до 604800.
- У шага может быть свой `timeoutS` — он действует, пока шаг текущий.
- `effectiveTimeout` = `timeoutS` текущего шага или `timeoutS` задания.
- Любой принятый **ответ** — событие любого типа, в том числе `ping` — ставит `seenAt = now` и
  `timeoutAt = now + effectiveTimeout`.
- Сторож на сервере закрывает задание с `timeoutAt ≤ now`: `failed`, `error.reason = "timeout"`.
- **Льготный период** после старта сервера: у каждого открытого задания
  `timeoutAt = max(timeoutAt, bootAt + effectiveTimeout)` (08 §16.3).

### 3.5. Итог и объяснение (D215, D216)

**Первый итог побеждает.** Событие в закрытое задание получает `409 closed` с текущим итогом.
Сервер всё равно пишет его в журнал с `afterClose: true`. Второго пуша нет.

`error` у `failed`:

```json
{
  "reason": "exit",
  "step": "test",
  "message": "Тесты: код выхода 1",
  "exitCode": 1,
  "command": "./gradlew test",
  "hasLog": true
}
```

| `reason` | Кто ставит | Что обязательно |
|---|---|---|
| `exit` | CLI: команда в `step -- cmd` или `run` вышла не с 0 | `exitCode`, `command`, `message` |
| `error` | CLI: `fail --message …` (ловушка `ERR`, `except` в Python) | `message` |
| `unclosed` | CLI: `fail --if-open --reason unclosed` (ловушка `EXIT`) | `message` |
| `timeout` | сервер, сторож | сервер сам пишет `message`: «Нет ответа 15 мин» |

`message` — до 200 символов. Хвост лога — отдельной ручкой §5.3: 50 строк, ≤ 8 КБ. В снимок и в
пуш он не входит.

## 4. Форма задания

### 4.1. Полная — в HTTP-ответах

```json
{
  "id": "rp_7Kq2xWm9aZp3LtQe",
  "rev": 1042,
  "client": { "id": "c_8fK2mQ4xZt7p", "label": "ноутбук" },
  "title": "Deploy api 2.4",
  "template": "deploy",
  "kind": "steps",
  "state": "failed",
  "steps": [
    { "id": "build",  "name": "Сборка",   "status": "done",    "startedAt": 1790617690, "endedAt": 1790617940 },
    { "id": "test",   "name": "Тесты",    "status": "failed",  "startedAt": 1790617940, "endedAt": 1790618750, "timeoutS": 1800 },
    { "id": "deploy", "name": "Выкладка", "status": "pending" },
    { "id": "check",  "name": "Проверка", "status": "pending" }
  ],
  "current": 1,
  "count": { "current": 312, "total": 540, "unit": "tests" },
  "message": null,
  "error": { "reason": "exit", "step": "test", "message": "Тесты: код выхода 1",
             "exitCode": 1, "command": "./gradlew test", "hasLog": true },
  "timeoutS": 900,
  "seq": 5,
  "createdAt": 1790617682, "updatedAt": 1790618750, "seenAt": 1790618750,
  "timeoutAt": null, "finishedAt": 1790618750,
  "hidden": false
}
```

- `current` — индекс текущего шага с нуля; `null` в `waiting`. В CLI шаги нумеруются с 1.
- `updatedAt` меняется, когда меняется видимое: шаг, счётчик, итог, заметка. `seenAt` меняется от
  любого ответа, в том числе `ping`.
- `hidden` — скрыто на **этом** устройстве; поле есть только в ответах устройству.
- У закрытого задания `timeoutAt` = `null`.

### 4.2. Компактная — в данных пуша

Около 300 байт. Её хватает, чтобы живое приложение обновило строку списка без запроса; полную форму
оно дозапрашивает синхронизацией.

```json
{ "v": 1, "id": "rp_7Kq2xWm9aZp3LtQe", "rev": 1042, "seq": 3,
  "title": "Deploy api 2.4", "state": "running",
  "step": { "i": 1, "n": 4, "name": "Тесты" },
  "count": { "c": 312, "t": 540 },
  "updatedAt": 1790618100, "seenAt": 1790618100, "timeoutAt": 1790619900 }
```

Телефон принимает компактную форму, только если её `rev` больше того, что у него уже есть.

## 5. Ручки клиента (CLI)

Все — `Authorization: Bearer c_….cs_…`, кроме регистрации.

### 5.1. Личность и пейринг

| Метод | Путь | Тело → ответ |
|---|---|---|
| POST | `/v1/clients` | `{label, secretHash}` → `201 {clientId, label}`. Без аутентификации |
| GET | `/v1/clients/me` | → `{clientId, label, createdAt, grants: n}` |
| PATCH | `/v1/clients/me` | `{label}` → `200` |
| DELETE | `/v1/clients/me` | → `204`; все гранты отозваны, открытые задания закрыты как `cancelled` |
| POST | `/v1/pairings` | `{}` → `201 {pairingId, pairingToken, link, expiresAt}` |
| GET | `/v1/pairings/{pairingId}` | → `{state: "open"\|"closed"\|"expired", expiresAt, devices: [PairedDevice]}` |
| DELETE | `/v1/pairings/{pairingId}` | → `204`; сессия закрыта, открытые токены стёрты |

`link` = `unitool://app/progress/pair?s=<PUBLIC_URL, urlencoded>&t=<pairingToken>`. Сервер собирает
его сам, чтобы CLI не угадывал свой публичный адрес за прокси.

`PairedDevice` — то, что CLI забирает опросом:

```json
{ "deviceId": "d_Hq3vN8wLr2Ks", "name": "Pixel 7", "platform": "android",
  "deviceToken": "dt_4mYt…", "pairedAt": 1790617700 }
```

`deviceToken` открытым текстом сервер держит **только пока сессия открыта**: CLI может перечитать
его, если упал между опросом и записью файла. При закрытии или истечении сессии остаётся только
хеш. Опрос: CLI спрашивает раз в 2 с (D227), сервер отвечает сразу.

### 5.2. Телефоны

| Метод | Путь | Тело → ответ |
|---|---|---|
| POST | `/v1/devices/status` | `{deviceTokens: [...]}` → `{devices: [DeviceStatus]}` |
| POST | `/v1/grants/revoke` | `{deviceToken}` → `204`; отключить телефон со стороны CLI |

```json
{ "deviceToken": "dt_4mYt…", "state": "active",
  "deviceId": "d_Hq3vN8wLr2Ks", "name": "Pixel 7", "platform": "android",
  "push": "ok", "lastDeliveryAt": 1790618750, "mutedUntil": null }
```

- `state`: `active` · `revoked` (отключён с телефона или устройство забыто) · `unknown` (токен не
  этого клиента или не существует).
- `push`: `ok` · `invalid` (FCM ответил `UNREGISTERED`: приложение удалено или токен протух) ·
  `none` (устройство без пушей, например desktop).

Токены идут в теле POST, а не в query: query попадает в логи прокси.

### 5.3. Задания

| Метод | Путь | Тело → ответ |
|---|---|---|
| POST | `/v1/progress` | `CreateProgress` → `201 {progress, targets}`; тот же `id` от того же клиента → `200` и существующее (идемпотентно) |
| POST | `/v1/progress/{id}/events` | `[Event]` → `200 {lastSeq, state, timeoutAt, serverNow}`; в закрытое → `409 closed` |
| PUT | `/v1/progress/{id}/log` | `text/plain`, ≤ 8 КБ → `204`; повтор заменяет |
| GET | `/v1/progress/{id}` | → `{progress, events}` — для `show` |

`CreateProgress`:

```json
{
  "id": "rp_7Kq2xWm9aZp3LtQe",
  "targets": ["dt_4mYt…", "dt_9QaZ…"],
  "title": "Deploy api 2.4",
  "template": "deploy",
  "kind": "steps",
  "timeoutS": 900,
  "steps": [
    { "id": "build",  "name": "Сборка" },
    { "id": "test",   "name": "Тесты", "timeoutS": 1800 },
    { "id": "deploy", "name": "Выкладка" },
    { "id": "check",  "name": "Проверка", "timeoutS": 300 }
  ],
  "at": "2026-09-28T17:48:02Z"
}
```

`targets` в ответе — результат по каждому токену: `[{deviceToken, result: "ok"|"revoked"|"unknown"}]`.
Задание создаётся, даже если ни один токен не подошёл: тогда `show` работает, а CLI печатает
предупреждение. Адресаты запоминаются в задании, и все следующие пуши, в том числе от сторожа, идут
им.

`Event` — у каждого `seq` и `at`:

| `type` | Поля | Действие | Пуш |
|---|---|---|---|
| `step` | `step` — `id` шага; `name?` — новое имя | сделать текущим; предыдущие `running` → `done`, перепрыгнутые `pending` → `skipped`; `waiting` → `running`. С `name` шаг ещё и переименовывается (`--steps 3` сначала даёт «Шаг 1…3») | да, сразу |
| `count` | `current`, `total?`, `unit?` | счётчик | не чаще раза в 30 с на задание, последнее значение уходит обязательно |
| `note` | `level: info\|warn\|error`, `message` | в журнал | нет |
| `ping` | — | только продлевает таймаут, в журнал не пишется | никогда |
| `finish` | `outcome: succeeded\|failed\|cancelled`, `message?`, `error?` | итог | да, сразу, со звуком |

- Повтор `seq` ≤ `lastSeq` молча пропускается: так досылается очередь CLI (03 §6). Пачка
  применяется по порядку `seq`.
- **Пропуски `seq` допустимы**: очередь могла потерять событие. Сервер пишет в журнал `gap` и
  применяет дальше.
- Событие с неизвестным `type` → `400`, и пачка не применяется целиком.

### 5.4. Разовое уведомление

| Метод | Путь | Тело → ответ |
|---|---|---|
| POST | `/v1/notices` | `{id, targets, title?, body, level}` → `202 {id, targets}`; тот же `id` → `200` |

- `level`: `info` · `ok` · `warn` · `error`.
- `body` — текст уведомления, до 400 символов. Без `title` он же становится заголовком пуша, а
  телом — метка клиента (§8.4): «✓ Бэкап готов / домашний NAS» читается лучше, чем наоборот.
- `title` — до 80 символов, по желанию.

## 6. Ручки устройства (приложение)

Все — `Authorization: Bearer d_….ds_…`, кроме регистрации.

| Метод | Путь | Тело → ответ |
|---|---|---|
| POST | `/v1/devices` | `{secretHash, platform, pushToken?, name, locale, timeZone, appVersion}` → `201 {deviceId}`. Без аутентификации |
| PUT | `/v1/devices/me` | те же поля без `secretHash` → `200`; при каждом старте и при ротации FCM-токена |
| DELETE | `/v1/devices/me` | → `204`; «Забыть устройство»: гранты отозваны, лента удалена |
| POST | `/v1/pairings/claim` | `{pairingToken}` → `200 {client: {id, label}, grantId}`; повтор тем же устройством → тот же грант |
| POST | `/v1/pairings/preview` | `{pairingToken}` → `{client: {label}, expiresAt}` — для экрана «Подключить „ноутбук“?» до подтверждения. Тикет в теле, а не в query: query оседает в журналах прокси |
| DELETE | `/v1/me/clients/{clientId}` | → `204`; отключить CLI с телефона |
| GET | `/v1/me/sync?cursor=<rev>` | → `Sync` (§6.1) |
| GET | `/v1/me/progress/{id}` | → `{progress, events, log?, serverNow}` — деталь |
| POST | `/v1/me/progress/{id}/hide` | → `204`; скрыть задание на этом устройстве |
| POST | `/v1/me/inbox/read` | `{ids: [...]}` или `{all: true}` → `{unread}` |
| PUT | `/v1/me/mute` | `{durationS}` или `{off: true}` → `{mutedUntil}` |

- `platform`: `android` · `ios` · `desktop` · `web`.
- `pushToken` может отсутствовать: desktop синхронизируется опросом, пушей у него нет.
- `timeZone` — IANA, например `Europe/Moscow`; по нему сервер пишет «18:05» в тексте пуша.
- `locale` — `ru` или `en`; другой язык сервер понимает как `en`.
- `durationS` — одно из `3600`, `18000`, `36000`, `604800`. Другое → `400`: набор фиксирован,
  чтобы тест и экран не разошлись. `mutedUntil = now + durationS` по часам **сервера**.

### 6.1. `Sync`

```json
{
  "cursor": 1043,
  "serverNow": 1790618760,
  "progress": [ { "...": "полная форма §4.1, изменённые после cursor" } ],
  "inbox": [
    { "id": "i_3Fq…", "rev": 1043, "kind": "result", "level": "error",
      "title": "✕ Deploy api 2.4 — упало на «Тесты»", "body": "Тесты: код выхода 1",
      "client": { "id": "c_8fK2mQ4xZt7p", "label": "ноутбук" },
      "progressId": "rp_7Kq2xWm9aZp3LtQe", "createdAt": 1790618750,
      "readAt": null, "silenced": false }
  ],
  "removed": { "progress": ["rp_…"], "inbox": [] },
  "unread": 3,
  "device": { "name": "Pixel 7", "mutedUntil": null, "push": "ok" },
  "clients": [ { "id": "c_8fK2mQ4xZt7p", "label": "ноутбук", "pairedAt": 1790617700,
                 "lastSeenAt": 1790618750, "open": 0 } ],
  "more": false
}
```

- `cursor=0` — первая синхронизация: всё, что есть в пределах хранения.
- В одной странице — до 200 заданий и 500 записей. `more: true` — сразу запросить следующую.
- `removed` — скрытое на этом устройстве и удалённое по сроку хранения.
- `clients` и `device` приходят целиком в каждом ответе: они маленькие.
- `silenced` — запись создана, пока устройство было заглушено (D228).
- У записи вида `notice` вместо `progressId` — `noticeId`. По нему ссылка `notice?id=n_…` из пуша
  находит запись.
- Текст записи ленты сервер собирает на языке устройства **в момент создания** и не переводит
  повторно.

`rev` — один растущий счётчик на весь сервер. Его повышает любое изменение, которое видит
устройство: задание, запись ленты, прочитанность, скрытие, заглушка, грант.

## 7. Ошибки и пределы

```json
{ "error": { "code": "closed", "message": "progress is already failed",
             "state": "failed", "error": { "reason": "timeout" } } }
```

| HTTP | `code` | Когда |
|---|---|---|
| 400 | `invalid` | тело не проходит схему; `message` называет поле |
| 401 | `unauthorized` | нет ключа, неверный секрет, запись отозвана |
| 403 | `forbidden` | ключ не того вида; задание чужого клиента |
| 404 | `not_found` | нет такого задания, сессии или гранта |
| 409 | `closed` | событие в закрытое задание (в журнал оно всё равно записано) |
| 409 | `invalid_step` | нет такого шага; возврат на пройденный |
| 410 | `expired` | сессия пейринга истекла или закрыта (claim, preview) |
| 413 | `too_large` | тело больше 64 КБ, лог больше 8 КБ |
| 429 | `rate_limited` | предел превышен, есть `Retry-After` |

Пределы на клиента:
- 10 запросов в секунду, всплеск до 50;
- 50 открытых заданий;
- 30 уведомлений в минуту;
- 20 телефонов.

Регистрация клиентов и устройств — не больше 10 в час с одного IP. Длины — §3.1, §3.5, §5.4.

## 8. Пуш: форма сообщения FCM

Одно сообщение на каждое устройство-адресат. Отправляется через `push-gateway` (D226) телом
FCM HTTP v1. Заглушенному устройству не отправляется ничего (D228).

### 8.1. Ход задания

```json
{ "message": {
    "token": "<FCM-токен устройства>",
    "notification": {
      "title": "Deploy api 2.4 — Тесты (2/4)",
      "body": "▰▰▱▱ · ноутбук · 21:05"
    },
    "data": {
      "schemaVersion": "1",
      "appId": "progress",
      "link": "unitool://app/progress/task?id=rp_7Kq2xWm9aZp3LtQe",
      "title": "Deploy api 2.4 — Тесты (2/4)",
      "body": "▰▰▱▱ · ноутбук · 21:05",
      "silent": "true",
      "data": "{\"v\":1,\"id\":\"rp_7Kq2xWm9aZp3LtQe\",\"rev\":1042,…}"
    },
    "android": {
      "priority": "high",
      "ttl": "3600s",
      "collapse_key": "rp_7Kq2xWm9aZp3LtQe",
      "notification": {
        "tag": "unitool://app/progress/task?id=rp_7Kq2xWm9aZp3LtQe",
        "channel_id": "unitool.progress",
        "event_time": "2026-09-28T18:05:50Z",
        "notification_priority": "PRIORITY_LOW"
      }
    },
    "apns": {
      "headers": { "apns-collapse-id": "rp_7Kq2xWm9aZp3LtQe", "apns-priority": "10",
                   "apns-expiration": "1790622350" },
      "payload": { "aps": { "thread-id": "progress", "interruption-level": "passive" } }
    }
} }
```

### 8.2. Итог и разовое уведомление

Отличия от хода:
- `data.silent` = `"false"`;
- `android.notification.channel_id` = `unitool.results`, без `notification_priority`;
- `ttl` = `86400s`;
- `aps.interruption-level` = `active`, плюс `aps.sound` = `default`.

У итога `tag` и `apns-collapse-id` — **те же, что у хода**: итог заменяет уведомление хода, и в шторке
не висят сразу «Тесты 2/4» и «упало» (D203).

`data.data` у итога — компактная форма §4.2 плюс `"inboxId": "i_…"`. У разового уведомления —
`{"v":1,"noticeId":"n_…","inboxId":"i_…","rev":1044}`: получив его, живое приложение просто
синхронизируется.

У разового уведомления:
- `link` = `unitool://app/progress/notice?id=n_…`;
- `tag` — эта ссылка;
- `apns-collapse-id` — `n_…`.

### 8.3. Зачем каждое поле

- **`tag` = `link`.** Так же тегирует `AndroidNotifier`, поэтому уведомление заменяется одинаково,
  кто бы его ни нарисовал: Firebase (процесс в фоне или мёртв) или хост (процесс на экране). Номер
  уведомления у Firebase — 0, и `AndroidNotifier` переходит на 0 (04 §7.2).
- **`silent: "true"` у хода.** Когда Unitool на экране, Firebase уведомление не рисует и отдаёт данные
  хосту. `PushRouter` при `silent` уведомление не рисует, а данные отдаёт мини-аппу: строка списка
  обновится. Если бы ход не был тихим, хост рисовал бы каждый шаг в канале со звуком.

  Цена: пока Unitool открыт на Android, уведомление хода в шторке не обновляется. Итог его заменит.
  Лечит это T1.
- **`event_time` = `updatedAt`.** Время в шапке уведомления на Android — момент изменения, а не
  доставки (08 §9.3).
- **`collapse_key`.** Пока телефон без сети, Firebase хранит только последнее сообщение задания.
- **`interruption-level: passive`.** На iOS ход не будит экран и не звучит; итог — `active`.
- **`notification_priority: PRIORITY_LOW`** дублирует низкую важность канала: на случай, если канал
  ещё не создан.
- **`priority: high` у хода.** Сообщение заканчивается видимым уведомлением, поэтому Firebase не
  понизит приоритет (08 §9.2).

### 8.4. Тексты

Собирает сервер из шаблонов `server/src/push/texts.{ru,en}.ts`, в поясе устройства. Тесты
сверяют каждый случай с фикстурой `protocol/fixtures/push-texts.json`.

| Случай | Заголовок (ru) | Тело (ru) |
|---|---|---|
| создано | `Deploy api 2.4 — ждёт начала` | `4 шага · ноутбук · 20:48` |
| шаг, `steps` | `Deploy api 2.4 — Тесты (2/4)` | `▰▰▱▱ · ноутбук · 21:05` |
| шаг со счётчиком | `Deploy api 2.4 — Тесты (2/4)` | `312/540 · ноутбук · 21:05` |
| `count` | `Импорт заказов — 312/540` | `▰▰▰▰▰▰▱▱▱▱ 58% · NAS · 21:05` |
| `task` | `Бэкап фото — идёт` | `с 20:48 · NAS · 21:05` |
| готово | `✓ Deploy api 2.4 — готово` | `<message>` или `за 17 мин` · `ноутбук` |
| упало (`exit`, `error`) | `✕ Deploy api 2.4 — упало на «Тесты»` | `<error.message> · ноутбук` |
| упало (`timeout`) | `✕ Ночной отчёт — нет ответа 15 мин` | `шаг «Выгрузка» · NAS` |
| упало (`unclosed`) | `✕ Deploy api 2.4 — скрипт не закрыл задание` | `шаг «Выкладка» · ноутбук` |
| отменено | `Deploy api 2.4 — отменено` | `ноутбук` |
| уведомление без `title` | `✓ Бэкап готов` — сам текст; знак `✓`, `⚠`, `✕` по `level`, у `info` без знака | метка клиента: `домашний NAS` |
| уведомление с `title` | `✓ <title>` | `<body> · <метка>` |

Шкала `▰▱` — 4 знака на `steps`, если шагов ≤ 8; иначе 10 знаков пропорционально. На `count` — всегда
10 знаков. Время в конце тела — `updatedAt` в поясе устройства, `HH:mm`. Для iOS это единственное
место, где видно время изменения, а не доставки.

## 9. Фикстуры (`protocol/fixtures/`)

| Файл | Что | Кто читает |
|---|---|---|
| `progress-*.json` | полная форма: `waiting`, `running-steps`, `running-count`, `task`, `succeeded`, `failed-exit`, `failed-error`, `failed-timeout`, `failed-unclosed`, `cancelled`, `hidden` | zod (сервер, CLI), Kotlin |
| `compact-*.json` | компактная форма | zod, Kotlin |
| `sync-*.json` | `Sync`: первая страница, страница с `more`, с `removed`, с заглушкой | zod, Kotlin |
| `push-*.json` | готовые сообщения FCM для каждого случая §8.4 на ru и en | тесты рассыльщика; Kotlin — разбор `data` |
| `push-texts.json` | вход (задание, язык, пояс) → заголовок и тело | тесты рассыльщика |
| `health-cases.json` | задание, `now`, `syncOk` → `health` | `protocol` (TS), Kotlin |
| `events-*.json` | пачки событий и ожидаемое задание после них: пропуски, повторы, закрытое | тесты движка |
| `errors.json` | тела ошибок §7 | CLI, Kotlin |

Правило: новое поле в протоколе — сначала фикстура, потом схема, потом код с обеих сторон. У набора
фикстур есть `fixtures/VERSION` и `SHA256SUMS`. Unitool копирует их скриптом
`scripts/sync-progress-fixtures.sh`, а тест приложения сверяет сумму (04 §9).
