package app.novelspeak

/** Counted copy shared by the reader status line, imports and the player notification. */
object UiText {

    /** "1 sentence", "0 sentences", "27 sentences". */
    fun sentenceCount(count: Int): String = countOf(count, "sentence")

    /** "1 line", "0 lines", "12 lines". */
    fun lineCount(count: Int): String = countOf(count, "line")

    /**
     * Humanise an imported filename: "the-salt-road.txt" → "The Salt Road".
     * Returns [fallback] for a blank/extension-only name.
     */
    fun titleFromFileName(name: String, fallback: String): String {
        val base = name.substringBeforeLast('.', name).trim()
        if (base.isEmpty()) return fallback
        val words = base.replace(Regex("[_-]+"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return fallback
        return words.joinToString(" ") { word ->
            word.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
        }
    }

    /** First letter or digit for a cover tile, skipping quotes, dashes and whitespace. */
    fun initial(title: String): String =
        title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"

    private fun countOf(count: Int, noun: String): String =
        "$count $noun" + if (count == 1) "" else "s"
}
