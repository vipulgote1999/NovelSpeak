package app.novelspeak.cast

/**
 * Deterministic dialogue segmentation and speaker attribution.
 *
 * The parser works on the same sentence-level blocks the reader already queues. It keeps the
 * original substrings (quotes included) so read-along offsets stay valid; the TTS text cleaner
 * removes quote glyphs later.
 *
 * Evidence order:
 *  1. an explicit speech tag near the quote ("said Mrs. Bennet", "she replied");
 *  2. the last character mentioned in narration (handles "Mr. Bennet replied that he had not.");
 *  3. alternating between the last two speakers;
 *  4. UNKNOWN + LOW rather than a confident wrong voice.
 */
object DialogueParser {

    private val IGNORE = RegexOption.IGNORE_CASE

    private val VERB = Regex(
        "\\b(said|replied|returned|asked|cried|whispered|muttered|shouted|answered|continued|" +
            "murmured|snapped|growled|sighed|laughed|called|told|added|began|exclaimed|observed|" +
            "remarked|repeated|stammered|breathed|hissed|roared|yelled)\\b",
        IGNORE
    )

    // Longer titles first: "Mr" must not steal the prefix of "Mrs".
    private val TITLED = Regex(
        "\\b(Mrs|Miss|Ms|Mr|Dr|Sir|Lady|Lord|Captain|Professor|Marshal)\\.?\\s+([A-Z][a-z]+)"
    )

    private val CAP_NAME = Regex("\\b([A-Z][a-z]{2,})\\b")

    private val PRONOUN = Regex(
        "\\b(he|she|his wife|her husband|his lady|the woman|the man|the young man|the old man|" +
            "the marshal|a third voice|another voice|a voice|someone)\\b",
        IGNORE
    )

    private val UNKNOWN_VOICE = Regex(
        "\\b(a third voice|another voice|a voice from|a voice behind|someone else)\\b",
        IGNORE
    )

    /** Anonymous descriptions that usually become a named character a few lines later. */
    private val DESCRIPTION = Regex(
        "\\b(the woman in the grey coat|the woman|the young woman|the girl|the lady|" +
            "the young man|the old man|the man|the boy)\\b",
        IGNORE
    )

    /** Actions that make a capitalised word likely to be a character name. */
    private val ACTION = setOf(
        "looked", "said", "replied", "asked", "cried", "turned", "stepped", "walked", "ran",
        "stood", "sat", "rose", "moved", "made", "answered", "whispered", "muttered",
        "shouted", "laughed", "smiled", "nodded", "shook", "opened", "closed", "took", "put",
        "came", "went", "saw", "heard", "felt", "knew", "thought", "began", "continued",
        "stopped", "waited", "watched", "set", "reached", "glanced", "stared"
    )

    /** Words that are capitalised because they start a sentence, not because they are names. */
    private val COMMON = setOf(
        "the", "this", "that", "these", "those", "however", "when", "then", "from", "but", "and",
        "for", "with", "she", "he", "they", "it", "his", "her", "their", "there", "what", "why",
        "how", "you", "your", "our", "we", "they", "them", "was", "were", "has", "have", "had",
        "not", "chapter", "part", "book", "one", "two", "three", "oh", "yes", "no", "well",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "mr", "mrs", "ms", "miss", "dr", "sir", "lady", "lord", "captain", "professor",
        "marshal", "told", "said", "asked", "replied", "outside", "inside", "wait", "bring",
        "come", "came", "here", "there", "what", "where", "when", "why", "how", "who",
        "whose", "mine", "yours", "design", "nonsense", "single", "married", "name", "boy",
        "girl", "man", "woman", "voice", "third", "doorway", "hand", "holster", "lantern",
        "table", "stable", "yard", "body", "rain", "sleeves", "grey", "coat", "young", "old",
        "little", "big", "good", "bad", "right", "left", "chapter", "part", "book"
    )

    private val QUOTE_PAIRS = mapOf(
        '"' to '"',
        '“' to '”',
        '‘' to '’',
        '«' to '»',
        '「' to '」'
    )

    fun analyze(
        blocks: List<CastBlock>,
        existing: List<CastMember> = emptyList()
    ): CastAnalysis {
        val state = State(existing)
        // Seed the cast before segmenting so names mentioned later can still match earlier aliases.
        for (b in blocks) state.seedNames(b.text)

        val segments = ArrayList<SpeechSegment>(blocks.size)
        for (b in blocks) {
            val text = b.text
            if (text.isEmpty()) continue
            val spans = quoteSpans(text)
            if (spans.isEmpty()) {
                state.observeNarration(text)
                segments.add(
                    SpeechSegment(
                        text, b.blockIndex, 0, text.length,
                        SegmentType.NARRATION, CastIds.NARRATOR, Confidence.HIGH
                    )
                )
                continue
            }
            var cursor = 0
            for (span in spans) {
                if (span.start > cursor) {
                    val gap = text.substring(cursor, span.start)
                    state.observeNarration(gap)
                    if (gap.isNotBlank()) {
                        segments.add(
                            SpeechSegment(
                                gap, b.blockIndex, cursor, span.start,
                                SegmentType.NARRATION, CastIds.NARRATOR, Confidence.HIGH
                            )
                        )
                    }
                }
                val quote = text.substring(span.start, span.end)
                val resolved = state.resolveQuote(text, span)
                if (resolved.id == CastIds.UNKNOWN) state.unresolved++
                segments.add(
                    SpeechSegment(
                        quote, b.blockIndex, span.start, span.end,
                        SegmentType.DIALOGUE, resolved.id, resolved.confidence
                    )
                )
                cursor = span.end
            }
            if (cursor < text.length) {
                val gap = text.substring(cursor)
                state.observeNarration(gap)
                if (gap.isNotBlank()) {
                    segments.add(
                        SpeechSegment(
                            gap, b.blockIndex, cursor, text.length,
                            SegmentType.NARRATION, CastIds.NARRATOR, Confidence.HIGH
                        )
                    )
                }
            }
        }
        val counts = segments.filter { it.isDialogue }
            .groupingBy { it.speakerId }
            .eachCount()
        // A member's confidence is the best evidence any of their lines produced. Without this,
        // every member stayed LOW forever and the cast sheet asked the user to review all of them.
        val best = HashMap<String, Confidence>()
        for (segment in segments) {
            if (!segment.isDialogue) continue
            best[segment.speakerId] = maxConfidence(
                best[segment.speakerId] ?: Confidence.LOW,
                segment.confidence
            )
        }
        val samples = HashMap<String, String>()
        for (segment in segments) {
            if (!segment.isDialogue) continue
            val text = segment.text.trim()
            if (text.length < 4) continue
            val previous = samples[segment.speakerId]
            if (previous == null || text.length > previous.length) {
                samples[segment.speakerId] = previewLine(text)
            }
        }
        val rawMembers = state.members().map {
            it.copy(
                dialogueLines = counts[it.id] ?: 0,
                confidence = best[it.id] ?: it.confidence,
                sampleLine = samples[it.id] ?: it.sampleLine
            )
        }
        val merged = mergeDescriptions(segments, rawMembers)
        return CastAnalysis(merged.first, merged.second, state.unresolved)
    }

    /**
     * A short, complete-sounding line for a voice audition. Long paragraphs are cut at a word
     * boundary rather than mid-word, and get terminal punctuation so the model sees EOS.
     */
    fun previewLine(text: String, maxChars: Int = 160): String {
        val clean = text.trim()
        if (clean.length <= maxChars) return clean
        val cut = clean.take(maxChars).substringBeforeLast(' ').trimEnd()
            .trimEnd(',', ';', ':', '-')
        if (cut.isEmpty()) return clean.take(maxChars)
        return cut + "."
    }

    private fun maxConfidence(a: Confidence, b: Confidence): Confidence {
        val rank = mapOf(Confidence.LOW to 0, Confidence.MEDIUM to 1, Confidence.HIGH to 2)
        return if ((rank[a] ?: 0) >= (rank[b] ?: 0)) a else b
    }

    // ------------------------------------------------------------------ quotes

    private data class Span(val start: Int, val end: Int)

    /** Returns [start, end) spans that include the opening and closing quote glyphs. */
    private fun quoteSpans(text: String): List<Span> {
        val out = ArrayList<Span>()
        var i = 0
        while (i < text.length) {
            val open = text[i]
            val close = QUOTE_PAIRS[open] ?: run { i++; continue }
            val end = text.indexOf(close, i + 1)
            if (end <= i) { i++; continue }
            out.add(Span(i, end + 1))
            i = end + 1
        }
        return out
    }

    // ------------------------------------------------------------------ descriptions

    private fun descriptionGender(text: String): Gender {
        val t = text.lowercase()
        return when {
            t.contains("woman") || t.contains("girl") || t.contains("lady") -> Gender.FEMALE
            t.contains("man") || t.contains("boy") -> Gender.MALE
            else -> Gender.UNKNOWN
        }
    }

    private fun isDescriptive(name: String): Boolean {
        val n = name.lowercase()
        return (n.startsWith("the ") || n.startsWith("a ")) &&
            listOf("woman", "man", "girl", "boy", "voice", "lady").any { n.contains(it) }
    }

    /**
     * Merge a provisional "the woman in the grey coat" member into the named character it turns
     * out to be. Conservative: only merge into a candidate with no more dialogue than the
     * description itself, so a minor description cannot steal the protagonist's identity.
     */
    private fun mergeDescriptions(
        segments: List<SpeechSegment>,
        members: List<CastMember>
    ): Pair<List<SpeechSegment>, List<CastMember>> {
        val descriptive = members.filter { it.dialogueLines > 0 && isDescriptive(it.name) }
        if (descriptive.isEmpty()) return segments to members
        val byId = members.associateBy { it.id }.toMutableMap()
        val replacements = HashMap<String, String>()
        for (d in descriptive) {
            val sameGender = byId.values.filter {
                it.id != d.id && !isDescriptive(it.name) && it.gender == d.gender &&
                    it.dialogueLines <= d.dialogueLines
            }
            val unknownGender = byId.values.filter {
                it.id != d.id && !isDescriptive(it.name) && it.gender == Gender.UNKNOWN &&
                    it.dialogueLines in 1..d.dialogueLines
            }
            val target = (sameGender + unknownGender).minByOrNull { it.dialogueLines } ?: continue
            replacements[d.id] = target.id
            byId[target.id] = target.copy(
                aliases = (target.aliases + d.name + d.aliases)
                    .distinctBy { CastIds.normalize(it) },
                gender = if (target.gender == Gender.UNKNOWN) d.gender else target.gender,
                dialogueLines = target.dialogueLines + d.dialogueLines,
                confidence = maxConfidence(target.confidence, d.confidence),
                sampleLine = if (d.sampleLine.length > target.sampleLine.length) {
                    d.sampleLine
                } else {
                    target.sampleLine
                }
            )
            byId.remove(d.id)
        }
        if (replacements.isEmpty()) return segments to members
        val mergedSegments = segments.map { s ->
            replacements[s.speakerId]?.let { s.copy(speakerId = it) } ?: s
        }
        return mergedSegments to byId.values.toList()
    }

    // ------------------------------------------------------------------ state

    private data class Resolution(val id: String, val confidence: Confidence)

    private data class TagCandidate(val text: String, val pronoun: Boolean)

    private class State(existing: List<CastMember>) {
        private val byId = LinkedHashMap<String, CastMember>()
        private var lastSpeaker: String? = null
        private var secondLastSpeaker: String? = null
        /** The most recent character mentioned/active who is not the current speaker. */
        private var lastOtherMentioned: String? = null
        private var lastMentioned: String? = null
        private var lastAddressed: String? = null
        private var lastFemaleSpeaker: String? = null
        private var lastMaleSpeaker: String? = null
        private var lastFemaleMentioned: String? = null
        private var lastMaleMentioned: String? = null
        private var unknownNext = false
        var unresolved = 0

        init {
            for (m in existing) byId[m.id] = m
        }

        fun members(): List<CastMember> = byId.values.toList()

        fun noteSpeaker(id: String) {
            if (id == CastIds.NARRATOR || id == CastIds.UNKNOWN) return
            if (lastSpeaker != null && lastSpeaker != id) {
                secondLastSpeaker = lastSpeaker
                lastOtherMentioned = lastSpeaker
            }
            lastSpeaker = id
            lastMentioned = id
            when (byId[id]?.gender) {
                Gender.FEMALE -> lastFemaleSpeaker = id
                Gender.MALE -> lastMaleSpeaker = id
                else -> {}
            }
        }

        fun resolveQuote(full: String, span: Span): Resolution {
            val resolution = resolveQuoteInner(full, span)
            noteAddressed(full.substring(span.start, span.end), resolution.id)
            return resolution
        }

        private fun resolveQuoteInner(full: String, span: Span): Resolution {
            val before = full.substring(kotlin.math.max(0, span.start - 120), span.start)
            val after = full.substring(span.end, kotlin.math.min(full.length, span.end + 120))

            if (unknownNext) {
                unknownNext = false
                return Resolution(CastIds.UNKNOWN, Confidence.LOW)
            }

            // A tag after the quote is the strongest signal; fall back to one before it.
            val tag = findTag(after) ?: findTag(before)
            if (tag != null) {
                val member = resolveCandidate(tag.text)
                if (member != null) {
                    noteSpeaker(member.id)
                    return Resolution(
                        member.id,
                        if (tag.pronoun) Confidence.MEDIUM else Confidence.HIGH
                    )
                }
                if (isUnknownVoice(tag.text)) {
                    return Resolution(CastIds.UNKNOWN, Confidence.LOW)
                }
            }

            if (UNKNOWN_VOICE.containsMatchIn(before) || UNKNOWN_VOICE.containsMatchIn(after)) {
                return Resolution(CastIds.UNKNOWN, Confidence.LOW)
            }

            val mentioned = lastMentioned
            if (mentioned != null && mentioned != lastSpeaker) {
                noteSpeaker(mentioned)
                return Resolution(mentioned, Confidence.MEDIUM)
            }

            val alternate = alternate()
            if (alternate != null) {
                noteSpeaker(alternate)
                return Resolution(alternate, Confidence.MEDIUM)
            }

            val last = lastSpeaker
            if (last != null) {
                noteSpeaker(last)
                return Resolution(last, Confidence.MEDIUM)
            }

            return Resolution(CastIds.UNKNOWN, Confidence.LOW)
        }

        private fun alternate(): String? {
            val last = lastSpeaker ?: return null
            val other = lastOtherMentioned
            if (other != null && other != last) return other
            val second = secondLastSpeaker
            return if (second != null && second != last) second else last
        }

        /** Update lastMentioned from prose; also arm the "unknown voice" rule. */
        fun observeNarration(text: String) {
            if (text.isBlank()) return
            if (UNKNOWN_VOICE.containsMatchIn(text)) unknownNext = true

            var bestScore = -1
            var best: CastMember? = null
            for (m in byId.values) {
                val needles = listOf(m.name) + m.aliases
                for (n in needles) {
                    val at = text.indexOf(n, ignoreCase = true)
                    if (at < 0) continue
                    // Prefer the longer phrase at the same/nearby position, so "the marshal"
                    // beats a bare "Marshal" and "Mrs. Bennet" beats "Bennet".
                    val score = n.length * 1000 - at
                    if (score > bestScore) {
                        bestScore = score
                        best = m
                    }
                }
            }
            if (best != null) {
                lastMentioned = best.id
                if (best.id != lastSpeaker) lastOtherMentioned = best.id
                when (best.gender) {
                    Gender.FEMALE -> lastFemaleMentioned = best.id
                    Gender.MALE -> lastMaleMentioned = best.id
                    else -> {}
                }
                return
            }

            // An anonymous description ("the woman in the grey coat", "the young man") can be
            // linked to a name that dialogue just addressed, or kept as a provisional member.
            val description = DESCRIPTION.find(text)?.value
            if (description != null) {
                val gender = descriptionGender(description)
                val addressed = lastAddressed?.let { byId[it] }
                    ?.takeIf { it.gender == gender || it.gender == Gender.UNKNOWN }
                if (addressed != null) {
                    val updated = addAlias(addressed, description, gender)
                    lastMentioned = updated.id
                    lastAddressed = null
                    return
                }
                val existing = byId.values.firstOrNull { it.matches(description) }
                if (existing != null) {
                    lastMentioned = existing.id
                    return
                }
                val created = findOrCreate(description, gender)
                if (created != null) {
                    val updated = addAlias(created, description, gender)
                    lastMentioned = updated.id
                    return
                }
            }

            // "his wife replied" / "she said" without a name in a pure narration block.
            val tag = findTag(text)
            if (tag != null) {
                resolveCandidate(tag.text)?.let { lastMentioned = it.id }
            }
        }

        /** Names inside a quote are usually the person being addressed. */
        private fun noteAddressed(quote: String, speakerId: String) {
            val names = ArrayList<String>()
            TITLED.findAll(quote).forEach { names.add(it.value) }
            CAP_NAME.findAll(quote).forEach { m ->
                if (m.value.lowercase() !in COMMON) names.add(m.value)
            }
            for (name in names) {
                val member = findOrCreate(name) ?: continue
                if (member.id != speakerId) lastAddressed = member.id
            }
        }

        private fun addAlias(member: CastMember, alias: String, gender: Gender): CastMember {
            val aliases = (member.aliases + alias).distinctBy { CastIds.normalize(it) }
            val updated = member.copy(
                aliases = aliases,
                gender = if (member.gender == Gender.UNKNOWN) gender else member.gender
            )
            byId[updated.id] = updated
            return updated
        }

        /** Add titled names and capitalised words that are followed by a character action. */
        fun seedNames(text: String) {
            for (m in TITLED.findAll(text)) {
                findOrCreate(m.value)
            }
            for (m in CAP_NAME.findAll(text)) {
                val word = m.value
                if (word.lowercase() in COMMON) continue
                val after = text.substring(m.range.last + 1).trimStart()
                val next = after.takeWhile { it.isLetter() }.lowercase()
                if (next in ACTION) findOrCreate(word)
            }
        }

        fun findOrCreate(raw: String, genderHint: Gender = Gender.UNKNOWN): CastMember? {
            val clean = raw.trim().trim('.', ',', ';', ':', '!', '?', '"', '“', '”', '‘', '’')
            if (clean.isEmpty()) return null
            val norm = CastIds.normalize(clean)
            if (norm.isEmpty() || norm in COMMON) return null

            byId.values.firstOrNull { it.matches(clean) }?.let { return it }

            val stripped = clean.replace(
                Regex("^(Mr|Mrs|Ms|Miss|Dr|Sir|Lady|Lord|Captain|Professor|Marshal)\\.?\\s+", IGNORE),
                ""
            )
            if (stripped != clean) {
                byId.values.firstOrNull {
                    CastIds.normalize(it.name) == CastIds.normalize(stripped)
                }?.let { return it }
            }

            val gender = if (genderHint != Gender.UNKNOWN) genderHint else genderFromTitle(clean)
            val base = CastIds.slug(clean)
            var id = base
            var n = 2
            while (byId.containsKey(id)) {
                val existing = byId[id]
                if (existing != null && existing.matches(clean)) return existing
                id = "$base-$n"
                n++
            }
            val aliases = ArrayList<String>()
            aliases.add(clean)
            if (stripped != clean) aliases.add(stripped)
            titleAlias(clean)?.let { aliases.add(it) }
            val member = CastMember(
                id = id,
                name = clean,
                aliases = aliases.distinctBy { CastIds.normalize(it) },
                gender = gender,
                confidence = Confidence.LOW
            )
            byId[id] = member
            return member
        }

        private fun resolveCandidate(candidate: String): CastMember? {
            val c = candidate.trim()
            if (c.isEmpty()) return null
            if (isUnknownVoice(c)) return null
            val lower = c.lowercase()

            if (lower.contains("his wife") || lower.contains("his lady")) {
                return spouse(Gender.FEMALE)
            }
            if (lower.contains("her husband")) {
                return spouse(Gender.MALE)
            }
            if (lower == "he" || lower == "him" || lower == "his") {
                return recent(Gender.MALE)
            }
            if (lower == "she" || lower == "her") {
                return recent(Gender.FEMALE)
            }
            if (lower.contains("the woman")) {
                return recent(Gender.FEMALE)
            }
            if (lower.contains("the young man") || lower.contains("the old man") ||
                lower == "the man"
            ) {
                return recent(Gender.MALE)
            }
            if (lower.contains("the marshal")) {
                return byId.values.firstOrNull { it.matches("the marshal") }
                    ?: recent(Gender.MALE)
            }
            return findOrCreate(c)
        }

        private fun recent(gender: Gender): CastMember? {
            lastMentioned?.let { id -> byId[id]?.takeIf { it.gender == gender }?.let { return it } }
            lastSpeaker?.let { id -> byId[id]?.takeIf { it.gender == gender }?.let { return it } }
            val specific = if (gender == Gender.FEMALE) lastFemaleSpeaker else lastMaleSpeaker
            specific?.let { id -> byId[id]?.let { return it } }
            val mentioned = if (gender == Gender.FEMALE) lastFemaleMentioned else lastMaleMentioned
            mentioned?.let { id -> byId[id]?.let { return it } }
            return byId.values.lastOrNull { it.gender == gender }
        }

        private fun spouse(gender: Gender): CastMember? {
            val last = lastSpeaker?.let { byId[it] }
            // "his wife" after a male has just spoken: match that speaker's surname.
            if (last != null && last.gender != gender && last.gender != Gender.UNKNOWN) {
                val surname = last.name.substringAfterLast(' ').lowercase()
                byId.values.firstOrNull {
                    it.gender == gender && it.name.substringAfterLast(' ').lowercase() == surname
                }?.let { return it }
            }
            // First mention with no prior speaker: find the first male/female pair sharing a surname.
            for (m in byId.values) {
                if (m.gender == gender || m.gender == Gender.UNKNOWN) continue
                val surname = m.name.substringAfterLast(' ').lowercase()
                byId.values.firstOrNull {
                    it.gender == gender && it.name.substringAfterLast(' ').lowercase() == surname
                }?.let { return it }
            }
            // The character has only been introduced by relation ("his lady", "his wife").
            // Mint the spouse from the first known counterpart, as a novel reader would.
            val counterpart = byId.values.firstOrNull {
                it.gender != gender && it.gender != Gender.UNKNOWN
            }
            if (counterpart != null) {
                return createSpouse(counterpart, gender)
            }
            if (last != null && last.gender == gender) return last
            return recent(gender)
        }

        private fun createSpouse(counterpart: CastMember, gender: Gender): CastMember? {
            val surname = counterpart.name.substringAfterLast(' ').trim()
            if (surname.isEmpty()) return null
            val title = if (gender == Gender.FEMALE) "Mrs." else "Mr."
            val created = findOrCreate("$title $surname", gender) ?: return null
            val extra = if (gender == Gender.FEMALE) {
                listOf("his wife", "his lady")
            } else {
                listOf("her husband")
            }
            val aliases = (created.aliases + extra).distinctBy { CastIds.normalize(it) }
            val updated = created.copy(aliases = aliases)
            byId[updated.id] = updated
            return updated
        }

        private fun isUnknownVoice(text: String): Boolean {
            val t = text.trim().lowercase()
            return t == "a voice" || t == "a third voice" || t == "another voice" ||
                t == "someone" || UNKNOWN_VOICE.containsMatchIn(text)
        }

        private fun findTag(window: String): TagCandidate? {
            val verbs = VERB.findAll(window).toList()
            if (verbs.isEmpty()) return null
            var best: TagCandidate? = null
            var bestDistance = Int.MAX_VALUE
            for (verb in verbs) {
                val from = kotlin.math.max(0, verb.range.first - 48)
                val to = kotlin.math.min(window.length, verb.range.last + 49)
                val region = window.substring(from, to)
                val candidates = ArrayList<TagCandidate>()
                for (m in TITLED.findAll(region)) {
                    candidates.add(TagCandidate(m.value, false))
                }
                for (m in PRONOUN.findAll(region)) {
                    candidates.add(TagCandidate(m.value, true))
                }
                for (m in CAP_NAME.findAll(region)) {
                    val w = m.value
                    if (w.lowercase() in COMMON) continue
                    if (w == verb.value) continue
                    candidates.add(TagCandidate(w, false))
                }
                for (c in candidates) {
                    val at = region.indexOf(c.text)
                    val distance = kotlin.math.abs(at - (verb.range.first - from))
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = c
                    }
                }
            }
            return best
        }

        private fun genderFromTitle(name: String): Gender = when {
            // Female titles first: "Mrs" starts with "Mr".
            name.startsWith("Mrs", true) || name.startsWith("Ms", true) ||
                name.startsWith("Miss", true) || name.startsWith("Lady", true) -> Gender.FEMALE
            name.startsWith("Mr", true) || name.startsWith("Sir", true) ||
                name.startsWith("Lord", true) || name.startsWith("Marshal", true) -> Gender.MALE
            else -> Gender.UNKNOWN
        }

        private fun titleAlias(name: String): String? = when {
            name.startsWith("Marshal", true) -> "the marshal"
            name.startsWith("Captain", true) -> "the captain"
            name.startsWith("Professor", true) -> "the professor"
            name.startsWith("Dr", true) -> "the doctor"
            name.startsWith("Lord", true) -> "the lord"
            name.startsWith("Lady", true) -> "the lady"
            else -> null
        }
    }
}
