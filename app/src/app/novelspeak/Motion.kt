package app.novelspeak

/**
 * Pure reduced-motion rules.
 *
 * Android exposes the system animator duration scale; a value of 0 means the user asked for no
 * motion. Keeping the decision in a plain object lets the host tests cover it without an Android
 * runtime. [MotionAndroid] reads the platform setting.
 */
object Motion {

    fun enabled(animatorScale: Float): Boolean = animatorScale > 0f

    /** 0 when the system asks for reduced motion, otherwise the base duration. */
    fun durationMs(baseMs: Int, animatorScale: Float): Long =
        if (enabled(animatorScale)) baseMs.coerceAtLeast(0).toLong() else 0L
}
