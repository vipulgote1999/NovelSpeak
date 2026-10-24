package app.novelspeak.library

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Reading totals for one local calendar day; [date] is ISO `yyyy-MM-dd`. */
data class DayStats(
    val date: String,
    val minutes: Int,
    val sentences: Int
)

/**
 * Local-only reading statistics: minutes and sentences per day plus all-time totals.
 *
 * No network, no telemetry — the roadmap's "real usage data without telemetry" requirement. The
 * data lives in the same versioned document as the library ([LibraryFile]), so a plain
 * [InMemoryStorage] makes this class fully JVM-testable.
 *
 * Implementation notes:
 *  - seconds are accumulated internally and only formatted as minutes, so calling
 *    [addReading] once per sentence (5 s each) does not round to zero every time;
 *  - day keys come from [Calendar]/[SimpleDateFormat] with [Locale.US], i.e. the device's local
 *    calendar day; passing an explicit [now] makes midnight rollover testable;
 *  - the last [KEEP_DAYS] days are retained, while [totalMinutes]/[totalSentences] keep counting
 *    pruned days (all-time totals are stored separately and rebuilt from day rows when missing);
 *  - corrupt storage reads as zeroes and is healed by the next write.
 *
 * The `site` argument is accepted for the eventual per-site readout (roadmap P1.8) but is not
 * part of the required read API yet, so it is not persisted in format v1.
 */
class ReadingStats(private val storage: LibraryStorage) {

    /**
     * Adds a chunk of reading. Non-positive values are ignored. [now] defaults to the wall clock
     * and is a parameter so tests can cross midnight deterministically.
     */
    fun addReading(
        seconds: Int,
        sentences: Int,
        site: String,
        now: Long = System.currentTimeMillis()
    ) {
        val addSeconds = seconds.coerceAtLeast(0).toLong()
        val addSentences = sentences.coerceAtLeast(0).toLong()
        if (addSeconds == 0L && addSentences == 0L) return
        synchronized(libraryLock) {
            val document = LibraryFile.read(storage)
            val key = formatDay(now)
            val bucket = document.days[key] ?: DayBucket()
            bucket.seconds = saturatedAdd(bucket.seconds, addSeconds)
            bucket.sentences = saturatedAdd(bucket.sentences, addSentences)
            document.days[key] = bucket
            val storedSeconds = document.totalSeconds
            val storedSentences = document.totalSentences
            if (storedSeconds == null || storedSentences == null) {
                // Older/partial document: rebuild the all-time totals from the day rows (the new
                // bucket is already included, so the incoming delta must not be added twice).
                document.totalSeconds = sumDays(document) { it.seconds }
                document.totalSentences = sumDays(document) { it.sentences }
            } else {
                document.totalSeconds = saturatedAdd(storedSeconds, addSeconds)
                document.totalSentences = saturatedAdd(storedSentences, addSentences)
            }
            pruneOlderThan(document, now)
            LibraryFile.write(storage, document)
        }
    }

    /** Today's totals; a day with no reading reports zeroes. */
    fun today(now: Long = System.currentTimeMillis()): DayStats {
        synchronized(libraryLock) {
            val key = formatDay(now)
            val bucket = LibraryFile.read(storage).days[key]
            return DayStats(
                key,
                minutesOf(bucket?.seconds ?: 0L),
                sentencesOf(bucket?.sentences ?: 0L)
            )
        }
    }

    /**
     * The last [n] local days, oldest first and ending with the day containing [now].
     *
     * Every day is present (zero-filled) so a chart never has holes. [n] is clamped to
     * 0..[KEEP_DAYS]; `lastDays(0)` is empty.
     */
    fun lastDays(n: Int = 7, now: Long = System.currentTimeMillis()): List<DayStats> {
        val count = n.coerceIn(0, KEEP_DAYS)
        if (count == 0) return emptyList()
        synchronized(libraryLock) {
            val document = LibraryFile.read(storage)
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = now
            val keys = ArrayList<String>(count)
            for (i in 0 until count) {
                keys.add(formatDay(calendar.timeInMillis))
                if (i < count - 1) calendar.add(Calendar.DAY_OF_YEAR, -1)
            }
            val result = ArrayList<DayStats>(count)
            for (i in keys.indices.reversed()) {
                val key = keys[i]
                val bucket = document.days[key]
                result.add(
                    DayStats(
                        key,
                        minutesOf(bucket?.seconds ?: 0L),
                        sentencesOf(bucket?.sentences ?: 0L)
                    )
                )
            }
            return result
        }
    }

    /** All-time reading minutes (pruned days included), rounded to the nearest minute. */
    fun totalMinutes(): Int {
        synchronized(libraryLock) {
            val document = LibraryFile.read(storage)
            val seconds = document.totalSeconds ?: sumDays(document) { it.seconds }
            return minutesOf(seconds)
        }
    }

    /** All-time sentence count (pruned days included). */
    fun totalSentences(): Int {
        synchronized(libraryLock) {
            val document = LibraryFile.read(storage)
            val sentences = document.totalSentences ?: sumDays(document) { it.sentences }
            return sentencesOf(sentences)
        }
    }

    private fun sumDays(document: LibraryFile.Document, field: (DayBucket) -> Long): Long {
        var total = 0L
        for (bucket in document.days.values) {
            total = saturatedAdd(total, field(bucket))
        }
        return total
    }

    private fun pruneOlderThan(document: LibraryFile.Document, now: Long) {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.add(Calendar.DAY_OF_YEAR, -(KEEP_DAYS - 1))
        val cutoff = formatDay(calendar.timeInMillis)
        val iterator = document.days.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() < cutoff) iterator.remove()
        }
    }

    companion object {
        /** Number of local days retained in the document (all-time totals are separate). */
        const val KEEP_DAYS = 30
    }
}

private const val DAY_PATTERN = "yyyy-MM-dd"

/** Local calendar day key; ISO order is also chronological order (used for pruning). */
private fun formatDay(millis: Long): String {
    val format = SimpleDateFormat(DAY_PATTERN, Locale.US)
    format.timeZone = TimeZone.getDefault()
    return format.format(Date(millis))
}

/** Rounds to the nearest minute so a 40 s session is not reported as zero. */
private fun minutesOf(seconds: Long): Int {
    val safeSeconds = seconds.coerceAtLeast(0L)
    val minutes = saturatedAdd(safeSeconds, 30L) / 60L
    return minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private fun sentencesOf(sentences: Long): Int =
    sentences.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
