# 00 — разведка: native messaging на Linux (шаг 0)

> 2026-10-25. Риск из README §12: «Firefox из snap (Ubuntu по умолчанию)». Итог: **работает во всех
> трёх**, останавливаться не нужно. Проба лежит в `recon/`.

## Что проверяли

Расширение MV3 (`recon/manifest.json`, `recon/bg.js`): при старте шлёт host `sendNativeMessage` ×2
(второй раз — с ответом на первый) и открывает `connectNative` с обменом в обе стороны. Host —
`recon/host.py` (stdio, длина 4 байта LE + JSON): пишет в лог `argv`, родителя и каждое сообщение,
отвечает `{pong, home, ssh, keyReadable, env_snap}`. Если ответ дошёл до расширения, в логе есть
шаг 2 с `got`, а для порта — шаг 4.

## Итог

| Браузер | Где | Host запущен | Ответ дошёл | Кто родитель host | `argv` |
|---|---|---|---|---|---|
| Chromium 145.0.7632.45 (Mint, .deb) | эта машина, Xvfb, `--load-extension` | да | да, оба способа | Chromium | `chrome-extension://<id>/` |
| Google Chrome 156.0.8078.47 (.deb, распакован без установки) | эта машина, puppeteer `installExtension` (CDP `Extensions.loadUnpacked`) | да | да, оба способа | Chrome | `chrome-extension://<id>/` |
| Firefox 146.0.1 (Mint, .deb) | эта машина, `web-ext run` | да | да, оба способа | Firefox | `<путь манифеста> <id расширения>` |
| **Firefox 158.0.1 snap** (Mozilla, строгая изоляция) | Docker `ubuntu:24.04` + systemd + snapd 2.77.1, `xdg-desktop-portal` 1.18.4-1ubuntu2.24.04.3 | **да, через портал** | **да, оба способа** | `xdg-desktop-portal` | `<путь манифеста> <id>` |

Во всех случаях host работает от имени пользователя, видит настоящий `$HOME` и читает
`~/.ssh/id_ed25519` (в snap: `SNAP` в окружении host пуст — он вне песочницы).

## Snap-Firefox: как это устроено (важно для `install` и попапа)

- Snap-Firefox сам host не запускает. Он идёт в портал `org.freedesktop.portal.WebExtensions`
  (Ubuntu-патч в `xdg-desktop-portal`), портал читает манифест из **настоящего**
  `~/.mozilla/native-messaging-hosts/` — того же места, что у Firefox из .deb. Отдельный путь для
  snap в `install` не нужен. Портал включён по умолчанию: `widget.use-xdg-desktop-portal.native-messaging`
  трогать не надо.
- Firefox 158 сначала пробует новый `org.freedesktop.NativeMessagingProxy`; в Ubuntu 24.04 его нет,
  он молча откатывается на портал.
- **При первом запуске портал спрашивает человека** («разрешить Firefox запускать
  `cx.m42.unitool`?», окно реализации портала — GNOME/GTK). Ответ хранится в хранилище прав:
  таблица `webextensions`, ключ — имя host, приложение `snap.firefox`. Пока никто не ответил или если
  отказали, расширение получает `An unexpected error occurred` (не «No such native application»).
  В контейнере окно открылось, но нажать было некому; дальше проверяли с заранее записанным
  разрешением (`flatpak permission-set webextensions cx.m42.unitool.probe snap.firefox yes`).
  → **D555**.
- Host запускается с окружением портала, а не Firefox: переменные, которые видел Firefox, в host не
  приходят. Host не должен полагаться на окружение браузера (проба сначала упала ровно на этом).

## Что не проверено

- Снимка самого окна портала нет: видно только по журналу (`Failed to associate portal window with
  parent window`) и по ошибке после ожидания. Текст окна на настоящей Ubuntu — посмотреть при ручной
  проверке.
- Чистая Ubuntu 24.04 с GNOME на железе — нет; был контейнер. Ради него пришлось сделать `/run`
  shared (`mount --make-rshared`) и отдать user-systemd `DISPLAY`; на настоящем рабочем столе это
  так и есть.
- Chrome/Chromium из Flatpak — не проверяли (план: «без обёрток не работает», в v1 не поддерживаем).
- Brave и Edge — не ставили; путь манифеста у них по образцу Chrome (`~/.config/BraveSoftware/Brave-Browser/`,
  `~/.config/microsoft-edge/`).

## Заметки для e2e (шаг 7)

- Branded Chrome 137+ не принимает `--load-extension`; Chromium 145 принимает. Для Chrome —
  puppeteer `enableExtensions` + `installExtension` по pipe. Распакованный Chrome без suid-песочницы
  требует `--no-sandbox` (только для проб).
- Каталог манифестов Chromium считается от `--user-data-dir` (`<dir>/NativeMessagingHosts/`), так
  что e2e не трогает `~/.config`. Firefox ищет только в `~/.mozilla/native-messaging-hosts/`.
- id распакованного расширения в Chromium задаётся полем `key` манифеста (SHA-256 открытого ключа,
  первые 32 hex → `a–p`).
