package app.novelspeak.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR for manga pages using ML Kit. The recognizer is chosen from the user's
 * preferred source language so Japanese/Chinese/Korean manga is read with the matching
 * script model instead of the Latin one.
 */
object MangaOcr {

    const val LANG_LATIN = "latin"
    const val LANG_JAPANESE = "japanese"
    const val LANG_CHINESE = "chinese"
    const val LANG_KOREAN = "korean"
    const val LANG_DEVANAGARI = "devanagari"

    fun supportedLanguages(): List<String> =
        listOf(LANG_LATIN, LANG_JAPANESE, LANG_CHINESE, LANG_KOREAN, LANG_DEVANAGARI)

    fun displayName(lang: String): String = when (lang) {
        LANG_LATIN -> "Latin (en, es, fr, …)"
        LANG_JAPANESE -> "Japanese"
        LANG_CHINESE -> "Chinese"
        LANG_KOREAN -> "Korean"
        LANG_DEVANAGARI -> "Devanagari (hi, mr, …)"
        else -> lang
    }

    /** Map a BCP-47-ish code to an OCR language bucket. */
    fun fromLanguageCode(code: String): String {
        val c = code.lowercase()
        return when {
            c.startsWith("ja") -> LANG_JAPANESE
            c.startsWith("zh") -> LANG_CHINESE
            c.startsWith("ko") -> LANG_KOREAN
            c.startsWith("hi") || c.startsWith("mr") || c.startsWith("ne") -> LANG_DEVANAGARI
            else -> LANG_LATIN
        }
    }

    fun recognizeBlocking(context: Context, uri: Uri, language: String): List<OcrBlock> {
        val bitmap = loadBitmap(context, uri) ?: return emptyList()
        return recognizeBlocking(bitmap, language)
    }

    fun recognizeBlocking(bitmap: Bitmap, language: String): List<OcrBlock> {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: List<OcrBlock> = emptyList()
        var error: Throwable? = null
        recognizeAsync(bitmap, language) { r, e ->
            result = r ?: emptyList()
            error = e
            latch.countDown()
        }
        latch.await()
        if (error != null) throw error!!
        return result
    }

    private fun recognizeAsync(bitmap: Bitmap, language: String, cb: (List<OcrBlock>?, Throwable?) -> Unit) {
        val recognizer = when (language) {
            LANG_JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            LANG_CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            LANG_KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            LANG_DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { res: com.google.mlkit.vision.text.Text ->
                val blocks = ArrayList<OcrBlock>()
                var lineIdx = 0
                for (block in res.textBlocks) {
                    for (line in block.lines) {
                        val bb = line.boundingBox
                        val rect = if (bb != null) RectF(bb.left.toFloat(), bb.top.toFloat(), bb.right.toFloat(), bb.bottom.toFloat()) else RectF()
                        val text = line.text.trim()
                        if (text.isNotEmpty()) blocks.add(OcrBlock(text = text, boundingBox = rect, confidence = 1f, lineIndex = lineIdx++))
                    }
                }
                if (blocks.isEmpty()) {
                    for (block in res.textBlocks) {
                        val bb = block.boundingBox
                        val rect = if (bb != null) RectF(bb.left.toFloat(), bb.top.toFloat(), bb.right.toFloat(), bb.bottom.toFloat()) else RectF()
                        val text = block.text.trim()
                        if (text.isNotEmpty()) blocks.add(OcrBlock(text = text, boundingBox = rect, confidence = 1f, lineIndex = lineIdx++))
                    }
                }
                cb(blocks, null)
            }
            .addOnFailureListener { e -> cb(null, e) }
    }

    suspend fun recognize(context: Context, uri: Uri, language: String): List<OcrBlock> {
        val bitmap = loadBitmap(context, uri) ?: return emptyList()
        return recognize(bitmap, language)
    }

    suspend fun recognize(bitmap: Bitmap, language: String): List<OcrBlock> =
        recognizeBlocking(bitmap, language)

    private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (t: Throwable) {
            null
        }
    }
}