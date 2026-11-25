#!/usr/bin/env node
// Static contract checks for the NovelSpeak website.
//
// This is not a browser. It proves the things source can prove — structure, paths, budgets, tokens,
// motion gating, contrast, shell parity — and says so. Visual judgement stays with a person; see
// docs/website/design-contract.md § Verification.
//
// Usage: node tools/site-check.mjs [--verbose]

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const SITE = join(ROOT, 'site');
const VERBOSE = process.argv.includes('--verbose');

const failures = [];
const notes = [];
const fail = (where, message) => failures.push(`${where}: ${message}`);
const note = (message) => notes.push(message);

const read = (p) => readFileSync(p, 'utf8');
const bytes = (p) => statSync(p).size;

function walk(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const p = join(dir, entry.name);
    return entry.isDirectory() ? walk(p) : [p];
  });
}

const htmlFiles = walk(SITE).filter((f) => f.endsWith('.html'));
const pages = htmlFiles.map((path) => ({
  path,
  rel: relative(SITE, path),
  dir: dirname(path),
  html: read(path),
}));

/* ---------------------------------------------------------------- 1. structure */

const textOf = (html) =>
  html
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&[a-z#0-9]+;/gi, ' ')
    .replace(/\s+/g, ' ')
    .trim();

const titleOf = (html) => (html.match(/<title>([^<]*)<\/title>/) || [, ''])[1].trim();
const descOf = (html) => (html.match(/<meta name="description" content="([^"]*)"/) || [, ''])[1].trim();
const h1s = (html) => [...html.matchAll(/<h1\b[^>]*>([\s\S]*?)<\/h1>/gi)].map((m) => textOf(m[1]));

for (const page of pages) {
  const h = page.html;
  const where = page.rel;

  if (!/^<!doctype html>/i.test(h)) fail(where, 'missing doctype');
  if (!/<html lang="[a-z-]+"/i.test(h)) fail(where, 'missing lang on <html>');
  if (!/<meta name="viewport"/i.test(h)) fail(where, 'missing viewport meta');
  if (!/<meta name="theme-color"/i.test(h)) fail(where, 'missing theme-color');
  if (/user-scalable=no|maximum-scale=1/i.test(h)) fail(where, 'viewport blocks zoom');

  const titles = h1s(h);
  if (titles.length !== 1) fail(where, `expected exactly one h1, found ${titles.length}`);
  if (titles[0] && titles[0].length > 90) fail(where, `h1 is ${titles[0].length} chars`);

  const title = titleOf(h);
  if (!title) fail(where, 'missing <title>');
  else if (title.length > 60) fail(where, `title is ${title.length} chars (max 60)`);

  const desc = descOf(h);
  if (!desc) fail(where, 'missing meta description');
  else if (desc.length < 50 || desc.length > 160) fail(where, `description is ${desc.length} chars (50–160)`);

  const isErrorPage = page.rel === '404.html';
  if (!/<main id="main">/.test(h)) fail(where, 'missing <main id="main">');
  if (!isErrorPage && !/<a class="skip" href="#main">/.test(h)) fail(where, 'missing skip link to #main');
  if (!isErrorPage && (h.match(/<header\b/g) || []).length < 1) fail(where, 'missing <header>');
  if (!isErrorPage && (h.match(/<footer\b/g) || []).length < 1) fail(where, 'missing <footer>');

  // heading order
  const levels = [...h.matchAll(/<h([1-6])\b/gi)].map((m) => Number(m[1]));
  let prev = 0;
  for (const level of levels) {
    if (prev && level > prev + 1) fail(where, `heading jumps h${prev} → h${level}`);
    prev = level;
  }

  // duplicate ids
  const ids = [...h.matchAll(/\sid="([^"]+)"/g)].map((m) => m[1]);
  const dupes = [...new Set(ids.filter((id, i) => ids.indexOf(id) !== i))];
  if (dupes.length) fail(where, `duplicate id(s): ${dupes.join(', ')}`);

  // a page must carry real content, not a shell
  const words = textOf(h).split(/\s+/).length;
  if (!isErrorPage && words < 250) fail(where, `only ${words} words of content in the HTML`);
  else note(`${where}: ${words} words, title ${title.length}, description ${desc.length}`);

  if (!/<script src="[^"]*site\.js"><\/script>/.test(h)) note(`${where}: no site.js (ok only for 404)`);
}

/* ---------------------------------------------------------------- 2. paths */

const EXTERNAL_OK = [
  /^https:\/\/github\.com\/vipulgote1999\/NovelSpeak(\.git)?(\/|$)/,
  /^https:\/\/github\.com\/k2-fsa\/sherpa-onnx(\/|$)/,
  /^https:\/\/play\.google\.com\/store\/apps\/details\?id=app\.novelspeak$/,
  /^https:\/\/vipulgote1999\.github\.io\/NovelSpeak\/(assets\/|$)/,
];
const externalSeen = new Set();

for (const page of pages) {
  const h = page.html;
  const refs = [...h.matchAll(/\b(?:href|src|poster)="([^"]*)"/g)].map((m) => m[1]);
  for (const ref of refs) {
    if (!ref) continue;
    if (ref.startsWith('//')) {
      fail(page.rel, `protocol-relative URL ${ref}`);
      continue;
    }
    if (ref.startsWith('/')) {
      fail(page.rel, `root-absolute URL ${ref} (breaks GitHub Pages subpath)`);
      continue;
    }
    if (/^(mailto:|tel:|data:|#)/.test(ref)) {
      if (ref.startsWith('#') && ref.length > 1) {
        const target = resolve(page.dir, page.path);
        const haystack = read(target);
        const id = ref.slice(1);
        if (!new RegExp(`id="${id}"`).test(haystack)) fail(page.rel, `anchor ${ref} has no target in this page`);
      }
      continue;
    }
    if (/^https?:/.test(ref)) {
      if (!EXTERNAL_OK.some((re) => re.test(ref))) fail(page.rel, `external URL not allowlisted: ${ref}`);
      else externalSeen.add(ref);
      continue;
    }
    // internal, relative to the page
    const [pathPart, hash] = ref.split('#');
    if (!pathPart) continue;
    let target = resolve(page.dir, pathPart.split('?')[0]);
    if (pathPart.endsWith('/')) target = join(target, 'index.html');
    const inside = !relative(SITE, target).startsWith('..');
    if (!inside) {
      fail(page.rel, `link leaves the site: ${ref}`);
      continue;
    }
    if (!existsSync(target)) {
      fail(page.rel, `broken link/asset: ${ref}`);
      continue;
    }
    if (hash && target.endsWith('.html')) {
      if (!new RegExp(`id="${hash}"`).test(read(target))) fail(page.rel, `anchor #${hash} not found in ${ref}`);
    }
  }
}

/* ---------------------------------------------------------------- 3. assets */

for (const page of pages) {
  const imgs = [...page.html.matchAll(/<img\b[^>]*>/g)].map((m) => m[0]);
  for (const tag of imgs) {
    if (!/\balt="/.test(tag)) fail(page.rel, `img without alt: ${tag.slice(0, 80)}`);
    if (!/\bwidth="\d+"/.test(tag) || !/\bheight="\d+"/.test(tag)) {
      fail(page.rel, `img without width/height: ${tag.slice(0, 80)}`);
    }
    const eager = /fetchpriority="high"/.test(tag) || /loading="eager"/.test(tag);
    const small = Number((tag.match(/\bwidth="(\d+)"/) || [, 0])[1]) <= 64;
    if (!eager && !small && !/loading="lazy"/.test(tag)) fail(page.rel, `img not lazy and not fetchpriority:high: ${tag.slice(0, 80)}`);
  }
  const videos = [...page.html.matchAll(/<video\b[^>]*>/g)].map((m) => m[0]);
  for (const tag of videos) {
    if (/autoplay/.test(tag)) fail(page.rel, 'video autoplays');
    if (!/preload="none"/.test(tag)) fail(page.rel, 'video is not preload="none"');
    if (!/poster="[^"]+"/.test(tag)) fail(page.rel, 'video has no poster');
    if (!/controls/.test(tag)) fail(page.rel, 'video has no native controls');
  }
  for (const tag of [...page.html.matchAll(/<iframe\b[^>]*>/g)]) fail(page.rel, `iframe present: ${tag[0].slice(0, 60)}`);
}

/* ---------------------------------------------------------------- 4. css: motion, tokens, radius */

const cssPath = join(SITE, 'assets/site.css');
const css = read(cssPath);
const cssWhere = 'assets/site.css';

if (/transition:\s*all\b/.test(css)) fail(cssWhere, 'transition: all');
if (!/prefers-reduced-motion:\s*reduce/.test(css)) fail(cssWhere, 'no reduced-motion block');

// every animation declaration must sit inside the no-preference block
function blockAt(source, marker) {
  const start = source.indexOf(marker);
  if (start < 0) return '';
  const open = source.indexOf('{', start);
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === '{') depth++;
    else if (source[i] === '}') {
      depth--;
      if (depth === 0) return source.slice(open + 1, i);
    }
  }
  return '';
}
const motionBlock = blockAt(css, '@media (prefers-reduced-motion: no-preference)');
if (!motionBlock) fail(cssWhere, 'missing prefers-reduced-motion: no-preference block');
const outsideMotion = css.replace(motionBlock, '');
for (const m of outsideMotion.matchAll(/(^|[;{\s])animation:\s*([^;}]+)/g)) {
  if (/animation:\s*none/.test(m[0])) continue;
  fail(cssWhere, `animation outside the no-preference block: ${m[0].trim().slice(0, 70)}`);
}
const keyframes = [...css.matchAll(/@keyframes\s+([a-z-]+)/g)].map((m) => m[1]);
for (const name of keyframes) {
  if (!motionBlock.includes(name)) fail(cssWhere, `@keyframes ${name} is not used inside the no-preference block`);
}

// hex tokens only in :root
const rootBlock = blockAt(css, ':root');
const outsideRoot = css.replace(rootBlock, '');
const strayHex = [...outsideRoot.matchAll(/#[0-9a-f]{3,8}\b/gi)].map((m) => m[0]);
if (strayHex.length) fail(cssWhere, `hex colour outside :root: ${[...new Set(strayHex)].join(', ')}`);

// radius scale
const allowedRadius = /\bvar\(--r[1-4]\)|calc\(var\(--r3\)|0\b|inherit|\.985/;
for (const m of css.matchAll(/border-radius:\s*([^;}]+)/g)) {
  if (!allowedRadius.test(m[1])) fail(cssWhere, `radius outside the scale: ${m[1].trim()}`);
}

// round-1 regressions
if (/\.chip\b/.test(css)) fail(cssWhere, 'inert chip component reintroduced');
const statusBlock = blockAt(css, '.status {');
if (/border/.test(statusBlock)) fail(cssWhere, '.status has a border (must read as text, not a control)');
if (!/scroll-margin-top/.test(blockAt(css, '.section {'))) fail(cssWhere, '.section has no scroll-margin-top');

/* ---------------------------------------------------------------- 5. contrast from tokens */

const token = (name) => {
  const m = rootBlock.match(new RegExp(`--${name}:\\s*(#[0-9a-f]{6})`, 'i'));
  return m ? m[1] : null;
};
const luminance = (hex) => {
  const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
};
const contrast = (a, b) => {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
};
const PAIRS = [
  ['text', 'ink', 4.5], ['text-2', 'ink', 4.5], ['text-3', 'ink', 4.5], ['text-3', 'ink-2', 4.5],
  ['accent', 'ink', 4.5], ['accent-ink', 'accent', 4.5], ['accent-ink', 'accent-hover', 4.5],
  ['rule-ui', 'ink', 3], ['rule-ui', 'ink-2', 3],
  ['paper-text', 'paper', 4.5], ['paper-text-2', 'paper', 4.5], ['paper-accent', 'paper', 4.5],
  ['paper-accent', 'paper-sheet', 4.5],
];
for (const [fg, bg, min] of PAIRS) {
  const f = token(fg), b = token(bg);
  if (!f || !b) {
    fail(cssWhere, `missing token --${fg} or --${bg}`);
    continue;
  }
  const ratio = contrast(f, b);
  if (ratio < min) fail(cssWhere, `contrast --${fg} on --${bg} is ${ratio.toFixed(2)}, needs ${min}`);
  else note(`contrast --${fg} on --${bg}: ${ratio.toFixed(2)}`);
}

/* ---------------------------------------------------------------- 6. js */

const js = read(join(SITE, 'assets/site.js'));
if (bytes(join(SITE, 'assets/site.js')) > 15 * 1024) fail('assets/site.js', 'over 15 KB');
if (/document\.write|innerHTML\s*=/.test(js)) fail('assets/site.js', 'uses document.write or innerHTML');
if (!/playUrl/.test(js)) fail('assets/site.js', 'Play URL is not configured in one place');

/* ---------------------------------------------------------------- 7. shell parity */

const headerOf = (html) => (html.match(/<header class="site-header">[\s\S]*?<\/header>/) || [''])[0];
const footerOf = (html) => (html.match(/<footer class="closing">[\s\S]*?<\/footer>/) || [''])[0];
const normalise = (s) => s.replace(/\s+aria-current="page"/g, '').split('../').join('').split('./').join('').replace(/\s+/g, ' ').trim();

const pageDocs = pages.filter((p) => p.rel !== '404.html');
const header = normalise(headerOf(pageDocs[0].html));
const footer = normalise(footerOf(pageDocs[0].html));
for (const page of pageDocs) {
  if (normalise(headerOf(page.html)) !== header) fail(page.rel, 'header differs from the shared shell');
  if (normalise(footerOf(page.html)) !== footer) fail(page.rel, 'footer differs from the shared shell');
  if (!/<nav class="nav" aria-label="Site">/.test(page.html)) fail(page.rel, 'missing primary nav landmark');
  if (page.rel !== 'index.html' && !/aria-current="page"/.test(page.html)) fail(page.rel, 'missing aria-current on the current page');
}

/* ---------------------------------------------------------------- 8. budgets + required files */

for (const page of pages) {
  const size = Buffer.byteLength(page.html);
  if (size > 40 * 1024) fail(page.rel, `HTML is ${(size / 1024).toFixed(1)} KB (max 40 KB)`);
  else note(`${page.rel}: ${(size / 1024).toFixed(1)} KB HTML`);
}
const cssSize = bytes(cssPath);
if (cssSize > 45 * 1024) fail('assets/site.css', `${(cssSize / 1024).toFixed(1)} KB (max 45 KB)`);
note(`assets/site.css: ${(cssSize / 1024).toFixed(1)} KB`);
note(`assets/site.js: ${(bytes(join(SITE, 'assets/site.js')) / 1024).toFixed(1)} KB`);

const imgDir = join(SITE, 'assets/img');
const images = readdirSync(imgDir).map((f) => join(imgDir, f));
const imageBytes = images.reduce((sum, f) => sum + bytes(f), 0);
if (imageBytes > 900 * 1024) fail('assets/img', `${(imageBytes / 1024).toFixed(0)} KB total (max 900 KB)`);
else note(`assets/img: ${images.length} files, ${(imageBytes / 1024).toFixed(0)} KB`);

const video = join(SITE, 'assets/media/film.mp4');
if (!existsSync(video)) fail('assets/media', 'film.mp4 is missing');
else note(`assets/media/film.mp4: ${(bytes(video) / 1024 / 1024).toFixed(1)} MB, never preloaded`);

for (const required of ['_headers', '_redirects', '404.html']) {
  if (!existsSync(join(SITE, required))) fail('site/', `missing ${required}`);
}
const notFound = read(join(SITE, '404.html'));
if (/href="(?!https?:|#)[^"]+"/.test(notFound) && /href="\.\.?\//.test(notFound)) {
  fail('404.html', 'uses a relative asset/link path (it is served at unknown depths)');
}

/* ---------------------------------------------------------------- 9. report */

if (VERBOSE) {
  note(`external origins: ${externalSeen.size}`);
  for (const url of externalSeen) note(`  ${url}`);
}
if (notes.length && (VERBOSE || failures.length)) {
  console.log(notes.map((n) => `  ${n}`).join('\n'));
}
if (failures.length) {
  console.error(`\nSITE: FAIL (${failures.length})`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`\nSITE: PASS — ${pages.length} pages, ${images.length} images, checks in docs/website/design-contract.md § Verification`);
