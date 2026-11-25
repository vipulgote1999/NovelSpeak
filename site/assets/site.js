// NovelSpeak — site/assets/site.js
// Progressive enhancement only: every page reads and every link works without this file.
// Configuration lives here and nowhere else (see docs/website/design-contract.md).

var SITE = {
  // Set this to the published listing URL when the Play listing goes live. Empty = status text.
  playUrl: '',

  // The newest signed APK that actually exists as a release asset. Bump with the release.
  release: {
    version: '1.3.0',
    tag: 'v1.3.0',
    file: 'NovelSpeak-1.3.0-arm64-v8a.apk',
    base: 'https://github.com/vipulgote1999/NovelSpeak/releases/download/v1.3.0/'
  }
};

(function () {
  'use strict';

  var doc = document;
  doc.documentElement.classList.add('js');
  var reduce = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : { matches: false };

  // --- Play status: text today, a real link only when a URL is configured ---------------
  var statuses = doc.querySelectorAll('[data-play-status]');
  for (var i = 0; i < statuses.length; i++) {
    var el = statuses[i];
    if (SITE.playUrl) {
      var a = doc.createElement('a');
      a.href = SITE.playUrl;
      a.rel = 'noreferrer';
      a.textContent = 'Google Play';
      el.textContent = '';
      el.appendChild(a);
      el.appendChild(doc.createTextNode(' \u2014 available'));
    } else {
      el.textContent = 'Google Play \u2014 coming';
    }
  }

  // --- APK links: one source of truth, upgraded in place --------------------------------
  var apks = doc.querySelectorAll('[data-apk]');
  for (var j = 0; j < apks.length; j++) {
    apks[j].href = SITE.release.base + SITE.release.file;
  }
  var shas = doc.querySelectorAll('[data-apk-sha]');
  for (var k = 0; k < shas.length; k++) {
    shas[k].href = SITE.release.base + SITE.release.file + '.sha256';
  }

  // --- Film: poster first, nothing fetched until a real press ---------------------------
  var films = doc.querySelectorAll('[data-film]');
  Array.prototype.forEach.call(films, function (film) {
    var video = film.querySelector('video');
    var button = film.querySelector('.film__play');
    if (!video || !button) return;
    button.addEventListener('click', function () {
      var playing = video.play();
      if (playing && playing.catch) playing.catch(function () { video.controls = true; });
    });
    video.addEventListener('play', function () { button.hidden = true; });
    video.addEventListener('pause', function () { button.hidden = false; });
    video.addEventListener('ended', function () { button.hidden = false; });
  });

  // --- Read-along: the page's one type-led moment, once, and only with motion allowed ---
  var readalong = doc.querySelector('[data-readalong]');
  if (readalong && !reduce.matches && 'IntersectionObserver' in window) {
    // Each span holds a whole word, so reading order, spelling and pronunciation are intact.
    // No aria-label on the paragraph: a <p> has no usable accessible name in ARIA 1.2.
    var words = readalong.textContent.replace(/\s+/g, ' ').trim().split(' ');
    readalong.textContent = '';
    words.forEach(function (word, index) {
      var span = doc.createElement('span');
      span.textContent = word;
      span.style.setProperty('--i', String(index));
      readalong.appendChild(span);
      if (index < words.length - 1) readalong.appendChild(doc.createTextNode(' '));
    });
    var readObserver = new IntersectionObserver(function (entries) {
      if (entries[0].isIntersecting) {
        readalong.classList.add('is-lit');
        readObserver.disconnect();
      }
    }, { threshold: 0.35 });
    readObserver.observe(readalong);
  }

  // --- Waveform: three cycles on first sight, paused while off-screen ------------------
  var waves = doc.querySelectorAll('.wave');
  Array.prototype.forEach.call(waves, function (wave) {
    if (!('IntersectionObserver' in window)) return;
    var seen = false;
    var observer = new IntersectionObserver(function (entries) {
      var entry = entries[0];
      if (entry.isIntersecting && !seen) {
        seen = true;
        wave.classList.add('is-live');
      }
      wave.classList.toggle('is-paused', !entry.isIntersecting);
    }, { threshold: 0.5 });
    observer.observe(wave);
  });

  // --- Nav disclosure: close on outside press and on Escape ----------------------------
  var menus = doc.querySelectorAll('details.more');
  Array.prototype.forEach.call(menus, function (menu) {
    doc.addEventListener('click', function (event) {
      if (menu.open && !menu.contains(event.target)) menu.open = false;
    });
    menu.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && menu.open) {
        menu.open = false;
        var summary = menu.querySelector('summary');
        if (summary) summary.focus();
      }
    });
    var links = menu.querySelectorAll('a');
    Array.prototype.forEach.call(links, function (link) {
      link.addEventListener('click', function () { menu.open = false; });
    });
  });
})();
