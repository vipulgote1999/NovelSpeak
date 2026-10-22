package app.novelspeak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Foreground playback service.
 *
 * This is the actual fix for the Chrome problem: Chrome's "listen to this page" stops when the tab
 * is backgrounded or the screen locks. A media-playback foreground service plus a partial wake lock
 * keeps synthesis and audio running with the screen off, and gives lock-screen/notification
 * controls (MediaSession) for free.
 */
class PlaybackService : Service(), Reader.Listener {

    private var session: MediaSession? = null
    private var wake: PowerManager.WakeLock? = null
    private var started = false
    private var focusRequest: AudioFocusRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            mainHandler.post { NovelSpeakApp.instance.reader.pause() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            session = MediaSession(this, "NovelSpeak").apply {
                setFlags(
                    MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { NovelSpeakApp.instance.reader.play() }
                    override fun onPause() { NovelSpeakApp.instance.reader.pause() }
                    override fun onStop() { NovelSpeakApp.instance.reader.stop() }
                    override fun onSkipToNext() { NovelSpeakApp.instance.reader.nextSentence() }
                    override fun onSkipToPrevious() { NovelSpeakApp.instance.reader.prevSentence() }
                })
                isActive = true
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "novelspeak:playback").apply { setReferenceCounted(false) }
            NovelSpeakApp.instance.reader.addListener(this)
        } catch (t: Throwable) {
            DiagLog.exception(this, "service onCreate failed", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_PAUSE -> NovelSpeakApp.instance.reader.pause()
                ACTION_PLAY -> { requestFocus(); NovelSpeakApp.instance.reader.play() }
                ACTION_STOP -> { NovelSpeakApp.instance.reader.stop(); stopSelfSafely() }
                ACTION_NEXT -> NovelSpeakApp.instance.reader.nextSentence()
                ACTION_PREV -> NovelSpeakApp.instance.reader.prevSentence()
            }
            if (!started) {
                started = true
                val ok = startForegroundSafely(buildNotification("Preparing…"))
                DiagLog.line(this, "startForeground ok=$ok")
                if (!ok) {
                    started = false
                    stopSelfSafely()
                }
            }
        } catch (t: Throwable) {
            DiagLog.exception(this, "service onStartCommand failed", t)
            stopSelfSafely()
        }
        return START_STICKY
    }

    override fun onReaderState(state: Reader.State) {
        try {
            if (state.playing) {
                try { wake?.acquire(6 * 60 * 60 * 1000L) } catch (e: Throwable) {}
                updateSession(PlaybackState.STATE_PLAYING)
            } else {
                try { if (wake?.isHeld == true) wake?.release() } catch (e: Throwable) {}
                updateSession(PlaybackState.STATE_PAUSED)
            }
            if (started) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification(state.status))
            }
        } catch (t: Throwable) {
            DiagLog.exception(this, "notification update failed", t)
        }
    }

    override fun onHighlight(blockIndex: Int) {}

    override fun onDestroy() {
        NovelSpeakApp.instance.reader.removeListener(this)
        abandonFocus()
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Throwable) {}
        session?.release()
        session = null
        started = false
        super.onDestroy()
    }

    private fun requestFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 26) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusListener)
                    .setWillPauseWhenDucked(true)
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (t: Throwable) {
            DiagLog.exception(this, "requestAudioFocus failed", t)
        }
    }

    private fun abandonFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusListener)
            }
        } catch (e: Throwable) {
        }
        focusRequest = null
    }

    private fun updateSession(state: Int) {
        val pb = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        session?.setPlaybackState(pb)
    }

    private fun startForegroundSafely(n: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (t: Throwable) {
            DiagLog.exception(this, "startForeground failed", t)
            false
        }
    }

    private fun stopSelfSafely() {
        try { stopForeground(true) } catch (e: Throwable) {}
        stopSelf()
    }

    private fun buildNotification(text: String): Notification {
        try {
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
            return builder
                .setContentTitle("NovelSpeak")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(action(android.R.drawable.ic_media_previous, "Prev", ACTION_PREV))
                .addAction(action(android.R.drawable.ic_media_pause, "Pause", ACTION_PAUSE))
                .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
                .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP))
                .build()
        } catch (t: Throwable) {
            DiagLog.exception(this, "notification build failed", t)
            val fallback = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
            return fallback
                .setContentTitle("NovelSpeak")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        }
    }

    private fun action(icon: Int, title: String, action: String): Notification.Action {
        val i = Intent(this, PlaybackService::class.java).setAction(action)
        val pi = PendingIntent.getService(this, action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    companion object {
        private const val CHANNEL = "novelspeak.playback"
        private const val NOTIF_ID = 42
        const val ACTION_PLAY = "app.novelspeak.PLAY"
        const val ACTION_PAUSE = "app.novelspeak.PAUSE"
        const val ACTION_STOP = "app.novelspeak.STOP"
        const val ACTION_NEXT = "app.novelspeak.NEXT"
        const val ACTION_PREV = "app.novelspeak.PREV"

        fun start(ctx: Context) {
            val i = Intent(ctx, PlaybackService::class.java).setAction(ACTION_PLAY)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, PlaybackService::class.java))
            } catch (e: Throwable) {}
        }
    }
}
