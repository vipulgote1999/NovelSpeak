package app.novelspeak

/**
 * Visibility rules for the full player card and the collapsed mini bar.
 *
 * Extracted so the Home/reader state combinations are host-testable: collapsing while paused on
 * Home must not leave an orphaned bar, and a reader with no content must never lose its only
 * expand affordance.
 */
object PlayerChrome {

    fun showCard(collapsed: Boolean, homeVisible: Boolean, playing: Boolean): Boolean =
        !collapsed && (!homeVisible || playing)

    fun showMini(
        collapsed: Boolean,
        homeVisible: Boolean,
        playing: Boolean,
        hasContent: Boolean
    ): Boolean = collapsed && hasContent && (!homeVisible || playing)

    /** The main reading action restarts once a queue exists; "Read" would be a lie. */
    fun readActionLabel(total: Int): String = if (total > 0) "Restart" else "Read"

    fun readActionDescription(total: Int): String =
        if (total > 0) "Restart chapter" else "Read chapter"

    /** The Read/Next/Cast strip and speed controls cannot share a row below 360 dp. */
    fun stackSpeedRow(screenWidthDp: Int, fontScale: Float): Boolean =
        screenWidthDp < 360 || fontScale >= 1.2f

    /**
     * Read/Next/Cast also need their own row below 360 dp or at 1.5x+. At 320 dp and 2x the
     * three labels otherwise squeeze Cast into a 47 dp column and wrap it onto three lines.
     */
    fun stackActionRow(screenWidthDp: Int, fontScale: Float): Boolean =
        screenWidthDp < 360 || fontScale >= 1.5f

    /** Compact transport targets; all meet the dense minimum, Play keeps the primary size. */
    data class TransportTargets(
        val previousDp: Int,
        val playDp: Int,
        val nextDp: Int,
        val stopDp: Int,
        val sleepDp: Int
    )

    fun compactTransportTargets(): TransportTargets = TransportTargets(
        previousDp = Design.TOUCH_DP,
        playDp = Design.PRIMARY_TOUCH_DP,
        nextDp = Design.TOUCH_DP,
        stopDp = Design.DENSE_TOUCH_DP,
        sleepDp = Design.DENSE_TOUCH_DP
    )
}
