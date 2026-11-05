package app.novelspeak.cast

import app.novelspeak.library.LibraryStorage

/**
 * Per-book cast persistence.
 *
 * The format is deliberately small and line-based so it runs on a plain JVM in host tests. One
 * document holds every book's cast; each store call rewrites only the records it owns.
 *
 * `v1`
 * `book<TAB>bookKey<TAB>narratorVoiceId<TAB>title`
 * `member<TAB>bookKey<TAB>id<TAB>name<TAB>gender<TAB>voiceId<TAB>userLocked<TAB>aliases<TAB>dialogueLines`
 */
class CastStore(private val storage: LibraryStorage) {

    @Synchronized
    fun load(bookKey: String): BookCast? {
        val key = clean(bookKey)
        if (key.isEmpty()) return null
        val document = read()
        val header = document.books[key] ?: return null
        val members = document.members[key].orEmpty()
        return BookCast(key, header.title, header.narratorVoiceId, members)
    }

    @Synchronized
    fun save(book: BookCast) {
        val key = clean(book.bookKey)
        if (key.isEmpty()) return
        val document = read()
        document.books[key] = Header(clean(book.title), clean(book.narratorVoiceId ?: ""))
        document.members[key] = book.members.toMutableList()
        write(document)
    }

    /**
     * Merge a chapter analysis into the stored cast. Existing ids, voices, user locks and aliases
     * win; new members and new aliases are appended.
     */
    @Synchronized
    fun merge(
        bookKey: String,
        title: String,
        analysis: CastAnalysis,
        narratorVoiceId: String? = null
    ): BookCast {
        val key = clean(bookKey)
        val existing = load(key)
        val merged = ArrayList<CastMember>()
        if (existing != null) merged.addAll(existing.members)

        for (incoming in analysis.members) {
            if (incoming.dialogueLines <= 0 && incoming.voiceId == null) continue
            val at = merged.indexOfFirst { it.id == incoming.id || it.matches(incoming.name) }
            if (at < 0) {
                merged.add(incoming)
                continue
            }
            val old = merged[at]
            merged[at] = old.copy(
                aliases = (old.aliases + incoming.aliases)
                    .distinctBy { CastIds.normalize(it) },
                gender = if (old.gender == Gender.UNKNOWN) incoming.gender else old.gender,
                voiceId = old.voiceId ?: incoming.voiceId,
                confidence = maxConfidence(old.confidence, incoming.confidence),
                userLocked = old.userLocked,
                // Re-analysing the same chapter must not inflate priority forever.
                dialogueLines = maxOf(old.dialogueLines, incoming.dialogueLines)
            )
        }

        val book = BookCast(
            bookKey = key,
            title = clean(title).ifEmpty { existing?.title ?: "" },
            narratorVoiceId = narratorVoiceId ?: existing?.narratorVoiceId,
            members = merged
        )
        save(book)
        return book
    }

    @Synchronized
    fun setVoice(bookKey: String, memberId: String, voiceId: String?): BookCast? {
        val book = load(bookKey) ?: return null
        val updated = book.members.map {
            if (it.id == memberId) it.copy(voiceId = voiceId, userLocked = true) else it
        }
        val next = book.copy(members = updated)
        save(next)
        return next
    }

    @Synchronized
    fun setNarratorVoice(bookKey: String, voiceId: String?): BookCast? {
        val book = load(bookKey) ?: return null
        val next = book.copy(narratorVoiceId = voiceId)
        save(next)
        return next
    }

    @Synchronized
    fun clear(bookKey: String? = null) {
        if (bookKey == null) {
            storage.write("v1\n")
            return
        }
        val key = clean(bookKey)
        val document = read()
        document.books.remove(key)
        document.members.remove(key)
        write(document)
    }

    // ------------------------------------------------------------------ document

    private data class Header(val title: String, val narratorVoiceId: String)

    private class Document(
        val books: MutableMap<String, Header> = LinkedHashMap(),
        val members: MutableMap<String, MutableList<CastMember>> = LinkedHashMap()
    )

    private fun read(): Document {
        val doc = Document()
        val raw = try {
            storage.read()
        } catch (t: Throwable) {
            null
        } ?: return doc
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            val f = line.split('\t')
            when {
                f.size >= 4 && f[0] == "book" -> {
                    val key = clean(f[1])
                    if (key.isNotEmpty()) {
                        doc.books[key] = Header(clean(f[3]), clean(f[2]))
                        doc.members.getOrPut(key) { ArrayList() }
                    }
                }
                f.size >= 8 && f[0] == "member" -> {
                    val key = clean(f[1])
                    if (key.isEmpty()) continue
                    val aliases = if (f[7].isEmpty()) emptyList()
                    else f[7].split('\u001f').map { clean(it) }.filter { it.isNotEmpty() }
                    val member = CastMember(
                        id = clean(f[2]),
                        name = clean(f[3]),
                        aliases = aliases,
                        gender = parseGender(f[4]),
                        voiceId = clean(f[5]).ifEmpty { null },
                        confidence = Confidence.LOW,
                        userLocked = f[6] == "1",
                        dialogueLines = if (f.size >= 9) f[8].toIntOrNull() ?: 0 else 0
                    )
                    doc.members.getOrPut(key) { ArrayList() }.add(member)
                }
            }
        }
        return doc
    }

    private fun write(document: Document) {
        val sb = StringBuilder("v1\n")
        for ((key, header) in document.books) {
            sb.append("book\t").append(clean(key)).append('\t')
                .append(clean(header.narratorVoiceId)).append('\t')
                .append(clean(header.title)).append('\n')
            for (m in document.members[key].orEmpty()) {
                sb.append("member\t").append(clean(key)).append('\t')
                    .append(clean(m.id)).append('\t')
                    .append(clean(m.name)).append('\t')
                    .append(m.gender.name.lowercase()).append('\t')
                    .append(clean(m.voiceId ?: "")).append('\t')
                    .append(if (m.userLocked) "1" else "0").append('\t')
                    .append(m.aliases.joinToString("\u001f") { clean(it) }).append('\t')
                    .append(m.dialogueLines)
                    .append('\n')
            }
        }
        try {
            storage.write(sb.toString())
        } catch (t: Throwable) {
            // Best-effort: losing a cast update is better than interrupting playback.
        }
    }

    private fun clean(value: String): String =
        value.replace('\t', ' ').replace('\n', ' ').replace('\u001f', ' ').trim()

    private fun parseGender(value: String): Gender = when (value.lowercase()) {
        "female" -> Gender.FEMALE
        "male" -> Gender.MALE
        else -> Gender.UNKNOWN
    }

    private fun maxConfidence(a: Confidence, b: Confidence): Confidence {
        val rank = mapOf(Confidence.LOW to 0, Confidence.MEDIUM to 1, Confidence.HIGH to 2)
        return if ((rank[a] ?: 0) >= (rank[b] ?: 0)) a else b
    }
}
