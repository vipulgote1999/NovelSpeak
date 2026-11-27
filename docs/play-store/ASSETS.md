# Play Store assets — NovelSpeak 1.4.0

## Files in this directory

| File | Size | Purpose |
|---|---|---|
| `graphics/icon-512.png` | 512×512 | Play Store app icon (32-bit PNG, no transparency required) |
| `graphics/feature-graphic-1024x500.png` | 1024×500 | Play Store feature graphic (no alpha) |
| `screenshots/phone-01-home.png` | 1080×1920 | Home screen, shortcuts and continue reading |
| `screenshots/phone-02-reading.png` | 1080×1920 | Reader mode with the player card |
| `screenshots/phone-03-settings.png` | 1080×1920 | Settings, grouped controls |
| `screenshots/phone-04-privacy.png` | 1080×1920 | In-app privacy and licences |
| `graphics/icon-512.png` | 512×512 | Launcher icon source also used by `app/res/mipmap-*` |

## Requirements met

- App icon: 512×512, 32-bit PNG, square, no rounded-corner requirement, no device frame.
- Feature graphic: 1024×500, PNG, no alpha, no text smaller than ~20 px at 1024 width.
- Phone screenshots: 1080×1920 portrait, PNG, at least 2 (4 supplied).
- All screenshots are real captures from the release build on an Android 15 emulator.
- No fake ratings, awards, device frames or claims that the app cannot deliver.
- The icon and feature graphic use the same palette as the app (`Palette.kt`).

## Regenerate the graphics

```bash
python3 tools/generate-store-graphics.py
```

The script writes the adaptive/legacy launcher icons into `app/res/mipmap-*`, the adaptive icon
XML into `app/res/mipmap-anydpi-v26/`, and the 512 px/1024×500 store graphics into
`docs/play-store/graphics/`. It uses only Pillow and the DejaVu fonts, so it is reproducible on
Linux CI.

## Capture new screenshots

```bash
adb install -r build/NovelSpeak-1.4.0-release-arm64-v8a.apk
adb shell am start -W -n app.novelspeak/.MainActivity
adb exec-out screencap -p > docs/play-store/screenshots/phone-01-home.png
```

For the reading screenshot, open a public-domain HTML page (Project Gutenberg or Wikipedia),
tap the book icon for reader mode, scroll to the first paragraph, and capture. Keep the status
bar showing a realistic time and network state; do not include notifications, other apps or
personal information.

## Listing copy

Use [`listing.md`](listing.md) for the title, short description, full description, category,
tags and graphic asset mapping. Do not add screenshots that imply the app supplies book content
or bypasses paywalls.
