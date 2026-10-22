package app.novelspeak.adblock

import java.util.regex.Pattern

/**
 * Resource-type bits. WebView does not tell us the request type, so the Android layer infers it
 * from the Accept header and the URL extension (see [UrlTools.typeFromHeaders]).
 */
object ResourceType {
    const val SCRIPT = 1
    const val IMAGE = 1 shl 1
    const val STYLESHEET = 1 shl 2
    const val XHR = 1 shl 3
    const val SUBDOCUMENT = 1 shl 4
    const val FONT = 1 shl 5
    const val MEDIA = 1 shl 6
    const val WEBSOCKET = 1 shl 7
    const val PING = 1 shl 8
    const val OBJECT = 1 shl 9
    const val OTHER = 1 shl 10
    const val DOCUMENT = 1 shl 11

    const val ALL = (1 shl 12) - 1
    const val NETWORK = ALL and DOCUMENT.inv()
}

object CosmeticAction {
    const val HIDE = 0
    const val REMOVE = 1
    const val STYLE = 2
    const val REMOVE_ATTR = 3
    const val REMOVE_CLASS = 4
}

/**
 * One parsed network rule. Rules are immutable after the index is built, except [regex], which is
 * a lazy cache of the compiled pattern.
 */
class NetworkFilter(
    @JvmField val id: Int,
    @JvmField val raw: String,
    /** Pattern after stripping anchors; for host-anchored rules this still starts with the host. */
    @JvmField val pattern: String,
    /** Host for `||` rules, used by the host bucket and by the label-boundary check. */
    @JvmField val hostname: String?,
    @JvmField val isException: Boolean,
    @JvmField val isImportant: Boolean,
    @JvmField val isRegex: Boolean,
    @JvmField val hostAnchored: Boolean,
    @JvmField val leftAnchor: Boolean,
    @JvmField val rightAnchor: Boolean,
    @JvmField val matchCase: Boolean,
    /** -1 = any, 0 = first-party only, 1 = third-party only. */
    @JvmField val thirdParty: Int,
    @JvmField val includeDomains: Array<String>?,
    @JvmField val excludeDomains: Array<String>?,
    /** 0 means "all network types" unless [documentOnly] is set. */
    @JvmField val types: Int,
    @JvmField val excludedTypes: Int,
    @JvmField val methods: Int,
    @JvmField val isPopup: Boolean,
    @JvmField val isDocument: Boolean,
    @JvmField val isGenerichide: Boolean,
    @JvmField val isElemhide: Boolean,
    @JvmField val redirect: String?,
    @JvmField val badfilter: Boolean,
    /** FNV-1a of the canonical line (badfilter option removed), used to apply `$badfilter`. */
    @JvmField val canonicalHash: Long
) {
    @Volatile
    @JvmField
    var regex: Pattern? = null

    /** Selected token for the token bucket; empty means the fallback bucket. */
    @JvmField
    var token: String = ""

    fun compiledRegex(): Pattern? {
        if (!isRegex) return null
        var p = regex
        if (p == null) {
            try {
                p = Pattern.compile(pattern, if (matchCase) 0 else Pattern.CASE_INSENSITIVE)
            } catch (t: Throwable) {
                return null
            }
            regex = p
        }
        return p
    }
}

/** One parsed cosmetic rule (element hiding, procedural action, or scriptlet). */
class CosmeticFilter(
    @JvmField val raw: String,
    @JvmField val hostnames: Array<String>,
    @JvmField val excludeHostnames: Array<String>,
    @JvmField val selector: String,
    @JvmField val action: Int,
    @JvmField val actionArg: String?,
    @JvmField val isException: Boolean,
    @JvmField val isScriptlet: Boolean,
    @JvmField val scriptletName: String?,
    @JvmField val scriptletArgs: Array<String>?,
    @JvmField val procedural: Boolean,
    /** First class/id in the selector; null means "always apply". */
    @JvmField val genericKey: String?
)

/** A cosmetic exception policy derived from `$elemhide` / `$generichide` network rules. */
class CosmeticPolicy(
    @JvmField val hostnames: Array<String>,
    @JvmField val excludeHostnames: Array<String>?,
    @JvmField val generichide: Boolean,
    @JvmField val elemhide: Boolean
)

/** Request shape handed to [AdBlockEngine.check]. */
class RequestInfo(
    @JvmField val url: String,
    @JvmField val documentUrl: String?,
    @JvmField val type: Int,
    @JvmField val method: String,
    @JvmField val isMainFrame: Boolean
)

class Decision(
    @JvmField val block: Boolean,
    @JvmField val rule: NetworkFilter?,
    @JvmField val redirect: String?
) {
    companion object {
        val NONE = Decision(false, null, null)
    }
}

class ProceduralRule(
    @JvmField val selector: String,
    @JvmField val action: Int,
    @JvmField val actionArg: String?
)

class CosmeticConfig(
    @JvmField val hideSelectors: List<String>,
    @JvmField val procedural: List<ProceduralRule>,
    @JvmField val scriptlets: List<String>,
    @JvmField val generichide: Boolean,
    @JvmField val elemhide: Boolean,
    @JvmField val exceptions: List<String>
) {
    companion object {
        val EMPTY = CosmeticConfig(emptyList(), emptyList(), emptyList(), false, false, emptyList())
    }
}

class BuildStats(
    @JvmField val networkRules: Int,
    @JvmField val cosmeticRules: Int,
    @JvmField val scriptletRules: Int,
    @JvmField val skipped: Int,
    @JvmField val buildMs: Long
)
