import app.novelspeak.PdfTextImporter
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Host-side checks for the PDF text-layer importer. Not part of the APK.
 *
 *   kotlinc app/src/app/novelspeak/PdfTextImporter.kt tools/pdf-test/TestMain.kt \
 *     -include-runtime -d /tmp/pdf-test.jar
 *   java -jar /tmp/pdf-test.jar
 *
 * Exit 0 = all checks pass. Any failure prints FAILED and exits 1.
 */
fun main() {
    var failures = 0
    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) println("PASS $name") else {
            failures++
            println("FAILED $name $detail")
        }
    }

    fun buildPdf(content: String, flate: Boolean = false): ByteArray {
        val contentBytes = content.toByteArray(Charsets.ISO_8859_1)
        val streamBytes: ByteArray
        val filterPart: String
        if (flate) {
            val def = Deflater()
            def.setInput(contentBytes)
            def.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!def.finished()) {
                val n = def.deflate(buf)
                out.write(buf, 0, n)
            }
            def.end()
            streamBytes = out.toByteArray()
            filterPart = " /Filter /FlateDecode"
        } else {
            streamBytes = contentBytes
            filterPart = ""
        }
        val prefix = "%PDF-1.4\n" +
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n" +
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n" +
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>\nendobj\n" +
            "4 0 obj\n<< /Length ${streamBytes.size}$filterPart >>\nstream\n"
        val suffix = "\nendstream\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n"
        val baos = ByteArrayOutputStream()
        baos.write(prefix.toByteArray(Charsets.ISO_8859_1))
        baos.write(streamBytes)
        baos.write(suffix.toByteArray(Charsets.ISO_8859_1))
        return baos.toByteArray()
    }

    // ---- 1. uncompressed content with escaped chars ----
    val escapedContent = "BT\n(Hello\\nWorld\\tTab\\(parens\\)\\\\backslash\\101 octal) Tj\nET"
    val r1 = PdfTextImporter.import(buildPdf(escapedContent))
    check("uncompressed-basic", r1.error.isEmpty() && r1.text.contains("Hello") && r1.text.contains("World"),
        "err=${r1.error} text=${r1.text}")
    check("escaped-newline", r1.text.contains("\n"), "text=${r1.text.replace("\n", "\\n")}")
    check("escaped-tab", r1.text.contains("\t"))
    check("escaped-parens", r1.text.contains("(parens)"))
    check("escaped-backslash", r1.text.contains("\\"))
    check("escaped-octal", r1.text.contains("A octal"))

    // ---- 2. FlateDecode stream ----
    val r2 = PdfTextImporter.import(buildPdf("BT\n(FlateDecode OK) Tj\nET", true))
    check("flate-decode", r2.error.isEmpty() && r2.text.contains("FlateDecode OK"),
        "err=${r2.error} text=${r2.text}")

    // ---- 3. hex string + TJ array ----
    val rHex = PdfTextImporter.import(buildPdf("BT\n<48656C6C6F> Tj\nET"))
    check("hex-string", rHex.text.contains("Hello"), "text=${rHex.text}")
    val rTJ = PdfTextImporter.import(buildPdf("BT\n[(Hello) 120 (World)] TJ\nET"))
    check("tj-array", rTJ.text.contains("Hello") && rTJ.text.contains("World"), "text=${rTJ.text}")

    // ---- 4. Td/T* newlines ----
    val rTd = PdfTextImporter.import(buildPdf("BT\n(First) Tj\nT*\n(Second) Tj\nET"))
    check("td-newline", rTd.text.contains("First") && rTd.text.contains("Second") && rTd.text.contains("\n"),
        "text=${rTd.text.replace("\n", "\\n")}")

    // ---- 5. encrypted marker ----
    val encBytes = ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n" +
        "trailer\n<< /Root 1 0 R /Encrypt 5 0 R >>\n%%EOF\n").toByteArray(Charsets.ISO_8859_1)
    val r3 = PdfTextImporter.import(encBytes)
    check("encrypted", r3.pages.isEmpty() && r3.error.lowercase().contains("encrypt"),
        "err=${r3.error}")

    // ---- 6. random bytes ----
    val random = ByteArray(200) { (it * 37 + 13).toByte() }
    val r4 = PdfTextImporter.import(random)
    check("random-no-text", r4.pages.isEmpty(), "err=${r4.error} pages=${r4.pages.size}")

    // ---- 7. file overload ----
    val tmp = java.io.File("/tmp/pdf-importer-test.pdf")
    tmp.writeBytes(buildPdf("BT\n(File path works) Tj\nET"))
    val rf = PdfTextImporter.import(tmp)
    check("file-overload", rf.text.contains("File path works"), "err=${rf.error}")
    tmp.delete()

    if (failures > 0) {
        println("$failures failure(s)")
        kotlin.system.exitProcess(1)
    } else {
        println("All checks passed")
    }
}
