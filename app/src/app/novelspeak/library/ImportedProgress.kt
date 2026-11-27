package app.novelspeak.library

/**
 * Imported-book finished-state policy, kept independent of Android's file storage so it can be
 * host-tested alongside LibraryStore's equivalent rule.
 *
 * A chapter entry un-finishes when it is re-read from before the end; imported books must do the
 * same, otherwise a finished book that is opened and restarted stays in the Finished filter
 * forever.
 */
object ImportedProgress {

    fun finishedAfterProgress(wasFinished: Boolean, position: Int, total: Int): Boolean {
        if (!wasFinished) return false
        if (total <= 1) return true
        return position >= total - 1
    }
}
