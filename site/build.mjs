// Builds the Unitool website into dist/. Plan: .ai-notes/site/01-site-plan.md.
//
//   content/index.html        the home page body (HTML: it has a grid)
//   content/<page>.md         a page at /<page>/
//   content/docs/<page>.md    a docs page at /docs/<page>/, ordered by DOCS below
//   content/<a>/<b>.md|.html  a page at /<a>/<b>/ (login, account, …)
//   content/private/<section>/  a hidden or secret part's docs at /docs/<section>/…, written to
//                             dist-private/ with manifest.json — served only to a browser signed in
//                             with an app that has the part (unitool-server lib/site.ts, .ai-notes/site/02)
//   ../play/PRIVACY.md        /privacy/ — the policy the stores link to, never copied (D465)
//
// Three HTML comments are filled in by the server per request and stay invisible on a static host:
// <!--auth--> in the menu, <!--private-nav--> in the docs sidebar, <!--account--> on /account/.
//
// Front matter is optional lines between `---` fences: `title:`, `description:`, and `class:` for
// the article (the FAQ uses it).
// The build fails on an internal link to a page that does not exist.

import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Marked } from 'marked';

const ROOT = dirname(fileURLToPath(import.meta.url));
const OUT = join(ROOT, 'dist');
const OUT_PRIVATE = join(ROOT, 'dist-private');

// What the site links to. Empty means "not public yet": the link is left out (D467, D469).
export const SITE = {
  name: 'Unitool',
  url: 'https://unitool.m42.cx',
  sourceUrl: '',
  superizerUrl: 'https://github.com/adminization/superizer',
  playUrl: '',
  appStoreUrl: '',
  contact: 'privacy@m42.cx',
};

// The docs sidebar, in reading order. Slug '' is /docs/ itself.
const DOCS = [
  ['Getting started', [['', 'Introduction'], ['install', 'Install'], ['home-screen', 'Home screen and apps'], ['activation', 'Promo codes and QR activation']]],
  ['Apps', [['calculator', 'Calculator'], ['currency-converter', 'Currency converter'], ['two-factor', '2FA codes']]],
  ['Command line', [['cli', 'The unitool command']]],
  ['Security and privacy', [['security', 'How your data is kept'], ['website', 'Signing in to this website']]],
  ['Developers', [['build-from-source', 'Build from source'], ['architecture', 'Architecture']]],
];

const NAV = [['/docs/', 'Docs'], ['/faq/', 'FAQ'], ['/privacy/', 'Privacy'], ['/#get', 'Get the app']];

const escape = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const slugify = (s) => s.toLowerCase().replace(/<[^>]+>/g, '').replace(/&[a-z]+;/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

function frontMatter(text) {
  const meta = {};
  const m = text.match(/^---\n([\s\S]*?)\n---\n/);
  if (!m) return { meta, body: text };
  for (const line of m[1].split('\n')) {
    const i = line.indexOf(':');
    if (i > 0) meta[line.slice(0, i).trim()] = line.slice(i + 1).trim();
  }
  return { meta, body: text.slice(m[0].length) };
}

function markdown(text) {
  const md = new Marked({ gfm: true });
  const seen = new Map();
  md.use({
    renderer: {
      heading({ tokens, depth }) {
        const html = this.parser.parseInline(tokens);
        let id = slugify(html) || 'section';
        const n = seen.get(id) ?? 0;
        seen.set(id, n + 1);
        if (n) id += `-${n}`;
        if (depth === 1) return `<h1>${html}</h1>\n`;
        return `<h${depth} id="${id}"><a class="anchor" href="#${id}">${html}</a></h${depth}>\n`;
      },
    },
  });
  return md.parse(text);
}

// {{key}} in content is replaced with SITE[key], so a URL is written once.
const fill = (text) => text.replace(/\{\{(\w+)\}\}/g, (all, k) => (k in SITE ? SITE[k] : all));

const template = readFileSync(join(ROOT, 'template.html'), 'utf8');
const pages = []; // { path, html }

function header(current) {
  const links = NAV.map(([href, label]) => {
    const on = current && href !== '/#get' && current.startsWith(href) ? ' aria-current="page"' : '';
    return `<a href="${href}"${on}>${label}</a>`;
  });
  if (SITE.sourceUrl) links.push(`<a href="${SITE.sourceUrl}">Source</a>`);
  links.push('<!--auth-->');
  return links.join('\n        ');
}

function page({ path, title, description, body, kind = 'page', section = null }) {
  const fullTitle = path === '/' ? `${SITE.name} — small tools in one app` : `${title} — ${SITE.name}`;
  const html = template
    .replaceAll('{{title}}', escape(fullTitle))
    .replaceAll('{{description}}', escape(description ?? ''))
    .replaceAll('{{canonical}}', SITE.url + path)
    .replaceAll('{{kind}}', kind)
    .replace('{{nav}}', () => header(path))
    .replace('{{body}}', () => body)
    .replace('{{year}}', String(new Date().getFullYear()))
    .replace('{{footer}}', () => footer());
  pages.push({ path, html, section, title });
}

function footer() {
  const items = [
    '<a href="/docs/">Docs</a>',
    '<a href="/faq/">FAQ</a>',
    '<a href="/privacy/">Privacy</a>',
    `<a href="${SITE.superizerUrl}">Superizer</a>`,
    `<a href="mailto:${SITE.contact}">${SITE.contact}</a>`,
  ];
  if (SITE.sourceUrl) items.splice(3, 0, `<a href="${SITE.sourceUrl}">Source</a>`);
  return items.join(' · ');
}

// Home
page({
  path: '/',
  description: 'Unitool is a calculator, a currency converter and a 2FA code generator in one app. No ads, no account, no tracking.',
  body: fill(readFileSync(join(ROOT, 'content/index.html'), 'utf8')),
  kind: 'home',
});

// Plain pages: content/**/*.md and *.html, outside docs/ and private/. a/b.md → /a/b/, a/index.md → /a/.
function walk(dir, rel = '') {
  const out = [];
  for (const entry of readdirSync(join(dir, rel), { withFileTypes: true })) {
    const path = rel ? `${rel}/${entry.name}` : entry.name;
    if (entry.isDirectory()) {
      if (!rel && (entry.name === 'docs' || entry.name === 'private')) continue;
      out.push(...walk(dir, path));
    } else if (/\.(md|html)$/.test(entry.name) && path !== 'index.html') {
      out.push(path);
    }
  }
  return out;
}
for (const file of walk(join(ROOT, 'content'))) {
  const { meta, body } = frontMatter(fill(readFileSync(join(ROOT, 'content', file), 'utf8')));
  const slug = file.replace(/\.(md|html)$/, '').replace(/(^|\/)index$/, '');
  const content = file.endsWith('.html') ? body : markdown(body);
  page({
    path: `/${slug ? slug + '/' : ''}`,
    title: meta.title ?? slug,
    description: meta.description,
    body: `<article class="prose ${meta.class ?? ''}">${content}</article>`,
  });
}

// Privacy: straight from play/PRIVACY.md
{
  const src = join(ROOT, '..', 'play', 'PRIVACY.md');
  if (!existsSync(src)) throw new Error(`missing ${src}`);
  page({
    path: '/privacy/',
    title: 'Privacy policy',
    description: 'What Unitool stores, what leaves the device, and what it collects.',
    body: `<article class="prose">${markdown(readFileSync(src, 'utf8'))}</article>`,
  });
}

// Docs
const order = DOCS.flatMap(([, items]) => items);
const known = new Set(order.map(([s]) => s));
for (const f of readdirSync(join(ROOT, 'content/docs'))) {
  const slug = f.replace(/\.md$/, '') === 'index' ? '' : f.replace(/\.md$/, '');
  if (!known.has(slug)) throw new Error(`content/docs/${f} is not in DOCS`);
}

function sidebar(currentSlug) {
  return DOCS.map(([section, items]) => {
    const links = items.map(([slug, label]) => {
      const on = slug === currentSlug ? ' aria-current="page"' : '';
      return `<li><a href="/docs/${slug ? slug + '/' : ''}"${on}>${label}</a></li>`;
    }).join('');
    return `<p class="side-head">${section}</p><ul>${links}</ul>`;
  }).join('\n') + '\n<!--private-nav-->';
}

const pagerLink = ([href, label], dir) => `<a class="${dir}" href="${href}"><span>${dir === 'prev' ? 'Previous' : 'Next'}</span>${label}</a>`;

function docsBody(side, html, prev, next) {
  return `<div class="docs">
  <nav class="side" aria-label="Documentation">${side}</nav>
  <details class="side-mobile"><summary>Contents</summary><nav aria-label="Documentation">${side}</nav></details>
  <article class="prose">${html}
    <nav class="pager">${prev ? pagerLink(prev, 'prev') : '<span></span>'}${next ? pagerLink(next, 'next') : ''}</nav>
  </article>
</div>`;
}

const docHref = (slug) => `/docs/${slug ? slug + '/' : ''}`;

order.forEach(([slug, label], i) => {
  const file = join(ROOT, 'content/docs', `${slug || 'index'}.md`);
  const { meta, body } = frontMatter(fill(readFileSync(file, 'utf8')));
  const prev = order[i - 1] && [docHref(order[i - 1][0]), order[i - 1][1]];
  const next = order[i + 1] && [docHref(order[i + 1][0]), order[i + 1][1]];
  page({
    path: docHref(slug),
    title: meta.title ?? label,
    description: meta.description,
    kind: 'docs',
    body: docsBody(sidebar(slug), markdown(body), prev, next),
  });
});

// Private sections: content/private/<section>/_section.json and its pages. Their sidebar group is
// the server's to draw (<!--private-nav-->), with every other section this browser may read.
const manifest = { sections: [] };
const privateRoot = join(ROOT, 'content/private');
if (existsSync(privateRoot)) {
  for (const id of readdirSync(privateRoot).sort()) {
    const dir = join(privateRoot, id);
    if (!/^[a-z0-9][a-z0-9-]*$/.test(id)) throw new Error(`content/private/${id}: not a section id`);
    if (known.has(id)) throw new Error(`content/private/${id} would shadow /docs/${id}/`);
    const spec = JSON.parse(readFileSync(join(dir, '_section.json'), 'utf8'));
    const listed = new Set(spec.pages.map(([slug]) => slug));
    for (const slug of listed) {
      if (!existsSync(join(dir, `${slug || 'index'}.md`))) throw new Error(`content/private/${id}/_section.json lists "${slug}", which has no ${slug || 'index'}.md`);
    }
    for (const f of readdirSync(dir).filter((f) => f.endsWith('.md'))) {
      const slug = f === 'index.md' ? '' : f.replace(/\.md$/, '');
      if (!listed.has(slug)) throw new Error(`content/private/${id}/${f} is not in _section.json`);
    }
    const href = (slug) => `/docs/${id}/${slug ? slug + '/' : ''}`;
    const section = { id, title: spec.title, pages: spec.pages.map(([slug, label]) => ({ path: href(slug), title: label })) };
    manifest.sections.push(section);
    spec.pages.forEach(([slug, label], i) => {
      const { meta, body } = frontMatter(fill(readFileSync(join(dir, `${slug || 'index'}.md`), 'utf8')));
      const prev = spec.pages[i - 1] && [href(spec.pages[i - 1][0]), spec.pages[i - 1][1]];
      const next = spec.pages[i + 1] && [href(spec.pages[i + 1][0]), spec.pages[i + 1][1]];
      page({
        path: href(slug),
        title: meta.title ?? label,
        description: meta.description,
        kind: 'docs',
        section: id,
        body: docsBody(sidebar(null), markdown(body), prev, next),
      });
    });
  }
}

// 404
page({
  path: '/404.html',
  title: 'Not found',
  body: '<article class="prose"><h1>Not found</h1><p>There is no page at this address. Try the <a href="/docs/">documentation</a> or the <a href="/">home page</a>.</p><p class="muted">Some pages open only in a browser signed in with the app: <a href="/login/">Sign in</a>.</p></article>',
});

// Every internal link must land on a page (or a static file), and on an id there if it names one.
const ids = new Map(pages.map((p) => [p.path, new Set([...p.html.matchAll(/ id="([^"]+)"/g)].map((m) => m[1]))]));
const privatePaths = new Set(pages.filter((p) => p.section).map((p) => p.path));
const broken = [];
for (const { path, html, section } of pages) {
  for (const [, href, hash] of html.matchAll(/href="(\/[^"#?]*)(?:#([^"]*))?"/g)) {
    // A public page naming a private one would tell everyone the hidden part exists.
    if (!section && privatePaths.has(href)) broken.push(`${path} → ${href} (public page links to a private one)`);
    const target = ids.get(href);
    if (!target && !existsSync(join(ROOT, 'static', href))) broken.push(`${path} → ${href}`);
    else if (target && hash && !target.has(hash)) broken.push(`${path} → ${href}#${hash}`);
  }
}
if (broken.length) throw new Error(`broken links:\n  ${broken.join('\n  ')}`);

rmSync(OUT, { recursive: true, force: true });
rmSync(OUT_PRIVATE, { recursive: true, force: true });
cpSync(join(ROOT, 'static'), OUT, { recursive: true });
for (const { path, html, section } of pages) {
  const root = section ? OUT_PRIVATE : OUT;
  const file = path.endsWith('.html') ? join(root, path) : join(root, path, 'index.html');
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, html);
}
mkdirSync(OUT_PRIVATE, { recursive: true });
writeFileSync(join(OUT_PRIVATE, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
const privateCount = privatePaths.size;
console.log(`built ${pages.length - privateCount} public pages into ${OUT} and ${privateCount} private pages in ${manifest.sections.length} sections into ${OUT_PRIVATE}`);
