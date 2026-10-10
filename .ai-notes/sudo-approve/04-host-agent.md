# 04 — Агент на хосте: `unitool-sudo`

> **Версия:** 1.2 · **Дата:** 2026-10-10 · Шаг 3 в 00-TASK. Решения D389 (плагин + бинарник),
> D400, D401, D407, D408, D411–D416. В 1.2 добавлен вход по паролю локального аккаунта (§13). Все тексты, которые видит человек в терминале, — на английском
> (`done/cli` D294). Эта заметка — по-русски.

## 1. Из чего состоит

```
sudo (setuid root)
 ├─ policy: sudoers.so          ← как было: кто что может (и пароль, если не NOPASSWD)
 └─ approval: sudo_approve.so   ← новое, ~150 строк C
       └─ fork + execve /usr/libexec/unitool-sudo/unitool-sudo check
             stdin  ← контекст команды (§3)
             stderr → терминал человека (код, обратный отсчёт)
             /dev/tty ↔ клавиша «p» и пароль (§13)
             exit   → 0 разрешить · 1 отказать · 2 ошибка (= отказ)
```

- **Плагин** знает sudo и ничего не знает о сети и криптографии. **Бинарник** — наоборот. Так
  код на C минимален, а всё остальное — тот же Kotlin, что на телефоне.
- PAM не годится: он не видит команду. `python_plugin` есть не во всех сборках sudo (D389).

## 2. Код в монорепозитории (D400, D401)

```
/prj/unitool/unitool-sudo/
  README.md                       английский: что это, установка, команды, аварийный вход
  agent/                          Gradle :unitool-sudo:agent — Kotlin MPP: jvm() + linuxX64()
    build.gradle.kts              deps: :unitool-kit, :unitool-cli:core; linuxX64Main: ktor-client-curl
    src/commonMain/kotlin/cx/m42/unitool/sudo/
      SudoAgent.kt                CliApp-подобный корень с командами §6
      Config.kt                   разбор /etc/unitool-sudo/config.toml (подмножество TOML, §7)
      AllowedKeys.kt              authorized_keys → List<SshPublicKey> + проверка прав (§4)
      HostKey.kt                  ssh_host_ed25519_key → AgeIdentity, .pub → SshPublicKey
      Context.kt                  разбор stdin от плагина (§3)
      Check.kt                    сценарий check (§5): грант → запрос → ожидание → Verdict
      Grants.kt                   файлы грантов, лидер сессии (§8)
      ServerApi.kt                интерфейс клиента сервера (01 §7.1) + реализация на ktor
      Countdown.kt                строка ожидания в терминале
      Password.kt                 вход по паролю: клавиша «p», запрос пароля, попытки (§13)
      PasswordCheck.kt            expect: проверка пароля локального аккаунта (PAM или shadow)
      Journal.kt                  /var/log/unitool-sudo.log + syslog authpriv
      Doctor.kt, Enroll.kt, Status.kt, Revoke.kt, KeyHashCmd.kt, TestCmd.kt
      Os.kt                       expect: файлы, права, /proc, hostname, uid, syslog, сигналы
    src/linuxX64Main/…            actual Os на platform.posix; actual PasswordCheck (cinterop libpam,
                                  запасной путь — crypt(3) по /etc/shadow); main()
    src/jvmMain/…                 actual Os для тестов поверх временного каталога (поддельный /proc)
    src/commonTest/…              тесты §10 (гоняются на JVM и нативно)
  plugin/
    sudo_approve.c                approval plugin (§3)
    Makefile                      cc -shared -fPIC -O2 -Wall -Werror, без зависимостей кроме sudo_plugin.h
    sudo_plugin.h                 копия из sudo 1.9.x (лицензия ISC, строка в README)
  packaging/
    install.sh, uninstall.sh      §9
    sudo.conf.snippet, sudoers.snippet, pam-logout.snippet
    pam.d/unitool-sudo            PAM-сервис для пароля: @include common-auth + common-account
  testdata/                       фикстуры 01 §9 + gen.sh
  e2e/                            шаг 5, 06 §2
```

- `settings.gradle.kts`: `include(":unitool-sudo:agent")` и `include(":unitool-sudo:e2e")`.
- Задача `:unitool-sudo:agent:install` кладёт бинарник в `unitool-sudo/agent/build/dist/unitool-sudo`,
  как `:unitool-cli:app:install`. Плагин собирается `make -C unitool-sudo/plugin`. Gradle-задача
  `:unitool-sudo:plugin` вызывает `make`, если есть `cc`, и пропускает с сообщением, если нет.
- Отдельный бинарник, а не команда в `unitool`: этот код работает **от root** на чужих серверах, и
  в нём не должно быть ничего лишнего (QR, split, link). Разбор аргументов и `Io` — из
  `unitool-cli:core`, ничего не копируется.
- `scripts/verify.sh` получает `:unitool-sudo:agent:allTests` и (если есть `cc`) сборку плагина.

## 3. Плагин `sudo_approve.so`

API — `struct approval_plugin` из `sudo_plugin.h` (sudo ≥ 1.9.0):

| Колбэк | Что делает |
|---|---|
| `open(version, conv, printf, settings, user_info, submit_optind, submit_argv, submit_envp, plugin_options, errstr)` | запоминает `settings` (нужны `run_shell`, `login_shell`) и `user_info` (`user`, `uid`, `cwd`, `tty`); путь к бинарнику — из `plugin_options` (`agent=/usr/libexec/unitool-sudo/unitool-sudo`), по умолчанию этот |
| `check(command_info, run_argv, run_envp, errstr)` | `fork` → в ребёнке `execve(agent, ["unitool-sudo","check"], {PATH=/usr/bin:/bin, LANG=C.UTF-8})`; в родителе пишет контекст в pipe и ждёт `waitpid`. Код 0 → `return 1` (разрешить); иначе `return 0`, `*errstr = "not approved"` |
| `close`, `show_version` | ничего / строка версии |

Контекст в stdin — записи `key=value`, разделённые `\0`:

```
v=1  user=dev0  uid=1000  runas_user=root  runas_uid=0  cwd=/root  tty=/dev/pts/3
command=/usr/bin/apt  argv=/usr/bin/apt  argv=upgrade  run_shell=0  login_shell=0
```

- `argv` повторяется по одному на аргумент: из `run_argv`, а для `argv[0]` — `command` из
  `command_info` (полный путь после разбора sudoers).
- Окружение команды (`run_envp`) не передаётся: на телефоне его не покажешь осмысленно.
- `fork` в sudo безопасен: approval-плагин вызывается до запуска команды, в однопоточном процессе.
  Сигналы: Ctrl+C в терминале получает вся группа процессов переднего плана, то есть и ребёнок. Это
  проверяет спайк S1. Если не получает — плагин ловит `SIGINT`/`SIGTERM` и пересылает ребёнку.
- Плагин не читает ответа, кроме кода выхода. Всё, что видит человек, пишет бинарник в stderr.

## 4. Какие ключи хост считает своими (D378, D379)

- `/root/.ssh/authorized_keys` и `/etc/unitool-sudo/authorized_keys` (root:root, 0644 или строже).
  `~/.ssh/authorized_keys` вызывающего не читается никогда.
- Для каждого файла и **каждого каталога до `/`**: владелец root, нет записи для группы и других.
  Нарушение → отказ, причина `perm:<путь>` в журнале и понятная строка в терминале. Файла нет —
  это не ошибка, это пустой список.
- Разбор — `SshPublicKey.parseAll` из `kit.ssh`, плюс опции строки. Строка с опцией
  `no-sudo-approve` пропускается. `sk-*` и DSA пропускаются молча, как в D154. RSA короче 2048
  пропускается.
- Если allowed пуст → отказ сразу, без сервера: `no keys allowed to approve sudo on this host`.

## 5. Сценарий `check` (D381, D383, D385)

```
1. Прочитать контекст (§3). user ∈ exempt_users → exit 0, строка в журнале «exempt».
1a. Команда — оболочка (`run_shell`/`login_shell` или argv[0] из /etc/shells) → только пароль
    (§13.3, D413): телефон не спрашивается, гранты не действуют.
2. Есть живой грант (uid, session) и он проходит перепроверку (§8) → exit 0, «grant-hit».
3. allowed = §4; hostKey = /etc/ssh/ssh_host_ed25519_key(.pub); client = /etc/unitool-sudo/client.key.
   Чего-то нет → отказ с подсказкой «run: unitool-sudo doctor».
4. R = SudoRequest.build(... iat = now, exp = now + timeout, code = SudoCode.random(), nonce)
   keyHashes = allowed.map { KeyHash.of(it.blob) }   (≤ 16; если больше — первые 16, строка в журнале)
5. POST /sudo/requests
     404 no_device → fallback (§7), «no phone holds any of the N allowed keys»
     401           → fallback, «this host is not enrolled: unitool-sudo enroll»
     429           → отказ, «too many requests, retry in N s»
     сеть          → повтор через 2 с до exp, потом fallback
6. stderr: "🔐 sudo: confirm on your phone [K7QX] — mu6: apt upgrade  2:59   (p = use password)"
   строка перерисовывается раз в секунду, если stderr — tty; иначе одна строка в начале.
   Если devices == 0: "  (no phone was notified: muted or no push; open Unitool → sudo)".
   Нажата «p» → §13.2: запрос на сервере отменяется (`DELETE`), дальше пароль.
7. Цикл до exp: GET /sudo/requests/{id}?wait=min(25, осталось)
     approved + sealed → 8
     denied            → exit 1, «denied on the phone»
     expired           → fallback (§7): пароль, если есть терминал
     cancelled         → exit 1
     сеть              → пауза 2 с, дальше
   SIGINT/SIGTERM → DELETE /sudo/requests/{id} (одна попытка, 3 с), exit 1, «cancelled».
8. Verdict.check(R, keyHashes, sealed, hostKey, allowed, now, maxSession)
     Refused(reason) → exit 1, «answer rejected: <reason>», журнал verify-failed:<reason>
     Approved(once)  → exit 0
     Approved(ttl|session) → записать грант (§8); exit 0
9. Журнал (§11) в любом исходе.
```

`approved` без `sealed` (кто-то уже забрал ответ) → отказ `already-delivered`: это значит, что
ответ видел кто-то ещё, а агент не знает, кто.

«Отклонить» на телефоне — окончательный отказ **этому запросу**: агент после него пароль не
предлагает. Но следующий `sudo` можно пройти по паролю (клавиша «p»). Пароль и телефон — два
равноправных входа, и это сознательно (§13.1).

## 6. Команды

| Команда | Кто | Что |
|---|---|---|
| `check` | только плагин (stdin — не tty, euid 0) | §5 |
| `enroll --server URL --label NAME [--force]` | root | `cs_` из `secureRandomBytes`, `POST /clients {label, secretHash}`, запись `/etc/unitool-sudo/client.key` (0600: `c_…` и `cs_…`) и `server` в конфиг. Уже есть клиент → отказ без `--force` |
| `status` | root | сервер, `clientId`, `label`, allowed (тип, `SHA256:` отпечаток, первые 12 символов keyHash, комментарий), живые гранты (пользователь, сессия, решение, до), версия |
| `keyhash [FILE\|-]` | кто угодно | для каждой строки: `keyHash  SHA256:…  comment` — сверка с экраном «Ключи» на телефоне |
| `revoke [--all]` | root (через sudo — ему хватит текущего гранта) | удалить грант своей сессии или все |
| `test` | root | настоящий запрос для `argv = ["/usr/bin/true"]` (на телефоне так и видно) и печать вердикта; гранта не пишет. Для ручной проверки и `doctor --online` |
| `doctor [--online]` | root | §9.2 |
| `logout` | только `pam_exec` (переменные `PAM_TYPE=close_session`, `PAM_USER`) | удалить гранты закрывающейся сессии (§8) |
| `--version`, `--help` | кто угодно | |

## 7. Конфиг `/etc/unitool-sudo/config.toml` (D399)

```toml
server       = "https://unitool.example/api/unitool/v1"   # пишет enroll
timeout      = 180          # 30..300, секунды ожидания телефона
password     = "on"         # "on" | "off": вход по паролю локального аккаунта (§13)
fallback     = "password"   # "password" | "deny": что делать, если телефон недоступен
password_grant = "15m"      # грант после верного пароля: "0" (один раз) .. "1h", как sudo timestamp
max_session  = "12h"        # потолок «до выхода» и «на время»; 15m..12h
exempt_users = []           # кому sudo без телефона (автоматика); по умолчанию никому
ca_file      = ""           # свой CA для сервера; пусто — системные
```

- Разбор — подмножество TOML: строки в двойных кавычках, целые, массивы строк, комментарии `#`.
  Остальное → ошибка с номером строки. Неизвестный ключ → ошибка (опечатка в `fallback` не должна
  молча оставить хост без защиты).
- **`fallback` (D412; заменяет D399, которая заменяла D391).** sudoers остаётся `NOPASSWD` для
  группы `sudo-approve`. Пароль спрашивает и проверяет **сам агент** (§13), поэтому «пароль, если
  телефона нет» снова возможен, и теперь правильно:
  - `password` (по умолчанию): телефон недоступен → запрос пароля в терминале; без терминала → отказ;
  - `deny`: телефон недоступен → отказ (для хостов, где пароль запрещён: `password = "off"`).
  - «Недоступен» = `no_device`, `401`, нет связи до дедлайна, телефон молчал до `exp`. Отказ на
    телефоне и неверный ответ — всегда отказ, при любом `fallback`.
  - `fallback = "password"` при `password = "off"` → ошибка конфига.

## 8. Гранты (D383)

- Каталог `/run/unitool-sudo/grants/` (0700 root, tmpfs, пустеет при перезагрузке). Файл
  `<uid>-<sessionid>.json` (0600), канонический JSON:
  `{request, answer, sig, signer, leaderPid, leaderStart, createdAt}` — R и A в base64, подпись,
  строка ключа.
- **Сессия** — `/proc/self/sessionid` процесса агента. Он наследуется от логина через sudo и
  плагин, а `setuid` его не меняет. Значение `4294967295` значит, что сессии нет (cron, сервис, sshd
  без `pam_loginuid`). Тогда гранты не пишутся: «на время» и «до выхода» работают как «один раз»,
  и телефон заранее показывает это по отсутствию `session` в R (01 §3.3).
- **Лидер сессии** — процесс с этим `sessionid` и самым ранним `starttime`: это сессионный `sshd`
  или `login`. Ищется по `/proc/*/sessionid` и `/proc/<pid>/stat` (поле 22). Для грантов
  запоминаются `leaderPid` и `leaderStart`.
- **Перепроверка при каждом `check`:**
  1. файл есть, принадлежит root, 0600;
  2. `answer.until > now`, и `createdAt + max_session > now`;
  3. лидер жив: `/proc/<leaderPid>/stat` есть и `starttime == leaderStart` (PID не переиспользован);
  4. `SshSig.verify(sig, answer, NAMESPACE, signer) == Valid`, `signer` всё ещё в allowed,
     `answer.req == sha256(request)`;
  5. `runas` текущей команды — любой. Грант даётся на сессию, как обычный sudo-timestamp, но шире:
     на все терминалы логина. В README агента это сказано прямо.
  Любой провал → файл удаляется, дальше обычный запрос.
- **Грант по паролю** (D414): после верного пароля пишется грант `kind: "password"` на
  `password_grant` (по умолчанию 15 мин, как обычный sudo). В нём нет подписи, поэтому пункт 4
  перепроверки для него пропускается: доверие держится на правах файла (root, 0600, `/run`).
  `password_grant = "0"` — гранта нет, каждый раз пароль или телефон.
- **Оболочка** (`sudo -s`, `sudo -i`, команда из `/etc/shells`) гранты не использует никакие:
  каждый раз пароль (D413).
- **Выход из сессии сразу.** Если в PAM sshd есть `session optional pam_exec.so
  /usr/libexec/unitool-sudo/unitool-sudo logout`, файл удаляется при выходе. Без этого грант гаснет
  при следующем `check` по пункту 3.

## 9. Установка и `doctor`

### 9.1. `packaging/install.sh` (запускать от root)

1. Проверить: Linux x86_64, sudo ≥ 1.9 (`sudo -V`), есть `ssh_host_ed25519_key`. На musl (Alpine) —
   есть `gcompat` (вопрос 184), иначе стоп с подсказкой `apk add gcompat`.
2. Положить файлы:
   - `/usr/libexec/unitool-sudo/unitool-sudo` (0755) и ссылку `/usr/local/sbin/unitool-sudo`;
   - `sudo_approve.so` в каталог плагинов sudo (`sudo -V` печатает `Plugin path`; Debian и Alpine —
     `/usr/libexec/sudo/`), 0644 root;
   - `/etc/unitool-sudo/` (0755) с `config.toml` и пустым `authorized_keys`, если их нет.
3. `/etc/sudo.conf` **не трогать**, а напечатать, что дописать. Как только в `sudo.conf` есть хоть
   одна строка `Plugin`, sudo перестаёт подставлять sudoers сам, поэтому нужны все строки (D408,
   проверяет спайк S1):
   ```
   Plugin sudoers_policy sudoers.so
   Plugin sudoers_io sudoers.so
   Plugin sudoers_audit sudoers.so
   Plugin sudo_approve sudo_approve.so agent=/usr/libexec/unitool-sudo/unitool-sudo
   ```
   Включение — отдельной командой `install.sh --enable`. Она пишет строки, проверяет `sudo -n true`
   в **уже открытой** root-сессии и откатывает `sudo.conf` из копии, если sudo сломался.
4. `uninstall.sh` убирает строку `sudo_approve` из `sudo.conf` первым делом, потом файлы.

### 9.2. `doctor`

Печатает таблицу `OK/FAIL/WARN` и выходит с кодом 1 при любом FAIL:
- версия sudo ≥ 1.9, плагин в `sudo.conf` и файл на месте;
- host key ed25519 есть, root 0600;
- права `authorized_keys` и каталогов (§4); allowed не пуст; тип каждого ключа проверяемый
  (`ssh-ed25519`, `ecdsa-sha2-nistp*`, RSA ≥ 2048 с `rsa-sha2-*`);
- `client.key` 0600 и разбирается; `--online`: `GET /clients/me` = 200 и `test` проходит;
- `/proc/self/sessionid` не `4294967295` в этой сессии (иначе WARN: «только один раз»);
- `pam_loginuid` в `/etc/pam.d/sshd` (WARN) и `pam_exec … logout` (WARN);
- **пароль как запасной вход (D415):** если `password = "on"`, у каждого пользователя группы
  `sudo-approve` в `/etc/shadow` рабочий пароль (не `!`, `*`, пусто, не истёк). Иначе FAIL:
  «user dev0 has no usable password: run `passwd dev0`, or you lose sudo with your phone». Есть
  `/etc/pam.d/unitool-sudo` (или хост без PAM и доступен `crypt`, спайк S6);
- `password = "off"`: WARN «потеря телефона = потеря sudo; вход только через консоль провайдера»;
- `--online`: `PasswordCheck` для текущего пользователя не вызывается (пароль не спрашиваем в
  `doctor`), только наличие сервиса PAM.

## 10. Тесты агента

Общие тесты (`agent/src/commonTest`) гоняются на JVM и нативно. ОС — через поддельный `Os` с
каталогом-песочницей и выдуманным `/proc`. Сервер — поддельный `ServerApi` в памяти. Время —
`FakeClock`.

| Тест | Что |
|---|---|
| `ConfigTest` | все ключи, границы `timeout`/`max_session`, неизвестный ключ, мусор, номер строки в ошибке |
| `AllowedKeysTest` | два файла, `no-sudo-approve`, `sk-*`/DSA/короткий RSA пропущены; каталог с записью группы → отказ `perm:`; `~/.ssh` не читается (файл есть в песочнице, но не в выборке) |
| `ContextTest` | разбор stdin плагина; повторный `argv`; `run_shell`/`login_shell` и `/etc/shells` → «оболочка»; обрезка `argv` > 4096 |
| `PasswordTest` | поддельные `PasswordCheck` и tty: «p» во время ожидания → `DELETE` запроса → пароль → exit 0 и грант 15 мин; 3 неверных → exit 1; без tty «p» нет; `password = "off"` → строки «p» нет; оболочка → сразу пароль, без сервера; `fallback = password` при `no_device`/таймауте; уведомление (§13.4) отправлено и его сбой не мешает |
| `CheckTest` | весь §5 с поддельным сервером: approved (once/ttl/session), denied (пароль не предлагается), expired, `no_device` + `deny`/`password`, 401, 429, сеть падает и встаёт, Ctrl+C → `DELETE`, `already-delivered`, `Refused` по каждому `verdicts.json` |
| `GrantsTest` | грант живой; лидер умер; PID переиспользован (другой `starttime`); `until` прошёл; `max_session`; ключ убрали из allowed; файл с правами 0644 → удалён; без сессии гранта нет |
| `CountdownTest` | формат строки, `m:ss`, не-tty печатает одну строку |
| `JournalTest` | строки журнала для каждого исхода; ни `cs_`, ни `sealed`, ни полного keyHash |
| `NativeBinaryTest` (jvmTest) | собранный бинарник: `--version`, `keyhash testdata/keys/*.pub` == `keyhash.json`, `check` с контекстом в stdin и поддельным сервером на локальном порту (ktor-server в тесте) |

Контейнерные проверки плагина и всего пути — в e2e (06 §2).

## 11. Журнал

`/var/log/unitool-sudo.log` (0600) и `syslog(LOG_AUTHPRIV | LOG_NOTICE)`, одна строка на событие:

```
2026-10-10T12:00:01Z mu6 user=dev0 runas=root cmd="/usr/bin/apt upgrade" code=K7QX id=sr_… result=approved:ttl until=…Z key=SHA256:…
```

Результаты: `approved:once|ttl|session`, `grant-hit`, `exempt`, `denied`, `expired`, `cancelled`,
`no-device`, `fallback-deny`, `password-ok`, `password-failed`, `password-shell`,
`verify-failed:<причина>`, `perm:<путь>`, `error:<что>`. Сам пароль не пишется никуда, никогда.

## 12. Спайки до кода (шаг 3.0)

| # | Что проверить | Если не вышло |
|---|---|---|
| S1 | Debian 12 в контейнере, sudo 1.9.13: минимальный плагин на C разрешает и отказывает по коду ребёнка; stderr ребёнка виден в терминале; Ctrl+C доходит до ребёнка; `sudo.conf` из §9.1 не ломает обычный sudo | стоп: без плагина нет схемы |
| S2 | Kotlin/Native-бинарник (`hello` из `unitool-cli`) запускается в Alpine 3.20 с `gcompat` и в Ubuntu 22.04/24.04 | Alpine — вопрос 184; без `gcompat` Alpine выпадает из v1 |
| S3 | `ktor-client-curl` для linuxX64 собирается на этой машине и в CI, делает HTTPS-запрос к `https://example.com`, бинарник работает на Debian 12 | (impl) вместо ktor — `execve /usr/bin/curl` с конфигом в stdin (`-K -`), секрет не в argv |
| S4 | в ssh-логине Debian 12 `/proc/self/sessionid` у ребёнка плагина совпадает с сессией логина; у `cron` — `4294967295` | «до выхода» — только по `until`, без проверки лидера |
| S5 | в `settings` approval-плагина есть `run_shell`/`login_shell` при `sudo -s`/`-i` | оболочка определяется только по `/etc/shells` и `argv[0]` |
| S6 | Kotlin/Native cinterop с `libpam` (`pam_start("unitool-sudo")`, `pam_authenticate`, `pam_acct_mgmt`) проверяет пароль на Debian 12 и Ubuntu 24.04; на Alpine без PAM — `crypt(3)` по `/etc/shadow` (yescrypt/sha512) | Alpine — без пароля, только телефон (вопрос 184) |
| S7 | ребёнок плагина читает клавишу из `/dev/tty` в неканоническом режиме и восстанавливает режим терминала после Ctrl+C, «p» и выхода | «p» не работает: пароль только при fallback (после таймаута или без телефона) |

## 13. Вход по паролю локального аккаунта (D411–D416)

### 13.1. Зачем и чем платим

Владелец, 2026-10-10: sudo обычно без пароля, через телефон. Но если телефон потерян или «что-то
произошло», нужно войти по паролю своего локального аккаунта.

- Пароль — **второй равноправный вход**, а не второй фактор. Кто знает пароль, тот получит sudo без
  телефона, как в обычном sudo. Телефон делает обычный вход удобнее и не ослабляет защиту по
  сравнению с «sudo по паролю». Но и сильнее её не делает, пока пароль включён.
- Чтобы вход по паролю не прошёл незаметно, о нём узнаёт телефон (§13.4).
- Кому нужна защита строже, ставит на хосте `password = "off"`. Тогда при потере телефона остаётся
  только консоль провайдера (`doctor` об этом предупреждает).

### 13.2. Как это выглядит

```
$ sudo apt upgrade
🔐 sudo: confirm on your phone [K7QX] — mu6: apt upgrade  2:41   (p = use password)
p
[sudo] password for dev0: ········
Reading package lists… 
```

1. Пока идёт ожидание, агент открывает `/dev/tty` (из контекста плагина), включает неканонический
   режим без эха и ждёт клавишу. «p» (или «P») → дальше пароль. Другие клавиши игнорируются.
   Ctrl+C работает как раньше.
2. Запрос на сервере отменяется: `DELETE /sudo/requests/{id}`. Телефон видит «отменён».
3. `[sudo] password for dev0: ` — тем же видом, что у sudo, без эха. До 3 попыток.
4. Проверка — `PasswordCheck.verify(user, password)`:
   - **PAM** (Debian, Ubuntu): сервис `/etc/pam.d/unitool-sudo` (`@include common-auth`,
     `@include common-account`). Так работают `pam_faillock`, истёкшие пароли, блокировка
     аккаунта — как у самого sudo;
   - **без PAM** (Alpine, спайк S6): `crypt(3)` по хэшу из `/etc/shadow`, сравнение за постоянное
     время, проверка полей срока и блокировки (`!`).
   - Пароль живёт в `SecretBytes`, затирается сразу после проверки. Нигде не пишется.
5. Неверно → пауза 2 с (если PAM не сделал своей), «Sorry, try again.»; после третьей —
   отказ, `password-failed` в журнале.
6. Верно → `password-ok`, грант `kind: password` на `password_grant` (§8), уведомление (§13.4),
   exit 0.

Без терминала (`ssh host sudo cmd` без `-t`, cron) клавиши «p» нет, пароль не спрашивается. Тогда
остаётся телефон, а при недоступном телефоне — отказ.

### 13.3. Когда пароль — единственный путь

- **Оболочка** (`sudo -s`, `sudo -i`, `sudo bash`, любая команда из `/etc/shells`) — только пароль,
  телефон не спрашивается, гранты не действуют (D413, ответ на вопрос 185). На сервер уходит только
  уведомление §13.4. Терминал печатает «root shell: password required».
- **Телефон недоступен** (`no_device`, `401`, нет связи, молчал до `exp`) и `fallback = "password"` —
  сразу запрос пароля, без «p».

### 13.4. Телефон узнаёт о входе по паролю (D416)

После верного пароля агент шлёт `POST /sudo/notices {keyHashes, kind: "password", host, user,
code}` — без команды. Это best-effort: одна попытка, 3 с, ошибка не мешает sudo. Сервер шлёт
видимый пуш «mu6: dev0 — sudo по паролю». Мини-апп кладёт его в историю. Если пароль узнал кто-то
чужой, владелец увидит это на телефоне.

### 13.5. Потерял телефон — что делать (уходит в `unitool-sudo/README.md`)

1. На хосте: `sudo …`, нажать «p», ввести пароль своего аккаунта.
2. Убрать строку ключа потерянного телефона из `/etc/unitool-sudo/authorized_keys` (или
   `/root/.ssh/authorized_keys`). С этого момента запросы его хэш не несут, и старый телефон не может
   ни разрешить, ни даже увидеть запрос. Закрытый ключ на потерянном телефоне защищён чипом и
   биометрией или паролем ключа (`ssh-new`).
3. Новый телефон: Unitool, промокод `{sudo}`, новый ключ → «Получать запросы» → строка ключа в
   `authorized_keys`.
4. Сервер ничего делать не обязан: устройство потерянного телефона просто больше не получает
   запросов. Удалить его из админки — по желанию.

Результаты — таблица в `PROGRESS.md` §2.
