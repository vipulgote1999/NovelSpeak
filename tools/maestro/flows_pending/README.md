# flows_pending/

New-feature flows wait here until the Lead confirms the matching build is installed, so the
default `tools/maestro/run.sh` never runs a flow against an APK that cannot support it.

Protocol:
1. Add the flow here with its `PENDING BUILD` note and provisional labels.
2. When the build is installed, run:
   `tools/maestro/run.sh <flow>.yaml` (or `tools/maestro/run.sh --pending`).
3. Fix any label drift against `maestro hierarchy`, then move the flow to `flows/` and update
   `EXPECTED.md` (status PASS + verified build/version).

The gate flows 05–09 were verified on 1.2.0-dev (versionCode 16) and promoted to `flows/`.
`flows/08_share_intent.txt` is an adb-driven script, not a Maestro YAML flow.
