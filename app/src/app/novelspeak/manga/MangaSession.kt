package app.novelspeak.manga

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import app.novelspeak.DiagLog
import com.google.mlkit.nl.translate.Translator

/**
 * Runs the per-page pipeline off the UI thread: load image, OCR it into bubble boxes, then
 * optionally translate each bubble. Results arrive in page order, which lets the reader
 * append text to the playback queue while later pages are still being processed.
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
        val blocks: List<OcrBlock>,
        /** Display text per block: translated when translation is on, otherwise the OCR text. */
        val texts: List<String>
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
        val title = "source=${ocrLanguage} translate=${translateTo ?: "off"} pages=${pages.size}"
        DiagLog.line(appContext, "manga session start $title")
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
                val blocks = try {
                    MangaOcr.recognizeBlocking(bitmap, ocrLanguage)
                } catch (t: Throwable) {
                    listener.onPageFailed(i, t)
                    continue
                }
                if (cancelled) return
                val ocrMs = SystemClock.elapsedRealtime() - ocrStart
                val texts = ArrayList<String>(blocks.size)
                for (block in blocks) {
                    var text = block.text
                    if (translator != null) {
                        text = MangaTranslator.translateBlocking(translator, text)
                    }
                    texts.add(text)
                }
                if (cancelled) return
                DiagLog.line(
                    appContext,
                    "manga page ${i + 1}/${pages.size} load=${loadMs}ms ocr=${ocrMs}ms " +
                        "boxes=${blocks.size} size=${bitmap.width}x${bitmap.height}"
                )
                listener.onPageReady(PageResult(i, blocks, texts))
            }
            DiagLog.line(appContext, "manga session finished pages=${pages.size}")
        } catch (t: Throwable) {
            DiagLog.exception(appContext, "manga session failed", t)
        } finally {
            MangaTranslator.close(translator)
            translator = null
            if (!cancelled) listener.onFinished()
        }
    }
}
