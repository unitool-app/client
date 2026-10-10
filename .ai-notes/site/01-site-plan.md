# 01 — The Unitool website: plan

> **Superseded in part by [03](03-inertia.md)** (2026-10-10): the site is now a server layer on
> Inertia.js; the decisions 03 lists as replaced are history here.

v1.0, 2026-10-10. Written in English at the owner's request ("everything in English").

## 1. What was asked

> Make the Unitool site simple, in the style of open-source project sites, not loaded with
> animation like elsewhere. A super-simple design: two colours, blue and white, and black text.
> Sections with features and FAQ. A way to go to the documentation and read it. Everything in
> English. Think it through ahead.

## 2. What the site is for

Four jobs, in this order:

1. **Say what Unitool is in one screen.** A visitor from a store page, a promo code or a link in a
   chat should know within ten seconds: a set of small tools in one app, no ads, no account, no
   tracking.
2. **Documentation.** How to use each app, the CLI, how secrets are kept, how to build from source.
   This is the part that will grow; it has to be cheap to add a page.
3. **FAQ.** The questions people ask before installing a 2FA app from someone they do not know.
4. **The public privacy policy URL** Play and App Store require (RELEASING.md: "host
   `play/PRIVACY.md` somewhere public; the URL is required"). Today it has no home. The site gives
   it one, built from the same file, so the policy cannot drift from what ships in `play/`.

What it is **not**: a blog, a landing page with sign-up, a marketing funnel, a web app. No cookies,
no analytics, no fonts or scripts from third parties — the site keeps the promise the app makes.

## 3. Decisions

| # | Decision | Why |
|---|---|---|
| D457 | The site lives in this monorepo, `site/`. Sources are Markdown plus one HTML template; output goes to `site/dist/` (gitignored). | Docs next to the code they describe are updated in the same commit. The app, the CLI and the kit are already here. |
| D458 | A ~200-line Node build script (`site/build.mjs`) with one dependency, `marked`. No site generator. | A generator (Docusaurus, VitePress, MkDocs Material) brings its own look, a theme to fight, a JS bundle and a dependency tree bigger than the site. We need: Markdown → HTML, one layout, a docs sidebar, prev/next. That is small enough to own. Node is already needed for the server and the wasm smoke test. |
| D459 | **Zero JavaScript in the pages.** Menus, the mobile docs contents and FAQ answers use `<details>`. | Nothing to load, nothing to break, nothing to animate. Works with JS off. |
| D460 | **No animation and no transitions at all.** Hover changes colour or underline instantly. | Asked for explicitly. |
| D461 | Palette: blue `#1747c8`, white `#ffffff`, black text `#111111`. A pale tint of the same blue (`#eef3fd`) for code blocks, table heads and callouts; a lighter black (`#4a4a4a`) for secondary text. Nothing else — no green, no red, no gradients, no shadows. | "Two colours, blue and white, and black text." The tint and the grey are shades of those, not new colours. Blue `#1747c8` on white is 7.9:1, AAA for text. |
| D462 | The header is a solid blue band with the name and the menu in white; the rest is white. The primary button is blue with white text, the secondary is white with a blue border. | The one strong block of colour is what makes "blue and white" read at a glance, without painting the whole page. |
| D463 | System fonts only (`system-ui` stack, `ui-monospace` for code). | No font download, no third-party request, text appears immediately. |
| D464 | Clean URLs: every page is `<path>/index.html`, linked as `/docs/two-factor/`. Links are root-absolute, so the site expects to be served at the root of its domain. | Stable addresses for the stores (`/privacy/`) and for links from the app later. |
| D465 | `/privacy/` is built from `play/PRIVACY.md` directly — never copied into `site/`. | One source; the build fails if the file is missing. |
| D466 | Screenshots come from the existing headless `Screenshots.kt` set (`build/screenshots/phone/`), copied into `site/static/img/` and committed. Only English frames are used. | Those are the real screens. Committed copies keep the build independent of a Gradle run. Today only two English frames exist — the 2FA list and Settings (see Q207). |
| D467 | Unitool is **not called open source** on the site. The client repository is private; only Superizer is public. The header has no "GitHub" link until there is a public repository to point at; `build.mjs` has a `sourceUrl` setting that adds one. | The style is open-source; the claim must not be false. |
| D468 | Secret (promo-code-only) apps are not documented and no promo codes appear anywhere on the site. The activation page explains the mechanism only. | A secret app's point is that it is not in the catalogue. |
| D469 | The download section says what is true today: Android in Google Play testing, iOS in TestFlight, desktop/web/CLI from source. Store links are settings in `build.mjs`; empty means "not public yet". | No dead store links. |
| D470 | English only. No language switcher in v1. | Asked for. A Russian copy later would be a second `content/` tree, not a rewrite. |

## 4. Site map

```
/                      Home: hero, screenshots, features, principles, get the app, top questions
/docs/                 Introduction (docs home)
  Getting started
    /docs/install/           Android, iOS, desktop, web, the CLI
    /docs/home-screen/       Home, the "+" catalogue, long press, the menu, Settings
    /docs/activation/        Promo codes and QR activation — the mechanism, no codes
  Apps
    /docs/calculator/
    /docs/currency-converter/
    /docs/two-factor/        add, codes, lock, export to GitHub SSH keys, import, Google Authenticator transfer
  Command line
    /docs/cli/               install, `unitool qr`, `unitool split`, conventions
  Security and privacy
    /docs/security/          where secrets live, the app lock, what leaves the phone, notifications
  Developers
    /docs/build-from-source/
    /docs/architecture/      Superizer host, apps as modules, adding an app, the kit
/faq/                  All questions, grouped, one <details> each
/privacy/              play/PRIVACY.md
/404.html
```

## 5. Page outlines

**Home** (custom HTML fragment, not Markdown — it has a grid):

1. Hero — "Unitool", the line *Small tools in one app. No ads, no account, no tracking.*, two
   sentences under it, buttons **Read the docs** (primary) and **Get the app** (secondary).
2. The English screenshots side by side (one column on a phone).
3. **Features** — six cards, each with a "Docs →" link: Calculator · Currency converter · 2FA codes ·
   App lock · Command line · Built on Superizer.
4. **Principles** — four short statements: nothing you type leaves the phone; secrets in secure
   hardware; open formats (otpauth, RFC 6238, age, OpenSSH); one codebase for every platform.
5. **Get Unitool** — a table: platform, status, how.
6. **Questions** — the four most asked, as `<details>`, and a link to the full FAQ.

**Docs pages** share one layout: sidebar on the left (sections and pages, the current one marked),
the text column at most 72 characters wide, "Previous / Next" at the bottom, "Edit this page" left
out while the repository is private.

**FAQ** — groups: General · Privacy and security · 2FA · Platforms · Development.

## 6. Layout and styles

- One stylesheet, `site/static/style.css`, under 300 lines, custom properties at the top.
- Content width 1080 px; prose 720 px.
- Header: blue band, 56 px; name on the left, menu on the right (Docs, FAQ, Privacy, Get the app);
  on narrow screens the menu wraps under the name — no hamburger.
- Docs below 860 px: the sidebar becomes a `<details>` "Contents" block above the text.
- Links blue and underlined in prose; in the header white.
- Code: monospace on the pale-blue tint, no syntax colouring (it would need colours we do not have).
- Tables: pale-blue head, thin blue-grey rules.
- Focus ring: 2 px blue outline — accessibility needs it and it is not an animation.
- Print: header and sidebar hidden.

## 7. Build and preview

```sh
cd site
npm ci            # once: installs marked
npm run build     # → site/dist
npm run serve     # http://localhost:8090 (python3 -m http.server over dist)
```

`build.mjs`:
1. reads `content/**/*.md` (front matter: `title`, `description`) and `content/index.html`;
2. reads `../play/PRIVACY.md` for `/privacy/`;
3. renders Markdown with `marked`, adds ids to headings (for `#anchors`);
4. wraps each page in `template.html` (title, description, header, sidebar for docs, prev/next);
5. copies `static/` to `dist/`;
6. fails on a link to a page that does not exist (internal links only) — the cheapest guard against
   rot.

## 8. Hosting (not done — needs the owner, Q204)

The site is static, so anything serves it. The domain that already exists is `unitool.m42.cx`,
where the API lives under `/api/unitool/v1`. Recommended:

- a tiny `nginx:alpine` image with `site/dist`, built by a workflow in this repository on push to
  `main` when `site/` or `play/PRIVACY.md` changes, pushed to ghcr like the server's;
- Nginx Proxy Manager: `/api/` → the server (as now), everything else → the static container.

Then the policy URL is `https://unitool.m42.cx/privacy/`, and it goes into both store consoles.

## 9. Phases

1. **v1 (this change)** — everything in §4 with real content, built and checked in a browser at
   desktop and phone widths. Not deployed.
2. **Hosting** — after Q204.
3. **More screenshots** — English frames of the calculator, the converter and Home from
   `Screenshots.kt` (Q207).
4. **Later, if wanted** — "Try it in the browser": the wasm build at `/try/` (needs WebGL; see
   README "The browser needs WebGL"); a changelog page; a Russian copy.

## 10. Open questions

| # | Question | Default if no answer |
|---|---|---|
| Q204 | Where is the site served? `unitool.m42.cx/` next to the API (recommended), a separate host such as `m42.cx/unitool`, or GitHub Pages (needs a public repository or a paid plan). | Built, not deployed. |
| Q205 | Will the Unitool repository become public, and under what licence? That decides whether the site may say "open source" and show a GitHub link. | No such claim, no link (D467). |
| Q206 | Store links: is the Play listing public yet, and is there an App Store page? | Status text only (D469). |
| Q207 | May `Screenshots.kt` render more frames in English (calculator, converter, Home, 2FA add)? | The two existing English frames. |
| Q208 | The privacy policy has two sentences meant for us, not for readers: "Play publishes that address … not an alias that bounces" and a path into the source tree under Backup. Remove them from `play/PRIVACY.md`? It will be public on the site. | Published as is, flagged. |
| Q209 | The policy speaks of Android only (Keystore, Android 13). The site will be the iOS policy URL too. Add the iOS side (Secure Enclave, iOS permissions)? | Unchanged. |
| Q210 | Contact address on the site: `privacy@m42.cx` for everything, or a separate one? | `privacy@m42.cx`. |
