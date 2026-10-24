package app.novelspeak

/**
 * Process-wide pronunciation replacement table, shared between the settings layer and the
 * TTS text path.
 *
 * ## Why this object exists
 *
 * `Prefs.pronunciationRules` stores the user's dictionary as raw editor text and
 * `PronunciationRules` owns parsing/validation for the Settings UI. The neural text path must
 * not depend on the settings parser (or touch SharedPreferences on the generation thread), so the
 * app publishes the parsed table here once and [TtsTextCleaner] reads it for every sentence:
 *
 * ```
 * TtsOverrides.set(PronunciationRules.toPairs(PronunciationRules.parse(prefs.pronunciationRules)))
 * ```
 *
 * and re-publishes it whenever the user saves the dictionary.
 *
 * ## Contract
 *
 *  - Sources and replacements are **literal strings**: no regex, no escapes, no `$1` captures.
 *    A rule source containing regex metacharacters (`.`, `+`, `[`, `*` ...) matches those
 *    characters literally.
 *  - Matching is **case-insensitive**; a source whose first/last character is a letter or digit
 *    additionally requires a word boundary (so `cat=dog` does not rewrite "scatter"), while
 *    punctuation-only sources match anywhere.
 *  - **Longest source first**, single left-to-right pass (no cascades: `a=b` then `b=c`
 *    turns "a" into "b", never "c").
 *  - An **empty replacement deletes** the source.
 *  - Rules with blank sources or control characters are rejected; the table is capped at
 *    [MAX_RULES] entries and a replacement at [MAX_REPLACEMENT_CHARS] characters.
 *
 * ## Threading
 *
 * The table is an immutable list published through a `@Volatile` reference. Readers always see a
 * consistent snapshot and never block; writers only ever replace the reference. A rule change is
 * picked up by the next sentence, which is exactly the "hear the difference immediately"
 * behaviour the Settings preview needs.
 *
 * Pure Kotlin, no Android imports — covered by the host harness `tools/tts-cleaner-test`.
 */
object TtsOverrides {

    /** Hard cap on active rules (matches `PronunciationRules.MAX_RULES` and the Settings editor). */
    const val MAX_RULES = 200

    /** A replacement is truncated to this many chars (protects the TTS text path). */
    const val MAX_REPLACEMENT_CHARS = 200

    /** A source longer than this can never match a <=180-char sentence chunk; drop it defensively. */
    private const val MAX_SOURCE_CHARS = 200

    /**
     * Current pronunciation rules as (source, replacement) pairs, longest-first ordering is NOT
     * required here — [TtsTextCleaner] sorts before applying. Always a validated, immutable
     * snapshot; empty by default.
     */
    @Volatile
    var pronunciationRules: List<Pair<String, String>> = emptyList()
        private set

    /**
     * Publishes a validated, immutable snapshot of [rules]. Never throws: malformed entries are
     * skipped, not fatal. Safe to call from the main thread at app start and from Settings on
     * every save.
     */
    fun set(rules: List<Pair<String, String>>) {
        pronunciationRules = sanitized(rules)
    }

    /** Drops every rule (e.g. user clears the dictionary). */
    fun clear() = set(emptyList())

    /**
     * Validates and caps [rules] without publishing them. Exposed for [TtsTextCleaner] so a rule
     * list passed directly to the cleaner gets the same safety guarantees as the published table.
     */
    internal fun sanitized(rules: List<Pair<String, String>>): List<Pair<String, String>> {
        if (rules.isEmpty()) return emptyList()
        val out = ArrayList<Pair<String, String>>(minOf(rules.size, MAX_RULES))
        for ((rawFrom, rawTo) in rules) {
            val from = rawFrom.trim()
            if (from.isEmpty() || from.length > MAX_SOURCE_CHARS) continue
            if (hasControlChars(from)) continue
            var to = rawTo.trim()
            if (to.length > MAX_REPLACEMENT_CHARS) {
                to = to.take(MAX_REPLACEMENT_CHARS)
                // Never leave a dangling high surrogate where the cap fell.
                if (to.isNotEmpty() && to.last().isHighSurrogate()) to = to.dropLast(1)
            }
            if (hasControlChars(to)) continue
            out.add(from to to)
            if (out.size >= MAX_RULES) break
        }
        return out
    }

    /** ASCII/Unicode control + format characters are not usable in spoken text. */
    private fun hasControlChars(value: String): Boolean {
        for (c in value) {
            if (c.code < 0x20 || c.code == 0x7F) return true
            if (c.category == CharCategory.CONTROL || c.category == CharCategory.FORMAT) return true
        }
        return false
    }
}
