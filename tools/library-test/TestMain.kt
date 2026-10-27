import app.novelspeak.library.Bookmark
import app.novelspeak.library.BookmarkStore
import app.novelspeak.library.InMemoryStorage
import app.novelspeak.library.LibraryEntry
import app.novelspeak.library.LibraryStorage
import app.novelspeak.library.LibraryStore
import app.novelspeak.library.ReadingStats
import java.util.Calendar
import java.util.Locale

/**
 * Host-side harness for the local library, bookmarks and reading statistics. Not part of the APK.
 *
 * All tested classes are pure JVM (no Android imports): only PrefsLibraryStorage.kt is
 * Android-dependent and it is deliberately not compiled into this harness.
 *
 *   cd /home/vipul/Project/NovelSpeak
 *   kotlinc app/src/app/novelspeak/library/LibraryFile.kt \
 *     app/src/app/novelspeak/library/LibraryStore.kt \
 *     app/src/app/novelspeak/library/ReadingStats.kt \
 *     tools/library-test/TestMain.kt -include-runtime -d /tmp/library-test.jar
 *   java -jar /tmp/library-test.jar
 *
 * Exit 0 = all checks pass; any failure prints FAIL lines and exits 1.
 */
private var checks = 0
private var failures = 0

private fun check(name: String, condition: Boolean, detail: String = "") {
    checks++
    if (condition) {
        println("PASS  " + name)
    } else {
        failures++
        println("FAIL  " + name + (if (detail.isEmpty()) "" else "  (" + detail + ")"))
    }
}

private fun eq(name: String, expected: Any?, actual: Any?) =
    check(name, expected == actual, "expected=<" + expected + "> actual=<" + actual + ">")

/** A LibraryEntry with neutral metadata, for percent/formatting checks. */
private fun entry(sentenceIndex: Int, totalSentences: Int) =
    LibraryEntry("https://example.com/c", "Chapter", "example.com", sentenceIndex, totalSentences, 0L)

/** Local wall-clock instant; the day parts are explicitly local so date keys match the stores. */
private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long {
    val calendar = Calendar.getInstance()
    calendar.clear()
    calendar.set(year, month - 1, day, hour, minute, 0)
    return calendar.timeInMillis
}

/** Independently derived local calendar-day key, matching the documented yyyy-MM-dd format. */
private fun dateKey(millis: Long): String {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = millis
    return String.format(
        Locale.US,
        "%04d-%02d-%02d",
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH)
    )
}

private fun testPercent() {
    println()
    println("== percent ==")
    eq("percent 42/100", 42, entry(42, 100).percent)
    eq("percent 0/0 unknown total", 0, entry(0, 0).percent)
    eq("percent positive index unknown total", 0, entry(5, 0).percent)
    eq("percent negative total", 0, entry(5, -10).percent)
    eq("percent negative index clamps low", 0, entry(-5, 100).percent)
    eq("percent exactly done", 100, entry(100, 100).percent)
    eq("percent past end clamps high", 100, entry(150, 100).percent)
    eq("percent int overflow safe", 100, entry(Int.MAX_VALUE, Int.MAX_VALUE).percent)
    eq("percent huge index tiny total", 100, entry(Int.MAX_VALUE, 1).percent)
    eq("percent tiny index huge total", 0, entry(1, Int.MAX_VALUE).percent)
    eq("finished defaults to false", false, entry(0, 10).finished)
}

private fun testLibraryRecord() {
    println()
    println("== library record / dedupe / ordering ==")
    val storage = InMemoryStorage()
    val store = LibraryStore(storage)

    eq("empty library size", 0, store.listEntries().size)
    eq("empty resume", null, store.resumeEntry())
    eq("empty getEntry", null, store.getEntry("https://a.test/1"))

    val a = store.recordProgress("https://a.test/1", "Chapter 1", "a.test", 5, 10)
    eq("record url", "https://a.test/1", a.url)
    eq("record title", "Chapter 1", a.title)
    eq("record site", "a.test", a.site)
    eq("record sentence index", 5, a.sentenceIndex)
    eq("record total", 10, a.totalSentences)
    eq("record percent", 50, a.percent)
    eq("record finished", false, a.finished)
    check("record timestamp set", a.updatedAt > 0L, "updatedAt=" + a.updatedAt)
    eq("list size after first record", 1, store.listEntries().size)
    eq("getEntry returns stored entry", a, store.getEntry("https://a.test/1"))

    val b = store.recordProgress("https://b.test/2", "Chapter 2", "b.test", 0, 20)
    eq("list size after second record", 2, store.listEntries().size)
    eq(
        "newest first",
        listOf("https://b.test/2", "https://a.test/1"),
        store.listEntries().map { it.url }
    )
    check(
        "library timestamps strictly increase",
        b.updatedAt > a.updatedAt,
        a.updatedAt.toString() + " -> " + b.updatedAt
    )

    val a2 = store.recordProgress("https://a.test/1", "Chapter 1", "a.test", 7, 10)
    eq("dedupe by url keeps two entries", 2, store.listEntries().size)
    eq(
        "updated entry moves to front",
        listOf("https://a.test/1", "https://b.test/2"),
        store.listEntries().map { it.url }
    )
    eq("dedupe keeps a single record", 1, store.listEntries().count { it.url == "https://a.test/1" })
    eq("dedupe updates progress", 70, a2.percent)
    eq("dedupe updates stored index", 7, store.getEntry("https://a.test/1")?.sentenceIndex)

    // A re-extraction with an empty title/site must not erase saved metadata.
    val a3 = store.recordProgress("https://a.test/1", "", "", 8, 10)
    eq("blank title preserves previous title", "Chapter 1", a3.title)
    eq("blank site preserves previous site", "a.test", a3.site)

    // Explicitly changed metadata is stored.
    val a4 = store.recordProgress("https://a.test/1", "Renamed", "a.test", 8, 10)
    eq("changed title is stored", "Renamed", a4.title)
    eq("changed progress percent", 80, a4.percent)

    // Negative input is clamped instead of being persisted as a negative cursor.
    val negative = store.recordProgress("https://neg.test/3", "Neg", "neg.test", -4, -9)
    eq("negative sentence index clamped", 0, negative.sentenceIndex)
    eq("negative total clamped", 0, negative.totalSentences)
    eq("negative clamped percent", 0, negative.percent)

    // A blank url is impossible to key: return an entry but persist nothing.
    val blankStorage = InMemoryStorage()
    val blankStore = LibraryStore(blankStorage)
    val blank = blankStore.recordProgress("   ", "t", "s", 1, 2)
    eq("blank url returns synthetic entry", "t", blank.title)
    eq("blank url not persisted", 0, blankStore.listEntries().size)

    // Url identity is trimmed and exact.
    store.recordProgress("  https://trim.test/x  ", "Trim", "trim.test", 1, 2)
    check("url is trimmed", store.getEntry("https://trim.test/x") != null)
}

private fun testLibraryPersistence() {
    println()
    println("== library persistence ==")
    val storage = InMemoryStorage()
    val store = LibraryStore(storage)
    val written = store.recordProgress("https://p.test/1", "P1", "p.test", 12, 40)
    store.recordProgress("https://p.test/2", "P2", "p.test", 0, 40)

    val reloaded = LibraryStore(storage)
    eq("reload size", 2, reloaded.listEntries().size)
    eq("reload entry equal", written, reloaded.getEntry("https://p.test/1"))
    eq("reload percent", 30, reloaded.getEntry("https://p.test/1")?.percent)
    eq(
        "reload ordering",
        listOf("https://p.test/2", "https://p.test/1"),
        reloaded.listEntries().map { it.url }
    )
    check(
        "document has versioned header",
        storage.read()?.startsWith("NSLIB 1") == true,
        "raw=" + storage.read()
    )
}

private fun testLibraryResume() {
    println()
    println("== resume / finished ==")
    val store = LibraryStore(InMemoryStorage())
    val a = "https://r.test/1"
    val b = "https://r.test/2"
    val c = "https://r.test/3"
    store.recordProgress(a, "A", "r.test", 1, 10)
    store.recordProgress(b, "B", "r.test", 2, 10)
    store.recordProgress(c, "C", "r.test", 3, 10)

    eq("resume newest unfinished", c, store.resumeEntry()?.url)
    store.markFinished(c)
    eq("markFinished flags entry", true, store.getEntry(c)?.finished)
    eq("resume skips finished newest", b, store.resumeEntry()?.url)
    store.markFinished(b)
    eq("resume skips two finished", a, store.resumeEntry()?.url)
    store.markFinished(a)
    eq("resume none when all finished", null, store.resumeEntry())
    eq("list still returns finished entries", 3, store.listEntries().size)

    // Re-reading a finished chapter from its start makes it resumable again.
    store.recordProgress(b, "B", "r.test", 0, 10)
    eq("re-read clears finished flag", false, store.getEntry(b)?.finished)
    eq("resume finds re-read chapter", b, store.resumeEntry()?.url)

    // A progress update that stays at the end preserves the finished flag.
    store.markFinished(b)
    store.recordProgress(b, "B", "r.test", 10, 10)
    eq("finished preserved when still at end", true, store.getEntry(b)?.finished)

    // An unknown total cannot prove the chapter is unfinished, so the flag is kept.
    store.markFinished(c)
    store.recordProgress(c, "C", "r.test", 0, 0)
    eq("finished preserved when total unknown", true, store.getEntry(c)?.finished)

    // markFinished of an unknown url is a harmless no-op.
    store.markFinished("https://missing.test/x")
    eq("markFinished unknown url is a no-op", 3, store.listEntries().size)
}

private fun testLibraryRemoveClear() {
    println()
    println("== remove / clear ==")
    val storage = InMemoryStorage()
    val store = LibraryStore(storage)
    val a = "https://d.test/1"
    val b = "https://d.test/2"
    store.recordProgress(a, "A", "d.test", 1, 10)
    store.recordProgress(b, "B", "d.test", 2, 10)

    eq("remove unknown url", false, store.remove("https://missing.test/x"))
    eq("remove blank url", false, store.remove("   "))
    eq("remove known url", true, store.remove(a))
    eq("remove drops from list", 1, store.listEntries().size)
    eq("remove drops from getEntry", null, store.getEntry(a))
    eq("remove twice is false", false, store.remove(a))
    eq("remove is persistent", 1, LibraryStore(storage).listEntries().size)

    store.clear()
    eq("clear empties list", 0, store.listEntries().size)
    eq("clear empties resume", null, store.resumeEntry())
    store.clear()
    eq("clear is idempotent", 0, store.listEntries().size)
    check(
        "clear leaves a valid document",
        storage.read()?.startsWith("NSLIB 1") == true,
        "raw=" + storage.read()
    )

    // After clear the store still works.
    store.recordProgress(b, "B", "d.test", 3, 10)
    eq("record after clear", 1, LibraryStore(storage).listEntries().size)

    // clear() also heals storage that previously contained garbage.
    val garbage = InMemoryStorage("this is not a library")
    LibraryStore(garbage).clear()
    check(
        "clear heals corrupt storage",
        garbage.read()?.startsWith("NSLIB 1") == true,
        "raw=" + garbage.read()
    )
}

private fun testLibraryCap() {
    println()
    println("== capacity cap ==")
    val storage = InMemoryStorage()
    val store = LibraryStore(storage, maxEntries = 3)
    store.recordProgress("https://k.test/1", "K1", "k.test", 0, 10)
    store.recordProgress("https://k.test/2", "K2", "k.test", 0, 10)
    store.recordProgress("https://k.test/3", "K3", "k.test", 0, 10)
    store.recordProgress("https://k.test/4", "K4", "k.test", 0, 10)

    eq("cap keeps max entries", 3, store.listEntries().size)
    eq(
        "cap drops least recently updated",
        listOf("https://k.test/4", "https://k.test/3", "https://k.test/2"),
        store.listEntries().map { it.url }
    )
    eq("cap drops entry from getEntry", null, store.getEntry("https://k.test/1"))
    eq("cap is persistent", 3, LibraryStore(storage).listEntries().size)

    store.recordProgress("https://k.test/3", "K3", "k.test", 1, 10)
    eq(
        "update on capped store keeps newest order",
        listOf("https://k.test/3", "https://k.test/4", "https://k.test/2"),
        store.listEntries().map { it.url }
    )

    val one = LibraryStore(InMemoryStorage(), maxEntries = 1)
    one.recordProgress("https://o.test/1", "O1", "o.test", 0, 1)
    one.recordProgress("https://o.test/2", "O2", "o.test", 0, 1)
    eq("cap of 1 keeps newest", 1, one.listEntries().size)
    eq("cap of 1 keeps right entry", "https://o.test/2", one.listEntries()[0].url)

    val zero = LibraryStore(InMemoryStorage(), maxEntries = 0)
    zero.recordProgress("https://z.test/1", "Z1", "z.test", 0, 1)
    eq("non-positive cap treated as 1", 1, zero.listEntries().size)
}

private fun testLibraryCorruption() {
    println()
    println("== corruption recovery ==")
    eq("null storage reads empty", 0, LibraryStore(InMemoryStorage(null)).listEntries().size)
    eq("blank storage reads empty", 0, LibraryStore(InMemoryStorage("")).listEntries().size)
    eq(
        "garbage storage reads empty",
        0,
        LibraryStore(InMemoryStorage("this is not a library\nat all")).listEntries().size
    )
    eq("garbage storage resume", null, LibraryStore(InMemoryStorage("garbage")).resumeEntry())

    val seed = InMemoryStorage()
    LibraryStore(seed).recordProgress("https://g.test/1", "G", "g.test", 1, 2)
    val valid = seed.read() ?: ""
    check("seed document valid", valid.startsWith("NSLIB 1"), "raw=" + valid)

    val wrongVersion = valid.replaceFirst("NSLIB 1", "NSLIB 9")
    eq(
        "wrong version reads empty",
        0,
        LibraryStore(InMemoryStorage(wrongVersion)).listEntries().size
    )

    // One malformed record line must not discard the valid entries around it.
    val withBadLines = valid.trimEnd('\n') +
        "\nL\tbroken-record" +
        "\nL\thttps://bad.test/1\ttitle\tsite\tNaN\t10\t0\t0" +
        "\nL\thttps://bad.test/2\ttitle\tsite\t1\t10\t0\t1\textra" +
        "\nB\tbroken-bookmark"
    val badDoc = LibraryStore(InMemoryStorage(withBadLines))
    eq("malformed lines skipped", 1, badDoc.listEntries().size)
    eq("valid line kept", "https://g.test/1", badDoc.listEntries()[0].url)

    // Truncated document (process death mid-write) must not crash and must heal on next write.
    val truncatedStorage = InMemoryStorage(valid.substring(0, valid.length / 2))
    val truncatedStore = LibraryStore(truncatedStorage)
    val truncatedCount = try {
        truncatedStore.listEntries().size
    } catch (t: Throwable) {
        -1
    }
    check("truncated document does not crash", truncatedCount in 0..1, "count=" + truncatedCount)
    val healed = truncatedStore.recordProgress("https://h.test/1", "H", "h.test", 0, 5)
    eq("write after truncation returns entry", "https://h.test/1", healed.url)
    eq("write after truncation is readable", 1, LibraryStore(truncatedStorage).listEntries().size)
    check(
        "write after truncation heals header",
        truncatedStorage.read()?.startsWith("NSLIB 1") == true,
        "raw=" + truncatedStorage.read()
    )

    // Storage failures are best-effort: a broken storage must never crash the reader.
    val broken = object : LibraryStorage {
        override fun read(): String? = throw IllegalStateException("broken read")
        override fun write(value: String) = throw IllegalStateException("broken write")
    }
    val brokenStore = LibraryStore(broken)
    eq("broken storage lists empty", 0, brokenStore.listEntries().size)
    val brokenRecord = try {
        brokenStore.recordProgress("https://b.test/1", "B", "b.test", 1, 2)
    } catch (t: Throwable) {
        null
    }
    check("broken storage record does not throw", brokenRecord != null)
    eq("broken storage resume is null", null, brokenStore.resumeEntry())
}

private fun testEscapingAndSharedStorage() {
    println()
    println("== escaping / shared document ==")
    val storage = InMemoryStorage()
    val store = LibraryStore(storage)
    val url = "https://example.com/chapter/1?q=a&b=c#frag"
    val title = "Tab\there, newline\nnext, backslash\\ and bell\u0001 end"
    val site = "example.com"
    store.recordProgress(url, title, site, 3, 9)

    val reloaded = LibraryStore(storage).getEntry(url)
    eq("escaped url round-trips", url, reloaded?.url)
    eq("escaped title round-trips", title, reloaded?.title)
    eq("escaped site round-trips", site, reloaded?.site)

    val bookmarkStore = BookmarkStore(storage)
    val snippet = "line1\nline2\ttabbed \"quoted\" — em dash"
    val bookmark = bookmarkStore.add(url, title, 3, snippet)
    check("bookmark add returns value", bookmark != null)
    val bookmarkReloaded = BookmarkStore(storage).list().firstOrNull()
    eq("escaped snippet round-trips", snippet, bookmarkReloaded?.snippet)
    eq("escaped bookmark title round-trips", title, bookmarkReloaded?.title)
    eq("bookmark did not clobber library", 1, LibraryStore(storage).listEntries().size)

    // Unicode beyond the BMP (emoji) must survive as well.
    val emojiStore = LibraryStore(InMemoryStorage())
    val emojiStorage = InMemoryStorage()
    val emojiLibrary = LibraryStore(emojiStorage)
    emojiLibrary.recordProgress("https://e.test/1", "Chapter 📖 — 第一章", "e.test", 1, 2)
    eq(
        "unicode title round-trips",
        "Chapter 📖 — 第一章",
        LibraryStore(emojiStorage).getEntry("https://e.test/1")?.title
    )
    check("unused store is clean", emojiStore.listEntries().isEmpty())
}

private fun testBookmarks() {
    println()
    println("== bookmarks ==")
    val storage = InMemoryStorage()
    val store = BookmarkStore(storage)
    eq("bookmarks start empty", 0, store.list().size)
    eq("blank url bookmark rejected", null, store.add("   ", "t", 1, "s"))
    eq("blank url bookmark not stored", 0, store.list().size)

    val b1 = store.add("https://a.test/1", "Chapter 1", 10, "snippet one")
    check("bookmark add returns bookmark", b1 != null)
    eq("bookmark url", "https://a.test/1", b1?.url)
    eq("bookmark title", "Chapter 1", b1?.title)
    eq("bookmark index", 10, b1?.sentenceIndex)
    eq("bookmark snippet", "snippet one", b1?.snippet)
    check("bookmark timestamp set", (b1?.createdAt ?: 0L) > 0L)
    eq("bookmark list size", 1, store.list().size)

    val b2 = store.add("https://a.test/1", "Chapter 1", 20, "snippet two")
    check("bookmark timestamps strictly increase", (b2?.createdAt ?: 0L) > (b1?.createdAt ?: 0L))
    eq("two bookmarks", 2, store.list().size)
    eq("newest bookmark first", 20, store.list()[0].sentenceIndex)
    eq("oldest bookmark last", 10, store.list()[1].sentenceIndex)

    // Same (url, sentence) re-bookmark updates instead of duplicating.
    val b3 = store.add("https://a.test/1", "Chapter 1", 10, "updated snippet")
    eq("re-bookmark does not duplicate", 2, store.list().size)
    eq("re-bookmark moves to front", 10, store.list()[0].sentenceIndex)
    eq("re-bookmark updates snippet", "updated snippet", store.list()[0].snippet)
    check("re-bookmark gets a fresh timestamp", (b3?.createdAt ?: 0L) > (b2?.createdAt ?: 0L))

    val other = store.add("https://b.test/2", "Book 2", 0, "other snippet")
    eq("filter by url", 2, store.list("https://a.test/1").size)
    eq("filter other url", 1, store.list("https://b.test/2").size)
    eq("filter missing url", 0, store.list("https://missing.test/x").size)
    eq("list all", 3, store.list().size)
    eq("null filter equals all", store.list(null).size, store.list().size)
    eq("newest across urls", "https://b.test/2", store.list()[0].url)

    eq("remove replaced bookmark is false", false, store.remove("https://a.test/1", b1?.createdAt ?: 0L))
    eq("remove by url+timestamp", true, store.remove("https://a.test/1", b2?.createdAt ?: 0L))
    eq("remove twice is false", false, store.remove("https://a.test/1", b2?.createdAt ?: 0L))
    eq("remove unknown url", false, store.remove("https://missing.test/x", b3?.createdAt ?: 0L))
    eq("remaining after remove", 2, store.list().size)
    eq(
        "remove keeps the deduped mark",
        10,
        store.list("https://a.test/1").singleOrNull()?.sentenceIndex
    )
    eq("bookmarks persist", 2, BookmarkStore(storage).list().size)
    eq("persisted bookmark identity", other?.createdAt, BookmarkStore(storage).list()[0].createdAt)

    store.clear()
    eq("clear empties bookmarks", 0, store.list().size)
    store.clear()
    eq("clear is idempotent", 0, store.list().size)
    eq("add after clear works", 1, store.add("https://c.test/3", "C", 0, "s")?.let { store.list().size } ?: 0)
}

private fun testStatsBasics() {
    println()
    println("== stats basics ==")
    val day = at(2026, 6, 10, 12, 0)
    val storage = InMemoryStorage()
    val stats = ReadingStats(storage)

    eq("today empty minutes", 0, stats.today(day).minutes)
    eq("today empty sentences", 0, stats.today(day).sentences)
    eq("today date is local day", dateKey(day), stats.today(day).date)
    eq("total minutes empty", 0, stats.totalMinutes())
    eq("total sentences empty", 0, stats.totalSentences())

    val emptyWeek = stats.lastDays(7, day)
    eq("lastDays default size", 7, emptyWeek.size)
    eq("lastDays ends today", dateKey(day), emptyWeek.last().date)
    check(
        "lastDays is oldest first and contiguous",
        emptyWeek.zipWithNext().all { (older, newer) -> older.date < newer.date },
        emptyWeek.map { it.date }.toString()
    )
    eq("lastDays zero-filled", 0, emptyWeek.first().minutes)
    eq("lastDays 0 is empty", 0, stats.lastDays(0, day).size)
    eq("lastDays negative is empty", 0, stats.lastDays(-3, day).size)
    eq("lastDays caps at KEEP_DAYS", ReadingStats.KEEP_DAYS, stats.lastDays(400, day).size)

    stats.addReading(120, 10, "example.com", day)
    eq("today minutes after add", 2, stats.today(day).minutes)
    eq("today sentences after add", 10, stats.today(day).sentences)
    eq("total minutes after add", 2, stats.totalMinutes())
    eq("total sentences after add", 10, stats.totalSentences())

    // Per-sentence calls accumulate seconds instead of each rounding down to zero.
    stats.addReading(20, 1, "example.com", day)
    eq("sub-minute add does not round up", 2, stats.today(day).minutes)
    stats.addReading(10, 1, "example.com", day)
    eq("accumulated seconds round to nearest minute", 3, stats.today(day).minutes)
    eq("sentences accumulate", 12, stats.today(day).sentences)

    stats.addReading(-100, -5, "example.com", day)
    eq("negative minutes ignored", 3, stats.today(day).minutes)
    eq("negative sentences ignored", 12, stats.today(day).sentences)

    stats.addReading(0, 0, "example.com", day)
    eq("zero add ignored", 3, stats.today(day).minutes)

    eq("stats persist minutes", 3, ReadingStats(storage).totalMinutes())
    eq("stats persist sentences", 12, ReadingStats(storage).totalSentences())
    eq("stats persist today", 3, ReadingStats(storage).today(day).minutes)
    check(
        "stats document has versioned header",
        storage.read()?.startsWith("NSLIB 1") == true,
        "raw=" + storage.read()
    )
}

private fun testStatsMidnightRollover() {
    println()
    println("== stats midnight rollover ==")
    val storage = InMemoryStorage()
    val stats = ReadingStats(storage)
    val lateNight = at(2026, 6, 10, 23, 59)
    val afterMidnight = at(2026, 6, 11, 0, 1)
    check(
        "explicit timestamps cross a day boundary",
        dateKey(lateNight) != dateKey(afterMidnight),
        dateKey(lateNight) + " vs " + dateKey(afterMidnight)
    )

    stats.addReading(300, 20, "example.com", lateNight)
    eq("late-night bucket minutes", 5, stats.today(lateNight).minutes)
    eq("late-night bucket sentences", 20, stats.today(lateNight).sentences)
    eq("next day empty before reading", 0, stats.today(afterMidnight).minutes)

    stats.addReading(60, 7, "example.com", afterMidnight)
    eq("after-midnight minutes", 1, stats.today(afterMidnight).minutes)
    eq("after-midnight sentences", 7, stats.today(afterMidnight).sentences)
    eq("late-night bucket unchanged", 5, stats.today(lateNight).minutes)
    eq("late-night paragraph unchanged", 20, stats.today(lateNight).sentences)

    val last = stats.lastDays(2, afterMidnight)
    eq("lastDays size across midnight", 2, last.size)
    eq("lastDays older day date", dateKey(lateNight), last[0].date)
    eq("lastDays older day minutes", 5, last[0].minutes)
    eq("lastDays older day sentences", 20, last[0].sentences)
    eq("lastDays newer day date", dateKey(afterMidnight), last[1].date)
    eq("lastDays newer day minutes", 1, last[1].minutes)
    eq("lastDays newer day sentences", 7, last[1].sentences)

    eq("totals across midnight minutes", 6, stats.totalMinutes())
    eq("totals across midnight sentences", 27, stats.totalSentences())
    eq("lastDays zero-fills the gap", 0, stats.lastDays(3, afterMidnight).first().minutes)
}

private fun testStatsPruneAndFallback() {
    println()
    println("== stats pruning / totals fallback ==")
    val storage = InMemoryStorage()
    val stats = ReadingStats(storage)
    val now = at(2026, 6, 10, 12, 0)
    val old = at(2026, 5, 1, 12, 0) // 40 days before now
    val oldKey = dateKey(old)

    stats.addReading(60, 4, "example.com", old)
    check(
        "old day stored before pruning",
        storage.read()?.contains("S\t" + oldKey) == true,
        "raw=" + storage.read()
    )
    stats.addReading(60, 2, "example.com", now)
    check(
        "old day pruned from day rows",
        storage.read()?.contains("S\t" + oldKey) != true,
        "raw=" + storage.read()
    )
    eq("all-time minutes keep pruned day", 2, stats.totalMinutes())
    eq("all-time sentences keep pruned day", 6, stats.totalSentences())
    eq("today minutes after prune", 1, stats.today(now).minutes)
    val lastWeek = stats.lastDays(7, now)
    eq("recent day retained", 1, lastWeek.last().minutes)
    eq("pruned day not in the week", 0, lastWeek.first().minutes)

    // When the T record is missing (partial/older document), totals derive from day rows.
    val raw = storage.read() ?: ""
    val stripped = raw.lineSequence().filterNot { it.startsWith("T\t") }.joinToString("\n")
    val fallbackStorage = InMemoryStorage(stripped)
    eq("fallback total minutes", 1, ReadingStats(fallbackStorage).totalMinutes())
    eq("fallback total sentences", 2, ReadingStats(fallbackStorage).totalSentences())

    // Corrupt stats records do not crash and are healed by the next write.
    val corrupt = InMemoryStorage("NSLIB 1\nS\tnot-a-date\t120\t5\nT\tx\ty")
    eq("corrupt stats today", 0, ReadingStats(corrupt).today(now).minutes)
    eq("corrupt stats totals", 0, ReadingStats(corrupt).totalMinutes())
    ReadingStats(corrupt).addReading(120, 3, "site", now)
    eq("corrupt stats heals", 2, ReadingStats(corrupt).today(now).minutes)
    eq("corrupt stats heals totals", 2, ReadingStats(corrupt).totalMinutes())
}

private fun testSharedStorage() {
    println()
    println("== shared storage across all three stores ==")
    val storage = InMemoryStorage()
    val library = LibraryStore(storage)
    val bookmarks = BookmarkStore(storage)
    val stats = ReadingStats(storage)
    val now = at(2026, 6, 10, 12, 0)

    library.recordProgress("https://s.test/1", "Shared 1", "s.test", 1, 4)
    bookmarks.add("https://s.test/1", "Shared 1", 1, "snippet")
    stats.addReading(120, 5, "s.test", now)

    eq("shared library visible", 1, LibraryStore(storage).listEntries().size)
    eq("shared bookmark visible", 1, BookmarkStore(storage).list().size)
    eq("shared stats visible", 2, ReadingStats(storage).today(now).minutes)

    library.recordProgress("https://s.test/2", "Shared 2", "s.test", 0, 4)
    eq("library write keeps bookmarks", 1, BookmarkStore(storage).list().size)
    eq("library write keeps stats", 5, ReadingStats(storage).totalSentences())

    bookmarks.add("https://s.test/2", "Shared 2", 0, "snippet 2")
    eq("bookmark write keeps library", 2, LibraryStore(storage).listEntries().size)
    eq("bookmark write keeps stats", 2, ReadingStats(storage).totalMinutes())

    library.clear()
    eq("clear library empties entries", 0, LibraryStore(storage).listEntries().size)
    eq("clear library keeps bookmarks", 2, BookmarkStore(storage).list().size)
    eq("clear library keeps stats", 2, ReadingStats(storage).totalMinutes())

    bookmarks.clear()
    eq("clear bookmarks keeps stats", 2, ReadingStats(storage).today(now).minutes)

    stats.addReading(60, 3, "s.test", now)
    eq("stats write keeps library empty", 0, LibraryStore(storage).listEntries().size)
    eq("stats write keeps bookmarks empty", 0, BookmarkStore(storage).list().size)
    eq("stats write lands", 3, ReadingStats(storage).today(now).minutes)
}

private fun testTimestamps() {
    println()
    println("== strictly increasing timestamps ==")
    val storage = InMemoryStorage()
    val library = LibraryStore(storage)
    val first = library.recordProgress("https://t.test/1", "T1", "t.test", 0, 10)
    val second = library.recordProgress("https://t.test/2", "T2", "t.test", 0, 10)
    val third = library.recordProgress("https://t.test/1", "T1", "t.test", 1, 10)
    check(
        "library updatedAt strictly increases",
        first.updatedAt < second.updatedAt && second.updatedAt < third.updatedAt,
        first.updatedAt.toString() + ", " + second.updatedAt + ", " + third.updatedAt
    )

    val bookmarks = BookmarkStore(storage)
    val markOne = bookmarks.add("https://t.test/1", "T1", 0, "a")
    val markTwo = bookmarks.add("https://t.test/1", "T1", 1, "b")
    check(
        "bookmark createdAt strictly increases",
        (markOne?.createdAt ?: 0L) < (markTwo?.createdAt ?: 0L),
        (markOne?.createdAt ?: 0L).toString() + " -> " + (markTwo?.createdAt ?: 0L)
    )
}

fun main() {
    println("NovelSpeak library/bookmarks/stats host tests")
    testPercent()
    testLibraryRecord()
    testLibraryPersistence()
    testLibraryResume()
    testLibraryRemoveClear()
    testLibraryCap()
    testLibraryCorruption()
    testEscapingAndSharedStorage()
    testBookmarks()
    testStatsBasics()
    testStatsMidnightRollover()
    testStatsPruneAndFallback()
    testSharedStorage()
    testTimestamps()

    println()
    println("----------------------------------------------------------------")
    if (failures > 0) {
        println(failures.toString() + " of " + checks + " checks FAILED")
        kotlin.system.exitProcess(1)
    }
    println("all " + checks + " checks passed")
}
