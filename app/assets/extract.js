// NovelSpeak generic article/chapter extractor.
// Strategy: Mozilla Readability first (same lib as Firefox Reader Mode), then a
// text-density fallback that mirrors what read-aloud implementations do on unknown sites.
// Every emitted block is tagged with data-ns="<index>" so playback can highlight/scroll.
(function () {
  if (window.__nsInstalled) return;
  window.__nsInstalled = true;

  var BLOCK_SEL = 'p, h1, h2, h3, h4, h5, li, blockquote, pre, td, dd';
  var SKIP_SEL = 'script, style, noscript, nav, header, footer, aside, form, button, select, svg, iframe';

  function norm(s) {
    return (s || '')
      .replace(/\u00a0/g, ' ')
      // Some novel sites obfuscate words with interior dots ("cla.s.sroom",
      // "b.a.s.t.a.r.d") to evade text filters, including single-letter runs
      // with "+" or "-" separators ("s.h.i.+ning"). Collapse only tokens made
      // of three or more letter/dot segments; leave e.g./i.e./U.S.A. alone.
      .replace(/\b[A-Za-z][A-Za-z]*(?:[.\-+]+[A-Za-z]+){2,}\b/g, function (m) {
        return m === m.toUpperCase() ? m : m.replace(/[.\-+]/g, '');
      })
      .replace(/\b[A-Za-z]+(?:\.[A-Za-z]+){2,}\b/g, function (m) {
        return m === m.toUpperCase() ? m : m.replace(/\./g, '');
      })
      .replace(/\s+/g, ' ')
      .trim();
  }

  function visible(el) {
    try {
      var cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') {
        return false;
      }
      return el.getClientRects().length > 0;
    } catch (e) {
      return true;
    }
  }

  function clearMarks() {
    var old = document.querySelectorAll('[data-ns]');
    for (var i = 0; i < old.length; i++) old[i].removeAttribute('data-ns');
  }

  function collect(root, useTextContent) {
    if (!root) return [];
    var blocks = [];
    var seen = Object.create(null);
    var nodes = root.querySelectorAll(BLOCK_SEL);
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.closest(SKIP_SEL)) continue;
      if (el.querySelector(BLOCK_SEL)) continue;      // container, not a leaf block
      // Readability's article holder is intentionally display:none, so visibility
      // checks only apply to the live DOM fallback. In the holder, textContent is
      // the only reliable source.
      if (!useTextContent && !visible(el)) continue;
      var t = norm(useTextContent ? (el.textContent || '') : (el.innerText || ''));
      if (t.length < 2) continue;
      // Dedupe only long blocks; short repeated dialogue lines are real text.
      if (t.length > 80 && seen[t]) continue;
      seen[t] = 1;
      el.setAttribute('data-ns', String(blocks.length));
      blocks.push({ i: blocks.length, text: t, tag: el.tagName.toLowerCase() });
    }
    return blocks;
  }

  // Text-density fallback for pages Readability cannot parse (chapter sites, Cloudflare pages).
  function bestContainer() {
    var cands = document.querySelectorAll(
      'article, main, [class*="chapter"], [id*="chapter"], [class*="content"], [id*="content"], ' +
      '[class*="read"], [id*="read"], [class*="text"], [id*="text"], [class*="entry"], [class*="post"], div, section'
    );
    var best = null, bestScore = 0;
    for (var i = 0; i < cands.length; i++) {
      var el = cands[i];
      if (el.closest(SKIP_SEL)) continue;
      var t = norm(el.innerText);
      if (t.length < 400) continue;
      var links = el.querySelectorAll('a').length;
      var paragraphs = el.querySelectorAll('p').length;
      var score = t.length - links * 80 + paragraphs * 40;
      if (score > bestScore) { bestScore = score; best = el; }
    }
    return best;
  }

  function meta() {
    var title = '';
    var h1 = document.querySelector('h1');
    if (h1) title = norm(h1.innerText);
    if (!title && document.title) title = norm(document.title);
    return title;
  }

  // Readability parses a detached clone, so the data-ns marks from collect() never reach the
  // visible page. Match extracted blocks back to real leaf elements by normalized text.
  function tagLive(blocks) {
    if (!blocks.length) return;
    var nodes = document.querySelectorAll(BLOCK_SEL);
    var used = Object.create(null);
    for (var b = 0; b < blocks.length; b++) {
      var want = blocks[b].text;
      var best = -1, bestScore = 0;
      for (var i = 0; i < nodes.length; i++) {
        if (used[i]) continue;
        var el = nodes[i];
        if (el.closest(SKIP_SEL)) continue;
        if (el.querySelector(BLOCK_SEL)) continue;
        if (!visible(el)) continue;
        var t = norm(el.innerText || '');
        if (!t) continue;
        var score = 0;
        if (t === want) score = 100;
        else if (t.length >= 20 && want.length >= 20 &&
                 (t.indexOf(want) === 0 || want.indexOf(t) === 0)) score = 60;
        if (score > bestScore) { bestScore = score; best = i; }
      }
      if (best >= 0) {
        used[best] = 1;
        nodes[best].setAttribute('data-ns', String(blocks[b].i));
      }
    }
  }

  function extract() {
    clearMarks();
    var title = meta();
    var blocks = [];
    var usedReadability = false;

    try {
      if (typeof Readability === 'function') {
        var clone = document.cloneNode(true);
        var article = new Readability(clone, { charThreshold: 200 }).parse();
        if (article && article.content) {
          var holder = document.createElement('div');
          holder.style.display = 'none';
          holder.innerHTML = article.content;
          document.body.appendChild(holder);
          blocks = collect(holder, true);
          holder.parentNode.removeChild(holder);
          if (article.title) title = norm(article.title);
          usedReadability = blocks.length > 0;
        }
      }
    } catch (e) { /* fall through */ }

    if (blocks.length < 2) {
      usedReadability = false;
      blocks = collect(bestContainer() || document.body, false);
    }
    if (usedReadability) tagLive(blocks);
    if (blocks.length === 0) {
      // Last resort: whole body as one block so the reader still says something.
      var t = norm(document.body ? document.body.innerText : '');
      if (t) blocks = [{ i: 0, text: t, tag: 'body' }];
    }

    var total = 0;
    for (var j = 0; j < blocks.length; j++) total += blocks[j].text.length;
    var wall = detectWall(total);
    return JSON.stringify({
      title: title, blocks: blocks, chars: total, url: location.href, wall: wall
    });
  }

  // Detect login/subscription walls and challenge interstitials so the UI can warn
  // honestly instead of speaking garbage. Never bypasses anything.
  function detectWall(total) {
    if (total >= 1200) return '';
    var bodyText = '';
    try {
      bodyText = norm(document.body ? document.body.innerText : '').toLowerCase();
    } catch (e) { bodyText = ''; }
    var hasPassword = false;
    try {
      hasPassword = !!document.querySelector('input[type="password"]');
    } catch (e) { hasPassword = false; }
    var strong = /(sign ?in|log ?in) to (continue|read|comment|view)|login required|subscribe to (continue|read|unlock)|subscriber[- ]only|members?[- ]only|premium (chapter|content|story)|unlock (this )?chapter|create (a )?free account|register to (continue|read)|this (chapter|content|story) is for (subscribers|members)/;
    var m = bodyText.match(strong);
    if (m) {
      return /sign ?in|log ?in|login/.test(m[0]) ? 'login' : 'subscribe';
    }
    if (hasPassword && total < 600) return 'login';
    if (total < 200 && /(enable javascript|verify you are human|checking your browser|attention required|just a moment)/.test(bodyText)) {
      return 'challenge';
    }
    return '';
  }

  // Apply user typography (font size/family/theme/width) after the built-in reader CSS.
  window.__nsApplyReaderStyle = function (css) {
    var id = 'ns-reader-user-style';
    var el = document.getElementById(id);
    if (!el) {
      el = document.createElement('style');
      el.id = id;
      (document.head || document.documentElement).appendChild(el);
    }
    el.textContent = css || '';
    return true;
  };

  function findNext() {
    var here = location.href;
    function usable(href) {
      return href && href !== here && href.indexOf('javascript:') !== 0 && href.indexOf('mailto:') !== 0;
    }
    // 1. Explicit machine-readable next links.
    var rel = document.querySelectorAll('link[rel~="next"], a[rel~="next"]');
    for (var r = 0; r < rel.length; r++) {
      var hr = rel[r].href;
      if (usable(hr)) return hr;
    }
    // 2. Class / id / aria / title hints used by chapter themes.
    var marked = document.querySelectorAll(
      'a.next, a[class*="next"], a[id*="next"], a[data-next], ' +
      'a[aria-label*="next" i], a[title*="next" i]'
    );
    for (var i = 0; i < marked.length; i++) {
      var hm = marked[i].href;
      var txt = norm(marked[i].innerText || marked[i].getAttribute('aria-label') || '');
      if (usable(hm) && !/prev|previous|back/i.test(txt)) return hm;
    }
    var all = document.querySelectorAll('a');
    // 3. Visible label is a next/continue call to action, with decorations/icons around it.
    var re = /^\s*(next(\s+(chapter|page|part))?|continue(\s+reading)?|read\s+(the\s+)?next|下一[章节頁页]?|次へ)\s*[»>→…\.\-]*\s*$/i;
    for (var k = 0; k < all.length; k++) {
      var label = norm(all[k].innerText);
      var hk = all[k].href;
      if (label && re.test(label) && usable(hk)) return hk;
    }
    // 4. Label merely contains "next" (e.g. "Next Chapter (144)") but is not a previous link.
    for (var k2 = 0; k2 < all.length; k2++) {
      var label2 = norm(all[k2].innerText);
      var hk2 = all[k2].href;
      if (label2 && /next/i.test(label2) && !/prev|previous|back/i.test(label2) && usable(hk2)) {
        return hk2;
      }
    }
    // 5. Link text is often an icon; accept an href that clearly says "next".
    for (var p = 0; p < all.length; p++) {
      var hp = all[p].href;
      if (!usable(hp)) continue;
      if (/(^|[/_.-])next([/_.-]|$)/i.test(hp)) return hp;
    }
    // 6. Chapter sites usually number URLs (/.../143). Pick the +1 sibling.
    var hereMatch = here.match(/^(.*?)(\d+)(\/?)([?#].*)?$/);
    if (hereMatch) {
      var want = String(parseInt(hereMatch[2], 10) + 1);
      for (var q = 0; q < all.length; q++) {
        var hq = all[q].href;
        if (!hq || hq === here) continue;
        var m = hq.match(/^(.*?)(\d+)(\/?)([?#].*)?$/);
        if (m && m[1] === hereMatch[1] && m[2] === want && m[3] === hereMatch[3]) return hq;
      }
    }
    return '';
  }

  var style = document.createElement('style');
  style.textContent =
    '[data-ns].ns-active{background:rgba(244,198,106,.35);border-radius:3px;' +
    'box-shadow:0 0 0 2px rgba(244,198,106,.22);transition:background .15s ease}';
  (document.head || document.documentElement).appendChild(style);

  // Reader mode: keep the real page (so highlight and links still work) but strip the
  // site chrome, ads, cookie bars and challenge dialogs, and apply book-like typography.
  var readerStyle = document.createElement('style');
  readerStyle.textContent = `
    html.ns-reader, html.ns-reader body {
      background:#0E1318 !important; background-image:none !important;
      color:#EAF0F5 !important;
    }
    html.ns-reader body {
      max-width:var(--ns-width,760px) !important; margin:0 auto !important;
      padding:16px 16px 56px !important;
      font-family:var(--ns-font-family,Georgia,'Times New Roman',serif) !important;
      font-size:var(--ns-font-size,17px) !important;
      line-height:var(--ns-line-height,1.7) !important;
    }
    html.ns-reader p, html.ns-reader li, html.ns-reader blockquote,
    html.ns-reader td, html.ns-reader dd {
      color:#EAF0F5 !important; background:transparent !important;
      font-size:var(--ns-font-size,17px) !important;
      line-height:var(--ns-line-height,1.7) !important;
      margin:0 0 1.05em !important;
    }
    html.ns-reader h1, html.ns-reader h2, html.ns-reader h3,
    html.ns-reader h4, html.ns-reader h5 {
      color:#FFFFFF !important; line-height:1.3 !important;
      margin:1.1em 0 .55em !important;
    }
    html.ns-reader h1 { font-size:1.5em !important; }
    html.ns-reader h2 { font-size:1.32em !important; }
    html.ns-reader h3 { font-size:1.18em !important; }
    html.ns-reader a { color:#6FE7BE !important; }
    html.ns-reader img { max-width:100% !important; height:auto !important; }
    html.ns-reader article, html.ns-reader main,
    html.ns-reader [class*="chapter"], html.ns-reader [id*="chapter"],
    html.ns-reader [class*="content"], html.ns-reader [id*="content"] {
      background:transparent !important; color:#EAF0F5 !important;
      max-width:var(--ns-width,760px) !important;
      margin-left:auto !important; margin-right:auto !important;
    }
    /* Word-level read-along uses the CSS Custom Highlight API (Chromium 105+). */
    ::highlight(ns-word) {
      background: rgba(110, 231, 183, 0.55) !important;
      color: #0E1318 !important;
    }
    html.ns-reader nav, html.ns-reader header, html.ns-reader footer,
    html.ns-reader aside, html.ns-reader iframe,
    html.ns-reader [class*="advert"], html.ns-reader [id*="advert"],
    html.ns-reader [class^="ad-"], html.ns-reader [id^="ad-"],
    html.ns-reader [class*="banner"], html.ns-reader [class*="modal"],
    html.ns-reader [class*="popup"], html.ns-reader [class*="overlay"],
    html.ns-reader [class*="cookie"], html.ns-reader [class*="challenge"],
    html.ns-reader [class*="fomo"], html.ns-reader [class*="sidebar"],
    html.ns-reader [class*="site-header"], html.ns-reader [class*="site-footer"],
    html.ns-reader button, html.ns-reader input, html.ns-reader select,
    html.ns-reader form, html.ns-reader [class*="toolbar"],
    html.ns-reader [class*="controls"], html.ns-reader [class*="chapter-nav"],
    html.ns-reader [class*="chapter-header"], html.ns-reader [class*="breadcrumb"],
    html.ns-reader [class*="pagination"], html.ns-reader [class*="navbar"],
    html.ns-reader [class*="menu"], html.ns-reader [class*="logo"],
    html.ns-reader [id*="logo"], html.ns-reader [id*="header"],
    html.ns-reader #centralNotice, html.ns-reader [id*="centralNotice"],
    html.ns-reader [class*="cbnnr"], html.ns-reader [class*="fundraising"],
    html.ns-reader [id*="fundraising"], html.ns-reader .hatnote,
    html.ns-reader .mw-editsection, html.ns-reader [class*="editsection"] {
      display:none !important;
    }
    html.ns-reader .ns-reader-hidden { display:none !important; }
    html.ns-reader [data-ns].ns-active {
      background:rgba(244,198,106,.10) !important;
      box-shadow:inset 3px 0 0 #F4C66A !important;
      border-radius:4px !important;
    }
    html.ns-reader h1[data-ns].ns-active, html.ns-reader h2[data-ns].ns-active,
    html.ns-reader h3[data-ns].ns-active, html.ns-reader h4[data-ns].ns-active,
    html.ns-reader h5[data-ns].ns-active,
    html.ns-reader [data-ns="0"].ns-active, html.ns-reader .ns-active-heading {
      background:transparent !important;
      box-shadow:inset 3px 0 0 #F4C66A !important;
    }

    /* ---- surface reset -------------------------------------------------
       Sites ship their own light panels (Wikipedia's .mw-body/hatnote/
       infobox, news walls, banner wrappers). Only recoloring body/p/li left
       those surfaces light while the text turned near-white: unreadable.
       Clear every background, then re-add a dark card for code/quote blocks. */
    html.ns-reader { overflow-x:hidden !important; }
    html.ns-reader body * {
      background-color:transparent !important;
      background-image:none !important;
      box-shadow:none !important;
      border-color:rgba(255,255,255,.14) !important;
    }
    html.ns-reader body div, html.ns-reader body span,
    html.ns-reader body section, html.ns-reader body article, html.ns-reader body main,
    html.ns-reader body table, html.ns-reader body caption,
    html.ns-reader body thead, html.ns-reader body tbody, html.ns-reader body tfoot,
    html.ns-reader body tr, html.ns-reader body td, html.ns-reader body th,
    html.ns-reader body ul, html.ns-reader body ol, html.ns-reader body li,
    html.ns-reader body dl, html.ns-reader body dt, html.ns-reader body dd,
    html.ns-reader body figure, html.ns-reader body figcaption,
    html.ns-reader body pre, html.ns-reader body code, html.ns-reader body center {
      color:#EAF0F5 !important;
    }
    html.ns-reader a, html.ns-reader a:visited { color:#6FE7BE !important; }
    html.ns-reader pre {
      background:rgba(255,255,255,.06) !important;
      padding:10px !important; border-radius:6px !important;
      white-space:pre-wrap !important; overflow-x:auto !important;
    }
    html.ns-reader code {
      background:rgba(255,255,255,.08) !important;
      padding:1px 4px !important; border-radius:4px !important;
    }

    /* ---- layout reset -------------------------------------------------
       Minerva (and several novel themes) switch tables to flex, which
       scatters infobox cells across the viewport and clips the edges. */
    html.ns-reader table {
      display:table !important; width:100% !important; max-width:100% !important;
      table-layout:auto !important; border-collapse:collapse !important;
    }
    html.ns-reader caption { display:table-caption !important; text-align:left !important; }
    html.ns-reader thead { display:table-header-group !important; }
    html.ns-reader tbody { display:table-row-group !important; }
    html.ns-reader tfoot { display:table-footer-group !important; }
    html.ns-reader tr { display:table-row !important; }
    html.ns-reader td, html.ns-reader th {
      display:table-cell !important; white-space:normal !important;
      word-break:break-word !important; padding:4px 6px !important;
      border-bottom:1px solid rgba(255,255,255,.12) !important;
    }
    html.ns-reader table.infobox, html.ns-reader .infobox { margin:0 0 1em !important; }

    /* ---- media -------------------------------------------------------- */
    html.ns-reader img, html.ns-reader video, html.ns-reader canvas,
    html.ns-reader svg, html.ns-reader figure, html.ns-reader table,
    html.ns-reader pre, html.ns-reader .mwe-math-element {
      max-width:100% !important; height:auto !important;
    }
    html.ns-reader .mwe-math-element { overflow-x:auto !important; }
  `;
  (document.head || document.documentElement).appendChild(readerStyle);

  window.__nsReader = function (on) {
    var root = document.documentElement;
    if (!root) return false;
    if (on) {
      root.classList.add('ns-reader');
      var els = document.querySelectorAll('body *');
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        try {
          var cs = getComputedStyle(el);
          if ((cs.position === 'fixed' || cs.position === 'sticky') &&
              el.offsetWidth > 180 && el.offsetHeight > 70) {
            el.classList.add('ns-reader-hidden');
          }
        } catch (e) { /* ignore cross-origin/layout errors */ }
      }
      // Remove site chrome before/after the extracted article: logos, toolbars,
      // previous/next widgets, footers. Only siblings on the path to the blocks.
      var blocks = document.querySelectorAll('[data-ns]');
      if (blocks.length) {
        hideSiblingsBefore(blocks[0]);
        hideSiblingsAfter(blocks[blocks.length - 1]);
      }
    } else {
      root.classList.remove('ns-reader');
      var hidden = document.querySelectorAll('.ns-reader-hidden');
      for (var j = 0; j < hidden.length; j++) {
        hidden[j].classList.remove('ns-reader-hidden');
      }
    }
    return true;
  };

  function hideSiblingsBefore(node) {
    var cur = node;
    while (cur && cur.parentElement && cur !== document.body) {
      var s = cur.previousElementSibling;
      while (s) {
        s.classList.add('ns-reader-hidden');
        s = s.previousElementSibling;
      }
      cur = cur.parentElement;
    }
  }

  function hideSiblingsAfter(node) {
    var cur = node;
    while (cur && cur.parentElement && cur !== document.body) {
      var s = cur.nextElementSibling;
      while (s) {
        s.classList.add('ns-reader-hidden');
        s = s.nextElementSibling;
      }
      cur = cur.parentElement;
    }
  }

  window.__nsExtract = extract;
  window.__nsNextLink = findNext;
  window.__nsHighlight = function (idx, scroll) {
    var prev = document.querySelector('[data-ns].ns-active');
    if (prev) {
      prev.classList.remove('ns-active');
      prev.classList.remove('ns-active-heading');
    }
    var el = document.querySelector('[data-ns="' + idx + '"]');
    if (!el) return false;
    el.classList.add('ns-active');
    if (/^H[1-6]$/.test(el.tagName || '')) el.classList.add('ns-active-heading');
    if (scroll !== false) {
      try { el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' }); } catch (e) { el.scrollIntoView(); }
    }
    return true;
  };
  // Map normalized block text (what TTS speaks) back to raw DOM text nodes so a
  // character range can be highlighted without mutating the page.
  function normMap(el) {
    var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null, false);
    var norm = '', map = [], node;
    while ((node = walker.nextNode())) {
      var t = node.nodeValue || '';
      for (var i = 0; i < t.length; i++) {
        var c = t.charAt(i);
        if (/\s/.test(c)) {
          if (norm.length && norm.charAt(norm.length - 1) !== ' ') {
            norm += ' ';
            map.push({ n: node, o: i });
          }
        } else {
          norm += c;
          map.push({ n: node, o: i });
        }
      }
    }
    return { norm: norm, map: map };
  }

  window.__nsHighlightWord = function (idx, start, end) {
    var el = document.querySelector('[data-ns="' + idx + '"]');
    if (!el || !(window.CSS && CSS.highlights && window.Highlight)) return false;
    try {
      var m = normMap(el);
      if (!m.norm.length) return false;
      start = Math.max(0, Math.min(start | 0, m.norm.length - 1));
      end = Math.max(start + 1, Math.min(end | 0, m.norm.length));
      var a = m.map[start], b = m.map[end - 1];
      if (!a || !b) return false;
      var r = document.createRange();
      r.setStart(a.n, a.o);
      r.setEnd(b.n, b.o + 1);
      CSS.highlights.set('ns-word', new Highlight(r));
      return true;
    } catch (e) {
      return false;
    }
  };

  window.__nsClearHighlight = function () {
    var prev = document.querySelector('[data-ns].ns-active');
    if (prev) {
      prev.classList.remove('ns-active');
      prev.classList.remove('ns-active-heading');
    }
    try {
      if (window.CSS && CSS.highlights) CSS.highlights.delete('ns-word');
    } catch (e) { /* ignore */ }
  };
})();
