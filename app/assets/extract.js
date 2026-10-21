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

  function extract() {
    clearMarks();
    var title = meta();
    var blocks = [];

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
        }
      }
    } catch (e) { /* fall through */ }

    if (blocks.length < 2) {
      blocks = collect(bestContainer() || document.body);
    }
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
    return '';
  }

  var style = document.createElement('style');
  style.textContent =
    '[data-ns].ns-active{background:rgba(255,214,0,.38);border-radius:3px;' +
    'box-shadow:0 0 0 2px rgba(255,214,0,.25);transition:background .15s ease}';
  (document.head || document.documentElement).appendChild(style);

  window.__nsExtract = extract;
  window.__nsNextLink = findNext;
  window.__nsHighlight = function (idx) {
    var prev = document.querySelector('[data-ns].ns-active');
    if (prev) prev.classList.remove('ns-active');
    var el = document.querySelector('[data-ns="' + idx + '"]');
    if (!el) return false;
    el.classList.add('ns-active');
    try { el.scrollIntoView({ block: 'center', behavior: 'smooth' }); } catch (e) { el.scrollIntoView(); }
    return true;
  };
  window.__nsClearHighlight = function () {
    var prev = document.querySelector('[data-ns].ns-active');
    if (prev) prev.classList.remove('ns-active');
  };
})();
