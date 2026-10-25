# unitool-browser — the Unitool extension

The browser extension called simply **Unitool** (D554). Today it does one thing: it takes a
password from Unitool → Passwords on your phone and fills it into the page open in this browser. The
design is in [`.ai-notes/passwords-browser`](../.ai-notes/passwords-browser/README.md); the 2FA codes
of `.ai-notes/twofa-browser` are to live in the same extension.

The extension decrypts nothing and keeps nothing (D535). It talks only to `unitool browser host` on
this computer (native messaging, host `cx.m42.unitool`); the host finds the phone in the Wi-Fi,
asks, and opens the answer — age, encrypted to this computer's SSH key — with the key in `~/.ssh`.
On the phone you see the site, pick the entry, and confirm with the device's lock, every time.

## Install from this folder

```sh
npm ci && npm run build                  # dist/chrome and dist/firefox
unitool browser install                  # once: lets the browsers start the host
```

- **Chrome, Chromium, Brave, Edge:** `chrome://extensions` → Developer mode → Load unpacked →
  `dist/chrome`. The `key` in `manifest/chrome.json` fixes the id to
  `omcnlonedghinfecceojlapofcjfcpoi`, the one `unitool browser install` allows (D567). A store id,
  once there is one: `unitool browser install --extension-id ID`.
- **Firefox (128+, snap included):** `about:debugging` → This Firefox → Load Temporary Add-on →
  `dist/firefox/manifest.json`. Firefox from snap asks once whether it may start Unitool — allow it
  (D555). For a permanent install the add-on has to be signed by AMO.

Then click the toolbar button or press **Ctrl+Shift+L** on a login page. The first time, «Pair a
phone» shows a QR code; scan it in Unitool → Passwords → Computers → Pair a computer.

## What it may do (D549)

`nativeMessaging`, `activeTab`, `scripting`, `storage` — nothing else. No host permissions, no
content scripts, no `externally_connectable`: a page cannot reach the extension, and the extension
touches a page only after your click, only in that tab, and fills only fields of the site the
request was for. It never submits a form.

## What it keeps

- `storage.local`: `{ v: 1, paired: true|false }` — to draw the first frame of the popup.
- The password: in the background's memory from the host's answer until it is in the field, or — if
  no field took it — until you press Copy or a minute passes. A JavaScript string cannot be zeroed;
  the last reference is dropped. Never in `storage`, never logged, never sent to the popup except as
  the answer to its own Copy click.
- The clipboard: the popup clears a copied password after 60 s **only while it stays open**; the
  popup says so.

## Code

```
manifest/      base.json + chrome.json / firefox.json, merged by build.mjs
_locales/      ru, en
src/fill.ts    the function executeScript runs in the page — no imports
src/core.ts    the background: one native port, one operation at a time
src/popup/     the popup: draws what the background says
test/          vitest: fill (jsdom), the background on fake chrome.*, the built manifests
```

Dependencies are only for building and testing: `esbuild`, `typescript`, `vitest`, `jsdom`. The
shipped extension has none.

```sh
npm test            # vitest
npm run build       # tsc + esbuild → dist/
npm run zip         # dist/unitool-chrome-<v>.zip (no "key") and unitool-firefox-<v>.xpi
```
