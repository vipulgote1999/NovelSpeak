# Notes for the Google Play review team

Paste this into **App content → App access → Any other instructions** (or the review notes
field). It is also useful for the pre-launch report.

```
NovelSpeak has no login, account, subscription or reviewer-only mode. Every feature is
available from the home screen.

Suggested review flow:
1. Launch the app. The home screen shows shortcuts (Royal Road, Scribble Hub, WebNovel,
   Wattpad, Project Gutenberg, Wikipedia) and the address bar.
2. Tap Project Gutenberg (or enter https://www.gutenberg.org/ and open any book's HTML page).
   Tap "Read". The page is extracted on-device and Android system TTS starts.
3. The first playback asks for notification permission. This is optional; denying it leaves
   playback working while the media notification stays hidden.
4. Tap the book icon to toggle reader mode and the shield icon to toggle ad blocking for the
   current site. The overflow menu contains Library, Settings, About & privacy, Translate,
   Share link and Import EPUB/PDF.
5. Settings → Reading aloud lets you install an optional neural voice. The app shows the exact
   download size and asks for confirmation before downloading; the model is SHA-256 verified
   and runs on-device. You do not need to install a model to review the app.
6. Settings → About NovelSpeak → Privacy & licences contains the full in-app privacy disclosure
   and a link to the hosted policy.

Important context:
- The app is a general-purpose web reader with unrestricted web access. It is intended for
  users aged 18+ and Google Play's target audience is set to 18+ with minor access restricted.
- The app does not host, publish or distribute books or other copyrighted content. It displays
  pages from sites the user chooses, like a browser.
- NovelSpeak never bypasses a login, paywall, Cloudflare challenge or DRM. If a site shows a
  verification page, the app detects it, does not read it aloud, and asks the user to finish
  the check in the page.
- The app has no account system, no ads, no analytics and no telemetry. The only network
  destinations are the pages the user opens, Google Search/Translate when the user chooses
  those actions, the official sherpa-onnx GitHub model releases after a size confirmation, and
  EasyList/uBlock filter lists after a size confirmation.
- The only foreground service is mediaPlayback for screen-off read-aloud, with a visible media
  notification and lock-screen controls.
- The benchmark activity is not exported in the Play build and is not reachable from the UI.
```

## What a reviewer should not need

- No test account or password.
- No invite code.
- No location, camera, microphone or storage permission.
- No network proxy, VPN or special device configuration.
- No paid content or in-app purchase.

## If the reviewer cannot reach a third-party site

Third-party sites can be unavailable, rate-limit the reviewer's region, or show a Cloudflare
challenge. That is outside the app's control. Use Project Gutenberg or Wikipedia for the
reading flow; both are public, stable and text-first. The app's own UI, settings, library and
privacy pages do not depend on any third-party site.
