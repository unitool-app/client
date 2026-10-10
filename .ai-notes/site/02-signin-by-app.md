# 02 — Private docs: signing in to the website with the app

> **Superseded in part by [03](03-inertia.md)** (2026-10-10): the site is now a server layer on
> Inertia.js; the decisions 03 lists as replaced are history here.

v1.0, 2026-10-10. Built the same day, at the owner's request ("do the whole cycle at once: backend,
server and app, a button in Settings, test it, build it"). English, like 01.

## 1. What was asked

> Pages on the site behind authorization through the app. You open the app and authorize; since the
> app talks to the server, the server issues a token. Depending on what you set up in the app — a
> new app or a secret app turned on — you see the documentation for that part. Not signed in: only
> the public documentation, in the app and on the site. Authorization through Settings, or wherever:
> scan a QR code, a request goes to the server, the browser gets a cookie or a JWT. It must not
> identify you — only "this app is identified", no name. The database records when it last came and
> what it did there.

Read as: a WhatsApp-Web-style QR sign-in, not "Web3" in the blockchain sense. "Identified app" =
one installation of Unitool (a device row), never a person.

## 2. The flow

```
 browser (/login/)                    unitool-server                         phone (Settings → Website)
 ─────────────────                    ──────────────                         ──────────────────────────
 POST /web/logins            ──▶  wl_ + token wlt_ (QR) + cookie wlb_
 shows QR(link …/login/app/#wlt_…)
 polls GET /web/logins/wl_…  ──▶  open                                       scans QR (or pastes the link)
                                                                    ◀──  POST /web/logins/inspect {token}   (device key)
                                  → browser's user-agent, IP, time    ──▶  "Sign in on the website?" + Sign in / Cancel
                                                                    ◀──  POST /web/logins/claim {token, sections}
                                  login approved for d_…
 polls                       ──▶  approved: session ws_ made,
                                  Set-Cookie unitool_session=ws_….wss_… (HttpOnly, Lax, 30 days idle)
 → /account/
 GET /docs/ssh-keys/         ──▶  device.sections ∋ ssh-keys ? page : 404
```

## 3. Decisions

| # | Decision | Why |
|---|---|---|
| D471 | A session stands for a **device row** (`d_…`), never a person. The session cookie is opaque `ws_<id>.wss_<secret>`, the server keeps SHA-256 of the secret. Not a JWT. | Revocable from the phone and the browser at once; the device's sections are read live, so a JWT would only cache a stale list. The same id/secret/hash pattern as the device key. |
| D472 | Two steps on the phone: read the code, then **inspect** (browser user-agent and address as the server saw it) and a tap on **Sign in**. | A QR someone else shows you (QRLJacking) reads as a stranger's browser before anything happens. |
| D473 | The QR is a link `https://unitool.m42.cx/login/app/#wlt_…`; the token is in the fragment. The app accepts only links of its own server. A camera that is not the app's opens a page saying "scan this with the app". | The fragment never reaches a server or a log; a phishing site's link is refused before any request. |
| D474 | The QR is bound to the browser that showed it by a second secret, `wlb_…`, in an HttpOnly cookie scoped to `/api/unitool/v1/web`. Only that browser picks the session up; once. | A stolen QR picture cannot be redeemed from elsewhere. |
| D475 | QR lives 5 minutes; a claim is one-shot (conditional update `open → approved`); a pick-up is one-shot (`approved → done`). | No replay, no race between two phones. |
| D476 | The app keeps its **own** device key for this (`unitool.web.device` in SafePrefs, secret sealed by the host vault when there is one), registered with `POST /devices` at the first sign-in, not the push registrar's. | The push registrar's key is internal to Superizer and exists only once a push app is used; reusing it needs Superizer 0.7.0 (twofa-browser D445 `pa_`). Later the two can merge. One phone may therefore have two device rows. |
| D477 | **Sections** = ids of hidden apps in `superizer.unlocked` + parts opened by code that do not stay unlocked: secret apps (seen as `Launched` of a secret app) and the SSH keys screen (`tools.opened.add("ssh-keys")`). Kept for good on the phone; sent at registration, at every claim, and by `PUT /devices/me {sections}` whenever the set changes after registration. | "What you turned on in the app" — including the doors that close behind you. |
| D478 | Sections are **claims of the app**, not proofs. A modified app could claim any section. | The private docs are "unlisted", not secret material. If a section ever holds something that matters, the server must check codes itself (Q211). |
| D479 | The server serves the whole site (`UNITOOL_SITE_DIR`, default `../site/dist`; private pages in `dist-private/` with `manifest.json`). A private page goes only to a session whose device lists its section; everyone else gets the **same 404** as for a page that does not exist. Public pages may not link to private ones (the build fails). | The site must not reveal which hidden parts exist. |
| D480 | Pages are still static files. The server fills three comments per request: `<!--auth-->` (Sign in / Account), `<!--private-nav-->` (sidebar groups of allowed sections), `<!--account-->`. On a static host they are invisible. | Keeps 01's zero-JS, no-framework site; personalised HTML is `Cache-Control: private, no-store`. |
| D481 | The one exception to "no JavaScript" (01 D459): `/login.js` on `/login/` — fetch a QR, poll every 2 s, redirect. | A sign-in that waits for a phone needs polling; a meta refresh would redraw the QR. |
| D482 | Visits: every HTML page a signed-in browser opens → `unitool_web_visits (sessionId, deviceId, path, at)`, pruned after 90 days; session `lastSeenAt` moved at most once a minute. Nothing for anonymous browsers. Sessions expire 30 days after the last visit. | "When it last came and what it did", and no more. |
| D483 | The phone lists its browsers (`GET /web/sessions`) and signs one or all out (`DELETE /web/sessions[/id]`); the browser signs itself out on `/account/` (`POST /web/logout`, 303 to `/`). | Both ends can end it. |
| D484 | The button is a host section **Website** in Settings, registered last among the host's blocks: Scan QR code to sign in (where there is a camera), Paste a sign-in link, Signed-in browsers. | The owner: "at the bottom of Settings, scan a QR code to authorize". |
| D485 | The client IP is `req.ip`, which honours `TRUST_PROXY`; headers are never read directly. Login creation is rate-limited per IP (`UNITOOL_WEB_LOGINS_PER_HOUR`, 60). | Spoofed headers must not bypass the limit or fake the address the phone shows. |

## 4. What was built

**Server** (`/prj/layers-fw/unitool-server`, uncommitted):
- migration `1791400000003_unitool_web` — `unitool_devices.sections`, tables `unitool_web_logins`,
  `unitool_web_sessions`, `unitool_web_visits`; models in `models/UnitoolWeb.ts` (sessions and visits
  read-only in the panel);
- `services/WebService.ts`, `lib/web.ts` (cookies, IP, public base), endpoints in `lib/apiRouter.ts`,
  `lib/site.ts` (the site middleware); dependency `qrcode` (SVG QR on the server);
- `lib/web.test.ts` — 8 tests over real HTTP with a site on disk. Whole suite 43 green, `tsc` clean.

**Site** (`site/`): `build.mjs` writes `dist/` and `dist-private/` + manifest, fails on a public →
private link; pages `/login/` (+ `login.js`), `/login/app/`, `/account/`, `/docs/website/`; private
sections `content/private/{ssh-keys,test-app,secret-test}/` (10 pages).

**App** (`composeApp`): `web/WebSignIn.kt` (API client, device key, sections), `i18n/WebStrings.kt`
(EN/RU), `settings/WebsiteScreens.kt` (section + three screens); `buildUnitool(server = …)`;
`HostTools.opened`; SSH keys screen marks itself opened. Tests: `WebsiteSignInTest` (7, faked HTTP),
`WebsiteEndToEndTest` (against a running server, skipped without `UNITOOL_E2E_LINK`), screenshots
`45-website-en`, `46-website-confirm-en`.

**Policy**: `play/PRIVACY.md` — the website sign-in as the sixth thing that leaves the device, the
registration it makes, what the server keeps and for how long; Data safety lines updated.

## 5. End-to-end run (2026-10-10, local)

Server on :17290 with `UNITOOL_SITE_DIR=/prj/unitool/site/dist` and a scratch SQLite; Chromium
(Playwright) on `/login/`; the app's real client (`WebsiteEndToEndTest`, Ktor) with the bench app
unlocked and SSH keys opened. The browser redirected itself to `/account/` listing *SSH keys* and
*Test App*; `/docs/ssh-keys/storage/` 200 with the private sidebar group; `/docs/secret-test/` 404;
after Sign out `/docs/ssh-keys/` 404. DB: one device `["ssh-keys","test-app"]`, three visits, login
`done`, no session left.

## 6. Not done / open

| # | Question | Default |
|---|---|---|
| Q211 | Should the server verify sections (the phone sends the code it was given, the server checks it against its own hash table) instead of trusting the app's claim? | Claims (D478). |
| Q212 | Deployment — **answered 2026-10-10 (D486):** `site/publish.sh` builds the site and copies it into the server checkout as `site/public` + `site/private`; it is committed there (the server repository is private) and the image serves it (`UNITOOL_SITE_DIR=/app/site/public` in the Dockerfile). Publishing is by hand after a site change; a CI step with a cross-repository token can do it later. | — |
| Q213 | The app's in-app docs ("in the app, only the public docs"): open the site from the app already signed in (a one-time ticket in the URL)? | Not built; the app links nothing yet. |
| Q214 | Merge the web device key with the push registrar's in Superizer 0.7.0 (twofa-browser D445)? | Two rows per phone for now. |
| Q215 | The Play listing's full description says the network is used for "two things and no others"; with the website sign-in it is more. Rewrite the listing text and the Data safety form (App activity: page views on our website). | Policy updated; listing text and consoles not. |
