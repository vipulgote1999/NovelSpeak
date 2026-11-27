package app.novelspeak.manga

/**
 * Provider detection and URL building that does not need Android or the network.
 *
 * Most sites are handled by the generic DOM/payload extractor. MangaDex needs a small API
 * adapter because its chapter pages sit behind a browser-verification challenge while the
 * public at-home API still returns the page list.
 */
object MangaProvider {

    enum class Kind { GENERIC, MANGADEX, WEBTOONS, TAPAS }

    private val MANGA_DEX_CHAPTER =
        Regex("""mangadex\.org/chapter/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})""")
    private val WEBTOONS_VIEWER =
        Regex("""^https?://(?:www\.|m\.)?webtoons\.com/.*/viewer(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
    private val TAPAS_EPISODE =
        Regex("""^https?://(?:www\.|m\.)?tapas\.io/episode/\d+(?:[?#].*)?$""", RegexOption.IGNORE_CASE)

    fun detect(url: String?): Kind = when {
        mangaDexChapterId(url) != null -> Kind.MANGADEX
        url != null && WEBTOONS_VIEWER.matches(url) -> Kind.WEBTOONS
        url != null && TAPAS_EPISODE.matches(url) -> Kind.TAPAS
        else -> Kind.GENERIC
    }

    /** Extracts the chapter UUID from a MangaDex reader URL, ignoring query/fragment. */
    fun mangaDexChapterId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val clean = url.substringBefore('#').substringBefore('?')
        return MANGA_DEX_CHAPTER.find(clean)?.groupValues?.get(1)?.lowercase()
    }

    /**
     * Builds the image URLs returned by `/at-home/server/{chapter}`. MangaDex serves original
     * pages under `/data/` and compressed ones under `/data-saver/`; both use the same hash and
     * file names.
     */
    fun pageUrls(
        baseUrl: String,
        hash: String,
        files: List<String>,
        dataSaver: Boolean = false
    ): List<String> {
        if (baseUrl.isBlank() || hash.isBlank()) return emptyList()
        val root = baseUrl.trimEnd('/')
        val mode = if (dataSaver) "data-saver" else "data"
        return files.asSequence()
            .map { it.trim().trimStart('/') }
            .filter { it.isNotEmpty() }
            .filter { !it.contains("..") }
            .map { "$root/$mode/$hash/$it" }
            .toList()
    }
}
