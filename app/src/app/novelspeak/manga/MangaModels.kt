package app.novelspeak.manga

import android.graphics.RectF

/**
 * One text region detected by OCR on a manga page. [boundingBox] is in image coordinates
 * and is used to highlight the region currently being read aloud.
 *
 * A block normally describes one speech bubble: every OCR line inside that bubble is merged
 * into [text] and the box is the union of the lines, so highlighting the "entire text box"
 * matches what the reader is speaking.
 */
data class OcrBlock(
    val text: String,
    val boundingBox: RectF,
    val confidence: Float = 1f,
    val lineIndex: Int = 0
)

/** A page image plus the HTTP referer used when the image host checks hotlinks. */
data class MangaPageSource(
    val uri: String,
    val referer: String? = null
)

/**
 * A speakable chunk mapped back to its page and OCR box. [readerIndex] is the
 * [app.novelspeak.Reader.Block.blockIndex] assigned when the chunk is queued; the reader view
 * uses it to switch pages and move the highlight whenever TTS advances.
 */
data class MangaCue(
    val readerIndex: Int,
    val pageIndex: Int,
    val pageBlockIndex: Int,
    val text: String,
    val originalText: String,
    val boundingBox: RectF
)

data class MangaPage(
    val uri: String,
    val width: Int = 0,
    val height: Int = 0,
    val blocks: List<OcrBlock> = emptyList(),
    val ocrLanguage: String = "latin",
    val ocrDone: Boolean = false,
    val ocrError: String? = null
)

data class MangaBook(
    val id: String,
    val title: String,
    val pages: List<MangaPage>,
    val createdAt: Long = System.currentTimeMillis()
)
