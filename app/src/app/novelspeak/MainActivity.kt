package app.novelspeak

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import app.novelspeak.adblock.UrlTools
import android.os.SystemClock
import app.novelspeak.library.BookmarkStore
import app.novelspeak.library.LibraryStore
import app.novelspeak.library.PrefsLibraryStorage
import app.novelspeak.library.ReadingStats
import kotlin.math.min

/**
 * WebView browser + reader UI.
 *
 * The page itself is untouched; we inject the extractor after load, then drive TTS from the
 * extracted sentences. Reader mode is a presentation-only overlay that strips site chrome,
 * ads and challenge dialogs so the same page reads like a book.
 */
class MainActivity : Activity(), Reader.Listener, AdBlocker.Listener {

    private lateinit var web: WebView
    private lateinit var topBar: LinearLayout
    private lateinit var navCluster: LinearLayout
    private lateinit var addressBox: FrameLayout
    private lateinit var urlDisplayRow: LinearLayout
    private lateinit var urlDisplay: TextView
    private lateinit var urlBar: EditText
    private lateinit var addressClear: FrameLayout
    private lateinit var cancelEdit: TextView
    private lateinit var overflowButton: FrameLayout
    private lateinit var reloadButton: FrameLayout
    private lateinit var reloadGlyph: GlyphView
    private lateinit var siteGlyph: GlyphView
    private lateinit var progressBar: ProgressBar
    private lateinit var suggestionsPopup: PopupWindow
    private lateinit var suggestionsPanel: LinearLayout
    private lateinit var notificationHint: TextView
    private lateinit var titleView: TextView
    private lateinit var adblockView: TextView
    private lateinit var statusView: TextView
    private lateinit var progressView: TextView
    private lateinit var speedView: TextView
    private lateinit var engineChip: TextView
    private lateinit var dotView: View
    private lateinit var seekBar: SeekBar
    private lateinit var playButton: FrameLayout
    private lateinit var playGlyph: GlyphView
    private lateinit var readerBtn: TextView
    private lateinit var backButton: FrameLayout
    private lateinit var forwardButton: FrameLayout

    private var appendNext = false
    private var seeking = false
    private var sentenceTotal = 0
    private var lastExtractedUrl: String? = null
    private var lastExtractedBlocks = -1
    private var extractionInFlight = false
    private var autoAdvance = false
    private var autoAdvanceCount = 0
    private var autoAppendStart = -1
    private var autoPlayRunnable: Runnable? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var contentRow: LinearLayout
    private lateinit var playerCard: LinearLayout
    private lateinit var pageHolder: FrameLayout
    private lateinit var homeView: ScrollView
    private lateinit var homeContinue: LinearLayout
    private lateinit var titleRow: LinearLayout
    private var homeVisible = false

    private data class HomeShortcut(
        val label: String,
        val monogram: String,
        val url: String,
        val opensLibrary: Boolean = false
    )

    private val homeShortcuts = listOf(
        HomeShortcut("EmpireNovel", "EN", "https://www.empirenovel.com/"),
        HomeShortcut("Royal Road", "RR", "https://www.royalroad.com/"),
        HomeShortcut("LightNovel", "LN", "https://www.lightnovelworld.com/"),
        HomeShortcut("WebNovel", "WN", "https://www.webnovel.com/"),
        HomeShortcut("Wattpad", "WP", "https://www.wattpad.com/"),
        HomeShortcut("Gutenberg", "PG", "https://www.gutenberg.org/"),
        HomeShortcut("Wikipedia", "WI", "https://en.m.wikipedia.org/"),
        HomeShortcut("Library", "L", "", opensLibrary = true)
    )
    private lateinit var library: LibraryStore
    private lateinit var bookmarks: BookmarkStore
    private lateinit var readingStats: ReadingStats
    private var lastSentenceTickMs = 0L
    private var lastStatsCursor = -1
    private lateinit var wallBanner: TextView
    private lateinit var sleepTimerView: TextView
    private var pendingResumeIndex = -1
    private var sleepTimerEndsAt = 0L
    private var sleepTimerChapter = false
    private var lastPersistedCursor = -1
    private var lastPersistAt = 0L

    // Chapter prefetch: extract the next chapter in a hidden WebView while the current one
    // plays, then append it instantly so the sentence queue never waits on a page load.
    private var prefetchLink = ""
    private var prefetching = false
    private var prefetchedUrl: String? = null
    private var prefetchedTitle = ""
    private var prefetchedBlocks: List<Reader.Block>? = null
    private var hiddenWeb: WebView? = null
    private var suppressNextExtract = false

    /** Releases a stuck drag flag if a gesture ends without onStopTrackingTouch. */
    private val seekingTimeout = Runnable {
        seeking = false
        onReaderState(reader.state())
    }
    private var editingUrl = false
    private var pageLoading = false
    private var lastDisplayedUrl = ""

    private val reader: Reader get() = NovelSpeakApp.instance.reader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWindowTheme()
        buildUi()
        configureWebView()
        reader.addListener(this)
        AdBlocker.addListener(this)
        val storage = PrefsLibraryStorage(this)
        library = LibraryStore(storage)
        readingStats = ReadingStats(storage)
        bookmarks = BookmarkStore(storage)
        onReaderState(reader.state())
        handleShareIntent(intent)
    }

    private fun applyWindowTheme() {
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG
        window.setBackgroundDrawable(ColorDrawable(Palette.BG))
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }

        // ---- top browser bar ----
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(4))
            setBackgroundColor(Palette.BG)
        }
        navCluster = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backButton = iconButton(Glyph.BACK, "Back", 40) { navigateBack() }
        forwardButton = iconButton(Glyph.FORWARD, "Forward", 40) { navigateForward() }
        reloadButton = iconButton(Glyph.RELOAD, "Reload", 40) {
            if (pageLoading) stopLoading() else web.reload()
        }
        reloadGlyph = reloadButton.getChildAt(0) as GlyphView
        navCluster.addView(backButton)
        navCluster.addView(forwardButton)
        navCluster.addView(reloadButton)
        topBar.addView(navCluster)

        addressBox = FrameLayout(this).apply {
            background = rounded(Palette.SURFACE_HIGH, 21)
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(6)
            }
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            contentDescription = "Address bar"
            setOnClickListener { enterEditMode() }
        }
        urlDisplayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(10), 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            contentDescription = "Edit address"
            setOnClickListener { enterEditMode() }
            setOnLongClickListener { showSiteDialog(); true }
        }
        siteGlyph = GlyphView(this, Glyph.LOCK, Palette.ACCENT)
        urlDisplayRow.addView(
            siteGlyph,
            LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(8) }
        )
        urlDisplay = TextView(this).apply {
            text = "Search or enter address"
            textSize = 13.5f
            setTextColor(Palette.TEXT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        urlDisplayRow.addView(urlDisplay)
        addressBox.addView(urlDisplayRow)

        urlBar = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine(true)
            hint = "Search or type URL"
            textSize = 14.5f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.MUTED)
            background = null
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            minHeight = 0
            setSelectAllOnFocus(true)
            setPadding(dp(12), 0, dp(38), 0)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_DONE
                ) {
                    loadFromBar()
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {}

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {}

                override fun afterTextChanged(s: Editable?) {
                    if (!editingUrl) return
                    addressClear.visibility =
                        if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    showSuggestions(s?.toString() ?: "")
                }
            })
        }
        addressBox.addView(urlBar)

        addressClear = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(30),
                dp(30),
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { marginEnd = dp(5) }
            background = ripple(Palette.SURFACE, 15, oval = true)
            contentDescription = "Clear address"
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener {
                urlBar.setText("")
                urlBar.requestFocus()
                showSuggestions("")
            }
        }
        addressClear.addView(
            GlyphView(this, Glyph.CLOSE, Palette.MUTED),
            FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER)
        )
        addressBox.addView(addressClear)
        topBar.addView(addressBox)

        cancelEdit = TextView(this).apply {
            text = "Cancel"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.ACCENT)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            minHeight = dp(42)
            isClickable = true
            isFocusable = true
            contentDescription = "Cancel address editing"
            visibility = View.GONE
            setOnClickListener { exitEditMode() }
        }
        topBar.addView(cancelEdit)

        overflowButton = iconButton(Glyph.MORE, "More options", 40) {
            showOverflowMenu()
        }
        topBar.addView(overflowButton)
        root.addView(topBar)

        progressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(Palette.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(2)
            )
            visibility = View.GONE
        }
        root.addView(progressBar)

        suggestionsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.SURFACE, 16)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            elevation = dp(8).toFloat()
        }
        suggestionsPopup = PopupWindow(
            suggestionsPanel,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
            setOnDismissListener { suggestionsPanel.removeAllViews() }
        }

        // ---- chapter title + reader toggle ----
        titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(10), dp(6))
        }
        titleView = TextView(this).apply {
            text = "NovelSpeak"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.TEXT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(titleView)
        adblockView = TextView(this).apply {
            textSize = 10f
            setTextColor(Palette.MUTED)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            isFocusable = true
            contentDescription = "Ad blocking and site settings"
            setOnClickListener { showSiteDialog() }
        }
        titleRow.addView(adblockView)
        readerBtn = textButton("Reader", primary = false, widthDp = 0, heightDp = 32) {
            toggleReader()
        }.apply {
            textSize = 11f
            setPadding(dp(12), 0, dp(12), 0)
        }
        titleRow.addView(readerBtn)
        root.addView(titleRow)

        wallBanner = TextView(this).apply {
            textSize = 12f
            setTextColor(Palette.BG)
            background = rounded(Palette.AMBER, 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { showSiteDialog() }
            visibility = View.GONE
        }
        root.addView(
            wallBanner,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(10), dp(2), dp(10), dp(4)) }
        )

        // ---- page ----
        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Palette.BG)
        }
        web.setOnTouchListener { _, _ ->
            if (editingUrl) exitEditMode()
            false
        }

        // ---- now-playing card ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.SURFACE, 18)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = dp(8).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(8), dp(4), dp(8), dp(8))
            }
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dotView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) }
            background = oval(Palette.MUTED)
        }
        statusRow.addView(dotView)
        statusView = TextView(this).apply {
            text = "Open a chapter, then tap Read"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.TEXT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusRow.addView(statusView)
        engineChip = TextView(this).apply {
            text = "idle"
            textSize = 10f
            setTextColor(Palette.MUTED)
            background = rounded(Palette.SURFACE_HIGH, 8)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            visibility = View.GONE
        }
        statusRow.addView(engineChip)
        card.addView(statusRow)

        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        seekBar = SeekBar(this).apply {
            max = 0
            splitTrack = false
            progressTintList = ColorStateList.valueOf(Palette.ACCENT)
            thumbTintList = ColorStateList.valueOf(Palette.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Palette.BORDER)
            layoutParams = LinearLayout.LayoutParams(0, dp(28), 1f)
            setPadding(dp(6), 0, dp(6), 0)
            contentDescription = "Sentence progress"
        }
        progressView = TextView(this).apply {
            text = "0 / 0"
            textSize = 11f
            setTextColor(Palette.MUTED)
            gravity = Gravity.END
            minWidth = dp(62)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        progressRow.addView(seekBar)
        progressRow.addView(progressView)
        card.addView(progressRow)

        notificationHint = TextView(this).apply {
            textSize = 11f
            setTextColor(Palette.AMBER)
            setPadding(dp(4), dp(4), dp(4), 0)
            maxLines = 2
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener {
                if (!AppPermissions.notificationsGranted(this@MainActivity)) {
                    AppPermissions.openNotificationSettings(this@MainActivity)
                } else {
                    AppPermissions.openPlaybackChannelSettings(this@MainActivity)
                }
            }
        }
        card.addView(notificationHint)

        sleepTimerView = TextView(this).apply {
            textSize = 11f
            setTextColor(Palette.ACCENT)
            setPadding(dp(4), dp(2), dp(4), 0)
            visibility = View.GONE
            contentDescription = "Sleep timer"
        }
        card.addView(sleepTimerView)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        val readButton = textButton("Read", primary = false, widthDp = 0, heightDp = 38) {
            extractNow(append = false)
        }
        readButton.contentDescription = "Read chapter"
        actionsRow.addView(readButton)
        val nextButton = textButton("Next ch", primary = false, widthDp = 0, heightDp = 38) {
            loadNextChapter()
        }
        nextButton.contentDescription = "Next chapter"
        actionsRow.addView(nextButton)
        actionsRow.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        actionsRow.addView(iconButton(Glyph.MINUS, "Slower", 34) { bumpSpeed(-0.05f) })
        speedView = TextView(this).apply {
            text = "1.00×"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.TEXT)
            gravity = Gravity.CENTER
            minWidth = dp(54)
        }
        actionsRow.addView(speedView)
        actionsRow.addView(iconButton(Glyph.PLUS, "Faster", 34) { bumpSpeed(0.05f) })
        card.addView(actionsRow)

        val transportRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        transportRow.addView(iconButton(Glyph.PREV, "Previous sentence", 44) {
            reader.prevSentence()
        })
        playButton = iconButton(
            Glyph.PLAY, "Play", 56, Palette.ACCENT, Palette.BG, oval = true
        ) {
            if (reader.hasContent()) {
                AppPermissions.requestNotificationsOnce(this)
            }
            reader.toggle()
        }
        playGlyph = playButton.getChildAt(0) as GlyphView
        transportRow.addView(playButton)
        transportRow.addView(iconButton(Glyph.NEXT, "Next sentence", 44) {
            reader.nextSentence()
        })
        transportRow.addView(iconButton(Glyph.STOP, "Stop", 40) {
            cancelAutoAdvance()
            reader.stop()
        })
        card.addView(transportRow)
        playerCard = card

        // Phone: browser above, player card below. Tablet/landscape: card beside the page.
        // The page slot holds the WebView plus the home tab overlay.
        pageHolder = FrameLayout(this)
        pageHolder.addView(
            web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        homeView = buildHomeView()
        pageHolder.addView(
            homeView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        contentRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contentRow.addView(
            pageHolder,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        contentRow.addView(card, card.layoutParams)
        root.addView(
            contentRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        applyAdaptiveLayout()

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && sentenceTotal > 0) {
                    progressView.text = "${progress + 1} / $sentenceTotal"
                    seekBar.contentDescription = "Sentence ${progress + 1} of $sentenceTotal"
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                seeking = true
                // A gesture can be interrupted (system navigation, auto chapter advance,
                // notification shade) without onStopTrackingTouch. Do not let the thumb
                // freeze forever.
                uiHandler.removeCallbacks(seekingTimeout)
                uiHandler.postDelayed(seekingTimeout, 4000)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                uiHandler.removeCallbacks(seekingTimeout)
                seeking = false
                if (sentenceTotal > 0) reader.seek(sb?.progress ?: 0)
            }
        })

        setContentView(root)
        applyInsets(root)
        updateReaderButton()
        updateNotificationHint()
    }

    // ---- Home / new tab -----------------------------------------------------

    private fun buildHomeView(): ScrollView {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            visibility = View.GONE
            contentDescription = "NovelSpeak home"
            setBackgroundColor(Palette.BG)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        // Wordmark: quiet serif display with one warm rule. Deliberately not a browser clone.
        col.addView(TextView(this).apply {
            text = "NovelSpeak"
            textSize = 34f
            typeface = Typeface.create("serif", Typeface.BOLD)
            setTextColor(Palette.TEXT)
            letterSpacing = -0.01f
            contentDescription = "NovelSpeak"
        })
        col.addView(TextView(this).apply {
            text = "A quiet place to read."
            textSize = 13f
            setTextColor(Palette.MUTED)
            setPadding(0, dp(4), 0, 0)
        })
        col.addView(View(this).apply {
            background = rounded(Palette.AMBER, 1)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(2)).apply { topMargin = dp(14) }
        })

        // Search pill expands the real address bar, so there is one navigation path.
        val search = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(Palette.SURFACE_HIGH, 26)
            setPadding(dp(18), 0, dp(18), 0)
            isClickable = true
            isFocusable = true
            contentDescription = "Home search"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(26) }
            setOnClickListener { enterEditMode() }
        }
        search.addView(
            GlyphView(this, Glyph.GLOBE, Palette.MUTED),
            LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(12) }
        )
        search.addView(TextView(this).apply {
            text = "Search or enter a web address"
            textSize = 14.5f
            setTextColor(Palette.MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(search)

        col.addView(homeSectionTitle("Shortcuts"))
        for (row in 0 until 2) {
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 4) {
                val shortcut = homeShortcuts[row * 4 + i]
                rowView.addView(
                    homeShortcutCell(shortcut),
                    LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                )
            }
            col.addView(rowView)
        }

        val continueHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(22), 0, 0)
        }
        continueHeader.addView(TextView(this).apply {
            text = "Continue reading"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.TEXT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        continueHeader.addView(TextView(this).apply {
            text = "Library"
            textSize = 12.5f
            setTextColor(Palette.ACCENT)
            setPadding(dp(10), dp(6), 0, dp(6))
            isClickable = true
            isFocusable = true
            contentDescription = "Open library"
            setOnClickListener { startActivity(Intent(this@MainActivity, LibraryActivity::class.java)) }
        })
        col.addView(continueHeader)

        homeContinue = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(homeContinue)

        col.addView(TextView(this).apply {
            text = "Local only: no account, no ads, no tracking."
            textSize = 11f
            setTextColor(Palette.MUTED)
            setPadding(0, dp(22), 0, 0)
        })

        scroll.addView(col)
        return scroll
    }

    private fun homeSectionTitle(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.MUTED)
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun homeShortcutCell(shortcut: HomeShortcut): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = "Shortcut " + shortcut.label
            setOnClickListener {
                if (shortcut.opensLibrary) {
                    startActivity(Intent(this@MainActivity, LibraryActivity::class.java))
                } else {
                    hideHome()
                    web.loadUrl(shortcut.url)
                }
            }
            addView(FrameLayout(this@MainActivity).apply {
                background = ripple(Palette.SURFACE_HIGH, 28, oval = true)
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
                addView(TextView(this@MainActivity).apply {
                    text = shortcut.monogram
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(Palette.ACCENT)
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
            })
            addView(TextView(this@MainActivity).apply {
                text = shortcut.label
                textSize = 11f
                setTextColor(Palette.MUTED)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            })
        }

    private fun refreshHome() {
        if (!::homeContinue.isInitialized) return
        homeContinue.removeAllViews()
        val entries = try {
            if (::library.isInitialized) {
                library.listEntries().filter { !it.finished }.take(3)
            } else {
                emptyList()
            }
        } catch (t: Throwable) {
            emptyList()
        }
        if (entries.isEmpty()) {
            homeContinue.addView(TextView(this).apply {
                text = "Chapters you open will appear here."
                textSize = 13f
                setTextColor(Palette.MUTED)
                setPadding(0, dp(4), 0, dp(4))
            })
            return
        }
        for (entry in entries) {
            homeContinue.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ripple(Palette.SURFACE, 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                contentDescription = "Continue reading: " + entry.title
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    pendingResumeIndex = entry.sentenceIndex
                    hideHome()
                    web.loadUrl(entry.url)
                }
                addView(TextView(this@MainActivity).apply {
                    text = entry.title.ifBlank { entry.url }
                    textSize = 14.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(Palette.TEXT)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(this@MainActivity).apply {
                    val site = try { app.novelspeak.adblock.UrlTools.host(entry.url) ?: "" } catch (t: Throwable) { "" }
                    text = (if (site.isEmpty()) "" else site + " · ") + entry.percent + "%"
                    textSize = 12f
                    setTextColor(Palette.MUTED)
                    setPadding(0, dp(3), 0, 0)
                })
            })
        }
    }

    private fun showHome() {
        if (!::homeView.isInitialized) return
        if (editingUrl) exitEditMode()
        hideKeyboard()
        homeVisible = true
        refreshHome()
        homeView.visibility = View.VISIBLE
        homeView.alpha = 0f
        homeView.translationY = dp(12).toFloat()
        homeView.animate().alpha(1f).translationY(0f).setDuration(220L).start()
        web.visibility = View.GONE
        titleRow.visibility = View.GONE
        navCluster.visibility = View.GONE
        addressBox.visibility = View.GONE
        cancelEdit.visibility = View.GONE
        // The player bar only appears on Home while audio is actually playing; otherwise
        // the tab gets the full height (like a browser new-tab page).
        playerCard.visibility = if (reader.state().playing) View.VISIBLE else View.GONE
        titleView.text = "NovelSpeak"
        updateAddressDisplay(null)
        DiagLog.line(this, "home shown")
    }

    private fun hideHome() {
        if (!homeVisible) return
        homeVisible = false
        if (homeView.visibility == View.VISIBLE) homeView.visibility = View.GONE
        web.visibility = View.VISIBLE
        if (::titleRow.isInitialized) titleRow.visibility = View.VISIBLE
        if (::playerCard.isInitialized) playerCard.visibility = View.VISIBLE
        if (::navCluster.isInitialized) navCluster.visibility = View.VISIBLE
        if (::addressBox.isInitialized) addressBox.visibility = View.VISIBLE
    }

    /** Two-pane layout above 600dp (tablets, landscape phones); single column otherwise. */
    private fun applyAdaptiveLayout() {
        if (!::contentRow.isInitialized || !::playerCard.isInitialized) return
        val wide = resources.configuration.screenWidthDp >= 600
        if (wide) {
            contentRow.orientation = LinearLayout.HORIZONTAL
            contentRow.gravity = Gravity.TOP
            val pageParams = pageHolder.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(0, 0)
            pageParams.width = 0
            pageParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            pageParams.weight = 1f
            pageParams.setMargins(0, 0, 0, 0)
            pageHolder.layoutParams = pageParams
            val cardParams = playerCard.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT)
            cardParams.width = dp(360)
            cardParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            cardParams.weight = 0f
            cardParams.setMargins(dp(8), dp(8), dp(8), dp(8))
            playerCard.layoutParams = cardParams
        } else {
            contentRow.orientation = LinearLayout.VERTICAL
            contentRow.gravity = Gravity.NO_GRAVITY
            val pageParams = pageHolder.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            pageParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            pageParams.height = 0
            pageParams.weight = 1f
            pageParams.setMargins(0, 0, 0, 0)
            pageHolder.layoutParams = pageParams
            val cardParams = playerCard.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            cardParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            cardParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            cardParams.weight = 0f
            cardParams.setMargins(dp(8), dp(4), dp(8), dp(8))
            playerCard.layoutParams = cardParams
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyAdaptiveLayout()
    }

    /** Keep the browser bar below the status bar and the player card above the nav bar. */
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
            v.setPadding(0, top, 0, bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun configureWebView() {
        AdBlocker.attach(web)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadsImagesAutomatically = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        }
        try {
            web.addJavascriptInterface(AdBlockBridge(), "NSAdBlock")
        } catch (t: Throwable) {
        }
        try {
            if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
        } catch (t: Throwable) {
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: Bitmap?
            ) {
                super.onPageStarted(view, url, favicon)
                hideHome()
                pageLoading = true
                progressBar.visibility = View.VISIBLE
                progressBar.progress = 0
                setReloadGlyph(true)
                if (!editingUrl) updateAddressDisplay(url)
                updateNavButtons()
                if (url != null && url != prefetchLink) cancelPrefetch()
                AdBlocker.onPageStarted(url ?: "")
                updateShield()
                dismissSuggestions()
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                if (view != null) AdBlocker.inject(view, url ?: "")
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? = AdBlocker.intercept(request)

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                if (request == null) return false
                if (AdBlocker.shouldOverride(request)) return true
                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    pageLoading = false
                    progressBar.visibility = View.GONE
                    setReloadGlyph(false)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageLoading = false
                progressBar.visibility = View.GONE
                setReloadGlyph(false)
                if (url != null) {
                    NovelSpeakApp.instance.prefs.rememberUrl(url)
                    if (!editingUrl) updateAddressDisplay(url)
                }
                titleView.text = view?.title ?: ""
                getSharedPreferences("novelspeak", MODE_PRIVATE).edit()
                    .putString("last_url", url ?: "").apply()
                updateNavButtons()
                if (view != null) AdBlocker.inject(view, url ?: "")
                // Prefetched chapter: its text was appended before the visible page loaded.
                // Tag the live DOM only (for read-along) and never replace the queue.
                if (suppressNextExtract) {
                    suppressNextExtract = false
                    appendNext = false
                    lastExtractedUrl = url
                    lastExtractedBlocks = reader.state().total
                    if (view != null) {
                        WebExtractor.install(web) {
                            WebExtractor.extract(web) { _, _, _ -> }
                            val p = NovelSpeakApp.instance.prefs
                            val host = UrlTools.host(url ?: "") ?: ""
                            val site = app.novelspeak.adblock.PublicSuffixList
                                .registrableDomain(host).ifEmpty { host }
                            WebExtractor.setReader(web, p.readerModeForSite(site))
                            applyReaderPrefs()
                        }
                    }
                    return
                }
                // A duplicate onPageFinished for the same document (ad-driven reload,
                // popunder navigation back, WebView quirk) must not rewind the reader.
                // Retry only when the previous extraction was weak (challenge page)
                // and playback is not in progress.
                val sameUrl = url != null && url == lastExtractedUrl
                val retryWeakPage = !reader.state().playing && lastExtractedBlocks in 0..10
                if (appendNext || !sameUrl || retryWeakPage) {
                    lastExtractedUrl = url
                    lastExtractedBlocks = -1
                    extractNow(append = appendNext)
                } else {
                    // Same document reloaded (ad reload or manual reload): keep the native
                    // reader position and refresh the page-side scripts/theme. Do not run a
                    // discarded full extract + sentence split here (that was the second
                    // chunking pass on every duplicate onPageFinished).
                    try {
                        WebExtractor.install(web)
                        WebExtractor.setReader(web, NovelSpeakApp.instance.prefs.readerMode)
                    } catch (t: Throwable) {
                    }
                }
                appendNext = false
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress >= 100) {
                    pageLoading = false
                    progressBar.visibility = View.GONE
                    setReloadGlyph(false)
                } else {
                    pageLoading = true
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                    setReloadGlyph(true)
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean = false

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                if (AdBlocker.isAdLikeDialog(message)) {
                    result?.cancel()
                    return true
                }
                return super.onJsAlert(view, url, message, result)
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                if (AdBlocker.isAdLikeDialog(message)) {
                    result?.cancel()
                    return true
                }
                return super.onJsConfirm(view, url, message, result)
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                if (AdBlocker.isAdLikeDialog(message)) {
                    result?.cancel()
                    return true
                }
                return super.onJsPrompt(view, url, message, defaultValue, result)
            }
        }
    }

    private fun navigateBack() {
        if (web.canGoBack()) {
            web.goBack()
            updateNavButtons()
        }
    }

    private fun navigateForward() {
        if (web.canGoForward()) {
            web.goForward()
            updateNavButtons()
        }
    }

    private fun updateNavButtons() {
        try {
            val canBack = web.canGoBack()
            backButton.isEnabled = canBack
            backButton.alpha = if (canBack) 1f else 0.35f
            val canForward = web.canGoForward()
            forwardButton.isEnabled = canForward
            forwardButton.alpha = if (canForward) 1f else 0.35f
        } catch (e: Throwable) {
        }
    }

    private fun loadFromBar() {
        val target = UrlInput.normalize(urlBar.text.toString()) ?: return
        hideKeyboard()
        urlBar.clearFocus()
        setEditingUi(false)
        dismissSuggestions()
        try {
            web.loadUrl(target)
        } catch (t: Throwable) {
            DiagLog.exception(this, "loadUrl failed target=$target", t)
            Toast.makeText(this, "Could not open that address", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enterEditMode() {
        if (editingUrl) return
        // Home hides the omnibox; the home search pill re-opens it for typing.
        if (::addressBox.isInitialized) addressBox.visibility = View.VISIBLE
        val current = web.url ?: lastDisplayedUrl
        setEditingUi(true)
        urlBar.setText(current)
        urlBar.requestFocus()
        // Select all, but put the active end at offset 0 so the editor shows the start of
        // the URL (the domain) instead of scrolling to the path.
        try {
            urlBar.setSelection(urlBar.text.length, 0)
        } catch (t: Throwable) {
            urlBar.setSelection(0, urlBar.text.length)
        }
        addressClear.visibility =
            if (urlBar.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        showKeyboard()
        showSuggestions("")
    }

    private fun exitEditMode() {
        if (!editingUrl) return
        hideKeyboard()
        urlBar.clearFocus()
        setEditingUi(false)
        dismissSuggestions()
        updateAddressDisplay(web.url ?: lastDisplayedUrl)
        if (homeVisible) {
            // Returning to Home keeps its minimal chrome.
            if (::addressBox.isInitialized) addressBox.visibility = View.GONE
            if (::navCluster.isInitialized) navCluster.visibility = View.GONE
        }
    }

    private fun setEditingUi(editing: Boolean) {
        editingUrl = editing
        navCluster.visibility = if (editing) View.GONE else View.VISIBLE
        overflowButton.visibility = if (editing) View.GONE else View.VISIBLE
        cancelEdit.visibility = if (editing) View.VISIBLE else View.GONE
        urlDisplayRow.visibility = if (editing) View.GONE else View.VISIBLE
        urlBar.visibility = if (editing) View.VISIBLE else View.GONE
        addressBox.background = if (editing) {
            roundedStroke(Palette.SURFACE, 16, Palette.ACCENT, 1)
        } else {
            rounded(Palette.SURFACE_HIGH, 21)
        }
        if (!editing) {
            addressClear.visibility = View.GONE
        }
    }

    private fun showKeyboard() {
        try {
            urlBar.postDelayed({
                try {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(urlBar, InputMethodManager.SHOW_IMPLICIT)
                } catch (t: Throwable) {
                }
            }, 100)
        } catch (t: Throwable) {
        }
    }

    private fun hideKeyboard() {
        try {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(urlBar.windowToken, 0)
        } catch (e: Throwable) {
        }
    }

    private fun stopLoading() {
        try {
            web.stopLoading()
        } catch (t: Throwable) {
        }
        pageLoading = false
        progressBar.visibility = View.GONE
        setReloadGlyph(false)
    }

    private fun setReloadGlyph(loading: Boolean) {
        if (!::reloadGlyph.isInitialized) return
        reloadGlyph.setGlyph(
            if (loading) Glyph.CLOSE else Glyph.RELOAD,
            Palette.TEXT
        )
        reloadButton.contentDescription = if (loading) "Stop loading" else "Reload"
    }

    /**
     * Shows the domain in bold and the path/query in a quieter style, like a mobile browser.
     * The full URL is always available when the bar is tapped for editing.
     */
    private fun updateAddressDisplay(url: String?) {
        if (!::urlDisplay.isInitialized) return
        if (homeVisible) {
            lastDisplayedUrl = ""
            urlDisplay.text = "Search or enter a web address"
            urlDisplay.setTextColor(Palette.MUTED)
            siteGlyph.setGlyph(Glyph.GLOBE, Palette.MUTED)
            addressBox.contentDescription = "Address bar"
            return
        }
        val value = url ?: ""
        lastDisplayedUrl = value
        if (value.isEmpty() || value == "about:blank") {
            urlDisplay.text = "Search or enter address"
            urlDisplay.setTextColor(Palette.MUTED)
            siteGlyph.setGlyph(Glyph.GLOBE, Palette.MUTED)
            addressBox.contentDescription = "Address bar"
            return
        }
        val uri = try {
            Uri.parse(value)
        } catch (t: Throwable) {
            null
        }
        val scheme = uri?.scheme?.lowercase() ?: ""
        val host = uri?.host ?: ""
        if (host.isEmpty()) {
            urlDisplay.text = value
            urlDisplay.setTextColor(Palette.TEXT)
            siteGlyph.setGlyph(
                if (scheme == "https") Glyph.LOCK else Glyph.GLOBE,
                if (scheme == "https") Palette.ACCENT else Palette.MUTED
            )
            addressBox.contentDescription = "Address bar, $value"
            return
        }

        val path = buildString {
            append(uri?.encodedPath ?: "")
            uri?.encodedQuery?.let { append("?").append(it) }
        }.let { if (it == "/") "" else it }

        val display = SpannableStringBuilder()
        display.append(host)
        display.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            display.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        display.setSpan(
            ForegroundColorSpan(Palette.TEXT),
            0,
            display.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (path.isNotEmpty()) {
            val start = display.length
            display.append(path)
            display.setSpan(
                ForegroundColorSpan(Palette.MUTED),
                start,
                display.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            display.setSpan(
                RelativeSizeSpan(0.92f),
                start,
                display.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        urlDisplay.text = display
        urlDisplay.setTextColor(Palette.TEXT)
        val secure = scheme == "https"
        siteGlyph.setGlyph(
            if (secure) Glyph.LOCK else if (scheme == "http") Glyph.ALERT else Glyph.GLOBE,
            if (secure) Palette.ACCENT else if (scheme == "http") Palette.AMBER else Palette.MUTED
        )
        urlDisplay.contentDescription = value
        addressBox.contentDescription = "Address bar, $value"
    }

    private fun showOverflowMenu() {
        val popup = PopupMenu(this, overflowButton)
        popup.menu.add(0, MENU_HOME, 0, "Home")
        popup.menu.add(0, MENU_RELOAD, 1, "Reload")
        popup.menu.add(0, MENU_COPY, 2, "Copy link")
        popup.menu.add(0, MENU_SHARE, 3, "Share link")
        popup.menu.add(0, MENU_EXTERNAL, 4, "Open in browser")
        popup.menu.add(0, MENU_SITE, 5, "Site settings")
        popup.menu.add(0, MENU_IMPORT, 6, "Import EPUB or PDF")
        popup.menu.add(0, MENU_BOOKMARK, 7, "Bookmark sentence")
        popup.menu.add(0, MENU_SLEEP, 8, "Sleep timer")
        popup.menu.add(0, MENU_LIBRARY, 9, "Library")
        popup.menu.add(0, MENU_TRANSLATE, 10, "Translate page")
        popup.menu.add(0, MENU_CONTINUE, 11, "Continue reading")
        popup.menu.add(0, MENU_SETTINGS, 12, "NovelSpeak settings")
        popup.menu.add(0, MENU_PRIVACY, 13, "About & privacy")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_HOME -> showHome()
                MENU_RELOAD -> web.reload()
                MENU_COPY -> copyLink()
                MENU_SHARE -> shareLink()
                MENU_EXTERNAL -> openExternal()
                MENU_SITE -> showSiteDialog()
                MENU_LIBRARY -> startActivity(Intent(this, LibraryActivity::class.java))
                MENU_CONTINUE -> continueReading()
                MENU_SLEEP -> showSleepTimerDialog()
                MENU_BOOKMARK -> bookmarkCurrentSentence()
                MENU_SETTINGS -> startActivity(
                    Intent(this, SettingsActivity::class.java)
                )
                MENU_PRIVACY -> startActivity(
                    Intent(this, AboutPrivacyActivity::class.java)
                )
                MENU_TRANSLATE -> translateCurrentPage()
                MENU_IMPORT -> pickDocumentForImport()
            }
            true
        }
        popup.show()
    }

    private fun showSiteDialog() {
        val url = web.url ?: lastDisplayedUrl
        val host = UrlTools.host(url) ?: run {
            Toast.makeText(this, "No site loaded", Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = NovelSpeakApp.instance.prefs
        val secure = url.startsWith("https://")
        val site = app.novelspeak.adblock.PublicSuffixList
            .registrableDomain(host).ifEmpty { host }
        var adblockChanged = false
        val checked = booleanArrayOf(
            prefs.readerModeForSite(site),
            prefs.adblockEnabled && !AdBlocker.isSiteAllowed(host)
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(
                "$host\n${if (secure) "Secure HTTPS connection" else "Not a secure connection"}"
            )
            .setMultiChoiceItems(
                arrayOf("Reader mode", "Block ads on this site"),
                checked
            ) { _, which, isChecked ->
                if (which == 0) {
                    prefs.setReaderModeForSite(site, isChecked)
                    WebExtractor.setReader(web, prefs.readerModeForSite(site))
                    updateReaderButton()
                } else {
                    adblockChanged = true
                    prefs.setAdblockSiteAllowed(site, !isChecked)
                    updateShield()
                }
            }
            .setPositiveButton("Done") { d, _ -> d.dismiss() }
            .setNeutralButton("App settings") { d, _ ->
                d.dismiss()
                AppPermissions.openAppSettings(this)
            }
            .create()
        dialog.setOnDismissListener {
            if (adblockChanged) {
                try {
                    web.reload()
                } catch (t: Throwable) {
                }
            }
        }
        dialog.show()
    }

    private fun copyLink() {
        val url = web.url ?: lastDisplayedUrl
        if (url.isEmpty()) return
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("URL", url))
            Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            DiagLog.exception(this, "copy link failed", t)
        }
    }

    private fun shareLink() {
        val url = web.url ?: lastDisplayedUrl
        if (url.isEmpty()) return
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(Intent.createChooser(send, "Share link"))
        } catch (t: Throwable) {
            DiagLog.exception(this, "share link failed", t)
        }
    }

    private fun openExternal() {
        val url = web.url ?: lastDisplayedUrl
        if (url.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSuggestions(query: String) {
        if (!editingUrl || isFinishing) return
        val q = query.trim()
        val matches = NovelSpeakApp.instance.prefs.recentUrls()
            .filter { q.isEmpty() || it.contains(q, true) }
            .take(5)
        if (matches.isEmpty()) {
            dismissSuggestions()
            return
        }
        suggestionsPanel.removeAllViews()
        if (q.isEmpty()) {
            suggestionsPanel.addView(TextView(this).apply {
                text = "RECENT"
                textSize = 10f
                letterSpacing = 0.1f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Palette.MUTED)
                setPadding(dp(10), dp(6), dp(10), dp(4))
            })
        }
        for (url in matches) suggestionsPanel.addView(suggestionRow(url))
        suggestionsPopup.width = resources.displayMetrics.widthPixels - dp(16)
        if (!suggestionsPopup.isShowing) {
            try {
                suggestionsPopup.showAsDropDown(topBar, dp(8), dp(2))
            } catch (t: Throwable) {
                DiagLog.exception(this, "suggestions popup failed", t)
            }
        }
    }

    private fun suggestionRow(url: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = ripple(Palette.SURFACE_HIGH, 12)
            isClickable = true
            isFocusable = true
            addView(
                GlyphView(this@MainActivity, Glyph.GLOBE, Palette.MUTED),
                LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(10) }
            )
            addView(TextView(this@MainActivity).apply {
                text = url
                textSize = 12.5f
                setTextColor(Palette.TEXT)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })
            setOnClickListener {
                urlBar.setText(url)
                urlBar.setSelection(url.length)
                hideKeyboard()
                loadFromBar()
            }
        }

    private fun dismissSuggestions() {
        if (::suggestionsPopup.isInitialized && suggestionsPopup.isShowing) {
            try {
                suggestionsPopup.dismiss()
            } catch (t: Throwable) {
            }
        }
        if (::suggestionsPanel.isInitialized) suggestionsPanel.removeAllViews()
    }

    private fun extractNow(append: Boolean) {
        if (extractionInFlight) {
            DiagLog.line(this, "extract skipped (already running) url=${web.url}")
            return
        }
        extractionInFlight = true
        val auto = autoAdvance
        if (!auto) autoAdvanceCount = 0
        // install() is async; extract only once the page-side scripts have actually run.
        WebExtractor.install(web) {
            WebExtractor.extract(web) { title, blocks, wall ->
                DiagLog.line(
                    this,
                    "extract url=${web.url} append=$append auto=$auto title=\"${title.take(60)}\" blocks=${blocks.size}"
                )
                runOnUiThread {
                    extractionInFlight = false
                    lastExtractedUrl = web.url
                    lastExtractedBlocks = blocks.size
                    val badPage = title.contains("not found", true) ||
                        title.contains("404", true)
                    val blocked = badPage ||
                        title.contains("just a moment", true) ||
                        title.contains("security verification", true) ||
                        title.contains("cloudflare", true) ||
                        title.contains("attention required", true)
                    val p = NovelSpeakApp.instance.prefs
                    if (append && (blocked || blocks.size < 2)) {
                        // Never append a 404/Cloudflare interstitial to the reading queue.
                        appendNext = false
                        autoAdvance = false
                        Toast.makeText(this, "Next chapter not found", Toast.LENGTH_SHORT).show()
                        WebExtractor.setReader(web, false)
                    } else if (blocks.isEmpty()) {
                        statusView.text = "No readable text found on this page"
                        autoAdvance = false
                        // Keep the user's reader preference: an empty extraction is not a
                        // reason to throw away the readable theme.
                        WebExtractor.setReader(web, p.readerMode)
                    } else {
                        if (append && reader.hasContent()) {
                            autoAppendStart = reader.state().total
                            reader.appendContent(blocks, title.ifEmpty { web.title ?: "" })
                        } else {
                            autoAppendStart = 0
                            reader.setContent(title.ifEmpty { web.title ?: "" }, blocks)
                        }
                        statusView.text = "Extracted " + blocks.size + " sentences"
                        onExtracted(title, wall)

                        // Do not hide a Cloudflare challenge: it must stay interactive
                        // until the site finishes verification. Reader mode resumes on
                        // the real page automatically.
                        WebExtractor.setReader(web, if (blocked) false else p.readerMode)
                        if (p.autoMode && !blocked) {
                            if (auto) {
                                // New chapter: give the page a moment, then start at its
                                // first sentence instead of continuing the old cursor.
                                val start = autoAppendStart
                                autoAdvance = false
                                val run = Runnable {
                                    autoPlayRunnable = null
                                    if (!isFinishing && reader.hasContent()) {
                                        if (start > 0) reader.seek(start)
                                        AppPermissions.requestNotificationsOnce(this)
                                        reader.play()
                                    }
                                }
                                autoPlayRunnable = run
                                uiHandler.postDelayed(run, AUTO_PLAY_DELAY_MS)
                            } else {
                                AppPermissions.requestNotificationsOnce(this)
                                reader.play()
                            }
                        } else if (!p.autoMode) {
                            autoAdvance = false
                        }
                    }
                }
            }
        }
    }

    private fun cancelAutoAdvance() {
        autoAdvance = false
        autoAdvanceCount = 0
        autoAppendStart = -1
        autoPlayRunnable?.let { uiHandler.removeCallbacks(it) }
        autoPlayRunnable = null
    }

    /** Post-extraction wiring: paywall banner, continue-reading resume, library record. */
    private fun onExtracted(title: String, wall: String) {
        val url = web.url ?: lastDisplayedUrl
        if (wall.isNotEmpty()) {
            showWallBanner(wall)
        } else {
            hideWallBanner()
        }
        // Challenge/interstitial pages must never pollute Continue reading.
        val probe = (title + " " + (web.title ?: "")).lowercase()
        val challengePage = probe.contains("just a moment") ||
            probe.contains("attention required") ||
            probe.contains("security verification") ||
            probe.contains("checking your browser") ||
            probe.contains("enable javascript and cookies")
        if (wall.isEmpty() && url.isNotEmpty() && !challengePage) {
            NovelSpeakApp.instance.prefs.lastUrl = url
            val site = UrlTools.host(url) ?: ""
            val total = reader.state().total
            if (total > 0) {
                library.recordProgress(
                    url, title.ifEmpty { web.title ?: "" }, site, 0, total
                )
            }
        }
        applyReaderPrefs()
        if (wall.isEmpty() && pendingResumeIndex > 0 && reader.hasContent()) {
            val total = reader.state().total
            val idx = pendingResumeIndex.coerceIn(0, (total - 1).coerceAtLeast(0))
            pendingResumeIndex = -1
            reader.seek(idx)
            statusView.text = "Resumed at sentence " + (idx + 1) + " / " + total
        }
        val p = NovelSpeakApp.instance.prefs
        if (wall.isEmpty() && reader.hasContent() && (p.autoMode || reader.state().playing)) {
            // Delay until the TTS engine has finished loading: a hidden WebView extracting a
            // page at the same time as a 100 MB model load peaks memory on low-RAM devices.
            uiHandler.removeCallbacks(prefetchRunnable)
            uiHandler.postDelayed(prefetchRunnable, PREFETCH_DELAY_MS)
        }
    }

    private val prefetchRunnable = Runnable { maybePrefetchNextChapter() }

    // ---- chapter prefetch -----------------------------------------------------

    private fun maybePrefetchNextChapter() {
        if (prefetching || prefetchedBlocks != null) return
        val current = web.url ?: return
        // Do not start a second WebView when the device is already under memory pressure.
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            if (info.availMem < PREFETCH_MIN_FREE_BYTES) {
                DiagLog.line(this, "prefetch skipped: low memory " + (info.availMem / (1024 * 1024)) + " MB")
                return
            }
        } catch (t: Throwable) {
        }
        prefetching = true
        WebExtractor.nextLink(web) { link ->
            runOnUiThread {
                prefetching = false
                if (link.isNullOrEmpty() || link == current) return@runOnUiThread
                prefetchLink = link
                try {
                    val hw = WebView(this)
                    hw.settings.javaScriptEnabled = true
                    hw.settings.domStorageEnabled = true
                    hw.settings.javaScriptCanOpenWindowsAutomatically = false
                    hw.settings.setSupportMultipleWindows(false)
                    hiddenWeb = hw
                    hw.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView?, u: String?) {
                            if (hiddenWeb !== v || v == null) return
                            WebExtractor.install(v) {
                                WebExtractor.extract(v) { t, blocks, wall ->
                                    runOnUiThread {
                                        if (hiddenWeb === v && wall.isEmpty() && blocks.isNotEmpty()) {
                                            prefetchedUrl = link
                                            prefetchedTitle = t
                                            prefetchedBlocks = blocks
                                            DiagLog.line(
                                                this@MainActivity,
                                                "prefetch ready blocks=" + blocks.size + " url=" + link
                                            )
                                        }
                                        if (hiddenWeb === v) {
                                            hiddenWeb = null
                                            try { v.stopLoading(); v.destroy() } catch (e: Throwable) { }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    hw.loadUrl(link)
                } catch (t: Throwable) {
                    DiagLog.exception(this, "prefetch failed", t)
                    cancelPrefetch()
                }
            }
        }
    }

    private fun cancelPrefetch() {
        prefetching = false
        prefetchLink = ""
        prefetchedUrl = null
        prefetchedTitle = ""
        prefetchedBlocks = null
        val hw = hiddenWeb
        hiddenWeb = null
        if (hw != null) {
            try { hw.stopLoading(); hw.destroy() } catch (t: Throwable) { }
        }
    }

    private fun showWallBanner(wall: String) {
        if (!::wallBanner.isInitialized) return
        wallBanner.text = when (wall) {
            "login" -> "Login may be required — open this page in NovelSpeak's browser and sign in, then tap Read."
            "subscribe" -> "This chapter may be subscriber-only — the readable text looks partial. No bypass attempted."
            else -> "This page looks like a verification wall — complete it in the browser, then tap Read."
        }
        wallBanner.visibility = View.VISIBLE
    }

    private fun hideWallBanner() {
        if (::wallBanner.isInitialized) wallBanner.visibility = View.GONE
    }

    /** Inject the user's reader typography (font, size, line height, width, theme). */
    private fun applyReaderPrefs() {
        try {
            val p = NovelSpeakApp.instance.prefs
            val style = ReaderStyle.of(
                p.readerFontScale, p.readerFontFamily, p.readerTheme,
                p.readerLineHeight, p.readerWidthPx
            )
            WebExtractor.applyReaderStyle(web, ReaderStyle.buildCss(style))
        } catch (t: Throwable) {
            DiagLog.exception(this, "apply reader style failed", t)
        }
    }

    /** Accumulate reading time locally (no telemetry) for the stats screen. */
    private fun recordReadingTime(state: Reader.State) {
        if (!state.playing || state.total <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (state.cursor != lastStatsCursor) {
            if (lastSentenceTickMs > 0L) {
                val secs = ((now - lastSentenceTickMs) / 1000L).toInt().coerceIn(0, 120)
                if (secs > 0) readingStats.addReading(secs, 1, UrlTools.host(web.url ?: "") ?: "")
            }
            lastStatsCursor = state.cursor
            lastSentenceTickMs = now
        }
    }

    private fun persistProgressThrottled(state: Reader.State) {
        if (!::library.isInitialized || state.total <= 0) return
        val now = System.currentTimeMillis()
        if (state.cursor == lastPersistedCursor || now - lastPersistAt < 2000L) return
        val url = web.url ?: lastDisplayedUrl
        if (url.isEmpty()) return
        val title = state.title.ifEmpty { web.title ?: "" }
        library.recordProgress(
            url, title, UrlTools.host(url) ?: "", state.cursor, state.total
        )
        lastPersistedCursor = state.cursor
        lastPersistAt = now
    }

    // ---- continue reading / library / sharing ---------------------------------

    // ---- EPUB / PDF / text import -------------------------------------------

    private fun pickDocumentForImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/epub+zip", "application/pdf", "text/plain")
            )
        }
        try {
            startActivityForResult(intent, REQ_IMPORT_DOCUMENT)
        } catch (t: Throwable) {
            Toast.makeText(this, "No document picker available", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT_DOCUMENT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (t: Throwable) {
            // Not all providers grant persistable access; the one-shot read still works.
        }
        importDocument(uri, null)
    }

    /** Imports a picked/shared EPUB, PDF or plain-text document into the reader queue. */
    private fun importDocument(uri: Uri, mimeHint: String?) {
        Thread({
            try {
                val name = documentName(uri)
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@Thread runOnUiThread {
                        Toast.makeText(this, "Could not read that file", Toast.LENGTH_SHORT).show()
                    }
                if (bytes.size > 64 * 1024 * 1024) {
                    return@Thread runOnUiThread {
                        Toast.makeText(this, "File is too large (limit 64 MB)", Toast.LENGTH_SHORT).show()
                    }
                }
                val lower = (name ?: "").lowercase()
                val mime = mimeHint ?: contentResolver.getType(uri) ?: ""
                when {
                    lower.endsWith(".epub") || mime.contains("epub") -> {
                        val result = EpubImporter.import(bytes.inputStream())
                        val book = result.book ?: return@Thread runOnUiThread {
                            Toast.makeText(this, result.error.ifBlank { "EPUB import failed" }, Toast.LENGTH_LONG).show()
                        }
                        val blocks = ArrayList<Reader.Block>()
                        var blockIndex = 0
                        var sentences = 0
                        for (chapter in book.chapters) {
                            for (s in SentenceSplitter.split(chapter.text, NovelSpeakApp.instance.prefs.maxSentenceChars)) {
                                if (sentences >= 5000) break
                                blocks.add(Reader.Block(s, blockIndex))
                                sentences++
                            }
                            blockIndex++
                            if (sentences >= 5000) break
                        }
                        runOnUiThread {
                            applyImported(
                                book.title + " · " + book.chapters.size + " chapters",
                                blocks
                            )
                        }
                    }
                    lower.endsWith(".pdf") || mime.contains("pdf") -> {
                        val result = PdfTextImporter.import(bytes)
                        if (result.pages.isEmpty()) {
                            return@Thread runOnUiThread {
                                Toast.makeText(
                                    this,
                                    result.error.ifBlank { "PDF has no text layer (OCR not supported)" },
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        val blocks = ArrayList<Reader.Block>()
                        var sentenceIndex = 0
                        for (page in result.pages) {
                            for (s in SentenceSplitter.split(page.text, NovelSpeakApp.instance.prefs.maxSentenceChars)) {
                                if (sentenceIndex >= 5000) break
                                blocks.add(Reader.Block(s, sentenceIndex))
                                sentenceIndex++
                            }
                        }
                        runOnUiThread {
                            applyImported(
                                (if (name.isNullOrBlank()) "Imported PDF" else name) +
                                    " · " + result.pages.size + " pages",
                                blocks
                            )
                        }
                    }
                    else -> {
                        val text = String(bytes, Charsets.UTF_8)
                        val blocks = ArrayList<Reader.Block>()
                        var i = 0
                        for (s in SentenceSplitter.split(text, NovelSpeakApp.instance.prefs.maxSentenceChars)) {
                            if (i >= 5000) break
                            blocks.add(Reader.Block(s, i))
                            i++
                        }
                        runOnUiThread {
                            applyImported(if (name.isNullOrBlank()) "Imported text" else name, blocks)
                        }
                    }
                }
            } catch (t: Throwable) {
                DiagLog.exception(this, "import failed", t)
                runOnUiThread {
                    Toast.makeText(this, "Import failed: " + t.javaClass.simpleName, Toast.LENGTH_LONG).show()
                }
            }
        }, "ns-import").start()
    }

    private fun applyImported(title: String, blocks: List<Reader.Block>) {
        if (blocks.isEmpty()) {
            Toast.makeText(this, "Nothing readable in that file", Toast.LENGTH_SHORT).show()
            return
        }
        reader.setContent(title, blocks)
        hideWallBanner()
        WebExtractor.setReader(web, false)
        updateReaderButton()
        statusView.text = "Imported " + blocks.size + " sentences — tap Play"
        statusView.contentDescription = "Reader status, imported " + blocks.size + " sentences"
        Toast.makeText(this, "Imported " + blocks.size + " sentences", Toast.LENGTH_SHORT).show()
    }

    private fun documentName(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (t: Throwable) {
            uri.lastPathSegment
        }
    }

    /**
     * Translation hand-off: opens the current page in the system's translation flow
     * (external browser/app) prefilled with the user's language. No on-device translation
     * is attempted, so no model or account is added to the app.
     */
    private fun translateCurrentPage() {
        val url = web.url ?: lastDisplayedUrl
        if (url.isEmpty()) {
            Toast.makeText(this, "Open a page first", Toast.LENGTH_SHORT).show()
            return
        }
        val target = java.util.Locale.getDefault().language.ifEmpty { "en" }
        val source = try {
            java.net.URLEncoder.encode(url, "UTF-8")
        } catch (t: Throwable) {
            url
        }
        val translate = "https://translate.google.com/translate?sl=auto&tl=" + target + "&u=" + source
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(translate)))
        } catch (t: Throwable) {
            Toast.makeText(this, "No browser available to translate", Toast.LENGTH_SHORT).show()
        }
    }

    /** Saves the sentence currently being spoken (or where playback is paused). */
    private fun bookmarkCurrentSentence() {
        if (!reader.hasContent()) {
            Toast.makeText(this, "Start reading a chapter first", Toast.LENGTH_SHORT).show()
            return
        }
        val url = web.url ?: lastDisplayedUrl
        if (url.isEmpty()) {
            Toast.makeText(this, "No page to bookmark", Toast.LENGTH_SHORT).show()
            return
        }
        val index = reader.currentIndex()
        val snippet = reader.sentenceText(index).ifBlank { "Sentence " + (index + 1) }
        val title = reader.state().title.ifEmpty { web.title ?: "" }
        val mark = bookmarks.add(url, title, index, snippet.take(240))
        if (mark == null) {
            Toast.makeText(this, "Could not save bookmark", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bookmarked sentence " + (index + 1), Toast.LENGTH_SHORT).show()
        }
    }

    private fun continueReading() {
        val entry = if (::library.isInitialized) library.resumeEntry() else null
        if (entry == null) {
            Toast.makeText(this, "Nothing to continue yet", Toast.LENGTH_SHORT).show()
            return
        }
        pendingResumeIndex = entry.sentenceIndex
        web.loadUrl(entry.url)
    }

    /**
     * Cold start opens the home tab (like a browser's new-tab page). The last chapter is one
     * tap away in Continue reading, so a process restart never silently re-reads an old page.
     */
    private fun openLastPosition() {
        showHome()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) { openLastPosition(); return }
        val action = intent.action
        @Suppress("DEPRECATION")
        val stream = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        val data = intent.data
        val mime = intent.type ?: ""
        val isDocument = data != null && (
            data.scheme == "content" || data.scheme == "file" ||
                mime.contains("epub") || mime.contains("pdf")
            )
        if (action == Intent.ACTION_SEND && stream != null && isDocument) {
            importDocument(stream, mime)
            return
        }
        if (action == Intent.ACTION_VIEW && data != null) {
            if (isDocument && (mime.contains("epub") || mime.contains("pdf") ||
                    (data.path ?: "").lowercase().let { it.endsWith(".epub") || it.endsWith(".pdf") })
            ) {
                importDocument(data, mime)
                return
            }
            // Library row tap: launchMode=singleTask delivers ACTION_VIEW here.
            pendingResumeIndex = intent.getIntExtra(
                LibraryActivity.EXTRA_RESUME_SENTENCE, -1
            )
            web.loadUrl(data.toString())
            return
        }
        if (action == Intent.ACTION_SEND || action == Intent.ACTION_PROCESS_TEXT) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            if (!text.isNullOrBlank()) {
                val trimmed = text.trim()
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    web.loadUrl(trimmed)
                } else {
                    renderSharedText(trimmed)
                }
                return
            }
        }
        openLastPosition()
    }

    /** Read text shared from another app / selected via ACTION_PROCESS_TEXT. */
    private fun renderSharedText(text: String) {
        val max = NovelSpeakApp.instance.prefs.maxSentenceChars
        val parts = SentenceSplitter.split(text, max)
        if (parts.isEmpty()) {
            statusView.text = "Shared text had nothing to read"
            return
        }
        val blocks = ArrayList<Reader.Block>(parts.size)
        for (i in parts.indices) blocks.add(Reader.Block(parts[i], i))
        reader.setContent("Shared text", blocks)
        hideWallBanner()
        statusView.text = "Shared text · " + blocks.size + " sentences"
        statusView.contentDescription = "Reader status, shared text queued"
        WebExtractor.setReader(web, false)
        AppPermissions.requestNotificationsOnce(this)
        reader.play()
    }

    // ---- sleep timer ----------------------------------------------------------

    private fun showSleepTimerDialog() {
        val options = arrayOf(
            "Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "End of chapter"
        )
        val current = NovelSpeakApp.instance.prefs.sleepTimerMinutes
        val selected = when (current) {
            15 -> 1; 30 -> 2; 45 -> 3; 60 -> 4; -1 -> 5; else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("Sleep timer")
            .setSingleChoiceItems(options, selected) { dialog, which ->
                dialog.dismiss()
                applySleepTimer(
                    when (which) {
                        1 -> 15; 2 -> 30; 3 -> 45; 4 -> 60; 5 -> -1; else -> 0
                    }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applySleepTimer(minutes: Int) {
        NovelSpeakApp.instance.prefs.sleepTimerMinutes = minutes
        uiHandler.removeCallbacks(sleepTick)
        sleepTimerChapter = minutes == -1
        sleepTimerEndsAt = if (minutes > 0) {
            System.currentTimeMillis() + minutes * 60_000L
        } else {
            0L
        }
        if (minutes > 0) {
            uiHandler.post(sleepTick)
            Toast.makeText(this, "Sleep timer: " + minutes + " minutes", Toast.LENGTH_SHORT).show()
        } else if (minutes == -1) {
            Toast.makeText(this, "Will pause at the end of this chapter", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Sleep timer off", Toast.LENGTH_SHORT).show()
        }
        updateSleepTimerView()
    }

    private val sleepTick = object : Runnable {
        override fun run() {
            if (sleepTimerEndsAt <= 0L) return
            if (System.currentTimeMillis() >= sleepTimerEndsAt) {
                sleepTimerEndsAt = 0L
                reader.pause()
                updateSleepTimerView()
                Toast.makeText(this@MainActivity, "Sleep timer: paused", Toast.LENGTH_SHORT).show()
            } else {
                updateSleepTimerView()
                uiHandler.postDelayed(this, 1000L)
            }
        }
    }

    private fun updateSleepTimerView() {
        if (!::sleepTimerView.isInitialized) return
        val remaining = sleepTimerEndsAt - System.currentTimeMillis()
        when {
            remaining > 0L -> {
                val secs = (remaining / 1000L).toInt()
                sleepTimerView.text = String.format("Sleep timer · %d:%02d", secs / 60, secs % 60)
                sleepTimerView.contentDescription =
                    "Sleep timer active, " + (secs / 60) + " minutes " + (secs % 60) + " seconds remaining"
                sleepTimerView.visibility = View.VISIBLE
            }
            sleepTimerChapter -> {
                sleepTimerView.text = "Sleep timer · end of chapter"
                sleepTimerView.contentDescription = "Sleep timer active, end of chapter"
                sleepTimerView.visibility = View.VISIBLE
            }
            else -> sleepTimerView.visibility = View.GONE
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onQueueFinished() {
        if (sleepTimerChapter) {
            sleepTimerChapter = false
            sleepTimerEndsAt = 0L
            updateSleepTimerView()
            cancelAutoAdvance()
            Toast.makeText(this, "Sleep timer: chapter finished, stopped", Toast.LENGTH_SHORT).show()
            return
        }
        val p = NovelSpeakApp.instance.prefs
        if (!p.autoMode) {
            val url = web.url ?: lastDisplayedUrl
            if (url.isNotEmpty()) {
                try { library.markFinished(url) } catch (t: Throwable) { }
            }
        }
        DiagLog.line(this, "queue finished auto=" + p.autoMode + " count=" + autoAdvanceCount)
        if (!p.autoMode) return
        if (autoAdvanceCount >= MAX_AUTO_CHAPTERS) {
            Toast.makeText(this, "Auto mode stopped after $MAX_AUTO_CHAPTERS chapters",
                Toast.LENGTH_LONG).show()
            return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            Toast.makeText(this, "Chapter finished — next page in 3 s", Toast.LENGTH_SHORT).show()
        }
        autoAdvanceCount++
        autoAdvance = true
        loadNextChapter()
    }

    private fun loadNextChapter() {
        pendingResumeIndex = -1
        val cached = prefetchedBlocks
        val cachedUrl = prefetchedUrl
        if (cached != null && !cachedUrl.isNullOrEmpty()) {
            val title = prefetchedTitle
            prefetchedBlocks = null
            prefetchedUrl = null
            prefetchedTitle = ""
            prefetchLink = ""
            appendNext = false
            suppressNextExtract = true
            lastExtractedUrl = cachedUrl
            lastExtractedBlocks = cached.size
            autoAppendStart = reader.state().total
            reader.appendContent(cached, title.ifEmpty { "Next chapter" })
            statusView.text = "Prefetched chapter · " + cached.size + " sentences"
            val p = NovelSpeakApp.instance.prefs
            if (p.autoMode) {
                val start = autoAppendStart
                autoAdvance = false
                uiHandler.postDelayed(Runnable {
                    if (!isFinishing && reader.hasContent()) {
                        if (start > 0) reader.seek(start)
                        reader.play()
                    }
                }, 350L)
            }
            web.loadUrl(cachedUrl)
            return
        }
        WebExtractor.nextLink(web) { link ->
            DiagLog.line(this, "next chapter link=${link.ifEmpty { "(none)" }}")
            runOnUiThread {
                if (link.isNullOrEmpty()) {
                    autoAdvance = false
                    Toast.makeText(this, "No next-chapter link found", Toast.LENGTH_SHORT).show()
                } else {
                    appendNext = true
                    web.loadUrl(link)
                }
            }
        }
    }

    private fun bumpSpeed(delta: Float) {
        val max = reader.maxSpeed().coerceAtLeast(2.0f)
        val v = (NovelSpeakApp.instance.prefs.speed + delta).coerceIn(0.5f, max)
        reader.setSpeed(v)
        speedView.text = "%.2f×".format(v)
        speedView.contentDescription = "Playback speed ${"%.2f".format(v)} times"
    }

    private fun toggleReader() {
        val p = NovelSpeakApp.instance.prefs
        p.readerMode = !p.readerMode
        WebExtractor.setReader(web, p.readerMode)
        updateReaderButton()
    }

    private fun updateReaderButton() {
        val prefs = NovelSpeakApp.instance.prefs
        val host = UrlTools.host(web.url ?: lastDisplayedUrl) ?: ""
        val site = app.novelspeak.adblock.PublicSuffixList.registrableDomain(host).ifEmpty { host }
        val on = prefs.readerModeForSite(site)
        readerBtn.text = if (on) "Reader on" else "Reader"
        readerBtn.setTextColor(if (on) Palette.ACCENT else Palette.TEXT)
        readerBtn.background = ripple(Palette.SURFACE_HIGH, 16)
        readerBtn.contentDescription =
            if (on) "Turn reader mode off" else "Turn reader mode on"
    }

    override fun onReaderState(state: Reader.State) {
        val total = state.total.coerceAtLeast(0)
        sentenceTotal = total
        persistProgressThrottled(state)
        updateSleepTimerView()
        recordReadingTime(state)
        if (homeVisible && ::playerCard.isInitialized) {
            playerCard.visibility = if (state.playing) View.VISIBLE else View.GONE
        }
        titleView.text = state.title.ifEmpty { web.title ?: "" }
        statusView.text = state.status
        statusView.contentDescription = "Reader status, " + state.status
        engineChip.text = state.engine
        engineChip.visibility = if (state.engine.isEmpty()) View.GONE else View.VISIBLE
        progressView.text = if (total > 0) "${state.cursor + 1} / $total" else "0 / 0"
        speedView.text = "%.2f×".format(NovelSpeakApp.instance.prefs.speed)
        speedView.contentDescription =
            "Playback speed ${"%.2f".format(NovelSpeakApp.instance.prefs.speed)} times"
        // isPressed is true only while the user is actually holding the thumb; the
        // tracking flag alone can stick if a gesture is interrupted (system nav,
        // auto chapter advance), which froze the thumb while the label kept moving.
        val dragging = seeking && seekBar.isPressed
        if (!dragging) {
            seekBar.max = (total - 1).coerceAtLeast(0)
            seekBar.progress = state.cursor.coerceIn(0, (total - 1).coerceAtLeast(0))
        }
        val newMax = (total - 1).coerceAtLeast(0)
        if (seekBar.max != newMax) {
            seeking = false
            uiHandler.removeCallbacks(seekingTimeout)
            seekBar.max = newMax
            seekBar.progress = state.cursor.coerceIn(0, newMax)
        }
        seekBar.contentDescription =
            if (total > 0) "Sentence ${state.cursor + 1} of $total" else "Sentence progress"
        playGlyph.glyph = if (state.playing) Glyph.PAUSE else Glyph.PLAY
        playGlyph.invalidate()
        playButton.contentDescription = if (state.playing) "Pause" else "Play"
        dotView.background = oval(statusColor(state))
        updateReaderButton()
        updateNotificationHint()
    }

    private fun statusColor(state: Reader.State): Int {
        val s = state.status.lowercase()
        return when {
            s.contains("error") || s.contains("failed") -> Palette.RED
            state.playing -> Palette.ACCENT
            s.contains("paused") -> Palette.AMBER
            else -> Palette.MUTED
        }
    }

    override fun onHighlight(blockIndex: Int) {
        if (NovelSpeakApp.instance.prefs.highlight) {
            WebExtractor.highlight(web, blockIndex)
        }
    }

    override fun onWordHighlight(blockIndex: Int, charStart: Int, charEnd: Int) {
        val p = NovelSpeakApp.instance.prefs
        if (!p.highlight || !p.readerWordHighlight) return
        WebExtractor.highlightWord(web, blockIndex, charStart, charEnd)
    }

    private fun updateShield() {
        if (!::adblockView.isInitialized) return
        val p = NovelSpeakApp.instance.prefs
        val host = UrlTools.host(web.url ?: "") ?: ""
        val allowed = host.isNotEmpty() && AdBlocker.isSiteAllowed(host)
        adblockView.text = when {
            !p.adblockEnabled -> "Ads off"
            allowed -> "Ads allowed"
            else -> "Ads blocked"
        }
    }

    override fun onAdBlockStats(blocked: Int, hidden: Int) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (!::adblockView.isInitialized) return@runOnUiThread
            val p = NovelSpeakApp.instance.prefs
            val host = UrlTools.host(web.url ?: "") ?: ""
            val allowed = host.isNotEmpty() && AdBlocker.isSiteAllowed(host)
            adblockView.text = when {
                !p.adblockEnabled -> "Ads off"
                allowed -> "Ads allowed"
                blocked > 0 || hidden > 0 ->
                    if (hidden > 0) "Ad $blocked/$hidden" else "Ad $blocked"
                else -> "Ads blocked"
            }
        }
    }

    private fun updateNotificationHint() {
        if (!::notificationHint.isInitialized) return
        val state = reader.state()
        val granted = AppPermissions.notificationsGranted(this)
        val channelOn = PlaybackService.channelEnabled(this)
        val show = (!granted || !channelOn) && (state.total > 0 || state.playing)
        notificationHint.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        notificationHint.text = if (!granted) {
            "Playback controls hidden · Allow notifications"
        } else {
            "Playback controls muted · Open notification settings"
        }
        notificationHint.contentDescription = notificationHint.text
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AppPermissions.REQ_NOTIFICATIONS) {
            updateNotificationHint()
            if (AppPermissions.notificationsGranted(this)) {
                PlaybackService.refresh(this)
            }
        }
    }

    override fun onBackPressed() {
        if (homeVisible) {
            super.onBackPressed()
            return
        }
        if (editingUrl) {
            exitEditMode()
            return
        }
        if (::suggestionsPopup.isInitialized && suggestionsPopup.isShowing) {
            suggestionsPopup.dismiss()
            return
        }
        if (web.canGoBack()) {
            web.goBack()
            updateNavButtons()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        try { web.onResume() } catch (t: Throwable) { }
        updateReaderButton()
        val prefs = NovelSpeakApp.instance.prefs
        val host = UrlTools.host(web.url ?: "") ?: ""
        val site = app.novelspeak.adblock.PublicSuffixList.registrableDomain(host).ifEmpty { host }
        WebExtractor.setReader(web, prefs.readerModeForSite(site))
        applyReaderPrefs()
        updateShield()
        updateNotificationHint()
        AdBlocker.inject(web, web.url ?: "")
    }

    override fun onPause() {
        super.onPause()
        // Stop page timers while reading with the screen off; the reader and media
        // service are independent of the WebView, so playback continues.
        if (!reader.state().playing) {
            try { web.onPause() } catch (t: Throwable) { }
        }
    }

    override fun onDestroy() {
        dismissSuggestions()
        cancelPrefetch()
        uiHandler.removeCallbacks(prefetchRunnable)
        uiHandler.removeCallbacks(sleepTick)
        reader.removeListener(this)
        AdBlocker.removeListener(this)
        if (isFinishing) {
            try {
                web.loadUrl("about:blank")
                web.destroy()
            } catch (t: Throwable) { }
        }
        super.onDestroy()
    }

    // ---- tiny design system ------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun roundedStroke(
        color: Int,
        radiusDp: Int,
        strokeColor: Int,
        strokeDp: Int
    ): GradientDrawable = rounded(color, radiusDp).apply {
        setStroke(dp(strokeDp), strokeColor)
    }

    private fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
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

    private fun textButton(
        label: String,
        primary: Boolean,
        widthDp: Int,
        heightDp: Int,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(if (primary) Palette.BG else Palette.TEXT)
        background = ripple(if (primary) Palette.ACCENT else Palette.SURFACE_HIGH, 12)
        setPadding(dp(12), 0, dp(12), 0)
        minWidth = 0
        minHeight = 0
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            if (widthDp > 0) dp(widthDp) else ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(heightDp)
        ).apply { marginEnd = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun iconButton(
        glyph: Glyph,
        description: String,
        sizeDp: Int,
        background: Int = Palette.SURFACE_HIGH,
        foreground: Int = Palette.TEXT,
        oval: Boolean = false,
        onClick: () -> Unit
    ): FrameLayout {
        val size = dp(sizeDp)
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(4) }
            this.background = ripple(background, sizeDp / 2, oval)
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val icon = GlyphView(this, glyph, foreground)
        container.addView(
            icon,
            FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        )
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return container
    }

    companion object {
        private const val DEFAULT_URL = "https://www.empirenovel.com/"
        private const val MENU_HOME = 1
        private const val MENU_RELOAD = 2
        private const val MENU_COPY = 3
        private const val MENU_SHARE = 4
        private const val MENU_EXTERNAL = 5
        private const val MENU_SITE = 6
        private const val MENU_BOOKMARK = 12
        private const val MENU_LIBRARY = 7
        private const val MENU_CONTINUE = 8
        private const val MENU_SLEEP = 9
        private const val MENU_SETTINGS = 10
        private const val MENU_PRIVACY = 11
        private const val MENU_TRANSLATE = 13
        private const val MENU_IMPORT = 14
        private const val REQ_IMPORT_DOCUMENT = 1001
        private const val PREFETCH_DELAY_MS = 12_000L
        private const val PREFETCH_MIN_FREE_BYTES = 350L * 1024 * 1024

        /** Pause between a chapter's extraction and its first spoken sentence. */
        private const val AUTO_PLAY_DELAY_MS = 2500L

        /** Stop a runaway auto-advance loop (wrong next-links, redirect chains). */
        private const val MAX_AUTO_CHAPTERS = 40
    }
}

// ---- palette + drawn icons -------------------------------------------------

private object Palette {
    val BG = 0xFF0F1115.toInt()
    val SURFACE = 0xFF171A21.toInt()
    val SURFACE_HIGH = 0xFF222833.toInt()
    val BORDER = 0xFF2C3442.toInt()
    val TEXT = 0xFFF3F6F9.toInt()
    val MUTED = 0xFF9AA6B6.toInt()
    val ACCENT = 0xFF6EE7B7.toInt()
    val AMBER = 0xFFF6C453.toInt()
    val RED = 0xFFF87171.toInt()
    val RIPPLE = 0x33FFFFFF
}

private enum class Glyph {
    PLAY, PAUSE, STOP, PREV, NEXT, BACK, FORWARD, RELOAD, GEAR, MENU, MORE,
    CLOSE, LOCK, GLOBE, ALERT, PLUS, MINUS
}

private class GlyphView(
    context: android.content.Context,
    var glyph: Glyph,
    color: Int
) : View(context) {

    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    fun setGlyph(g: Glyph, color: Int) {
        glyph = g
        stroke.color = color
        fill.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val s = min(width, height) * 0.42f
        when (glyph) {
            Glyph.PLAY -> {
                val p = Path()
                p.moveTo(cx - s * 0.45f, cy - s)
                p.lineTo(cx + s, cy)
                p.lineTo(cx - s * 0.45f, cy + s)
                p.close()
                canvas.drawPath(p, fill)
            }
            Glyph.PAUSE -> {
                val w = s * 0.34f
                val gap = s * 0.22f
                canvas.drawRoundRect(
                    cx - gap / 2 - w, cy - s, cx - gap / 2, cy + s, w / 2, w / 2, fill
                )
                canvas.drawRoundRect(
                    cx + gap / 2, cy - s, cx + gap / 2 + w, cy + s, w / 2, w / 2, fill
                )
            }
            Glyph.STOP -> canvas.drawRoundRect(
                cx - s * 0.72f, cy - s * 0.72f, cx + s * 0.72f, cy + s * 0.72f,
                s * 0.18f, s * 0.18f, fill
            )
            Glyph.PREV -> {
                val p = Path()
                p.moveTo(cx + s * 0.55f, cy - s)
                p.lineTo(cx - s * 0.35f, cy)
                p.lineTo(cx + s * 0.55f, cy + s)
                p.close()
                canvas.drawPath(p, fill)
                canvas.drawRoundRect(
                    cx - s * 0.78f, cy - s, cx - s * 0.48f, cy + s,
                    s * 0.12f, s * 0.12f, fill
                )
            }
            Glyph.NEXT -> {
                val p = Path()
                p.moveTo(cx - s * 0.55f, cy - s)
                p.lineTo(cx + s * 0.35f, cy)
                p.lineTo(cx - s * 0.55f, cy + s)
                p.close()
                canvas.drawPath(p, fill)
                canvas.drawRoundRect(
                    cx + s * 0.48f, cy - s, cx + s * 0.78f, cy + s,
                    s * 0.12f, s * 0.12f, fill
                )
            }
            Glyph.BACK -> {
                val p = Path()
                p.moveTo(cx + s * 0.35f, cy - s * 0.8f)
                p.lineTo(cx - s * 0.45f, cy)
                p.lineTo(cx + s * 0.35f, cy + s * 0.8f)
                canvas.drawPath(p, stroke)
            }
            Glyph.FORWARD -> {
                val p = Path()
                p.moveTo(cx - s * 0.35f, cy - s * 0.8f)
                p.lineTo(cx + s * 0.45f, cy)
                p.lineTo(cx - s * 0.35f, cy + s * 0.8f)
                canvas.drawPath(p, stroke)
            }
            Glyph.RELOAD -> {
                val r = RectF(cx - s * 0.75f, cy - s * 0.75f, cx + s * 0.75f, cy + s * 0.75f)
                canvas.drawArc(r, -55f, 285f, false, stroke)
                val p = Path()
                p.moveTo(cx + s * 0.55f, cy - s * 0.95f)
                p.lineTo(cx + s * 0.05f, cy - s * 0.72f)
                p.lineTo(cx + s * 0.58f, cy - s * 0.38f)
                p.close()
                canvas.drawPath(p, fill)
            }
            Glyph.MENU -> {
                val half = s * 0.8f
                for (i in -1..1) {
                    val y = cy + i * s * 0.45f
                    canvas.drawLine(cx - half, y, cx + half, y, stroke)
                }
            }
            Glyph.GEAR -> {
                val r = s * 0.55f
                canvas.drawCircle(cx, cy, r, stroke)
                canvas.drawCircle(cx, cy, r * 0.34f, stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    val x1 = cx + (r * 0.85f * Math.cos(a)).toFloat()
                    val y1 = cy + (r * 0.85f * Math.sin(a)).toFloat()
                    val x2 = cx + (r * 1.18f * Math.cos(a)).toFloat()
                    val y2 = cy + (r * 1.18f * Math.sin(a)).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, stroke)
                }
            }
            Glyph.MORE -> {
                for (i in -1..1) {
                    canvas.drawCircle(cx, cy + i * s * 0.58f, s * 0.14f, fill)
                }
            }
            Glyph.CLOSE -> {
                canvas.drawLine(
                    cx - s * 0.62f, cy - s * 0.62f,
                    cx + s * 0.62f, cy + s * 0.62f,
                    stroke
                )
                canvas.drawLine(
                    cx + s * 0.62f, cy - s * 0.62f,
                    cx - s * 0.62f, cy + s * 0.62f,
                    stroke
                )
            }
            Glyph.LOCK -> {
                val shackle = RectF(
                    cx - s * 0.44f,
                    cy - s * 0.88f,
                    cx + s * 0.44f,
                    cy + s * 0.38f
                )
                canvas.drawArc(shackle, 180f, 180f, false, stroke)
                canvas.drawRoundRect(
                    cx - s * 0.64f,
                    cy - s * 0.08f,
                    cx + s * 0.64f,
                    cy + s * 0.86f,
                    s * 0.16f,
                    s * 0.16f,
                    fill
                )
            }
            Glyph.GLOBE -> {
                canvas.drawCircle(cx, cy, s * 0.86f, stroke)
                canvas.drawLine(cx - s * 0.86f, cy, cx + s * 0.86f, cy, stroke)
                canvas.drawOval(
                    RectF(
                        cx - s * 0.38f,
                        cy - s * 0.86f,
                        cx + s * 0.38f,
                        cy + s * 0.86f
                    ),
                    stroke
                )
            }
            Glyph.ALERT -> {
                val p = Path()
                p.moveTo(cx, cy - s * 0.92f)
                p.lineTo(cx + s * 0.92f, cy + s * 0.76f)
                p.lineTo(cx - s * 0.92f, cy + s * 0.76f)
                p.close()
                canvas.drawPath(p, stroke)
                canvas.drawLine(cx, cy - s * 0.34f, cx, cy + s * 0.22f, stroke)
                canvas.drawCircle(cx, cy + s * 0.49f, s * 0.11f, fill)
            }
            Glyph.PLUS -> {
                canvas.drawLine(cx - s * 0.7f, cy, cx + s * 0.7f, cy, stroke)
                canvas.drawLine(cx, cy - s * 0.7f, cx, cy + s * 0.7f, stroke)
            }
            Glyph.MINUS -> canvas.drawLine(cx - s * 0.7f, cy, cx + s * 0.7f, cy, stroke)
        }
    }
}
