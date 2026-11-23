import app.novelspeak.PageFailure
import app.novelspeak.UiText

/**
 * Host checks for the copy and the error-page gate.
 *
 *   kotlinc app/src/app/novelspeak/PageFailure.kt app/src/app/novelspeak/UiText.kt \
 *     tools/error-page-test/TestMain.kt -include-runtime -d /tmp/error-page-test.jar
 *   java -jar /tmp/error-page-test.jar
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

    check("zero sentences", UiText.sentenceCount(0) == "0 sentences", UiText.sentenceCount(0))
    check("one sentence is singular", UiText.sentenceCount(1) == "1 sentence", UiText.sentenceCount(1))
    check("two sentences", UiText.sentenceCount(2) == "2 sentences", UiText.sentenceCount(2))
    check("large count", UiText.sentenceCount(1204) == "1204 sentences")
    check("one line is singular", UiText.lineCount(1) == "1 line", UiText.lineCount(1))
    check("many lines", UiText.lineCount(3) == "3 lines", UiText.lineCount(3))

    check(
        "hyphenated filename becomes a title",
        UiText.titleFromFileName("the-salt-road.txt", "Imported text") == "The Salt Road",
        UiText.titleFromFileName("the-salt-road.txt", "Imported text")
    )
    check(
        "underscores and case",
        UiText.titleFromFileName("my_book__v2.TXT", "Imported text") == "My Book V2",
        UiText.titleFromFileName("my_book__v2.TXT", "Imported text")
    )
    check(
        "already spaced title keeps words",
        UiText.titleFromFileName("Already Nice Title.epub", "Imported") == "Already Nice Title",
        UiText.titleFromFileName("Already Nice Title.epub", "Imported")
    )
    check("blank filename falls back", UiText.titleFromFileName("", "Imported text") == "Imported text")
    check("initial skips a quote", UiText.initial("\"The Salt Road\"") == "T", UiText.initial("\"The Salt Road\""))
    check("initial skips a dash", UiText.initial(" — Chapter") == "C", UiText.initial(" — Chapter"))
    check("initial fallback", UiText.initial("") == "•", UiText.initial(""))
    check("duration minutes", UiText.durationMinutes(42) == "42 min", UiText.durationMinutes(42))
    check("duration exact hour", UiText.durationMinutes(60) == "1 h", UiText.durationMinutes(60))
    check("duration hours and minutes", UiText.durationMinutes(72) == "1 h 12 min", UiText.durationMinutes(72))
    check("duration zero", UiText.durationMinutes(0) == "0 min", UiText.durationMinutes(0))

    check("Chromium error title", PageFailure.isErrorPage("Webpage not available"))
    check(
        "Chromium error body title",
        PageFailure.isErrorPage("Webpage at http://localhost:8765/chapter1.html could not be loaded because:")
    )
    check("net error code", PageFailure.isErrorPage("net::ERR_EMPTY_RESPONSE"))
    check("404 page", PageFailure.isErrorPage("404 Not Found"))
    check("404 dash page", PageFailure.isErrorPage("404 - Not Found"))
    check("404 colon page", PageFailure.isErrorPage("404: Page not found"))
    check("page not found", PageFailure.isErrorPage("Page not found"))
    check("real chapter title", !PageFailure.isErrorPage("Chapter One: The Lamplighter's Daughter"))
    check("chapter with a number", !PageFailure.isErrorPage("Chapter 404: The Missing Page"))
    check("chapter title ending in not found", !PageFailure.isErrorPage("The City Not Found"))
    check("chapter title starting with 404", !PageFailure.isErrorPage("404 Ways to Read"))
    check("chapter titled 404 page turner", !PageFailure.isErrorPage("404 Page Turner"))
    check("empty title", !PageFailure.isErrorPage(""))

    check("fixture error body", PageFailure.isErrorBody("Error response Error code: 404"))
    check("net error body", PageFailure.isErrorBody("net::ERR_NAME_NOT_RESOLVED"))
    check("real chapter body", !PageFailure.isErrorBody("The harbour town of Wick put itself to bed early."))

    println("$checks checks, $failures failure(s)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
