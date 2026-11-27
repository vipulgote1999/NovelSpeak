import app.novelspeak.manga.MangaLine
import app.novelspeak.manga.MangaTextLayout

/**
 * Host-side checks for manga reading order and speech chunk validation.
 *
 *   kotlinc app/src/app/novelspeak/SentenceSplitter.kt \
 *     app/src/app/novelspeak/manga/MangaTextLayout.kt tools/manga-text-test/TestMain.kt \
 *     -include-runtime -d /tmp/manga-text-test.jar
 *   java -jar /tmp/manga-text-test.jar
 *
 * Exit 0 = all checks pass. Any failure prints FAILED and exits 1.
 */
fun main() {
    var failures = 0
    var checks = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        checks++
        if (ok) println("PASS $name") else {
            failures++
            println("FAILED $name $detail")
        }
    }

    fun line(text: String, l: Float, t: Float, r: Float, b: Float) =
        MangaLine(text, l, t, r, b)

    // Two rows, two bubbles each. Left-to-right is the English scanlation order.
    val grid = listOf(
        line("top-left", 0f, 0f, 100f, 50f),
        line("top-right", 200f, 0f, 300f, 50f),
        line("bottom-left", 0f, 100f, 100f, 150f),
        line("bottom-right", 200f, 100f, 300f, 150f)
    )
    val ltr = MangaTextLayout.readingOrder(grid, rightToLeft = false)
    check(
        "ltr-grid",
        ltr.map { grid[it].text } ==
            listOf("top-left", "top-right", "bottom-left", "bottom-right"),
        "got=${ltr.map { grid[it].text }}"
    )
    val rtl = MangaTextLayout.readingOrder(grid, rightToLeft = true)
    check(
        "rtl-grid",
        rtl.map { grid[it].text } ==
            listOf("top-right", "top-left", "bottom-right", "bottom-left"),
        "got=${rtl.map { grid[it].text }}"
    )

    // A tall left panel and a short right bubble share a row when their vertical spans
    // overlap; the short bubble must not be pushed into the next row.
    val mixed = listOf(
        line("tall-left", 0f, 0f, 100f, 200f),
        line("short-right", 200f, 20f, 300f, 60f),
        line("below", 0f, 260f, 100f, 320f)
    )
    val mixedOrder = MangaTextLayout.readingOrder(mixed, rightToLeft = false)
    check(
        "overlap-row",
        mixedOrder.map { mixed[it].text } == listOf("tall-left", "short-right", "below"),
        "got=${mixedOrder.map { mixed[it].text }}"
    )

    // A stylised bubble lettered line by line can be detected as several overlapping boxes.
    // Those lines share a column and must stay top-to-bottom even when their left edges
    // wobble; they must not be re-ordered by x.
    val lettered = listOf(
        line("IS", 20f, 0f, 80f, 80f),
        line("ABOUT TO WITNESS", 14f, 34f, 86f, 114f),
        line("A", 24f, 68f, 76f, 148f),
        line("GREAT ERA", 18f, 102f, 82f, 182f),
        line("OF", 26f, 136f, 74f, 216f),
        line("PIRACY!", 16f, 170f, 84f, 250f)
    )
    val letteredOrder = MangaTextLayout.readingOrder(lettered, rightToLeft = false)
    check(
        "lettered-column",
        letteredOrder.map { lettered[it].text } ==
            listOf("IS", "ABOUT TO WITNESS", "A", "GREAT ERA", "OF", "PIRACY!"),
        "got=${letteredOrder.map { lettered[it].text }}"
    )

    // Empty boxes cannot be placed; they go after the page text rather than crashing.
    val withEmpty = listOf(
        line("real", 0f, 0f, 100f, 50f),
        line("empty", 0f, 0f, 0f, 0f)
    )
    val emptyOrder = MangaTextLayout.readingOrder(withEmpty, rightToLeft = false)
    check("empty-last", emptyOrder.last() == 1, "got=$emptyOrder")

    // Hyphenated line wraps are one word, not "exam- ple".
    check(
        "dehyphenate",
        MangaTextLayout.normalize("exam- ple") == "example",
        "got=${MangaTextLayout.normalize("exam- ple")}"
    )
    check(
        "dehyphenate-uppercase",
        MangaTextLayout.normalize("SOME- TIMES") == "SOMETIMES",
        "got=${MangaTextLayout.normalize("SOME- TIMES")}"
    )
    check(
        "keep-real-hyphen",
        MangaTextLayout.normalize("well-known") == "well-known",
        "got=${MangaTextLayout.normalize("well-known")}"
    )
    check(
        "collapse-space",
        MangaTextLayout.normalize("  one\n\t two  ") == "one two",
        "got=${MangaTextLayout.normalize("  one\n\t two  ")}"
    )

    // Non-speech fragments must never become a Reader block.
    check("skip-page-number", MangaTextLayout.chunks("12").isEmpty())
    check("skip-punctuation", MangaTextLayout.chunks("!!! ...").isEmpty())
    check("skip-url", MangaTextLayout.chunks("https://example.com/page").isEmpty())
    check("keep-shout", MangaTextLayout.chunks("NO!!").isNotEmpty())
    check("keep-dialogue", MangaTextLayout.chunks("I'm going to be King of the Pirates!").isNotEmpty())

    // A long bubble is split at sentence boundaries and never exceeds the cap.
    val longBubble = buildString {
        repeat(8) { append("This is sentence number ${it + 1}, and it keeps the bubble going. ") }
    }
    val chunks = MangaTextLayout.chunks(longBubble, maxChars = 180)
    check("long-splits", chunks.size >= 2, "chunks=${chunks.size}")
    check("long-cap", chunks.all { it.length <= 180 }, "max=${chunks.maxOfOrNull { it.length }}")
    check("long-speakable", chunks.all { MangaTextLayout.isSpeakable(it) })

    // Two tiny fragments in one bubble are merged so a neural voice does not synthesize an
    // isolated "Hi." on its own.
    val tiny = MangaTextLayout.chunks("Hi. Luffy!")
    check("merge-tiny", tiny.size == 1 && tiny[0] == "Hi. Luffy!", "got=$tiny")

    // OCR noise: punctuation-only lines inside a bubble are dropped, words survive.
    val noisy = MangaTextLayout.chunks("... THE KING OF THE PIRATES! ...")
    check("noise-kept", noisy.isNotEmpty() && noisy[0].contains("KING OF THE PIRATES"), "got=$noisy")

    println("$checks checks, $failures failure(s)")
    if (failures > 0) {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
    println("ALL PASS")
}
