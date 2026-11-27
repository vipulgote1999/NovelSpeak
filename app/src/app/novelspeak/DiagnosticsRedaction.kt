package app.novelspeak

import java.util.Locale

/**
 * Redaction shared by the shareable diagnostics bundle and the on-device event log.
 *
 * Key-based redaction alone is not enough: values such as `library_v1` and
 * `zipvoice_transcript` do not have a sensitive word in their key, but they still contain
 * chapter URLs, book titles, reading history or a user's private speech text. Redact
 * those keys explicitly and scrub any URL or credential-shaped token that survives.
 */
object DiagnosticsRedaction {
    private const val MAX_VALUE_CHARS = 300

    private val SENSITIVE_KEY_PARTS = arrayOf(
        "url", "token", "rule", "secret", "password", "passphrase",
        "library", "bookmark", "transcript", "history", "sites"
    )

    private val URL = Regex("(?i)https?://[^\\s\"']+")

    private val CREDENTIAL = Regex(
        "(?i)(token|secret|password|passphrase|api[_-]?key)\\s*=\\s*\\S+"
    )

    fun safeValue(key: String, value: Any?): String {
        if (value == null) return "null"
        val lower = key.lowercase(Locale.US)
        val sensitive = SENSITIVE_KEY_PARTS.any { lower.contains(it) }
        return when (value) {
            is String -> if (sensitive) {
                "(redacted, ${value.length} chars)"
            } else {
                clean(value).take(MAX_VALUE_CHARS)
            }
            is Set<*> -> if (sensitive) {
                "(redacted, ${value.size} items)"
            } else {
                value.joinToString("|") { clean(it.toString()) }.take(MAX_VALUE_CHARS)
            }
            is Boolean, is Int, is Long, is Float -> value.toString()
            else -> if (sensitive) "(redacted)" else clean(value.toString()).take(MAX_VALUE_CHARS)
        }
    }

    /** Scrubs URLs and credential-shaped values from free-form event/crash text. */
    fun redact(text: String): String {
        var out = URL.replace(text, "[url redacted]")
        out = CREDENTIAL.replace(out) { "${it.groupValues[1]}=[redacted]" }
        return out
    }

    private fun clean(value: String): String =
        redact(
            value
                .replace("\r", " ")
                .replace("\n", "\\n")
                .replace("\t", " ")
        )
}
