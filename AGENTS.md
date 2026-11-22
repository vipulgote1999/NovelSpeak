# Agent notes

## Testing

Read [`docs/testing.md`](docs/testing.md) before changing behavior.

- While working: `tools/test-changed.sh` (host + browser; add `--run-device` when an emulator is attached).
- Before claiming completion: `tools/test-all.sh --quick`.
- Full gate with a device: `tools/test-all.sh`.
- New host harnesses must be registered in `tools/host-tests.tsv`; `tools/test-host.sh --check` enforces it.
- Device flows must use the test-only APK with `app/res-dev` cleartext config; the release APK must stay cleartext-blocked.

## Project shape

- No Gradle, Hilt, Robolectric, Espresso or JUnit. Keep the existing host-Kotlin + Maestro stack.
- Kotlin **2.2+** is required.
- Build with `./build.sh`; release signing and Play details are in `RELEASING.md`.
