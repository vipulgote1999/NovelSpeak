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
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.novelspeak.manga.MangaCue
import app.novelspeak.manga.MangaFeedView
import app.novelspeak.manga.MangaOcr
import app.novelspeak.manga.MangaPageLoader
import app.novelspeak.manga.MangaPageSource
import app.novelspeak.manga.MangaSession
import app.novelspeak.manga.MangaTextLayout
import java.io.File

/**
 * Page-synchronised manga reader with a continuous vertical feed.
 *
 * Pages are extracted from the browser as a consecutive image chapter and stacked in one scroll
 * surface. OCR runs page by page on a worker thread; each page's bubbles are sorted into reading
 * order and split into validated speech chunks. As the shared [Reader] advances, the feed scrolls
 * the spoken bubble into view and highlights its box, so image, text and speech always agree.
 *
 * Playback can start after the first page while OCR keeps streaming later pages behind it.
 */
class MangaReaderActivity : Activity(), Reader.Listener {

    private lateinit var feedView: MangaFeedView
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

    private var activePage = -1
    private var activeCue: MangaCue? = null
    private var sessionGeneration = 0
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWindowTheme()
        title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Manga page"
        referer = intent.getStringExtra(EXTRA_REFERER)
        buildUi()
        reader.setWordHighlightOverride(true)
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
        feedView = MangaFeedView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            onBlockTap = { page, block -> jumpToBox(page, block) }
            onVisiblePageChanged = { page ->
                // While speech owns the scroll, the spoken page is authoritative. Only a
                // manual browse (playback stopped) moves the badge with the viewport.
                if (pages.isNotEmpty() && !reader.state().playing) {
                    pageBadge.text = "${page + 1} / ${pages.size}"
                }
            }
        }
        imageFrame.addView(feedView)
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
            setOnLongClickListener {
                showChunkList()
                true
            }
            contentDescription =
                "Current manga text. Tap to see all text; long press for the chunk list"
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
            textAction("Chunks", "Show every detected text chunk") { showChunkList() }
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
        feedView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
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
        cues.clear()
        cueByReaderIndex.clear()
        queueStarted = false
        ocrComplete = false
        ocrTotal = pages.size
        ocrProcessed = 0
        ocrFailed = 0
        autoResumeWhenBuffered = false
        activePage = -1
        activeCue = null
        textPanel.visibility = View.GONE
        originalText.visibility = View.GONE
        pageBadge.text = if (pages.isEmpty()) "0 / 0" else "1 / ${pages.size}"
        emptyHint.text = if (pages.isEmpty()) {
            "Open a page or load the sample"
        } else {
            "Loading pages…"
        }
        emptyHint.visibility = View.VISIBLE
        feedView.setPages(pages.toList(), loader)
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
                feedView.setPageImage(0, bmp)
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
        ocrComplete = false
        ocrProcessed = 0
        ocrFailed = 0
        ocrTotal = pages.size
        autoResumeWhenBuffered = false
        playbackStarted = false
        activeCue = null
        feedView.setPages(pages.toList(), loader)
        emptyHint.text = "Loading pages…"
        emptyHint.visibility = View.VISIBLE
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
        val rightToLeft = when (prefs.mangaReadingDirection) {
            "rtl" -> true
            "ltr" -> false
            else -> ocrLang == MangaOcr.LANG_JAPANESE || ocrLang == MangaOcr.LANG_CHINESE
        }
        DiagLog.line(
            this,
            "manga reader start pages=${pages.size} ocr=$ocrLang " +
                "translate=${translateTo ?: "off"} rtl=$rightToLeft " +
                "maxChunk=${prefs.maxSentenceChars} title=\"${title.take(60)}\""
        )

        val newSession = MangaSession(
            context = this,
            pages = pages.toList(),
            loader = loader,
            ocrLanguage = ocrLang,
            translateTo = translateTo,
            maxChunkChars = prefs.maxSentenceChars,
            rightToLeft = rightToLeft,
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
                        feedView.setPageImage(pageIndex, bitmap)
                        emptyHint.visibility = View.GONE
                        pageBadge.text = "${(activePage.takeIf { it >= 0 } ?: pageIndex) + 1} / ${pages.size}"
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
        ocrProcessed++
        ocrProgress.text = "$ocrProcessed / $ocrTotal"
        feedView.setPageResult(result.pageIndex, result)

        val translate = NovelSpeakApp.instance.prefs.mangaTranslate
        var added = 0
        for (chunk in result.chunks) {
            if (!MangaTextLayout.isSpeakable(chunk.text)) continue
            val block = result.blocks.getOrNull(chunk.blockIndex) ?: continue
            val cue = MangaCue(
                readerIndex = cues.size,
                pageIndex = result.pageIndex,
                pageBlockIndex = chunk.blockIndex,
                blockIndices = chunk.blockIndices,
                text = chunk.text,
                originalText = if (translate) chunk.originalText else "",
                boundingBox = block.boundingBox
            )
            cues.add(cue)
            cueByReaderIndex[cue.readerIndex] = cue
            added++
        }
        if (added > 0) appendToReader(result, added)
        if (result.chunks.isEmpty()) {
            statusView.text = "No speech on page ${result.pageIndex + 1} · OCR ${ocrProcessed}/${ocrTotal}"
        }
        syncControls()
    }

    private fun appendToReader(result: MangaSession.PageResult, added: Int) {
        val newCues = cues.subList(cues.size - added, cues.size)
        val newBlocks = newCues.map { cue ->
            Reader.Block(text = cue.text, blockIndex = cue.readerIndex, charStart = -1, charEnd = -1)
        }
        if (!queueStarted) {
            reader.setContent(title, newBlocks)
            queueStarted = true
            statusView.text =
                "Page ${result.pageIndex + 1} ready · ${newBlocks.size} chunks. Tap Play."
            playButton.alpha = 1f
            playButton.isEnabled = true
        } else {
            reader.appendContent(newBlocks)
            if (autoResumeWhenBuffered && !reader.state().playing) {
                autoResumeWhenBuffered = false
                // finishQueue() leaves the cursor on the last spoken line; move it to the
                // first newly appended line so resuming does not replay that line.
                reader.seek(newCues.first().readerIndex)
                reader.play()
                statusView.text = "Resuming with page ${result.pageIndex + 1}…"
            } else if (!reader.state().playing) {
                statusView.text =
                    "${cues.size} chunks ready · OCR ${ocrProcessed}/${ocrTotal}"
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
                readerState.playing -> "Playing · ${cues.size} chunks"
                readerState.status == "Finished" -> "Finished"
                else -> "Ready · ${cues.size} chunks. Tap Play."
            }
        }
        syncControls()
    }

    // ------------------------------------------------------------------------------------
    // Page + box synchronisation
    // ------------------------------------------------------------------------------------

    private fun jumpToBox(pageIndex: Int, pageBlockIndex: Int) {
        val cue = cues.firstOrNull {
            it.pageIndex == pageIndex && it.blockIndices.contains(pageBlockIndex)
        } ?: return
        activePage = pageIndex
        reader.seek(cue.readerIndex)
        feedView.resumeFollowing()
        feedView.setActive(pageIndex, cue.blockIndices)
        statusView.text = if (reader.state().playing) {
            "Jumped to this bubble"
        } else {
            "Selected page ${pageIndex + 1} bubble"
        }
    }

    private fun jumpPage(delta: Int) {
        if (pages.isEmpty()) return
        val from = activePage
            .takeIf { it >= 0 }
            ?: feedView.currentPage().coerceAtLeast(0)
        val range = if (delta > 0) (from + 1 until pages.size) else (from - 1 downTo 0)
        val pageWithCue = range.firstOrNull { page -> cues.any { it.pageIndex == page } }
        if (pageWithCue != null) {
            val cue = cues.first { it.pageIndex == pageWithCue }
            activePage = pageWithCue
            reader.seek(cue.readerIndex)
            feedView.resumeFollowing()
            feedView.setActive(pageWithCue, cue.blockIndices)
        } else {
            val target = (from + delta).coerceIn(0, pages.lastIndex)
            activePage = target
            feedView.scrollToPage(target)
            pageBadge.text = "${target + 1} / ${pages.size}"
        }
    }

    private fun showFullTextDialog() {
        val cue = activeCue ?: return
        val message = buildString {
            append(cue.text)
            if (cue.originalText.isNotBlank() && cue.originalText != cue.text) {
                append("\n\nOriginal: ")
                append(cue.originalText)
            }
            append("\n\nPage ${cue.pageIndex + 1} of ${pages.size}")
        }
        AlertDialog.Builder(this)
            .setTitle("Current chunk")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    /** Every detected chunk, so the reader can see the queue and jump to any bubble. */
    private fun showChunkList() {
        if (cues.isEmpty()) {
            Toast.makeText(this, "No OCR chunks yet", Toast.LENGTH_SHORT).show()
            return
        }
        val snapshot = cues.toList()
        val labels = snapshot.map { cue ->
            "P${cue.pageIndex + 1} · ${cue.text.take(110)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Text chunks · ${snapshot.size}")
            .setItems(labels) { dialog, which ->
                val cue = snapshot[which]
                activePage = cue.pageIndex
                reader.seek(cue.readerIndex)
                feedView.resumeFollowing()
                feedView.setActive(cue.pageIndex, cue.blockIndices)
                dialog.dismiss()
            }
            .setNegativeButton("Close", null)
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
                state.playing -> "Playing · ${cues.size} chunks · page ${(activePage + 1).coerceAtLeast(1)}/${pages.size}"
                state.status.startsWith("Chapter appended") ->
                    "Ready · ${cues.size} chunks"
                state.status.startsWith("Ready ·") && !state.playing ->
                    "Ready · ${cues.size} chunks. Tap Play."
                else -> state.status
            }
        }
        speedView.text = formatSpeed(NovelSpeakApp.instance.prefs.speed)
        syncControls()
    }

    override fun onHighlight(blockIndex: Int) {
        val cue = cueByReaderIndex[blockIndex] ?: return
        activeCue = cue
        activePage = cue.pageIndex
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

        feedView.setActive(cue.pageIndex, cue.blockIndices)
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

    /**
     * Approximate read-along inside the current bubble. The engine's boundary callbacks are
     * chunk-based, so the Reader estimates the word range from elapsed time and speed; painting
     * that range makes the "which words are being spoken" feedback immediate.
     */
    override fun onWordHighlight(blockIndex: Int, charStart: Int, charEnd: Int) {
        val cue = cueByReaderIndex[blockIndex] ?: return
        if (cue !== activeCue) return
        val text = cue.text
        if (text.isEmpty()) return
        val start = charStart.coerceIn(0, text.length)
        val end = charEnd.coerceIn(start, text.length)
        if (end <= start) return
        val span = SpannableString(text)
        span.setSpan(
            BackgroundColorSpan(0x6634D399),
            start,
            end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        currentText.setText(span, TextView.BufferType.SPANNABLE)
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

        val page = activePage.takeIf { it >= 0 } ?: feedView.currentPage()
        val hasPrevPage = pages.isNotEmpty() && page > 0
        val hasNextPage = pages.isNotEmpty() && page < pages.size - 1
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
        val direction = when (prefs.mangaReadingDirection) {
            "rtl" -> "Right to left"
            "ltr" -> "Left to right"
            else -> "Auto (script)"
        }
        val items = arrayOf(
            "Translate: ${if (prefs.mangaTranslate) "ON" else "OFF"}",
            "Reading order: $direction",
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
                        prefs.mangaReadingDirection = when (prefs.mangaReadingDirection) {
                            "auto" -> "rtl"
                            "rtl" -> "ltr"
                            else -> "auto"
                        }
                        Toast.makeText(
                            this,
                            "Reading order: ${prefs.mangaReadingDirection}. Re-run OCR to apply.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    2 -> {
                        prefs.highlight = !prefs.highlight
                        feedView.setBoxesVisible(prefs.highlight)
                        Toast.makeText(
                            this,
                            "Highlight boxes ${if (prefs.highlight) "on" else "off"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    3 -> startSession()
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
        reader.setWordHighlightOverride(null)
        feedView.release()
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
                @Suppress("DEPRECATION")
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
