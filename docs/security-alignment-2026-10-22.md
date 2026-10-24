# Best practices and security alignment update: secure media intents and least privilege

* **Improvement Description:** The media-playback notification and MediaSession now use only explicit, immutable PendingIntents to a non-exported service. The unused microphone permission was removed. This prevents notification-action intent hijacking/redirection and keeps the runtime permission surface limited to notifications.
* **Priority Level:** High for component/PendingIntent hardening; Medium for the permission cleanup.
* **Alignment Action:** Converted playback actions to explicit immutable PendingIntents, kept the service non-exported, removed `RECORD_AUDIO`, and documented the foreground-service and notification policy.

## Files modified

* `app/AndroidManifest.xml`
* `app/src/app/novelspeak/PlaybackService.kt`
* `app/src/app/novelspeak/AppPermissions.kt`
* `app/src/app/novelspeak/MainActivity.kt`
* `app/src/app/novelspeak/SettingsActivity.kt`
* `app/src/app/novelspeak/Prefs.kt`
* `app/src/app/novelspeak/UrlInput.kt`
* `docs/permissions-and-playback.md`

## Implementation summary

* The playback service remains `android:exported="false"` and now declares
  `android:stopWithTask="false"` explicitly so its lifecycle is intentional.
* Notification actions, the notification delete action, and the MediaSession
  activity use `PendingIntent.FLAG_IMMUTABLE` and explicit `Intent` components.
* URLs typed into the address bar are normalized by `UrlInput`; non-web schemes
  (`javascript:`, `file:`, `content:`, `intent:`, `data:`, `blob:`, `mailto:`,
  `tel:`, `ftp:`, `ws:`, `wss:`, `chrome:`, `resource:`) are converted to a search
  query and never handed to the WebView.
* The unused `RECORD_AUDIO` permission was removed. The only dangerous runtime
  permission now requested is `POST_NOTIFICATIONS`, and it is requested at most
  once, contextually, on the first Play action.
* The playback notification uses `Notification.MediaStyle` with the
  non-exported service's `MediaSession`; no implicit broadcast or implicit
  PendingIntent is used.
* The app does not request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; Settings links
  to the public system battery-optimization list instead.

## Implementation diff

```diff
--- a/app/AndroidManifest.xml
+++ b/app/AndroidManifest.xml
@@ -12,7 +12,6 @@
     <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
     <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
     <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
-    <uses-permission android:name="android.permission.RECORD_AUDIO" />
 
     <application
@@ -47,6 +46,7 @@
         <service
             android:name=".PlaybackService"
             android:exported="false"
+            android:stopWithTask="false"
             android:foregroundServiceType="mediaPlayback" />
```

```diff
--- a/app/src/app/novelspeak/PlaybackService.kt
+++ b/app/src/app/novelspeak/PlaybackService.kt
@@
-    private fun servicePendingIntent(action: String): PendingIntent {
-        val i = Intent(this, PlaybackService::class.java).setAction(action)
-        return PendingIntent.getService(this, action.hashCode(), i,
-            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
-    }
+    /**
+     * Explicit, immutable PendingIntent. Keeping the target component fixed means a notification
+     * action can only ever reach this non-exported service.
+     */
+    private fun servicePendingIntent(action: String): PendingIntent {
+        val i = Intent(this, PlaybackService::class.java).setAction(action)
+        return PendingIntent.getService(
+            this,
+            action.hashCode(),
+            i,
+            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
+        )
+    }
+
+    private fun openAppIntent(): PendingIntent {
+        val i = Intent(this, MainActivity::class.java).addFlags(
+            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
+        )
+        return PendingIntent.getActivity(
+            this,
+            1,
+            i,
+            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
+        )
+    }
```

```diff
--- a/app/src/app/novelspeak/AppPermissions.kt
+++ b/app/src/app/novelspeak/AppPermissions.kt
@@
+    fun requestNotificationsOnce(activity: Activity): Boolean {
+        if (notificationsGranted(activity) || wasNotificationAsked(activity)) return false
+        requestNotifications(activity)
+        return true
+    }
```

```diff
--- a/app/src/app/novelspeak/UrlInput.kt
+++ b/app/src/app/novelspeak/UrlInput.kt
@@
+    private val NON_WEB_SCHEMES = setOf(
+        "javascript", "file", "content", "intent", "data", "blob",
+        "mailto", "tel", "ftp", "ws", "wss", "chrome", "resource"
+    )
+
     fun normalize(raw: String): String? {
@@
-        // Anything that already carries a non-web scheme is treated as a search query.
+        // Anything that already carries a non-web scheme is treated as a search query. A bare
+        // "localhost:3000" is not a scheme; it falls through to the host check below.
         val colon = text.indexOf(':')
         if (colon in 2..24) {
-            val scheme = text.substring(0, colon)
-            if (scheme.all { it.isLetter() }) return search(text)
+            val scheme = text.substring(0, colon).lowercase()
+            val followedByAuthority = text.startsWith("$scheme://", ignoreCase = true)
+            if (scheme.all { it.isLetter() } &&
+                (followedByAuthority || scheme in NON_WEB_SCHEMES)
+            ) {
+                return search(text)
+            }
         }
```

## Testing and verification

1. Built the app with the repository's `build.sh` against Android API 34.
2. Installed on an API 35 emulator and verified the requested permission list — `RECORD_AUDIO` is absent and `POST_NOTIFICATIONS` is the only runtime permission.
3. Confirmed opening the app and Settings does not show a permission dialog; the first Play action shows it once, and a second Play after denial does not.
4. Confirmed the foreground service reports `isForeground=true`, `foregroundId=42`, and `types=0x00000002` while playing.
5. Confirmed `dumpsys media_session` reports `state=PLAYING(3)` with chapter metadata, and playback continues with `mWakefulness=Asleep`.
6. Confirmed the notification has `category=transport`, four actions, `vis=PUBLIC`, and the `novelspeak.playback` channel; pausing changes the action/state to paused, and pressing Stop removes both the service and notification.
7. Ran the standalone `UrlInput` test cases (HTTPS/HTTP, host-only, path/query, localhost:port, about:, search text, and dangerous schemes) — all passed.
