package app.novelspeak.library

/**
 * Column rules for the Library list.
 *
 * The Library keeps one recycled [android.widget.ListView]; at wide widths each adapter row holds
 * two cards side by side. Keeping the rule here makes the breakpoint and pairing host-testable.
 */
object LibraryGrid {

    /** Medium and expanded windows get two columns. */
    const val WIDE_DP = 600

    fun columns(screenWidthDp: Int): Int = if (screenWidthDp >= WIDE_DP) 2 else 1

    /**
     * Pairs items in order. An odd count leaves the last pair's right side null so the adapter can
     * render a spacer instead of stretching the final card across the row.
     */
    fun <T> pairs(items: List<T>): List<Pair<T, T?>> {
        if (items.isEmpty()) return emptyList()
        val out = ArrayList<Pair<T, T?>>((items.size + 1) / 2)
        var i = 0
        while (i < items.size) {
            out.add(items[i] to items.getOrNull(i + 1))
            i += 2
        }
        return out
    }
}
