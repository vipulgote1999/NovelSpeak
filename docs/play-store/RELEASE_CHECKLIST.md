# Play Store release checklist — NovelSpeak 1.4.0

Run this checklist before every Play upload. Commands assume the repository root and:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export BUILD_TOOLS="$ANDROID_HOME/build-tools/36.0.0"
export ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
export PATH="$HOME/.local/share/kotlin-2.2.20/bin:$BUILD_TOOLS:$PATH"
source "$HOME/.keys/novelspeak-release.env"
```

## 1. Build and signing

- [ ] `android:versionCode` incremented and `android:versionName` updated in
      `app/AndroidManifest.xml`.
- [ ] `CHANGELOG.md` and `docs/releases/v1.4.0.md` updated.
- [ ] APK built with `build.sh` using the release key; not debug-signed.
- [ ] AAB built with `tools/build-aab.sh` using the upload key.
- [ ] Upload key and passwords backed up offline.
- [ ] `jarsigner -verify` says "jar verified" for the AAB.
- [ ] `apksigner verify --print-certs` shows the release certificate, not the debug cert.

## 2. Technical compliance

- [ ] `targetSdkVersion` is **36**.
- [ ] `minSdkVersion` is 24.
- [ ] `compileSdkVersion` is 36.
- [ ] `versionCode` is higher than every previously uploaded build.
- [ ] AAB is the upload artifact (not APK).
- [ ] 64-bit `arm64-v8a` native libraries present.
- [ ] `zipalign -c -P 16 -v 4` passes for the APK and the Play-generated universal APK.
- [ ] `readelf -lW` shows `LOAD` alignment `0x4000` for every `.so`.
- [ ] `extractNativeLibs="false"` and native libraries are stored uncompressed.
- [ ] No `android:debuggable`, no `android:testOnly`.
- [ ] `allowBackup="false"`.
- [ ] `usesCleartextTraffic="false"` and the release network config has no localhost exceptions.
- [ ] No exported components except the launcher activity and the permission-guarded media
      button receiver. `BenchmarkActivity` is **not** exported.
- [ ] Only the six declared permissions: INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK,
      FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS.
- [ ] No `QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_*`, location, camera,
      microphone, contacts, SMS, call log, body sensors or Bluetooth permissions.
- [ ] No `<uses-permission android:name="com.google.android.gms.permission.AD_ID" />`.
- [ ] No ad, analytics or crash-reporting SDK in `deps/` or the APK.
- [ ] No `addJavascriptInterface` / `@JavascriptInterface` in the shipped source.
- [ ] WebView: HTTPS-only main frame, no file/content access, no mixed content, no third-party
      cookies, safe browsing on, debugging off in release.
- [ ] Exported activity rejects non-HTTP(S) URIs from other apps.
- [ ] Model and filter-list downloads are HTTPS-only and disclose their size before starting.
- [ ] Foreground service uses `mediaPlayback`, starts `startForeground()` and posts a media
      notification.
- [ ] `POST_NOTIFICATIONS` is requested at first playback and denial does not crash playback.
- [ ] `enableOnBackInvokedCallback="false"` is present until the custom back stack is migrated.

## 3. Store listing and app content

- [ ] Privacy policy URL is live, HTTPS, globally accessible, not a PDF.
- [ ] In-app privacy page is reachable from Settings and links to the same URL.
- [ ] Ads declaration = **No**.
- [ ] App access = **All functionality available without special access**; no credentials.
- [ ] Target audience = **18 and over only**; **Restrict minor access** enabled.
- [ ] Content rating completed; **unrestricted web access = Yes**.
- [ ] Data safety = **No data collected**, **No data shared**; policy matches.
- [ ] Foreground-service declaration completed for `mediaPlayback` with description and video.
- [ ] News app = No; COVID = No; government = No; financial = No; health = No; gambling = No;
      UGC = No; payments/subscriptions = No; accessibility API = No.
- [ ] Store listing text matches [`listing.md`](listing.md).
- [ ] Icon 512×512, feature graphic 1024×500, at least 2 phone screenshots.
- [ ] Screenshots show the real app and make no false claims.
- [ ] Contact email is valid and monitored.
- [ ] App category is Books & Reference.

## 4. Testing

- [ ] Clean install on a physical Android 16 device or API 36 emulator.
- [ ] Cold start < 3 s, no crash, no ANR.
- [ ] Home → Project Gutenberg/Wikipedia → Read works with system TTS.
- [ ] Screen-off playback survives 5 minutes; notification and lock-screen controls work.
- [ ] Deny notification permission; playback still works and UI explains the hidden controls.
- [ ] Rotate phone/tablet; state survives and layout does not clip.
- [ ] Open Settings, Library, About & privacy, overflow menu; back stack behaves.
- [ ] Import a small EPUB and a PDF with a text layer.
- [ ] Share a URL from another app; share selected text via PROCESS_TEXT.
- [ ] Tap a non-HTTP link (mailto/tel/intent) and confirm it is not loaded into the WebView.
- [ ] Install a neural model after confirming the size; verify SHA-256 and playback.
- [ ] Cancel a model download and confirm no partial model is used.
- [ ] Update filter lists after confirming the size.
- [ ] Run the pre-launch report; triage every crash, ANR, accessibility and security warning.
- [ ] Check TalkBack on home, settings, player controls and privacy page.
- [ ] Check 200% font scale and display size; no clipped controls.
- [ ] Check RTL layout (Arabic/Hebrew) for obvious mirroring issues.
- [ ] Check the app on a 16 KB page-size emulator if available (`adb shell getconf PAGE_SIZE`
      returns 16384).

## 5. Submission

- [ ] Upload AAB to Internal testing.
- [ ] Complete every App content item; no "Needs attention" remains.
- [ ] Review the pre-launch report.
- [ ] Promote to Closed testing and run the reviewer flow in [`REVIEW_NOTES.md`](REVIEW_NOTES.md).
- [ ] Promote to Production with a staged rollout.
- [ ] Monitor Android vitals, crash rate and reviews for the first 48 hours.
