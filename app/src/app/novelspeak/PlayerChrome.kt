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
}
