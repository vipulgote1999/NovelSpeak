package app.novelspeak.library

import android.content.Context

/**
 * [LibraryStorage] backed by one string preference.
 *
 * The library, bookmarks and reading statistics intentionally share the single key
 * [KEY] ("library_v1") because [LibraryFile] stores them as independent record types of one
 * versioned document — that is what lets all three stores be handed this same implementation
 * without overwriting each other.
 *
 * It uses the existing "novelspeak" preference file (same file as [app.novelspeak.Prefs]) so the
 * data lands in the backup scope the app already declares and is trivially inspectable/clearable.
 * Reads and writes are best-effort: a failure returns null / is swallowed rather than crashing
 * the reader. The only Android-dependent file in this package; the stores themselves are pure
 * JVM so `tools/library-test` needs no Android runtime.
 */
class PrefsLibraryStorage(context: android.content.Context) : LibraryStorage {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_FILE,
        Context.MODE_PRIVATE
    )

    override fun read(): String? = try {
        prefs.getString(KEY, null)
    } catch (t: Throwable) {
        null
    }

    override fun write(value: String) {
        try {
            prefs.edit().putString(KEY, value).apply()
        } catch (t: Throwable) {
            // Best-effort: losing a bookkeeping update is better than interrupting playback.
        }
    }

    companion object {
        /** The single string preference holding the whole library document. */
        const val KEY = "library_v1"

        /** Same preference file as [app.novelspeak.Prefs] ("novelspeak"). */
        const val PREFS_FILE = "novelspeak"
    }
}
