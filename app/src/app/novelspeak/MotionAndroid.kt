package app.novelspeak

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings

/** Reads the platform's animator-duration scale. */
object MotionAndroid {

    fun animationsEnabled(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 26) {
            ValueAnimator.areAnimatorsEnabled()
        } else {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }
    } catch (t: Throwable) {
        true
    }
}
