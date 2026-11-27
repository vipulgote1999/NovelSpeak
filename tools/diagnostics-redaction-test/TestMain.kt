import app.novelspeak.DiagnosticsRedaction

/**
 * Regression for the shareable diagnostics bundle and the on-device log.
 *
 * Redaction must be based on what the value is, not only on the preference key:
 * the library document and the ZipVoice transcript do not contain "url" in their
 * key names, but both carry private reading data.
 */
fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) println("PASS $name") else {
            failures++
            println("FAILED $name $detail")
        }
    }

    val library = "NSLIB 1\nL\thttps://secret.example/chapter-7\tMy Book\tsecret.example\t42\t100\t0\t0"
    val libraryOut = DiagnosticsRedaction.safeValue("library_v1", library)
    check("library value is redacted", libraryOut.contains("redacted"), "got=$libraryOut")
    check("library URL does not leak", !libraryOut.contains("https://"), "got=$libraryOut")
    check("library title does not leak", !libraryOut.contains("My Book"), "got=$libraryOut")

    val transcript = "the quick brown fox is my private reference speech"
    val transcriptOut = DiagnosticsRedaction.safeValue("zipvoice_transcript", transcript)
    check("transcript is redacted", transcriptOut.contains("redacted"), "got=$transcriptOut")
    check("transcript text does not leak", !transcriptOut.contains("private reference"), "got=$transcriptOut")

    val sitesOut = DiagnosticsRedaction.safeValue("adblock_allow_sites", "secret.example|other.example")
    check("allow-listed sites do not leak", !sitesOut.contains("secret.example"), "got=$sitesOut")

    val siteSetOut = DiagnosticsRedaction.safeValue(
        "reader_off_sites",
        setOf("secret.example", "other.example")
    )
    check("site set is redacted", !siteSetOut.contains("secret.example"), "got=$siteSetOut")

    val nullOut = DiagnosticsRedaction.safeValue("library_v1", null)
    check("null is preserved", nullOut == "null", "got=$nullOut")

    val noteOut = DiagnosticsRedaction.safeValue("reading_note", "see https://secret.example/a?b=1 now")
    check("URL inside an ordinary value is scrubbed", !noteOut.contains("https://"), "got=$noteOut")
    check("ordinary text is preserved", noteOut.contains("see") && noteOut.contains("now"), "got=$noteOut")

    val plainOut = DiagnosticsRedaction.safeValue("reader_theme", "sepia")
    check("ordinary value is preserved", plainOut == "sepia", "got=$plainOut")

    val event = "GET https://secret.example/chapter?token=abc123 done"
    val eventOut = DiagnosticsRedaction.redact(event)
    check("event URL is redacted", !eventOut.contains("https://"), "got=$eventOut")
    check("credential is redacted", !eventOut.contains("abc123"), "got=$eventOut")

    val upper = DiagnosticsRedaction.redact("see HTTPS://SECRET.EXAMPLE/a")
    check("uppercase URL is redacted", !upper.contains("SECRET.EXAMPLE"), "got=$upper")

    val plainSet = DiagnosticsRedaction.safeValue("plain_set", setOf("one", "https://secret.example/b"))
    check("URL inside a set is scrubbed", !plainSet.contains("https://"), "got=$plainSet")
    check("ordinary set member is preserved", plainSet.contains("one"), "got=$plainSet")

    if (failures > 0) kotlin.system.exitProcess(1)
    println("ALL PASS")
}
