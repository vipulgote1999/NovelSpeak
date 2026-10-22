# Ad blocking in NovelSpeak

NovelSpeak is a WebView browser, so its ad blocker cannot be a copy of a Chromium
network stack or an extension. It is built from the same pieces a shipped WebView
browser uses, chosen after inspecting two mature implementations:

* **Brave `adblock-rust`** — for the rule index and cosmetic-filter design.
* **DuckDuckGo Privacy Browser for Android** — for the WebView integration,
  breakage handling, and surrogate scripts.

The implementation is pure Kotlin and has no native dependency. Lists are bundled
in `app/assets/adblock/` and can be updated from Settings.

## What the WebView platform allows

Verified against `WebViewClient`:

| Capability | Reality |
|---|---|
| See subresource requests | Yes, `shouldInterceptRequest` on a background thread |
| See main-frame requests | Yes, request exposes `isForMainFrame` |
| Block a request | Return any `WebResourceResponse` |
| See resource type / MIME | **No** — inferred from `Accept` + URL extension |
| See redirect targets | **No** — only the initial URL is reported |
| See `blob:` / `javascript:` | **No** |
| Read/modify response bodies | **No** (only by substituting a whole response) |
| Inject CSS/JS | Yes, `evaluateJavascript` / `addJavascriptInterface` |
| Use extension APIs (uBO) | **No** |

The design therefore has four layers:

1. **Network blocking** — `shouldInterceptRequest` matches the URL against
   EasyList/EasyPrivacy/uBO rules and returns an empty response.
2. **Cosmetic filtering** — a content script applies site-specific selectors and
   asks native code for generic selectors that match the classes/ids actually on
   the page.
3. **Behavioral filtering** — a small scriptlet library neutralises popup and
   anti-adblock patterns; blocked tracker scripts get a surrogate stub so the
   page's own code keeps working.
4. **List lifecycle** — lists are bundled, parsed in the background at startup,
   and can be refreshed over HTTPS from Settings.

## Network engine

### Rule index (from adblock-rust)

Scanning ~123k rules for every subresource would be fatal on a phone. The engine
uses the indexing strategy from `adblock-rust/src/network_filter_list.rs` and
`src/filters/token_selector.rs`:

* every non-host rule is filed under **one rare token** extracted from its pattern;
* requests are tokenised, and only the buckets for tokens in the URL are checked;
* `||host^` rules use a **hostname bucket** and never run a regex;
* rules whose pattern has no safe token go to a small fallback bucket;
* regex rules use a **3-character prefix bucket** (`/banner\d+\.gif/` must still be
  found from the URL token `banner12`).

Token selection is frequency-aware: the build counts token usage across all rules
and picks the least-used token per rule, with `http`, `www`, `com`, `js`, `ad`
and similar common tokens heavily penalised. Measured on the bundled pack:

```
network rules   122,884
cosmetic rules   32,403
scriptlet rules   4,612
host buckets    111,922
token buckets     3,449
regex buckets        43
fallback            151
parse + index    ~380 ms (JVM harness; a phone is a few seconds at worst)
```

### Matching precedence (from adblock-rust)

1. `$important` blocks unconditionally (no exception applies).
2. A normal block is cancelled by any `@@` exception.
3. `$badfilter` removes its counterpart rule at build time.
4. `$third-party` / `$first-party` use eTLD+1 from the bundled Public Suffix List.
5. `$domain=` includes/excludes by document host suffix.
6. `$script`, `$image`, `$stylesheet`, `$xhr`, `$subdocument`, `$font`, `$media`,
   `$websocket`, `$ping`, `$object` filter by the inferred type.
7. `$document` exceptions put the whole site on the allowlist; `$document` block
   rules never blank a reader page.
8. `$generichide` and `$elemhide` only affect cosmetic filtering.

Unsupported options (`$csp`, `$removeparam`, `$replace`, `$stealth`, `$denyallow`,
`$specifichide`, …) cause the rule to be dropped rather than approximated. This is
deliberate: a missed block is recoverable, a broken chapter page is not.

## Cosmetic filtering (from adblock-rust)

Generic cosmetic rules are the expensive part: injecting all of them on every page
would add megabytes of CSS. Brave's `CosmeticFilterCache` instead indexes generic
rules by their first class/id and lets the page ask for the rules that could
possibly apply:

* host-specific rules are precomputed per site and injected on `onPageCommitVisible`;
* a `MutationObserver` collects new class/id names;
* the content script calls the `NSAdBlock` bridge, which returns matching
  generic selectors;
* selectors are inserted in batches of 120; a bad selector falls back to
  per-selector insertion and then to direct `style.display = 'none'`;
* procedural filters (`:has-text`, `:matches-css`, `:upward`, `:xpath`) and actions
  (`:remove`, `:style`, `:remove-attr`, `:remove-class`) run in-page;
* `$elemhide` / `$generichide` exceptions disable the corresponding layer;
* a conservative overlay heuristic removes large fixed/absolute elements
  (z-index ≥ 1000, covering ≥35% × 25% of the viewport) when they contain an
  ad-looking phrase or a nested iframe. This catches interstitial/popunder
  creatives that have no matching list rule; the normal page header in the test
  page is left alone, and the whole layer can be turned off in Settings.

## Breakage handling (from DuckDuckGo)

`WebViewRequestInterceptor` in DuckDuckGo established the useful invariants:

* `shouldInterceptRequest` returns `null` to allow and a response to block;
* blocked tracker scripts are replaced by **surrogates** (small stubs) rather than
  an empty body, so globals such as `ga`, `googletag` and `fbq` still exist;
* an allowlist (user site, tracker allowlist, content-blocking exception) wins over
  a block;
* blocking a tracker from the **same entity** as the document is skipped;
* main-frame navigations are handled separately (`shouldOverrideUrlLoading`).

NovelSpeak implements those rules in `AdBlocker.kt` and
`app/src/app/novelspeak/adblock/`:

* the **Ad** button in the URL bar allowlists the current registrable domain and
  reloads the page;
* popup/redirect blocking only fires for non-gesture main-frame navigations, and
  only when a `$popup` rule matches, so a reader's own clicks and legitimate
  redirects still work;
* `intent:`, `market:` and `app:` schemes are blocked because ad redirects use them
  to jump to an app store;
* `setSupportMultipleWindows(false)` and a `WebChromeClient.onCreateWindow` that
  returns `false` close the other popup path;
* main-frame **ad-landing** jumps are blocked even when a click-layer makes them
  look gesture-driven. A third-party navigation to a cheap/suspicious TLD
  (`.top`, `.cfd`, `.xyz`, …) or an ad-shaped URL (`clickid=`, `aff=`, `redirect=`)
  is refused with a toast, so the reader stays on the chapter. This is what stops
  new domains that are not yet in any list, e.g. `yourbestplace.top` and
  `ho.beefycasease.cfd`;
* invisible full-screen click-layers (fixed/absolute, high z-index, transparent)
  are hidden, and fake “Chrome Mobile WebView Privacy” / notification-permission
  nags are removed. This includes nags rendered inside same-origin
  `about:srcdoc` iframes (the main-frame text scan cannot see those; the iframe
  body is checked directly, while cross-origin real Cloudflare frames cannot be
  read and are left alone). Text that merely says “Cloudflare” is not trusted: a
  fake challenge can use the same wording, so the layer's structure is what gets
  hidden;
* JS `alert`/`confirm`/`prompt` dialogs whose text matches a notification,
  push-permission or anti-adblock nag are suppressed instead of shown.

## Lists

| File | Source | Contents |
|---|---|---|
| `easylist.txt` | easylist.to | ads |
| `easyprivacy.txt` | easylist.to | trackers |
| `ublock-filters.txt` | uBlockOrigin/uAssets | ads + trackers |
| `ublock-annoyances.txt` | uBlockOrigin/uAssets (`annoyances-others.txt`) | overlays/popups |
| `ublock-badware.txt` | uBlockOrigin/uAssets | malware |

Licences and attribution are in `app/assets/adblock/NOTICE.txt`.

Settings → Ad blocking → **Update lists** downloads fresh copies to
`filesDir/adblock/`. The loader prefers those files over the bundled assets; a
failed download leaves the bundled lists untouched. There is no signature check
because filter lists are not signed; downloads use HTTPS and are size/format
checked.

## Performance and limits

* Cold load parses ~168k lines and builds the index; the engine is swapped in
  atomically, and requests pass through until it is ready. If a page finishes
  before the index, the engine-ready callback re-injects the cosmetic layer
  instead of waiting for a reload. On the API 35 emulator the log line was
  `adblock ready network=122884 cosmetic=32403 scriptlets=4612 build=114ms`, and
  the engine was ready under a second after process start.
* On the same emulator, the chapter page reported `Ad 0/129` (129 cosmetic
  selectors applied) and the large interstitial overlay was removed; the in-app
  self-test reported `22/22 passed`.
* On a physical iqoo I2214 (Android 15), the live home page reported `Ad 3/127`
  (3 network requests blocked, 127 cosmetic selectors applied) and the in-app
  self-test passed 22/22. On the reported problem chapter
  (`/novel/my-werewolf-system/67`) the final build rendered the chapter cleanly
  with `Ad 0/128`; the fake Cloudflare/notification click-layer (including its
  same-origin `srcdoc` iframe nag) was gone.
  Logcat showed the redirect guard refusing `yourbestplace.top`
  (`gesture=false suspicious=true`) and `ho.beefycasease.cfd`
  (`gesture=true suspicious=true`), with the WebView staying on the chapter.
* The debuggable test build was inspected over Chrome DevTools Protocol. The
  exact fake “Chrome Mobile WebView Privacy” modal, a transparent full-screen
  click-layer, and a Cloudflare-styled decoy were injected into the live page:
  the first two became `display: none`, the Cloudflare-like decoy was preserved.
  The same inspection showed `window.__nsAdblock` present and `hidden=125` after
  the engine-ready re-injection, fixing the startup race.
* `shouldInterceptRequest` runs on a WebView background thread; the engine is
  immutable after build, so no locking is needed on the hot path.
* Stats notifications are throttled to 120 ms so an ad-heavy page cannot flood the
  UI thread with updates.
* The bridge accepts at most 32 KB per call and 25 calls/second, and validates
  names, so hostile page JavaScript cannot use it as a CPU amplifier.
* No response-body filtering, no redirect-target blocking, no `$csp`,
  `$removeparam`, or trusted scriptlets. Those need a proxy/MITM or a Chromium
  fork, not a WebView client.

## Verification

* `AdBlockSelfTest` (Settings → Ad blocking → **Self-test**) covers host anchors,
  exceptions, `$third-party`, `$domain`, `$script`, regex, document exceptions,
  cosmetic host/generic rules, scriptlets and PSL resolution. It is deterministic
  because it uses inline rules, not the mutable lists.
* The JVM harness in `tools/adblock-test/` runs the same suite and can print index
  statistics against the bundled lists:

```bash
kotlinc app/src/app/novelspeak/adblock/*.kt tools/adblock-test/TestMain.kt \
  -include-runtime -d /tmp/adblock-test.jar
java -jar /tmp/adblock-test.jar app/assets/adblock/public_suffix_list.dat
```

* The full app was compiled with Kotlin 2.2.20 against the Android 34 jar, dexed
  with R8/D8 9.5.28, packaged with `aapt2`, signed with `apksigner`, installed on
  an API 35 emulator, and exercised there. The JS runtime was also tested in
  Chromium with a fake bridge: initial hiding, `:has-text`/`:style`, dynamic
  class/id lookup via `MutationObserver`, `window.open` neutralisation, and the
  overlay heuristic all passed.
