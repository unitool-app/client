# 01 — Контракт: хэши, запрос, ответ, проверка, API, фикстуры

> **Версия:** 1.2 · **Дата:** 2026-10-10 · Контракт между тремя частями: агентом на хосте, сервером
> и мини-аппом. Если этот файл расходится с README, прав README. С остальными файлами прав этот.
>
> Протокол меняется только целиком, в одном шаге и в таком порядке: эта заметка → фикстуры
> (`unitool-sudo/testdata/`) → `kit.sudo` → сервер → агент → приложение (00-TASK §5).

## 1. Слова

| Слово | Что это |
|---|---|
| **хост** | машина, где человек набирает `sudo`. На ней стоят агент `unitool-sudo` и плагин |
| **клиент** `c_…` | хост глазами сервера: id `c_` + 12 base58, секрет `cs_` + 32 base58 (D382) |
| **устройство** `d_…` | телефон с Unitool глазами сервера; уже есть на сервере (`/devices`) |
| **ключ** | SSH-ключ в кольце телефона (`SshKeyring`); его закрытая половина телефон не покидает никогда |
| **allowed** | открытые ключи, которым хост доверяет sudo (§3 README, D378): `/root/.ssh/authorized_keys` и `/etc/unitool-sudo/authorized_keys` |
| **keyHash** | хэш **открытого** ключа (§2). Только он связывает хост, сервер и телефон |
| **R** | запрос: что именно собираются выполнить (§3). Собирает агент |
| **A** | ответ человека: решение и срок (§4). Собирает телефон |
| **sealed** | `A` + подпись, зашифрованные age на ключ хоста (§5). Сервер видит только его |
| **грант** | разрешение, которое агент хранит у себя после ответа «на время» или «до выхода» (04 §8) |

## 2. keyHash (D376, D377, D405)

```
keyHash = lowercase_hex( SHA-256( "unitool-sudo/v1" ‖ 0x00 ‖ blob ) )      // 64 символа [0-9a-f]
blob    = открытый ключ в wire-формате SSH: base64-декод второй колонки строки authorized_keys
          (= SshPublicKey.blob)
```

- Хэшируется **открытый** ключ. Хост знает только открытые ключи (из `authorized_keys`), поэтому
  совпасть с телефоном может только хэш открытой половины. Закрытый ключ, его хэш или что-то,
  производное от него, с телефона не уходит (D405).
- Одна реализация: `kit.sudo.KeyHash.of(blob: ByteArray): String` в `:unitool-kit`. Ею пользуются и
  телефон, и агент (D401). Сервер хэшей не считает, он только сверяет строки по регулярке
  `^[0-9a-f]{64}$`.
- Префикс `unitool-sudo/v1p` зарезервирован под «перец» (вопрос 177). Код v1 его не понимает.
- Независимая сверка: `testdata/gen.sh` считает хэши фикстур shell-командой
  `{ printf 'unitool-sudo/v1\0'; awk '{print $2}' k.pub | base64 -d; } | sha256sum`, без нашего кода.

## 3. Запрос R

### 3.1. Поля

| Поле | Тип | Обязательно | Ограничения и смысл |
|---|---|---|---|
| `v` | int | да | `1` |
| `host` | string | да | `gethostname()`, ≤ 253 байт |
| `hostKey` | string | да | `ssh-ed25519 AAAA…` из `/etc/ssh/ssh_host_ed25519_key.pub`, **без комментария** |
| `user` | string | да | кто вызывает sudo, ≤ 64 |
| `uid` | int | да | его uid |
| `runas` | string | да | от чьего имени выполнить (`root` по умолчанию), ≤ 64 |
| `argv` | string[] | да | команда после разбора sudoers, `argv[0]` — полный путь; сумма длин ≤ 4096 байт |
| `argvTruncated` | bool | нет | есть только со значением `true`, если `argv` обрезан |
| `cwd` | string | да | ≤ 1024 |
| `tty` | string | нет | `/dev/pts/3`; нет поля, если терминала нет |
| `session` | int | нет | `/proc/self/sessionid`; нет поля, если там `4294967295` |
| `nonce` | string | да | 16 случайных байт, base64url без `=` (22 символа) |
| `iat` | int | да | unix-секунды, время хоста |
| `exp` | int | да | `iat` + `timeout` (30–300, по умолчанию 180) |
| `code` | string | да | 4 символа из `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` |

Поля `shell` нет (1.2, D413): оболочка (`sudo -s`, `sudo -i`, команда из `/etc/shells`) на телефон
не попадает вовсе, её открывает только пароль (04 §13.3).

Всё, что не в таблице, — ошибка разбора. Неизвестное поле не пропускается: телефон должен показать
человеку **всё**, что он подписывает, а показать то, чего не знает, он не может.

### 3.2. Канонический JSON (D403)

R, A и конверт §5 пишутся одним и тем же каноническим JSON. `kit.sudo.CanonicalJson` — свой, без
kotlinx.serialization, потому что `:unitool-kit` живёт без зависимостей (`checkDependencyRules`).

- Объект: ключи по возрастанию кодов символов, без пробелов и переводов строк. `null` нет:
  отсутствующее значение — отсутствующий ключ.
- Строки — UTF-8. Экранируются только `"`, `\` и символы `< 0x20`: `\n`, `\t`, `\r`, `\b`, `\f` и
  `\u00XX` с маленькими hex-буквами. Остальные символы, включая не-ASCII, пишутся как есть.
- Числа — только целые от `0` до `2^53−1`, без ведущих нулей, знака и экспоненты.
- `true`/`false`, массивы строк.
- **Разбор строгий.** `parse(bytes)` разбирает, кодирует обратно и требует совпадения байт в байт.
  Иначе — `NotCanonical`. Так у одного R ровно одно представление, и `sha256(R)` однозначен.
- Хэш всегда считается от **байтов**, которые пришли или ушли, а не от повторной сериализации.

Пример R (пробелы и переносы здесь только для чтения, в байтах их нет):

```json
{"argv":["/usr/bin/apt","upgrade"],"code":"K7QX","cwd":"/root","exp":1791612525,
 "host":"mu6","hostKey":"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI…","iat":1791612345,
 "nonce":"q0n0Vb1W2xQm3s9fJk2p1A","runas":"root","session":4182,"tty":"/dev/pts/3",
 "uid":1000,"user":"dev0","v":1}
```

### 3.3. Что проверяет телефон до подписи

1. R разбирается строго, `v == 1`, все обязательные поля на месте.
2. `R.exp` не в прошлом по часам телефона, с допуском 30 с. Иначе экран «истёк», кнопки серые.
3. Хотя бы один хэш из `Req.keyHashes` совпадает с хэшем включённого ключа этого телефона.
4. `R.hostKey` разбирается как `ssh-ed25519`. Пин по TOFU (D390) выглядит так:
   `(clientId, R.host, R.hostKey)`.
5. `argvTruncated` → нельзя ответить «на время» и «до выхода», только «один раз» или «отклонить».

## 4. Ответ A

| Поле | Тип | Смысл |
|---|---|---|
| `v` | int | `1` |
| `req` | string | `lowercase_hex(sha256(байты R))` |
| `decision` | string | `once` · `ttl` · `session` |
| `until` | int | `once` → `R.exp`; `ttl` → `now + 900` или `now + 3600`; `session` → `now + 43200` (потолок 12 ч) |

```json
{"decision":"ttl","req":"5f1c…64 hex…","until":1791616000,"v":1}
```

- Подпись: `SshKeyring.sign(key, namespace = "sudo-approve@superizer", data = байты A)` →
  армированный SSHSIG (`-----BEGIN SSH SIGNATURE-----`, sha512). Это обычный `ssh-keygen -Y sign
  -n sudo-approve@superizer`, и проверяется он обычным `ssh-keygen -Y verify`.
- **Отказ** не подписывается и не шифруется: `POST …/answer {"decision":"deny"}`. Поддельный отказ
  от сервера ничего не даёт сверх того, что сервер и так может: молчать.
- `now` — часы телефона. Хост принимает `until ≤ hostNow + 43200 + 300` (5 мин на разницу часов) и
  сам режет срок гранта по своему `max_session`.

## 5. Конверт sealed (D380)

```
inner  = CanonicalJson { "a": base64(байты A), "sig": "<армированный SSHSIG>" }
sealed = Age.armor( Age.encrypt( inner, recipients = [ SshPublicKey(R.hostKey) ] ) )
```

- Получатель — **ключ хоста из R**, а не что-то, что прислал сервер отдельно. Подменить ключ хоста
  в R сервер может, но тогда `sha256(R)` в A не совпадёт с тем, что держит агент (README §2).
- Агент открывает конверт закрытым `/etc/ssh/ssh_host_ed25519_key` (без пароля, root:root 0600).
  Это та же функция `Age.decrypt` с программной идентичностью, что уже есть в `services/ssh`, после
  переезда в `kit.ssh` (D402).
- `sealed` ≤ 16 КБ (RSA-4096 даёт SSHSIG около 1 КБ; с запасом).

## 6. Проверка на хосте (D381)

`kit.sudo.Verdict.check(request: ByteArray, keyHashes: Set<String>, sealed: String, hostKey: AgeIdentity,
allowed: List<SshPublicKey>, now: Long, maxSession: Long): Verdict` — чистая функция, одна на
агента и на тесты. Порядок пунктов — порядок кода, первый провал даёт причину:

| # | Проверка | Причина в журнале |
|---|---|---|
| 1 | `sealed` деармируется и открывается ключом хоста | `age` |
| 2 | `inner` — канонический JSON с ровно `a` и `sig`, `a` — канонический A, `v == 1` | `json` |
| 3 | `A.req == hex(sha256(request))` | `req-mismatch` |
| 4 | `K = SshSig.signer(sig)` существует, и `K ∈ allowed` (сравнение по `blob`) | `key-not-allowed` |
| 5 | `KeyHash.of(K.blob) ∈ keyHashes` — ключ был среди тех, кого спросили | `hash-not-requested` |
| 6 | `SshSig.verify(sig, байты A, "sudo-approve@superizer", K) == Valid` | `sig-invalid` / `sig-unsupported` |
| 7 | `now ≤ R.exp` (R — свой, из памяти) | `expired` |
| 8 | `decision ∈ {once, ttl, session}`; для `ttl`/`session` `until ≤ now + maxSession + 300` | `until-too-far` / `decision-unknown` |
| 9 | для `ttl`/`session` у R есть `session` | `session-unavailable` → грант не пишется, команда разрешается как `once` |

`Verdict = Approved(decision, until, signer: SshPublicKey) | Refused(reason: String)`.

## 7. HTTP API (D384)

База `/api/unitool/v1`. Ошибки — `{error:{code,message}}` (контракт сервера). Новые коды:
`conflict` (409), `gone` (410), `no_device` (404).

### 7.1. Хост (клиент)

| Метод и путь | Auth | Тело | Ответ |
|---|---|---|---|
| `POST /clients` | нет; 30/ч на IP | `{label: 1–80, secretHash: hex64}` | `201 {clientId}` |
| `GET /clients/me` | `Bearer c_….cs_…` | — | `200 {clientId, label, createdAt, lastSeenAt}` |
| `PATCH /clients/me` | то же | `{label}` | `200` как GET |
| `DELETE /clients/me` | то же | — | `204`; запросы клиента уходят в `cancelled` |
| `POST /sudo/requests` | то же; 6/мин на клиента | `{keyHashes: hex64[1..16], request: base64 ≤ 8 КБ, code, ttlSeconds: 30–300}` | `201 {id, expiresAt, devices}`; `404 no_device`; `429` |
| `GET /sudo/requests/{id}?wait=0..30` | то же; только свои | — | `200 {state, sealed?}`. При `pending` ждёт до `wait` с |
| `DELETE /sudo/requests/{id}` | то же | — | `204` → `cancelled`; уже финальный — `204` без изменений |
| `POST /sudo/notices` | то же; 6/мин на клиента | `{keyHashes: hex64[1..16], kind: "password", user: 1–64, code}` | `202 {devices}`; ничего не хранится, только пуш `sudo-notice` (D416) |

- `secretHash` = sha256 от `cs_…`, ровно как у устройства (`registerDeviceSchema`).
- `devices` в ответе на `POST` — сколько устройств получили пуш (для текста в терминале). Ноль
  при ненулевом числе держателей хэша означает, что все они заглушили этот хост. Запрос всё равно
  создаётся и лежит во входящих.
- `sealed` отдаётся **один раз**: первый `GET`, увидевший `approved`, получает его, и поле в БД
  обнуляется. Повторный `GET` даёт `{state:"approved"}` без `sealed`. Агент, потерявший ответ из-за
  обрыва, получает отказ (D386).

### 7.2. Устройство

| Метод и путь | Тело | Ответ |
|---|---|---|
| `PUT /sudo/keys` | `{hashes: hex64[0..32]}` | `200 {hashes}`; набор заменяется целиком, `[]` — отписаться |
| `GET /sudo/keys` | — | `200 {hashes}` |
| `GET /sudo/inbox` | — | `200 {requests: Req[], serverNow}` — `pending`, не истёкшие, хотя бы один хэш мой |
| `GET /sudo/requests/{id}` | — | `200 Req`; `404`, если ни одного моего хэша (не 403 — чтобы не раскрывать, что запрос есть) |
| `POST /sudo/requests/{id}/answer` | `{decision:"deny"}` или `{decision:"approve", sealed}` | `200 {state}`; `409 conflict` — уже отвечено; `410 gone` — истёк или отменён |
| `PUT /sudo/mutes/{clientId}` | `{until: unix-сек, ≤ now + 7 сут}` | `200`; `until ≤ now` снимает заглушку |
| `GET /sudo/mutes` | — | `200 {mutes: [{clientId, label, until}]}` |

```ts
Req = { id: "sr_…", code: "K7QX", client: { id: "c_…", label: "mu6" },
        keyHashes: string[] /* только мои */, request: string /* base64 R */,
        createdAt: number, expiresAt: number, state: "pending"|"approved"|"denied"|"expired"|"cancelled" }
```

### 7.3. Состояния запроса

```
            answer approve ─▶ approved ──(GET отдал sealed)──▶ approved, sealed = NULL
pending ──┼ answer deny    ─▶ denied
            DELETE         ─▶ cancelled
            сторож, exp    ─▶ expired
```

Из финального состояния выхода нет. `answer` на финальном → 409 (`approved`/`denied`) или 410
(`expired`/`cancelled`).

### 7.4. Пределы (D387)

| Что | Предел | Ответ |
|---|---|---|
| `POST /clients` | 30 в час на IP | `429` + `Retry-After` |
| `POST /sudo/requests` | 6 в минуту на клиента | то же |
| ожидающих на один `keyHash` | 3 | `429`, `message: "too many pending requests for this key"` |
| `PUT /sudo/keys` | 32 хэша на устройство | `400 invalid` |

## 8. Пуши (D388)

Формат — `buildUnitoolMessage` сервера (схема 1 Superizer). Новое поле `interruptionLevel` (D398)
даёт `aps["interruption-level"]`.

| Поле | `sudo` (видимый) | `sudo-gone` (тихий) | `sudo-notice` (видимый, 1.2) |
|---|---|---|---|
| `appId` | `sudo-approve` | `sudo-approve` | `sudo-approve` |
| `link` | `unitool://app/sudo-approve/request?id=sr_…` | `unitool://app/sudo-approve/inbox` | `unitool://app/sudo-approve/inbox` |
| `title` / `body` | `sudo · K7QX` / `mu6: подтвердите на телефоне` (ru) или `mu6: confirm on your phone` (en) по `locale` устройства | — | `sudo по паролю` / `mu6: dev0 вошёл по паролю` (ru) или `sudo by password` / `mu6: dev0 used a password` (en) |
| `silent` | `false` | `true` | `false` |
| `data` | `{"v":1,"kind":"sudo","id":"sr_…"}` | `{"v":1,"kind":"sudo-gone","id":"sr_…"}` | `{"v":1,"kind":"sudo-notice","notice":"password","client":"c_…","user":"dev0","code":"K7QX"}` |
| `collapseKey` | id запроса | id запроса | — |
| `channelId` | `unitool.sudo` | — | `unitool.general` |
| `ttlSeconds` | `expiresAt − now` | 60 | 86400 |
| `interruptionLevel` | `time-sensitive`, но только после ответа на вопрос 182 (05 §1.3); до того поля нет | — | — |

Команда и путь в пуш не попадают (в `sudo-notice` есть только имя пользователя): их видно только на экране запроса. Android `tag`
берётся из `link` (так у сервера сейчас), поэтому у двух запросов теги разные.

## 9. Фикстуры (`/prj/unitool/unitool-sudo/testdata/`)

Источник правды — файлы. Их делает `gen.sh` **настоящими** `ssh-keygen`, `age` и `sha256sum`, а не
нашим кодом. Так фикстуры заодно проверяют совместимость.

| Файл | Что |
|---|---|
| `keys/` | тестовые ключи: `ed25519`, `ecdsa-p256`, `ecdsa-p384`, `rsa-3072` (пары) и `host_ed25519` (ключ «хоста»). Все с пометкой `TEST ONLY` в комментарии |
| `keyhash.json` | `[{type, publicLine, keyHash}]` для 4 ключей; хэш посчитан shell-командой §2 |
| `requests.json` | канонические R (base64): обычный, `argvTruncated`, без `session`, без `tty`, с не-ASCII в `argv`; и **неканонические**: пробел, порядок ключей, `1.0`, `\u0041` вместо `A`, лишнее поле (в том числе `shell`), `null` |
| `answers.json` | для каждого ключа: A (base64), `sig` из `ssh-keygen -Y sign -n sudo-approve@superizer`, `sealed` из `age -R host_ed25519.pub -a` |
| `verdicts.json` | случаи для `Verdict.check`: `request`, `keyHashes`, `sealed`, `allowed`, `now` → ожидаемый `Approved(…)` или `Refused(reason)`. Минимум по одному на каждую строку §6 и на подмену `argv`, `hostKey`, `nonce`, `code`, чужой namespace |
| `SHA256SUMS` | суммы всех файлов выше |

- `gen.sh` перезапускается только по необходимости. Ключи и случайные данные он берёт из
  фиксированного seed-каталога и меняет только то, что меняется (подписи RSA детерминированы,
  ed25519 тоже; ECDSA и age — нет, поэтому их значения фиксируются в файле один раз).
- Kotlin читает фикстуры через сгенерированный `SudoVectors.kt` (commonTest `:unitool-kit`). Тест
  `SudoVectorsInSyncTest` (desktopTest) сверяет его с JSON и `SHA256SUMS`.
- Сервер берёт из фикстур только формы: `keyHash`, base64 R, непрозрачный `sealed`. Копия лежит в
  `layers/app-unitool/test/fixtures/sudo/`, и тест сверяет её `SHA256SUMS` с исходником, если
  путь `../../unitool/unitool-sudo/testdata` доступен. Иначе тест пропускается с пометкой.
