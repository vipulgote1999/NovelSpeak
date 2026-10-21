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

    fun install(web: WebView) {
        val ctx = web.context
        web.evaluateJavascript(readAsset(ctx, "readability.js"), null)
        web.evaluateJavascript(readAsset(ctx, "extract.js"), null)
    }

    fun extract(web: WebView, onResult: (String, List<Reader.Block>) -> Unit) {
        web.evaluateJavascript("window.__nsExtract ? window.__nsExtract() : ''") { value ->
            val json = unquote(value)
            if (json.isNullOrEmpty()) { onResult("", emptyList()); return@evaluateJavascript }
            try {
                val obj = JSONObject(json)
                val title = obj.optString("title", "")
                val arr = obj.optJSONArray("blocks") ?: return@evaluateJavascript onResult(title, emptyList())
                val max = NovelSpeakApp.instance.prefs.maxSentenceChars
                val blocks = ArrayList<Reader.Block>()
                for (i in 0 until arr.length()) {
                    val b = arr.getJSONObject(i)
                    val text = b.optString("text", "")
                    val blockIndex = b.optInt("i", i)
                    if (text.isBlank()) continue
                    for (s in SentenceSplitter.split(text, max)) {
                        blocks.add(Reader.Block(s, blockIndex))
                    }
                }
                onResult(title, blocks)
            } catch (e: Throwable) {
                onResult("", emptyList())
            }
        }
    }

    fun highlight(web: WebView, blockIndex: Int) {
        web.evaluateJavascript("window.__nsHighlight && window.__nsHighlight($blockIndex)", null)
    }

    fun clearHighlight(web: WebView) {
        web.evaluateJavascript("window.__nsClearHighlight && window.__nsClearHighlight()", null)
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
