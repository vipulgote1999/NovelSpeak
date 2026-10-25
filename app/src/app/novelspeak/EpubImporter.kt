package app.novelspeak

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

object EpubImporter {

    data class Chapter(val title: String, val text: String)
    data class Book(val title: String, val author: String, val chapters: List<Chapter>)
    data class Result(val book: Book?, val error: String)

    private const val ENTRY_CAP = 8 * 1024 * 1024
    private const val TOTAL_CAP = 64 * 1024 * 1024
    private const val CHAPTER_CAP = 500

    fun import(input: InputStream): Result {
        return try {
            val zip = readZip(input) ?: return Result(null, "invalid zip")
            buildBook(zip)
        } catch (t: Throwable) {
            Result(null, t.message ?: t.toString())
        }
    }

    fun import(file: File): Result {
        return try {
            file.inputStream().use { import(it) }
        } catch (t: Throwable) {
            Result(null, t.message ?: t.toString())
        }
    }

    private fun readZip(input: InputStream): Map<String, ByteArray>? {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0
        try {
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name == null || name.isEmpty()) {
                        entry = zis.nextEntry
                        continue
                    }
                    if (isUnsafePath(name)) {
                        throw IllegalArgumentException("path traversal in entry: " + name)
                    }
                    if (!entry.isDirectory) {
                        val baos = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var read: Int
                        var entryTotal = 0
                        while (true) {
                            read = zis.read(buf)
                            if (read < 0) break
                            entryTotal += read
                            if (entryTotal > ENTRY_CAP) {
                                throw IllegalArgumentException("entry exceeds 8 MB cap: " + name)
                            }
                            total += read
                            if (total > TOTAL_CAP) {
                                throw IllegalArgumentException("total exceeds 64 MB cap")
                            }
                            baos.write(buf, 0, read)
                        }
                        out[name] = baos.toByteArray()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (t: Throwable) {
            throw t
        }
        return out
    }

    private fun isUnsafePath(path: String): Boolean {
        val norm = path.replace('\\', '/')
        if (norm.startsWith("/")) return true
        return norm.split("/").any { it == ".." }
    }

    private fun findEntry(zip: Map<String, ByteArray>, path: String): ByteArray? {
        if (zip.containsKey(path)) return zip[path]
        val target = path.trimStart('/')
        for ((k, v) in zip) {
            if (k.equals(target, ignoreCase = true)) return v
            if (k.trimStart('/').equals(target, ignoreCase = true)) return v
        }
        return null
    }

    private fun buildBook(zip: Map<String, ByteArray>): Result {
        val containerBytes = findEntry(zip, "META-INF/container.xml")
            ?: return Result(null, "missing META-INF/container.xml")
        val containerDoc = parseXml(containerBytes) ?: return Result(null, "corrupt container.xml")
        val rootfiles = containerDoc.getElementsByTagName("rootfile")
        if (rootfiles.length == 0) return Result(null, "missing rootfile in container.xml")
        val rootfile = rootfiles.item(0) as? Element
            ?: return Result(null, "invalid rootfile element")
        val opfPath = (rootfile.getAttribute("full-path") ?: "").trim()
        if (opfPath.isEmpty()) return Result(null, "missing rootfile full-path")
        if (isUnsafePath(opfPath)) return Result(null, "path traversal in rootfile")

        val opfBytes = findEntry(zip, opfPath)
            ?: return Result(null, "missing OPF: " + opfPath)
        val opfDoc = parseXml(opfBytes) ?: return Result(null, "corrupt OPF")

        var title = firstDcText(opfDoc, "title")
        var author = firstDcText(opfDoc, "creator")

        val manifest = LinkedHashMap<String, Pair<String, String>>()
        val items = opfDoc.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val el = items.item(i) as? Element ?: continue
            val id = el.getAttribute("id") ?: ""
            val href = el.getAttribute("href") ?: ""
            val mt = el.getAttribute("media-type") ?: ""
            if (id.isNotEmpty() && href.isNotEmpty()) {
                manifest[id] = Pair(href, mt)
            }
        }

        val spineIds = ArrayList<String>()
        val itemrefs = opfDoc.getElementsByTagName("itemref")
        for (i in 0 until itemrefs.length) {
            val el = itemrefs.item(i) as? Element ?: continue
            val idref = el.getAttribute("idref") ?: ""
            if (idref.isNotEmpty()) spineIds.add(idref)
        }
        if (spineIds.size > CHAPTER_CAP) {
            return Result(null, "too many chapters (cap " + CHAPTER_CAP + ")")
        }

        val opfDir = parentDir(opfPath)
        val chapters = ArrayList<Chapter>()
        for (idref in spineIds) {
            val entry = manifest[idref] ?: continue
            val href = entry.first
            val mt = entry.second
            if (!isHtmlMedia(mt)) continue
            val resolved = resolveHref(opfDir, href) ?: return Result(null, "path traversal in manifest href")
            val bytes = findEntry(zip, resolved) ?: continue
            val html = decodeBytes(bytes)
            val chapterTitle = extractTitle(html, idref)
            val text = stripHtml(html)
            if (text.length < 20) continue
            if (chapters.size >= CHAPTER_CAP) break
            chapters.add(Chapter(chapterTitle, text))
        }

        val book = Book(title, author, chapters)
        return Result(book, "")
    }

    private fun isHtmlMedia(mt: String): Boolean {
        val m = mt.lowercase().trim()
        return m == "application/xhtml+xml" || m == "text/html"
    }

    private fun parentDir(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx >= 0) path.substring(0, idx) else ""
    }

    private fun resolveHref(baseDir: String, href: String): String? {
        val h = href.trim()
        if (h.isEmpty()) return null
        val combined = if (baseDir.isEmpty()) h else baseDir + "/" + h
        if (isUnsafePath(combined)) return null
        val parts = combined.replace('\\', '/').split("/")
        val resolved = ArrayList<String>()
        for (p in parts) {
            if (p.isEmpty() || p == ".") continue
            if (p == "..") return null
            resolved.add(p)
        }
        return resolved.joinToString("/")
    }

    private fun parseXml(bytes: ByteArray): Document? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Throwable) {}
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Throwable) {}
            try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Throwable) {}
            factory.isXIncludeAware = false
            factory.setExpandEntityReferences(false)
            val builder = factory.newDocumentBuilder()
            builder.parse(ByteArrayInputStream(bytes))
        } catch (_: Throwable) {
            null
        }
    }

    private fun firstDcText(doc: Document, local: String): String {
        val ns = "http://purl.org/dc/elements/1.1/"
        var list = doc.getElementsByTagNameNS(ns, local)
        if (list.length == 0) list = doc.getElementsByTagName("dc:" + local)
        if (list.length == 0) list = doc.getElementsByTagName(local)
        if (list.length == 0) {
            val all = doc.getElementsByTagName("*")
            for (i in 0 until all.length) {
                val el = all.item(i) as? Element ?: continue
                if (el.localName == local || el.nodeName.substringAfter(':') == local) {
                    return (el.textContent ?: "").trim()
                }
            }
            return ""
        }
        return (list.item(0).textContent ?: "").trim()
    }

    private fun decodeBytes(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Throwable) {
            String(bytes, StandardCharsets.ISO_8859_1)
        }
    }

    private fun extractTitle(html: String, fallback: String): String {
        val regex = Regex("<(h1|h2|title)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val m = regex.find(html)
        val raw = m?.groupValues?.get(2) ?: ""
        val text = collapseWs(decodeEntities(stripTagsQuick(raw)))
        return if (text.isNotEmpty()) text else fallback
    }

    private fun stripTagsQuick(s: String): String {
        return s.replace(Regex("<[^>]+>"), "")
    }

    private fun stripHtml(html: String): String {
        var s = html
        s = s.replace(Regex("<script\\b[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("<style\\b[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        val boundary = Regex("</?\\s*(br|p|div|h[1-6]|li)\\b[^>]*>", RegexOption.IGNORE_CASE)
        s = boundary.replace(s, "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = decodeEntities(s)
        s = collapseWs(s)
        return s
    }

    private fun decodeEntities(s: String): String {
        val regex = Regex("&(#?)(x?)([0-9a-zA-Z]+);")
        return regex.replace(s) { mr ->
            val hash = mr.groupValues[1]
            val x = mr.groupValues[2]
            val body = mr.groupValues[3]
            if (hash == "#") {
                val code = try {
                    if (x.equals("x", ignoreCase = true)) body.toInt(16) else body.toInt(10)
                } catch (_: Throwable) { -1 }
                if (code in 0..0x10FFFF) {
                    try { String(Character.toChars(code)) } catch (_: Throwable) { mr.value }
                } else mr.value
            } else {
                when (body.lowercase()) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    else -> mr.value
                }
            }
        }
    }

    private fun collapseWs(s: String): String {
        return s.replace(Regex("\\s+"), " ").trim()
    }
}
