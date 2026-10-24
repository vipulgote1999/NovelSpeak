package app.novelspeak

import java.text.Normalizer

/**
 * Neural-TTS text sanitizer for Kokoro (espeak-ng/misaki frontend) and Pocket-TTS
 * (SentencePiece + autoregressive flow LM).
 *
 * ## Why (external evidence, 2026)
 *
 * - hexgrad/kokoro#365: "Punctuation tokens book real duration and fill it with
 *   audible signal… a breathy/static tail glued to every exclamation or
 *   quoted-dialogue boundary… recurs at nearly every dialogue boundary (.\" and
 *   !\" endings)". Quote glyphs are *tokens with booked frames*, i.e. audible junk.
 * - hexgrad/kokoro#365 finding 2: the leading quote token also carries the next
 *   word's onset. Post-hoc gating of those frames produces clicks/holes — so the
 *   right fix is to remove quote glyphs from the TEXT before synthesis, never to
 *   trim them out of the audio afterwards.
 * - hexgrad/kokoro#325: hard 510-phoneme limit; manual sentence chunking is the
 *   supported workaround (we already chunk <=180 chars).
 * - k2-fsa/sherpa-onnx#3176: Pocket-TTS produces "inaudible and weird sounds" on
 *   very short inputs ("Hello World"), while long text works — upstream reproduces
 *   it without sherpa. Short fragments should be merged, not synthesized alone.
 * - TTS vendor guidance (Sestek, ElevenLabs): prefer punctuation that supports
 *   prosody over display-style quoting; remove unnecessary quotation marks; too
 *   many pause tags cause instability/noise.
 *
 * ## What it does
 *
 * Only presentation is normalized; words, casing, contractions, numbers and
 * sentence prosody (`. , ! ? ; :`) are preserved. Nothing is reworded, so audio
 * quality is not traded away:
 *
 *   1. NFKC + strip control/format/zero-width characters
 *   2. unify “ ” ‘ ’ „ « » and backticks
 *   3. HTML entities (rare extraction leftovers)
 *   4. residual dotted obfuscation ("ma.s.s" -> "mass"), acronyms/decimals kept
 *   5. delete glue characters with no spoken form (* _ ~ ^ |)
 *   6. quote glyphs are removed entirely (kokoro#365); apostrophes survive only
 *      inside words so contractions stay natural
 *   7. ellipsis and em/en dashes become a comma pause — the audiobook convention —
 *      instead of a hard stop or a spoken "dash"
 *   8. shout runs ("!!!", "?!") collapse to one mark; `--` becomes a pause
 *   9. outer shells/punctuation are trimmed
 *  10. punctuation-only input returns null: the caller must SKIP it
 *  11. terminal punctuation is guaranteed so the model sees EOS
 *
 * Pure Kotlin, no Android imports — runnable in the host JVM harness
 * (tools/tts-cleaner-test).
 */
object TtsTextCleaner {

    private val DOTTED_WORD = Regex("""\b[A-Za-z]+(?:\.[A-Za-z]+){2,}\b""")
    /** Single-letter runs with . - or + separators: "s.h.i.+ning" -> "shining". */
    private val DOTTED_LETTERS = Regex("""\b[A-Za-z][A-Za-z]*(?:[.\-+]+[A-Za-z]+){2,}\b""")
    private val MULTI_SPACE = Regex("""[ \t\u00a0\u2000-\u200b\u202f\u205f\u3000]+""")
    private val SHOUT_RUN = Regex("""([!?])[!?]+""")
    private val ELLIPSIS = Regex("""(?:\u2026|\.{2,})""")
    private val ENTITY_NUM = Regex("""&#\d+;|&#x[0-9a-fA-F]+;""")
    /** Quote/dash removal can leave a space before punctuation ("say , never"). */
    private val SPACE_BEFORE_PUNCT = Regex("""[ \t]+([,.;:!?])""")
    /** Terminal marks that already signal EOS; ':' deliberately excluded (weak stop). */
    private val TERMINAL = charArrayOf('.', '!', '?', ';')

    /**
     * Clean [raw] for neural synthesis.
     * @return speakable text, or null when the sentence must be skipped.
     */
    fun cleanForNeural(raw: String): String? {
        if (raw.isBlank()) return null
        var s = raw

        // 1. Compatibility decomposition + strip invisible/control characters.
        try {
            s = Normalizer.normalize(s, Normalizer.Form.NFKC)
        } catch (e: Throwable) {
            // very old runtimes; continue with the raw string
        }
        val sb0 = StringBuilder(s.length)
        for (c in s) {
            when {
                c == '\uFEFF' || c == '\u200B' || c == '\u200C' || c == '\u200D' -> { /* drop */ }
                c == '\n' || c == '\t' -> sb0.append(' ')
                c.code < 0x20 -> sb0.append(' ')
                c.category == CharCategory.CONTROL -> sb0.append(' ')
                c.category == CharCategory.FORMAT -> { /* drop */ }
                else -> sb0.append(c)
            }
        }
        s = sb0.toString()

        // 2. Quote/dash unify. Quotes are normalized to ASCII here and removed at
        // step 6; em/en dashes become commas (a pause), never a spoken "dash".
        s = s.replace('“', '"').replace('”', '"').replace('„', '"')
            .replace('«', '"').replace('»', '"')
            .replace('‘', '\'').replace('’', '\'').replace('‚', '\'')
            .replace('‹', '\'').replace('›', '\'').replace('`', '\'')
            .replace("\u00ad", "") // soft hyphen
            .replace('\u00a0', ' ')

        // 3. HTML entities that occasionally survive extraction.
        s = s.replace("&amp;", " and ")
            .replace("&nbsp;", " ").replace("&quot;", " ")
            .replace("&lt;", " ").replace("&gt;", " ")
            .replace("&#39;", "'")
        s = ENTITY_NUM.replace(s, " ")

        // 4. Residual dotted obfuscation (defense-in-depth: extract.js handles most).
        s = DOTTED_LETTERS.replace(s) { m ->
            val w = m.value
            if (w == w.uppercase()) w else w.replace(Regex("""[.\-+]"""), "")
        }
        s = DOTTED_WORD.replace(s) { m ->
            val w = m.value
            if (w == w.uppercase()) w else w.replace(".", "")
        }

        // 5. Glue characters with no spoken form: delete, so "sh*t" -> "sht"
        // (pronounceable) rather than "sh t" (two fragments).
        for (c in charArrayOf('*', '_', '~', '^', '|')) {
            if (s.indexOf(c) >= 0) s = s.replace(c.toString(), "")
        }

        // 6. Ellipsis / dash runs -> comma pause (audiobook convention).
        s = ELLIPSIS.replace(s, ", ")
        s = s.replace('—', ',').replace('–', ',').replace('―', ',')
        s = s.replace("--", ", ")

        // 7. Character allow-list. Quote glyphs are dropped (kokoro#365: they book
        // audible frames); apostrophes survive only inside a word (don't, it's).
        val sb = StringBuilder(s.length)
        for ((i, c) in s.withIndex()) {
            when {
                c.isLetterOrDigit() -> sb.append(c)
                c == ' ' -> sb.append(' ')
                c == '\'' -> {
                    val prev = s.getOrNull(i - 1)
                    val next = s.getOrNull(i + 1)
                    if (prev != null && next != null && prev.isLetter() && next.isLetter()) {
                        sb.append('\'')
                    } else {
                        sb.append(' ')
                    }
                }
                c == '-' -> {
                    val prev = s.getOrNull(i - 1)
                    val next = s.getOrNull(i + 1)
                    // keep compounds ("Tier-3", "Ahh-wooo"); a standalone dash is a pause
                    if (prev != null && next != null && prev.isLetterOrDigit() && next.isLetterOrDigit()) {
                        sb.append('-')
                    } else {
                        sb.append(' ')
                    }
                }
                c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':' -> sb.append(c)
                c == '&' -> sb.append(" and ")
                else -> sb.append(' ') // emoji, @ # $ % / \ + = < > { } [ ] ( ), bullets, quotes
            }
        }
        s = sb.toString()

        // 8. Collapse whitespace and shout runs; no repeated terminal marks.
        s = MULTI_SPACE.replace(s, " ").trim()
        if (s.isEmpty()) return null
        s = SHOUT_RUN.replace(s, "$1")
        s = SPACE_BEFORE_PUNCT.replace(s, "$1")
        s = MULTI_SPACE.replace(s, " ").trim()

        // 9. Trim outer punctuation/shells left by the splitter
        // ('"What was that?' / 'That thing...!"' / '- Tom!').
        s = stripShell(s)
        if (s.isEmpty()) return null

        // 10. Punct-only guard: "...", '"', "*", "—" have no phonemes and make the
        // model hallucinate. Caller skips these (advance, no audio).
        if (s.none { it.isLetterOrDigit() }) return null

        // 11. Guarantee terminal punctuation so the model sees EOS. Replace a weak
        // trailing comma/colon instead of producing "…:." .
        val last = s.last()
        if (last !in TERMINAL) {
            s = if (last == ',' || last == ':' || last == '-') {
                s.dropLast(1).trimEnd() + "."
            } else {
                s + "."
            }
        }

        return s
    }

    private fun stripShell(s0: String): String {
        var s = s0.trim()
        var changed = true
        while (changed && s.isNotEmpty()) {
            changed = false
            // leading punctuation: quotes are already gone; strip commas, colons,
            // list dashes and stray separators left by dialogue markers
            while (s.isNotEmpty() && (s[0] == ',' || s[0] == ';' || s[0] == ':' ||
                        s[0] == '.' || s[0] == '-')
            ) {
                s = s.substring(1).trimStart()
                changed = true
            }
            // trailing separators never carry meaning; terminal . ! ? are preserved
            while (s.isNotEmpty() && (s.last() == ',' || s.last() == ':' ||
                        s.last() == '-' || (s.last() == ';' && s.length > 1))
            ) {
                s = s.substring(0, s.length - 1).trimEnd()
                changed = true
            }
        }
        return s.trim()
    }
}
