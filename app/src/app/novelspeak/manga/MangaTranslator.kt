package app.novelspeak.manga

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Optional on-device translation of OCR'd manga text into the user's preferred reading
 * language. Translation is best-effort: if the model cannot be downloaded or the language
 * pair is unsupported, callers receive the original text unchanged.
 */
object MangaTranslator {

    /** Returns a translator for [source] -> [target] (BCP-47-ish, lowercased). */
    fun translatorFor(source: String, target: String): Translator? {
        val src = toTranslateLang(source) ?: return null
        val tgt = toTranslateLang(target) ?: return null
        if (src == tgt) return null
        val opts = TranslatorOptions.Builder()
            .setSourceLanguage(src)
            .setTargetLanguage(tgt)
            .build()
        return Translation.getClient(opts)
    }

    private fun toTranslateLang(code: String): String? {
        val c = code.lowercase()
        // ML Kit uses its own language constants. Map common ones.
        return when {
            c.startsWith("ja") -> TranslateLanguage.JAPANESE
            c.startsWith("zh") -> TranslateLanguage.CHINESE
            c.startsWith("ko") -> TranslateLanguage.KOREAN
            c.startsWith("en") -> TranslateLanguage.ENGLISH
            c.startsWith("es") -> TranslateLanguage.SPANISH
            c.startsWith("fr") -> TranslateLanguage.FRENCH
            c.startsWith("de") -> TranslateLanguage.GERMAN
            c.startsWith("it") -> TranslateLanguage.ITALIAN
            c.startsWith("pt") -> TranslateLanguage.PORTUGUESE
            c.startsWith("ru") -> TranslateLanguage.RUSSIAN
            c.startsWith("hi") -> TranslateLanguage.HINDI
            c.startsWith("ar") -> TranslateLanguage.ARABIC
            else -> null
        }
    }

    /**
     * Ensures the model is downloaded, then translates [text]. Returns original on failure.
     */
    suspend fun translate(translator: Translator?, text: String): String = translateBlocking(translator, text)

    fun translateBlocking(translator: Translator?, text: String): String {
        if (translator == null || text.isBlank()) return text
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = text
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    translator.translate(text)
                        .addOnSuccessListener { result = it; latch.countDown() }
                        .addOnFailureListener { latch.countDown() }
                }
                .addOnFailureListener { latch.countDown() }
            latch.await()
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            return text
        } catch (t: Throwable) {
            return text
        }
        return result
    }

    fun close(translator: Translator?) {
        try { translator?.close() } catch (ignored: Throwable) {}
    }
}