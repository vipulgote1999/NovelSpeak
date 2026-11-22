import app.novelspeak.DocumentKind

/**
 * Host checks that every file type the picker accepts also survives the intent gate.
 *
 *   kotlinc app/src/app/novelspeak/DocumentKind.kt tools/document-kind-test/TestMain.kt \
 *     -include-runtime -d /tmp/document-kind-test.jar
 *   java -jar /tmp/document-kind-test.jar
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

    check("epub mime", DocumentKind.isImportable("application/epub+zip", ""))
    check("pdf mime", DocumentKind.isImportable("application/pdf", ""))
    check("plain text mime", DocumentKind.isImportable("text/plain", ""))
    check("text path", DocumentKind.isImportable("application/octet-stream", "/tmp/Downloads/book.txt"))
    check("epub path", DocumentKind.isImportable(null, "/tmp/book.EPUB"))
    check("pdf path", DocumentKind.isImportable("", "/tmp/book.pdf"))
    check("image refused", !DocumentKind.isImportable("image/png", "/tmp/cover.png"))
    check("html refused", !DocumentKind.isImportable("text/html", "/tmp/page.html"))
    check("empty refused", !DocumentKind.isImportable(null, null))

    check("kind epub", DocumentKind.kind("application/epub+zip", "") == DocumentKind.EPUB)
    check("kind pdf", DocumentKind.kind("", "/tmp/book.pdf") == DocumentKind.PDF)
    check("kind text", DocumentKind.kind("text/plain", "") == DocumentKind.TEXT)

    println("$checks checks, $failures failure(s)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
