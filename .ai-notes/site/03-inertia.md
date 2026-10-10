# 03 — The site rebuilt on Inertia.js, like restoapp-marketplace

v1.0, 2026-10-10. Built and checked the same day.

## 1. What was asked, and what had been misread

The first request said the site should be "не сильно замороченный, на инерции, как это сделано в
других местах". It was read as "not heavy on animation"; the owner meant **on Inertia.js, the way
`/prj/layers-fw/restoapp-marketplace` does it**. After the static build of 01 and 02 had shipped, the
owner said: "надо было сделать на inertiajs также как сделано у restoapp-marketplace — переделай".

Superseded by this note: 01 D457 (site in the app repository), D458 (own `build.mjs` + marked),
D459 (zero JavaScript), D464's static-host remark, D465's "never copied"; 02 D479–D481 (static
files with HTML comments filled in per request, `/login.js`), D486 (`site/publish.sh`). Kept: the
look (D461–D463, blue/white/black, system fonts, no animation — still true, just not the request),
the site map, the content, and the whole sign-in by app (02 D471–D478, D482–D485).

## 2. Decisions

| # | Decision | Why |
|---|---|---|
| D487 | The site is a NodeKnit layer of the server, `layers/app-unitool-site`, extending `AppInertiajs` from `@nodeknit/app-inertiajs` (submodule `local_modules/app-inertiajs`, pinned to 79cd7e8 — the commit restoapp-marketplace uses). React 18 + Inertia with SSR, Vite 7, Tailwind 3. Same file layout as `app-marketplace-frontend`: `index.ts` (pages), `src/` (server code), `web/` (vite/tailwind/postcss configs, `src/app.tsx`, `src/ssr.tsx`, `pages/`, `components/`, `assets/`). | "Как сделано у restoapp-marketplace": one way of making sites across the projects. |
| D488 | Words stay Markdown (`content/`), rendered to HTML **on the server** with markdown-it (ids on headings) and handed to pages as a prop. Sidebar order in `content/docs/_docs.json`; private parts in `content/private/<id>/_section.json`. | Docs are text people edit; React pages only lay them out. |
| D489 | A private page and a missing page are one route family (`/docs/:slug`, `/docs/:section/:page`) and one component (`Docs`), and both answer **404**: the props resolver pins the status (`pinStatus`), because app-inertiajs sets a status per route after resolving props. | The site must not say which hidden parts exist (02 D479), now without a static 404 file. |
| D490 | The site asks app-unitool who a browser is through a `Symbol.for('unitool.webAccess')` global (`lib/webAccess.ts`: `visitor(req)`, `recordVisit`), not an import. | Under tsx a module can load twice; a second WebService would hold models nobody initialised (server AGENTS.md). |
| D491 | Every page gets `signedIn`, `path` and the docs nav **with the private groups this browser may see** as props; the header shows Sign in / Account from them. Visits are recorded in the props resolvers, only for pages that exist. | Inertia's SSR adapter here has no shared props (marketplace's `withAuth`, same pattern). |
| D492 | The login page is a React component: it asks for the QR and polls in `useEffect` after hydration; the SSR render is its "Loading…" state. Links are plain `<a>` (full page loads), forms plain POSTs. | app-inertiajs does not implement the Inertia XHR protocol (marketplace AGENTS.md says the same). |
| D493 | `web/dist` is not in git; the Dockerfile builds it in its own stage (`npm run build:site`) and copies it into the image. The privacy policy is written in the app repository (`play/PRIVACY.md`) and copied into `content/privacy.md` by `scripts/publish-privacy.sh` there (`--check` to compare). | Marketplace's Dockerfile does the same; the stores and the app keep one policy file. |
| D494 | Assets (screenshots, favicon) are imported from `web/src/assets`, never `public/`; URLs come out hashed under `/site-assets/`. | marketplace docs: app-inertiajs proxies only `/src/`… to Vite in dev, so `public/` would 404 there. |

## 3. Run

```sh
cd unitool-server
npm run build:site          # web/dist/client + web/dist/server/ssr.js
npm run dev                 # production-like: serves web/dist
VITE_ENV=dev npm run dev    # Vite with hot reload
npx vitest run              # includes layers/app-unitool-site/src/content.test.ts
npm run typecheck:site      # the React side
```

## 4. Checked (2026-10-10, local, built bundle)

`/`, `/docs/…`, `/faq/`, `/privacy/`, `/login/`, `/login/app/`, `/account/` 200; `/docs/ssh-keys/`
signed out 404, same as `/docs/nope/`; `/dashboard` untouched. Chromium on `/login/?next=/docs/ssh-keys/`
→ the app's real client (`WebsiteEndToEndTest`) claimed it → the browser moved itself to
`/docs/ssh-keys/` with *SSH keys* and *Test App* groups in the sidebar; `/docs/secret-test/` 404;
Sign out on `/account/` → `/`, private page 404 again. Visits: only the three pages that existed.
A QR older than 5 minutes was refused (410) — a slow Gradle run proved it by accident.
Server suite 45 green, `tsc` clean on both sides.
