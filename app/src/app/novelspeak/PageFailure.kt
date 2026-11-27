package app.novelspeak

/**
 * Detects the pages WebView renders when a navigation fails. Those pages look like prose to
 * Readability, so without this gate the reader queues "Webpage not available" or a 404 notice
 * and speaks it. Pure, so the host suite can pin the titles that are refused.
 */
object PageFailure {

    fun isErrorPage(title: String): Boolean {
        val t = title.trim().lowercase()
        if (t.isEmpty()) return false
        if (t.startsWith("net::err")) return true
        if (t.startsWith("webpage not available")) return true
        if (t.contains("could not be loaded because")) return true
        if (t.startsWith("this site can") || t.startsWith("site can")) return true
        if (t.startsWith("problem loading page")) return true
        if (t == "no internet" || t.startsWith("no internet ")) return true
        if (t == "404") return true
        if (t.startsWith("404") && (t.contains("not found") || t.contains("error"))) {
            return true
        }
        if (t == "not found" || t.startsWith("page not found")) return true
        return false
    }

    /** Server error documents often carry a generic title but an unmistakable first block. */
    fun isErrorBody(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false
        if (t.startsWith("error response")) return true
        if (t.contains("error code: 404") || t.contains("error code 404")) return true
        if (t.contains("net::err_")) return true
        if (t.contains("webpage not available")) return true
        if (t.contains("this page could not be found")) return true
        return false
    }
}
