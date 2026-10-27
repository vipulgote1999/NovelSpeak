package app.novelspeak

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

object PdfTextImporter {

    data class Page(val index: Int, val text: String)

    data class Result(val pages: List<Page>, val error: String) {
        val text: String get() = pages.joinToString("\n\n") { it.text }
    }

    private const val MAX_FILE_BYTES = 64 * 1024 * 1024
    private const val MAX_STREAM_BYTES = 32 * 1024 * 1024
    private const val MAX_PAGES = 3000
    private const val MAX_CHARS = 2_000_000

    fun import(file: File): Result {
        return try {
            if (!file.exists() || !file.isFile) return Result(emptyList(), "file not found")
            if (file.length() > MAX_FILE_BYTES) return Result(emptyList(), "file too large (>64MB)")
            import(file.readBytes())
        } catch (e: Exception) {
            Result(emptyList(), "error: ${e.message ?: e::class.simpleName}")
        }
    }

    fun import(bytes: ByteArray): Result {
        return try {
            if (bytes.size > MAX_FILE_BYTES) return Result(emptyList(), "file too large (>64MB)")
            val rawText = String(bytes, Charsets.ISO_8859_1)
            if (rawText.contains("/Encrypt")) return Result(emptyList(), "encrypted PDF")

            val objs = parseObjects(rawText)
            val pageIds = collectPages(rawText, objs)
            val effectivePageIds = if (pageIds.isNotEmpty()) pageIds else {
                objs.filter { (_, o) -> isPageDict(o.dict) }.keys.sorted()
            }

            val pages = mutableListOf<Page>()
            var totalChars = 0
            var anyText = false
            var processed = 0
            for (pid in effectivePageIds) {
                if (processed >= MAX_PAGES) break
                processed++
                val contentBytes = contentBytesFor(pid, objs)
                val pageTextRaw = if (contentBytes.isEmpty()) "" else {
                    val s = String(contentBytes, Charsets.ISO_8859_1)
                    extractText(s)
                }
                var pageText = pageTextRaw
                if (totalChars + pageText.length > MAX_CHARS) {
                    val allowed = MAX_CHARS - totalChars
                    pageText = if (allowed <= 0) "" else pageText.substring(0, allowed)
                }
                totalChars += pageText.length
                if (pageText.isNotBlank()) anyText = true
                pages.add(Page(processed - 1, pageText))
                if (totalChars >= MAX_CHARS) break
            }

            if (pages.isEmpty()) {
                val streamPages = mutableListOf<String>()
                for ((_, o) in objs) {
                    val b = o.stream ?: continue
                    val s = String(b, Charsets.ISO_8859_1)
                    if (!looksLikeContentStream(s)) continue
                    val t = extractText(s)
                    if (t.isNotBlank()) streamPages.add(t)
                }
                if (streamPages.isNotEmpty()) {
                    streamPages.forEachIndexed { i, t ->
                        val used = pages.sumOf { it.text.length }
                        val allowed = (MAX_CHARS - used).coerceAtLeast(0)
                        pages.add(Page(i, t.take(allowed)))
                    }
                    anyText = true
                }
            }

            if (!anyText) return Result(emptyList(), "no text layer found")
            Result(pages, "")
        } catch (e: Exception) {
            Result(emptyList(), "error: ${e.message ?: e::class.simpleName}")
        }
    }

    private data class Obj(val id: Int, val dict: String, val stream: ByteArray?)

    private fun parseObjects(rawText: String): Map<Int, Obj> {
        val result = linkedMapOf<Int, Obj>()
        val regex = Regex("""(\d+)\s+(\d+)\s+obj\b(.*?)(?:endobj|$)""", RegexOption.DOT_MATCHES_ALL)
        for (m in regex.findAll(rawText)) {
            val id = m.groupValues[1].toIntOrNull() ?: continue
            val body = m.groupValues[3]
            val streamMatch = Regex("""stream\r?\n""").find(body)
            if (streamMatch != null) {
                val dict = body.substring(0, streamMatch.range.first)
                val dataStart = streamMatch.range.last + 1
                val endIdx = body.lastIndexOf("endstream")
                val end = if (endIdx >= 0) endIdx else body.length
                var rawStream = body.substring(dataStart, end)
                if (rawStream.endsWith("\r\n")) rawStream = rawStream.substring(0, rawStream.length - 2)
                else if (rawStream.endsWith("\n") || rawStream.endsWith("\r")) rawStream = rawStream.substring(0, rawStream.length - 1)
                val rawBytes = rawStream.toByteArray(Charsets.ISO_8859_1)
                val decoded = decodeStream(dict, rawBytes)
                result[id] = Obj(id, dict, decoded)
            } else {
                result[id] = Obj(id, body, null)
            }
        }
        return result
    }

    private fun decodeStream(dict: String, raw: ByteArray): ByteArray {
        val filterMatch = Regex("""/Filter\s*(\[[^\]]*\]|/\S+)""").find(dict)
        val filterStr = filterMatch?.value ?: ""
        val flate = filterStr.contains("FlateDecode")
        if (!flate) return raw
        val inflater = Inflater()
        inflater.setInput(raw)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = inflater.inflate(buf)
            if (n > 0) {
                total += n
                if (total > MAX_STREAM_BYTES) {
                    inflater.end()
                    throw IllegalStateException("decompressed stream too large")
                }
                out.write(buf, 0, n)
            } else {
                if (inflater.finished()) break
                if (inflater.needsInput()) break
                break
            }
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun isPageDict(dict: String): Boolean =
        Regex("""/Type\s*/Page(?!s)""").containsMatchIn(dict)

    private fun collectPages(rawText: String, objs: Map<Int, Obj>): List<Int> {
        val pages = mutableListOf<Int>()
        val rootId = Regex("""/Root\s+(\d+)\s+\d+\s+R""").find(rawText)?.groupValues?.get(1)?.toIntOrNull()
        if (rootId != null) {
            val visited = mutableSetOf<Int>()
            fun traverse(id: Int) {
                if (!visited.add(id)) return
                val o = objs[id] ?: return
                val dict = o.dict
                if (isPageDict(dict)) {
                    pages.add(id)
                    return
                }
                val pagesRef = Regex("""/Pages\s+(\d+)\s+\d+\s+R""").find(dict)?.groupValues?.get(1)?.toIntOrNull()
                if (pagesRef != null) traverse(pagesRef)
                val kidsMatch = Regex("""/Kids\s*\[([^\]]*)\]""").find(dict)
                if (kidsMatch != null) {
                    for (r in Regex("""(\d+)\s+\d+\s+R""").findAll(kidsMatch.groupValues[1])) {
                        traverse(r.groupValues[1].toInt())
                    }
                }
            }
            traverse(rootId)
        }
        if (pages.isEmpty()) {
            for ((id, o) in objs) {
                if (isPageDict(o.dict)) pages.add(id)
            }
            pages.sort()
        }
        return pages
    }

    private fun contentBytesFor(pageId: Int, objs: Map<Int, Obj>): ByteArray {
        val o = objs[pageId] ?: return byteArrayOf()
        val contentsMatch = Regex("""/Contents\s*(\[[^\]]*\]|(\d+)\s+(\d+)\s+R)""").find(o.dict)
            ?: return byteArrayOf()
        val refs = Regex("""(\d+)\s+\d+\s+R""").findAll(contentsMatch.value).map { it.groupValues[1].toInt() }.toList()
        if (refs.isEmpty()) return byteArrayOf()
        val out = ByteArrayOutputStream()
        for (rid in refs) {
            val co = objs[rid] ?: continue
            val b = co.stream
            if (b != null) out.write(b)
        }
        return out.toByteArray()
    }

    private fun looksLikeContentStream(s: String): Boolean =
        s.contains("Tj") || s.contains("TJ") || s.contains("'")

    private fun extractText(content: String): String {
        val tokens = parseTokens(content)
        return interpret(tokens)
    }

    private sealed class Tok {
        data class Num(val v: Double) : Tok()
        data class Str(val v: String) : Tok()
        data class Hex(val v: String) : Tok()
        data class Name(val v: String) : Tok()
        data class Arr(val items: List<Tok>) : Tok()
        data class Op(val v: String) : Tok()
    }

    private fun parseTokens(s: String): List<Tok> {
        val toks = mutableListOf<Tok>()
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '%' -> {
                    while (i < n && s[i] != '\n' && s[i] != '\r') i++
                }
                c == '(' -> {
                    val (str, next) = readLiteral(s, i)
                    toks.add(Tok.Str(str))
                    i = next
                }
                c == '<' -> {
                    if (i + 1 < n && s[i + 1] == '<') {
                        var j = s.indexOf(">>", i)
                        if (j < 0) j = n
                        i = if (j < n) j + 2 else n
                    } else {
                        val (hex, next) = readHex(s, i)
                        toks.add(Tok.Hex(hex))
                        i = next
                    }
                }
                c == '[' -> {
                    val (arr, next) = readArray(s, i)
                    toks.add(arr)
                    i = next
                }
                c == ']' || c == '>' -> i++
                else -> {
                    val start = i
                    while (i < n && !s[i].isWhitespace() && s[i] !in "()%<>[]") i++
                    val word = s.substring(start, i)
                    if (word.isEmpty()) {
                        i++
                        continue
                    }
                    val num = word.toDoubleOrNull()
                    if (num != null) toks.add(Tok.Num(num))
                    else if (word.startsWith("/")) toks.add(Tok.Name(word))
                    else toks.add(Tok.Op(word))
                }
            }
        }
        return toks
    }

    private fun readLiteral(s: String, start: Int): Pair<String, Int> {
        var i = start + 1
        val sb = StringBuilder()
        var depth = 1
        val n = s.length
        while (i < n && depth > 0) {
            val c = s[i]
            if (c == '\\') {
                sb.append(c)
                if (i + 1 < n) {
                    sb.append(s[i + 1])
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (c == '(') {
                depth++
                sb.append(c)
                i++
            } else if (c == ')') {
                depth--
                if (depth > 0) sb.append(c)
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        return Pair(decodeLiteral(sb.toString()), i)
    }

    private fun readHex(s: String, start: Int): Pair<String, Int> {
        var i = start + 1
        val sb = StringBuilder()
        val n = s.length
        while (i < n && s[i] != '>') {
            sb.append(s[i])
            i++
        }
        return Pair(sb.toString(), i + 1)
    }

    private fun readArray(s: String, start: Int): Pair<Tok.Arr, Int> {
        var i = start + 1
        val items = mutableListOf<Tok>()
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c.isWhitespace()) {
                i++
                continue
            }
            if (c == ']') return Pair(Tok.Arr(items), i + 1)
            if (c == '(') {
                val (str, next) = readLiteral(s, i)
                items.add(Tok.Str(str))
                i = next
                continue
            }
            if (c == '<') {
                if (i + 1 < n && s[i + 1] == '<') {
                    var j = s.indexOf(">>", i)
                    if (j < 0) j = n
                    i = if (j < n) j + 2 else n
                    continue
                } else {
                    val (hex, next) = readHex(s, i)
                    items.add(Tok.Hex(hex))
                    i = next
                    continue
                }
            }
            if (c == '[') {
                val (arr, next) = readArray(s, i)
                items.add(arr)
                i = next
                continue
            }
            val st = i
            while (i < n && !s[i].isWhitespace() && s[i] !in "()%<>[]") i++
            val word = s.substring(st, i)
            if (word.isNotEmpty()) {
                val num = word.toDoubleOrNull()
                if (num != null) items.add(Tok.Num(num))
                else if (word.startsWith("/")) items.add(Tok.Name(word))
                else items.add(Tok.Op(word))
            }
        }
        return Pair(Tok.Arr(items), n)
    }

    private fun decodeLiteral(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        val n = raw.length
        while (i < n) {
            val c = raw[i]
            if (c == '\\') {
                if (i + 1 >= n) {
                    i++
                    continue
                }
                val nx = raw[i + 1]
                when (nx) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    '(' -> { sb.append('('); i += 2 }
                    ')' -> { sb.append(')'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '\n' -> { i += 2 }
                    '\r' -> {
                        if (i + 2 < n && raw[i + 2] == '\n') i++
                        i += 2
                    }
                    else -> {
                        if (nx in '0'..'7') {
                            var j = i + 1
                            var oct = ""
                            while (j < n && oct.length < 3 && raw[j] in '0'..'7') {
                                oct += raw[j]
                                j++
                            }
                            if (oct.isNotEmpty()) {
                                sb.append(oct.toInt(8).toChar())
                                i = j
                            } else {
                                sb.append(nx)
                                i += 2
                            }
                        } else {
                            sb.append(nx)
                            i += 2
                        }
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun decodeHex(raw: String): String {
        val cleaned = raw.filter { !it.isWhitespace() }
        val hex = if (cleaned.length % 2 != 0) cleaned + "0" else cleaned
        if (hex.isEmpty()) return ""
        return try {
            val bytes = ByteArray(hex.length / 2) { j ->
                hex.substring(j * 2, j * 2 + 2).toInt(16).toByte()
            }
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            } else {
                String(bytes, Charsets.ISO_8859_1)
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun interpret(tokens: List<Tok>): String {
        val stack = mutableListOf<Tok>()
        val completed = mutableListOf<String>()
        var cur = StringBuilder()

        fun finish() {
            completed.add(cur.toString())
            cur = StringBuilder()
        }

        var fontSize = 12.0

        fun pop(): Tok? = if (stack.isEmpty()) null else stack.removeAt(stack.lastIndex)
        fun popNum(): Double {
            val t = pop()
            return if (t is Tok.Num) t.v else 0.0
        }
        fun toText(t: Tok?): String = when (t) {
            is Tok.Str -> t.v
            is Tok.Hex -> decodeHex(t.v)
            else -> ""
        }

        for (tok in tokens) {
            when (tok) {
                is Tok.Num, is Tok.Str, is Tok.Hex, is Tok.Name, is Tok.Arr -> stack.add(tok)
                is Tok.Op -> {
                    val op = tok.v
                    when (op) {
                        "Tj" -> {
                            val o = pop()
                            cur.append(toText(o))
                        }
                        "TJ" -> {
                            val o = pop()
                            if (o is Tok.Arr) {
                                for (item in o.items) {
                                    when (item) {
                                        is Tok.Str, is Tok.Hex -> cur.append(toText(item))
                                        is Tok.Num -> {
                                            if (item.v < -100.0) {
                                                if (cur.isNotEmpty() && !cur.endsWith(" ")) cur.append(' ')
                                            }
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        }
                        "'" -> {
                            val o = pop()
                            finish()
                            cur.append(toText(o))
                        }
                        "\"" -> {
                            val oStr = pop()
                            popNum()
                            popNum()
                            finish()
                            cur.append(toText(oStr))
                        }
                        "Td", "TD" -> {
                            val ty = popNum()
                            val tx = popNum()
                            if (ty != 0.0) finish()
                            else if (tx > 1.0 && cur.isNotEmpty() && !cur.endsWith(" ")) cur.append(' ')
                        }
                        "T*" -> finish()
                        "Tm" -> {
                            repeat(6) { popNum() }
                        }
                        "Tf" -> {
                            val size = popNum()
                            if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                            if (size != 0.0) fontSize = size
                        }
                        "BT" -> {
                            if (cur.isNotEmpty()) finish()
                        }
                        "ET" -> {
                            if (cur.isNotEmpty()) finish()
                        }
                        else -> {}
                    }
                    stack.clear()
                }
            }
        }
        if (cur.isNotEmpty()) finish()
        while (completed.isNotEmpty() && completed.last().isEmpty()) completed.removeAt(completed.lastIndex)
        return completed.joinToString("\n")
    }
}
