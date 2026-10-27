import app.novelspeak.EpubImporter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Host-side harness for EPUB import. Not part of the APK.
 *
 *   kotlinc app/src/app/novelspeak/EpubImporter.kt tools/epub-test/TestMain.kt \
 *     -include-runtime -d /tmp/epub-test.jar
 *   java -jar /tmp/epub-test.jar
 */
fun main() {
    var failures = 0
    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) println("PASS " + name) else {
            failures++
            println("FAIL " + name + " " + detail)
        }
    }

    val containerXml = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    fun opf(title: String, author: String, items: List<Pair<String, String>>): String {
        val manifest = items.joinToString("\n") { (id, href) ->
            "    <item id=\"" + id + "\" href=\"" + href + "\" media-type=\"application/xhtml+xml\"/>"
        }
        val spine = items.joinToString("\n") { (id, _) ->
            "    <itemref idref=\"" + id + "\"/>"
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>""" + title + """</dc:title>
    <dc:creator>""" + author + """</dc:creator>
  </metadata>
  <manifest>
""" + manifest + """
  </manifest>
  <spine>
""" + spine + """
  </spine>
</package>"""
    }

    fun xhtml(title: String, body: String): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>""" + title + """</title></head>
<body>
<h1>""" + title + """</h1>
""" + body + """
</body>
</html>"""
    }

    fun buildEpub(
        title: String = "Test Book",
        author: String = "Test Author",
        chapters: List<Pair<String, String>> = listOf(
            "Chapter One" to "<p>Hello &amp; welcome to <b>NovelSpeak</b>.</p><br/><p>Second paragraph with &lt;tag&gt; and &#39;quote&#39;.</p>",
            "Chapter Two" to "<p>Another chapter <i>here</i>.</p><div>Nested <span>inline</span> text.</div>"
        )
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(containerXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val items = chapters.mapIndexed { i, (t, _) -> "ch" + (i + 1) to "ch" + (i + 1) + ".xhtml" }
            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(opf(title, author, items).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            chapters.forEachIndexed { i, (t, body) ->
                val id = "ch" + (i + 1)
                zos.putNextEntry(ZipEntry("OEBPS/" + id + ".xhtml"))
                zos.write(xhtml(t, body).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            zos.putNextEntry(ZipEntry("OEBPS/short.xhtml"))
            zos.write(xhtml("Short", "<p>Hi</p>").toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    val bytes = buildEpub()
    val streamRes = EpubImporter.import(ByteArrayInputStream(bytes))
    val book = streamRes.book
    check("stream-no-error", streamRes.error.isEmpty() && book != null, "err=" + streamRes.error)
    check("stream-title", book?.title == "Test Book", "got=" + book?.title)
    check("stream-author", book?.author == "Test Author", "got=" + book?.author)
    check("stream-chapter-count", book?.chapters?.size == 2, "got=" + book?.chapters?.size)
    check("stream-order-first", book?.chapters?.get(0)?.title == "Chapter One", "got=" + book?.chapters?.get(0)?.title)
    check("stream-order-second", book?.chapters?.get(1)?.title == "Chapter Two", "got=" + book?.chapters?.get(1)?.title)
    val t0 = book?.chapters?.get(0)?.text ?: ""
    check("stream-entity-amp", t0.contains("Hello & welcome to NovelSpeak."), "got=" + t0)
    check("stream-entity-lt", t0.contains("Second paragraph with <tag> and 'quote'."), "got=" + t0)
    check("stream-no-tags", !t0.contains("<b>") && !t0.contains("<p>"), "got=" + t0)

    val tmp = File("/tmp/epub-test-sample.epub")
    tmp.writeBytes(bytes)
    val fileRes = EpubImporter.import(tmp)
    check("file-import", fileRes.book != null && fileRes.book?.chapters?.size == 2, "err=" + fileRes.error)

    val emptyRes = EpubImporter.import(ByteArrayInputStream(ByteArray(0)))
    check("empty-rejected", emptyRes.book == null && emptyRes.error.isNotEmpty(), "err=" + emptyRes.error)

    val corruptRes = EpubImporter.import(ByteArrayInputStream("not a zip".toByteArray()))
    check("corrupt-rejected", corruptRes.book == null && corruptRes.error.isNotEmpty(), "err=" + corruptRes.error)

    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
        zos.putNextEntry(ZipEntry("../evil.xml"))
        zos.write("<x/>".toByteArray())
        zos.closeEntry()
    }
    val travRes = EpubImporter.import(ByteArrayInputStream(baos.toByteArray()))
    check("traversal-rejected", travRes.book == null && travRes.error.isNotEmpty(), "err=" + travRes.error)

    val baos2 = ByteArrayOutputStream()
    ZipOutputStream(baos2).use { zos ->
        zos.putNextEntry(ZipEntry("META-INF/container.xml"))
        zos.write(ByteArray(8 * 1024 * 1024 + 1) { 'a'.code.toByte() })
        zos.closeEntry()
    }
    val overRes = EpubImporter.import(ByteArrayInputStream(baos2.toByteArray()))
    check("oversized-rejected", overRes.book == null && overRes.error.isNotEmpty(), "err=" + overRes.error)

    if (failures > 0) {
        println(failures.toString() + " case(s) failed")
        kotlin.system.exitProcess(1)
    } else {
        println("ALL PASS")
    }
}
