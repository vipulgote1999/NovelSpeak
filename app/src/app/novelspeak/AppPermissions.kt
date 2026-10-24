package app.novelspeak

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Runtime permission and system-settings entry points.
 *
 * Only POST_NOTIFICATIONS is a dangerous permission here (API 33+). Network access, wake locks
 * and the media-playback foreground service are normal permissions granted at install time.
 * NovelSpeak deliberately does not request microphone, camera, location, contacts or storage:
 * pages are read through WebView, and diagnostics/model files use app-private storage or
 * MediaStore scoped storage.
 */
object AppPermissions {
    const val REQ_NOTIFICATIONS = 4101

    fun notificationsGranted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun wasNotificationAsked(ctx: Context): Boolean = Prefs(ctx).notificationsAsked

    fun shouldShowNotificationRationale(activity: Activity): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)

    /**
     * Ask at most once across launches. Returns true when the system dialog was launched.
     * Playback is never blocked on the answer: notifications only control whether the media
     * controls are visible.
     */
    fun requestNotificationsOnce(activity: Activity): Boolean {
        if (notificationsGranted(activity) || wasNotificationAsked(activity)) return false
        requestNotifications(activity)
        return true
    }

    fun requestNotifications(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33 || notificationsGranted(activity)) return
        Prefs(activity).notificationsAsked = true
        try {
            activity.requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        } catch (t: Throwable) {
            DiagLog.exception(activity, "notification permission request failed", t)
        }
    }

    fun openNotificationSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (t: Throwable) {
                DiagLog.exception(ctx, "notification settings intent failed", t)
            }
        }
        openAppSettings(ctx)
    }

    fun openPlaybackChannelSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, PlaybackService.CHANNEL)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (t: Throwable) {
                DiagLog.exception(ctx, "notification channel settings intent failed", t)
            }
        }
        openNotificationSettings(ctx)
    }

    fun openAppSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            DiagLog.exception(ctx, "app settings intent failed", t)
        }
    }

    /**
     * Opens the system battery-optimization list. This does not require the restricted
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission and lets the user choose the app.
     */
    fun openBatterySettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            openAppSettings(ctx)
        }
    }

    fun notificationStatus(ctx: Context): String = when {
        !notificationsGranted(ctx) ->
            "Off · playback controls stay hidden while you read"
        !PlaybackService.channelEnabled(ctx) ->
            "Muted · the playback notification channel is turned off"
        else ->
            "On · controls appear on the lock screen and in the notification shade"
    }

    fun notificationChannelStatus(ctx: Context): String = when {
        Build.VERSION.SDK_INT < 26 -> "On"
        PlaybackService.channelEnabled(ctx) -> "On"
        else -> "Muted in system settings"
    }
}
