# Play Store readiness — NovelSpeak 1.4.0

This directory is the submission packet for Google Play. It was audited on **7 November 2026**
against Google Play's 2026 requirements (target API 36, AAB, 16 KB page sizes, Data safety,
content ratings, foreground-service declarations and the User Data / Device and Network Abuse
policies).

## Read in this order

1. [`PLAY_CONSOLE.md`](PLAY_CONSOLE.md) — exact App content answers and declarations.
2. [`listing.md`](listing.md) — title, short/full description, category and tags.
3. [`DATA_SAFETY.md`](DATA_SAFETY.md) — Data safety form and the edge cases.
4. [`REVIEW_NOTES.md`](REVIEW_NOTES.md) — what to paste for the review team.
5. [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md) — build, test and submission checklist.
6. [`ASSETS.md`](ASSETS.md) — icon, feature graphic and screenshot inventory.

## What was fixed for this release

| Area | Before | Now |
|---|---|---|
| Target API | 34 | **36** (required for new apps/updates since 31 Aug 2026) |
| Upload format | APK only | **Signed AAB** built by `tools/build-aab.sh`, APK kept for direct distribution |
| 16 KB pages | ELF-aligned but compressed libs | ELF `0x4000` aligned, **uncompressed** libs, `zipalign -P 16`, AAB config `PAGE_ALIGNMENT_16K` |
| Play icon | `@android:drawable/sym_def_app_icon` | Original adaptive/legacy icon and 512 px store icon |
| Exported debug component | `BenchmarkActivity` exported in release | **Not exported**; only the dev variant flips it |
| WebView bridge | `addJavascriptInterface` on untrusted pages | Origin-scoped `WebViewCompat.addWebMessageListener`, HTTPS only |
| WebView hardening | mixed content compatibility, file/content access, third-party cookies | HTTPS main frame, no file/content access, mixed content never, third-party cookies off, safe browsing on |
| Intent handling | Could load arbitrary `content:`/`file:`/`javascript:` from an exported activity | Only HTTP(S) links and `content:` EPUB/PDF imports |
| Cleartext | localhost exception in the release config | Release config has no exceptions; dev config moved to `app/res-dev` |
| Default shortcuts | Included a site with a piracy history | Royal Road, Scribble Hub, WebNovel, Wattpad, Gutenberg, Wikipedia, Library |
| Download consent | Model/list download started on tap | Confirmation dialog discloses the size before download |
| Diagnostics redaction | Event log URLs were not redacted | URLs and credential-shaped values redacted before zipping |
| Privacy policy | In-app text only | In-app text + hosted HTML policy + Pages workflow |
| Back navigation | `onBackPressed` only | `enableOnBackInvokedCallback="false"` so the custom WebView back stack works on Android 16 |

## Manual steps that still need the account owner

- Enter the Play Console contact email (the repo uses the GitHub issue tracker as the privacy
  contact; the store listing still needs a monitored email).
- Complete developer identity verification, create the app, upload the AAB, and complete the
  App content forms listed in `PLAY_CONSOLE.md`.
- Record the foreground-service demo video described in `PLAY_CONSOLE.md`.
- Run the pre-launch report on an Android 16 device/emulator.

## Live privacy policy

GitHub Pages is enabled with **GitHub Actions** as the source. The workflow
`.github/workflows/pages.yml` published `site/`, and both URLs return HTTP 200:

- `https://vipulgote1999.github.io/NovelSpeak/`
- `https://vipulgote1999.github.io/NovelSpeak/privacy/`

## Audit evidence

- The automated Play policy scan (data safety, login credentials, restricted permissions) is in
  `.scratch/play_policy_insights_*/compliance_report.md` for this session. It reports no
  critical/important findings.
- `build/NovelSpeak-1.4.0.aab` is signed with the upload key; `jarsigner -verify` passes.
- `build/NovelSpeak-1.4.0-arm64-v8a.apk` is signed with the release key; `apksigner verify`
  passes.
- `zipalign -c -P 16 -v 4` passes for both the APK and the Play-generated universal APK.
- `aapt2 dump badging` shows `targetSdkVersion:'36'`, `versionCode='20'`,
  `versionName='1.4.0'`.
