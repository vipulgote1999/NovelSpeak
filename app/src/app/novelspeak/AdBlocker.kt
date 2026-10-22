package app.novelspeak

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import java.lang.ref.WeakReference
import app.novelspeak.adblock.AdBlockEngine
import app.novelspeak.adblock.CosmeticConfig
import app.novelspeak.adblock.FilterParser
import app.novelspeak.adblock.PublicSuffixList
import app.novelspeak.adblock.RequestInfo
import app.novelspeak.adblock.ResourceType
import app.novelspeak.adblock.Surrogates
import app.novelspeak.adblock.UrlTools
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Android-side adapter around the pure-Kotlin filter engine.
 *
 * The WebView hook is `shouldInterceptRequest`; returning `null` lets the request continue and
 * returning a response substitutes an empty body (the same contract DuckDuckGo's
 * `WebViewRequestInterceptor` relies on). Main-frame navigations are handled separately in
 * `shouldOverrideUrlLoading`, because returning an empty response for a document would show a blank
 * page instead of leaving the reader on the current chapter.
 */
object AdBlocker {

    interface Listener {
        fun onAdBlockStats(blocked: Int, hidden: Int)
    }

    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ns-adblock-load").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val blockedCount = AtomicInteger()
    private val hiddenCount = AtomicInteger()
    private val lastNotifyMs = AtomicLong(0)
    private val lookupWindowStart = AtomicLong(0)
    private val lookupWindowCount = AtomicInteger(0)

    @Volatile
    private var context: Context? = null

    @Volatile
    private var prefs: Prefs? = null

    @Volatile
    private var engine: AdBlockEngine? = null

    @Volatile
    private var loading = false

    @Volatile
    private var reloadPending = false

    @Volatile
    private var loadError: String? = null

    @Volatile
    private var status = "Not loaded"

    @Volatile
    private var currentUrl: String? = null

    @Volatile
    private var currentHost: String? = null

    @Volatile
    private var contentScript: String? = null

    @Volatile
    private var webRef: WeakReference<WebView>? = null

    private val pixelGif: ByteArray by lazy {
        Base64.decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==", Base64.DEFAULT)
    }

    private val lists = listOf(
        "easylist.txt" to "https://easylist.to/easylist/easylist.txt",
        "easyprivacy.txt" to "https://easylist.to/easylist/easyprivacy.txt",
        "ublock-filters.txt" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
        "ublock-annoyances.txt" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/annoyances-others.txt",
        "ublock-badware.txt" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt"
    )

    /**
     * TLDs that are cheap/free and disproportionately used for throwaway ad landing pages.
     * A third-party, non-gesture navigation to one of these is treated as a popunder. This is
     * a heuristic, not a list rule; it is on the "Block popups and redirects" switch.
     */
    private val SUSPICIOUS_TLDS = setOf(
        "top", "xyz", "click", "buzz", "rest", "monster", "cfd", "lat", "loan", "work",
        "men", "review", "country", "stream", "download", "racing", "win", "bid", "party",
        "trade", "webcam", "science", "gq", "ml", "ga", "cf", "tk"
    )

    fun init(ctx: Context) {
        if (context != null) return
        val app = ctx.applicationContext
        context = app
        prefs = Prefs(app)
        reload()
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    /** Keeps a weak handle so cosmetic rules can be applied when the engine finishes after a page load. */
    fun attach(web: WebView) {
        webRef = WeakReference(web)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun reload() {
        val ctx = context ?: return
        if (loading) {
            reloadPending = true
            return
        }
        loading = true
        loadError = null
        status = "Loading filter lists…"
        loader.execute {
            try {
                val psl = readAssetText(ctx, "adblock/public_suffix_list.dat")
                if (psl.isNullOrEmpty()) throw IllegalStateException("missing public suffix list")
                PublicSuffixList.load(psl)

                val lines = ArrayList<String>(180_000)
                for ((name, _) in lists) {
                    val updated = File(ctx.filesDir, "adblock/$name")
                    if (updated.isFile && updated.length() > 4096) {
                        updated.forEachLine { lines.add(it) }
                    } else {
                        appendAssetLines(ctx, "adblock/$name", lines)
                    }
                }

                val parsed = FilterParser.parse(lines)
                val built = AdBlockEngine.build(parsed.network, parsed.cosmetic)
                engine = built
                val s = built.stats()
                status = "Ready · ${s.networkRules} network · ${s.cosmeticRules} cosmetic rules"
                DiagLog.line(
                    ctx,
                    "adblock ready network=${s.networkRules} cosmetic=${s.cosmeticRules} " +
                        "scriptlets=${s.scriptletRules} build=${s.buildMs}ms skipped=${parsed.skipped}"
                )
                // The first page can finish loading before the lists are indexed. Re-apply the
                // cosmetic layer now instead of waiting for the next reload.
                val w = webRef?.get()
                if (w != null) {
                    Handler(Looper.getMainLooper()).post {
                        try {
                            val pageUrl = w.url ?: currentUrl ?: ""
                            if (pageUrl.isNotEmpty()) inject(w, pageUrl)
                        } catch (t: Throwable) {
                        }
                    }
                }
            } catch (t: Throwable) {
                engine = null
                loadError = t.message ?: t.javaClass.simpleName
                status = "Ad blocking unavailable: $loadError"
                DiagLog.line(ctx, "adblock load failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                loading = false
                if (reloadPending) {
                    reloadPending = false
                    reload()
                }
            }
        }
    }

    fun statusText(): String {
        val e = engine
        return when {
            loading -> "Loading filter lists…"
            loadError != null -> "Ad blocking unavailable: $loadError"
            e != null -> "Ready · ${e.stats().networkRules} network · ${e.stats().cosmeticRules} cosmetic rules"
            else -> status
        }
    }

    fun onPageStarted(url: String) {
        currentUrl = url
        currentHost = UrlTools.host(url)
        blockedCount.set(0)
        hiddenCount.set(0)
        notifyStats(force = true)
    }

    /** Called from `WebViewClient.shouldInterceptRequest` on a background thread. */
    fun intercept(request: WebResourceRequest?): WebResourceResponse? {
        if (request == null) return null
        val url = request.url?.toString()

        // A main-frame request is the earliest reliable document context; onPageStarted may not
        // have run yet for the very first subresource of a navigation.
        if (request.isForMainFrame) {
            if (!url.isNullOrEmpty()) {
                currentUrl = url
                currentHost = UrlTools.host(url)
            }
            return null
        }

        val p = prefs ?: return null
        if (!p.adblockEnabled) return null
        val e = engine ?: return null
        val documentUrl = currentUrl ?: return null
        val documentHost = UrlTools.host(documentUrl) ?: return null
        if (isSiteAllowed(documentHost)) return null
        if (url == null || !UrlTools.isHttpUrl(url)) return null

        return try {
            val type = UrlTools.typeFromHeaders(request.requestHeaders?.get("Accept"), url)
            val decision = e.check(RequestInfo(url, documentUrl, type, request.method ?: "GET", false))
            if (!decision.block) return null

            blockedCount.incrementAndGet()
            notifyStats()

            val host = UrlTools.host(url) ?: ""
            val surrogate = Surrogates.forHost(host) ?: Surrogates.forRedirect(decision.redirect)
            when {
                surrogate != null -> jsResponse(surrogate)
                type == ResourceType.SCRIPT -> jsResponse("")
                type == ResourceType.IMAGE -> imageResponse()
                else -> emptyResponse()
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Called from `WebViewClient.shouldOverrideUrlLoading` on the UI thread. */
    fun shouldOverride(request: WebResourceRequest?): Boolean {
        if (request == null) return false
        val p = prefs ?: return false
        if (!p.adblockEnabled || !p.adblockPopups) return false
        val url = request.url?.toString() ?: return false
        val documentUrl = currentUrl

        // A site the reader explicitly exempted should not even have its intent: redirects
        // rewritten, otherwise "disable on this site" would be a half-measure.
        if (documentUrl != null) {
            val documentHost = UrlTools.host(documentUrl)
            if (documentHost != null && isSiteAllowed(documentHost)) return false
        }

        val scheme = UrlTools.scheme(url)
        if (url.startsWith("intent:") || url.startsWith("market:") || url.startsWith("app:") ||
            scheme == "intent" || scheme == "market" || scheme == "app"
        ) {
            // Ad redirects commonly jump to an app-install intent; a browser should not follow it.
            return true
        }
        if (!request.isForMainFrame || documentUrl == null) return false

        val targetHost = UrlTools.host(url) ?: return false
        val documentHost = UrlTools.host(documentUrl) ?: return false
        val sameSite = PublicSuffixList.sameSite(targetHost, documentHost)
        val knownPopup = try {
            engine?.shouldBlockDocument(url, documentUrl) == true
        } catch (t: Throwable) {
            false
        }

        if (!request.hasGesture() && knownPopup) {
            return blockNavigation(targetHost)
        }

        // Low-reputation ad landing pages are often too new to be in any filter list. A
        // third-party top-level jump to one is a clickunder / popunder until proven otherwise;
        // staying on the chapter is strictly better than showing a full-page "exclusive content"
        // signup. This also covers an invisible ad click-layer swallowing a tap, which is how the
        // redirect can still carry a gesture flag.
        if (!sameSite) {
            val suspicious = isLikelyAdNavigation(targetHost, url)
            val ctx = context
            if (ctx != null) {
                DiagLog.line(
                    ctx,
                    "adblock nav host=$targetHost gesture=${request.hasGesture()} suspicious=$suspicious"
                )
            }
            if (suspicious) {
                return blockNavigation(targetHost)
            }
        }
        return false
    }

    private fun blockNavigation(host: String): Boolean {
        blockedCount.incrementAndGet()
        notifyStats(force = true)
        toast("Blocked ad redirect: $host")
        return true
    }

    private fun toast(message: String) {
        val ctx = context ?: return
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
            }
        }
    }

    private fun isLikelyAdNavigation(host: String, url: String): Boolean {
        val h = host.lowercase(Locale.US)
        val tld = h.substringAfterLast('.', "")
        if (SUSPICIOUS_TLDS.contains(tld)) return true
        if (h.contains("popunder") || h.contains("popup") || h.contains("clickunder") ||
            h.contains("advert") || h.contains("promo") || h.contains("reward") ||
            h.contains("bonus") || h.contains("prize") || h.contains("casino") ||
            h.contains("tracker") || h.contains("clicktrack")
        ) {
            return true
        }
        val lower = url.lowercase(Locale.US)
        return lower.contains("clickid=") || lower.contains("click_id=") ||
            lower.contains("campaign=") || lower.contains("aff=") ||
            lower.contains("affiliate=") || lower.contains("landing=") ||
            lower.contains("redirect=") || lower.contains("popunder=")
    }

    /** True for the fake "Chrome Mobile WebView Privacy" / push-notification / anti-adblock nags. */
    fun isAdLikeDialog(message: String?): Boolean {
        if (message.isNullOrEmpty()) return false
        val m = message.lowercase(Locale.US)
        if (m.contains("chrome mobile webview") || m.contains("notification system")) return true
        if (m.contains("push notification") || m.contains("allow notifications") ||
            m.contains("enable notifications")
        ) {
            return true
        }
        if (m.contains("notification") &&
            (m.contains("allow") || m.contains("enable") || m.contains("subscribe") || m.contains("push"))
        ) {
            return true
        }
        if ((m.contains("disable") || m.contains("turn off")) &&
            (m.contains("adblock") || m.contains("ad blocker") || m.contains("ad-block"))
        ) {
            return true
        }
        return false
    }

    fun inject(web: WebView, url: String) {
        val p = prefs ?: return
        val e = engine ?: return
        if (!p.adblockEnabled || !p.adblockCosmetic) return
        val host = UrlTools.host(url) ?: return
        if (isSiteAllowed(host)) return

        try {
            val script = contentScript ?: readAssetText(web.context, "adblock/content.js")?.also { contentScript = it }
            if (script.isNullOrEmpty()) return
            web.evaluateJavascript(script, null)

            val config = e.cosmeticFor(host)
            val json = configJson(host, config, p.adblockPopups)
            web.evaluateJavascript("window.__nsAdblockInit && window.__nsAdblockInit($json)", null)

            var injected = 0
            for (js in config.scriptlets) {
                if (injected >= 12) break
                web.evaluateJavascript(js, null)
                injected++
            }
        } catch (t: Throwable) {
            DiagLog.line(web.context, "adblock inject failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun configJson(host: String, config: CosmeticConfig, popups: Boolean): String {
        val root = JSONObject()
        root.put("host", host)
        root.put("popups", popups)
        root.put("generichide", config.generichide)
        root.put("elemhide", config.elemhide)
        root.put("hide", JSONArray(config.hideSelectors))
        root.put("exceptions", JSONArray(config.exceptions))
        val procedural = JSONArray()
        for (p in config.procedural) {
            procedural.put(JSONObject().put("s", p.selector).put("a", p.action).put("v", p.actionArg ?: ""))
        }
        root.put("procedural", procedural)
        return root.toString()
    }

    fun toggleSite(host: String): Boolean {
        val p = prefs ?: return false
        if (host.isEmpty()) return false
        val site = PublicSuffixList.registrableDomain(host).ifEmpty { host }
        val already = p.adblockAllowedSites.contains(site)
        p.setAdblockSiteAllowed(site, !already)
        return !already
    }

    fun isSiteAllowed(host: String): Boolean {
        val p = prefs ?: return false
        val allowed = p.adblockAllowedSites
        if (allowed.isEmpty()) return false
        for (suffix in UrlTools.suffixes(host)) {
            if (allowed.contains(suffix)) return true
        }
        return false
    }

    fun currentDocumentHost(): String? = currentHost

    fun genericSelectors(classes: List<String>, ids: List<String>, host: String): List<String> {
        val p = prefs ?: return emptyList()
        val e = engine ?: return emptyList()
        if (!p.adblockEnabled || !p.adblockCosmetic || isSiteAllowed(host)) return emptyList()

        // The bridge is reachable from page JavaScript; cap its work so a hostile page cannot
        // turn cosmetic lookups into a CPU denial of service.
        val now = System.currentTimeMillis()
        val start = lookupWindowStart.get()
        if (now - start > 1000) {
            lookupWindowStart.set(now)
            lookupWindowCount.set(0)
        }
        if (lookupWindowCount.incrementAndGet() > 25) return emptyList()

        return try {
            e.genericSelectorsFor(classes, ids, host)
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun addHidden(count: Int) {
        if (count <= 0) return
        val total = hiddenCount.addAndGet(if (count > 2000) 2000 else count)
        if (total > 100_000) hiddenCount.set(100_000)
        notifyStats()
    }

    fun updateLists(ctx: Context, onProgress: (String) -> Unit): Boolean {
        if (downloading) {
            onProgress("An update is already running")
            return false
        }
        downloading = true
        var ok = 0
        var failed = 0
        try {
            val dir = File(ctx.filesDir, "adblock")
            if (!dir.isDirectory) dir.mkdirs()
            for ((name, url) in lists) {
                onProgress("Downloading $name…")
                val dest = File(dir, name)
                val tmp = File(dir, "$name.tmp")
                if (download(url, tmp) && validList(tmp)) {
                    if (dest.exists()) dest.delete()
                    if (tmp.renameTo(dest)) ok++ else {
                        tmp.delete()
                        failed++
                    }
                } else {
                    tmp.delete()
                    failed++
                }
            }
        } finally {
            downloading = false
        }
        if (ok > 0) reload()
        onProgress("Updated $ok list(s)" + if (failed > 0) ", $failed failed" else "")
        return ok > 0
    }

    @Volatile
    private var downloading = false

    /** Rejects HTML error pages and other non-list payloads before they replace a good list. */
    private fun validList(f: File): Boolean = try {
        f.bufferedReader().useLines { lines ->
            lines.take(25).any {
                it.startsWith("[Adblock") || it.startsWith("! Title:") ||
                    it.startsWith("!Title:") || it.startsWith("! Description:")
            }
        }
    } catch (t: Throwable) {
        false
    }

    private fun download(url: String, dest: File): Boolean {        var conn: HttpURLConnection? = null
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "NovelSpeak/0.3 (+adblock list update)")
            }
            conn = c
            if (c.responseCode != 200) return false
            val len = c.contentLength.toLong()
            if (len > 25L * 1024 * 1024) return false
            BufferedInputStream(c.inputStream).use { input ->
                ByteArrayOutputStream(if (len > 0) len.toInt() else 64 * 1024).use { out ->
                    val buf = ByteArray(32 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > 25 * 1024 * 1024) return false
                        out.write(buf, 0, n)
                    }
                    if (total < 4096) return false
                    dest.writeBytes(out.toByteArray())
                }
            }
            true
        } catch (t: Throwable) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun notifyStats(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyMs.get() < 120) return
        lastNotifyMs.set(now)
        val b = blockedCount.get()
        val h = hiddenCount.get()
        for (l in listeners) {
            try {
                l.onAdBlockStats(b, h)
            } catch (t: Throwable) {
            }
        }
    }

    private fun jsResponse(js: String): WebResourceResponse {
        val bytes = js.toByteArray(Charsets.UTF_8)
        return WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(bytes))
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun imageResponse(): WebResourceResponse =
        WebResourceResponse("image/gif", null, ByteArrayInputStream(pixelGif))

    private fun readAssetText(ctx: Context, name: String): String? = try {
        ctx.assets.open(name).bufferedReader().use { it.readText() }
    } catch (t: Throwable) {
        null
    }

    private fun appendAssetLines(ctx: Context, name: String, out: MutableList<String>) {
        try {
            ctx.assets.open(name).bufferedReader().useLines { lines -> lines.forEach { out.add(it) } }
        } catch (t: Throwable) {
            DiagLog.line(ctx, "adblock missing asset $name")
        }
    }
}
