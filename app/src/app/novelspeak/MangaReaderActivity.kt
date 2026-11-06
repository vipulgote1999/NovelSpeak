package app.novelspeak

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.novelspeak.manga.MangaOcr
import app.novelspeak.manga.MangaTranslator
import app.novelspeak.manga.OcrBlock
import com.google.mlkit.nl.translate.Translator
import java.io.InputStream

/**
 * Image-based manga reader. Extracts text from the page with on-device ML Kit OCR, optionally
 * translates it into the user's preferred language, then feeds the chunks to the shared
 * TTS reader. Story cast / dialogue parsing is intentionally disabled for manga: every
 * extracted line is read by the narrator.
 */
class MangaReaderActivity : Activity(), Reader.Listener {

    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var chunksView: LinearLayout
    private lateinit var readButton: Button
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button

    private val ui = Handler(Looper.getMainLooper())
    private val reader: Reader get() = NovelSpeakApp.instance.reader

    private var pageUri: Uri? = null
    private var blocks: List<OcrBlock> = emptyList()
    private var translatedTexts: List<String> = emptyList()
    private var translator: Translator? = null
    private var currentIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        status = TextView(this).apply {
            text = "Open a manga page image to read it aloud."
            textSize = 13f
            setTextColor(Palette.MUTED)
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(status)

        image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Palette.SURFACE)
        }
        root.addView(image)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val openButton = Button(this).apply {
            text = "Open image"
            setOnClickListener {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                startActivityForResult(pick, REQ_PICK)
            }
        }
        readButton = Button(this).apply { text = "OCR + Read"; setOnClickListener { runOcrAndRead() } }
        prevButton = Button(this).apply { text = "◀"; setOnClickListener { reader.prevSentence() } }
        nextButton = Button(this).apply { text = "▶"; setOnClickListener { reader.nextSentence() } }
        val settingsButton = Button(this).apply { text = "⚙"; setOnClickListener { showSettingsDialog() } }
        controls.addView(openButton)
        controls.addView(readButton)
        controls.addView(prevButton)
        controls.addView(nextButton)
        controls.addView(settingsButton)
        root.addView(controls)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(160)
            )
        }
        chunksView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(chunksView)
        root.addView(scroll)

        setContentView(root)

        reader.addListener(this)

        val uriStr = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (uriStr != null) {
            pageUri = Uri.parse(uriStr)
            loadImage(pageUri!!)
        } else {
            status.text = "Tap \"Open image\" to choose a manga page."
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            pageUri = data?.data
            if (pageUri != null) loadImage(pageUri!!)
        }
    }

    private fun loadImage(uri: Uri) {
        try {
            val bmp = openBitmap(uri)
            if (bmp != null) image.setImageBitmap(bmp)
            status.text = "Page loaded. Tap OCR + Read."
        } catch (t: Throwable) {
            status.text = "Could not load image: ${t.message}"
        }
    }

    private fun openBitmap(uri: Uri): Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (t: Throwable) { null }
    }

    private fun runOcrAndRead() {
        val uri = pageUri ?: return
        status.text = "Running OCR…"
        readButton.isEnabled = false
        val lang = NovelSpeakApp.instance.prefs.mangaOcrLanguage
        val resolvedLang = if (lang == "auto") MangaOcr.fromLanguageCode(NovelSpeakApp.instance.prefs.mangaTargetLanguage) else lang
        Thread {
            try {
                val bmp = openBitmap(uri) ?: throw IllegalStateException("no bitmap")
                val ocrBlocks = MangaOcr.recognizeBlocking(bmp, resolvedLang)
                ui.post {
                    blocks = ocrBlocks
                    renderChunks()
                    if (blocks.isEmpty()) {
                        status.text = "No text detected on this page."
                        readButton.isEnabled = true
                        return@post
                    }
                    status.text = "OCR found ${blocks.size} text regions. Preparing TTS…"
                    feedReader(resolvedLang)
                }
            } catch (t: Throwable) {
                ui.post {
                    status.text = "OCR failed: ${t.message}"
                    readButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun feedReader(ocrLang: String) {
        val prefs = NovelSpeakApp.instance.prefs
        val targetLang = prefs.mangaTargetLanguage
        val doTranslate = prefs.mangaTranslate
        translator = if (doTranslate) MangaTranslator.translatorFor(ocrLang, targetLang) else null

        Thread {
            val texts = ArrayList<String>()
            for (b in blocks) {
                var t = b.text
                if (doTranslate) {
                    t = MangaTranslator.translateBlocking(translator, t)
                }
                texts.add(t)
            }
            ui.post {
                translatedTexts = texts
                val readerBlocks = texts.mapIndexed { i, t ->
                    Reader.Block(text = t, blockIndex = i, charStart = -1, charEnd = -1)
                }
                reader.setContent("Manga page", readerBlocks)
                // Disable story cast for manga: force narrator-only (already no cast).
                status.text = "Ready · ${texts.size} chunks. Tap Play."
                readButton.isEnabled = true
                renderChunks()
            }
        }.start()
    }

    private fun renderChunks() {
        chunksView.removeAllViews()
        val list = if (translatedTexts.isNotEmpty()) translatedTexts else blocks.map { it.text }
        for (i in list.indices) {
            val tv = TextView(this).apply {
                text = "${i + 1}. ${list[i]}"
                textSize = 12f
                setTextColor(if (i == currentIndex) Palette.ACCENT else Palette.TEXT)
                setPadding(dp(4), dp(2), dp(4), dp(2))
            }
            chunksView.addView(tv)
        }
    }

    override fun onReaderState(state: Reader.State) {}

    override fun onHighlight(blockIndex: Int) {
        currentIndex = blockIndex.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
        renderChunks()
    }

    override fun onSegmentHighlight(blockIndex: Int, charStart: Int, charEnd: Int, speaker: String) {}

    override fun onWordHighlight(blockIndex: Int, charStart: Int, charEnd: Int) {}

    private fun showSettingsDialog() {
        val prefs = NovelSpeakApp.instance.prefs
        val ocrOptions = MangaOcr.supportedLanguages().map { MangaOcr.displayName(it) }.toTypedArray()
        val ocrCurrent = MangaOcr.supportedLanguages().indexOf(prefs.mangaOcrLanguage).coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle("Manga settings")
            .setSingleChoiceItems(ocrOptions, ocrCurrent) { d, which ->
                prefs.mangaOcrLanguage = MangaOcr.supportedLanguages()[which]
                d.dismiss()
            }
            .setPositiveButton("Translate: ${if (prefs.mangaTranslate) "ON" else "OFF"}") { _, _ ->
                prefs.mangaTranslate = !prefs.mangaTranslate
                Toast.makeText(this, "Translate toggled: ${prefs.mangaTranslate}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        reader.removeListener(this)
        MangaTranslator.close(translator)
    }

    companion object {
        const val EXTRA_IMAGE_URI = "manga_image_uri"
        private const val REQ_PICK = 42
    }
}