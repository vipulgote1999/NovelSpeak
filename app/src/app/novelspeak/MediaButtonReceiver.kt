package app.novelspeak

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * Manifest media-button receiver.
 *
 * Android 8+ delivers headset/Bluetooth keys to the session that last owned audio playback. When
 * there is no such session it falls back to the *remembered* media button receiver; if an app has
 * none, the key is dropped (AOSP `MediaSessionService.dispatchMediaKeyEventLocked`). NovelSpeak
 * needs this receiver for the restart case: the service was stopped, or the TTS provider owned the
 * audio (system engine before in-process playback), so the framework never picked our session.
 *
 * Contract mirrors Media3's MediaButtonReceiver:
 *  - only `ACTION_MEDIA_BUTTON`
 *  - only `ACTION_DOWN` with `repeatCount == 0` (a held button must not repeat)
 *  - on API 26+ only PLAY / PLAY_PAUSE / HEADSETHOOK, because the service must enter the
 *    foreground immediately and a next/previous key cannot start meaningful playback
 *
 * The actual work happens in [PlaybackService.onMediaButton]; this class stays small so a
 * broadcast never does I/O.
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_MEDIA_BUTTON) {
            DiagLog.line(context, "media button receiver: ignored action=${intent?.action}")
            return
        }
        @Suppress("DEPRECATION")
        val key = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
        if (key == null) {
            DiagLog.line(context, "media button receiver: no KeyEvent extra")
            return
        }
        if (key.action != KeyEvent.ACTION_DOWN || key.repeatCount != 0) {
            return
        }
        DiagLog.line(
            context,
            "media button receiver code=${key.keyCode} repeat=${key.repeatCount}"
        )
        if (android.os.Build.VERSION.SDK_INT >= 26 &&
            key.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY &&
            key.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE &&
            key.keyCode != KeyEvent.KEYCODE_HEADSETHOOK
        ) {
            // The service would have to start in the foreground instantly for a key that cannot
            // do anything useful without a queue; ignore like Media3 does.
            DiagLog.line(context, "media button receiver: code=${key.keyCode} ignored on 26+")
            return
        }
        PlaybackService.onMediaButton(context, key.keyCode, key.repeatCount)
    }
}
