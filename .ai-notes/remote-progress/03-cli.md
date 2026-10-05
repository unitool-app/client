# 03 — CLI `unitool-progress` (npm-пакет)

> Часть задания [remote-progress](README.md). Протокол — [01](01-model-and-protocol.md). Методы и
> их смысл перенесены из 08 §11. Здесь добавлены `login`, хранение токенов телефонов, короткий
> опрос при пейринге и детали реализации.

## 1. Пакет

| | |
|---|---|
| Имя в npm | `unitool-progress` (свободно на 2026-09-28; вопрос 116 — оставить или scoped) |
| Команда | `unitool-progress` |
| Node | ≥ 20 (`fetch`, `parseArgs`, `AbortSignal.timeout`) |
| Сборка | esbuild в один файл `dist/cli.js` с `#!/usr/bin/env node`; `protocol`, `zod` и `qrcode` — внутри бандла |
| Зависимости во время работы | **ни одной**: всё в бандле, `npm i -g` ничего не качает сверх пакета |
| В пакете | `dist/cli.js`, `GUIDE.md`, `templates/*.json`, `README.md`, `LICENSE` |
| Разбор аргументов | `node:util` `parseArgs`; своя тонкая таблица команд, без `commander` |

Запуск без установки: `npx unitool-progress pair`. В CI удобнее
`npm i -g unitool-progress@<версия>`: так версия закреплена.

## 2. Правила вывода и кодов (D205, D221)

- **stdout — только то, что читает скрипт**: ID из `new`, JSON из `show --json` и `devices --json`,
  строка из `pair --export`. Всё для человека — в stderr. Поэтому `ID=$(unitool-progress new …)`
  работает без `| tail -1`.
- Коды выхода:

| Код | Когда |
|---|---|
| 0 | успех; **и** ошибка сети или сервера — прогресс не ломает пайплайн: одна строка в stderr, события в очереди (§6) |
| 2 | ошибка использования: нет команды, нет ID, неверный флаг, нет `credentials.json` у команды, которой он нужен. Это ошибка самого скрипта — её надо увидеть, пока скрипт пишется |
| код команды | `step … -- CMD` и `run -- CMD`; убита сигналом N → `128 + N` |
| 3 | только с `--strict` или `UNITOOL_PROGRESS_STRICT=1`: ошибка сети или сервера |

- Сообщения в stderr начинаются с `unitool-progress:`. Язык — по `LC_ALL`/`LANG` (`ru*` → русский,
  иначе английский). Тексты — в `cli/src/i18n/{ru,en}.ts`, полнота проверяется тестом.
- `--help` у каждой команды. Первая строка общего `--help`: «Agents: run `unitool-progress guide`».

## 3. Файлы и окружение

Каталог — `$UNITOOL_PROGRESS_HOME`, иначе `$XDG_CONFIG_HOME/unitool-progress`, иначе
`~/.config/unitool-progress`; на Windows — `%APPDATA%\unitool-progress`.

`credentials.json`. Права `0600`, пишется атомарно: временный файл, потом `rename`.

```json
{
  "v": 1,
  "server": "https://progress.example.com",
  "clientId": "c_8fK2mQ4xZt7p",
  "clientSecret": "cs_…",
  "label": "ноутбук",
  "devices": [
    { "deviceId": "d_Hq3vN8wLr2Ks", "name": "Pixel 7", "platform": "android",
      "deviceToken": "dt_4mYt…", "pairedAt": 1790617700 },
    { "deviceId": "d_Zp8…", "name": "iPhone 15", "platform": "ios",
      "deviceToken": "dt_9QaZ…", "pairedAt": 1790617760 }
  ]
}
```

Это и есть «CLI хранит у себя все токены, которые нужны, чтобы отправлять на устройство» (D225).
FCM-токенов здесь нет: они меняются, и их держит сервер.

Состояние заданий — `$XDG_STATE_HOME/unitool-progress/progress/<ID>.json`, по умолчанию
`~/.local/state/…`:

```json
{ "id": "rp_…", "lastSeq": 7, "closed": false, "targets": ["dt_…"],
  "steps": ["build","test","deploy","check"], "pending": [ /* неотправленные события */ ] }
```

Запись под файловой блокировкой: `<ID>.lock` через `open(…, 'wx')`, повтор каждые 20 мс до 2 с.
Устаревшая блокировка — старше 10 с — снимается. Так два процесса одного скрипта (GitHub Actions: шаги
в разных процессах, файлы общие) не выдадут один `seq`. Если состояния нет, а ID есть (другая машина),
CLI берёт `lastSeq` из `GET /v1/progress/{id}`.

Окружение:

| Переменная | Смысл |
|---|---|
| `UNITOOL_PROGRESS_SERVER` | сервер вместо сохранённого |
| `UNITOOL_PROGRESS_CREDENTIALS` | base64 от `credentials.json` — для CI, вместо файла (§8.3) |
| `UNITOOL_PROGRESS_HOME` | каталог настроек |
| `UNITOOL_PROGRESS_STRICT=1` | как `--strict` |
| `UNITOOL_PROGRESS_DISABLE=1` | ничего не отправлять: `new` печатает ID, остальное молча выходит 0. Для локальных прогонов без телефонов |
| `UNITOOL_PROGRESS_ID` | ставит `run` для дочерней команды: вложенный скрипт двигает то же задание |

Сервер по умолчанию вшит при сборке (`DEFAULT_SERVER`) — адрес ждёт вопроса 114. Пока он пуст,
`login` без `--server` отвечает кодом 2 и строкой «укажите сервер: `unitool-progress login --server
https://…`».

## 4. Команды

| Команда | Что делает | stdout |
|---|---|---|
| `login [--server URL] [--label L]` | зарегистрировать CLI (D224): секрет рождается здесь, серверу уходит хеш; сохранить `credentials.json`. Уже вошёл — напечатать, кто он, и выйти 0 | — |
| `logout [--keep-server]` | `DELETE /v1/clients/me`, стереть `credentials.json`; телефоны увидят, что CLI отключён | — |
| `pair [--label L]` | подключить телефоны (§5); без `login` — сначала он | — |
| `pair --export --label L` | **отдельная** личность для CI: новый `login` не в файл, та же сессия QR, в конце — строка для секрета CI (§8.3) | `UNITOOL_PROGRESS_CREDENTIALS=…` |
| `devices [--json]` | телефоны из `credentials.json` и их статус с сервера (§5.3) | JSON с `--json` |
| `devices remove <N\|имя>` | `POST /v1/grants/revoke` и убрать из файла | — |
| `status` | сервер (`/healthz`), личность, телефоны, неотправленные события | — |
| `templates [show T]` | встроенные, пользовательские и проектные шаблоны (§7) | — |
| `new T [--title …] [--steps a,b,c \| N] [--total N] [--unit U] [--timeout 15m] [--to имена]` | создать задание со всеми шагами | **ID** |
| `step ID S [--name …] [--timeout 30m]` | шаг `S` — `id`, номер с 1 или `next` — стал текущим | — |
| `step ID S [--timeout …] [--no-log] -- CMD…` | выполнить команду как шаг (§6.1) | вывод команды |
| `update ID --current N [--total N] [--unit U]` | счётчик | — |
| `note ID MESSAGE [--level info\|warn\|error]` | заметка в журнал | — |
| `ping ID` | «жив», без изменений — для долгих циклов | — |
| `done ID [--message …]` | успех | — |
| `fail ID [--message …] [--exit-code N] [--reason error\|unclosed] [--log FILE\|-] [--no-log] [--if-open]` | провал; `--if-open` — только если задание ещё открыто (для ловушки `EXIT`) | — |
| `cancel ID [--message …]` | отмена | — |
| `show ID [--json]` | состояние задания | состояние |
| `notify MESSAGE [--title T] [--level info\|ok\|warn\|error] [--to имена]` | разовое уведомление, «просто пуш по финалу» | — |
| `run [--template T] [--title …] [--timeout …] [--no-log] -- CMD…` | одна команда целиком: `new task` + `step 1 -- CMD` + `done`/`fail` | вывод команды |
| `guide` | инструкция для агентов (§9) | markdown |
| `install-skill [--project]` | положить её скиллом Claude Code | — |
| `--version` | версия CLI и протокола | версия |

Уточнения:
- **`--to`** — имена или номера из `devices`, через запятую. По умолчанию — все телефоны. У `step`,
  `done` и прочих `--to` нет: адресаты задаются при создании и живут в задании.
- **Длительности** — `90s`, `15m`, `2h`, `1d`. Голое число — секунды.
- **`--if-open`** сначала смотрит локальное состояние. Если там `closed` — выходит 0 без запроса.
  Иначе отправляет `finish`, а `409 closed` глотает молча. Поэтому ловушки `ERR` и `EXIT` после
  упавшего `step -- cmd` ничего не портят (08 §11.7, D215).
- **`fail` без `--reason`** ставит `error`. `--exit-code` идёт в `error.exitCode`.
- **`--log -`** читает хвост из stdin, `--log FILE` — из файла. Берутся последние 50 строк, после
  вычистки (§6.3) и обрезки до 8 КБ.
- **`show`** без `--json` печатает в stderr шаги с отметками `✓ ◉ ○ ✕ ·`, состояние и «обновлено N
  назад». С `--json` — полную форму 01 §4.1 в stdout.
- **Любой ответ `409 closed`** печатается один раз: «задание уже закрыто в 21:25 (нет ответа 15 мин)».
  После него `closed: true` в локальном состоянии, дальше молча. Код 0.
- **`401`** — «CLI отключён от сервера, выполните `unitool-progress login`», код 0.

## 5. Вход и пейринг

### 5.1. `login`

1. Сгенерировать `clientSecret` (`cs_` + 32 base58), посчитать SHA-256.
2. `POST /v1/clients {label, secretHash}` → `clientId`. Метка по умолчанию — `os.hostname()` без
   домена (вопрос 108 — допущение).
3. Записать `credentials.json` с пустым `devices`, права `0600`.
4. stderr: `Вошли как «ноутбук» (c_8fK2…) на https://… — теперь unitool-progress pair`.

### 5.2. `pair`

```
$ unitool-progress pair
CLI «ноутбук» · сервер https://progress.example.com
Отсканируйте код в Unitool → Прогресс → «Подключить» или камерой телефона.
Можно несколько телефонов подряд.

  █▀▀▀▀▀█ ▀▄▀█▄ █▀▀▀▀▀█
  █ ███ █ ▄▀▀▄▀ █ ███ █
  █ ▀▀▀ █ ██▀▄█ █ ▀▀▀ █
  ▀▀▀▀▀▀▀ █ ▀ █ ▀▀▀▀▀▀▀
  …
  unitool://app/progress/pair?s=https%3A%2F%2Fprogress.example.com&t=pt_9f2a…

  ✓ Pixel 7 (android)          21:02
  ✓ iPhone 15 (ios)            21:03
Enter — закончить · код действует ещё 8 мин
Готово: телефонов 2. Проверка: unitool-progress notify "Привет с ноутбука"
```

1. Если нет `credentials.json`, выполнить `login`.
2. `POST /v1/pairings` → `{pairingId, pairingToken, link, expiresAt}`.
3. QR рисует `qrcode.toString(link, {type: 'terminal', small: true})` (D227). Под ним — ссылка текстом:
   её можно открыть на телефоне из мессенджера.
4. Раз в 2 с — `GET /v1/pairings/{pairingId}`. Каждое новое устройство (по `deviceId`) сразу
   записывается в `credentials.json`. Повторный скан заменяет `deviceToken` у того же `deviceId`
   (02 §7). В stderr печатается `✓ имя (платформа)`.
5. Выход:
   - по Enter или Ctrl-C — `DELETE /v1/pairings/{id}`, итог в stderr, код 0;
   - при истечении — «код истёк; подключено: N», код 0.

   Если stdin не терминал, сессия ждёт до истечения. `--count N` завершает её после N телефонов.
6. Сеть пропала во время опроса — повторять с отступом до 10 с и писать в stderr одну строку
   «нет связи с сервером, повторяю…». Опрос не прерывается: телефон мог уже подключиться.

### 5.3. `devices`

```
$ unitool-progress devices
1  Pixel 7      android  с 2026-09-28  доставка 21:12 ✓
2  iPhone 15    ios      с 2026-09-28  доставка 21:12 ✓  · заглушён до 22:40
3  Galaxy Tab   android  с 2026-10-27  токен FCM недействителен ✗ (откройте на нём Unitool)
4  Pixel 6      android  отключён с телефона — убран из списка
```

`POST /v1/devices/status` со всеми токенами. Ответ `revoked` убирает устройство из
`credentials.json`: строка печатается последний раз. Без сети — список из файла с пометкой «статус
неизвестен: нет связи».

## 6. Отправка событий

### 6.1. `step ID S -- CMD` — шаг, который выполняет команду

Перенесено из 08 §11.2 без маркерных строк (вопрос 102 — не делаем).

- Отметить шаг `S` текущим (`step`), потом запустить `CMD`: `spawn` без оболочки,
  `stdio: ['inherit', 'pipe', 'pipe']`.
- Вывод проходит насквозь в свои stdout и stderr, в том же порядке кусков. Параллельно в памяти
  держатся последние 200 строк.

  Цена труб: у дочерней команды `isatty` = false, и часть программ перестанет раскрашивать вывод. Это
  сознательно: PTY потребовал бы нативный модуль. В `--help` есть подсказка про `FORCE_COLOR=1`.
- Пока команда печатает, CLI шлёт `ping` не чаще раза в 30 с. Если команда молчит дольше таймаута,
  сервер закроет задание по таймауту. Так и задумано (D213): для долгих тихих шагов есть `--timeout`.
- Код 0 → выйти 0. Шаг остаётся текущим, пока не начнётся следующий или не придёт `done`.
- Код ≠ 0:
  - `finish failed`: `reason: exit`, шаг, `exitCode`, `command`;
  - `message`: «Тесты: код выхода 1» — имя шага и код;
  - `PUT …/log`: хвост после вычистки;
  - выйти с кодом команды, чтобы `set -e` остановил скрипт как обычно.
- `command` — это `argv` команды после вычистки, обрезанный до 200 символов. Командная строка не
  передаётся целиком, если длиннее.
- SIGINT и SIGTERM, пришедшие в CLI, пересылаются команде. Если она после этого вышла — `finish
  cancelled` и код `128 + N`. Если команду убили сигналом не мы — `failed`, `message` «убита
  SIGKILL».
- `--no-log` — хвост не отправляется. `hasLog: false`.

### 6.2. Очередь и досыл (D205)

1. Событие получает `seq = lastSeq + 1` под блокировкой (§3) и сразу добавляется в `pending`.
2. Отправка: `POST /v1/progress/{id}/events` со **всем** `pending`, таймаут 5 с. Ответ `lastSeq` → из
   `pending` удаляется всё, что ≤ него.
3. Сеть или `5xx`/`429`:
   - одна строка в stderr («сервер недоступен, событие отправится позже»), код 0;
   - запустить **досыльщик**: `spawn(process.execPath, [cli, '_flush', id], {detached: true, stdio:
     'ignore'}).unref()`, если его ещё нет (pid-файл рядом с состоянием);
   - досыльщик повторяет с отступом 1–2–4–…–60 с в течение часа, потом сдаётся и оставляет файл
     следующему вызову.
4. Каждый вызов CLI с этим ID сначала досылает `pending`.
5. Создание (`new`) тоже может лечь в очередь. ID напечатан сразу, а `POST /v1/progress` досылается
   первым. Сервер идемпотентен по ID (01 §5.3).
6. Если сервер был недоступен дольше таймаута, задание закроется как «нет ответа», а досланные
   события лягут в журнал «после закрытия». Это честная картина того, что видели телефоны (08
   §11.3).

### 6.3. Вычистка секретов

Применяется к `title`, `message`, `command`, хвосту лога, `note`. Перенесено из 08 §11.5:

- значения переменных окружения, в имени которых есть `TOKEN`, `SECRET`, `KEY`, `PASSWORD`, `PASS`,
  `AUTH`, `CREDENTIAL`, `PRIVATE`, и значение длиннее 7 символов → `***`;
- известные форматы:
  - `Authorization: …`, `Bearer …`;
  - `ghp_…`, `gho_…`, `github_pat_…`, `glpat-…`, `xox[abprs]-…`, `AKIA[0-9A-Z]{16}`, `sk-…`,
    `npm_…`, JWT `eyJ…\.eyJ…\.…`;
  - `-----BEGIN … PRIVATE KEY-----…END…` целиком;
  - `://user:pass@` в URL, `password=` и `token=` в параметрах;
- свои секреты CLI: `cs_…`, `dt_…`.

Это лучшее усилие, а не гарантия. Гарантия — `--no-log`, и инструкция для агента прямо требует не
класть секреты в заголовки (§9).

## 7. Шаблоны (D212)

Формат — перенесено из 08 §6.4, упрощено:

```json
{
  "name": "deploy",
  "description": { "ru": "Сборка, тесты, выкладка, проверка", "en": "Build, test, deploy, verify" },
  "kind": "steps",
  "timeout": "15m",
  "steps": [
    { "id": "build",  "name": { "ru": "Сборка",   "en": "Build" } },
    { "id": "test",   "name": { "ru": "Тесты",    "en": "Tests" }, "timeout": "30m" },
    { "id": "deploy", "name": { "ru": "Выкладка", "en": "Deploy" } },
    { "id": "check",  "name": { "ru": "Проверка", "en": "Verify" }, "timeout": "5m" }
  ]
}
```

Встроенные (вопрос 112 — допущение):

| Шаблон | `kind` | Шаги | Таймаут |
|---|---|---|---|
| `task` | `task` | «Выполнение» | 15m |
| `steps` | `steps` | из `--steps a,b,c`; `--steps N` → «Шаг 1…N», имена потом через `step --name` | 15m |
| `count` | `count` | один шаг; `--total N` обязателен, `--unit` по желанию | 15m |
| `build` | `steps` | Подготовка, Сборка, Тесты (30m), Артефакты | 15m |
| `deploy` | `steps` | Сборка, Тесты (30m), Выкладка, Проверка (5m) | 15m |
| `release` | `steps` | Сборка, Тесты, Подпись, Загрузка, Публикация | 30m |
| `agent` | `steps` | Анализ, Изменения, Проверка, Итог | 30m |

Поиск: `./.unitool-progress/templates/*.json` (проект) → `<каталог настроек>/templates/*.json` →
встроенные. Первое совпадение по имени побеждает. Имя шага — строка или `{ru, en}`; язык — по
`LANG`. `templates show T` печатает шаблон с уже выбранным языком. Флаги `new` важнее шаблона:
`--title`, `--timeout`, `--steps`. Неверный шаблон — код 2 с именем файла и полем.

## 8. Рецепты

### 8.1. Одна долгая команда и «только итог»

```bash
unitool-progress run --title "Импорт базы" --timeout 20m -- ./import.sh dump.sql

./backup.sh && unitool-progress notify "Бэкап готов" --level ok \
            || unitool-progress notify "Бэкап упал" --level error
```

### 8.2. Claude Code: пуш, когда агент ждёт ответа

```json
{ "hooks": {
    "Notification": [ { "hooks": [ { "type": "command",
      "command": "unitool-progress notify 'Claude Code ждёт ответа' --level warn" } ] } ]
} }
```

### 8.3. GitHub Actions

На ноутбуке: `unitool-progress pair --export --label "GitHub CI"`. Команда покажет QR для тех же
телефонов и напечатает строку `UNITOOL_PROGRESS_CREDENTIALS=…`. Значение идёт в секреты репозитория.

```yaml
env:
  UNITOOL_PROGRESS_CREDENTIALS: ${{ secrets.UNITOOL_PROGRESS_CREDENTIALS }}
steps:
  - run: npm i -g unitool-progress@1
  - run: echo "PROGRESS_ID=$(unitool-progress new steps --title 'Release ${{ github.ref_name }}' --steps 'Сборка,Тесты,Загрузка' --timeout 30m)" >> "$GITHUB_ENV"
  - run: unitool-progress step "$PROGRESS_ID" 1 -- ./gradlew bundleRelease
  - run: unitool-progress step "$PROGRESS_ID" 2 -- ./gradlew test
  - run: unitool-progress step "$PROGRESS_ID" 3 -- ./upload.sh
  - if: success()
    run: unitool-progress done "$PROGRESS_ID"
  - if: failure() || cancelled()
    run: unitool-progress fail "$PROGRESS_ID" --if-open --message "Job ${{ job.status }}"
```

Телефоны, подключённые позже, до CI не доходят: секрет — снимок `credentials.json`. Чтобы добавить
телефон, нужно повторить `pair --export` и обновить секрет. Так и задумано: CI не пишет себе файлы.

## 9. Инструкция для агентов (D218)

`GUIDE.md` в пакете — текст из 08 §11.9 с этими правками:
- `update` вместо `progress`;
- нет MCP и маркерных строк;
- в «Rules» добавлено: «run `unitool-progress devices` once; if it lists no phones, tell the user to
  run `unitool-progress pair` and stop — do not pair on their behalf».

Заготовки bash и Python — **дословно** из 08 §11.7 (bash — с тремя ловушками, Python — с `except
BaseException` и `--log -`). Таблица отказов — там же.

- `unitool-progress guide` печатает `GUIDE.md`.
- `install-skill` кладёт его как `~/.claude/skills/progress-tracking/SKILL.md`; с `--project` — в
  `.claude/skills/progress-tracking/` текущего репозитория. Если файл уже есть и он другой — показать
  diff и не перезаписывать без `--force`.

Рядом с пакетом, в репозитории (не в npm), лежит `examples/demo-deploy.sh` — bash-заготовка из
`GUIDE.md` с `sleep 20` между шагами и `FAIL_AT=N`, чтобы упасть на шаге N. Ею прокликивают
уведомления руками (05 §5.2).

Заготовки из `GUIDE.md` — исполняемые тесты (05 §3). Тест вырезает блоки кода из самого `GUIDE.md`, а
не из копии, поэтому инструкция не может разойтись с поведением CLI.

## 10. Публикация (ждёт вопроса 116)

- Версия CLI — semver, своя. Протокол — `v1` в пути; `--version` печатает обе.
- `npm pack --dry-run` в CI: в тарболе только файлы из §1, размер < 1 МБ. Тест сверяет список.
- Публикация — GitHub Actions по тегу `cli-v1.2.3`: `npm publish --provenance --access public` с
  `NPM_TOKEN` из секретов. До ответа на 116 — только `npm pack` и установка тарбола локально
  (`npm i -g ./unitool-progress-0.1.0.tgz`).
- `DEFAULT_SERVER` подставляется при сборке из `cli/build-config.json`; пустой — допустим (§3).

## 11. Тесты CLI (vitest)

Тесты запускают **собранный** `dist/cli.js` как дочерний процесс. Каталог настроек — временный.
Сервер — настоящий `createApp` из `server` в том же процессе теста: `:memory:`, `FakeSender`,
dev-ручки, случайный порт.

| Файл | Что доказывает |
|---|---|
| `args.test.ts` | каждая команда из §4: флаги, длительности, `--to`, код 2 на неверном вводе; `--help` у каждой |
| `login-pair.test.ts` | `login` пишет `0600`; `pair` печатает QR и ссылку; «телефон» (тест дёргает `claim` через API) появляется в выводе и в файле; второй телефон в той же сессии; повторный скан заменяет токен; истечение; Enter закрывает сессию |
| `devices.test.ts` | статусы `ok`, `invalid`, `revoked` → удалён из файла; `remove`; без сети — список из файла |
| `new-step.test.ts` | stdout `new` — ровно ID и перевод строки; шаги по номеру, `id`, `next`; `--steps 3` + `step --name`; перепрыгнутый шаг → `skipped` на сервере |
| `step-cmd.test.ts` | вывод проходит насквозь байт в байт; код 0/1/3 сохраняется; при коде 1 на сервере `failed(exit)` с хвостом последних 50 строк; `ping` не чаще раза в интервал (в тестах интервал сжат переменной `UNITOOL_PROGRESS_PING_MS`, которую CLI читает только при `NODE_ENV=test`); SIGINT → `cancelled`, код 130; `kill -9` дочерней → «убита SIGKILL» |
| `traps.test.ts` | `fail --if-open` после `step`, упавшего с кодом 1, не шлёт второго итога; `--if-open` на открытом шлёт `unclosed` |
| `queue.test.ts` | сервер выключен → `new` печатает ID, код 0, события в `pending`; сервер включён → следующий вызов досылает, порядок `seq` сохранён; досыльщик сам досылает; `409` после таймаута → один раз строка, дальше тихо |
| `concurrency.test.ts` | 20 параллельных `update` одного ID → 20 разных `seq`, сервер принял все |
| `redact.test.ts` | фикстуры вывода с поддельными токенами всех форматов §6.3 и переменными окружения → ни одного совпадения в том, что пришло на сервер |
| `notify.test.ts` | `notify` → `FakeSender` получил сообщение для каждого телефона; `--to` отбирает; `--level` → знак в заголовке |
| `show.test.ts` | `show --json` проходит схему; человеческий вывод на фикстурах |
| `templates.test.ts` | встроенные валидны; проектный перекрывает встроенный; ошибка в шаблоне → код 2 с полем |
| `export.test.ts` | `pair --export` не трогает основной `credentials.json`; строка из stdout, положенная в `UNITOOL_PROGRESS_CREDENTIALS`, работает без файла |
| `i18n.test.ts` | у каждого ключа есть ru и en |
| `pack.test.ts` | содержимое `npm pack --dry-run` |
