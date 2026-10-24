# Headset / Bluetooth media controls

Date: 2026-10-24 · Symptom: "control using headphone is not working".

## 1. Reproduction

Emulator (API 35, x86_64), system TTS engine, chapter playing:

```
adb shell cmd media_session dispatch play-pause   # paused while PLAYING (worked)
adb shell input keyevent 85                       # paused while PLAYING
adb shell cmd media_session dispatch play          # while PAUSED: nothing happened
adb shell input keyevent 85                        # while PAUSED: nothing happened
```

The in-app play button resumed fine, so the Reader was healthy — the key never reached the app.
Instrumenting `MediaSession.Callback.onMediaButtonEvent` showed **zero** callbacks for those keys.

`dumpsys media_session` before the fix:

```
NovelSpeak app.novelspeak/NovelSpeak/2
  mediaButtonReceiver=null
  active=true
  state=PlaybackState {state=PLAYING(3) ...}
Global priority session is com.android.server.telecom/HeadsetMediaButton
  Last MediaButtonReceiver: null
  Media button session is null
```

`dumpsys audio` showed the playing AudioTrack belonged to `uid:10165` (Google TTS), not
NovelSpeak (`uid 10228`).

## 2. Root cause

Two independent platform rules combined:

1. **Media buttons are routed to the app that owns audio playback.**
   AOSP `MediaSessionStack.updateMediaButtonSessionIfNeeded()` walks the active audio-playback
   client UIDs and skips any UID without a MediaSession ("Ignore if the lastly played app isn't a
   media app"). With `TextToSpeech.speak()` the audio is produced by the **TTS provider process**,
   so the playback UID is Google TTS, which has no session → `Media button session is null`.
2. **When no session is selected, the system falls back to the remembered MediaButtonReceiver.**
   AOSP `MediaSessionService.dispatchMediaKeyEventLocked()` uses
   `mLastMediaButtonReceiverHolder`; if the app never declared one, the key is discarded. Ours was
   `null`, hence "nothing happens".

The neural engine was unaffected because it writes an `AudioTrack` in our process — NovelSpeak is
then the audio client and the session is selected.

## 3. Fix

### a. Play system-TTS audio in-process

`SystemTtsEngine` now uses `TextToSpeech.synthesizeToFile()` and decodes the WAV with the existing
`WavReader`, then streams it through `PcmPlayer` (24 kHz mono). The audio client UID is NovelSpeak,
so Android selects our MediaSession as the media-button session. It also keeps screen-off playback
inside our foreground service, consistent with the neural path.

* Providers that reject file synthesis fall back to direct `speak()` so audio still works (headset
  control then depends on the platform again). The fallback is logged.
* The denoiser is disabled for system TTS (`PcmPlayer.setDenoiseEnabled(false)`): it targets
  vocoder hiss, and clean TTS needs no processing.
* One `write()` per sentence keeps `PcmPlayer`'s per-chunk peak normalisation constant. Slicing
  would make the gain pump.

### b. Declare the media-button receiver

* `MediaButtonReceiver` (manifest, exported, `ACTION_MEDIA_BUTTON`).
* `MediaSession.setMediaButtonBroadcastReceiver()` on API 31+,
  `setMediaButtonReceiver(PendingIntent)` below that.
* `PlaybackService` accepts `ACTION_MEDIA_BUTTON` and maps keys to the same transport helpers the
  session callback uses. Only `ACTION_DOWN` with `repeatCount == 0` is honoured, and on API 26+
  only PLAY / PLAY_PAUSE / HEADSETHOOK are accepted (they are the only keys that can start a
  foreground service meaningfully) — the same contract Media3's receiver implements.
* If no queue is loaded (process was recreated), a key opens the reader instead of doing nothing.

### c. Seek deadlock found while testing

After a seek, `PcmPlayer.awaitDrained(targetFrames)` waited on a frame count captured before the
AudioTrack flush; the head position had already reset, so the target was unreachable and the
engine thread hung (no audio after skipping). `awaitDrained` now takes the player **epoch** and
aborts when a reset/stop occurs. The neural engine's boundary waiter got the same guard, so a seek
during a long Kokoro/Pocket sentence cannot stall playback either.

### d. PlaybackState actions

Added `ACTION_PLAY` / `ACTION_PAUSE` alongside `ACTION_PLAY_PAUSE` for controllers that branch on
the concrete action.

## 4. Verification (emulator, API 35, x86_64)

| Check | Evidence |
|---|---|
| Audio owner | `dumpsys audio` → `new player piid:… uid/pid:10228/… AudioTrack … usage=USAGE_MEDIA` |
| Media button session | `Media button session is app.novelspeak/NovelSpeak/5` |
| Headset pause | `media key code=85 …` → `media onPause` → state `PAUSED` |
| Headset resume (previously broken) | state `PAUSED` → `media onPlay` → `PLAYING` |
| Headset next / previous | cursor `2/679 → 3/679 → 2/679`, `onSkipToNext` / `onSkipToPrevious` |
| Standby (screen off) pause/resume | both transitions observed with the display off |
| Restart path | after `dispatch stop` (0 sessions): `dispatch play` → `media button receiver code=126` → `media key dispatch` → `play started` |
| Seek while playing | 4 skips produced 3 fresh renders and playback continued (no stall) |
| Crashes | none in logcat |

Commands used:

```bash
adb shell cmd media_session dispatch play-pause   # or next / previous / stop / play
adb shell input keyevent 85                       # KEYCODE_MEDIA_PLAY_PAUSE
adb shell dumpsys media_session | grep -A9 'NovelSpeak'
adb shell dumpsys audio | grep 'new player'
```

## 5. Notes

* After a force-stop Android blocks broadcasts to the app until it is launched again; that is
  platform behaviour, not an app bug.
* If a device's TTS provider cannot synthesize to file, the app still plays audio through
  `speak()`, but media-button routing falls back to the provider's UID. The log line
  `tts file synthesis unsupported` identifies that configuration.
* Bluetooth AVRCP and wired headsets both arrive as `ACTION_MEDIA_BUTTON` / media keys and use the
  same paths verified above.
