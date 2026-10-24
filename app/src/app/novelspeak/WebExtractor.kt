package app.novelspeak

import android.content.Context
import android.webkit.WebView
import org.json.JSONObject

/**
 * Generic page -> sentences extraction.
 *
 * Two stages, both site-agnostic:
 *  1. Mozilla Readability (bundled, same library Firefox Reader Mode uses)
 *  2. text-density fallback in extract.js for pages Readability rejects (chapter sites often have
 *     odd markup; Cloudflare-protected pages still work once the WebView has a valid session)
 *
 * Emitted blocks carry `data-ns` indices so playback can highlight and auto-scroll the exact
 * paragraph being spoken, on any site.
 */
object WebExtractor {

    /**
     * Injects the page-side libraries. [onReady] runs after `extract.js` has executed, so
     * callers that immediately call [extract] cannot race the async script evaluation
     * (which previously produced 0 blocks on freshly loaded documents).
     */
    fun install(web: WebView, onReady: (() -> Unit)? = null) {
        val ctx = web.context
        web.evaluateJavascript(readAsset(ctx, "readability.js")) {
            web.evaluateJavascript(readAsset(ctx, "extract.js")) {
                onReady?.invoke()
            }
        }
    }

    /**
     * Extract the current page. [onResult] receives (title, blocks, wall) where wall is one of
     * "", "login", "subscribe" or "challenge" when the page looks gated — the UI must warn
     * instead of speaking paywall/interstitial text. Nothing is bypassed.
     */
    fun extract(web: WebView, onResult: (String, List<Reader.Block>, String) -> Unit) {
        web.evaluateJavascript("window.__nsExtract ? window.__nsExtract() : ''") { value ->
            val json = unquote(value)
            if (json.isNullOrEmpty()) { onResult("", emptyList(), ""); return@evaluateJavascript }
            try {
                val obj = JSONObject(json)
                val title = obj.optString("title", "")
                val wall = obj.optString("wall", "")
                val arr = obj.optJSONArray("blocks")
                    ?: return@evaluateJavascript onResult(title, emptyList(), wall)
                val max = NovelSpeakApp.instance.prefs.maxSentenceChars
                val raw = ArrayList<Reader.Block>()
                for (i in 0 until arr.length()) {
                    val b = arr.getJSONObject(i)
                    val text = b.optString("text", "")
                    val blockIndex = b.optInt("i", i)
                    if (text.isBlank()) continue
                    for (s in SentenceSplitter.split(text, max)) {
                        raw.add(Reader.Block(s, blockIndex))
                    }
                }
                onResult(title, mergeShortFragments(raw, max), wall)
            } catch (e: Throwable) {
                onResult("", emptyList(), "")
            }
        }
    }

    /**
     * Merge short / speech-less fragments before they reach a neural engine.
     * See [SentenceSplitter.mergeGroups] for the rationale; the merged block keeps
     * the first fragment's `data-ns` index so read-along highlight still works.
     */
    private fun mergeShortFragments(
        blocks: List<Reader.Block>,
        maxChars: Int
    ): List<Reader.Block> {
        val groups = SentenceSplitter.mergeGroups(blocks.map { it.text }, maxChars)
        val out = ArrayList<Reader.Block>(groups.size)
        for (g in groups) {
            val text = StringBuilder()
            for (idx in g) {
                val t = blocks[idx].text
                if (t.none { it.isLetterOrDigit() }) continue
                if (text.isNotEmpty()) text.append(' ')
                text.append(t)
            }
            if (text.isEmpty()) continue
            out.add(Reader.Block(text.toString(), blocks[g.first].blockIndex))
        }
        return out
    }

    /** Apply user typography (font size/family/theme/width) after the built-in reader CSS. */
    fun applyReaderStyle(web: WebView, css: String) {
        val escaped = org.json.JSONObject.quote(css)
        web.evaluateJavascript(
            "window.__nsApplyReaderStyle && window.__nsApplyReaderStyle($escaped)", null
        )
    }

    /**
     * Word-level read-along. charStart/charEnd are offsets into the normalized block text
     * (the same text that was queued for speech). Chromium's CSS Custom Highlight API is
     * used; engines/pages that lack it silently keep the sentence highlight.
     */
    fun highlightWord(web: WebView, blockIndex: Int, charStart: Int, charEnd: Int) {
        web.evaluateJavascript(
            "window.__nsHighlightWord && window.__nsHighlightWord($blockIndex,$charStart,$charEnd)",
            null
        )
    }

    fun highlight(web: WebView, blockIndex: Int) {
        val scroll = NovelSpeakApp.instance.prefs.autoScroll
        web.evaluateJavascript(
            "window.__nsHighlight && window.__nsHighlight($blockIndex, $scroll)", null
        )
    }

    fun clearHighlight(web: WebView) {
        web.evaluateJavascript("window.__nsClearHighlight && window.__nsClearHighlight()", null)
    }

    /** Toggle the injected reader theme (hides site chrome/ads and restyles the article). */
    fun setReader(web: WebView, on: Boolean) {
        web.evaluateJavascript("window.__nsReader && window.__nsReader($on)", null)
    }

    fun nextLink(web: WebView, onResult: (String) -> Unit) {
        web.evaluateJavascript("window.__nsNextLink ? window.__nsNextLink() : ''") { value ->
            onResult(unquote(value) ?: "")
        }
    }

    private fun readAsset(ctx: Context, name: String): String = try {
        ctx.assets.open(name).bufferedReader().use { it.readText() }
    } catch (e: Throwable) {
        ""
    }

    /** evaluateJavascript returns a JSON-encoded string; decode it back. */
    private fun unquote(value: String?): String? {
        if (value == null || value == "null" || value.isEmpty()) return null
        return try {
            JSONObject("{\"v\":$value}").getString("v")
        } catch (e: Throwable) {
            value.trim('"')
        }
    }
}
