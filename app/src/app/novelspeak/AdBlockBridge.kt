package app.novelspeak

import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/**
 * The only object exposed to page JavaScript. It can return cosmetic selectors for class/id names
 * and report a hidden-element count; it has no file, network, or reflection access. Inputs are
 * bounded because any page can call it.
 */
class AdBlockBridge {

    @JavascriptInterface
    fun lookup(json: String): String {
        if (json.length > 32_768) return "[]"
        return try {
            val obj = JSONObject(json)
            val classes = names(obj.optJSONArray("c"))
            val ids = names(obj.optJSONArray("i"))
            val host = AdBlocker.currentDocumentHost() ?: return "[]"
            val selectors = AdBlocker.genericSelectors(classes, ids, host)
            JSONArray(selectors).toString()
        } catch (t: Throwable) {
            "[]"
        }
    }

    @JavascriptInterface
    fun hidden(count: Int) {
        AdBlocker.addHidden(count)
    }

    private fun names(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(minOf(arr.length(), 400))
        var i = 0
        while (i < arr.length() && out.size < 400) {
            val s = arr.optString(i, "")
            if (validName(s)) out.add(s)
            i++
        }
        return out
    }

    private fun validName(s: String): Boolean {
        if (s.length < 1 || s.length > 80) return false
        for (c in s) {
            val ok = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'
            if (!ok) return false
        }
        return true
    }
}
