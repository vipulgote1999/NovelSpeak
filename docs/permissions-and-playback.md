# Permissions, notifications, and background playback

This document describes what NovelSpeak requests, when it asks, and how playback
continues with the screen off. It matches the implementation in
`app/AndroidManifest.xml`, `AppPermissions.kt`, `PlaybackService.kt`, and
`MainActivity.kt`.

## Permission inventory

| Permission | Type | Why it is used |
| --- | --- | --- |
| `INTERNET` | Normal | Load pages, download models and filter lists. |
| `ACCESS_NETWORK_STATE` | Normal | Detect connectivity for model/list downloads. |
| `WAKE_LOCK` | Normal | Keep the CPU awake while TTS is playing with the screen off. |
| `FOREGROUND_SERVICE` | Normal | Run the media-playback service. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Normal | Declare the service's Android 14+ foreground type. |
| `POST_NOTIFICATIONS` | Runtime (API 33+) | Show playback controls on the lock screen and in the shade. |

The app does **not** request `RECORD_AUDIO`, camera, location, contacts, or
storage permissions. Diagnostics and crash logs use MediaStore scoped storage on
API 29+ or the app-specific external directory on older releases. Model files live
in app-private storage. Website permission requests (camera, microphone,
geolocation) remain denied by the WebView; the reader has no feature that needs
them.

## Notification permission behaviour

1. The app never opens the notification dialog on launch or when Settings opens.
2. The first Play tap with content loaded calls
   `AppPermissions.requestNotificationsOnce(activity)`.
3. The `notificationsAsked` preference is persisted before the system dialog is
   launched, so the dialog is shown at most once. Playback is not blocked by the
   answer.
4. If the user denies:
   - the system dialog is not shown again;
   - the player card shows `Playback controls hidden · Allow notifications`;
   - Settings shows the current status and an **Open notification settings** button.
5. If the user later grants the permission (system settings or Settings button),
   `onRequestPermissionsResult` refreshes the media notification immediately.
6. The **Playback controls** notification channel is separate from other channels.
   If the user mutes the channel, Settings reports it and links to the channel
   settings.

## Background playback policy

- `PlaybackService` is declared with
  `android:foregroundServiceType="mediaPlayback"`, `android:exported="false"`, and
  `android:stopWithTask="false"`.
- The service enters the foreground before handling any action, using
  `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` on API 29+.
- A partial wake lock is held only while playback is active and released on pause,
  stop, error, or service destruction.
- Audio focus is requested when playback starts and abandoned when it pauses or
  stops. Transient focus loss pauses the reader.
- The service returns `START_NOT_STICKY`; if the process is killed there is no
  ghost notification or empty playback session.
- `onTaskRemoved` keeps a playing service alive (so playback survives swiping the
  task away) and stops a paused service.
- No exact alarms, broadcast receivers, or unrestricted background services are
  used. The only long-running background component is the media-playback
  foreground service.
- The app does **not** request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Settings
  links to the public battery-optimization list so the user can choose whether to
  exempt NovelSpeak; this avoids a restricted Play permission.

## Media notification and lock-screen controls

The notification uses `Notification.MediaStyle` with the existing
`android.media.MediaSession` token:

- title: chapter title;
- subtitle: playback state plus sentence position (for example `Playing · 7/256`);
- actions: previous, play/pause (dynamic), next, stop;
- compact view: previous, play/pause, next;
- public lock-screen visibility;
- low-importance, silent channel (`Playback controls`);
- content intent returns to `MainActivity`; delete intent stops playback.

`MediaSession` publishes metadata and `PlaybackState` so Bluetooth, Android Auto,
the lock screen, and the system media player can control playback.

## Verification

Useful adb checks after installing a debug/release build:

```bash
# Requested permissions: should not contain RECORD_AUDIO
adb shell dumpsys package app.novelspeak | sed -n '/requested permissions:/,/install permissions:/p'

# Foreground service and notification id
adb shell dumpsys activity services app.novelspeak | grep -E 'PlaybackService|isForeground|foregroundId'

# MediaSession state and metadata
adb shell dumpsys media_session | grep -A 12 'package=app.novelspeak'

# Notification channel and actions
adb shell dumpsys notification --noredact | grep -A 30 'pkg=app.novelspeak'
```

Expected result while reading: `isForeground=true`, `state=PLAYING(3)`, and a
notification with `category=transport`, `actions=4`, and
`channel=novelspeak.playback`.
