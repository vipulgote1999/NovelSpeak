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
- `android.jar` for API 36 (the Play target; API 34 also works for resource linking on Termux)
- Dependency jars/AAR in `deps/` (see README): `sherpa.aar`, `commons-compress.jar`,
  `commons-io.jar`, `webkit-classes.jar`, `annotation.jar`; `r8.jar` is optional and preferred
  on Termux. Fetch WebKit with `tools/fetch-webkit-deps.sh`.

Set the paths for this machine:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export BUILD_TOOLS="$ANDROID_HOME/build-tools/36.0.0"
export PATH="$BUILD_TOOLS:$PATH"
export DEPS="$PWD/deps"
export ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
export KOTLIN_STDLIB="/path/to/kotlinc/lib/kotlin-stdlib.jar"
```

## Per-release checklist

1. Update `android:versionCode` (increment) and `android:versionName` in
   `app/AndroidManifest.xml`.
2. Add the release section to `CHANGELOG.md` and write `docs/releases/v<version>.md`.
3. Build the release APK (direct distribution) and the Play AAB from a clean tree:

   ```bash
   source "$HOME/.keys/novelspeak-release.env"
   VERSION="1.4.0"
   KEYSTORE="$HOME/.keys/novelspeak-release.jks" \
   KS_ALIAS="novelspeak" \
   KS_PASS="$NOVELSPEAK_KS_PASS" \
   KEY_PASS="$NOVELSPEAK_KEY_PASS" \
   ZIPALIGN="$BUILD_TOOLS/zipalign" \
   OUT_APK="build/NovelSpeak-$VERSION-arm64-v8a.apk" \
   bash ./build.sh

   KEYSTORE="$HOME/.keys/novelspeak-release.jks" \
   KS_ALIAS="novelspeak" \
   KS_PASS="$NOVELSPEAK_KS_PASS" \
   KEY_PASS="$NOVELSPEAK_KEY_PASS" \
   OUT_AAB="build/NovelSpeak-$VERSION.aab" \
   bash tools/build-aab.sh
   ```

   `build.sh` stores native libraries uncompressed, zipaligns them with `-P 16` and
   verifies the signature. `tools/build-aab.sh` re-links resources in proto format,
   writes a 16 KB-aligned bundle config and signs the AAB with the upload key.

4. Verify both artifacts and record checksums:

   ```bash
   APK="build/NovelSpeak-$VERSION-arm64-v8a.apk"
   AAB="build/NovelSpeak-$VERSION.aab"
   "$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$APK"
   "$BUILD_TOOLS/aapt2" dump badging "$APK" | head -8
   "$BUILD_TOOLS/zipalign" -c -P 16 -v 4 "$APK" | tail -1
   jarsigner -verify -verbose -certs "$AAB" | grep 'jar verified'
   sha256sum "$APK" "$AAB" | tee build/SHA256SUMS
   ```

   Check that `versionCode`/`versionName`, `minSdkVersion 24`, `targetSdkVersion 36`,
   the adaptive icon, and the uncompressed arm64 native libraries are correct. Put
   both checksums in the release notes.

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
     "$APK" "$AAB" build/SHA256SUMS \
     --title "NovelSpeak $VERSION" \
     --notes-file "docs/releases/v$VERSION.md" \
     --latest
   ```

7. Confirm the release page lists the APK, AAB and checksums and is marked **Latest**.

## Play Store upload

1. Upload `build/NovelSpeak-$VERSION.aab` to the Play Console (internal testing first).
2. Keep Play App Signing enabled. The AAB above is signed with the upload key; Google
   re-signs the delivered APKs.
3. Complete every **App content** declaration using
   [`docs/play-store/PLAY_CONSOLE.md`](docs/play-store/PLAY_CONSOLE.md): privacy policy,
   ads (No), app access (no login), target audience (18+ and restrict minor access),
   content rating (unrestricted web access = Yes), data safety, and the media-playback
   foreground service declaration.
4. Use the store listing copy and graphics in [`docs/play-store/`](docs/play-store/).
5. Run the pre-launch report on at least one Android 16 device or emulator and check the
   Play Console policy warnings before rolling out.

## Notes

- The first public release uses a new release key. Users with a debug-signed build must
  uninstall it before installing a release build.
- Debug builds still use `build/debug.keystore` by default and are unaffected by the release
  variables above.
- `build/`, APKs, and signing material are ignored by git.
- The Play release manifest sets `android:enableOnBackInvokedCallback="false"` so the custom
  WebView back stack keeps working on Android 16. Migrate to `OnBackInvokedDispatcher` before
  removing this opt-out.
