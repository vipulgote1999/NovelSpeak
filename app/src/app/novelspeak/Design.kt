package app.novelspeak

/**
 * Shared design tokens for the programmatic View UI.
 *
 * The app deliberately has no AndroidX/Material dependency; these constants are the single source
 * of truth for spacing, radii, touch targets, motion and adaptive content width. Screens migrate
 * to them incrementally, starting with Home, the player and Library.
 */
object Design {

    // 4/8 dp rhythm.
    const val SPACE_XS = 4
    const val SPACE_SM = 8
    const val SPACE_MD = 12
    const val SPACE_LG = 16
    const val SPACE_XL = 24
    const val SPACE_XXL = 32
    const val SPACE_HUGE = 48

    // Radii in dp.
    const val RADIUS_SM = 8
    const val RADIUS_MD = 14
    const val RADIUS_LG = 20
    const val RADIUS_PILL = 999

    // Touch targets.
    const val TOUCH_DP = 48
    const val DENSE_TOUCH_DP = 44

    // Reading surfaces keep a finite measure on tablets.
    const val MAX_CONTENT_DP = 720

    // Motion, in milliseconds.
    const val MOTION_FAST = 140
    const val MOTION_NORMAL = 220
    const val MOTION_SLOW = 320

    /**
     * Horizontal gutter for a window width. Compact phones get 20 dp, medium/expanded 24 dp.
     * The content cap, not a larger gutter, is what keeps tablets readable.
     */
    fun gutter(widthDp: Int): Int = when {
        widthDp >= 600 -> 24
        else -> 20
    }

    /** Readable content width, capped at [max] and never narrower than 280 dp. */
    fun contentWidth(widthDp: Int, max: Int = MAX_CONTENT_DP): Int {
        val available = widthDp - 2 * gutter(widthDp)
        return available.coerceIn(280, max)
    }

    /** Left/right padding that centers [contentWidth] in the available window. */
    fun contentPadding(widthDp: Int, max: Int = MAX_CONTENT_DP): Int {
        val width = contentWidth(widthDp, max)
        return ((widthDp - width).coerceAtLeast(0) / 2)
    }

    fun isMedium(widthDp: Int): Boolean = widthDp in 600..839

    fun isExpanded(widthDp: Int): Boolean = widthDp >= 840
}
