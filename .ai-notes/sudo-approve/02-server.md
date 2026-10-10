# 02 — Сервер: `/prj/layers-fw/unitool-server`, слой `app-unitool`

> **Версия:** 1.2 · **Дата:** 2026-10-10 · Шаг 2 в 00-TASK. Контракт — 01 §7–§8. Сервер —
> почтовый ящик: он связывает хэш с устройствами, будит телефон и держит ответ, пока его не заберут.
> Решений о доступе он не принимает (D375).
>
> Состояние на 2026-10-10: репозиторий — один коммит `64796ba` на `main`, дерево чистое. Клиентов
> `c_` нет; `DeviceService.authenticate` отвечает `403 forbidden` на любой `xx_…` кроме `d_…`.

## 1. Правила репозитория, которые здесь важны

Из `AGENTS.md` сервера, читать целиком перед началом:
- README сервера (русский) — контракт. Новые ручки сначала описываются там, раздел «sudo».
- Модель без миграции не меняется: тесты строят БД из миграций (`test/db.ts`).
- API висит на корневом app (`/api/unitool/v1`), не через `adminizerMiddlewares`.
- Формат пуша живёт только в `lib/push/unitoolMessage.ts`, и его тест пинит каждый ключ.
- Общее изменяемое состояние — на глобалах `Symbol.for`.
- Секреты и токены в логах — только `tokenTail`.
- Тесты мокают декораторы `@nodeknit/app-adminizer`.
- Не коммитить и не публиковать без явной просьбы. В этом задании коммиты в локальный `main`
  разрешены (00-TASK §3), пуш — нет.

Команды: `npm test` (vitest) и `npm run build` (`tsc --noEmit`). Оба зелёные — условие каждого
коммита.

## 2. Файлы

Всё в `layers/app-unitool/`. ✚ — новый файл, ✎ — правка.

| Файл | Что |
|---|---|
| ✎ `lib/errors.ts` | `ApiErrorCode` += `conflict` (409), `gone` (410), `no_device` (404) |
| ✎ `lib/ids.ts` | `newClientId()` (`c_` + 12), `CLIENT_ID_PATTERN`, `newSudoRequestId()` (`sr_` + 12), `SUDO_REQUEST_ID_PATTERN`, `KEY_HASH_PATTERN = /^[0-9a-f]{64}$/`, `SUDO_CODE_PATTERN = /^[A-HJ-NP-Z2-9]{4}$/` |
| ✎ `lib/rateLimit.ts` | сообщение ошибки — параметр конструктора (сейчас зашито «too many registrations…») |
| ✎ `lib/schemas.ts` | `registerClientSchema`, `updateClientSchema`, `sudoKeysSchema`, `createSudoRequestSchema`, `answerSudoSchema`, `muteSchema`, `waitQuerySchema` (01 §7) |
| ✚ `models/UnitoolClient.ts` | `unitool_clients`: `id` PK, `label`, `secretHash`, `createdAt`, `lastSeenAt`, `updatedAt` |
| ✚ `models/UnitoolSudoKey.ts` | `unitool_sudo_keys`: `deviceId` FK → devices `ON DELETE CASCADE`, `keyHash`; уникальный `(deviceId, keyHash)`, индекс `keyHash` |
| ✚ `models/UnitoolSudoRequest.ts` | `unitool_sudo_requests`: `id` PK, `clientId` FK cascade, `keyHashes` TEXT (JSON), `request` TEXT NULL, `code`, `state`, `sealed` TEXT NULL, `answeredBy` NULL, `notified` TEXT (JSON списка deviceId), `createdAt`, `expiresAt`, `answeredAt` NULL, `deliveredAt` NULL; индексы `(state, expiresAt)`, `clientId` |
| ✚ `models/UnitoolSudoMute.ts` | `unitool_sudo_mutes`: `deviceId` FK cascade, `clientId` FK cascade, `until`; PK `(deviceId, clientId)` |
| ✚ `migrations/umzug/1791400000002_unitool_clients.ts` … `…05_unitool_sudo_mutes.ts` | по одной на модель; `up`/`down` с тем же типом `QI`, что у соседей |
| ✎ `migrations/index.ts`, ✎ `test/db.ts`, ✎ `index.ts` (`models`) | регистрация четырёх моделей |
| ✚ `services/ClientService.ts` | `register`, `authenticate(authorization)` (по образцу `DeviceService.authenticate`: `Bearer c_….cs_…`, `sameDigest`), `view`, `update`, `remove`, `seen` |
| ✚ `services/SudoService.ts` | вся логика §3; без Express, на вход — проверенные значения |
| ✚ `lib/sudoWaiters.ts` | `Map<requestId, Set<() => void>>` на `Symbol.for('unitool.sudo.waiters')`; `wait(id, ms)`, `wake(id)` |
| ✚ `lib/sudoSweeper.ts` | сторож §4; таймер на `Symbol.for('unitool.sudo.sweeper')`, `start()`/`stop()` идемпотентны |
| ✎ `services/PushService.ts` | вынести из `send()` внутреннюю `deliver(devices, content, {sentBy})`: строка `UnitoolPush`, рассылка по 20, сброс мёртвых токенов. `send()` для MCP работает как раньше |
| ✎ `lib/push/unitoolMessage.ts` | `UnitoolPushContent.interruptionLevel?: 'active' \| 'time-sensitive'` → `aps['interruption-level']` только у видимого пуша (D398) |
| ✎ `lib/apiRouter.ts` | middleware `withClient` (по образцу `withDevice`), ручки 01 §7 **до** catch-all 404; опции `clientRegistrationsPerHour` (30), `sudoRequestsPerMinute` (6), `sudoWaitMaxSeconds` (30) |
| ✎ `index.ts` | `sudoSweeper.start()` в `mount()`; Adminizer: клиенты (без `secretHash`), запросы (только `id`, `clientId`, `code`, `state`, время), ключи и заглушки — только чтение |
| ✎ `mcp/tools.ts` | `unitool.sudoRequests` (protected): последние 50 без `request`/`sealed` — для отладки владельцем |
| ✎ README сервера | раздел «sudo»: модель, ручки, состояния, пределы, приватность; строка в «Чего пока нет» убирается для `c_` |

## 3. `SudoService`

Все методы статические, как у `DeviceService`. Время берётся из `now()` (внедряемое, для тестов).

### 3.1. Устройство

- `setKeys(device, hashes)`: в транзакции удалить строки устройства и вставить новые (уникальные,
  ≤ 32). Вернуть отсортированный список.
- `keys(device)`.
- `inbox(device)`: запросы `pending`, `expiresAt > now`, у которых пересечение `keyHashes` с ключами
  устройства не пусто. Порядок — новые сверху. В `Req.keyHashes` — только пересечение (01 §7.2).
- `get(device, id)`: тот же фильтр по хэшам, но в любом состоянии, пока строка жива. Иначе
  `404 not_found`.
- `answer(device, id, input)`:
  1. найти через `get` (то есть 404, если не мой);
  2. `state != pending` → 409 (`approved`/`denied`) или 410 (`expired`/`cancelled`); `expiresAt ≤ now`
     → перевести в `expired`, 410;
  3. `approve` → `sealed` обязателен (≤ 16 КБ, начинается с `-----BEGIN AGE ENCRYPTED FILE-----`);
     `deny` → `sealed` запрещён;
  4. обновление **условное**: `UPDATE … WHERE id = ? AND state = 'pending'`. Ноль строк — значит
     ответил другой телефон: 409. Так два ответа не перетрут друг друга;
  5. `wake(id)`; тихий `sudo-gone` всем из `notified`, кроме ответившего (§5).
- `mute(device, clientId, until)`: `until ≤ now` удаляет строку; `until > now + 7 сут` → 400.
  Клиент должен существовать (иначе 404).
- `mutes(device)`: живые заглушки с `label` клиента.

### 3.2. Хост

- `create(client, input)`:
  1. предел 6 в минуту на клиента (`RateLimiter`, ключ `clientId`);
  2. устройства = `SELECT DISTINCT deviceId FROM unitool_sudo_keys WHERE keyHash IN (…)`. Пусто →
     `404 no_device`, строка не создаётся;
  3. для каждого хэша из запроса: `pending`-запросов с этим хэшем уже 3 → 429;
  4. вставить строку: `expiresAt = now + ttlSeconds`, `state = pending`;
  5. пуш `sudo` устройствам без живой заглушки от этого клиента (§5). Их список — в `notified`;
  6. вернуть `{id, expiresAt, devices: notified.length}`.
- `poll(client, id, waitSeconds)`:
  1. строка этого клиента, иначе 404;
  2. `pending` и `wait > 0` → `await wait(id, min(wait, 30) * 1000)`, потом перечитать;
  3. `approved` и `sealed != null` → вернуть `sealed`, в той же транзакции `sealed = NULL`,
     `deliveredAt = now` (D386);
  4. вернуть `{state, sealed?}`.
- `notice(client, input)` (1.2, D416): устройства с этими хэшами без живой заглушки от клиента →
  видимый пуш `sudo-notice` (01 §8). Предел — общий с `create` (6/мин на клиента). В БД ничего не
  пишется, кроме строки `UnitoolPush`, как у любого пуша. `no_device` здесь не ошибка: `202 {devices: 0}`.
- `cancel(client, id)`: условное `UPDATE … SET state='cancelled' WHERE state='pending'`, `wake(id)`,
  `sudo-gone` всем из `notified`.

### 3.3. Long-poll (D385)

- `wait(id, ms)` регистрирует `resolve` в наборе и снимает его по таймеру или по `wake`. Если
  клиент закрыл соединение раньше, Express вызывает `req.on('close')`, и ожидание снимается.
- Сервер однопроцессный. Это ограничение записывается в README сервера. Для нескольких процессов
  понадобится Postgres `LISTEN/NOTIFY` — не v1.
- Прокси перед сервером должен держать соединение ≥ 35 с. Это пункт выкладки (00-TASK §5,
  вопрос 114).

## 4. Сторож (D386)

Раз в 60 с (и один раз при старте):
1. `pending` с `expiresAt ≤ now` → `expired`, `wake(id)`, `sudo-gone` по `notified`.
2. У строк с `expiresAt + 60 с ≤ now` обнуляются `request` и `sealed`. Агент к этому времени уже
   отказал по своему дедлайну.
3. Строки старше 7 суток удаляются.
4. Заглушки с `until ≤ now` удаляются.

Сторож не трогает строку, которую в эту же секунду обновляет `answer`: все переходы условные
(`WHERE state = 'pending'`).

## 5. Пуши

- Содержимое — 01 §8. Текст по `locale` устройства: `ru*` → русский, иначе английский. Тексты —
  в `lib/sudoTexts.ts` (две константы, без библиотек).
- Отправка — `PushService.deliver(devices, content, {sentBy: 'sudo'})`. Сбой провайдера не роняет
  `create`: запрос всё равно создан и лежит во входящих. В ответе `devices` = сколько устройств
  провайдер принял.
- Если провайдер не настроен (`pushConfigured() == false`), `create` всё равно работает: `devices = 0`,
  в журнале предупреждение. Так e2e и разработка идут без Firebase (`PUSH_PROVIDER=log`).
- В журнале по пушу — `id` запроса, `code`, число устройств. Хэши — только `tokenTail`.

## 6. Приватность (D386)

- В журналах, ответах Adminizer и MCP нет `request`, `sealed`, `secretHash` и полных хэшей.
- После `exp` + 60 с в БД нет ни `request`, ни `sealed`.
- Открытых ключей и подписей сервер не получает вообще. Тест §7 это доказывает.

## 7. Тесты

Новые файлы рядом с кодом. HTTP-тесты — тем же способом, что `lib/apiRouter.test.ts`: настоящий
`express()` на порту 0, `fetch`, `createTestDb()`, `resetPushProvider(new LogPushProvider())`.

| Файл | Что проверяет |
|---|---|
| `services/ClientService.test.ts` | регистрация, `authenticate`: верный ключ, чужой секрет (401), `d_…` в клиентской ручке (403), битая строка (401); `seen` |
| `services/SudoService.test.ts` | всё из §3 с поддельным `now`: пересечение хэшей, 404 для чужого, 409/410, условное обновление (два `answer` подряд — второй 409), `sealed` один раз, отмена, заглушки, пределы 3/хэш и 6/мин, `no_device` |
| `lib/sudoApi.test.ts` | цикл через HTTP: устройство + клиент → `PUT keys` → `POST requests` → `GET inbox` → `answer` → `GET ?wait=25` **просыпается сразу** (проверка: ответ быстрее 1 с) → повторный `GET` без `sealed`; `wait` истекает по таймеру с `pending`; `DELETE` будит ожидающего |
| `lib/sudoSweeper.test.ts` | истечение, обнуление полей, удаление через 7 суток, снятие заглушек; повторный `start()` не плодит таймеры |
| `lib/push/unitoolMessage.test.ts` ✎ | `interruptionLevel` даёт `aps['interruption-level']`; у тихого пуша его нет; старые ключи без изменений |
| `lib/sudoNotice.test.ts` | `POST /sudo/notices`: пуш `sudo-notice` держателям хэшей, без команды; заглушённые не получают; предел общий с `create`; чужой ключ `d_…` → 403 |
| `lib/sudoPush.test.ts` | через `LogPushProvider`: `data` = `{"v":1,"kind":"sudo","id":…}`, канал `unitool.sudo`, `ttl` = остаток, `collapseKey`; заглушённое устройство не получает; `sudo-gone` после ответа уходит всем, кроме ответившего |
| `lib/sudoPrivacy.test.ts` | прогнать цикл с фикстурами 01 §9, потом выгрузить **все** таблицы и захваченные логи; не найти `ssh-ed25519`, `ecdsa-sha2`, `ssh-rsa`, `AAAA`, `BEGIN SSH SIGNATURE`, полный хэш, `cs_`; после сторожа нет и `BEGIN AGE` |
| `test/sudoFixtures.test.ts` | копия фикстур совпадает с `SHA256SUMS` исходника (если он рядом) |

## 8. Готово, когда

- `npm test` и `npm run build` зелёные, новые тесты из §7 есть и проходят.
- README сервера описывает раздел «sudo» так, что по нему можно написать клиента без этой заметки.
- `npm run dev` и `curl` по примеру из раздела «sudo» README сервера проходят руками: устройство,
  клиент, ключи, запрос, ответ, long-poll.
- Коммиты в локальный `main` сервера по одному на подшаг (ошибки и id, модели и миграции,
  `ClientService`, `SudoService` и ручки, пуши, сторож, README). Не пушить.
