package app.novelspeak

/**
 * User pronunciation dictionary: one `from=to` replacement per line.
 *
 * The rules are applied to each sentence *before* it is handed to the TTS engine, so users can
 * fix names and acronyms that the neural voices mispronounce ("Shen Wei=Shen Way", "NS=N S").
 * The engine layer publishes the parsed table through `TtsOverrides.set(listOf(from to to))`;
 * [toPairs] / [fromPairs] are the bridge for that contract (see `TtsOverrides.kt`).
 *
 * Pure JVM, no Android imports — covered by the host harness `tools/feature-test/TestMain.kt`.
 *
 * Grammar (deliberately line-based so the Settings editor can validate one line at a time):
 *  - `from=to` — the first `=` splits source from replacement; `to` may contain further `=`.
 *  - blank lines and lines whose first non-space character is `#` are comments.
 *  - a line without `=`, or with an empty source, is ignored.
 *  - an empty replacement deletes the source (e.g. `~=` removes stray tildes).
 *  - literal strings, no regex and no escapes; control characters are rejected (the engine's
 *    `TtsOverrides` would refuse them, so [parse] drops the line instead).
 *  - matching is case-insensitive; later duplicates of the same source win.
 *  - at most [MAX_RULES] rules are honoured; longer lists are truncated.
 *
 * Applying: longest source first, single left-to-right pass (no cascades: `a=b` then `b=c`
 * turns "a" into "b", never "c"). Sources whose first/last character is a letter or digit
 * additionally require a word boundary, so `cat=dog` does not rewrite "scatter"; sources made
 * only of punctuation/spaces (e.g. `St.=Street`) match anywhere.
 */
object PronunciationRules {

    /** Hard cap on active rules, matching the Settings editor's limit. */
    const val MAX_RULES = 200

    /** A replacement may not be longer than this many chars (protects the TTS text path). */
    const val MAX_REPLACEMENT_CHARS = 200

    /** One parsed replacement. [from] is never empty; [to] may be empty (delete the source). */
    data class Rule(val from: String, val to: String)

    /**
     * Parses the config text. Never throws: malformed lines are skipped, not fatal.
     *
     * @return at most [MAX_RULES] rules, in file order with last-definition-wins de-duplication.
     */
    fun parse(text: String?): List<Rule> {
        if (text.isNullOrEmpty()) return emptyList()
        // Keyed by lowercase source: matching is case-insensitive, so "Cat" and "cat" are one rule.
        val deduped = LinkedHashMap<String, Rule>()
        var accepted = 0
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val from = line.substring(0, eq).trim()
            if (from.isEmpty()) continue
            val to = line.substring(eq + 1).trim()
            if (hasControlChars(from) || hasControlChars(to)) continue
            deduped[from.lowercase()] = Rule(from, to)
            accepted++
            if (accepted >= MAX_RULES) break
        }
        return deduped.values.toList()
    }

    /** Convenience: parse [config] and apply it to [text]. Never throws. */
    fun applyRules(text: String, config: String?): String = apply(text, parse(config))

    /**
     * Applies [rules] to [text]. Longest source wins, single pass, case-insensitive,
     * word-boundary aware. A replacement is truncated to [MAX_REPLACEMENT_CHARS].
     */
    fun apply(text: String, rules: List<Rule>): String {
        if (text.isEmpty() || rules.isEmpty()) return text
        val active = ArrayList<Rule>(rules.size)
        for (r in rules) {
            if (r.from.isEmpty()) continue
            active.add(r)
            if (active.size >= MAX_RULES) break
        }
        if (active.isEmpty()) return text
        // Longest first so "New York" beats "New"; the sort is stable, equal lengths keep file order.
        active.sortByDescending { it.from.length }

        val out = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            var matched: Rule? = null
            for (rule in active) {
                if (!text.regionMatches(i, rule.from, 0, rule.from.length, ignoreCase = true)) continue
                if (!hasBoundary(text, i, rule.from)) continue
                matched = rule
                break
            }
            if (matched != null) {
                out.append(matched.to.take(MAX_REPLACEMENT_CHARS))
                i += matched.from.length
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /**
     * Validates editor input and returns a human-readable message, or null when the text is
     * acceptable. [parse] never fails, so this only reports things a user would want to know.
     */
    fun validate(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val problems = ArrayList<String>()
        val valid = ArrayList<Rule>()
        val lines = text.split('\n')
        for ((index, rawLine) in lines.withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            val from = if (eq > 0) line.substring(0, eq).trim() else ""
            val to = if (eq >= 0) line.substring(eq + 1).trim() else ""
            when {
                eq < 0 -> problems.add("line ${index + 1}: expected from=to")
                from.isEmpty() -> problems.add("line ${index + 1}: source is empty")
                hasControlChars(from) || hasControlChars(to) ->
                    problems.add("line ${index + 1}: control characters are not allowed")
                else -> valid.add(Rule(from, to))
            }
            if (valid.size >= MAX_RULES) {
                if (lines.drop(index + 1).any { isRuleLine(it) }) {
                    problems.add("only the first $MAX_RULES rules are used")
                }
                break
            }
        }
        if (problems.isEmpty()) return null
        return problems.take(3).joinToString("; ")
    }

    /** Formats [rules] back to the one-rule-per-line format stored in Prefs. */
    fun serialize(rules: List<Rule>): String =
        rules.take(MAX_RULES).joinToString("\n") { "${it.from}=${it.to}" }

    /** Bridge to `TtsOverrides.set(...)`: (source, replacement) pairs, no Rule wrapper. */
    fun toPairs(rules: List<Rule>): List<Pair<String, String>> =
        rules.take(MAX_RULES).map { it.from to it.to }

    /** Bridge from `TtsOverrides` back to the Settings/preview representation. */
    fun fromPairs(pairs: List<Pair<String, String>>): List<Rule> =
        pairs.take(MAX_RULES).mapNotNull { (from, to) ->
            val f = from.trim()
            if (f.isEmpty()) null else Rule(f, to.trim())
        }

    private fun isRuleLine(line: String): Boolean {
        val t = line.trim()
        return t.isNotEmpty() && !t.startsWith("#") && t.indexOf('=') > 0
    }

    /** ASCII controls are not usable in spoken text; keep NUL/CR/LF out of the engine table. */
    private fun hasControlChars(value: String): Boolean {
        for (c in value) {
            if (c.code < 0x20 || c.code == 0x7F) return true
        }
        return false
    }

    /**
     * Word-boundary check: a source edge that is a letter/digit must not touch another
     * letter/digit in the text. Pure-punctuation sources are exempt (so "St." can match).
     */
    private fun hasBoundary(text: String, start: Int, from: String): Boolean {
        val end = start + from.length
        if (from[0].isLetterOrDigit() && start > 0 && text[start - 1].isLetterOrDigit()) {
            return false
        }
        if (from[from.length - 1].isLetterOrDigit() && end < text.length &&
            text[end].isLetterOrDigit()
        ) {
            return false
        }
        return true
    }
}
