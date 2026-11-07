package app.novelspeak.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * On-device OCR for manga pages using ML Kit. The recognizer is chosen from the user's
 * preferred source language so Japanese/Chinese/Korean manga is read with the matching
 * script model instead of the Latin one.
 *
 * Recognizers are expensive to create and hold downloaded models, so one client is kept per
 * language for the process lifetime. Calls are serialized per language; a failed client is
 * discarded so the next page retries with a clean one.
 */
object MangaOcr {

    const val LANG_LATIN = "latin"
    const val LANG_JAPANESE = "japanese"
    const val LANG_CHINESE = "chinese"
    const val LANG_KOREAN = "korean"
    const val LANG_DEVANAGARI = "devanagari"

    private val recognizers = HashMap<String, TextRecognizer>()
    private val locks = HashMap<String, ReentrantLock>()
    private val clientsLock = Any()

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

    /**
     * Recognizes [bitmap] and returns one [OcrBlock] per ML Kit text block (speech bubble).
     * Lines inside a bubble are joined, and the bounding box is their union, so TTS speaks a
     * whole bubble and the UI can highlight the whole box.
     */
    fun recognizeBlocking(bitmap: Bitmap, language: String): List<OcrBlock> {
        val pair = clientFor(language)
        val recognizer = pair.first
        val lock = pair.second
        lock.withLock {
            val latch = java.util.concurrent.CountDownLatch(1)
            var result: List<OcrBlock> = emptyList()
            var error: Throwable? = null
            val image = InputImage.fromBitmap(bitmap, 0)
            try {
                recognizer.process(image)
                    .addOnSuccessListener { res: Text -> result = toBlocks(res) }
                    .addOnFailureListener { e -> error = e }
                    .addOnCompleteListener { latch.countDown() }
                latch.await()
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                throw t
            } catch (t: Throwable) {
                error = t
            }
            if (error != null) {
                discardClient(language, recognizer)
                throw error!!
            }
            return result
        }
    }

    /** Every OCR line with its own tight box. Useful for debugging or fine-grained overlays. */
    fun recognizeLinesBlocking(bitmap: Bitmap, language: String): List<OcrBlock> {
        val pair = clientFor(language)
        val recognizer = pair.first
        val lock = pair.second
        lock.withLock {
            val latch = java.util.concurrent.CountDownLatch(1)
            var result: List<OcrBlock> = emptyList()
            var error: Throwable? = null
            try {
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { res: Text -> result = toLines(res) }
                    .addOnFailureListener { e -> error = e }
                    .addOnCompleteListener { latch.countDown() }
                latch.await()
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                throw t
            } catch (t: Throwable) {
                error = t
            }
            if (error != null) {
                discardClient(language, recognizer)
                throw error!!
            }
            return result
        }
    }

    private fun toBlocks(res: Text): List<OcrBlock> {
        val out = ArrayList<OcrBlock>(res.textBlocks.size)
        var index = 0
        for (block in res.textBlocks) {
            val text = StringBuilder()
            var box: RectF? = null
            for (line in block.lines) {
                val lineText = line.text.trim()
                if (lineText.isEmpty()) continue
                if (text.isNotEmpty()) text.append(' ')
                text.append(lineText)
                val bb = line.boundingBox
                if (bb != null) {
                    val r = RectF(
                        bb.left.toFloat(), bb.top.toFloat(),
                        bb.right.toFloat(), bb.bottom.toFloat()
                    )
                    val existing = box
                    if (existing == null) box = r else existing.union(r)
                }
            }
            if (text.isEmpty()) continue
            val rect = box ?: block.boundingBox?.let {
                RectF(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
            } ?: RectF()
            out.add(OcrBlock(text.toString(), rect, 1f, index++))
        }
        if (out.isEmpty() && res.text.isNotBlank()) {
            // Very defensive fallback: a recognizer returned text without block structure.
            out.add(OcrBlock(res.text.trim(), RectF(), 1f, 0))
        }
        return out
    }

    private fun toLines(res: Text): List<OcrBlock> {
        val out = ArrayList<OcrBlock>()
        var index = 0
        for (block in res.textBlocks) {
            for (line in block.lines) {
                val text = line.text.trim()
                if (text.isEmpty()) continue
                val bb = line.boundingBox
                val rect = if (bb != null) {
                    RectF(bb.left.toFloat(), bb.top.toFloat(), bb.right.toFloat(), bb.bottom.toFloat())
                } else {
                    RectF()
                }
                out.add(OcrBlock(text, rect, 1f, index++))
            }
        }
        return out
    }

    private fun clientFor(language: String): Pair<TextRecognizer, ReentrantLock> {
        val key = supportedLanguages().firstOrNull { it == language } ?: LANG_LATIN
        synchronized(clientsLock) {
            val lock = locks.getOrPut(key) { ReentrantLock() }
            val existing = recognizers[key]
            if (existing != null) return Pair(existing, lock)
            val created = when (key) {
                LANG_JAPANESE -> TextRecognition.getClient(
                    JapaneseTextRecognizerOptions.Builder().build()
                )
                LANG_CHINESE -> TextRecognition.getClient(
                    ChineseTextRecognizerOptions.Builder().build()
                )
                LANG_KOREAN -> TextRecognition.getClient(
                    KoreanTextRecognizerOptions.Builder().build()
                )
                LANG_DEVANAGARI -> TextRecognition.getClient(
                    DevanagariTextRecognizerOptions.Builder().build()
                )
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
            recognizers[key] = created
            return Pair(created, lock)
        }
    }

    private fun discardClient(language: String, client: TextRecognizer) {
        synchronized(clientsLock) {
            val key = supportedLanguages().firstOrNull { it == language } ?: LANG_LATIN
            if (recognizers[key] === client) {
                recognizers.remove(key)
                try { client.close() } catch (ignored: Throwable) {}
            }
        }
    }

    suspend fun recognize(context: Context, uri: Uri, language: String): List<OcrBlock> {
        val bitmap = loadBitmap(context, uri) ?: return emptyList()
        return recognizeBlocking(bitmap, language)
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
