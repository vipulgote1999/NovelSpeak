package app.novelspeak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent

/**
 * Foreground media playback service.
 *
 * TTS keeps running with the screen off, and a MediaStyle notification backed by a MediaSession
 * provides lock-screen/shade/Bluetooth controls. Playback is intentionally not blocked when
 * POST_NOTIFICATIONS is denied: Android still allows the foreground service, but the media
 * notification is hidden by the system. The UI surfaces that state instead of asking again.
 */
class PlaybackService : Service(), Reader.Listener {

    private var session: MediaSession? = null
    private var wake: PowerManager.WakeLock? = null
    private var started = false
    private var stopping = false
    private var hasAudioFocus = false
    private var focusRequest: AudioFocusRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            hasAudioFocus = false
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
                setPlaybackToLocal(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setSessionActivity(openAppIntent())
                // Media-button fallback: on Android 8+ the system sends keys to the last
                // media-button session, or to its remembered receiver when no session owns
                // audio. Without a receiver the key is discarded (AOSP MediaSessionService
                // falls back to mLastMediaButtonReceiverHolder and gives up when it is null).
                if (Build.VERSION.SDK_INT >= 31) {
                    setMediaButtonBroadcastReceiver(
                        ComponentName(this@PlaybackService, MediaButtonReceiver::class.java)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setMediaButtonReceiver(mediaButtonPendingIntent())
                }
                setCallback(object : MediaSession.Callback() {
                    /**
                     * Every media key that reaches the session lands here first. Logged so a
                     * field report can distinguish "Android never routed the key" from
                     * "callback ran but playback state did not change".
                     */
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        @Suppress("DEPRECATION")
                        val ke = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
                        DiagLog.line(
                            this@PlaybackService,
                            "media key code=${ke?.keyCode} action=${ke?.action} " +
                                "repeat=${ke?.repeatCount} playing=" +
                                NovelSpeakApp.instance.reader.state().playing
                        )
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }

                    override fun onPlay() {
                        DiagLog.line(this@PlaybackService, "media onPlay")
                        play()
                    }

                    override fun onPause() {
                        DiagLog.line(this@PlaybackService, "media onPause")
                        pause()
                    }

                    override fun onStop() {
                        DiagLog.line(this@PlaybackService, "media onStop")
                        stopPlayback()
                    }

                    override fun onSkipToNext() {
                        DiagLog.line(this@PlaybackService, "media onSkipToNext")
                        nextSentence()
                    }

                    override fun onSkipToPrevious() {
                        DiagLog.line(this@PlaybackService, "media onSkipToPrevious")
                        prevSentence()
                    }
                })
                isActive = true
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "novelspeak:playback"
            ).apply { setReferenceCounted(false) }
            NovelSpeakApp.instance.reader.addListener(this)
            updateSession(NovelSpeakApp.instance.reader.state())
            updateMetadata(NovelSpeakApp.instance.reader.state())
        } catch (t: Throwable) {
            DiagLog.exception(this, "service onCreate failed", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (!started) {
                started = startForegroundSafely(
                    buildNotification(NovelSpeakApp.instance.reader.state())
                )
                DiagLog.line(
                    this,
                    "startForeground ok=$started notifications=" +
                        AppPermissions.notificationsGranted(this) +
                        " channel=" + channelEnabled(this)
                )
                if (!started) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }

            when (intent?.action) {
                ACTION_PLAY -> play()
                ACTION_PAUSE -> pause()
                ACTION_STOP -> stopPlayback()
                ACTION_NEXT -> nextSentence()
                ACTION_PREV -> prevSentence()
                ACTION_MEDIA_BUTTON -> handleMediaKey(
                    intent.getIntExtra(EXTRA_MEDIA_KEY, -1),
                    intent.getIntExtra(EXTRA_MEDIA_REPEAT, 0)
                )
                ACTION_REFRESH -> {
                    val state = NovelSpeakApp.instance.reader.state()
                    if (state.playing || state.status.startsWith("Paused")) {
                        refreshNotification(state)
                    } else {
                        stopSelfSafely()
                    }
                }
                null -> {
                    if (!NovelSpeakApp.instance.reader.state().playing) stopSelfSafely()
                }
            }
        } catch (t: Throwable) {
            DiagLog.exception(this, "service onStartCommand failed", t)
            stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    override fun onReaderState(state: Reader.State) {
        try {
            if (state.playing && !stopping) {
                if (wake?.isHeld != true) {
                    try {
                        wake?.acquire(6 * 60 * 60 * 1000L)
                    } catch (t: Throwable) {
                    }
                }
            } else {
                releaseWake()
                abandonFocus()
            }
            updateSession(state)
            updateMetadata(state)
            if (started && !stopping) refreshNotification(state)
        } catch (t: Throwable) {
            DiagLog.exception(this, "notification update failed", t)
        }
    }

    override fun onHighlight(blockIndex: Int) {}

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!NovelSpeakApp.instance.reader.state().playing) stopSelfSafely()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopping = true
        try {
            NovelSpeakApp.instance.reader.removeListener(this)
        } catch (t: Throwable) {
        }
        abandonFocus()
        releaseWake()
        try {
            session?.release()
        } catch (t: Throwable) {
        }
        session = null
        started = false
        try {
            stopForeground(true)
        } catch (t: Throwable) {
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ transport

    private fun play() {
        requestFocus()
        NovelSpeakApp.instance.reader.play()
    }

    private fun pause() = NovelSpeakApp.instance.reader.pause()

    private fun toggle() {
        if (NovelSpeakApp.instance.reader.state().playing) pause() else play()
    }

    private fun nextSentence() = NovelSpeakApp.instance.reader.nextSentence()

    private fun prevSentence() = NovelSpeakApp.instance.reader.prevSentence()

    private fun stopPlayback() {
        stopping = true
        NovelSpeakApp.instance.reader.stop()
        stopSelfSafely()
    }

    /**
     * Entry point for a key that arrived through [MediaButtonReceiver] (session inactive: app
     * backgrounded/stopped). Key repeats are ignored so a held headset button does not toggle
     * repeatedly.
     */
    private fun handleMediaKey(code: Int, repeatCount: Int) {
        DiagLog.line(
            this,
            "media key dispatch code=$code repeat=$repeatCount " +
                "content=${NovelSpeakApp.instance.reader.hasContent()}"
        )
        if (code < 0) return
        if (!NovelSpeakApp.instance.reader.hasContent()) {
            openUi()
            return
        }
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> pause()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> if (repeatCount == 0) toggle()
            KeyEvent.KEYCODE_MEDIA_NEXT -> nextSentence()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> prevSentence()
            KeyEvent.KEYCODE_MEDIA_STOP -> stopPlayback()
        }
    }

    private fun openUi() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        } catch (t: Throwable) {
            DiagLog.exception(this, "media key open UI failed", t)
        }
    }

    /**
     * Pre-API-31 fallback used by [MediaSession.setMediaButtonReceiver]. The system fills the
     * KeyEvent into this intent, so it must be mutable (pre-31 PendingIntents are mutable by
     * default); on API 31+ [MediaSession.setMediaButtonBroadcastReceiver] is used instead.
     */
    private fun mediaButtonPendingIntent(): PendingIntent {
        val i = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setClass(this, MediaButtonReceiver::class.java)
        return PendingIntent.getBroadcast(
            this,
            0,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun requestFocus() {
        if (hasAudioFocus) return
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
                hasAudioFocus = am.requestAudioFocus(req) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                hasAudioFocus = am.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
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
        } catch (t: Throwable) {
        }
        focusRequest = null
        hasAudioFocus = false
    }

    private fun releaseWake() {
        try {
            if (wake?.isHeld == true) wake?.release()
        } catch (t: Throwable) {
        }
    }

    private fun updateSession(state: Reader.State) {
        val speed = NovelSpeakApp.instance.prefs.speed.coerceIn(0.5f, 2.5f)
        val playback = when {
            state.playing -> PlaybackState.STATE_PLAYING
            state.total == 0 && state.status == "Idle" -> PlaybackState.STATE_NONE
            state.status.startsWith("Finished") ||
                state.status.startsWith("Stopped") ||
                state.status.contains("error", ignoreCase = true) ->
                PlaybackState.STATE_STOPPED
            else -> PlaybackState.STATE_PAUSED
        }
        val pb = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(
                playback,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                speed,
                System.currentTimeMillis()
            )
            .build()
        session?.setPlaybackState(pb)
    }

    private fun updateMetadata(state: Reader.State) {
        val title = state.title.ifEmpty { "NovelSpeak" }
        val subtitle = if (state.total > 0) {
            "${state.status} · ${state.cursor + 1}/${state.total}"
        } else {
            state.status
        }
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "NovelSpeak")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, "Read aloud")
            .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, (state.cursor + 1).toLong())
            .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, state.total.toLong())
            .build()
        session?.setMetadata(metadata)
    }

    private fun refreshNotification(state: Reader.State) {
        if (!started || stopping) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(state))
        } catch (t: Throwable) {
            DiagLog.exception(this, "notification notify failed", t)
        }
    }

    private fun startForegroundSafely(n: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID,
                    n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
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
        stopping = true
        try {
            stopForeground(true)
        } catch (t: Throwable) {
        }
        stopSelf()
    }

    private fun buildNotification(state: Reader.State): Notification {
        val title = state.title.ifEmpty { "NovelSpeak" }
        val text = if (state.total > 0) {
            "${state.status} · ${state.cursor + 1}/${state.total}"
        } else {
            state.status
        }
        val playPause = if (state.playing) {
            action(android.R.drawable.ic_media_pause, "Pause", ACTION_PAUSE)
        } else {
            action(android.R.drawable.ic_media_play, "Play", ACTION_PLAY)
        }

        return try {
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CHANNEL)
            } else {
                Notification.Builder(this)
            }
            builder
                .setContentTitle(title)
                .setContentText(text)
                .setSubText("NovelSpeak")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(openAppIntent())
                .setDeleteIntent(servicePendingIntent(ACTION_STOP))
                .setOngoing(state.playing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setAutoCancel(false)
                .addAction(
                    action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREV)
                )
                .addAction(playPause)
                .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
                .addAction(
                    action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP)
                )

            if (Build.VERSION.SDK_INT < 26) {
                builder.setPriority(Notification.PRIORITY_LOW)
                builder.setDefaults(0)
                builder.setSound(null)
                builder.setVibrate(null)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
            session?.let { s ->
                builder.setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(s.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
            }
            builder.build()
        } catch (t: Throwable) {
            DiagLog.exception(this, "notification build failed", t)
            val fallback = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CHANNEL)
            } else {
                Notification.Builder(this)
            }
            fallback
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .build()
        }
    }

    private fun action(icon: Int, title: String, action: String): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, icon),
            title,
            servicePendingIntent(action)
        ).build()

    /**
     * Explicit, immutable PendingIntent. Keeping the target component fixed means a notification
     * action can only ever reach this non-exported service.
     */
    private fun servicePendingIntent(action: String): PendingIntent {
        val i = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        return PendingIntent.getActivity(
            this,
            1,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL,
            "Playback controls",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Media controls for read-aloud playback"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL = "novelspeak.playback"
        private const val NOTIF_ID = 42

        const val ACTION_PLAY = "app.novelspeak.PLAY"
        const val ACTION_PAUSE = "app.novelspeak.PAUSE"
        const val ACTION_STOP = "app.novelspeak.STOP"
        const val ACTION_NEXT = "app.novelspeak.NEXT"
        const val ACTION_PREV = "app.novelspeak.PREV"
        const val ACTION_REFRESH = "app.novelspeak.REFRESH"
        const val ACTION_MEDIA_BUTTON = "app.novelspeak.MEDIA_BUTTON"
        const val EXTRA_MEDIA_KEY = "app.novelspeak.extra.MEDIA_KEY"
        const val EXTRA_MEDIA_REPEAT = "app.novelspeak.extra.MEDIA_REPEAT"

        fun start(ctx: Context) {
            val i = Intent(ctx, PlaybackService::class.java).setAction(ACTION_PLAY)
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, PlaybackService::class.java))
            } catch (t: Throwable) {
            }
        }

        /**
         * Forward a headset/Bluetooth media key that arrived as a broadcast.
         *
         * On Android 8+ this path is only used when the session is inactive, so the service is
         * normally not running. Starting a foreground service here is allowed: the system
         * allowlists media-button receivers for a short foreground-service start (see
         * `media_button_receiver_fgs_allowlist_duration_ms` in `dumpsys media_session`).
         */
        fun onMediaButton(ctx: Context, keyCode: Int, repeatCount: Int) {
            val app = ctx.applicationContext as? NovelSpeakApp
            if (app == null || !app.reader.hasContent()) {
                // Nothing queued: bring the reader up so the next press has something to control.
                try {
                    ctx.startActivity(
                        Intent(ctx, MainActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    )
                } catch (t: Throwable) {
                    DiagLog.exception(ctx, "media button open UI failed", t)
                }
                return
            }
            val i = Intent(ctx, PlaybackService::class.java)
                .setAction(ACTION_MEDIA_BUTTON)
                .putExtra(EXTRA_MEDIA_KEY, keyCode)
                .putExtra(EXTRA_MEDIA_REPEAT, repeatCount)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (t: Throwable) {
                DiagLog.exception(ctx, "media button service start failed", t)
                try {
                    ctx.startService(i)
                } catch (ignored: Throwable) {
                }
            }
        }

        /**
         * Re-posts the current notification after the user grants POST_NOTIFICATIONS while a
         * playback session is already running. No-op when nothing is loaded.
         */
        fun refresh(ctx: Context) {
            val state = NovelSpeakApp.instance.reader.state()
            if (!state.playing && !state.status.startsWith("Paused")) return
            try {
                ctx.startService(
                    Intent(ctx, PlaybackService::class.java).setAction(ACTION_REFRESH)
                )
            } catch (t: Throwable) {
                DiagLog.exception(ctx, "notification refresh failed", t)
            }
        }

        fun channelEnabled(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < 26) return true
            return try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = nm.getNotificationChannel(CHANNEL) ?: return true
                channel.importance != NotificationManager.IMPORTANCE_NONE
            } catch (t: Throwable) {
                true
            }
        }
    }
}
