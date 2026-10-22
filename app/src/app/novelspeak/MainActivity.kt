package app.novelspeak

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.text.InputType
import android.text.TextUtils
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
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
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
    private lateinit var urlBar: EditText
    private lateinit var titleView: TextView
    private lateinit var adblockView: TextView
    private lateinit var adButton: TextView
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
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(4))
        }
        backButton = iconButton(Glyph.BACK, "Back", 40) { navigateBack() }
        forwardButton = iconButton(Glyph.FORWARD, "Forward", 40) { navigateForward() }
        top.addView(backButton)
        top.addView(forwardButton)
        top.addView(iconButton(Glyph.RELOAD, "Reload", 40) { web.reload() })

        urlBar = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine(true)
            hint = "Paste a chapter URL"
            textSize = 12f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.MUTED)
            background = rounded(Palette.SURFACE_HIGH, 20)
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(6)
            }
            setOnEditorActionListener { _, _, _ ->
                loadFromBar()
                true
            }
        }
        top.addView(urlBar)
        top.addView(
            textButton("Go", primary = false, widthDp = 42, heightDp = 40) { loadFromBar() }
                .apply { setTextColor(Palette.ACCENT) }
        )
        adButton = textButton("Ad", primary = false, widthDp = 42, heightDp = 40) {
            toggleAdblock()
        }.apply {
            textSize = 11f
            setTextColor(Palette.ACCENT)
        }
        top.addView(adButton)
        top.addView(iconButton(Glyph.GEAR, "Settings", 40) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        root.addView(top)

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
            setPadding(dp(8), 0, dp(8), 0)
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
            maybeAskNotifications()
            reader.toggle()
        }
        playGlyph = playButton.getChildAt(0) as GlyphView
        transportRow.addView(playButton)
        transportRow.addView(iconButton(Glyph.NEXT, "Next sentence", 44) {
            reader.nextSentence()
        })
        transportRow.addView(iconButton(Glyph.STOP, "Stop", 40) {
            reader.stop()
        })
        card.addView(transportRow)
        root.addView(card)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && sentenceTotal > 0) {
                    progressView.text = "${progress + 1} / $sentenceTotal"
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                if (sentenceTotal > 0) reader.seek(sb?.progress ?: 0)
            }
        })

        setContentView(root)
        applyInsets(root)
        updateReaderButton()
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
                updateNavButtons()
                AdBlocker.onPageStarted(url ?: "")
                updateShield()
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

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                urlBar.setText(url ?: "")
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
                    // Same document reloaded (ad reload or manual reload): keep the
                    // native reader position, but reinstall the page-side extractor
                    // and refresh data-ns marks so highlight/next-link keep working.
                    try {
                        WebExtractor.install(web)
                        WebExtractor.extract(web) { _, _ -> }
                        WebExtractor.setReader(web, NovelSpeakApp.instance.prefs.readerMode)
                    } catch (t: Throwable) {
                    }
                }
                appendNext = false
            }
        }
        web.webChromeClient = object : WebChromeClient() {
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
        var u = urlBar.text.toString().trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http")) u = "https://$u"
        hideKeyboard()
        urlBar.clearFocus()
        web.loadUrl(u)
    }

    private fun hideKeyboard() {
        try {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(urlBar.windowToken, 0)
        } catch (e: Throwable) {
        }
    }

    private fun extractNow(append: Boolean) {
        WebExtractor.install(web)
        WebExtractor.extract(web) { title, blocks ->
            DiagLog.line(
                this,
                "extract url=${web.url} append=$append title=\"${title.take(60)}\" blocks=${blocks.size}"
            )
            runOnUiThread {
                lastExtractedUrl = web.url
                lastExtractedBlocks = blocks.size
                val badPage = title.contains("not found", true) ||
                    title.contains("404", true)
                val blocked = badPage ||
                    title.contains("just a moment", true) ||
                    title.contains("security verification", true) ||
                    title.contains("cloudflare", true) ||
                    title.contains("attention required", true)
                if (append && (blocked || blocks.size < 2)) {
                    // Never append a 404/Cloudflare interstitial to the reading queue.
                    appendNext = false
                    Toast.makeText(this, "Next chapter not found", Toast.LENGTH_SHORT).show()
                    WebExtractor.setReader(web, false)
                } else if (blocks.isEmpty()) {
                    statusView.text = "No readable text found on this page"
                    WebExtractor.setReader(web, false)
                } else {
                    if (append && reader.hasContent()) {
                        reader.appendContent(blocks)
                    } else {
                        reader.setContent(title.ifEmpty { web.title ?: "" }, blocks)
                    }
                    statusView.text = "Extracted ${blocks.size} sentences"
                    // Do not hide a Cloudflare challenge: it must stay interactive
                    // until the site finishes verification. Reader mode resumes on
                    // the real page automatically.
                    WebExtractor.setReader(
                        web,
                        if (blocked) false else NovelSpeakApp.instance.prefs.readerMode
                    )
                }
            }
        }
    }

    private fun loadNextChapter() {
        WebExtractor.nextLink(web) { link ->
            DiagLog.line(this, "next chapter link=${link.ifEmpty { "(none)" }}")
            runOnUiThread {
                if (link.isNullOrEmpty()) {
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
        if (!seeking) {
            seekBar.max = (total - 1).coerceAtLeast(0)
            seekBar.progress = state.cursor.coerceIn(0, (total - 1).coerceAtLeast(0))
        }
        playGlyph.glyph = if (state.playing) Glyph.PAUSE else Glyph.PLAY
        playGlyph.invalidate()
        playButton.contentDescription = if (state.playing) "Pause" else "Play"
        dotView.background = oval(statusColor(state))
        updateReaderButton()
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

    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            try {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
            } catch (e: Throwable) {
            }
        }
    }

    private fun toggleAdblock() {
        val p = NovelSpeakApp.instance.prefs
        if (!p.adblockEnabled) {
            Toast.makeText(this, "Enable ad blocking in Settings first", Toast.LENGTH_SHORT).show()
            return
        }
        val host = UrlTools.host(web.url ?: "") ?: return
        val nowAllowed = AdBlocker.toggleSite(host)
        Toast.makeText(
            this,
            if (nowAllowed) "Ad blocking disabled on $host" else "Ad blocking enabled on $host",
            Toast.LENGTH_SHORT
        ).show()
        updateShield()
        web.reload()
    }

    private fun updateShield() {
        if (!::adButton.isInitialized) return
        val p = NovelSpeakApp.instance.prefs
        val host = UrlTools.host(web.url ?: "") ?: ""
        val allowed = host.isNotEmpty() && AdBlocker.isSiteAllowed(host)
        adButton.text = if (!p.adblockEnabled || allowed) "Ad✕" else "Ad✓"
        adButton.alpha = if (!p.adblockEnabled) 0.4f else 1f
        if (!p.adblockEnabled || allowed) adblockView.text = ""
    }

    override fun onAdBlockStats(blocked: Int, hidden: Int) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (!::adButton.isInitialized) return@runOnUiThread
            val p = NovelSpeakApp.instance.prefs
            val host = UrlTools.host(web.url ?: "") ?: ""
            val allowed = host.isNotEmpty() && AdBlocker.isSiteAllowed(host)
            adButton.text = if (!p.adblockEnabled || allowed) "Ad✕" else "Ad✓"
            adButton.alpha = if (!p.adblockEnabled) 0.4f else 1f
            adblockView.text = if (p.adblockEnabled && !allowed && (blocked > 0 || hidden > 0)) {
                if (hidden > 0) "Ad $blocked/$hidden" else "Ad $blocked"
            } else {
                ""
            }
        }
    }

    override fun onBackPressed() {
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
        AdBlocker.inject(web, web.url ?: "")
    }

    override fun onDestroy() {
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
    PLAY, PAUSE, STOP, PREV, NEXT, BACK, FORWARD, RELOAD, GEAR, MENU, PLUS, MINUS
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
            Glyph.PLUS -> {
                canvas.drawLine(cx - s * 0.7f, cy, cx + s * 0.7f, cy, stroke)
                canvas.drawLine(cx, cy - s * 0.7f, cx, cy + s * 0.7f, stroke)
            }
            Glyph.MINUS -> canvas.drawLine(cx - s * 0.7f, cy, cx + s * 0.7f, cy, stroke)
        }
    }
}
