package app.novelspeak

/**
 * Back precedence for the reader shell.
 *
 * Kept Android-free so the ordering is host-testable. The important rule: an active address
 * edit (including its suggestion popup) must be cancelled before the Home tab is allowed to
 * leave, and an imported book owns the first Back because its hidden WebView has no matching
 * history entry.
 */
object BackPolicy {

    enum class Action {
        LEAVE_IMPORT,
        EXIT_EDIT,
        DISMISS_SUGGESTIONS,
        EXIT_HOME,
        WEB_BACK,
        EXIT_APP
    }

    fun decide(
        imported: Boolean,
        editing: Boolean,
        suggestionsShowing: Boolean,
        home: Boolean,
        canGoBack: Boolean
    ): Action = when {
        imported -> Action.LEAVE_IMPORT
        editing -> Action.EXIT_EDIT
        suggestionsShowing -> Action.DISMISS_SUGGESTIONS
        home -> Action.EXIT_HOME
        canGoBack -> Action.WEB_BACK
        else -> Action.EXIT_APP
    }
}
