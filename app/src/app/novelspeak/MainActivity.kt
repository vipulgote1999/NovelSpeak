package app.novelspeak

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
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
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import app.novelspeak.adblock.UrlTools
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
        onReaderState(reader.state())
        val last = getSharedPreferences("novelspeak", MODE_PRIVATE).getString("last_url", null)
        web.loadUrl(last ?: DEFAULT_URL)
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
        val titleRow = LinearLayout(this).apply {
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

        // ---- page ----
        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Palette.BG)
        }
        root.addView(web)
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

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        actionsRow.addView(textButton("Read", primary = false, widthDp = 0, heightDp = 38) {
            extractNow(append = false)
        })
        actionsRow.addView(textButton("Next ch", primary = false, widthDp = 0, heightDp = 38) {
            loadNextChapter()
        })
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
        root.addView(card)

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
                pageLoading = true
                progressBar.visibility = View.VISIBLE
                progressBar.progress = 0
                setReloadGlyph(true)
                if (!editingUrl) updateAddressDisplay(url)
                updateNavButtons()
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
        popup.menu.add(0, MENU_SETTINGS, 6, "NovelSpeak settings")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_HOME -> web.loadUrl(DEFAULT_URL)
                MENU_RELOAD -> web.reload()
                MENU_COPY -> copyLink()
                MENU_SHARE -> shareLink()
                MENU_EXTERNAL -> openExternal()
                MENU_SITE -> showSiteDialog()
                MENU_SETTINGS -> startActivity(
                    Intent(this, SettingsActivity::class.java)
                )
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
            prefs.readerMode,
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
                    prefs.readerMode = isChecked
                    WebExtractor.setReader(web, prefs.readerMode)
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
            WebExtractor.extract(web) { title, blocks ->
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
                        statusView.text = "Extracted ${blocks.size} sentences"
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

    override fun onQueueFinished() {
        val p = NovelSpeakApp.instance.prefs
        DiagLog.line(this, "queue finished auto=${p.autoMode} count=$autoAdvanceCount")
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
        val v = (NovelSpeakApp.instance.prefs.speed + delta).coerceIn(0.5f, 2.5f)
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
        val on = NovelSpeakApp.instance.prefs.readerMode
        readerBtn.text = if (on) "Reader on" else "Reader"
        readerBtn.setTextColor(if (on) Palette.ACCENT else Palette.TEXT)
        readerBtn.background = ripple(Palette.SURFACE_HIGH, 16)
        readerBtn.contentDescription =
            if (on) "Turn reader mode off" else "Turn reader mode on"
    }

    override fun onReaderState(state: Reader.State) {
        val total = state.total.coerceAtLeast(0)
        sentenceTotal = total
        titleView.text = state.title.ifEmpty { web.title ?: "" }
        statusView.text = state.status
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
        updateReaderButton()
        WebExtractor.setReader(web, NovelSpeakApp.instance.prefs.readerMode)
        updateShield()
        updateNotificationHint()
        AdBlocker.inject(web, web.url ?: "")
    }

    override fun onDestroy() {
        dismissSuggestions()
        reader.removeListener(this)
        AdBlocker.removeListener(this)
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
        private const val MENU_SETTINGS = 7

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
