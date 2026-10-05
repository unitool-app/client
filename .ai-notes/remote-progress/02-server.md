# 02 — `progress-server` на Express

> Часть задания [remote-progress](README.md). Протокол — [01](01-model-and-protocol.md). Здесь —
> как сервер устроен внутри, что хранит, как шлёт пуши и чем проверяется.

## 1. Репозиторий `/prj/unitool-progress`

Новый git-репозиторий, npm workspaces (D223):

```
unitool-progress/
  package.json              # workspaces, общие скрипты: build, test, lint, verify
  tsconfig.base.json        # strict, ES2023, NodeNext
  protocol/                 # @unitool-progress/protocol — private, собирается внутрь cli и server
    src/ids.ts              # base58, префиксы, генерация
    src/schemas.ts          # zod: Progress, CompactProgress, Event, Sync, InboxItem, ошибки…
    src/health.ts           # health(progress, now, syncOk) — чистая функция (01 §3.2)
    src/texts.ts            # шкала ▰▱ и форматирование длительностей — общие для сервера и CLI
    fixtures/               # 01 §9, VERSION, SHA256SUMS
    test/
  server/                   # progress-server — private, Docker-образ
    src/
      index.ts              # чтение конфигурации, запуск, остановка по SIGTERM
      app.ts                # createApp({db, sender, clock, config}) → express.Application
      config.ts
      db/                   # sqlite.ts (обёртка node:sqlite), migrations/*.sql, repo-*.ts
      auth.ts               # Bearer <id>.<secret>, два вида ключей
      limits.ts             # пределы 01 §7, in-memory token bucket
      routes/               # clients.ts, pairings.ts, devices.ts, progress.ts, notices.ts, me.ts, dev.ts
      engine/               # apply.ts — применение событий; steps.ts; finish.ts
      push/                 # compose.ts — сообщение FCM; texts.ru.ts, texts.en.ts; sender.ts; dispatcher.ts
      sweeper.ts            # таймауты, отложенные пуши счётчика, очистка по сроку
    test/
    Dockerfile
  cli/                      # 03
  e2e/                      # 05 §3
  scripts/verify.sh         # сборка, типы, lint, все тесты, e2e; отчёт как у Unitool
  .github/workflows/ci.yml
```

Зависимости сервера во время работы: `express@5` и `zod`. Больше ничего: SQLite встроен
(`node:sqlite`), HTTP-клиент — встроенный `fetch`, криптография — `node:crypto`. Для разработки:
`typescript`, `vitest`, `supertest`, `@types/*`, `eslint`. Это тот же стиль «читается за один присест»,
что у `push-gateway`.

**`node:sqlite`** в Node 24 печатает `ExperimentalWarning`. Запуск идёт с
`--disable-warning=ExperimentalWarning`. Весь доступ к базе — через `db/sqlite.ts` (`run`, `get`,
`all`, `tx`), поэтому замена на `better-sqlite3` трогает один файл.

## 2. Приложение

`createApp` собирает Express из частей, которые тесты подменяют:

```ts
export interface Deps {
  db: Db                    // открытая база с применёнными миграциями
  sender: PushSender        // GatewaySender | FakeSender
  clock: Clock              // () => seconds; в тестах — ручные часы
  config: Config
  random?: RandomSource     // для ID и секретов; в тестах — детерминированный
}
export function createApp(deps: Deps): { app: express.Express; sweeper: Sweeper }
```

- `express.json({ limit: '64kb' })`. Для `PUT …/log` — `express.text({ limit: '8kb' })`.
- Каждая ручка проверяет тело схемой zod из `protocol`. Ошибка — `400 invalid` в форме 01 §7.
- Один обработчик ошибок в конце цепочки. Неизвестная ошибка — `500` без стека в теле; стек идёт в
  лог.
- Журнал запросов — одна JSON-строка на запрос: метод, путь без query, статус, время, `clientId` или
  `deviceId`. **Никогда** — заголовок `Authorization`, тела, FCM-токены. Токены в пути и query
  маскируются по префиксу (`dt_4m…`).
- `trust proxy` — из конфигурации: IP нужен для пределов регистрации.
- `GET /healthz` → `{status: "ok", version, db: "ok", push: "gateway"|"fake"}`.

## 3. Хранение

Миграции — пронумерованные `.sql`, применяются при старте в транзакции. Версия — `PRAGMA
user_version`. `journal_mode=WAL`, `foreign_keys=ON`, `busy_timeout=5000`.

```sql
CREATE TABLE counters (name TEXT PRIMARY KEY, value INTEGER NOT NULL);  -- 'rev'

CREATE TABLE clients (
  id TEXT PRIMARY KEY, label TEXT NOT NULL, secret_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL, seen_at INTEGER, revoked_at INTEGER
);

CREATE TABLE devices (
  id TEXT PRIMARY KEY, secret_hash TEXT NOT NULL, platform TEXT NOT NULL,
  push_token TEXT, push_state TEXT NOT NULL DEFAULT 'none',   -- ok | invalid | none
  name TEXT NOT NULL, locale TEXT NOT NULL, time_zone TEXT NOT NULL, app_version TEXT,
  muted_until INTEGER, rev INTEGER NOT NULL,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, forgotten_at INTEGER
);

CREATE TABLE pairings (
  id TEXT PRIMARY KEY, client_id TEXT NOT NULL REFERENCES clients(id),
  token_hash TEXT NOT NULL UNIQUE, expires_at INTEGER NOT NULL, closed_at INTEGER
);

CREATE TABLE grants (
  id TEXT PRIMARY KEY, client_id TEXT NOT NULL REFERENCES clients(id),
  device_id TEXT NOT NULL REFERENCES devices(id),
  token_hash TEXT NOT NULL UNIQUE,
  token_plain TEXT,                 -- только пока сессия пейринга открыта (01 §5.1)
  pairing_id TEXT REFERENCES pairings(id),
  created_at INTEGER NOT NULL, revoked_at INTEGER, rev INTEGER NOT NULL,
  last_delivery_at INTEGER, last_delivery_result TEXT,
  UNIQUE (client_id, device_id)     -- повторный скан тем же телефоном → тот же грант, новый токен
);

CREATE TABLE progress (
  id TEXT PRIMARY KEY, client_id TEXT NOT NULL REFERENCES clients(id),
  title TEXT NOT NULL, template TEXT, kind TEXT NOT NULL,
  steps_json TEXT NOT NULL, current INTEGER, count_json TEXT,
  state TEXT NOT NULL, message TEXT, error_json TEXT, has_log INTEGER NOT NULL DEFAULT 0,
  timeout_s INTEGER NOT NULL, seq INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, seen_at INTEGER NOT NULL,
  timeout_at INTEGER, finished_at INTEGER,
  push_due_at INTEGER,              -- отложенный пуш счётчика (§5.3)
  rev INTEGER NOT NULL
);
CREATE INDEX progress_timeout ON progress(timeout_at) WHERE timeout_at IS NOT NULL;
CREATE INDEX progress_push_due ON progress(push_due_at) WHERE push_due_at IS NOT NULL;

CREATE TABLE progress_targets (     -- кому шлём это задание; видимость для sync
  progress_id TEXT NOT NULL REFERENCES progress(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL REFERENCES devices(id),
  grant_id TEXT NOT NULL REFERENCES grants(id),
  hidden_at INTEGER, rev INTEGER NOT NULL,
  last_pushed_seq INTEGER, last_pushed_at INTEGER,
  PRIMARY KEY (progress_id, device_id)
);

CREATE TABLE progress_events (
  progress_id TEXT NOT NULL REFERENCES progress(id) ON DELETE CASCADE,
  seq INTEGER NOT NULL, type TEXT NOT NULL, body_json TEXT NOT NULL,
  at_client TEXT, received_at INTEGER NOT NULL, after_close INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (progress_id, seq)
);                                  -- без ping; не больше 200 на задание, старые удаляются

CREATE TABLE progress_logs (progress_id TEXT PRIMARY KEY REFERENCES progress(id) ON DELETE CASCADE,
  text TEXT NOT NULL, created_at INTEGER NOT NULL);

CREATE TABLE notices (
  id TEXT PRIMARY KEY, client_id TEXT NOT NULL REFERENCES clients(id),
  title TEXT, body TEXT NOT NULL, level TEXT NOT NULL, created_at INTEGER NOT NULL
);

CREATE TABLE inbox (                -- одна строка на устройство (D229)
  id TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES devices(id),
  kind TEXT NOT NULL,               -- notice | result
  notice_id TEXT REFERENCES notices(id), progress_id TEXT REFERENCES progress(id),
  client_id TEXT NOT NULL, level TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL,
  created_at INTEGER NOT NULL, read_at INTEGER, silenced INTEGER NOT NULL DEFAULT 0,
  rev INTEGER NOT NULL
);
CREATE INDEX inbox_device_rev ON inbox(device_id, rev);

CREATE TABLE tombstones (           -- для Sync.removed: что исчезло и когда
  device_id TEXT NOT NULL, kind TEXT NOT NULL, object_id TEXT NOT NULL, rev INTEGER NOT NULL
);
```

**`rev`.** Одна функция `nextRev()` в той же транзакции, что и изменение:
`UPDATE counters SET value = value + 1 WHERE name='rev' RETURNING value`. Каждое изменение, видимое
устройству, ставит `rev` строке, которую увидит `sync` (01 §6.1):
- задание — `progress.rev`;
- скрытие — `progress_targets.rev`;
- запись ленты и прочитанность — `inbox.rev`;
- заглушка и имя — `devices.rev`;
- грант — `grants.rev`.

`sync` выбирает для устройства:
- задания, где `progress.rev > cursor OR progress_targets.rev > cursor`, при условии
  `progress_targets.device_id = me`;
- записи `inbox`, где `rev > cursor`;
- `tombstones`, где `rev > cursor`.

Новый `cursor` — максимум из отданного. Если записей больше предела, страница режется по `rev`:
граница не делит одну транзакцию, потому что у каждой строки свой `rev`.

## 4. Движок заданий (`engine/`)

Чистые функции над строкой задания, без базы и часов — их тестируют табличными тестами по фикстурам
`events-*.json`:

```ts
applyEvents(p: ProgressRow, events: Event[], now: number):
  { next: ProgressRow; journal: JournalEntry[]; pushes: PushIntent[]; rejected?: 'closed' }
```

- Пачка сортируется по `seq`. Повторы (`seq ≤ p.seq`) пропускаются, пропуски пишутся в журнал
  (`gap`).
- `step`, `count`, `note`, `finish` — по таблице 01 §5.3. Любой принятый ответ: `seenAt = now`,
  `timeoutAt = now + effectiveTimeout`.
- **Закрытое задание.** Изменения не применяются. Каждое событие идёт в журнал с `afterClose`,
  результат — `rejected: 'closed'` (→ `409`). `ping` в закрытое тоже `409`: так CLI узнаёт, что
  продолжать бессмысленно.
- `PushIntent` — «что стоит отправить»: `created`, `step`, `count`, `final`. Отправляет ли
  рассыльщик и когда — решает он (§5).

Сторож вызывает `timeoutProgress(p, now)`: `failed`, `error = {reason: 'timeout', step, message}`,
журнал, `PushIntent final`.

Транзакция на одну ручку: прочитать задание → `applyEvents` → записать задание, журнал и
`progress_targets` → `nextRev` → зафиксировать. Пуши уходят **после** фиксации: пуш о состоянии, которое
откатилось, хуже пропущенного пуша.

## 5. Рассыльщик (`push/`)

### 5.1. `PushSender`

```ts
export interface PushSender {
  send(message: FcmMessage): Promise<SendResult>
}
export type SendResult =
  | { ok: true }
  | { ok: false; kind: 'invalid-token' | 'retryable' | 'rejected' | 'config'; status: number; detail?: string }
```

- **`GatewaySender(url, apiKey, timeoutMs)`** — `POST <url>/v1/messages:send` с телом `{message}`.
  Разбор ответа — как у Agentiz (README шлюза):
  - `404`/`UNREGISTERED`, а также `400 INVALID_ARGUMENT` с ошибкой про токен → `invalid-token`;
  - `429` и `5xx` → `retryable`;
  - `401` и `403` → `config`;
  - прочие `400` → `rejected`.
- **`FakeSender`** — пишет сообщения в память и отдаёт их через `/dev/pushes` (§8). Им пользуются
  тесты, e2e и ручная проверка без телефонов.
- **`LogSender`** — пишет сжатую строку в лог и отвечает `ok`. Для запуска без Firebase.

### 5.2. `Dispatcher`

`dispatch(intent, progressOrNotice)` для каждого адресата:
1. Грант отозван или устройство забыто — пропустить.
2. `muted_until > now` — пропустить (D228). Для итога и уведомления запись ленты всё равно создаётся,
   с `silenced = 1`.
3. `push_token` пуст или `push_state = 'invalid'` — пропустить. Запись ленты создаётся.
4. Собрать сообщение: `compose.ts` по 01 §8 и тексты по 01 §8.4 на языке и в поясе устройства.
5. `sender.send`. `retryable` → один повтор через 2 с. `invalid-token` → `devices.push_token = NULL`,
   `push_state = 'invalid'`. Устройство вернёт токен при следующем `PUT /v1/devices/me`.
6. Записать `last_delivery_at` и `last_delivery_result` в грант, `last_pushed_seq` и `last_pushed_at`
   в `progress_targets`.

Отправки идут параллельно по адресатам (`Promise.allSettled`), но на одно задание — последовательно.
Иначе итог может обогнать шаг и оказаться в шторке под ним. Порядок держит очередь-обещание на
`progressId` в памяти.

Записи ленты (итог задания и разовое уведомление) создаются в той же транзакции, что и событие, до
отправки. Лента не зависит от того, дошёл ли пуш.

### 5.3. Сглаживание (D203)

| Намерение | Когда уходит |
|---|---|
| `created` | сразу |
| `step` | сразу; отменяет отложенный `count` этого задания |
| `count` | сразу, если с прошлого пуша задания прошло ≥ 30 с; иначе `push_due_at = lastPushedAt + 30`, и уйдёт одно последнее значение |
| `final` | сразу; отменяет отложенное |

## 6. Сторож (`sweeper.ts`)

Один цикл `setInterval` раз в `SWEEP_INTERVAL_MS` (по умолчанию 5000):

1. **Таймауты.** Выбрать `progress` с `timeout_at ≤ now` и незакрытым состоянием → `timeoutProgress`
   → транзакция → пуш `final`.
2. **Отложенные пуши.** Выбрать `push_due_at ≤ now` → пуш `count` с текущим значением →
   `push_due_at = NULL`.
3. **Сессии пейринга.** Истёкшие → `closed_at`, `grants.token_plain = NULL`.
4. **Хранение** (раз в час). Удалить закрытые задания с `finished_at < now − RETENTION_DAYS`. Удалить
   записи ленты и уведомления старше срока. Для каждого удалённого у устройства — `tombstones`. Отдельно
   удалить сами `tombstones` старше 2 × срока.

Сторож должен выдерживать остановку процесса в любой момент: каждое действие — своя транзакция, и
повтор его безвреден.

**Льготный период** — при старте, до первого цикла (01 §3.4).

`sweeper.tick(now)` экспортируется: тесты крутят часы и вызывают его руками, без таймеров.

## 7. Пейринг и устройства — поведение, которое легко забыть

- `claim` проверяет: сессия открыта и не истекла, устройство не забыто.
- **Повторный скан тем же телефоном** той же или новой сессией того же клиента. Грант прежний, токен
  **новый**: старый хеш заменён, `token_plain` — новый. CLI получит новый токен и заменит старый у
  себя по `deviceId`.
- **Грант, отозванный с телефона**, при новом скане оживает: `revoked_at = NULL`, новый токен.
- `DELETE /v1/devices/me` («Забыть устройство»):
  - гранты отзываются, лента удаляется, FCM-токен стирается;
  - строка устройства остаётся с `forgotten_at`, чтобы `devices/status` у CLI ответил `revoked`, а не
    `unknown`;
  - через срок хранения строка удаляется.
- `DELETE /v1/clients/me`: гранты отзываются, открытые задания закрываются `cancelled`, и всем
  адресатам уходит итоговый пуш.
- Разовое уведомление с `targets`, где часть токенов отозвана: отправляется остальным, а в ответе
  каждому токену — свой результат.

## 8. Конфигурация и режимы

| Переменная | По умолчанию | Смысл |
|---|---|---|
| `PORT` | `8787` | |
| `DB_PATH` | `./data/progress.sqlite` | каталог создаётся |
| `PUBLIC_URL` | — **обязательна** | адрес, который попадает в QR (`s=`) |
| `PUSH_MODE` | `gateway` | `gateway` · `fake` · `log` |
| `PUSH_GATEWAY_URL`, `PUSH_GATEWAY_API_KEY` | — | обязательны при `gateway`; без них — отказ при старте, как у шлюза |
| `PUSH_TIMEOUT_MS` | `10000` | |
| `SWEEP_INTERVAL_MS` | `5000` | |
| `RETENTION_DAYS` | `30` | D233 |
| `MIN_TIMEOUT_S` | `60` | в dev можно `1` — для быстрых тестов таймаута |
| `DEV_ROUTES` | `0` | `1` включает `/dev/*`; **запрещено** вместе с `PUSH_MODE=gateway` — отказ при старте |
| `TRUST_PROXY` | `0` | `1` за обратным прокси |

`/dev/*` существует только в dev-режиме:
- `GET /dev/pushes?device=<deviceId>&since=<n>` — что «ушло» через `FakeSender`;
- `POST /dev/clock` `{advanceS}` — сдвинуть часы сервера;
- `POST /dev/sweep` — провернуть сторожа сейчас.

Этими ручками пользуются e2e (05 §3) и ручная проверка без Firebase (05 §5.1).

**Остановка по SIGTERM**: закрыть приём и дождаться текущих запросов и отправок (≤ 10 с). Потом
остановить сторожа и закрыть базу.

## 9. Деплой (ждёт вопросов 114–115)

- `Dockerfile`: `node:24-slim`, сборка в отдельной стадии, `USER node`, том для `/data`,
  `HEALTHCHECK` на `/healthz`.
- Рядом — второй экземпляр `push-gateway` с сервисным аккаунтом `unitool-b1529`, если владелец
  выберет вариант (а) вопроса 115. Сеть между контейнерами внутренняя, наружу торчит только
  прогресс-сервер за HTTPS.
- Бэкап — копия файла SQLite через `sqlite3 .backup` раз в сутки (или `VACUUM INTO`). Одна строка в
  README репозитория; расписание — вопрос вместе с 114.
- До ответа: локальный запуск и e2e; для iPhone — туннель с HTTPS (`cloudflared tunnel --url
  http://localhost:8787`) и `PUBLIC_URL`, равный адресу туннеля.

## 10. Тесты сервера (vitest + supertest)

Все — без сети и без таймеров: `createApp` с `:memory:`-базой, `FakeSender`, ручными часами и
детерминированным `random`.

| Файл | Что доказывает |
|---|---|
| `protocol/test/schemas.test.ts` | каждая фикстура проходит свою схему; испорченные копии (лишнее поле, неверный тип) — нет |
| `protocol/test/health.test.ts` | все строки `health-cases.json` |
| `protocol/test/ids.test.ts` | префиксы, длины, алфавит base58, разбор `Bearer id.secret` |
| `server/test/auth.test.ts` | нет ключа → 401; неверный секрет → 401; ключ устройства на ручке клиента → 403; отозванный клиент → 401 |
| `server/test/pairing.test.ts` | создать → превью → claim → опрос отдаёт токен → закрыть → токен из опроса пропал, хеш работает; истечение → 410; повторный скан — тот же грант, новый токен; несколько телефонов в одной сессии |
| `server/test/engine.test.ts` | табличные тесты по `events-*.json`: шаги, `skipped`, счётчик, повторы, пропуски, первый итог побеждает, `afterClose`, возврат на пройденный шаг → `invalid_step` |
| `server/test/progress-routes.test.ts` | создать (идемпотентно), события, лог (замена, 8 КБ, 413), `show`; чужой клиент → 403; адресаты с отозванными токенами |
| `server/test/timeout.test.ts` | ручные часы: `ping` продлевает; таймаут шага; сторож закрывает с `timeout` и шлёт итог; льготный период после «перезапуска» (новый `createApp` на той же базе) |
| `server/test/dispatch.test.ts` | сообщения совпадают с `push-*.json`; тексты — с `push-texts.json` (ru, en, пояса); сглаживание счётчика (30 с, последнее значение уходит); порядок: итог не обгоняет шаг; `invalid-token` стирает токен; один повтор на `retryable` |
| `server/test/mute.test.ts` | заглушено → ни одного сообщения, запись ленты с `silenced`; истекло → снова шлёт; `durationS` не из набора → 400 |
| `server/test/sync.test.ts` | курсор: первая синхронизация, дозапрос по `more`, скрытие → в `removed`, прочитано → `rev` растёт, заглушка видна; устройство не видит чужих заданий |
| `server/test/inbox.test.ts` | итог и уведомление создают по записи на каждое устройство; прочитать одну и все |
| `server/test/retention.test.ts` | удаление по сроку → `tombstones` → `removed` в `sync` |
| `server/test/limits.test.ts` | 429 с `Retry-After`; 50 открытых заданий; регистрация с одного IP |
| `server/test/logging.test.ts` | прогнать все ручки и проверить журнал запросов: ни одного секрета, токена устройства, FCM-токена и тела |
| `server/test/gateway-sender.test.ts` | на поддельном HTTP-сервере: разбор ответов шлюза во все `SendResult`; таймаут |

Покрытие — `vitest --coverage`, порог 90% строк для `engine/`, `push/` и `sweeper.ts`: там живут
правила. Для остального порога нет.
