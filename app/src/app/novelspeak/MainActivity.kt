package app.novelspeak

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * WebView browser + reader UI.
 *
 * The page itself is untouched: we only inject the extractor after load, then drive TTS from the
 * extracted sentences. Works for any URL, which is the requirement (empirenovel and any similar
 * serialized-novel site).
 */
class MainActivity : Activity(), Reader.Listener {

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var statusView: TextView
    private lateinit var titleView: TextView
    private lateinit var playBtn: Button
    private lateinit var speedView: TextView

    private var appendNext = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NovelSpeakApp.instance.reader.addListener(this)
        buildUi()
        configureWebView()
        val last = getSharedPreferences("novelspeak", MODE_PRIVATE).getString("last_url", null)
        web.loadUrl(last ?: DEFAULT_URL)
    }

    private fun buildUi() {
        val pad = (resources.displayMetrics.density * 8).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // --- URL bar ---
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(pad, pad, pad, 0) }
        bar.addView(iconButton("◀") { if (web.canGoBack()) web.goBack() })
        bar.addView(iconButton("▶") { if (web.canGoForward()) web.goForward() })
        bar.addView(iconButton("⟳") { web.reload() })
        urlBar = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine(true)
            hint = "https://…"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        urlBar.setOnEditorActionListener { _, _, _ -> loadFromBar(); true }
        bar.addView(urlBar)
        bar.addView(iconButton("Go") { loadFromBar() })
        bar.addView(iconButton("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(bar)

        titleView = TextView(this).apply {
            setPadding(pad, 2, pad, 2)
            textSize = 12f
            maxLines = 1
            setTextColor(Color.DKGRAY)
        }
        root.addView(titleView)

        // --- WebView ---
        web = WebView(this)
        web.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        root.addView(web)

        // --- Player bar ---
        val player = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        player.addView(iconButton("Read") { extractNow(append = false) })
        player.addView(iconButton("Next ch") { loadNextChapter() })
        player.addView(iconButton("⏮") { NovelSpeakApp.instance.reader.prevSentence() })
        playBtn = iconButton("▶") { NovelSpeakApp.instance.reader.toggle() }
        player.addView(playBtn)
        player.addView(iconButton("⏭") { NovelSpeakApp.instance.reader.nextSentence() })
        player.addView(iconButton("■") { NovelSpeakApp.instance.reader.stop() })
        player.addView(iconButton("−") { bumpSpeed(-0.05f) })
        speedView = TextView(this).apply {
            text = "1.00×"
            setPadding(pad, 0, pad, 0)
            textSize = 13f
        }
        player.addView(speedView)
        player.addView(iconButton("+") { bumpSpeed(0.05f) })
        root.addView(player)

        statusView = TextView(this).apply {
            setPadding(pad, 0, pad, pad)
            textSize = 12f
            setTextColor(Color.DKGRAY)
            text = "Open a chapter, then tap Read"
        }
        root.addView(statusView)

        setContentView(root)
    }

    private fun iconButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(12, 0, 12, 0)
            setOnClickListener { onClick() }
        }

    private fun configureWebView() {
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
        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                urlBar.setText(url ?: "")
                titleView.text = view?.title ?: ""
                getSharedPreferences("novelspeak", MODE_PRIVATE).edit().putString("last_url", url ?: "").apply()
                // Auto-extract so "next chapter" can continue seamlessly.
                extractNow(append = appendNext)
                appendNext = false
            }
        }
        web.webChromeClient = WebChromeClient()
    }

    private fun loadFromBar() {
        var u = urlBar.text.toString().trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http")) u = "https://$u"
        web.loadUrl(u)
    }

    private fun extractNow(append: Boolean) {
        WebExtractor.install(web)
        WebExtractor.extract(web) { title, blocks ->
            runOnUiThread {
                if (blocks.isEmpty()) {
                    statusView.text = "No readable text found on this page"
                } else {
                    if (append && NovelSpeakApp.instance.reader.hasContent()) {
                        NovelSpeakApp.instance.reader.appendContent(blocks)
                    } else {
                        NovelSpeakApp.instance.reader.setContent(title.ifEmpty { web.title ?: "" }, blocks)
                    }
                    statusView.text = "Extracted ${blocks.size} sentences"
                }
            }
        }
    }

    private fun loadNextChapter() {
        WebExtractor.nextLink(web) { link ->
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
        NovelSpeakApp.instance.reader.setSpeed(v)
        speedView.text = "%.2f×".format(v)
    }

    override fun onReaderState(state: Reader.State) {
        playBtn.text = if (state.playing) "⏸" else "▶"
        titleView.text = state.title.ifEmpty { web.title ?: "" }
        statusView.text = "${state.status}  ·  ${state.cursor + 1}/${state.total}  ·  ${state.engine}"
        speedView.text = "%.2f×".format(NovelSpeakApp.instance.prefs.speed)
    }

    override fun onHighlight(blockIndex: Int) {
        if (NovelSpeakApp.instance.prefs.highlight) WebExtractor.highlight(web, blockIndex)
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        NovelSpeakApp.instance.reader.removeListener(this)
        super.onDestroy()
    }

    companion object {
        // Generic default; any URL works.
        private const val DEFAULT_URL = "https://www.empirenovel.com/"
    }
}
