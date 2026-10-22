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
    return (s || '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
  }

  function clearMarks() {
    var old = document.querySelectorAll('[data-ns]');
    for (var i = 0; i < old.length; i++) old[i].removeAttribute('data-ns');
  }

  function collect(root) {
    if (!root) return [];
    var blocks = [];
    var seen = Object.create(null);
    var nodes = root.querySelectorAll(BLOCK_SEL);
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.closest(SKIP_SEL)) continue;
      if (el.querySelector(BLOCK_SEL)) continue;      // container, not a leaf block
      var t = norm(el.innerText || el.textContent);
      if (t.length < 2) continue;
      if (seen[t]) continue;
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
        var t = norm(el.innerText || el.textContent);
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
          blocks = collect(holder);
          holder.parentNode.removeChild(holder);
          if (article.title) title = norm(article.title);
          usedReadability = blocks.length > 0;
        }
      }
    } catch (e) { /* fall through */ }

    if (blocks.length < 2) {
      usedReadability = false;
      blocks = collect(bestContainer() || document.body);
    }
    if (usedReadability) tagLive(blocks);
    if (blocks.length === 0) {
      // Last resort: whole body as one block so the reader still says something.
      var t = norm(document.body ? document.body.innerText : '');
      if (t) blocks = [{ i: 0, text: t, tag: 'body' }];
    }

    var total = 0;
    for (var j = 0; j < blocks.length; j++) total += blocks[j].text.length;
    return JSON.stringify({ title: title, blocks: blocks, chars: total, url: location.href });
  }

  function findNext() {
    var anchors = document.querySelectorAll('a[rel~="next"], a.next, a[class*="next"], a[id*="next"]');
    for (var i = 0; i < anchors.length; i++) {
      var href = anchors[i].href;
      if (href && href !== location.href) return href;
    }
    var all = document.querySelectorAll('a');
    var re = /^\s*(next(\s+chapter)?|continue(\s+reading)?|read\s+more|下一[章节頁页]?|次へ)\s*[»>→]*\s*$/i;
    for (var k = 0; k < all.length; k++) {
      var txt = norm(all[k].innerText);
      if (txt && re.test(txt)) {
        var h = all[k].href;
        if (h && h.indexOf('javascript:') !== 0) return h;
      }
    }
    // Link text is often an icon; accept an href that clearly says "next".
    for (var p = 0; p < all.length; p++) {
      var hp = all[p].href;
      if (!hp || hp === location.href || hp.indexOf('javascript:') === 0) continue;
      if (/(^|[/_.-])next([/_.-]|$)/i.test(hp)) return hp;
    }
    // Chapter sites usually number URLs (/.../1426). Pick the +1 sibling.
    var here = location.href.match(/^(.*?)(\d+)(\/?)([?#].*)?$/);
    if (here) {
      var want = String(parseInt(here[2], 10) + 1);
      for (var q = 0; q < all.length; q++) {
        var hq = all[q].href;
        if (!hq || hq === location.href) continue;
        var m = hq.match(/^(.*?)(\d+)(\/?)([?#].*)?$/);
        if (m && m[1] === here[1] && m[2] === want && m[3] === here[3]) return hq;
      }
    }
    return '';
  }

  var style = document.createElement('style');
  style.textContent =
    '[data-ns].ns-active{background:rgba(255,214,0,.38);border-radius:3px;' +
    'box-shadow:0 0 0 2px rgba(255,214,0,.25);transition:background .15s ease}';
  (document.head || document.documentElement).appendChild(style);

  // Reader mode: keep the real page (so highlight and links still work) but strip the
  // site chrome, ads, cookie bars and challenge dialogs, and apply book-like typography.
  var readerStyle = document.createElement('style');
  readerStyle.textContent = `
    html.ns-reader, html.ns-reader body {
      background:#0F1115 !important; background-image:none !important;
      color:#E9EDF2 !important;
    }
    html.ns-reader body {
      max-width:760px !important; margin:0 auto !important;
      padding:16px 16px 56px !important;
      font-family:Georgia,'Times New Roman',serif !important;
      font-size:17px !important; line-height:1.7 !important;
    }
    html.ns-reader p, html.ns-reader li, html.ns-reader blockquote,
    html.ns-reader td, html.ns-reader dd {
      color:#E9EDF2 !important; background:transparent !important;
      font-size:17px !important; line-height:1.7 !important;
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
    html.ns-reader a { color:#6EE7B7 !important; }
    html.ns-reader img { max-width:100% !important; height:auto !important; }
    html.ns-reader article, html.ns-reader main,
    html.ns-reader [class*="chapter"], html.ns-reader [id*="chapter"],
    html.ns-reader [class*="content"], html.ns-reader [id*="content"] {
      background:transparent !important; color:#E9EDF2 !important;
      max-width:760px !important; margin-left:auto !important; margin-right:auto !important;
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
    html.ns-reader [id*="logo"], html.ns-reader [id*="header"] {
      display:none !important;
    }
    html.ns-reader .ns-reader-hidden { display:none !important; }
    html.ns-reader [data-ns].ns-active {
      background:rgba(246,196,83,.10) !important;
      box-shadow:inset 3px 0 0 #F6C453 !important;
      border-radius:4px !important;
    }
    html.ns-reader h1[data-ns].ns-active, html.ns-reader h2[data-ns].ns-active,
    html.ns-reader h3[data-ns].ns-active, html.ns-reader h4[data-ns].ns-active,
    html.ns-reader h5[data-ns].ns-active,
    html.ns-reader [data-ns="0"].ns-active, html.ns-reader .ns-active-heading {
      background:transparent !important;
      box-shadow:inset 3px 0 0 #F6C453 !important;
    }
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
      try { el.scrollIntoView({ block: 'center', behavior: 'smooth' }); } catch (e) { el.scrollIntoView(); }
    }
    return true;
  };
  window.__nsClearHighlight = function () {
    var prev = document.querySelector('[data-ns].ns-active');
    if (prev) {
      prev.classList.remove('ns-active');
      prev.classList.remove('ns-active-heading');
    }
  };
})();
