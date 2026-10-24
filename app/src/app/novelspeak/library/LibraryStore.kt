package app.novelspeak.library

/** Titles are page metadata; bounds keep one pathological page from bloating the preference. */
private const val MAX_TITLE_LENGTH = 300

/** Site labels are host/display names, never full URLs. */
private const val MAX_SITE_LENGTH = 120

/** A bookmark snippet is one sentence by design; hard-bound it for the same reason. */
private const val MAX_SNIPPET_LENGTH = 500

/**
 * A chapter the user has opened, plus the last known reading position.
 *
 * [updatedAt] is epoch millis and is kept strictly increasing across records (two updates in the
 * same millisecond get an artificial +1 ms) so "newest first" ordering is deterministic.
 * [percent] derives the display progress; [finished] is an explicit user/queue state that is not
 * implied by [percent] alone.
 */
data class LibraryEntry(
    val url: String,
    val title: String,
    val site: String,
    val sentenceIndex: Int,
    val totalSentences: Int,
    val updatedAt: Long,
    val finished: Boolean = false
) {
    /**
     * Reading progress as a whole percentage: `sentenceIndex * 100 / totalSentences`, clamped to
     * 0..100, or 0 when [totalSentences] is not positive (unknown/empty chapter).
     *
     * Long arithmetic is used so a chapter with a huge sentence index cannot overflow Int and
     * report a negative percentage.
     */
    val percent: Int
        get() {
            if (totalSentences <= 0) return 0
            val progress = (sentenceIndex.toLong() * 100L) / totalSentences.toLong()
            return progress.coerceIn(0L, 100L).toInt()
        }
}

/** A sentence-level mark inside a chapter. */
data class Bookmark(
    val url: String,
    val title: String,
    val sentenceIndex: Int,
    val snippet: String,
    val createdAt: Long
)

/**
 * Persistence seam for the local library.
 *
 * Deliberately tiny so the stores can run on a plain JVM in `tools/library-test` without any
 * Android class. The shipped implementation is [PrefsLibraryStorage].
 */
interface LibraryStorage {
    /** The current raw document, or null when nothing has been stored yet. Implementations may throw. */
    fun read(): String?

    /** Replaces the whole document. Implementations may throw; callers treat writes as best-effort. */
    fun write(value: String)
}

/** In-memory storage for host tests and one-shot checks. */
class InMemoryStorage(initial: String? = null) : LibraryStorage {
    private var value: String? = initial

    @Synchronized
    override fun read(): String? = value

    @Synchronized
    override fun write(value: String) {
        this.value = value
    }
}

/**
 * Persistent local library: one entry per chapter url, newest first.
 *
 * Semantics:
 *  - [recordProgress] deduplicates by url and moves the entry to the top; a blank [title]/[site]
 *    in a later call never erases previously stored metadata;
 *  - [resumeEntry] is the newest entry that is not [LibraryEntry.finished] — the "Continue
 *    reading" source;
 *  - [markFinished] flips the flag without reordering; re-reading a chapter from before its end
 *    (a later [recordProgress] with `sentenceIndex < totalSentences`) clears it again;
 *  - at most [maxEntries] entries are kept; the least recently updated are dropped on write;
 *  - corrupt/empty storage reads as an empty library and is overwritten by the next write.
 *
 * All methods are thread-safe with respect to other stores sharing the same storage instance.
 * The class is framework-free (no Android imports) so it is covered by a host JVM harness.
 */
class LibraryStore(
    private val storage: LibraryStorage,
    maxEntries: Int = DEFAULT_MAX_ENTRIES
) {

    /** A non-positive cap is treated as 1 so a caller cannot accidentally disable the library. */
    private val capacity: Int = maxEntries.coerceAtLeast(1)

    /**
     * Records (or refreshes) the reading position of one chapter and returns the stored entry.
     *
     * [sentenceIndex] and [totalSentences] are clamped to non-negative values. A blank [url] is
     * not persisted (there is nothing to key it on) but a synthetic entry is still returned so
     * callers do not need a null check.
     */
    fun recordProgress(
        url: String,
        title: String,
        site: String,
        sentenceIndex: Int,
        totalSentences: Int
    ): LibraryEntry {
        val cleanUrl = url.trim()
        val safeTotal = totalSentences.coerceAtLeast(0)
        val safeIndex = sentenceIndex.coerceAtLeast(0)
        val now = System.currentTimeMillis()
        if (cleanUrl.isEmpty()) {
            return LibraryEntry(
                cleanUrl,
                clampText(title, MAX_TITLE_LENGTH),
                clampText(site, MAX_SITE_LENGTH),
                safeIndex,
                safeTotal,
                now
            )
        }
        synchronized(libraryLock) {
            val document = LibraryFile.read(storage)
            val previous = document.entries.firstOrNull { it.url == cleanUrl }
            val resolvedTitle = clampText(
                title.takeIf { it.isNotBlank() } ?: previous?.title ?: "",
                MAX_TITLE_LENGTH
            )
            val resolvedSite = clampText(
                site.takeIf { it.isNotBlank() } ?: previous?.site ?: "",
                MAX_SITE_LENGTH
            )
            // Re-reading from before the end of the chapter un-finishes it; an unknown total
            // (0) cannot prove the chapter is finished, so keep the flag in that case.
            val stillAtEnd = safeTotal <= 0 || safeIndex >= safeTotal
            val finished = previous?.finished == true && stillAtEnd
            val updatedAt = nextTimestamp(
                document.entries.maxOfOrNull { it.updatedAt } ?: 0L,
                now
            )
            val entry = LibraryEntry(
                cleanUrl,
                resolvedTitle,
                resolvedSite,
                safeIndex,
                safeTotal,
                updatedAt,
                finished
            )
            document.entries.removeAll { it.url == cleanUrl }
            document.entries.add(0, entry)
            while (document.entries.size > capacity) {
                document.entries.removeAt(document.entries.size - 1)
            }
            LibraryFile.write(storage, document)
            return entry
        }
    }

    /** Newest unfinished entry, i.e. what "Continue reading" should open; null when empty. */
    fun resumeEntry(): LibraryEntry? = load().firstOrNull { !it.finished }

    /** All entries, most recently updated first. */
    fun listEntries(): List<LibraryEntry> = load()

    /** One entry by exact url, or null. */
    fun getEntry(url: String): LibraryEntry? {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return null
        return load().firstOrNull { it.url == cleanUrl }
    }

    /** Removes one url; true when an entry was actually removed. */
    fun remove(url: String): Boolean {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return false
        var removed = false
        synchronized(libraryLock) {
            val document = LibraryFile.read(storage)
            val before = document.entries.size
            document.entries.removeAll { it.url == cleanUrl }
            removed = document.entries.size != before
            if (removed) LibraryFile.write(storage, document)
        }
        return removed
    }

    /** Marks an existing entry finished; unknown urls are ignored. Does not change [updatedAt]. */
    fun markFinished(url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        synchronized(libraryLock) {
            val document = LibraryFile.read(storage)
            val index = document.entries.indexOfFirst { it.url == cleanUrl }
            if (index >= 0) {
                document.entries[index] = document.entries[index].copy(finished = true)
                LibraryFile.write(storage, document)
            }
        }
    }

    /** Removes every library entry. Bookmarks and reading stats stored alongside are untouched. */
    fun clear() {
        synchronized(libraryLock) {
            val document = LibraryFile.read(storage)
            document.entries.clear()
            LibraryFile.write(storage, document)
        }
    }

    private fun load(): List<LibraryEntry> =
        LibraryFile.read(storage).entries.sortedByDescending { it.updatedAt }.take(capacity)

    companion object {
        /** Default library size; enough for a long serial, small enough for one preference string. */
        const val DEFAULT_MAX_ENTRIES = 200
    }
}

/**
 * Sentence bookmarks, newest first.
 *
 * One mark per (url, sentence) pair: bookmarking the same sentence again updates the snippet and
 * timestamp instead of creating a duplicate. [list] can be filtered by url. Removing is keyed by
 * (url, createdAt) because the timestamp is unique — it is the identity a UI row holds.
 *
 * Shares the storage document with [LibraryStore]/[ReadingStats]; each store only rewrites its
 * own records.
 */
class BookmarkStore(private val storage: LibraryStorage) {

    /**
     * Adds a bookmark and returns it, or null when [url] is blank.
     *
     * Re-adding the same (url, [sentenceIndex]) replaces the previous mark.
     */
    fun add(url: String, title: String, sentenceIndex: Int, snippet: String): Bookmark? {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return null
        val safeIndex = sentenceIndex.coerceAtLeast(0)
        val now = System.currentTimeMillis()
        var bookmark: Bookmark? = null
        synchronized(libraryLock) {
            val document = LibraryFile.read(storage)
            document.bookmarks.removeAll { it.url == cleanUrl && it.sentenceIndex == safeIndex }
            val created = Bookmark(
                cleanUrl,
                clampText(title, MAX_TITLE_LENGTH),
                safeIndex,
                clampText(snippet, MAX_SNIPPET_LENGTH),
                nextTimestamp(document.bookmarks.maxOfOrNull { it.createdAt } ?: 0L, now)
            )
            document.bookmarks.add(0, created)
            LibraryFile.write(storage, document)
            bookmark = created
        }
        return bookmark
    }

    /** Bookmarks, newest first; [url] filters to one chapter when non-null. */
    fun list(url: String? = null): List<Bookmark> {
        synchronized(libraryLock) {
            val all = LibraryFile.read(storage).bookmarks
            val filtered = if (url == null) {
                all.toList()
            } else {
                val cleanUrl = url.trim()
                all.filter { it.url == cleanUrl }
            }
            return filtered.sortedByDescending { it.createdAt }
        }
    }

    /** Removes the bookmark identified by (url, [createdAt]); true when something was removed. */
    fun remove(url: String, createdAt: Long): Boolean {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return false
        var removed = false
        synchronized(libraryLock) {
            val document = LibraryFile.read(storage)
            val before = document.bookmarks.size
            document.bookmarks.removeAll { it.url == cleanUrl && it.createdAt == createdAt }
            removed = document.bookmarks.size != before
            if (removed) LibraryFile.write(storage, document)
        }
        return removed
    }

    /** Removes every bookmark. Library entries and reading stats stored alongside are untouched. */
    fun clear() {
        synchronized(libraryLock) {
            val document = LibraryFile.read(storage)
            document.bookmarks.clear()
            LibraryFile.write(storage, document)
        }
    }
}

/**
 * Strictly increasing wall-clock stamp: leaves [now] alone when it moves forward, otherwise bumps
 * the previously stored maximum by one millisecond.
 */
private fun nextTimestamp(latest: Long, now: Long): Long = when {
    now > latest -> now
    latest == Long.MAX_VALUE -> latest
    else -> latest + 1L
}

private fun clampText(value: String, maxLength: Int): String =
    if (value.length <= maxLength) value else value.substring(0, maxLength)
