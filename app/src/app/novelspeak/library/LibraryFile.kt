package app.novelspeak.library

/**
 * Hand-rolled persistence shared by [LibraryStore], [BookmarkStore] and [ReadingStats].
 *
 * The stores deliberately share one versioned, line-oriented document instead of one key per
 * feature: they are all handed a [LibraryStorage] and [PrefsLibraryStorage] exposes exactly one
 * SharedPreferences string ("library_v1"), so a single document with independent record types is
 * the only layout where `LibraryStore`, `BookmarkStore` and `ReadingStats` can all be wired to
 * the same storage without clobbering each other.
 *
 * Format v1 — first line `NSLIB 1`, then one record per line, fields separated by a literal tab:
 *
 * ```
 * NSLIB 1
 * L <url> <title> <site> <sentenceIndex> <totalSentences> <updatedAt> <finished 0|1>
 * B <url> <title> <sentenceIndex> <snippet> <createdAt>
 * S <yyyy-MM-dd> <seconds> <sentences>
 * T <totalSeconds> <totalSentences>
 * ```
 *
 * String fields are escaped (`\\`, `\t`, `\n`, `\r`, `\uXXXX`) so titles and snippets can
 * contain anything without breaking the record framing. The format is hand-rolled rather than
 * `org.json` because this code must be unit-testable on a plain JVM (no Android runtime) and must
 * tolerate arbitrarily broken input instead of throwing.
 *
 * Robustness contract:
 *  - a missing, foreign, wrong-version or truncated document parses as empty; no exception ever
 *    escapes [read];
 *  - a single malformed record is skipped, so one bad line does not throw away a whole library;
 *  - every write re-emits a complete, valid document, i.e. the next write heals corruption;
 *  - writes are best-effort: a storage failure is swallowed so playback is never interrupted.
 */
internal object LibraryFile {

    /** Version marker; bump only for an incompatible record change (parse then must stay lenient). */
    const val HEADER = "NSLIB 1"

    private const val REC_LIBRARY = "L"
    private const val REC_BOOKMARK = "B"
    private const val REC_DAY = "S"
    private const val REC_TOTALS = "T"

    private const val FIELDS_LIBRARY = 8
    private const val FIELDS_BOOKMARK = 6
    private const val FIELDS_DAY = 4
    private const val FIELDS_TOTALS = 3

    private val DAY_KEY = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    /** Mutable in-memory view of one complete document. */
    class Document {
        /** Most-recently-updated first (the order records are written in). */
        val entries = ArrayList<LibraryEntry>()

        /** Newest first. */
        val bookmarks = ArrayList<Bookmark>()

        /** Local calendar day (`yyyy-MM-dd`) -> accumulated totals. */
        val days = LinkedHashMap<String, DayBucket>()

        /** All-time totals; null means "not recorded yet, derive from the day rows". */
        var totalSeconds: Long? = null
        var totalSentences: Long? = null
    }

    /** Never throws: returns an empty document for anything that is not a readable v1 document. */
    fun read(storage: LibraryStorage): Document {
        val empty = Document()
        val raw = try {
            storage.read()
        } catch (t: Throwable) {
            null
        } ?: return empty
        if (raw.isEmpty()) return empty

        val document = Document()
        var headerSeen = false
        for (line in raw.split('\n')) {
            if (line.isEmpty()) continue
            if (!headerSeen) {
                // A foreign/old document must not be interpreted as library data.
                if (line != HEADER) return empty
                headerSeen = true
                continue
            }
            try {
                parseRecord(line, document)
            } catch (t: Throwable) {
                // A single corrupt record must not discard the rest of the document.
            }
        }
        return if (headerSeen) document else empty
    }

    /** Best-effort: formats [document] and stores it; storage failures are swallowed. */
    fun write(storage: LibraryStorage, document: Document) {
        try {
            storage.write(render(document))
        } catch (t: Throwable) {
            // Local bookkeeping must never break playback.
        }
    }

    /** Renders a valid v1 document. Exposed for the host tests' format checks. */
    fun render(document: Document): String {
        val out = StringBuilder(HEADER.length + 64 + document.entries.size * 96 + document.bookmarks.size * 72)
        out.append(HEADER)
        for (entry in document.entries) {
            out.append('\n').append(REC_LIBRARY)
            appendField(out, entry.url)
            appendField(out, entry.title)
            appendField(out, entry.site)
            appendField(out, entry.sentenceIndex.toString())
            appendField(out, entry.totalSentences.toString())
            appendField(out, entry.updatedAt.toString())
            appendField(out, if (entry.finished) "1" else "0")
        }
        for (bookmark in document.bookmarks) {
            out.append('\n').append(REC_BOOKMARK)
            appendField(out, bookmark.url)
            appendField(out, bookmark.title)
            appendField(out, bookmark.sentenceIndex.toString())
            appendField(out, bookmark.snippet)
            appendField(out, bookmark.createdAt.toString())
        }
        for ((date, bucket) in document.days.toSortedMap()) {
            out.append('\n').append(REC_DAY)
            appendField(out, date)
            appendField(out, bucket.seconds.toString())
            appendField(out, bucket.sentences.toString())
        }
        val totalSeconds = document.totalSeconds
        val totalSentences = document.totalSentences
        if (totalSeconds != null && totalSentences != null) {
            out.append('\n').append(REC_TOTALS)
            appendField(out, totalSeconds.toString())
            appendField(out, totalSentences.toString())
        }
        out.append('\n')
        return out.toString()
    }

    private fun appendField(out: StringBuilder, value: String) {
        out.append('\t').append(escape(value))
    }

    private fun parseRecord(line: String, document: Document) {
        // split() keeps trailing empties, so an empty last string field still yields its slot.
        val parts = line.split('\t')
        when {
            parts[0] == REC_LIBRARY -> parseLibrary(parts, document)
            parts[0] == REC_BOOKMARK -> parseBookmark(parts, document)
            parts[0] == REC_DAY -> parseDay(parts, document)
            parts[0] == REC_TOTALS -> parseTotals(parts, document)
            // Unknown record type: ignored, so a newer version's records are not fatal.
        }
    }

    private fun parseLibrary(parts: List<String>, document: Document) {
        if (parts.size != FIELDS_LIBRARY) return
        val url = unescape(parts[1]) ?: return
        if (url.isEmpty()) return
        val title = unescape(parts[2]) ?: return
        val site = unescape(parts[3]) ?: return
        val sentenceIndex = parts[4].toIntOrNull() ?: return
        val totalSentences = parts[5].toIntOrNull() ?: return
        val updatedAt = parts[6].toLongOrNull() ?: return
        if (sentenceIndex < 0 || totalSentences < 0 || updatedAt < 0L) return
        // Duplicate urls cannot happen through the public API; keep the first (newest) if they do.
        if (document.entries.any { it.url == url }) return
        val finished = parts[7] == "1" || parts[7] == "true"
        document.entries.add(
            LibraryEntry(url, title, site, sentenceIndex, totalSentences, updatedAt, finished)
        )
    }

    private fun parseBookmark(parts: List<String>, document: Document) {
        if (parts.size != FIELDS_BOOKMARK) return
        val url = unescape(parts[1]) ?: return
        if (url.isEmpty()) return
        val title = unescape(parts[2]) ?: return
        val sentenceIndex = parts[3].toIntOrNull() ?: return
        val snippet = unescape(parts[4]) ?: return
        val createdAt = parts[5].toLongOrNull() ?: return
        if (sentenceIndex < 0 || createdAt < 0L) return
        if (document.bookmarks.any { it.url == url && it.createdAt == createdAt }) return
        document.bookmarks.add(Bookmark(url, title, sentenceIndex, snippet, createdAt))
    }

    private fun parseDay(parts: List<String>, document: Document) {
        if (parts.size != FIELDS_DAY) return
        val date = unescape(parts[1]) ?: return
        if (!DAY_KEY.matches(date)) return
        val seconds = parts[2].toLongOrNull() ?: return
        val sentences = parts[3].toLongOrNull() ?: return
        if (seconds < 0L || sentences < 0L) return
        val bucket = document.days[date] ?: DayBucket()
        bucket.seconds = saturatedAdd(bucket.seconds, seconds)
        bucket.sentences = saturatedAdd(bucket.sentences, sentences)
        document.days[date] = bucket
    }

    private fun parseTotals(parts: List<String>, document: Document) {
        if (parts.size != FIELDS_TOTALS) return
        val seconds = parts[1].toLongOrNull() ?: return
        val sentences = parts[2].toLongOrNull() ?: return
        if (seconds < 0L || sentences < 0L) return
        document.totalSeconds = seconds
        document.totalSentences = sentences
    }

    /** Escapes record separators and control characters; plain text is returned unchanged. */
    private fun escape(value: String): String {
        var needsEscaping = false
        for (c in value) {
            if (c == '\\' || c == '\t' || c == '\n' || c == '\r' || c < ' ') {
                needsEscaping = true
                break
            }
        }
        if (!needsEscaping) return value
        val out = StringBuilder(value.length + 8)
        for (c in value) {
            when {
                c == '\\' -> out.append("\\\\")
                c == '\t' -> out.append("\\t")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c < ' ' -> {
                    out.append("\\u")
                    appendHex4(out, c.code)
                }
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** Returns null for a malformed escape so the whole record is skipped. */
    private fun unescape(value: String): String? {
        if (value.indexOf('\\') < 0) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= value.length) return null
            when (value[i + 1]) {
                '\\' -> out.append('\\')
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                'u' -> {
                    if (i + 5 >= value.length) return null
                    var code = 0
                    for (k in 2..5) {
                        val digit = value[i + k].digitToIntOrNull(16) ?: return null
                        code = (code shl 4) or digit
                    }
                    out.append(code.toChar())
                    i += 4
                }
                else -> return null
            }
            i += 2
        }
        return out.toString()
    }

    private fun appendHex4(out: StringBuilder, code: Int) {
        val digits = "0123456789abcdef"
        out.append(digits[(code shr 12) and 0xF])
        out.append(digits[(code shr 8) and 0xF])
        out.append(digits[(code shr 4) and 0xF])
        out.append(digits[code and 0xF])
    }
}

/** One local calendar day of accumulated reading. */
internal class DayBucket(var seconds: Long = 0L, var sentences: Long = 0L)

/**
 * Process-wide monitor for read-modify-write cycles. Every store that shares a storage instance
 * can be called from a background thread (Reader callbacks); without this lock two writes could
 * drop one another's section.
 */
internal val libraryLock = Any()

/** Non-negative saturating addition: a corrupt counter can never wrap around to a negative. */
internal fun saturatedAdd(base: Long, delta: Long): Long {
    val safeBase = base.coerceAtLeast(0L)
    if (delta <= 0L) return safeBase
    return if (safeBase > Long.MAX_VALUE - delta) Long.MAX_VALUE else safeBase + delta
}
