# Extraction regression fixtures

`run.sh` runs the deterministic fixture that needs no adblock bridge:

- `ad-hidden.html` — a cosmetic rule is simulated with an inline `<style>`.
  The test asserts that `extract.js` drops the hidden `SPONSORED` block while
  keeping the two visible chapter paragraphs.

`ad-hidden-adblock.html` is the app-level version: it has **no inline CSS**; the
ad class (`.ad--article`) is hidden only by the bundled AdBlock list. Load it in
the app with:

```bash
cd tools/extract-test
python3 -m http.server 8765 --bind 127.0.0.1
adb reverse tcp:8765 tcp:8765
adb shell am start -a android.intent.action.VIEW \
  -d http://localhost:8765/ad-hidden-adblock.html \
  -n app.novelspeak/.MainActivity
```

Expected app log after the fix:

```text
adblock ready network=122885 cosmetic=32405 ...
extract url=http://localhost:8765/ad-hidden-adblock.html ... blocks=2
```

If the generic cosmetic batch is applied too late, the log shows `blocks=3`
and the `SPONSORED` text is read by TTS.
