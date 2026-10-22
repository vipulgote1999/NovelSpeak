/*
 * NovelSpeak ad-blocking content runtime.
 *
 * The native engine decides what to hide; this script only applies selectors to the live DOM and
 * asks the native side for generic rules that match the classes/ids actually present on the page.
 * That "ask for the classes on the page" step is the same optimization Brave's cosmetic cache uses:
 * injecting every generic selector up front is far too expensive for a phone.
 */
(function () {
    'use strict';

    var NS = window.__nsAdblock = window.__nsAdblock || {};
    if (NS.__v === 1) return;
    NS.__v = 1;

    NS.added = NS.added || {};
    NS.seen = NS.seen || {};
    NS.pendingC = NS.pendingC || {};
    NS.pendingI = NS.pendingI || {};
    NS.procedural = NS.procedural || [];
    NS.style = NS.style || null;
    NS.sheet = NS.sheet || null;
    NS.observer = NS.observer || null;
    NS.flushTimer = NS.flushTimer || null;
    NS.procTimer = NS.procTimer || null;
    NS.genericOff = NS.genericOff || false;
    NS.elemhide = NS.elemhide || false;
    NS.hidden = NS.hidden || 0;
    NS.openOverridden = NS.openOverridden || false;
    NS.overlaySeen = NS.overlaySeen || (typeof WeakSet !== 'undefined' ? new WeakSet() : null);
    NS.overlayTimer = NS.overlayTimer || null;
    NS.overlayLast = NS.overlayLast || 0;
    NS.nagSeen = NS.nagSeen || (typeof WeakSet !== 'undefined' ? new WeakSet() : null);
    NS.nagTimer = NS.nagTimer || null;
    NS.nagLast = NS.nagLast || 0;

    var AD_TEXT = /(sponsor|advert|promo|bonus|welcome|cash|casino|betting|\bbet\b|offer|prize|reward|download now|install now)/i;
    var NAG_TEXT = /(chrome mobile webview|notification system|allow notifications|enable notifications|push notifications|subscribe to notifications|click ["']?ok["']? and then ["']?allow)/i;
    var SECURITY_TEXT = /(cloudflare|checking the security|i'm not a robot|verify you are human|turnstile|security service)/i;

    function ensureStyle() {
        if (NS.style && NS.style.parentNode) return;
        try {
            var parent = document.head || document.documentElement;
            if (!parent) return;
            NS.style = document.createElement('style');
            NS.style.type = 'text/css';
            NS.style.id = '__nsAdblockStyle';
            parent.appendChild(NS.style);
            NS.sheet = NS.style.sheet;
        } catch (e) {
            NS.style = null;
            NS.sheet = null;
        }
    }

    function count(n) {
        NS.hidden += n;
        try {
            if (window.NSAdBlock && window.NSAdBlock.hidden) window.NSAdBlock.hidden(n);
        } catch (e) {}
    }

    function hideDirect(list) {
        var applied = 0;
        for (var k = 0; k < list.length && applied < 600; k++) {
            try {
                var nodes = document.querySelectorAll(list[k]);
                for (var n = 0; n < nodes.length; n++) {
                    nodes[n].style.setProperty('display', 'none', 'important');
                    count(1);
                }
                applied++;
            } catch (e) {}
        }
    }

    function addCssList(list) {
        if (!list || !list.length) return;
        var fresh = [];
        for (var i = 0; i < list.length; i++) {
            var s = list[i];
            if (!s || NS.added[s]) continue;
            NS.added[s] = 1;
            fresh.push(s);
        }
        if (!fresh.length) return;

        ensureStyle();
        if (NS.sheet) {
            // Batch into groups; one invalid selector must not discard the whole batch.
            for (var start = 0; start < fresh.length; start += 120) {
                var end = Math.min(start + 120, fresh.length);
                var group = fresh.slice(start, end);
                try {
                    NS.sheet.insertRule(group.join(',') + '{display:none!important}', NS.sheet.cssRules.length);
                    count(group.length);
                    continue;
                } catch (e) {
                    // fall through to per-selector insertion
                }
                var inserted = 0;
                for (var j = 0; j < group.length; j++) {
                    try {
                        NS.sheet.insertRule(group[j] + '{display:none!important}', NS.sheet.cssRules.length);
                        inserted++;
                        count(1);
                    } catch (e2) {}
                }
                // CSP can make the stylesheet inert even though the element exists; fall back
                // to setting inline styles so the ads are still hidden.
                if (inserted === 0) hideDirect(group);
            }
            return;
        }

        hideDirect(fresh);
    }

    function findClose(s, open) {
        var depth = 0;
        var quote = '';
        for (var i = open; i < s.length; i++) {
            var c = s.charAt(i);
            if (quote) {
                if (c === quote && s.charAt(i - 1) !== '\\') quote = '';
                continue;
            }
            if (c === '"' || c === "'") {
                quote = c;
            } else if (c === '(') {
                depth++;
            } else if (c === ')') {
                depth--;
                if (depth === 0) return i;
            }
        }
        return -1;
    }

    function firstOperator(s) {
        var names = [':has-text(', ':matches-css(', ':upward(', ':xpath('];
        var best = -1;
        for (var i = 0; i < names.length; i++) {
            var p = s.indexOf(names[i]);
            if (p >= 0 && (best < 0 || p < best)) best = p;
        }
        return best;
    }

    function parseOps(s) {
        var ops = [];
        var names = [':has-text(', ':matches-css(', ':upward(', ':xpath('];
        var i = 0;
        while (i < s.length) {
            var found = -1;
            var name = null;
            for (var k = 0; k < names.length; k++) {
                var p = s.indexOf(names[k], i);
                if (p >= 0 && (found < 0 || p < found)) {
                    found = p;
                    name = names[k];
                }
            }
            if (found < 0) break;
            var open = found + name.length - 1;
            var close = findClose(s, open);
            if (close < 0) break;
            ops.push({n: name.substring(1, name.length - 1), a: s.substring(open + 1, close)});
            i = close + 1;
        }
        return ops;
    }

    function baseSelector(s) {
        var idx = firstOperator(s);
        return idx < 0 ? s : s.substring(0, idx);
    }

    function unique(list) {
        var out = [];
        var seen = {};
        for (var i = 0; i < list.length; i++) {
            var el = list[i];
            if (!el || seen[el]) continue;
            // MutationObserver exposes Node, not Element, via addedNodes.parentNode.
            if (el.nodeType && el.nodeType !== 1) continue;
            seen[el] = 1;
            out.push(el);
        }
        return out;
    }

    function selectBase(base) {
        if (!base || base === '*') {
            try { return Array.prototype.slice.call(document.querySelectorAll('*')); } catch (e) { return []; }
        }
        try { return Array.prototype.slice.call(document.querySelectorAll(base)); } catch (e) { return []; }
    }

    function applyOp(elements, op) {
        var out = [];
        var i;
        if (op.n === 'has-text') {
            var needle = op.a.replace(/^["']|["']$/g, '').toLowerCase();
            for (i = 0; i < elements.length; i++) {
                var text = (elements[i].textContent || '').toLowerCase();
                if (text.indexOf(needle) >= 0) out.push(elements[i]);
            }
            return out;
        }
        if (op.n === 'matches-css') {
            var colon = op.a.indexOf(':');
            if (colon < 0) return elements;
            var prop = op.a.substring(0, colon).trim();
            var want = op.a.substring(colon + 1).trim();
            var re = null;
            if (want.length > 2 && want.charAt(0) === '/' && want.charAt(want.length - 1) === '/') {
                try { re = new RegExp(want.substring(1, want.length - 1)); } catch (e) { re = null; }
            }
            for (i = 0; i < elements.length; i++) {
                try {
                    var got = window.getComputedStyle(elements[i])[prop];
                    if (re ? re.test(got) : String(got).indexOf(want) >= 0) out.push(elements[i]);
                } catch (e2) {}
            }
            return out;
        }
        if (op.n === 'upward') {
            var arg = op.a.trim();
            var n = parseInt(arg, 10);
            for (i = 0; i < elements.length; i++) {
                var el = elements[i];
                if (!isNaN(n) && /^[0-9]+$/.test(arg)) {
                    for (var k = 0; k < n && el; k++) el = el.parentElement;
                    if (el) out.push(el);
                } else {
                    try {
                        var closest = el.closest ? el.closest(arg) : null;
                        if (closest) out.push(closest);
                    } catch (e3) {}
                }
            }
            return unique(out);
        }
        if (op.n === 'xpath') {
            try {
                var snap = document.evaluate(op.a, document, null, 7, null);
                var nodes = [];
                for (var m = 0; m < snap.snapshotLength; m++) nodes.push(snap.snapshotItem(m));
                if (!elements.length) return unique(nodes);
                var set = {};
                for (var q = 0; q < elements.length; q++) set[elements[q]] = 1;
                for (var r = 0; r < nodes.length; r++) if (set[nodes[r]]) out.push(nodes[r]);
                return out;
            } catch (e4) {
                return [];
            }
        }
        return elements;
    }

    function applyOneProcedural(rule) {
        if (!rule || !rule.s) return;
        var base = baseSelector(rule.s);
        var elements = selectBase(base);
        if (!elements.length) return;
        var ops = parseOps(rule.s);
        for (var i = 0; i < ops.length; i++) {
            elements = applyOp(elements, ops[i]);
            if (!elements.length) return;
        }
        var action = rule.a || 0;
        for (var j = 0; j < elements.length && j < 2000; j++) {
            var el = elements[j];
            try {
                if (action === 1) {
                    if (el.parentNode) el.parentNode.removeChild(el);
                    count(1);
                } else if (action === 2) {
                    el.style.cssText += ';' + (rule.v || '');
                    count(1);
                } else if (action === 3) {
                    el.removeAttribute(rule.v || '');
                    count(1);
                } else if (action === 4) {
                    el.classList.remove(rule.v || '');
                    count(1);
                } else {
                    el.style.setProperty('display', 'none', 'important');
                    count(1);
                }
            } catch (e) {}
        }
    }

    function applyProcedural() {
        NS.procTimer = null;
        if (NS.elemhide || !NS.procedural.length) return;
        for (var i = 0; i < NS.procedural.length; i++) applyOneProcedural(NS.procedural[i]);
    }

    function scheduleProcedural() {
        if (NS.procTimer) return;
        NS.procTimer = setTimeout(applyProcedural, 450);
    }

    function hideObtrusiveOverlays() {
        NS.overlayTimer = null;
        if (NS.elemhide) return;
        var now = Date.now();
        if (now - NS.overlayLast < 1200) {
            scheduleOverlayCheck();
            return;
        }
        NS.overlayLast = now;
        var vw = window.innerWidth || 0;
        var vh = window.innerHeight || 0;
        if (vw < 200 || vh < 200) return;
        var all;
        try {
            all = document.querySelectorAll('body *');
        } catch (e) {
            return;
        }
        var limit = Math.min(all.length, 4500);
        for (var i = 0; i < limit; i++) {
            var el = all[i];
            if (!el) continue;
            // Only remember elements we actually hid. An overlay is often inserted early and
            // styled later, so caching "seen but not hidden" misses the click-layer case.
            if (NS.overlaySeen && NS.overlaySeen.has && NS.overlaySeen.has(el)) continue;
            if (el.style && el.style.display === 'none') continue;

            var r;
            try { r = el.getBoundingClientRect(); } catch (e1) { continue; }
            if (r.width < vw * 0.35 || r.height < vh * 0.25) continue;
            if (r.bottom < 0 || r.top > vh || r.right < 0 || r.left > vw) continue;

            var text = '';
            try { text = (el.textContent || '').slice(0, 400); } catch (e3) {}
            if (SECURITY_TEXT.test(text)) continue;

            var src = '';
            try {
                src = (el.src || '') + ' ' +
                    (el.getAttribute ? (el.getAttribute('src') || '') : '');
            } catch (e8) {}
            if (/cloudflare|turnstile|challenges\.cloudflare/i.test(src)) continue;

            var idc = '';
            try {
                idc = ((el.id || '') + ' ' + (typeof el.className === 'string' ? el.className : '')).toLowerCase();
            } catch (e9) {}
            if (idc.indexOf('cloudflare') >= 0 || idc.indexOf('turnstile') >= 0 || idc.indexOf('challenge') >= 0) continue;

            var cs;
            try { cs = window.getComputedStyle(el); } catch (e2) { continue; }
            if (!cs || (cs.position !== 'fixed' && cs.position !== 'absolute')) continue;
            var z = parseInt(cs.zIndex, 10);
            if (!(z >= 1000)) continue;
            if (el === document.body || el.contains(document.body)) continue;

            var hasFrame = false;
            try { hasFrame = !!el.querySelector('iframe'); } catch (e4) {}
            var isFrame = el.tagName && el.tagName.toUpperCase() === 'IFRAME';
            var bg = '';
            try { bg = (cs.backgroundColor || '').toLowerCase(); } catch (e6) {}
            var opacity = 1;
            try { opacity = parseFloat(cs.opacity || '1'); } catch (e7) {}
            var transparent = bg === 'transparent' || bg === 'rgba(0, 0, 0, 0)' ||
                bg === 'rgba(0,0,0,0)' || opacity < 0.05;
            var fullScreen = r.width >= vw * 0.7 && r.height >= vh * 0.7;
            if (!hasFrame && !isFrame && !AD_TEXT.test(text) && !(transparent && fullScreen)) continue;

            try {
                el.style.setProperty('display', 'none', 'important');
                if (NS.overlaySeen && NS.overlaySeen.add) NS.overlaySeen.add(el);
                count(1);
            } catch (e5) {}
        }
    }

    function scheduleOverlayCheck() {
        if (NS.overlayTimer) return;
        NS.overlayTimer = setTimeout(hideObtrusiveOverlays, 450);
    }

    function hideNotificationNags() {
        NS.nagTimer = null;
        if (NS.elemhide) return;
        var now = Date.now();
        if (now - NS.nagLast < 1000) {
            scheduleNagCheck();
            return;
        }
        NS.nagLast = now;
        var all;
        try {
            all = document.querySelectorAll('body *');
        } catch (e) {
            return;
        }
        var limit = Math.min(all.length, 5000);
        for (var i = 0; i < limit; i++) {
            var el = all[i];
            if (!el) continue;
            // Only cache elements we actually hid; the modal's text is often injected after
            // the container element exists, so caching "checked but not hidden" misses it.
            if (NS.nagSeen && NS.nagSeen.has && NS.nagSeen.has(el)) continue;
            if (el.style && el.style.display === 'none') continue;
            var text = '';
            try { text = (el.textContent || ''); } catch (e1) { continue; }
            if (text.length > 800) continue;
            if (SECURITY_TEXT.test(text)) continue;
            if (!NAG_TEXT.test(text)) continue;

            // Hide the closest positioned ancestor, which is usually the modal shell.
            var target = el;
            var p = el;
            for (var k = 0; k < 4 && p; k++) {
                var cs;
                try { cs = getComputedStyle(p); } catch (e2) { break; }
                if (cs && (cs.position === 'fixed' || cs.position === 'absolute')) {
                    target = p;
                    break;
                }
                p = p.parentElement;
            }
            try {
                target.style.setProperty('display', 'none', 'important');
                if (NS.nagSeen && NS.nagSeen.add) NS.nagSeen.add(el);
                count(1);
            } catch (e3) {}
        }
        hideIframeNags();
    }

    function scheduleNagCheck() {
        if (NS.nagTimer) return;
        NS.nagTimer = setTimeout(hideNotificationNags, 500);
    }

    /**
     * The fake privacy/notification modal is sometimes rendered inside a same-origin
     * about:srcdoc iframe. Cross-origin frames (real Cloudflare Turnstile) throw on
     * contentDocument and are left alone; same-origin ad frames are hidden.
     */
    function hideIframeNags() {
        var frames;
        try { frames = document.querySelectorAll('iframe'); } catch (e) { return; }
        for (var i = 0; i < frames.length; i++) {
            var f = frames[i];
            var doc = null;
            try { doc = f.contentDocument; } catch (e) { continue; }
            if (!doc) continue;
            var text = '';
            try {
                text = doc.body ? (doc.body.innerText || doc.body.textContent || '') : '';
            } catch (e) { continue; }
            if (!text || !NAG_TEXT.test(text)) continue;

            var target = f;
            var p = f;
            for (var k = 0; k < 5 && p; k++) {
                var cs;
                try { cs = getComputedStyle(p); } catch (e) { break; }
                if (cs && (cs.position === 'fixed' || cs.position === 'absolute')) {
                    target = p;
                    break;
                }
                p = p.parentElement;
            }
            try {
                target.style.setProperty('display', 'none', 'important');
                count(1);
            } catch (e) {}
        }
    }

    function addElement(el) {
        try {
            if (el.id && !NS.seen['i:' + el.id]) NS.pendingI[el.id] = 1;
            var cl = el.classList;
            if (cl) {
                for (var j = 0; j < cl.length; j++) {
                    var c = cl[j];
                    if (c && !NS.seen['c:' + c]) NS.pendingC[c] = 1;
                }
            }
        } catch (e) {}
    }

    function collect(root) {
        if (!root || root.nodeType !== 1) return;
        addElement(root);
        var nodes;
        try { nodes = root.querySelectorAll('*'); } catch (e) { return; }
        var limit = Math.min(nodes.length, 4000);
        for (var i = 0; i < limit; i++) addElement(nodes[i]);
        // Bound the memory of the seen/pending maps on very long-lived pages.
        var n = 0;
        for (var k in NS.seen) { n++; if (n > 6000) { NS.seen = {}; break; } }
    }

    function flush() {
        NS.flushTimer = null;
        if (NS.genericOff) return;
        var classes = [];
        var ids = [];
        var k;
        for (k in NS.pendingC) {
            classes.push(k);
            NS.seen['c:' + k] = 1;
            if (classes.length >= 250) break;
        }
        for (k in NS.pendingI) {
            ids.push(k);
            NS.seen['i:' + k] = 1;
            if (ids.length >= 150) break;
        }
        NS.pendingC = {};
        NS.pendingI = {};
        if (!classes.length && !ids.length) return;
        try {
            var bridge = window.NSAdBlock;
            if (!bridge || !bridge.lookup) return;
            var raw = bridge.lookup(JSON.stringify({c: classes, i: ids}));
            var list = JSON.parse(raw);
            if (list && list.length) addCssList(list);
        } catch (e) {}
    }

    function scheduleFlush() {
        if (NS.flushTimer) return;
        NS.flushTimer = setTimeout(flush, 120);
    }

    function observe() {
        if (NS.observer || !window.MutationObserver) return;
        try {
            NS.observer = new MutationObserver(function (mutations) {
                for (var i = 0; i < mutations.length; i++) {
                    var added = mutations[i].addedNodes;
                    if (!added) continue;
                    for (var j = 0; j < added.length; j++) {
                        var node = added[j];
                        if (node && node.nodeType === 1) collect(node);
                    }
                }
                scheduleFlush();
                if (NS.procedural.length) scheduleProcedural();
                scheduleOverlayCheck();
                scheduleNagCheck();
            });
            NS.observer.observe(document.documentElement || document, {childList: true, subtree: true});
        } catch (e) {}
    }

    function overrideWindowOpen() {
        if (NS.openOverridden) return;
        NS.openOverridden = true;
        try {
            window.open = function () { return null; };
        } catch (e) {}
        try {
            // Links with target=_blank are another common popunder path; the native side also
            // disables multiple windows, this is belt and braces.
            document.addEventListener('click', function (ev) {
                var a = ev.target && ev.target.closest ? ev.target.closest('a[target="_blank"]') : null;
                if (a) a.removeAttribute('target');
            }, true);
        } catch (e2) {}
    }

    window.__nsAdblockInit = function (config) {
        try {
            if (!config) return;
            NS.genericOff = !!config.generichide;
            NS.elemhide = !!config.elemhide;
            if (config.popups) overrideWindowOpen();
            if (!NS.elemhide) {
                addCssList(config.hide || []);
                NS.procedural = config.procedural || [];
                applyProcedural();
                scheduleOverlayCheck();
                scheduleNagCheck();
                // Some ad layers are inserted early and only styled after the first paint.
                setTimeout(hideObtrusiveOverlays, 1800);
                setTimeout(hideNotificationNags, 1800);
            }
            observe();
            collect(document.documentElement || document);
            scheduleFlush();
        } catch (e) {}
    };
})();
