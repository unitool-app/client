# 02 — Сервер: `/prj/layers-fw/unitool-server`, слой `app-unitool`

> Шаги 0 (адреса) и 2 (всё остальное) в 00-TASK. Контракт — 01 §8–§9. Сервер — почтовый ящик: он
> связывает браузер с адресом 2FA, будит телефон и держит ответ, пока его не заберут. Решений он не
> принимает и ничего не расшифровывает (D433).
>
> Состояние на 2026-10-10: `main`, коммиты `64796ba`, `781d815`, дерево чистое. Есть только
> устройства и пуши. Клиентов `c_`, long-poll и `deliver()` нет; их же планирует sudo-approve
> (02 там). Кто строится первым, тот их и делает, второй переиспользует (D450). Миграции занимают
> `1791400000000`–`…02`; новые начинаются с `…03` (номера в sudo-approve/02 устарели).

## 1. Правила репозитория, которые здесь важны

`AGENTS.md` сервера — прочитать целиком. Главное:
- README сервера — контракт. Новые ручки сначала описываются там, раздел «Коды 2FA в браузер».
- Модель без миграции не меняется: тесты строят БД из миграций (`test/db.ts`). Новая модель — ещё
  и в `models` слоя (`index.ts`) и в `test/db.ts`.
- API — на корневом app под `/api/unitool/v1`, обёртка `handle()`, ошибки `ApiError`, схемы zod в
  `lib/schemas.ts`.
- Формат пуша живёт только в `lib/push/unitoolMessage.ts`.
- Общее изменяемое состояние (ожидающие long-poll) — на глобалах `Symbol.for`.
- Секреты и токены в логах — только `tokenTail`.
- Команды: `npm test`, `npm run build`. Оба зелёные — условие каждого коммита.

## 2. Файлы

Всё в `layers/app-unitool/`. ✚ — новый, ✎ — правка.

| Файл | Что |
|---|---|
| ✎ `lib/errors.ts` | коды `conflict` (409), `gone` (410), `no_link` (404) — если sudo-approve ещё не добавила |
| ✎ `lib/ids.ts` | `newClientId` (`c_`+12), `newAddressId` (`pa_`+12), `newPairingId` (`op_`+12), `newPairingToken` (`pt_`+22), `newLinkId` (`l_`+12), `newOtpRequestId` (`or_`+16) и шаблоны к ним |
| ✎ `lib/rateLimit.ts` | сообщение — параметр; ключ — любая строка, а не только IP |
| ✎ `lib/schemas.ts` | схемы 01 §8: `registerClient`, `updateClient`, `createAddress`, `claimPairing`, `createOtpRequest`, `answerOtp`, `waitQuery`; `sealed` — base64url, предел длины |
| ✎ `lib/auth.ts` ✚ | `authenticate(header)` → `{kind:'device'\|'client'\|'address', row}` по префиксу; `withDevice`, `withClient`, `withAddress`. `DeviceService.authenticate` переезжает сюда, 403 на чужой вид |
| ✎ `lib/push/deliver.ts` ✚ | `deliver({deviceIds, appId, title, body, link, data, silent, collapseKey})` — вынесено из `PushService.send`; MCP-инструмент зовёт его же |
| ✚ `lib/longPoll.ts` | ожидатели по ключу (`or_…`, `op_…`) на `Symbol.for('unitool.longPoll')`: `wait(key, ms)`, `wake(key)` |
| ✚ `models/UnitoolClient.ts` | `unitool_clients`: `id`, `label`, `secretHash`, `createdAt`, `lastSeenAt` |
| ✚ `models/UnitoolAddress.ts` | `unitool_addresses`: `id`, `deviceId` FK cascade, `appId`, `secretHash`, `createdAt`; уникальный `(deviceId, appId)` |
| ✚ `models/UnitoolOtpPairing.ts` | `unitool_otp_pairings`: `id`, `clientId` FK cascade, `tokenHash`, `state`, `linkId` NULL, `expiresAt` |
| ✚ `models/UnitoolOtpLink.ts` | `unitool_otp_links`: `id`, `clientId` FK cascade, `addressId` FK cascade, `state`, `phonePub`, `hello` TEXT NULL, `createdAt`, `lastUsedAt`, `lastIp` |
| ✚ `models/UnitoolOtpRequest.ts` | `unitool_otp_requests`: `id`, `linkId` FK cascade, `sealed` NULL, `answer` NULL, `state`, `ip`, `createdAt`, `expiresAt`, `answeredAt`, `deliveredAt`; индексы `(state, expiresAt)`, `linkId` |
| ✚ `migrations/umzug/1791400000003_…` и дальше | по одной на модель (клиенты — если ещё нет) |
| ✚ `services/AddressService.ts` | `create(device, appId, secretHash)`, `remove(device, appId)` |
| ✚ `services/OtpService.ts` | привязка, запросы, входящие (§3) |
| ✚ `services/OtpWatchdog.ts` | сторож (§4) |
| ✎ `lib/apiRouter.ts` | ручки 01 §8, до catch-all 404 |
| ✎ `index.ts` слоя | модели в `models`, админка: клиенты, адреса, привязки, запросы — только чтение и без секретов (§6) |
| ✎ `README.md` | раздел «Коды 2FA в браузер» и «Адреса приложений»; строку «Dockerfile нет» убрать — он есть |

## 3. `OtpService`

### 3.1. Привязка

- `createPairing(client)`: закрыть открытую сессию клиента; `pt_` → хеш в строку; вернуть
  `{pairingId, token, expiresAt = now + 600}`.
- `claim(address, {token, phonePub, hello})`:
  - найти открытую сессию по хешу токена (неверный, истёкший или уже занятый — один и тот же
    `410 gone`, чтобы токены нельзя было перебирать по ответам);
  - `address.appId` должен быть `2fa`;
  - создать привязку `pending` с `phonePub`, `hello`; сессия → `claimed`, `wake(op)`;
  - вернуть `{linkId, client:{label}}`.
- `pollPairing(client, op, wait)`: только своя сессия; `open` — ждать; `claimed` — отдать `link`.
- `accept(client, linkId)`: только своя `pending`; → `active`; прежняя `active` привязка клиента
  удаляется (D456); `hello` обнуляется; тихий пуш `otp-link` на адрес.
- Сессия `claimed` без `accept` за 10 мин — привязка удаляется сторожем.

### 3.2. Запрос

- `createRequest(client, {linkId, sealed}, ip)`:
  - привязка своя и `active`, иначе `410 gone`;
  - пределы 01 §8.5; ожидающий запрос этой привязки → `cancelled`, `wake`;
  - строка `pending`, `expiresAt = now + 120`; `link.lastUsedAt`, `link.lastIp`;
  - `deliver()` на устройство адреса, текст по 01 §9 (`lib/otpTexts.ts`, ru/en по `locale`
    устройства). Пуш — не транзакция: упал пуш — запрос всё равно ждёт во входящих;
  - вернуть `{id, expiresAt}`.
- `poll(client, id, wait)`: своя строка; `pending` — ждать до `wait` или `wake`; `answered` —
  отдать `sealed` ответа, обнулить `answer`, `deliveredAt = now`.
- `cancel(client, id)`: `pending` → `cancelled`.
- `inbox(address, ip)`: `pending` и не истёкшие запросы по привязкам адреса; `sameNetwork = (req.ip == ip)`.
- `answer(address, id, body)`: только запрос своей привязки; `pending` → `answered`/`denied`,
  `wake(id)`; не `pending` → `409`/`410`.

### 3.3. Long-poll

Как в sudo-approve/02 §3.3: ожидатель на `Symbol.for`, таймер на `wait`, `wake` из `answer`/`cancel`/
сторожа. Один процесс — этого достаточно, сервер один. Обрыв соединения клиента снимает ожидатель.

## 4. Сторож (раз в 10 с)

- `pending` с `expiresAt < now` → `expired`, `wake`.
- `sealed` и `answer` обнуляются у всего, что старше `expiresAt + 60`; `ip` — тоже.
- Строки запросов без полезной нагрузки — удаляются через 7 дней.
- Сессии привязки старше `expiresAt` → `expired`; `pending` привязки старше 10 мин — удаляются.

## 5. Адреса (шаг 0)

- `POST /devices/me/addresses {appId, secretHash}`: `appId` — `^[a-z0-9-]{1,32}$`; повтор для того
  же `(deviceId, appId)` меняет `secretHash`, id прежний.
- `DELETE /devices/me/addresses/{appId}`: удаляет адрес; каскад — привязки и запросы.
- `deliver()` на адрес: устройство адреса, и только если `appId ∈ device.apps` (D429). Иначе
  запрос остаётся во входящих, пуша нет; в ответ браузеру это не сообщается.
- `authenticate` для `pa_…`: адрес + его устройство живы, `lastSeenAt` устройства обновляется.

## 6. Приватность (D452)

- Админка: клиенты (`id`, `label`, даты), адреса (`id`, `deviceId`, `appId`), привязки (`id`,
  `state`, даты), запросы (`id`, `state`, даты). Без `sealed`, `answer`, `hello`, `phonePub`, хешей.
- Логи: id, состояние, длина `sealed`. Никогда — `sealed`, `pt_`, `cs_`, `pas_`, `phonePub`.
- `TRUST_PROXY` должен быть выставлен в развёртывании (NPM → контейнер), иначе `ip` — адрес прокси.
  Проверка — тест в `smoke` и строка в README.

## 7. Тесты (vitest, `createTestDb()`)

| Тест | Что |
|---|---|
| `auth.test.ts` | три вида Bearer; чужой вид — 403; неверный секрет — 401; удалённый адрес — 401 |
| `AddressService.test.ts` | создание, повтор меняет секрет, удаление каскадом, удаление устройства |
| `OtpService.pairing.test.ts` | полный цикл; неверный / истёкший / занятый токен — одинаковый 410; claim адресом не `2fa` — 403; accept чужой привязки — 404; вторая привязка вытесняет первую |
| `OtpService.request.test.ts` | создание, пределы, новый отменяет старый, ответ кодом, отказ, двойной ответ — 409, ответ на истёкший — 410, `sealed` выдаётся один раз |
| `longPoll.test.ts` | ожидание прерывается ответом, отменой, сторожем; таймаут; обрыв |
| `OtpWatchdog.test.ts` | вычистка по времени (поддельные часы) |
| `deliver.test.ts` | пуш только при `appId ∈ apps`; текст и `collapseKey`; провайдер `log` |
| `apiRouter.otp.test.ts` | ручки целиком через supertest, коды ошибок 01 §8 |
| `privacy.test.ts` | канарейка в `sealed`/`hello`/секретах не появляется в логах, ответах админки и ошибках |

## 8. Готово, когда

- все ручки 01 §8 есть и описаны в README сервера;
- тесты §7 зелёные, `npm run build` чистый;
- `PUSH_PROVIDER=log`: в логе видно уведомление с текстом 01 §9 и без сайта;
- фикстуры не нужны: сервер `sealed` не открывает.
