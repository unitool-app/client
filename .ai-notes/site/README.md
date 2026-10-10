# .ai-notes/site — the Unitool website

A simple, static, open-source-style site: blue and white, black text, no animation; features, docs,
FAQ and the public privacy policy. English throughout.

| File | What |
|---|---|
| [01-site-plan.md](01-site-plan.md) | Goals, decisions D457–D470, site map, page outlines, styles, build, hosting, phases, questions Q204–Q210 |
| [02-signin-by-app.md](02-signin-by-app.md) | Private docs: a browser signs in with the app by QR; sections per installation; decisions D471–D486, questions Q211–Q215 |
| [03-inertia.md](03-inertia.md) | The site rebuilt as a server layer on Inertia.js like restoapp-marketplace (01's "на инерции" was misread); D487–D494; supersedes 01 D457–D459, 02 D479–D481, D486 |

Status 2026-10-10: the site is `layers/app-unitool-site` in unitool-server (React + Inertia SSR,
03); sign-in by app as in 02; the app side shipped in Play alpha 1021 / TestFlight. Open: Q205
(public repository and licence), Q211, Q213–Q215. Next numbers: D495 / Q216.
