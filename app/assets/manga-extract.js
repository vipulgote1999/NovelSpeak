// NovelSpeak manga chapter extractor.
//
// Runs inside the page WebView (or a headless browser in the regression test). It collects
// page-image candidates from four places:
//   1. rendered <img>/<picture> elements, including lazy data-* attributes and declared sizes
//   2. page backgrounds and preload links
//   3. the largest repeated viewer stack on the page (works when every page lives in its own
//      timestamped CDN directory, as on Webtoons and Tapas)
//   4. the page's own JavaScript payload (Next.js/Nuxt chapters often put the whole page list in
//      a serialized JSON blob while only rendering the first two <img> tags)
//
// Candidates are grouped by directory and scored by how many form a numbered run. When a site
// has no numbered URLs, the viewer stack is used in DOM order. Chrome, thumbnails, covers and
// recommendation cards are rejected before either path runs.
//
// The result is a JSON object on window.__nsMangaResult:
//   { title, urls: [...], count, groups, scanMs }
// window.__nsMangaDone flips to true when the scan is complete.
(function () {
  if (window.__nsMangaRunning) return;
  window.__nsMangaRunning = true;
  window.__nsMangaDone = false;
  window.__nsMangaResult = null;
  window.__nsMangaProgress = 'starting';

  var MAX_SCAN_MS = 22000;
  var MAX_PAGES = 400;
  var started = Date.now();
  var candidates = {};
  var orderSeq = 0;
  var viewerUrls = [];
  var viewerSeen = {};
  var domCandidates = [];

  var BAD = /(logo|icon|avatar|sprite|favicon|banner|advert|promo|emoji|badge|profile|thumb|thmb|social|share|related|recommend|placeholder|spinner|loading|blank|pixel)/i;
  var BAD_DIR = /(thumb|thmb|avatar|icon|banner|sprite|emoji|profile|social|related|recommend)/i;
  var CHROME_NODE = /(logo|avatar|thumb|thmb|card|cover|banner|nav|header|footer|sidebar|related|recommend|splash|sprite|badge|emoji|comment|social|share)/i;
  var PAGE_HINT = /(page|chapter|manga|comic|panel|scan|raw|webtoon|episode)/i;
  var IMG_EXT = /\.(png|jpe?g|webp|avif)(?:[?#][^\s"'<>\\]*)?$/i;

  // These selectors are unambiguous page containers, so a lazy image is accepted even when
  // its height is not known until the browser decodes it (Tapas leaves height empty).
  var STRONG_VIEWER_SELECTORS = [
    '#_imageList img',
    '.content__img',
    'img.reader-img',
    'img.episode__img',
    '#readerarea img',
    '.reading-content img',
    '.chapter-images img',
    '.episode__content img'
  ];
  var VIEWER_SELECTORS = STRONG_VIEWER_SELECTORS.concat([
    '.viewer_img img',
    '.chapter-content img',
    '.page-break img',
    '.viewer__body img',
    '.episode-body img',
    '[data-reader] img'
  ]);

  function abs(u) {
    if (!u) return null;
    u = String(u).trim().replace(/^["']+|["']+$/g, '');
    u = u.replace(/\\\//g, '/').replace(/\\u002F/gi, '/').replace(/&amp;/g, '&');
    if (!u || u.indexOf('data:') === 0 || u.indexOf('blob:') === 0) return null;
    if (u.indexOf('//') === 0) u = location.protocol + u;
    else if (u.charAt(0) === '/') u = location.origin + u;
    else if (!/^https?:/i.test(u)) {
      try { u = new URL(u, location.href).href; } catch (e) { return null; }
    }
    if (!/^(https?|file):/i.test(u)) return null;
    return u.split('#')[0];
  }

  function add(raw, width, height, alt, fromDom) {
    var u = abs(raw);
    if (!u || !IMG_EXT.test(u)) return null;
    if (BAD.test(u) || BAD.test(alt || '')) return null;
    var existing = candidates[u];
    if (!existing) {
      existing = {
        url: u,
        w: width || 0,
        h: height || 0,
        alt: alt || '',
        order: orderSeq++,
        dom: !!fromDom
      };
      candidates[u] = existing;
    } else {
      if (width > existing.w) existing.w = width;
      if (height > existing.h) existing.h = height;
      if (fromDom) existing.dom = true;
      if (alt) existing.alt = alt;
    }
    return existing;
  }

  function viewerKey(url) {
    var parts = String(url).split('?');
    if (parts.length < 2) return parts[0];
    var keep = parts[1].split('&').filter(function (part) {
      return !/^(type|quality|q|w|width|h|height|resize|fit)=/i.test(part);
    });
    return parts[0] + (keep.length ? '?' + keep.join('&') : '');
  }

  function markViewer(candidate) {
    if (!candidate) return;
    var key = viewerKey(candidate.url);
    if (viewerSeen[key]) return;
    viewerSeen[key] = true;
    viewerUrls.push(candidate.url);
  }

  function bestSrcset(value) {
    if (!value) return null;
    var parts = String(value).split(',');
    var best = null;
    var bestScore = -1;
    for (var i = 0; i < parts.length; i++) {
      var bits = parts[i].trim().split(/\s+/);
      if (!bits[0] || !IMG_EXT.test(bits[0])) continue;
      var score = 1;
      if (bits[1]) {
        var n = parseFloat(bits[1]);
        if (isFinite(n)) score = n;
      }
      if (score > bestScore) {
        bestScore = score;
        best = bits[0];
      }
    }
    return best;
  }

  function number(value) {
    var n = parseFloat(value);
    return isFinite(n) && n > 0 ? n : 0;
  }

  function declaredSize(img) {
    var nw = img.naturalWidth || 0;
    var nh = img.naturalHeight || 0;
    var w = nw > 2 ? nw : (number(img.getAttribute('width')) || img.width || 0);
    var h = nh > 2 ? nh : (number(img.getAttribute('height')) || img.height || 0);
    return { w: w, h: h };
  }

  function isPageShape(w, h, url, alt) {
    // Real page slices are large. A 280x400 cover card is below this area threshold; viewer
    // pages on Webtoons/Tapas declare 700x1140 or 940x1280.
    if (w >= 250 && h >= 250 && w * h >= 100000) return true;
    if (w >= 400 && h >= 400) return true;
    // Lazy placeholders may have no usable dimensions yet. Keep an explicit page URL.
    if ((!w || !h) && PAGE_HINT.test(url + ' ' + (alt || ''))) return true;
    return false;
  }

  function isChromeNode(img) {
    var node = img;
    for (var depth = 0; node && depth < 5; depth++) {
      var label = (node.id || '') + ' ' + (node.className || '') + ' ' +
        (node.getAttribute ? (node.getAttribute('role') || '') : '');
      if (CHROME_NODE.test(label)) return true;
      node = node.parentElement;
    }
    return false;
  }

  function imageUrl(img) {
    var src = img.currentSrc || img.src || '';
    if (/^data:/i.test(src) || /(spacer|blank|pixel|loading)[.\-_]/i.test(src)) src = '';
    var srcset = bestSrcset(img.getAttribute('srcset'));
    return img.getAttribute('data-original') ||
      img.getAttribute('data-src') ||
      img.getAttribute('data-lazy-src') ||
      img.getAttribute('data-url') ||
      img.getAttribute('data-echo') ||
      img.getAttribute('data-cfsrc') ||
      srcset || src;
  }

  function collectDom() {
    var imgs = document.querySelectorAll('img');
    for (var i = 0; i < imgs.length; i++) {
      var img = imgs[i];
      if (isChromeNode(img)) continue;
      var size = declaredSize(img);
      var alt = img.getAttribute('alt') || '';
      var url = imageUrl(img);
      if (url && isPageShape(size.w, size.h, url, alt)) {
        var candidate = add(url, size.w, size.h, alt, true);
        if (candidate) domCandidates.push({ candidate: candidate, node: img });
      }

      var sources = img.querySelectorAll ? img.querySelectorAll('source') : [];
      for (var s = 0; s < sources.length; s++) {
        var su = bestSrcset(sources[s].getAttribute('srcset')) || sources[s].getAttribute('src');
        if (su && isPageShape(size.w, size.h, su, alt)) add(su, size.w, size.h, alt, true);
      }
    }

    var pictureSources = document.querySelectorAll('picture source');
    for (var p = 0; p < pictureSources.length; p++) {
      var pu = bestSrcset(pictureSources[p].getAttribute('srcset')) ||
        pictureSources[p].getAttribute('src');
      if (pu) add(pu, 0, 0, '', true);
    }

    var backgrounds = document.querySelectorAll('[style*="background-image"]');
    for (var b = 0; b < backgrounds.length; b++) {
      var bg = backgrounds[b];
      if (isChromeNode(bg)) continue;
      var m = bg.style.backgroundImage.match(/url\(["']?([^"')]+)["']?\)/);
      if (m && IMG_EXT.test(m[1])) {
        add(m[1], bg.clientWidth || 0, bg.clientHeight || 0, '', true);
      }
    }

    var preloads = document.querySelectorAll('link[rel="preload"][as="image"]');
    for (var l = 0; l < preloads.length; l++) {
      var href = preloads[l].getAttribute('href');
      if (href && IMG_EXT.test(href)) add(href, 0, 0, '', true);
    }
  }

  /**
   * Known viewer containers first, then the largest repeated ancestor stack. This is what makes
   * Webtoons (every page has a unique timestamp directory) and Tapas (one class per page image)
   * work without a site-specific rule.
   */
  function collectViewer() {
    for (var s = 0; s < VIEWER_SELECTORS.length; s++) {
      var strong = STRONG_VIEWER_SELECTORS.indexOf(VIEWER_SELECTORS[s]) >= 0;
      var imgs = document.querySelectorAll(VIEWER_SELECTORS[s]);
      for (var i = 0; i < imgs.length; i++) {
        var img = imgs[i];
        if (isChromeNode(img)) continue;
        var size = declaredSize(img);
        var alt = img.getAttribute('alt') || '';
        var url = imageUrl(img);
        if (!url) continue;
        if (!strong && !isPageShape(size.w, size.h, url, alt)) continue;
        markViewer(add(url, size.w, size.h, alt, true));
      }
    }
    if (viewerUrls.length >= 3) return;

    // No selector matched. Walk ancestors of every large DOM image and keep the deepest
    // container that holds the most pages.
    var containers = new Map();
    for (var d = 0; d < domCandidates.length; d++) {
      var node = domCandidates[d].node;
      var candidate = domCandidates[d].candidate;
      var parent = node.parentElement;
      var depth = 0;
      while (parent && parent !== document.body && parent !== document.documentElement && depth < 7) {
        var entry = containers.get(parent);
        if (!entry) {
          entry = { element: parent, items: [], depth: depth };
          containers.set(parent, entry);
        }
        entry.items.push(candidate);
        parent = parent.parentElement;
        depth++;
      }
    }
    var best = null;
    var bestScore = -1;
    containers.forEach(function (entry) {
      if (entry.items.length < 3) return;
      var score = entry.items.length * 100 + entry.depth;
      if (score > bestScore) {
        bestScore = score;
        best = entry;
      }
    });
    if (best) {
      best.items.sort(function (a, b) { return a.order - b.order; });
      for (var k = 0; k < best.items.length; k++) markViewer(best.items[k]);
    }
  }

  function collectScripts() {
    var scripts = document.querySelectorAll('script');
    var total = 0;
    for (var i = 0; i < scripts.length; i++) {
      var text = scripts[i].textContent || '';
      if (!text || total > 4000000) continue;
      total += text.length;
      if (text.length > 2000000) text = text.substring(0, 2000000);
      // Serialized payloads escape slashes ("\/api\/img") and sometimes unicode slashes.
      var normalized = text.replace(/\\\//g, '/').replace(/\\u002F/gi, '/');
      var re = /((?:https?:)?(?:\/|\.{1,2}\/)?[A-Za-z0-9_@~%+=:,.'\/\-]+\.(?:png|jpe?g|webp|avif)(?:\?[^\s"'<>()\\]*)?)/gi;
      var match;
      var guard = 0;
      while ((match = re.exec(normalized)) !== null && guard++ < 5000) {
        add(match[1], 0, 0, '', false);
      }
    }
  }

  function pageNumber(url) {
    var path = url.split('?')[0];
    var m = path.match(/(\d{1,4})(?=\.[a-z]{3,4}$)/i);
    if (m) return parseInt(m[1], 10);
    var q = url.match(/[?&](?:page|p|pg)=(\d{1,4})/i);
    if (q) return parseInt(q[1], 10);
    return -1;
  }

  function groupKey(url) {
    var path = url.split('?')[0];
    var slash = path.lastIndexOf('/');
    return (slash > 0 ? path.substring(0, slash + 1) : path).toLowerCase();
  }

  function longestRun(numbers) {
    if (!numbers.length) return 0;
    var sorted = numbers.slice().sort(function (a, b) { return a - b; });
    var best = 1;
    var run = 1;
    for (var i = 1; i < sorted.length; i++) {
      if (sorted[i] === sorted[i - 1]) continue;
      if (sorted[i] === sorted[i - 1] + 1) {
        run++;
        if (run > best) best = run;
      } else {
        run = 1;
      }
    }
    return best;
  }

  function pickTitle() {
    var og = document.querySelector('meta[property="og:title"]');
    var t = (og && og.content) || '';
    if (!t) {
      var h1 = document.querySelector('h1');
      t = (h1 && h1.textContent) || document.title || '';
    }
    t = String(t).replace(/\s+/g, ' ').trim();
    t = t.replace(/\s*[-|–—]\s*Read .*$/i, '');
    t = t.replace(/\s*\|\s*(?:Read|Manga|Home).*$/i, '');
    return t || 'Manga';
  }

  function finish() {
    var all = [];
    for (var key in candidates) {
      if (Object.prototype.hasOwnProperty.call(candidates, key)) all.push(candidates[key]);
    }
    var groups = {};
    for (var i = 0; i < all.length; i++) {
      var g = groupKey(all[i].url);
      if (!groups[g]) groups[g] = [];
      groups[g].push(all[i]);
    }

    var bestGroup = null;
    var bestScore = -1;
    var bestRun = 0;
    var groupReport = [];
    for (var gk in groups) {
      if (!Object.prototype.hasOwnProperty.call(groups, gk)) continue;
      var arr = groups[gk];
      var nums = [];
      for (var j = 0; j < arr.length; j++) {
        var n = pageNumber(arr[j].url);
        if (n > 0) nums.push(n);
      }
      var run = longestRun(nums);
      var score = arr.length * 10 + run * 30 + (PAGE_HINT.test(gk) ? 40 : 0);
      if (BAD_DIR.test(gk)) score -= 500;
      groupReport.push({ dir: gk, count: arr.length, run: run, score: score });
      if (score > bestScore) {
        bestScore = score;
        bestGroup = arr;
        bestRun = run;
      }
    }

    var viewer = [];
    for (var v = 0; v < viewerUrls.length; v++) {
      var vc = candidates[viewerUrls[v]];
      if (vc) viewer.push(vc);
    }

    var domPages = all.filter(function (c) {
      return c.dom && isPageShape(c.w, c.h, c.url, c.alt);
    });
    var chosen;
    var usingViewer = false;
    if (bestGroup && bestGroup.length >= 3 && bestRun >= 3) {
      chosen = bestGroup.slice();
    } else if (viewer.length >= 3) {
      chosen = viewer.slice();
      usingViewer = true;
    } else if (bestGroup && bestGroup.length >= 3) {
      chosen = bestGroup.slice();
    } else if (domPages.length >= 2) {
      chosen = domPages.slice();
    } else {
      chosen = all.slice();
    }

    // De-duplicate by page number when the chapter is numbered; keep the highest-resolution
    // candidate for a page (srcset variants and thumbnails otherwise become duplicate pages).
    var numbered = [];
    var unnumbered = [];
    for (var k = 0; k < chosen.length; k++) {
      var num = pageNumber(chosen[k].url);
      if (num > 0) numbered.push(chosen[k]);
      else unnumbered.push(chosen[k]);
    }
    var byNumber = {};
    for (var q = 0; q < numbered.length; q++) {
      var item = numbered[q];
      var numKey = String(pageNumber(item.url));
      var prev = byNumber[numKey];
      if (!prev || (item.w * item.h) > (prev.w * prev.h)) byNumber[numKey] = item;
    }
    var pageList = [];
    for (var nk in byNumber) {
      if (Object.prototype.hasOwnProperty.call(byNumber, nk)) pageList.push(byNumber[nk]);
    }
    pageList.sort(function (a, b) { return pageNumber(a.url) - pageNumber(b.url); });

    if (pageList.length >= 3 && !usingViewer) {
      // A cover/credits image without a number sometimes sits before page 1. Keep unnumbered
      // DOM images that were collected before the first numbered page, drop the rest.
      var firstOrder = pageList.length ? pageList[0].order : Infinity;
      var before = unnumbered.filter(function (c) {
        return c.dom && c.order < firstOrder && isPageShape(c.w, c.h, c.url, c.alt);
      });
      before.sort(function (a, b) { return a.order - b.order; });
      chosen = before.concat(pageList);
    } else if (!usingViewer) {
      chosen = chosen.sort(function (a, b) {
        var na = pageNumber(a.url);
        var nb = pageNumber(b.url);
        if (na > 0 && nb > 0 && na !== nb) return na - nb;
        return a.order - b.order;
      });
    } else {
      // Viewer order is already the reading order; do not re-sort by URL.
      chosen = viewer.slice();
    }

    var urls = [];
    var seen = {};
    for (var x = 0; x < chosen.length && urls.length < MAX_PAGES; x++) {
      if (!seen[chosen[x].url]) {
        seen[chosen[x].url] = true;
        urls.push(chosen[x].url);
      }
    }

    window.__nsMangaResult = {
      title: pickTitle(),
      urls: urls,
      count: urls.length,
      groups: groupReport.sort(function (a, b) { return b.score - a.score; }).slice(0, 5),
      source: usingViewer ? 'viewer' : 'numbered',
      scanMs: Date.now() - started
    };
    window.__nsMangaProgress = 'done';
    window.__nsMangaDone = true;
    window.__nsMangaRunning = false;
  }

  function step() {
    collectDom();
    collectViewer();
    var doc = document.documentElement;
    var max = Math.max(
      document.body ? document.body.scrollHeight : 0,
      doc ? doc.scrollHeight : 0
    );
    var vh = window.innerHeight || 800;
    var elapsed = Date.now() - started;
    window.__nsMangaProgress = 'scanning ' + Math.round(window.scrollY) + '/' + max;
    if (elapsed < MAX_SCAN_MS && window.scrollY + vh < max - 4) {
      window.scrollBy(0, Math.max(vh * 0.9, 600));
      setTimeout(step, 110);
    } else {
      window.scrollTo(0, 0);
      // Give the last lazy images and the hydrated framework a moment to settle before the
      // final collection.
      setTimeout(function () {
        collectDom();
        collectViewer();
        collectScripts();
        window.scrollTo(0, 0);
        setTimeout(function () {
          collectDom();
          collectViewer();
          finish();
        }, 350);
      }, 500);
    }
  }

  // Collect what is already rendered, then walk the chapter so lazy-loaded page tags exist,
  // then finish with the viewer stack and the framework payload.
  collectDom();
  collectViewer();
  setTimeout(step, 250);
})();
