package app.novelspeak.library

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A book imported from EPUB/PDF/plain text.
 *
 * The extracted sentences live in app-private storage (filesDir/imports/<id>.txt, one sentence
 * per line); this record is the durable index entry shown in Library and Home. The original
 * document is not kept: once imported, playback and progress no longer depend on the source file
 * or on SAF permissions.
 */
data class ImportedBook(
    val id: String,
    val title: String,
    val author: String,
    val kind: String,
    val sentenceCount: Int,
    val charCount: Int,
    val importedAt: Long,
    val updatedAt: Long,
    val position: Int,
    val finished: Boolean = false
) {
    val percent: Int
        get() = if (sentenceCount <= 0) 0
        else ((position.toLong() * 100L) / sentenceCount).toInt().coerceIn(0, 100)
}

/**
 * Durable store for imported books.
 *
 * - Text: filesDir/imports/<id>.txt (newline-separated sentences; sentences never contain
 *   newlines after SentenceSplitter, and any stray newline is flattened on save).
 * - Index: filesDir/imports/index.json (tolerant; corruption degrades to "no imports", never
 *   crashes the Library).
 * - Caps: 5,000 sentences and ~6 MB text per book, 100 books total (oldest pruned).
 * - All operations are synchronized: imports happen on a background thread while the UI reads.
 */
class ImportedBookStore(context: Context) {

    private val dir: File =
        File(context.applicationContext.filesDir, "imports").apply { if (!exists()) mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val lock = Any()

    fun save(title: String, author: String, kind: String, sentences: List<String>): ImportedBook? {
        if (sentences.isEmpty()) return null
        val clean = ArrayList<String>(minOf(sentences.size, MAX_SENTENCES))
        for (raw in sentences) {
            if (clean.size >= MAX_SENTENCES) break
            val line = raw.replace('\n', ' ').replace('\r', ' ').trim()
            if (line.isEmpty()) continue
            clean.add(line)
        }
        if (clean.isEmpty()) return null
        val text = clean.joinToString("\n")
        if (text.length > MAX_TEXT_CHARS) return null
        synchronized(lock) {
            val list = readIndex()
            val book = ImportedBook(
                id = newId(),
                title = title.trim().ifBlank { "Imported book" },
                author = author.trim(),
                kind = kind,
                sentenceCount = clean.size,
                charCount = text.length,
                importedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                position = 0,
                finished = false
            )
            return try {
                textFile(book.id).writeText(text)
                list.add(0, book)
                while (list.size > MAX_BOOKS) {
                    val old = list.removeAt(list.lastIndex)
                    textFile(old.id).delete()
                }
                writeIndex(list)
                book
            } catch (t: Throwable) {
                textFile(book.id).delete()
                null
            }
        }
    }

    fun list(): List<ImportedBook> = synchronized(lock) { readIndex() }

    fun get(id: String): ImportedBook? =
        synchronized(lock) { readIndex().firstOrNull { it.id == id } }

    fun loadSentences(id: String): List<String> = synchronized(lock) {
        try {
            val file = textFile(id)
            if (!file.isFile) return emptyList()
            file.readText().split('\n').filter { it.isNotBlank() }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun updateProgress(id: String, position: Int, total: Int = -1): Boolean =
        synchronized(lock) {
            val list = readIndex()
            val i = list.indexOfFirst { it.id == id }
            if (i < 0) return false
            val old = list[i]
            val safeTotal = if (total > 0) total else old.sentenceCount
            val safePos = position.coerceIn(0, (safeTotal - 1).coerceAtLeast(0))
            list[i] = old.copy(
                position = safePos,
                sentenceCount = safeTotal,
                updatedAt = System.currentTimeMillis()
            )
            writeIndex(list)
            true
        }

    fun markFinished(id: String): Boolean = synchronized(lock) {
        val list = readIndex()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        list[i] = list[i].copy(finished = true, updatedAt = System.currentTimeMillis())
        writeIndex(list)
        true
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val list = readIndex()
        val removed = list.removeAll { it.id == id }
        textFile(id).delete()
        if (removed) writeIndex(list)
        removed
    }

    fun clear(): Boolean = synchronized(lock) {
        try {
            dir.listFiles()?.forEach { it.delete() }
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun newId(): String =
        "imp-" + System.currentTimeMillis().toString(36) + "-" +
            Integer.toHexString((System.nanoTime() and 0xFFFFFF).toInt())

    private fun textFile(id: String): File = File(dir, id + ".txt")

    private fun readIndex(): MutableList<ImportedBook> {
        val out = ArrayList<ImportedBook>()
        try {
            if (!indexFile.isFile) return out
            val array = JSONArray(indexFile.readText())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isBlank()) continue
                out.add(
                    ImportedBook(
                        id = id,
                        title = o.optString("title", "Imported book"),
                        author = o.optString("author", ""),
                        kind = o.optString("kind", "TEXT"),
                        sentenceCount = o.optInt("sentences", 0),
                        charCount = o.optInt("chars", 0),
                        importedAt = o.optLong("importedAt", 0L),
                        updatedAt = o.optLong("updatedAt", 0L),
                        position = o.optInt("position", 0),
                        finished = o.optBoolean("finished", false)
                    )
                )
            }
            out.sortByDescending { it.updatedAt }
        } catch (t: Throwable) {
            out.clear()
        }
        return out
    }

    private fun writeIndex(list: List<ImportedBook>) {
        val array = JSONArray()
        for (b in list) {
            array.put(
                JSONObject().apply {
                    put("id", b.id)
                    put("title", b.title)
                    put("author", b.author)
                    put("kind", b.kind)
                    put("sentences", b.sentenceCount)
                    put("chars", b.charCount)
                    put("importedAt", b.importedAt)
                    put("updatedAt", b.updatedAt)
                    put("position", b.position)
                    put("finished", b.finished)
                }
            )
        }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(array.toString())
            tmp.delete()
        }
    }

    private companion object {
        const val MAX_SENTENCES = 5_000
        const val MAX_TEXT_CHARS = 6_000_000
        const val MAX_BOOKS = 100
    }
}
