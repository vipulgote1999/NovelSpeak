package app.novelspeak

/**
 * Which actions the overflow menu offers, and in what order.
 *
 * Extracted from the Activity so the Home/reader rules are host-testable: the new-tab page must
 * never offer page-only actions (Reload, Copy link, Site settings, Translate), and a reader
 * without queued content must not offer Bookmark sentence or Sleep timer.
 */
object MenuPolicy {

    const val HOME = 1
    const val RELOAD = 2
    const val COPY = 3
    const val SHARE = 4
    const val EXTERNAL = 5
    const val SITE = 6
    const val LIBRARY = 7
    const val CONTINUE = 8
    const val SLEEP = 9
    const val SETTINGS = 10
    const val PRIVACY = 11
    const val BOOKMARK = 12
    const val TRANSLATE = 13
    const val IMPORT = 14
    const val FORWARD = 15

    data class Action(val id: Int, val label: String)

    private fun action(id: Int): Action = when (id) {
        HOME -> Action(HOME, "Home")
        FORWARD -> Action(FORWARD, "Forward")
        RELOAD -> Action(RELOAD, "Reload")
        COPY -> Action(COPY, "Copy link")
        SHARE -> Action(SHARE, "Share link")
        EXTERNAL -> Action(EXTERNAL, "Open in browser")
        SITE -> Action(SITE, "Site settings")
        LIBRARY -> Action(LIBRARY, "Library")
        CONTINUE -> Action(CONTINUE, "Continue reading")
        SLEEP -> Action(SLEEP, "Sleep timer")
        SETTINGS -> Action(SETTINGS, "NovelSpeak settings")
        PRIVACY -> Action(PRIVACY, "About & privacy")
        BOOKMARK -> Action(BOOKMARK, "Bookmark sentence")
        TRANSLATE -> Action(TRANSLATE, "Translate page")
        IMPORT -> Action(IMPORT, "Import EPUB or PDF")
        else -> throw IllegalArgumentException("unknown menu action $id")
    }

    /**
     * @param home true while the new-tab page is the visible surface
     * @param hasContent a chapter or shared text is queued in the reader
     * @param canForward the WebView has a forward history entry
     */
    fun items(home: Boolean, hasContent: Boolean, canForward: Boolean): List<Action> {
        val order = ArrayList<Int>(13)
        if (home) {
            // Everything here must work with no page behind it.
            order.add(IMPORT)
            order.add(LIBRARY)
            order.add(CONTINUE)
            order.add(SETTINGS)
            order.add(PRIVACY)
        } else {
            order.add(HOME)
            if (canForward) order.add(FORWARD)
            order.add(RELOAD)
            order.add(SHARE)
            order.add(COPY)
            order.add(EXTERNAL)
            order.add(SITE)
            if (hasContent) {
                order.add(BOOKMARK)
                order.add(SLEEP)
            }
            order.add(IMPORT)
            order.add(LIBRARY)
            order.add(TRANSLATE)
            order.add(SETTINGS)
            order.add(PRIVACY)
        }
        return order.map(::action)
    }
}
