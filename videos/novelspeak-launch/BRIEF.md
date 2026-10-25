---
workflow: product-launch-video
flow: automation
storyboard: no
message: "NovelSpeak reads any web novel aloud with offline neural voices — no account, no ads, no meter."
destination: tiktok-reels-shorts
aspect: 1080x1920
language: en
length: 22s
audience: web-novel readers on TikTok / Reels / Shorts
angle: problem-reversal-hook → product reveal → real UI show-and-tell → quiet-luxury CTA
narration: no
---

## Intent

A premium 20–25 second vertical launch film for **NovelSpeak**, the Android app that reads any
serialized web novel aloud. The source of truth is the actual installed app on the connected
device: real screenshots of the home tab, a chapter being read with paragraph highlighting, the
player, the neural-voice settings, the library, and screen-off media controls. No invented UI, no
features beyond what the app verifiably ships.

Feel: editorial and restrained — a quiet luxury product film, not an ad. Serif display type, mint
signal accent, amber warm rule, ink-dark surfaces. Kinetic type carries the story; real UI zooms and
match cuts carry the proof. Minimal copy, generous negative space, precise beat timing.

Story: Hook ("Any web novel. Out loud.") → product reveal (wordmark, real home screen) → UI
showcase (real chapter extraction + read-along highlight → offline neural voices → screen-off
playback) → CTA (wordmark, "A quiet place to read.", free/offline/no account).

## Assets

- launch-capture/shots/*.png — real device screenshots captured from the installed app (app.novelspeak 1.2.0-dev), staged into capture/assets/.
- launch-capture/recordings/*.mp4 — real read-along playback screen recording, if usable.
- app/src/app/novelspeak/Palette.kt — the app's "Harbor ink" brand tokens (BG #0E1318, surface #161D24, mint #6FE7BE, amber #F4C66A).
- app/src/app/novelspeak/MainActivity.kt — real wordmark treatment (serif display, amber rule) and real tagline "A quiet place to read."

## Customizations

- 9:16 only (1080×1920), 20–25s.
- Kinetic editorial type: masked line reveals, word-level beats, no bouncing/generic spring animation.
- Real-UI zoom moves and match cuts on a common mint accent; no generic 3D device renders.
- Punchy sound design (whooshes, hits, UI ticks) over a modern upbeat bed; no voiceover.
- Captions off by default (on-screen copy is designed); keep one cohesive palette (Harbor ink + mint + amber).

## Notes

- Verified features only (README + source): any-URL browser with Readability extraction; on-device
  neural Kokoro voices (11 English voices, 103 MB int8) + Pocket TTS cloning; screen-off playback
  with media controls; word-level read-along highlight and auto-scroll; auto-next chapter; library
  and continue reading; sleep timer; no account/ads/telemetry. Do NOT claim OCR, DRM bypass, cloud
  voices, or paywall bypass.
- Render is explicitly requested by the user; deliver renders/video.mp4 plus contact sheet.
- No captions burned in; text on screen is art direction, so keep it minimal and legible.
- Music: no HeyGen sign-in (local engines only). Must still ship a licensed/offline-generated bed and SFX.
