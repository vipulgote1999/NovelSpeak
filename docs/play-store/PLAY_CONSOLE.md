# Play Console configuration — NovelSpeak 1.4.0

This is the exact configuration to enter in Google Play Console for the first production
submission. It is written against the requirements in force on **7 November 2026**.

## App identity

| Field | Value |
|---|---|
| Package name | `app.novelspeak` |
| App name | NovelSpeak: Web Novel Reader |
| Version name | `1.4.0` |
| Version code | `20` |
| Default language | English (United States) |
| App or game | App |
| Category | Books & Reference |
| Tags | Reading, Text to speech, Offline |
| Contact email | Use the email on the Play Console developer account (required; not stored in this repo) |
| Website | `https://vipulgote1999.github.io/NovelSpeak/` (after the Pages workflow runs) |
| Privacy policy | `https://vipulgote1999.github.io/NovelSpeak/privacy/` |
| Content rating | Complete the IARC questionnaire; unrestricted web access = **Yes** |
| Target audience | **18 and over only**; enable **Restrict minor access** |
| Ads | **No** |
| In-app purchases | **No** |
| Account creation | **No** |

## App content declarations

### Privacy policy

Enter:

```
https://vipulgote1999.github.io/NovelSpeak/privacy/
```

The same policy is available inside the app at **Settings → About NovelSpeak → Privacy &
licences**, with a link to the hosted page. The page must be publicly reachable, HTTPS, not a
PDF, and not geofenced. The `site/` directory and `.github/workflows/pages.yml` publish it.

### Ads

Select **No**. NovelSpeak contains no ad SDK, no house ads, no cross-promotion and no paid
placement. The in-app ad blocker only hides third-party ads inside NovelSpeak's own WebView; it
is not an ad product and does not monetize the user.

### App access

Select **All functionality is available without special access**.

NovelSpeak has no login, no account, no subscription and no reviewer-only code path. Every
screen is reachable from the home screen. No test credentials are required.

### Target audience and content

- Age groups: **18 and over** only.
- **Restrict minor access: enabled.** This is required because the app has unrestricted web
  access and is not designed for children.
- App details: "General-purpose web reader with an address bar. The app does not host or
  moderate the websites a user visits. Users must be 18+."
- Ads: No.
- Store presence: not designed for children; not eligible for Teacher Approved.

Do **not** select any age group below 18. Selecting 13–17 would pull the app into the Families
policy because unrestricted web access is incompatible with child-directed design. The app's
own content is a reading tool, but the open web is not filtered.

### Content rating questionnaire

Select the category **Reference, News, or Educational** (or **Utility, Productivity,
Communication, or Other** if that is the closest available label for your account).

Answers:

| Question | Answer |
|---|---|
| Violence | No |
| Blood / gore | No |
| Sexual content / nudity | No |
| Profanity | No |
| Controlled substances | No |
| Gambling / simulated gambling | No |
| Fear / horror | No |
| User-generated content hosted by the app | No |
| Users can interact / share content with each other | No |
| Users can share their location | No |
| Purchases / digital goods | No |
| Ads | No |
| **Unrestricted web access** | **Yes** |
| Miscellaneous / other | No |

Because the app has an address bar and can open arbitrary websites, **Unrestricted web access
must be Yes**. Expect the IARC authorities to assign a mature rating (typically 17+/18+ or the
regional equivalent). That rating is accurate and is why the target audience is 18+.

### Data safety

See [`DATA_SAFETY.md`](DATA_SAFETY.md). Summary:

- Data collected: **No**
- Data shared: **No**
- Data processed ephemerally: **No** (the app does not operate a backend)
- Data encrypted in transit: **Yes** (all app-initiated traffic is HTTPS)
- Users can request data deletion: **No account exists**, so there is nothing to delete on a
  server. Local data is removed by clearing app storage or uninstalling.
- Prominent disclosure: **Not required** — no personal/sensitive data is collected in the
  background, and diagnostic export is user-initiated through the system share sheet.

The privacy policy explains the third-party websites and services a user may choose to open
(Google Search, Google Translate, GitHub model downloads, EasyList/uBlock lists) so the label
and the policy are consistent.

### Foreground service declaration

The app declares one foreground service type: **mediaPlayback**.

Use this description:

> NovelSpeak reads the current book chapter aloud with an on-device text-to-speech engine.
> Playback must continue when the screen is off or the user switches apps, and the media
> notification provides play/pause, previous/next sentence and stop controls. The service is
> started only when the user taps Read or a media button, stops when playback stops, and never
> records audio.

User impact: screen-off listening, lock-screen/Bluetooth controls, and pause/resume around
calls and navigation prompts.

Demo video shot list (30–60 seconds):

1. Open NovelSpeak and load a public-domain page (Project Gutenberg is a good choice).
2. Tap **Read**; audio starts and the playback notification appears.
3. Lock the screen; audio continues and the lock-screen controls are visible.
4. Press pause/play from the lock screen; press next/previous sentence.
5. Open the notification shade and show the media notification and Stop action.
6. Return to the app, tap Stop, and show that the service and notification disappear.

### Other declarations

| Declaration | Answer |
|---|---|
| News app | No — general web reader, not a news publisher |
| COVID-19 contact tracing / status | No |
| Government app | No |
| Financial features | No |
| Health features / Health Connect | No |
| Real-money gambling / games / contests | No |
| User-generated content | No — the app hosts no user content or social feed |
| Payments / subscriptions | No |
| Accessibility API | No |
| Restricted permissions (SMS, Call Log, location, all files, photos, contacts, etc.) | None requested |
| QUERY_ALL_PACKAGES | No |
| REQUEST_INSTALL_PACKAGES | No |
| VPN service | No |
| Full-screen intents | No |
| Exact alarms | No |
| Background location | No |
| Bluetooth / nearby devices | No |
| Advertising ID | Not used |

## Release configuration

- **AAB required:** upload `build/NovelSpeak-1.4.0.aab`. Do not upload the APK to Play.
- **Play App Signing:** enabled. The `.aab` is signed with the upload key
  (`~/.keys/novelspeak-release.jks`); Google re-signs delivered APKs.
- **Target SDK:** 36 (Android 16). Required for new apps and updates since 31 August 2026.
- **Min SDK:** 24.
- **ABI:** `arm64-v8a` only. The neural engine `.so` files are ~32 MB; no 32-bit build is
  shipped. This is allowed, but it limits installation to 64-bit devices.
- **16 KB page size:** ELF `LOAD` segments are `0x4000`-aligned, native libraries are stored
  uncompressed, and the bundle requests `PAGE_ALIGNMENT_16K`. Verified with `zipalign -c -P 16`.
- **Debugging:** release manifest is not debuggable, has no `testOnly`, uses
  `allowBackup="false"`, blocks cleartext, and does not export the benchmark harness.
- **Version codes:** increment `android:versionCode` for every upload. Never reuse a code.

## Manual steps that only the account owner can do

1. Create/verify the Play Console developer account and complete **developer identity
   verification** (rolling out through 2026–2027).
2. Accept the Developer Distribution Agreement and set up a payments profile only if you later
   add paid features (not needed for a free app).
3. Create the app with package `app.novelspeak`.
4. Upload the AAB to **Internal testing** first.
5. Complete every **App content** item above.
6. Add the store listing text and graphics from this directory.
7. Run the **Pre-launch report** on an Android 16 device/emulator and fix every crash,
   ANR, accessibility and policy warning.
8. Promote to Closed testing, then Production with a staged rollout (for example 10% → 50% →
   100%).
9. Keep the upload keystore and passwords backed up offline. Losing the upload key is
   recoverable through Play App Signing support; losing the app signing key is not.

## Pre-submission command checks

```bash
# Target SDK, version, permissions, icon
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging build/NovelSpeak-1.4.0-arm64-v8a.apk | head -20

# 16 KB ELF and ZIP alignment
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 -v 4 build/NovelSpeak-1.4.0-arm64-v8a.apk | tail -1

# AAB signature and config
jarsigner -verify -verbose -certs build/NovelSpeak-1.4.0.aab | grep 'jar verified'

# Universal APK generated by Play's own bundletool
java -cp "$BUNDLETOOL_CP" com.android.tools.build.bundletool.BundleToolMain build-apks \
  --bundle=build/NovelSpeak-1.4.0.aab --output=build/verify.apks --mode=universal \
  --aapt2="$ANDROID_HOME/build-tools/36.0.0/aapt2"
unzip -p build/verify.apks universal.apk > build/universal.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 -v 4 build/universal.apk | tail -1
```
