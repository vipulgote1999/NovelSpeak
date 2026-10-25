# Play release prep (one-time Pro)

This document is the checklist for a Google Play listing. The GitHub/F-Droid build stays
feature-complete; Play adds distribution and an optional one-time unlock. Nothing here changes the
privacy posture (no account, no analytics, no cloud inference).

## 1. Packaging

- Build per-ABI artifacts: `ABI=arm64-v8a ./build.sh` and `ABI=armeabi-v7a ./build.sh`
  (the neural `.so`s dominate APK size; the default build is arm64-only).
- For Play, upload an AAB or per-ABI APKs; keep `versionCode` monotonic in `app/AndroidManifest.xml`
  (`1.2.0-dev` = 16 today).
- Current APK: ~15.3 MB arm64; native payload ~32 MB uncompressed, models are downloaded on demand.
- Permissions must stay at the current five (INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK,
  FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS); no storage, location,
  contacts, camera or microphone.

## 2. One-time Pro (optional)

- Price: **$4.99–$6.99 one-time** (research baseline: one-time peers are $7.49–$11.99).
- What Pro may add: convenience/support extras (extra voice packs, batch export), never gating the
  core promise (any URL reading, offline neural voices, screen-off playback, no meter).
- Billing: Play Billing wrapper behind an interface; the open-source build compiles it out and keeps
  every feature free. Never add ads, subscriptions or credit meters (top review-damage themes).
- Trial: not needed; local inference has no marginal cost.

## 3. Store listing copy (draft)

- Title: `NovelSpeak: web novel reader`
- Short description: `Read any web novel aloud with offline neural voices. No account, no ads, no meter.`
- Full description: any URL browser + Readability extraction, auto-next chapter, 11 Kokoro voices +
  Pocket voice cloning, screen-off media controls, sleep timer, library/resume, share-to-read,
  pronunciation dictionary, typography controls, ad blocking, privacy-first (no telemetry).
- Screenshots: reader on a chapter, player card with sentence progress, settings voices,
  library screen, privacy page.
- Data safety: no data collected; no data shared; model/filter downloads only.
- Content rating: everyone; mention user-provided URLs.

## 4. Policy checklist

- [ ] Privacy policy page (in-app "About & privacy" + hosted URL).
- [ ] Foreground service declaration: mediaPlayback, with the notification and stop action.
- [ ] No MANAGE_EXTERNAL_STORAGE / QUERY_ALL_PACKAGES / READ_PHONE_STATE.
- [ ] No misleading claims (do not claim Chrome stops on lock; Chrome supports lock-screen playback).
- [ ] Copyright posture: generic page-scoped extraction only; no book-source catalogue, no DRM
      circumvention; login/paywall detection is warn-only.
- [ ] Cloning: user-recorded/imported reference only, consent text, "cloned" label, delete wipes data.
- [ ] Test the release build on API 24, 29, 33, 34/35 (emulator + one physical device).

## 5. Release checklist

1. Update versionName/versionCode and CHANGELOG.
2. Run host suites: library, feature, TTS cleaner, audio DSP, EPUB, PDF, adblock, URL input.
3. Run `tools/maestro/run.sh` (all flows) and `tools/maestro/intents.sh` on a clean install.
4. Verify `./build.sh` from a clean checkout with the documented toolchain.
5. Tag the release, publish the APK + sha256 on GitHub, then upload the AAB/APKs to Play.
