package app.novelspeak.cast

import android.content.Context
import app.novelspeak.library.LibraryStorage

/**
 * [LibraryStorage] backed by one string preference, separate from the library document.
 *
 * Cast data is small and private; keeping it in the same preference file as the library means the
 * app's existing backup/clear behaviour covers it without a second file format.
 */
class PrefsCastStorage(context: Context) : LibraryStorage {

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
            // Best-effort: a lost cast update must never interrupt playback.
        }
    }

    companion object {
        const val KEY = "cast_v1"
        const val PREFS_FILE = "novelspeak"
    }
}
