// NovelSpeak manga chapter extractor.
//
// Runs inside the page WebView (or a headless browser in the regression test). It collects
// page-image candidates from three places: rendered <img>/<picture> elements, CSS backgrounds,
// and the page's own JavaScript payload (Next.js/Nuxt chapters often put the whole page list in a
// serialized JSON blob while only rendering the first two <img> tags). Candidates are then
// grouped by directory, scored by how many form a numbered run, and returned in page order.
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

  var MAX_SCAN_MS = 15000;
  var started = Date.now();
  var candidates = {};
  var orderSeq = 0;

  var BAD = /(logo|icon|avatar|sprite|favicon|banner|advert|promo|emoji|badge|profile|thumb|social|share|related|recommend|placeholder|spinner|loading|blank|pixel)/i;
  var BAD_DIR = /(thumb|avatar|icon|banner|sprite|emoji|profile|social|related|recommend)/i;
  var PAGE_HINT = /(page|chapter|manga|comic|panel|scan|raw|webtoon)/i;
  var IMG_EXT = /\.(png|jpe?g|webp|avif)(?:[?#][^\s"'<>\\]*)?$/i;

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
    if (!u || !IMG_EXT.test(u)) return;
    if (BAD.test(u) || BAD.test(alt || '')) return;
    var existing = candidates[u];
    if (!existing) {
      candidates[u] = {
        url: u,
        w: width || 0,
        h: height || 0,
        alt: alt || '',
        order: orderSeq++,
        dom: !!fromDom
      };
    } else {
      if (width > existing.w) existing.w = width;
      if (height > existing.h) existing.h = height;
      if (fromDom) existing.dom = true;
      if (alt) existing.alt = alt;
    }
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

  function isPageShape(w, h, url, alt) {
    if (w >= 150 && h >= 250) {
      return h > w * 1.05 || PAGE_HINT.test(url + ' ' + (alt || ''));
    }
    // Lazy images may not have decoded dimensions yet. Keep them when the URL or alt says
    // "page", or when they are wide/tall enough to plausibly be a scan.
    return (PAGE_HINT.test(url + ' ' + (alt || '')) || (w >= 400 && h >= 400));
  }

  function collectDom() {
    var imgs = document.querySelectorAll('img');
    for (var i = 0; i < imgs.length; i++) {
      var img = imgs[i];
      var src = img.currentSrc || img.src || '';
      if (/^data:/i.test(src) || /(spacer|blank|pixel|loading)[.\-_]/i.test(src)) src = '';
      var srcset = bestSrcset(img.getAttribute('srcset'));
      var url = img.getAttribute('data-original') ||
        img.getAttribute('data-src') ||
        img.getAttribute('data-lazy-src') ||
        img.getAttribute('data-url') ||
        srcset || src;
      var w = img.naturalWidth || img.width || 0;
      var h = img.naturalHeight || img.height || 0;
      var alt = img.getAttribute('alt') || '';
      if (url && isPageShape(w, h, url, alt)) add(url, w, h, alt, true);

      var sources = img.querySelectorAll ? img.querySelectorAll('source') : [];
      for (var s = 0; s < sources.length; s++) {
        var su = bestSrcset(sources[s].getAttribute('srcset')) || sources[s].getAttribute('src');
        if (su && isPageShape(w, h, su, alt)) add(su, w, h, alt, true);
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
      var m = backgrounds[b].style.backgroundImage.match(/url\(["']?([^"')]+)["']?\)/);
      if (m && IMG_EXT.test(m[1])) add(m[1], 0, 0, '', true);
    }

    var preloads = document.querySelectorAll('link[rel="preload"][as="image"]');
    for (var l = 0; l < preloads.length; l++) {
      var href = preloads[l].getAttribute('href');
      if (href && IMG_EXT.test(href)) add(href, 0, 0, '', true);
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
      }
    }

    var domPages = all.filter(function (c) {
      return c.dom && isPageShape(c.w, c.h, c.url, c.alt);
    });
    var chosen;
    if (bestGroup && bestGroup.length >= 3) {
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

    if (pageList.length >= 3) {
      // A cover/credits image without a number sometimes sits before page 1. Keep unnumbered
      // DOM images that were collected before the first numbered page, drop the rest.
      var firstOrder = pageList.length ? pageList[0].order : Infinity;
      var before = unnumbered.filter(function (c) {
        return c.dom && c.order < firstOrder && isPageShape(c.w, c.h, c.url, c.alt);
      });
      before.sort(function (a, b) { return a.order - b.order; });
      chosen = before.concat(pageList);
    } else {
      chosen = chosen.sort(function (a, b) {
        var na = pageNumber(a.url);
        var nb = pageNumber(b.url);
        if (na > 0 && nb > 0 && na !== nb) return na - nb;
        return a.order - b.order;
      });
    }

    var urls = [];
    var seen = {};
    for (var x = 0; x < chosen.length; x++) {
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
      scanMs: Date.now() - started
    };
    window.__nsMangaProgress = 'done';
    window.__nsMangaDone = true;
    window.__nsMangaRunning = false;
  }

  function step() {
    collectDom();
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
        collectScripts();
        window.scrollTo(0, 0);
        setTimeout(function () {
          collectDom();
          finish();
        }, 350);
      }, 500);
    }
  }

  // Collect what is already rendered (often the first two pages), then walk the chapter so
  // lazy-loaded page tags exist, then finish with the framework payload.
  collectDom();
  setTimeout(step, 250);
})();
