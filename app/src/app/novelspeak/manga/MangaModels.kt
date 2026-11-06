package app.novelspeak.manga

import android.graphics.RectF

/**
 * One text region detected by OCR on a manga page. [boundingBox] is in image coordinates
 * and is used to highlight the region currently being read aloud.
 */
data class OcrBlock(
    val text: String,
    val boundingBox: RectF,
    val confidence: Float = 1f,
    val lineIndex: Int = 0
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