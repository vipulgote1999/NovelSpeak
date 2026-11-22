package app.novelspeak

/**
 * Installation shape for a model entry.
 *
 * Sherpa models are normally `.tar.bz2` bundles, but the 24 kHz vocoder is a bare
 * `.onnx` file. The installer must choose the matching path instead of assuming every
 * download is an archive.
 */
object ModelInstallPolicy {
    fun isArchive(url: String): Boolean =
        withoutQuery(url).endsWith(".tar.bz2", ignoreCase = true)

    fun targetFileName(url: String): String =
        withoutQuery(url).substringAfterLast('/')

    private fun withoutQuery(url: String): String =
        url.substringBefore('?').substringBefore('#')
}
