# Releasing NovelSpeak

The version lives only in `app/AndroidManifest.xml` (`android:versionCode` and
`android:versionName`). `build.sh` reads it from the manifest; do not duplicate it.

## One-time signing setup

Release keys live outside the repository. Losing the key means future releases cannot update
an installed copy, so back up both files offline.

```bash
mkdir -p "$HOME/.keys" && chmod 700 "$HOME/.keys"
keytool -genkeypair \
  -keystore "$HOME/.keys/novelspeak-release.jks" \
  -alias novelspeak \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=NovelSpeak,O=NovelSpeak,C=IN"
```

Store the passwords in `$HOME/.keys/novelspeak-release.env`, readable only by you:

```bash
# $HOME/.keys/novelspeak-release.env
export NOVELSPEAK_KS_PASS='<store password>'
export NOVELSPEAK_KEY_PASS='<key password>'
```

```bash
chmod 600 "$HOME/.keys/novelspeak-release.env"
```

Never commit the keystore, the password file, or the passwords. Both are covered by
`.gitignore`, but keep them outside the working tree anyway.

## Build environment

`build.sh` needs:

- JDK 17+ (`java`, `keytool`, `jar`)
- Kotlin compiler (`kotlinc`)
- Android SDK build-tools (`aapt2`, `d8`, `apksigner`, `zipalign`)
- `android.jar` for API 34
- Dependency jars/AAR in `deps/` (see README): `sherpa.aar`, `commons-compress.jar`,
  `commons-io.jar`; `r8.jar` is optional and preferred on Termux

Set the paths for this machine:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export BUILD_TOOLS="$ANDROID_HOME/build-tools/36.0.0"
export PATH="$BUILD_TOOLS:$PATH"
export DEPS="$PWD/deps"
export ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
export KOTLIN_STDLIB="/path/to/kotlinc/lib/kotlin-stdlib.jar"
```

## Per-release checklist

1. Update `android:versionCode` (increment) and `android:versionName` in
   `app/AndroidManifest.xml`.
2. Add the release section to `CHANGELOG.md` and write `docs/releases/v<version>.md`.
3. Build the release APK from a clean tree:

   ```bash
   source "$HOME/.keys/novelspeak-release.env"
   VERSION="1.0.0"
   KEYSTORE="$HOME/.keys/novelspeak-release.jks" \
   KS_ALIAS="novelspeak" \
   KS_PASS="$NOVELSPEAK_KS_PASS" \
   KEY_PASS="$NOVELSPEAK_KEY_PASS" \
   ZIPALIGN="$BUILD_TOOLS/zipalign" \
   OUT_APK="build/NovelSpeak-$VERSION-arm64-v8a.apk" \
   bash ./build.sh
   ```

   The build zipaligns before signing and verifies the signature. Pass
   `ABI=armeabi-v7a` to produce a 32-bit APK instead.

4. Verify the artifact and record the checksum:

   ```bash
   APK="build/NovelSpeak-$VERSION-arm64-v8a.apk"
   "$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$APK"
   "$BUILD_TOOLS/aapt2" dump badging "$APK" | head -5
   unzip -t "$APK" >/dev/null
   sha256sum "$APK" > "$APK.sha256"
   cat "$APK.sha256"
   ```

   Check that `versionCode`/`versionName`, `minSdkVersion 24`, and the arm64 native
   libraries are correct. Put the checksum in the release notes.

5. Commit the version bump, changelog, and release notes:

   ```bash
   git add app/AndroidManifest.xml CHANGELOG.md docs/releases README.md
   git commit -m "release: NovelSpeak $VERSION"
   ```

6. Tag and publish:

   ```bash
   git tag -a "v$VERSION" -m "NovelSpeak $VERSION"
   git push origin main
   git push origin "v$VERSION"
   gh release create "v$VERSION" \
     "$APK" "$APK.sha256" \
     --title "NovelSpeak $VERSION" \
     --notes-file "docs/releases/v$VERSION.md" \
     --latest
   ```

7. Confirm the release page lists the APK and checksum and is marked **Latest**.

## Notes

- The first public release uses a new release key. Users with a debug-signed build must
  uninstall it before installing a release build.
- Debug builds still use `build/debug.keystore` by default and are unaffected by the release
  variables above.
- `build/`, APKs, and signing material are ignored by git.
