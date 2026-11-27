# Google Play store listing — NovelSpeak 1.4.0

Copy these values into **Grow → Store presence → Main store listing**. Keep every claim
accurate: the app is a browser/reader with native text-to-speech, not a content service.

## App name (30 characters max)

```
NovelSpeak: Web Novel Reader
```

28 characters. The short name "NovelSpeak" is used inside the app and on the launcher.

## Short description (80 characters max)

```
Read web novels aloud with offline voices and screen-off playback.
```

66 characters. Alternatives if you need to rotate copy:

- `Turn web chapters into audiobooks with on-device neural voices.` (63)
- `A quiet web novel reader with offline text-to-speech and a local library.` (73)

## Full description (4000 characters max)

```
NovelSpeak turns the web pages you already read into clean, listenable text — with voices that
run on your phone, not in the cloud.

READ ANY PAGE YOU CHOOSE
Open a chapter in the built-in browser, or share a URL from another app. NovelSpeak extracts the
main text, removes page clutter, and queues it for reading. It works with serialized chapter
pages, standard articles, Project Gutenberg books, Wikipedia and other text-first sites. It does
not host, publish or distribute books, and it is not affiliated with any website or publisher.

LISTEN WITH OFFLINE VOICES
• Android system TTS: instant, offline, and available in every language your device supports.
• Neural voices: optional Kokoro, Supertonic, Pocket and ZipVoice models run entirely on-device
  after a one-time download. Models are SHA-256 verified and never uploaded.
• Adjust speed, pitch, volume boost and sentence pacing.
• Screen-off playback with lock-screen, notification and headset controls.
• Sleep timer, per-sentence navigation and automatic next-chapter queueing.

A READER THAT REMEMBERS YOUR PLACE
• Local library with continue-reading, bookmarks and reading statistics.
• Import EPUB, PDF (text layer) and plain-text files you own; the extracted text stays in the
  app's private storage.
• Reader mode with adjustable font, size, line height, width and theme.
• A two-column library grid on tablets and a compact player on phones.

QUIET BY DEFAULT
• No account, no sign-in, no ads and no telemetry.
• No advertising ID, no analytics SDK, no crash-reporting SDK and no push service.
• Ad blocking runs only inside NovelSpeak's own browser view; it does not touch other apps or
  device-wide settings.
• Your reading position, library and settings stay on your device. Nothing is uploaded to a
  NovelSpeak server because there is no NovelSpeak server.

BUILT FOR LONG READING SESSIONS
• A dark, low-glare interface designed for night reading.
• Per-site reader preferences and ad-block controls.
• Diagnostics you create and share yourself only when you ask for support.

GOOD TO KNOW
• NovelSpeak is a general-purpose web reader with unrestricted web access. It is intended for
  users aged 18 and over; parents should use Android parental controls for younger readers.
• Some sites use Cloudflare or a paywall. NovelSpeak detects the interstitial, does not read it
  aloud, and never bypasses a login, paywall or DRM.
• Neural voices are large (about 50–200 MB) and are downloaded only after you confirm the size.
• System TTS quality and language support depend on the voices installed on your device.

NovelSpeak is open source under the Apache License 2.0. Source, issue tracker and release notes:
https://github.com/vipulgote1999/NovelSpeak
```

Character count is under 4000. It mentions "unrestricted web access" and 18+, which matches
the content rating and target audience.

## Category and tags

- **Category:** Books & Reference
- **Tags:** Reading, Text to speech, Offline
- **Contact details:** use the developer contact email on the Play Console account
- **Website:** `https://vipulgote1999.github.io/NovelSpeak/`
- **Privacy policy:** `https://vipulgote1999.github.io/NovelSpeak/privacy/`

## Graphic assets

| Asset | File | Notes |
|---|---|---|
| App icon | [`graphics/icon-512.png`](graphics/icon-512.png) | 512×512 PNG, 32-bit, no rounded-corner requirement |
| Feature graphic | [`graphics/feature-graphic-1024x500.png`](graphics/feature-graphic-1024x500.png) | 1024×500 PNG, no alpha |
| Phone screenshots | [`screenshots/`](screenshots/) | 1080×1920 PNG, at least 2, up to 8 |
| Tablet screenshots (optional) | add 1600×2560 or 2560×1600 | Recommended if you ship tablet layouts |
| Promo video (optional) | — | Use the 30–60 s foreground-service demo as a starting point |

Screenshots must show the real app. Do not add fake device frames, fake ratings, fake awards or
text that implies a feature the app does not have. The prepared set includes home, reading,
settings and privacy screens.

## What not to say

Avoid these claims in the listing, screenshots or ads:

- "Read any book free" / "unlimited free novels" — the app does not supply content.
- "Bypass paywalls", "remove DRM", "unlock chapters" — the app explicitly does not.
- "Official" or any publisher/website logo that implies affiliation.
- "Works on every site" — Cloudflare, DRM, login walls and non-text pages can prevent
  extraction.
- "No ads" is accurate because the app serves none; "ad-free web" is not, because websites may
  still show first-party content or ads that the filter lists do not cover.
