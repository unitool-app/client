# site — the Unitool website

Static, no JavaScript, no animation; blue, white and black text. The plan and its decisions are in
[`.ai-notes/site`](../.ai-notes/site/README.md).

```sh
cd site
npm ci            # once: installs marked, the only dependency
npm run build     # → dist/ ; fails on a link to a page or an #anchor that does not exist
npm run serve     # http://localhost:8090
```

| Where | What |
|---|---|
| `content/index.html` | the home page |
| `content/faq.md` | the FAQ (`<details>` blocks) |
| `content/docs/*.md` | docs pages; their order and the sidebar are `DOCS` in `build.mjs` |
| `../play/PRIVACY.md` | `/privacy/`, read directly — never copied |
| `build.mjs` → `SITE` | links that are empty until public: `sourceUrl`, `playUrl`, `appStoreUrl` |
| `static/` | `style.css`, the favicon, screenshots (from `Screenshots.kt`, English frames only) |

A new docs page is a Markdown file in `content/docs/` plus a line in `DOCS`. `{{superizerUrl}}`,
`{{contact}}` and the other `SITE` keys can be used in any page.
