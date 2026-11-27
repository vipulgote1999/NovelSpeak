package app.novelspeak.manga

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import app.novelspeak.DiagLog
import com.google.mlkit.nl.translate.Translator

/**
 * Runs the per-page pipeline off the UI thread: load image, OCR it into bubble boxes, sort the
 * bubbles into the page's reading order, then split each bubble into speech chunks. Results
 * arrive in page order, which lets the reader append text to the playback queue while later pages
 * are still being processed.
 *
 * All callbacks are delivered on the session worker thread; hosts must post to their main
 * thread before touching views or the shared [app.novelspeak.Reader].
 */
class MangaSession(
    context: Context,
    private val pages: List<MangaPageSource>,
    private val loader: MangaPageLoader,
    private val ocrLanguage: String,
    private val translateTo: String?,
    private val maxChunkChars: Int,
    private val rightToLeft: Boolean,
    private val listener: Listener
) {

    interface Listener {
        fun onPageStarted(pageIndex: Int, total: Int)
        fun onPageImage(pageIndex: Int, bitmap: Bitmap)
        fun onPageReady(result: PageResult)
        fun onPageFailed(pageIndex: Int, error: Throwable)
        fun onFinished()
    }

    data class PageResult(
        val pageIndex: Int,
        /** Bubbles in reading order; indices match the overlay boxes on the page. */
        val blocks: List<OcrBlock>,
        /** Display text per bubble, for accessibility / the "original" line in the UI. */
        val texts: List<String>,
        /** Speech chunks in reading order, each mapped to its source bubble. */
        val chunks: List<MangaChunk>,
        val width: Int,
        val height: Int
    )

    private val appContext = context.applicationContext
    @Volatile private var cancelled = false
    private var worker: Thread? = null
    private var translator: Translator? = null

    fun start() {
        if (worker != null) return
        worker = Thread({ run() }, "manga-ocr").apply {
            isDaemon = true
            start()
        }
    }

    fun cancel() {
        cancelled = true
        worker?.interrupt()
    }

    private fun run() {
        val title = "source=$ocrLanguage translate=${translateTo ?: "off"} pages=${pages.size}"
        DiagLog.line(appContext, "manga session start $title")
        var totalChunks = 0
        var totalChars = 0
        var maxChunk = 0
        var minChunk = Int.MAX_VALUE
        var shortChunks = 0
        var pagesWithText = 0
        try {
            translator = if (translateTo != null) {
                MangaTranslator.translatorFor(ocrLanguage, translateTo)
            } else {
                null
            }
            for (i in pages.indices) {
                if (cancelled) return
                val page = pages[i]
                listener.onPageStarted(i, pages.size)
                val loadStart = SystemClock.elapsedRealtime()
                val bitmap = try {
                    loader.load(page)
                } catch (t: Throwable) {
                    null
                }
                if (bitmap == null) {
                    listener.onPageFailed(i, java.io.IOException("Could not load page ${i + 1}"))
                    continue
                }
                listener.onPageImage(i, bitmap)
                if (cancelled) return
                val loadMs = SystemClock.elapsedRealtime() - loadStart
                val ocrStart = SystemClock.elapsedRealtime()
                val raw = try {
                    MangaOcr.recognizeBlocking(bitmap, ocrLanguage)
                } catch (t: Throwable) {
                    listener.onPageFailed(i, t)
                    continue
                }
                if (cancelled) return
                val ocrMs = SystemClock.elapsedRealtime() - ocrStart
                val blocks = orderBlocks(raw)
                val texts = ArrayList<String>(blocks.size)
                val chunks = ArrayList<MangaChunk>()
                for (index in blocks.indices) {
                    val block = blocks[index]
                    var text = block.text
                    if (translator != null) {
                        text = MangaTranslator.translateBlocking(translator, text)
                    }
                    texts.add(text)
                    for (chunk in MangaTextLayout.chunks(text, maxChunkChars)) {
                        chunks.add(MangaChunk(index, chunk, block.text))
                    }
                }
                val speech = coalesce(chunks, maxChunkChars, bitmap.height, blocks)
                if (cancelled) return
                val longest = speech.maxOfOrNull { it.text.length } ?: 0
                val pageChars = speech.sumOf { it.text.length }
                if (speech.isNotEmpty()) pagesWithText++
                for (chunk in speech) {
                    totalChunks++
                    totalChars += chunk.text.length
                    if (chunk.text.length > maxChunk) maxChunk = chunk.text.length
                    if (chunk.text.length < minChunk) minChunk = chunk.text.length
                    if (chunk.text.length < MangaTextLayout.MIN_SPEAKABLE_CHARS) shortChunks++
                }
                DiagLog.line(
                    appContext,
                    "manga page ${i + 1}/${pages.size} load=${loadMs}ms ocr=${ocrMs}ms " +
                        "boxes=${blocks.size} chunks=${speech.size} chars=$pageChars " +
                        "maxChunk=$longest size=${bitmap.width}x${bitmap.height}"
                )
                listener.onPageReady(
                    PageResult(i, blocks, texts, speech, bitmap.width, bitmap.height)
                )
            }
            val shortest = if (minChunk == Int.MAX_VALUE) 0 else minChunk
            DiagLog.line(
                appContext,
                "manga summary pages=${pages.size} pagesWithText=$pagesWithText " +
                    "chunks=$totalChunks chars=$totalChars maxChunk=$maxChunk " +
                    "minChunk=$shortest short<${MangaTextLayout.MIN_SPEAKABLE_CHARS}=$shortChunks"
            )
        } catch (t: Throwable) {
            DiagLog.exception(appContext, "manga session failed", t)
        } finally {
            MangaTranslator.close(translator)
            translator = null
            if (!cancelled) listener.onFinished()
        }
    }

    /**
     * Adjacent short bubbles are joined into one utterance. Manga dialogue is full of
     * two-to-ten-character lines ("DON!!", "WHAT?!", "LUFFY!"); synthesising each one alone
     * gives a neural voice no prosodic context and makes playback sound like a list of beeps.
     * Joining also keeps the queue shorter while the cap still guarantees responsive chunks.
     */
    private fun coalesce(
        chunks: List<MangaChunk>,
        maxChars: Int,
        pageHeight: Int,
        blocks: List<OcrBlock>
    ): List<MangaChunk> {
        if (chunks.size < 2) return chunks
        val cap = maxChars.coerceIn(60, 400)
        val out = ArrayList<MangaChunk>(chunks.size)
        var current = chunks[0]
        for (i in 1 until chunks.size) {
            val next = chunks[i]
            val combined = current.text.length + 1 + next.text.length
            val short = current.text.length < MangaTextLayout.MIN_SPEAKABLE_CHARS ||
                next.text.length < MangaTextLayout.MIN_SPEAKABLE_CHARS
            if (short && combined <= cap &&
                sameSpeechColumn(current.blockIndices, next.blockIndices, blocks, pageHeight)
            ) {
                val indices = (current.blockIndices + next.blockIndices).distinct()
                current = MangaChunk(
                    blockIndex = current.blockIndex,
                    text = current.text + " " + next.text,
                    originalText = current.originalText + " " + next.originalText,
                    blockIndices = indices
                )
            } else {
                out.add(current)
                current = next
            }
        }
        out.add(current)
        return out
    }

    /**
     * Short bubbles are only joined when they sit in the same vertical column and the gap is
     * small. Without this guard a row of unrelated panels turns into one long, context-free
     * sentence and the highlight spans half the page.
     */
    private fun sameSpeechColumn(
        first: List<Int>,
        second: List<Int>,
        blocks: List<OcrBlock>,
        pageHeight: Int
    ): Boolean {
        val a = unionBox(first, blocks) ?: return true
        val b = unionBox(second, blocks) ?: return true
        if (pageHeight <= 0) return true
        val minWidth = minOf(a.width(), b.width())
        if (minWidth <= 0f) return true
        val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        if (overlap / minWidth < 0.30f) return false
        val gap = maxOf(0f, maxOf(a.top, b.top) - minOf(a.bottom, b.bottom))
        return gap <= pageHeight * 0.30f
    }

    private fun unionBox(indices: List<Int>, blocks: List<OcrBlock>): android.graphics.RectF? {
        var out: android.graphics.RectF? = null
        for (index in indices) {
            val box = blocks.getOrNull(index)?.boundingBox ?: continue
            if (box.isEmpty) continue
            val rect = android.graphics.RectF(box)
            val existing = out
            if (existing == null) out = rect else existing.union(rect)
        }
        return out
    }

    /** Reorders ML Kit's detection order into the order a reader would speak the bubbles. */
    private fun orderBlocks(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.size < 2) return blocks
        val lines = blocks.map {
            val box = it.boundingBox
            MangaLine(it.text, box.left, box.top, box.right, box.bottom)
        }
        val order = MangaTextLayout.readingOrder(lines, rightToLeft)
        val out = ArrayList<OcrBlock>(blocks.size)
        for ((pageIndex, sourceIndex) in order.withIndex()) {
            val original = blocks[sourceIndex]
            out.add(original.copy(lineIndex = pageIndex))
        }
        return out
    }
}
