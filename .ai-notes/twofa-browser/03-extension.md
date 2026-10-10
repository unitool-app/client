# 03 — Расширение: `unitool-browser/`

> Шаг 3 в 00-TASK. Контракт — 01. Расширение — тонкое: оно показывает QR, шифрует запрос, ждёт и
> вставляет код. Решений о доступе оно не принимает, секретов TOTP не видит.

## 1. Платформы и форма

- **Chrome** (и всё на Chromium: Edge, Brave, Opera, Vivaldi) — Manifest V3, фоновый
  service worker.
- **Firefox** ≥ 128 — Manifest V3, фоновая «event page» (`background.scripts`).
  `browser_specific_settings.gecko.id = "unitool-2fa@m42.cx"`.
- **Safari** — нет в v1 (вопрос 196).
- Один код, два манифеста: `manifest/base.json` + `chrome.json` / `firefox.json`, склеиваются при
  сборке.

## 2. Код

```
unitool-browser/
  package.json         esbuild, typescript, vitest, @noble/{curves,ciphers,hashes}, uqr (QR)
  tsconfig.json        strict
  build.mjs            сборка в dist/chrome, dist/firefox; npm run zip → .zip и .xpi
  manifest/            base.json, chrome.json, firefox.json
  _locales/{ru,en}/messages.json
  src/
    crypto/
      b64.ts           base64url без '='
      canonical.ts     канонический JSON (01 §5)
      envelope.ts      seal/open (01 §6)
      link.ts          ключи привязки (01 §4), hello
      otp.ts           R, A, проверки (01 §5, §7)
    api.ts             клиент сервера: Bearer, ошибки → типы, wait
    store.ts           storage.local (§4), миграция по полю v
    background.ts      состояние, привязка, запрос, long-poll, бейдж
    popup/             popup.html, popup.ts, popup.css
    options/           options.html, options.ts
    fill.ts            функция для scripting.executeScript, без импортов (§6)
    qr.ts              uqr → SVG
  testdata/            gen.py, vectors.json (01 §10)
  test/                vitest
  e2e/                 Playwright + phone-sim (05 §2)
  README.md            что это, как поставить из папки, что видит сервер
```

Зависимости — только перечисленные. Каждая новая — строка в `README.md` с причиной. Никаких
фреймворков UI: экранов четыре, это DOM и CSS.

## 3. Манифест (D448)

```json
{
  "manifest_version": 3,
  "name": "__MSG_name__",
  "default_locale": "en",
  "permissions": ["storage", "activeTab", "scripting"],
  "host_permissions": ["https://unitool.m42.cx/*"],
  "action": { "default_popup": "popup/popup.html" },
  "options_ui": { "page": "options/options.html", "open_in_tab": false },
  "commands": { "_execute_action": { "suggested_key": { "default": "Alt+Shift+2" } } },
  "content_security_policy": { "extension_pages": "script-src 'self'; object-src 'none'" }
}
```

- Нет `content_scripts`, нет `<all_urls>`, нет `externally_connectable`, нет `notifications`,
  нет `clipboardWrite` (копирует попап, у него есть жест пользователя).
- `activeTab` даёт доступ к активной вкладке после клика по значку или по `Alt+Shift+2`. Больше
  ничего.
- Другой сервер (своя установка) — `optional_host_permissions` `https://*/*` и запрос права в
  настройках, только если человек сменил адрес. По умолчанию не используется.

## 4. Хранение (D435, D454)

`storage.local`:

```json
{
  "v": 1,
  "server": "https://unitool.m42.cx",
  "label": "Chrome · Linux",
  "client": { "id": "c_…", "secret": "cs_…" },
  "link": { "id": "l_…", "kReq": "<b64url>", "kAns": "<b64url>", "phone": "Pixel 7", "linkedAt": 1791612345 },
  "pairing": { "id": "op_…", "token": "pt_…", "b": "<b64url>", "s": "<b64url>", "expiresAt": 1791612945 }
}
```

- `pairing` живёт только во время привязки и стирается при любом исходе.
- Запрос в работе (`id`, `sealedR`, `n`, `origin`, `tabId`) — в памяти фона. Код — только в памяти
  фона и попапа, до `validTo + 5 с` (D454). В `storage` (включая `storage.session`), `console` и
  ошибках кода нет.
- Клиент `c_` заводится при первой привязке (`POST /clients`), секрет рождается здесь.

## 5. Экраны

Попап, 360 px шириной. Состояния:

| Состояние | Что видно |
|---|---|
| **Не привязан** | «Привяжите телефон с Unitool, чтобы брать коды 2FA с него» · [Привязать телефон] |
| **Привязка** | QR (01 §3) · «Unitool → 2FA → ⋮ → Браузеры → Привязать, отсканируйте код» · таймер 10:00 · [Отмена]. Попап закрыли — привязка продолжается в фоне, при следующем открытии QR снова на экране |
| **Привязано** | «Привязано к «Pixel 7»» на 3 с, затем «Готово» |
| **Готово** | имя сайта вкладки крупно · [Запросить код] · мелко «Телефон: Pixel 7». Не `https:` — кнопка неактивна, «коды выдаются только сайтам по https» |
| **Ждём телефон** | «Подтвердите на телефоне» · **число сверки 47** крупно · «выберите на телефоне это же число» · таймер 2:00 · [Отмена] |
| **Код пришёл** | «GitHub · xziy» · `492 039` · «Вставлено в поле» или [Вставить] · [Копировать] · «действует ещё 21 с» |
| **Отказ** | «Отклонено на телефоне» |
| **Истекло** | «Телефон не ответил за 2 минуты» · [Ещё раз] |
| **Отвязан** | «Телефон отвязал этот браузер» · [Привязать снова] |
| **Нет связи** | «Сервер Unitool недоступен» · [Повторить] |

Настройки: имя браузера (видно на телефоне; меняется до привязки, после — «отвяжите и привяжите
снова»), «Отвязать телефон», блок «Что видит сервер» (D433 простыми словами), адрес сервера под
«Дополнительно».

Попап закрыт, а код пришёл — бейдж на значке «1». Открыли попап — код на экране, если ещё действует.

Тексты — `_locales/ru` и `_locales/en`, `chrome.i18n.getMessage`. Тон — как в 2FA Unitool.

## 6. Вставка (D453)

`fill.ts` — одна функция без импортов (её сериализует `executeScript`), аргументы `code`,
`expectedOrigin`:

1. `location.origin !== expectedOrigin` → вернуть `wrong_origin`, ничего не трогать.
2. Цель:
   - `input[autocomplete="one-time-code"]`, видимое и не `disabled`;
   - иначе `document.activeElement`, если это `input` типа `text`/`tel`/`number`/`password`;
   - иначе видимое `input` с `otp|totp|2fa|mfa|code|token` в `name`, `id`, `aria-label`,
     `placeholder` (ровно одно);
   - иначе ряд из 6–8 видимых `input[maxlength="1"]` подряд — по цифре в каждое.
3. Запись — через нативный сеттер `HTMLInputElement.prototype.value` и события `input` и `change`
   (React и Vue иначе не видят значение).
4. Не отправлять форму. Нажать «Войти» — дело человека.
5. Вернуть `filled` / `not_found` / `ambiguous`. Внутри фреймов не искать (v1).

Вставка запускается сама, когда код пришёл, попап открыт, вкладка та же (`tabId`) и `origin` не
поменялся. Иначе — кнопки [Вставить] и [Копировать].

## 7. Фон

- **Привязка.** `POST /otp/pairings` → сгенерировать `b`, `s` → QR → `GET /otp/pairings/{op}?wait=25`
  в цикле до `claimed` или конца срока → ключи и `hello` (01 §4) → `accept` или `DELETE`.
- **Запрос.** Взять `origin` активной вкладки (`tabs.query` под `activeTab`) → `R` → `sealedR` →
  `POST /otp/requests` → `GET …?wait=25` в цикле до ответа или `expiresAt` → проверка (01 §7) →
  код в память, сообщение попапу, вставка.
- **Не дать фону уснуть.** В Chrome service worker засыпает через 30 с без событий. Пока идёт
  привязка или запрос, фон раз в 20 с вызывает `chrome.runtime.getPlatformInfo()` — обычный
  способ продлить жизнь на время работы. После — перестаёт.
- **Ошибки сервера:** `401` → клиент удалён: стереть `client`, `link`, экран «Не привязан»;
  `410 gone` по привязке → «Отвязан»; `429` → «слишком часто, подождите»; сеть — повтор через
  2, 4, 8 с в пределах срока запроса.
- **Отмена** из попапа — `DELETE /otp/requests/{id}`.

## 8. Тесты (vitest)

| Тест | Что |
|---|---|
| `crypto.vectors.test.ts` | все наборы `testdata/vectors.json` (01 §10), включая `bad` |
| `canonical.test.ts` | 12 пар из фикстур + отказ на `NaN`, дробях, `undefined` |
| `otp.test.ts` | проверки `A`: чужой `req`, чужой `requestId` в `ad`, не цифры — отказ |
| `fill.test.ts` | jsdom: шесть разметок (`one-time-code`, фокус, по имени, 6 клеток, два кандидата → `ambiguous`, чужой `origin`); React-подобный контролируемый input видит значение |
| `background.test.ts` | поддельные `fetch` и `chrome.*`: привязка, запрос, отмена, 401, 410, 429, сон фона |
| `store.test.ts` | миграция `v`, `pairing` стирается при любом исходе; канарейка кода не попадает в `storage` |
| `manifest.test.ts` | собранные манифесты: ровно права D448, CSP, `gecko.id` |

Команды: `npm test`, `npm run build` (tsc + esbuild), `npm run zip`. Все зелёные — условие коммита.
`scripts/verify.sh` Unitool получает шаг `unitool-browser` (`npm ci && npm test && npm run build`).

## 9. Готово, когда

- тесты §8 зелёные, сборка даёт `dist/chrome` и `dist/firefox`;
- `Load unpacked` в Chrome и `about:debugging` в Firefox: расширение ставится без предупреждений о
  правах сверх «читать и изменять данные на unitool.m42.cx»;
- e2e 05 §2 проходят.
