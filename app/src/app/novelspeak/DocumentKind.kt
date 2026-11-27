package app.novelspeak

/**
 * Which content:// documents the import pipeline accepts. Shared by the picker result gate and
 * the ACTION_VIEW/SEND intent gate so a type accepted by one path cannot be dropped by the other.
 */
object DocumentKind {

    const val EPUB = "epub"
    const val PDF = "pdf"
    const val TEXT = "text"

    fun isImportable(mime: String?, path: String?): Boolean = kindOrNull(mime, path) != null

    fun kind(mime: String?, path: String?): String? = kindOrNull(mime, path) ?: TEXT

    private fun kindOrNull(mime: String?, path: String?): String? {
        val m = mime?.trim()?.lowercase().orEmpty()
        val p = path?.trim()?.lowercase().orEmpty()
        if (m.contains("epub") || p.endsWith(".epub")) return EPUB
        if (m.contains("pdf") || p.endsWith(".pdf")) return PDF
        // HTML is a web page, not a stored book; importing it would read markup aloud.
        if (m.contains("html") || p.endsWith(".html") || p.endsWith(".htm")) return null
        if (m.startsWith("text/") || p.endsWith(".txt")) return TEXT
        return null
    }
}
