package app.novelspeak

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.novelspeak.manga.MangaCue
import app.novelspeak.manga.MangaOcr
import app.novelspeak.manga.MangaPageLoader
import app.novelspeak.manga.MangaPageSource
import app.novelspeak.manga.MangaPageView
import app.novelspeak.manga.MangaSession
import app.novelspeak.manga.OcrBlock
import java.io.File

/**
 * Page-synchronised manga reader.
 *
 * The page image is the primary surface: OCR boxes are drawn over it and the bubble currently
 * being spoken is highlighted. As the shared [Reader] advances, the view automatically turns to
 * the page that owns the active text, so image and speech always come from the same page.
 *
 * OCR runs page by page on a worker thread. Text is appended to the reader queue as pages
 * finish, which means playback can start after the first page instead of waiting for a whole
 * chapter, while later pages keep streaming in behind it.
 */
class MangaReaderActivity : Activity(), Reader.Listener {

    private lateinit var pageView: MangaPageView
    private lateinit var emptyHint: TextView
    private lateinit var titleView: TextView
    private lateinit var pageBadge: TextView
    private lateinit var statusView: TextView
    private lateinit var ocrProgress: TextView
    private lateinit var textPanel: LinearLayout
    private lateinit var currentText: TextView
    private lateinit var originalText: TextView
    private lateinit var playButton: TextView
    private lateinit var prevSentence: TextView
    private lateinit var nextSentence: TextView
    private lateinit var prevPage: TextView
    private lateinit var nextPage: TextView
    private lateinit var speedView: TextView
    private lateinit var ocrButton: TextView
    private lateinit var ocrProgressRow: LinearLayout

    private val ui = Handler(Looper.getMainLooper())
    private val reader: Reader get() = NovelSpeakApp.instance.reader
    private val loader by lazy { MangaPageLoader(this) }

    private val pages = ArrayList<MangaPageSource>()
    private val pageResults = ArrayList<MangaSession.PageResult?>()
    private val cues = ArrayList<MangaCue>()
    private val cueByReaderIndex = HashMap<Int, MangaCue>()

    private var session: MangaSession? = null
    private var title = "Manga page"
    private var referer: String? = null
    private var queueStarted = false
    private var ocrTotal = 0
    private var ocrProcessed = 0
    private var ocrFailed = 0
    private var ocrComplete = false
    private var playbackStarted = false
    private var autoResumeWhenBuffered = false

    private var currentPage = -1
    private var currentBlocks: List<OcrBlock> = emptyList()
    private var currentPageBlock = -1
    private var pageLoadToken = 0
    private var sessionGeneration = 0
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWindowTheme()
        title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Manga page"
        referer = intent.getStringExtra(EXTRA_REFERER)
        buildUi()
        reader.addListener(this)

        val urls = intent.getStringArrayListExtra(EXTRA_IMAGE_URLS)
        val uriStr = intent.getStringExtra(EXTRA_IMAGE_URI)
        when {
            !urls.isNullOrEmpty() -> {
                setPages(urls.map { MangaPageSource(it, referer) })
                startSession()
            }
            !uriStr.isNullOrBlank() -> {
                setPages(listOf(MangaPageSource(uriStr, referer)))
                loadPageBitmap(0)
                statusView.text = "Page loaded. Tap OCR + READ."
            }
            else -> {
                statusView.text = "Open a manga page image to read it aloud."
            }
        }
        syncControls()
    }

    // ------------------------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------------------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(8), 0, dp(8), dp(6))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val back = actionButton("‹", "Back") { finish() }
        top.addView(back)
        titleView = TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.TEXT)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        top.addView(titleView)
        pageBadge = TextView(this).apply {
            text = "0 / 0"
            textSize = 12f
            setTextColor(Palette.TEXT_SECONDARY)
            background = rounded(Palette.SURFACE_HIGH, 9)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            contentDescription = "Page number"
        }
        top.addView(pageBadge)
        top.addView(actionButton("⚙", "Manga settings") { showSettingsDialog() })
        root.addView(top)

        val imageFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.BLACK)
            clipToPadding = false
        }
        pageView = MangaPageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            onBlockTap = { blockIndex -> jumpToBox(blockIndex) }
        }
        imageFrame.addView(pageView)
        emptyHint = TextView(this).apply {
            text = "Open a page or load the sample"
            textSize = 14f
            setTextColor(Palette.MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        imageFrame.addView(emptyHint)
        root.addView(imageFrame)

        textPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xE6212B34.toInt(), Design.RADIUS_MD)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, dp(4)) }
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { showFullTextDialog() }
            contentDescription = "Current manga text. Tap to see all text"
        }
        currentText = TextView(this).apply {
            textSize = 15f
            setTextColor(Palette.TEXT)
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "OCR text appears here while the page is read aloud."
        }
        originalText = TextView(this).apply {
            textSize = 12f
            setTextColor(Palette.MUTED)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
            visibility = View.GONE
        }
        textPanel.addView(currentText)
        textPanel.addView(originalText)
        root.addView(textPanel)

        ocrProgressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        statusView = TextView(this).apply {
            text = "Idle"
            textSize = 12f
            setTextColor(Palette.MUTED)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        ocrProgress = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Palette.ACCENT)
            gravity = Gravity.END
            setPadding(dp(8), 0, 0, 0)
        }
        ocrProgressRow.addView(statusView)
        ocrProgressRow.addView(ocrProgress)
        root.addView(ocrProgressRow)

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        prevPage = actionButton("⏮", "Previous page") { jumpPage(-1) }
        prevSentence = actionButton("◀", "Previous text") { reader.prevSentence() }
        playButton = actionButton("▶", "Play", 56, primary = true) {
            if (reader.hasContent()) AppPermissions.requestNotificationsOnce(this)
            reader.toggle()
        }
        nextSentence = actionButton("▶", "Next text") { reader.nextSentence() }
        nextPage = actionButton("⏭", "Next page") { jumpPage(1) }
        transport.addView(prevPage)
        transport.addView(prevSentence)
        transport.addView(playButton)
        transport.addView(nextSentence)
        transport.addView(nextPage)
        root.addView(transport)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        actions.addView(
            textAction("Open", "Open manga page images") {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                startActivityForResult(pick, REQ_PICK)
            }
        )
        actions.addView(
            textAction("Sample", "Load the bundled manga sample") { loadSample() }
        )
        ocrButton = textAction("OCR + Read", "Run OCR on these pages and read") {
            startSession()
        }.apply {
            val height = if (resources.configuration.fontScale >= 1.5f) {
                dp(Design.TOUCH_DP)
            } else {
                dp(Design.DENSE_TOUCH_DP)
            }
            layoutParams = LinearLayout.LayoutParams(0, height, 1.4f)
                .apply { marginStart = dp(4); marginEnd = dp(4) }
            background = ripple(Palette.ACCENT, 12)
            setTextColor(Palette.BG)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        actions.addView(ocrButton)
        actions.addView(
            textAction("Fit", "Fit the whole page") { pageView.resetZoom() }
        )
        root.addView(actions)

        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }
        speedRow.addView(actionButton("−", "Slower", 40) { bumpSpeed(-0.1f) })
        speedView = TextView(this).apply {
            text = formatSpeed(NovelSpeakApp.instance.prefs.speed)
            textSize = 13f
            setTextColor(Palette.TEXT_SECONDARY)
            gravity = Gravity.CENTER
            minWidth = dp(62)
            contentDescription = "Playback speed"
            isClickable = true
            setOnClickListener { bumpSpeed(0.1f) }
        }
        speedRow.addView(speedView)
        speedRow.addView(actionButton("+", "Faster", 40) { bumpSpeed(0.1f) })
        root.addView(speedRow)

        setContentView(root)
        applyInsets(root)

        // The user asked for image + text together; make the relationship explicit to
        // screen readers too.
        pageView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        textPanel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun actionButton(
        label: String,
        description: String,
        sizeDp: Int = Design.DENSE_TOUCH_DP,
        primary: Boolean = false,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        textSize = if (primary) 22f else 18f
        gravity = Gravity.CENTER
        setTextColor(if (primary) Palette.BG else Palette.TEXT)
        background = if (primary) {
            ripple(Palette.ACCENT, sizeDp / 2, oval = true)
        } else {
            ripple(Palette.SURFACE_HIGH, sizeDp / 2, oval = true)
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        }
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun textAction(
        label: String,
        description: String,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        isAllCaps = true
        gravity = Gravity.CENTER
        setTextColor(Palette.TEXT)
        background = ripple(Palette.SURFACE_HIGH, 12)
        val height = if (resources.configuration.fontScale >= 1.5f) {
            dp(Design.TOUCH_DP)
        } else {
            dp(Design.DENSE_TOUCH_DP)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, height
        ).apply { marginEnd = dp(4) }
        setPadding(dp(10), 0, dp(10), 0)
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------------------------------
    // Sources and OCR pipeline
    // ------------------------------------------------------------------------------------

    private fun setPages(newPages: List<MangaPageSource>) {
        session?.cancel()
        session = null
        sessionGeneration++
        pages.clear()
        pages.addAll(newPages)
        pageResults.clear()
        repeat(pages.size) { pageResults.add(null) }
        cues.clear()
        cueByReaderIndex.clear()
        queueStarted = false
        ocrComplete = false
        ocrTotal = pages.size
        ocrProcessed = 0
        ocrFailed = 0
        autoResumeWhenBuffered = false
        currentPage = -1
        currentPageBlock = -1
        currentBlocks = emptyList()
        textPanel.visibility = View.GONE
        originalText.visibility = View.GONE
        pageBadge.text = if (pages.isEmpty()) "0 / 0" else "1 / ${pages.size}"
        emptyHint.text = if (pages.isEmpty()) {
            "Open a page or load the sample"
        } else {
            "Loading page…"
        }
        emptyHint.visibility = View.VISIBLE
        pageView.setPage(null, emptyList(), -1)
        ocrProgress.text = if (pages.isEmpty()) "" else "0 / ${pages.size}"
        ocrButton.isEnabled = pages.isNotEmpty()
        ocrButton.alpha = if (pages.isNotEmpty()) 1f else 0.4f
        syncControls()
    }

    private fun loadSample() {
        try {
            val f = File(cacheDir, "manga-sample.png")
            assets.open("manga-sample.png").use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
            val bmp = BitmapFactory.decodeFile(f.absolutePath)
            setPages(listOf(MangaPageSource(Uri.fromFile(f).toString())))
            if (bmp != null) {
                currentPage = 0
                pageView.setPage(bmp, emptyList(), -1, NovelSpeakApp.instance.prefs.highlight)
                pageBadge.text = "1 / 1"
                emptyHint.visibility = View.GONE
            }
            statusView.text = "Sample loaded. Tap OCR + READ."
            ocrButton.isEnabled = true
            ocrButton.alpha = 1f
            syncControls()
        } catch (t: Throwable) {
            statusView.text = "Sample load failed: ${t.message}"
            DiagLog.exception(this, "manga sample load failed", t)
        }
    }

    private fun startSession() {
        if (pages.isEmpty()) {
            statusView.text = "Open a manga page image first."
            return
        }
        session?.cancel()
        reader.stop()
        val gen = ++sessionGeneration
        queueStarted = false
        cues.clear()
        cueByReaderIndex.clear()
        repeat(pages.size) { if (it >= pageResults.size) pageResults.add(null) else pageResults[it] = null }
        ocrComplete = false
        ocrProcessed = 0
        ocrFailed = 0
        ocrTotal = pages.size
        autoResumeWhenBuffered = false
        playbackStarted = false
        currentPageBlock = -1
        textPanel.visibility = View.GONE
        currentText.text = "OCR text appears here while the page is read aloud."
        originalText.visibility = View.GONE
        ocrProgress.text = "0 / ${pages.size}"
        ocrButton.isEnabled = false
        ocrButton.alpha = 0.5f
        statusView.text = "Preparing OCR…"
        syncControls()

        val prefs = NovelSpeakApp.instance.prefs
        val configured = prefs.mangaOcrLanguage
        val ocrLang = if (configured == "auto") {
            MangaOcr.fromLanguageCode(prefs.mangaTargetLanguage)
        } else {
            configured
        }
        val translateTo = if (prefs.mangaTranslate) prefs.mangaTargetLanguage else null
        DiagLog.line(
            this,
            "manga reader start pages=${pages.size} ocr=$ocrLang " +
                "translate=${translateTo ?: "off"} title=\"${title.take(60)}\""
        )

        val newSession = MangaSession(
            context = this,
            pages = pages.toList(),
            loader = loader,
            ocrLanguage = ocrLang,
            translateTo = translateTo,
            listener = object : MangaSession.Listener {
                override fun onPageStarted(pageIndex: Int, total: Int) {
                    postUi {
                        if (gen != sessionGeneration) return@postUi
                        ocrTotal = total
                        ocrProgress.text = "${pageIndex + 1} / $total"
                    }
                }

                override fun onPageImage(pageIndex: Int, bitmap: Bitmap) {
                    postUi {
                        if (gen != sessionGeneration) return@postUi
                        if (currentPage < 0) {
                            currentPage = pageIndex
                            currentBlocks = pageResults.getOrNull(pageIndex)?.blocks ?: emptyList()
                            pageView.setPage(
                                bitmap, currentBlocks, -1,
                                NovelSpeakApp.instance.prefs.highlight
                            )
                            pageBadge.text = "${pageIndex + 1} / ${pages.size}"
                            emptyHint.visibility = View.GONE
                        }
                    }
                }

                override fun onPageReady(result: MangaSession.PageResult) {
                    postUi {
                        if (gen != sessionGeneration) return@postUi
                        onPageTextReady(result)
                    }
                }

                override fun onPageFailed(pageIndex: Int, error: Throwable) {
                    postUi {
                        if (gen != sessionGeneration) return@postUi
                        ocrProcessed++
                        ocrFailed++
                        ocrProgress.text = "$ocrProcessed / $ocrTotal"
                        statusView.text = "Page ${pageIndex + 1} failed: ${error.message ?: "OCR error"}"
                        DiagLog.exception(
                            this@MangaReaderActivity,
                            "manga page failed: ${pages.getOrNull(pageIndex)?.uri}",
                            error
                        )
                    }
                }

                override fun onFinished() {
                    postUi {
                        if (gen != sessionGeneration) return@postUi
                        onOcrFinished()
                    }
                }
            }
        )
        session = newSession
        syncControls()
        newSession.start()
    }

    private fun onPageTextReady(result: MangaSession.PageResult) {
        while (pageResults.size <= result.pageIndex) pageResults.add(null)
        pageResults[result.pageIndex] = result
        ocrProcessed++
        ocrProgress.text = "$ocrProcessed / $ocrTotal"

        if (result.pageIndex == currentPage) {
            currentBlocks = result.blocks
            pageView.setBlocks(result.blocks, currentPageBlock)
            if (NovelSpeakApp.instance.prefs.highlight) pageView.setBoxesVisible(true)
        }

        var added = 0
        for (i in result.blocks.indices) {
            val block = result.blocks[i]
            val text = result.texts.getOrNull(i)?.takeIf { it.isNotBlank() } ?: block.text
            if (text.isBlank()) continue
            val cue = MangaCue(
                readerIndex = cues.size,
                pageIndex = result.pageIndex,
                pageBlockIndex = i,
                text = text,
                originalText = block.text,
                boundingBox = block.boundingBox
            )
            cues.add(cue)
            cueByReaderIndex[cue.readerIndex] = cue
            added++
        }
        if (added > 0) appendToReader(result, added)
        if (cues.isEmpty()) {
            statusView.text = "No text on page ${result.pageIndex + 1}…"
        }
        syncControls()
    }

    private fun appendToReader(result: MangaSession.PageResult, added: Int) {
        val newCues = cues.subList(cues.size - added, cues.size)
        val blocks = newCues.map { cue ->
            Reader.Block(text = cue.text, blockIndex = cue.readerIndex, charStart = -1, charEnd = -1)
        }
        if (!queueStarted) {
            reader.setContent(title, blocks)
            queueStarted = true
            statusView.text = "Page ${result.pageIndex + 1} ready · ${blocks.size} lines. Tap Play."
            playButton.alpha = 1f
            playButton.isEnabled = true
        } else {
            reader.appendContent(blocks)
            if (autoResumeWhenBuffered && !reader.state().playing) {
                autoResumeWhenBuffered = false
                // finishQueue() leaves the cursor on the last spoken line; move it to the
                // first newly appended line so resuming does not replay that line.
                reader.seek(newCues.first().readerIndex)
                reader.play()
                statusView.text = "Resuming with page ${result.pageIndex + 1}…"
            } else if (!reader.state().playing) {
                statusView.text = "${cues.size} lines ready · page ${result.pageIndex + 1}"
            }
        }
        syncControls()
    }

    private fun onOcrFinished() {
        ocrComplete = true
        ocrButton.isEnabled = true
        ocrButton.alpha = 1f
        ocrProgress.text = if (ocrFailed > 0) "done · $ocrFailed failed" else "done"
        if (cues.isEmpty()) {
            statusView.text = "No text detected in these pages."
        } else if (!queueStarted) {
            statusView.text = "OCR finished but no text was queued."
        } else {
            val readerState = reader.state()
            statusView.text = when {
                readerState.playing -> "Playing"
                readerState.status == "Finished" -> "Finished"
                else -> "Ready · ${cues.size} lines. Tap Play."
            }
        }
        syncControls()
    }

    // ------------------------------------------------------------------------------------
    // Page + box synchronisation
    // ------------------------------------------------------------------------------------

    private fun showPage(pageIndex: Int, activeBlockIndex: Int) {
        if (pageIndex !in pages.indices) return
        val pageChanged = pageIndex != currentPage
        currentPage = pageIndex
        currentPageBlock = activeBlockIndex
        pageBadge.text = "${pageIndex + 1} / ${pages.size}"
        currentBlocks = pageResults.getOrNull(pageIndex)?.blocks ?: emptyList()

        val cached = loader.cached(pages[pageIndex].uri)
        if (cached != null) {
            emptyHint.visibility = View.GONE
            if (pageChanged) {
                pageView.setPage(
                    cached, currentBlocks, activeBlockIndex,
                    NovelSpeakApp.instance.prefs.highlight
                )
            } else {
                pageView.setBlocks(currentBlocks, activeBlockIndex)
                pageView.setActiveBlock(activeBlockIndex)
            }
            return
        }

        val token = ++pageLoadToken
        if (pageChanged) pageView.setPage(null, emptyList(), -1)
        emptyHint.text = "Loading page ${pageIndex + 1}…"
        emptyHint.visibility = View.VISIBLE
        statusView.text = "Loading page ${pageIndex + 1}…"
        Thread {
            val bmp = loader.load(pages[pageIndex])
            postUi {
                if (token != pageLoadToken || currentPage != pageIndex) return@postUi
                if (bmp == null) {
                    emptyHint.text = "Could not load page ${pageIndex + 1}"
                    statusView.text = "Could not load page ${pageIndex + 1}"
                    return@postUi
                }
                emptyHint.visibility = View.GONE
                currentBlocks = pageResults.getOrNull(pageIndex)?.blocks ?: currentBlocks
                pageView.setPage(
                    bmp, currentBlocks, currentPageBlock,
                    NovelSpeakApp.instance.prefs.highlight
                )
            }
        }.start()
    }

    private fun loadPageBitmap(pageIndex: Int) {
        if (pageIndex !in pages.indices) return
        showPage(pageIndex, -1)
    }

    private fun jumpToBox(pageBlockIndex: Int) {
        val cue = cues.firstOrNull {
            it.pageIndex == currentPage && it.pageBlockIndex == pageBlockIndex
        } ?: return
        reader.seek(cue.readerIndex)
        statusView.text = if (reader.state().playing) {
            "Jumped to this line"
        } else {
            "Selected page ${currentPage + 1} line"
        }
    }

    private fun jumpPage(delta: Int) {
        if (cues.isEmpty()) return
        val from = currentPage.coerceAtLeast(0)
        var target = -1
        if (delta > 0) {
            for (cue in cues) {
                if (cue.pageIndex > from && (target < 0 || cue.pageIndex < target)) {
                    target = cue.pageIndex
                }
            }
        } else {
            for (cue in cues) {
                if (cue.pageIndex < from && cue.pageIndex > target) target = cue.pageIndex
            }
        }
        if (target < 0) return
        val cue = cues.firstOrNull { it.pageIndex == target } ?: return
        reader.seek(cue.readerIndex)
    }

    private fun showFullTextDialog() {
        val cue = cues.lastOrNull { it.readerIndex == reader.currentIndex() } ?: return
        val message = buildString {
            append(cue.text)
            if (cue.originalText.isNotBlank() && cue.originalText != cue.text) {
                append("\n\nOriginal: ")
                append(cue.originalText)
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Page ${cue.pageIndex + 1}")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    // ------------------------------------------------------------------------------------
    // Reader callbacks
    // ------------------------------------------------------------------------------------

    override fun onReaderState(state: Reader.State) {
        if (state.playing) playbackStarted = true
        playButton.text = if (state.playing) "II" else "▶"
        playButton.contentDescription = if (state.playing) "Pause" else "Play"
        if (queueStarted && reader.hasContent()) {
            statusView.text = when {
                state.playing -> "Playing"
                state.status.startsWith("Chapter appended") ->
                    "Ready · ${cues.size} lines"
                state.status.startsWith("Ready ·") && !state.playing ->
                    "Ready · ${cues.size} lines. Tap Play."
                else -> state.status
            }
        }
        speedView.text = formatSpeed(NovelSpeakApp.instance.prefs.speed)
        syncControls()
    }

    override fun onHighlight(blockIndex: Int) {
        val cue = cueByReaderIndex[blockIndex] ?: return
        currentPageBlock = cue.pageBlockIndex
        currentText.text = cue.text
        if (cue.originalText.isNotBlank() && cue.originalText != cue.text) {
            originalText.text = cue.originalText
            originalText.visibility = View.VISIBLE
        } else {
            originalText.visibility = View.GONE
        }
        textPanel.visibility = View.VISIBLE
        textPanel.contentDescription =
            "Current manga text, page ${cue.pageIndex + 1} of ${pages.size}: ${cue.text}"

        if (cue.pageIndex != currentPage) {
            showPage(cue.pageIndex, cue.pageBlockIndex)
        } else {
            pageView.setActiveBlock(cue.pageBlockIndex)
        }
        pageBadge.text = "${cue.pageIndex + 1} / ${pages.size}"
        syncControls()
    }

    override fun onQueueFinished() {
        if (!ocrComplete && queueStarted && playbackStarted) {
            autoResumeWhenBuffered = true
            statusView.text = "Buffering the next page…"
        }
        playbackStarted = false
    }

    override fun onSegmentHighlight(
        blockIndex: Int,
        charStart: Int,
        charEnd: Int,
        speaker: String
    ) {
    }

    override fun onWordHighlight(blockIndex: Int, charStart: Int, charEnd: Int) {
    }

    private fun syncControls() {
        val hasQueue = queueStarted && reader.hasContent()
        playButton.isEnabled = hasQueue
        playButton.alpha = if (hasQueue) 1f else 0.4f
        val total = reader.state().total
        val canStep = hasQueue && total > 0
        prevSentence.isEnabled = canStep
        nextSentence.isEnabled = canStep
        prevSentence.alpha = if (canStep) 1f else 0.4f
        nextSentence.alpha = if (canStep) 1f else 0.4f

        val from = currentPage.coerceAtLeast(0)
        val hasPrevPage = cues.any { it.pageIndex < from }
        val hasNextPage = cues.any { it.pageIndex > from }
        prevPage.isEnabled = hasPrevPage
        nextPage.isEnabled = hasNextPage
        prevPage.alpha = if (hasPrevPage) 1f else 0.4f
        nextPage.alpha = if (hasNextPage) 1f else 0.4f
        ocrButton.isEnabled = pages.isNotEmpty() && (ocrComplete || session == null)
        ocrButton.alpha = if (ocrButton.isEnabled) 1f else 0.5f
    }

    // ------------------------------------------------------------------------------------
    // Settings and picker
    // ------------------------------------------------------------------------------------

    private fun showSettingsDialog() {
        val prefs = NovelSpeakApp.instance.prefs
        val languages = MangaOcr.supportedLanguages()
        val names = languages.map { MangaOcr.displayName(it) }.toTypedArray()
        val current = languages.indexOf(prefs.mangaOcrLanguage).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("OCR language")
            .setSingleChoiceItems(names, current) { dialog, which ->
                prefs.mangaOcrLanguage = languages[which]
                dialog.dismiss()
                Toast.makeText(
                    this,
                    "OCR language: ${names[which]}. Re-run OCR to apply.",
                    Toast.LENGTH_SHORT
                ).show()
                syncControls()
            }
            .setNeutralButton("More") { _, _ -> showMoreSettingsDialog() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showMoreSettingsDialog() {
        val prefs = NovelSpeakApp.instance.prefs
        val items = arrayOf(
            "Translate: ${if (prefs.mangaTranslate) "ON" else "OFF"}",
            "Highlight boxes: ${if (prefs.highlight) "ON" else "OFF"}",
            "Re-run OCR + Read"
        )
        AlertDialog.Builder(this)
            .setTitle("Manga settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        prefs.mangaTranslate = !prefs.mangaTranslate
                        Toast.makeText(
                            this,
                            "Translate ${if (prefs.mangaTranslate) "on" else "off"}. Re-run OCR to apply.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    1 -> {
                        prefs.highlight = !prefs.highlight
                        pageView.setBoxesVisible(prefs.highlight)
                        Toast.makeText(
                            this,
                            "Highlight boxes ${if (prefs.highlight) "on" else "off"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    2 -> startSession()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uris = ArrayList<Uri>()
        data?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        }
        if (uris.isEmpty()) data?.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (ignored: Throwable) {
            }
        }
        setPages(uris.map { MangaPageSource(it.toString()) })
        loadPageBitmap(0)
        statusView.text = "${uris.size} page${if (uris.size == 1) "" else "s"} loaded. Tap OCR + READ."
    }

    private fun bumpSpeed(delta: Float) {
        val p = NovelSpeakApp.instance.prefs
        val max = reader.maxSpeed().coerceAtLeast(0.5f)
        val next = (p.speed + delta).coerceIn(0.5f, max)
        reader.setSpeed(next)
        speedView.text = formatSpeed(next)
        speedView.contentDescription = "Playback speed ${formatSpeed(next)}. Tap to speed up"
    }

    private fun formatSpeed(v: Float): String = "%.2f×".format(v)

    // ------------------------------------------------------------------------------------
    // Lifecycle and small helpers
    // ------------------------------------------------------------------------------------

    override fun onDestroy() {
        destroyed = true
        session?.cancel()
        session = null
        reader.removeListener(this)
        loader.clearMemory()
        super.onDestroy()
    }

    private fun postUi(block: () -> Unit) {
        ui.post {
            if (!destroyed && !isFinishing) block()
        }
    }

    private fun applyWindowTheme() {
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG
        window.setBackgroundDrawable(ColorDrawable(Palette.BG))
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
    }

    private fun applyInsets(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(dp(8), top, dp(8), bottom + dp(6))
            insets
        }
        root.requestApplyInsets()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun ripple(color: Int, radiusDp: Int, oval: Boolean = false): RippleDrawable {
        val content = GradientDrawable().apply {
            setColor(color)
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(radiusDp).toFloat()
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(radiusDp).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(Palette.RIPPLE), content, mask)
    }

    companion object {
        /** Multiple page-image URLs extracted from the browser. */
        const val EXTRA_IMAGE_URLS = "manga_image_urls"

        /** A single page URI (file/content) for external launches. */
        const val EXTRA_IMAGE_URI = "manga_image_uri"

        /** Chapter/page title shown in the top bar. */
        const val EXTRA_TITLE = "manga_title"

        /** HTTP referer used for hotlink-protected images. */
        const val EXTRA_REFERER = "manga_referer"

        private const val REQ_PICK = 42
    }
}
