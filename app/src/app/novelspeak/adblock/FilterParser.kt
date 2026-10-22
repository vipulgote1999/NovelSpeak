package app.novelspeak.adblock

import java.util.Locale

/**
 * Parses Adblock Plus / uBlock Origin filter syntax into the compact model used by
 * [AdBlockEngine].
 *
 * The supported subset is deliberately conservative: a rule that uses an option we do not
 * implement is dropped rather than interpreted approximately. That trades a few missed blocks
 * for far fewer site breakages, which matters more in a reader that must keep chapter pages working.
 */
object FilterParser {

    class ParseResult(
        @JvmField val network: List<NetworkFilter>,
        @JvmField val cosmetic: List<CosmeticFilter>,
        @JvmField val skipped: Int
    )

    private val KNOWN_OPTIONS = HashSet<String>(64).apply {
        addAll(listOf(
            "script", "image", "stylesheet", "css", "xhr", "xmlhttprequest", "subdocument",
            "sub_frame", "font", "media", "websocket", "ping", "beacon", "object",
            "object-subrequest", "other", "document", "doc", "all", "network", "popup",
            "third-party", "3p", "first-party", "1p", "important", "match-case", "badfilter",
            "generichide", "elemhide", "domain", "method", "redirect", "redirect-rule",
            "removeparam", "csp", "denyallow", "inline-script", "inline-font", "webrtc",
            "stealth", "sitekey", "app", "header", "permissions", "replace", "urltransform",
            "uritransform", "genericblock", "specifichide", "to", "from", "empty", "mp4", "noop"
        ))
    }

    private class Options {
        var types = 0
        var excludedTypes = 0
        var thirdParty = -1
        var important = false
        var matchCase = false
        var badfilter = false
        var generichide = false
        var elemhide = false
        var popup = false
        var document = false
        var redirect: String? = null
        var includeDomains: MutableList<String>? = null
        var excludeDomains: MutableList<String>? = null
        var methods = 0
    }

    fun parse(lines: List<String>, firstId: Int = 1): ParseResult {
        val network = ArrayList<NetworkFilter>(lines.size / 2)
        val cosmetic = ArrayList<CosmeticFilter>(lines.size / 4)
        var skipped = 0
        var id = firstId

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue
            val sep = cosmeticSeparator(line)
            if (sep != null) {
                val c = parseCosmetic(line, sep)
                if (c == null) skipped++ else cosmetic.add(c)
            } else {
                val n = parseNetwork(line, id)
                if (n == null) {
                    skipped++
                } else {
                    network.add(n)
                    id++
                }
            }
        }

        // $badfilter disables a rule regardless of the order the two appear in the lists.
        val badHashes = HashSet<Long>()
        for (f in network) if (f.badfilter) badHashes.add(f.canonicalHash)
        val kept = if (badHashes.isEmpty()) network else network.filter { it.canonicalHash !in badHashes }

        return ParseResult(kept, cosmetic, skipped)
    }

    // ---------------------------------------------------------------- cosmetic

    private fun cosmeticSeparator(line: String): String? {
        // Longest / most specific first; "#@#" must win over "##".
        val separators = arrayOf("#@?#", "#@#", "#?#", "#$#", "#%#", "##")
        for (sep in separators) {
            val idx = line.indexOf(sep)
            if (idx < 0) continue
            val left = line.substring(0, idx)
            if (idx == 0 && sep == "##") return sep
            if (left.isEmpty()) continue
            var ok = true
            for (c in left) {
                val allowed = c.isLetterOrDigit() || c == '.' || c == '*' || c == '~' ||
                    c == ',' || c == '-' || c == '_'
                if (!allowed) {
                    ok = false
                    break
                }
            }
            if (ok) return sep
        }
        return null
    }

    private fun parseCosmetic(line: String, sep: String): CosmeticFilter? {
        val idx = line.indexOf(sep)
        if (idx < 0) return null
        val left = line.substring(0, idx).trim()
        var right = line.substring(idx + sep.length).trim()
        if (right.isEmpty()) return null
        if (right.startsWith("^")) return null // HTML filtering is not implemented

        val include = ArrayList<String>(2)
        val exclude = ArrayList<String>(2)
        if (left.isNotEmpty() && left != "*") {
            for (part in left.split(',')) {
                var h = part.trim().lowercase(Locale.US)
                if (h.isEmpty()) continue
                if (h.startsWith("~")) {
                    h = h.substring(1)
                    if (h.isNotEmpty()) exclude.add(h)
                } else {
                    include.add(h)
                }
            }
        }

        val isException = sep.startsWith("#@")
        val isScriptletSyntax = right.startsWith("+js(") && right.endsWith(")")

        if (isScriptletSyntax) {
            val inner = right.substring(4, right.length - 1)
            val args = splitArgs(inner)
            if (args.isEmpty() || args[0].isEmpty()) return null
            val name = args[0].lowercase(Locale.US)
            val rest = if (args.size > 1) args.copyOfRange(1, args.size) else emptyArray()
            return CosmeticFilter(
                raw = line,
                hostnames = include.toTypedArray(),
                excludeHostnames = exclude.toTypedArray(),
                selector = "",
                action = CosmeticAction.HIDE,
                actionArg = null,
                isException = isException,
                isScriptlet = true,
                scriptletName = name,
                scriptletArgs = rest,
                procedural = false,
                genericKey = null
            )
        }

        if (sep == "#%#") return null // AdGuard JS injection: deliberately unsupported
        if (sep == "#$#") return null // AdGuard CSS injection: deliberately unsupported

        val parsed = parseAction(right)
        right = parsed.first
        var action = parsed.second
        var actionArg = parsed.third
        if (right.isEmpty()) return null

        val procedural = right.contains(":has-text(") || right.contains(":matches-css(") ||
            right.contains(":upward(") || right.contains(":xpath(") || right.contains(":watch-attr(")

        if (action == CosmeticAction.HIDE && procedural) {
            // Keep the raw procedural selector; the in-page runtime understands the operators.
        }

        val key = if (include.isEmpty()) genericKey(right) else null
        return CosmeticFilter(
            raw = line,
            hostnames = include.toTypedArray(),
            excludeHostnames = exclude.toTypedArray(),
            selector = right,
            action = action,
            actionArg = actionArg,
            isException = isException,
            isScriptlet = false,
            scriptletName = null,
            scriptletArgs = null,
            procedural = procedural,
            genericKey = key
        )
    }

    /** Returns (selector, action, argument). */
    private fun parseAction(sel: String): Triple<String, Int, String?> {
        if (sel.endsWith(")")) {
            val styles = sel.lastIndexOf(":style(")
            if (styles > 0) {
                return Triple(sel.substring(0, styles), CosmeticAction.STYLE,
                    sel.substring(styles + 7, sel.length - 1))
            }
            val attr = sel.lastIndexOf(":remove-attr(")
            if (attr > 0) {
                return Triple(sel.substring(0, attr), CosmeticAction.REMOVE_ATTR,
                    sel.substring(attr + 13, sel.length - 1))
            }
            val cls = sel.lastIndexOf(":remove-class(")
            if (cls > 0) {
                return Triple(sel.substring(0, cls), CosmeticAction.REMOVE_CLASS,
                    sel.substring(cls + 14, sel.length - 1))
            }
        }
        val remove = sel.lastIndexOf(":remove()")
        if (remove > 0) return Triple(sel.substring(0, remove), CosmeticAction.REMOVE, null)
        return Triple(sel, CosmeticAction.HIDE, null)
    }

    private fun genericKey(selector: String): String? {
        var i = 0
        while (i < selector.length) {
            val c = selector[i]
            if ((c == '.' || c == '#') && i + 1 < selector.length && isKeyChar(selector[i + 1])) {
                var j = i + 1
                while (j < selector.length && isKeyChar(selector[j])) j++
                return selector.substring(i + 1, j)
            }
            i++
        }
        return null
    }

    private fun isKeyChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

    // ---------------------------------------------------------------- network

    private fun parseNetwork(line: String, id: Int): NetworkFilter? {
        var s = line
        var isException = false
        if (s.startsWith("@@")) {
            isException = true
            s = s.substring(2)
        }
        val split = splitOptions(s) ?: return null
        val patternPart = split.first
        val opts = parseOptions(split.second) ?: return null
        if (patternPart.isEmpty()) return null

        var p = patternPart
        var hostAnchored = false
        var left = false
        var right = false
        if (p.startsWith("||")) {
            hostAnchored = true
            p = p.substring(2)
        } else if (p.startsWith("|")) {
            left = true
            p = p.substring(1)
        }
        if (p.endsWith("|")) {
            right = true
            p = p.substring(0, p.length - 1)
        }
        if (p.isEmpty()) return null

        val isRegex = p.length > 2 && p.startsWith("/") && p.endsWith("/")
        var hostname: String? = null
        if (hostAnchored && !isRegex) {
            var end = 0
            while (end < p.length && p[end] != '^' && p[end] != '/' && p[end] != '*' &&
                p[end] != '?' && p[end] != ':' && p[end] != '#'
            ) end++
            val h = p.substring(0, end).lowercase(Locale.US)
            if (h.isEmpty() || h.contains("*")) return null
            hostname = h
        }

        val matchPattern = if (isRegex) p.substring(1, p.length - 1) else p
        if (matchPattern.isEmpty()) return null

        val canonical = canonicalLine(line)
        return NetworkFilter(
            id = id,
            raw = line,
            pattern = matchPattern,
            hostname = hostname,
            isException = isException,
            isImportant = opts.important,
            isRegex = isRegex,
            hostAnchored = hostAnchored,
            leftAnchor = left,
            rightAnchor = right,
            matchCase = opts.matchCase,
            thirdParty = opts.thirdParty,
            includeDomains = opts.includeDomains?.toTypedArray(),
            excludeDomains = opts.excludeDomains?.toTypedArray(),
            types = opts.types,
            excludedTypes = opts.excludedTypes,
            methods = opts.methods,
            isPopup = opts.popup,
            isDocument = opts.document,
            isGenerichide = opts.generichide,
            isElemhide = opts.elemhide,
            redirect = opts.redirect,
            badfilter = opts.badfilter,
            canonicalHash = fnv1a(canonical)
        )
    }

    private fun splitOptions(s: String): Pair<String, String?>? {
        val idx = s.lastIndexOf('$')
        if (idx <= 0 || idx == s.length - 1) return s to null
        val tail = s.substring(idx + 1)
        val first = tail.substringBefore(',').substringBefore('=').removePrefix("~")
        if (!KNOWN_OPTIONS.contains(first.lowercase(Locale.US))) return s to null
        return s.substring(0, idx) to tail
    }

    private fun parseOptions(tail: String?): Options? {
        val o = Options()
        if (tail.isNullOrEmpty()) return o
        for (raw in tail.split(',')) {
            var name = raw.trim()
            if (name.isEmpty()) continue
            var neg = false
            if (name.startsWith("~")) {
                neg = true
                name = name.substring(1)
            }
            val eq = name.indexOf('=')
            val key = (if (eq >= 0) name.substring(0, eq) else name).lowercase(Locale.US)
            val value = if (eq >= 0) name.substring(eq + 1) else ""

            val bit = typeBit(key)
            if (bit != 0) {
                if (neg) o.excludedTypes = o.excludedTypes or bit else o.types = o.types or bit
                if (key == "document" || key == "doc") o.document = !neg
                continue
            }

            when (key) {
                "third-party", "3p" -> o.thirdParty = if (neg) 0 else 1
                "first-party", "1p" -> o.thirdParty = if (neg) 1 else 0
                "important" -> {
                    if (neg) return null
                    o.important = true
                }
                "match-case" -> {
                    if (neg) return null
                    o.matchCase = true
                }
                "badfilter" -> {
                    if (neg) return null
                    o.badfilter = true
                }
                "generichide" -> o.generichide = !neg
                "elemhide" -> o.elemhide = !neg
                "popup" -> {
                    o.popup = !neg
                    // Popup rules are evaluated against a top-level navigation, not a subresource.
                    if (!neg) o.types = o.types or ResourceType.DOCUMENT
                }
                "domain" -> {
                    if (value.isEmpty()) return null
                    for (d in value.split('|')) {
                        var dom = d.trim().lowercase(Locale.US)
                        if (dom.isEmpty()) continue
                        if (dom.startsWith("~")) {
                            dom = dom.substring(1)
                            if (dom.isEmpty()) continue
                            if (o.excludeDomains == null) o.excludeDomains = ArrayList()
                            o.excludeDomains!!.add(dom)
                        } else {
                            if (o.includeDomains == null) o.includeDomains = ArrayList()
                            o.includeDomains!!.add(dom)
                        }
                    }
                }
                "method" -> {
                    if (!neg) {
                        for (m in value.split('|')) {
                            when (m.trim().lowercase(Locale.US)) {
                                "get" -> o.methods = o.methods or METHOD_GET
                                "post" -> o.methods = o.methods or METHOD_POST
                                "head" -> o.methods = o.methods or METHOD_HEAD
                            }
                        }
                    }
                }
                "redirect", "redirect-rule" -> {
                    if (neg || value.isEmpty()) return null
                    o.redirect = value.substringBefore(':')
                }
                "all" -> o.types = if (neg) o.types else ResourceType.ALL
                "network" -> o.types = if (neg) o.types else ResourceType.NETWORK
                // Options that change response semantics we cannot reproduce in a WebView.
                "removeparam", "csp", "denyallow", "inline-script", "inline-font", "webrtc",
                "stealth", "sitekey", "app", "header", "permissions", "replace", "urltransform",
                "uritransform", "genericblock", "specifichide", "to", "from", "empty", "mp4", "noop" -> return null
                else -> return null
            }
        }
        return o
    }

    private fun typeBit(key: String): Int = when (key) {
        "script" -> ResourceType.SCRIPT
        "image" -> ResourceType.IMAGE
        "stylesheet", "css" -> ResourceType.STYLESHEET
        "xhr", "xmlhttprequest" -> ResourceType.XHR
        "subdocument", "sub_frame" -> ResourceType.SUBDOCUMENT
        "font" -> ResourceType.FONT
        "media" -> ResourceType.MEDIA
        "websocket" -> ResourceType.WEBSOCKET
        "ping", "beacon" -> ResourceType.PING
        "object", "object-subrequest" -> ResourceType.OBJECT
        "other" -> ResourceType.OTHER
        "document", "doc" -> ResourceType.DOCUMENT
        else -> 0
    }

    private fun canonicalLine(line: String): String =
        line.replace("badfilter,", "").replace(",badfilter", "").trim()

    private fun fnv1a(s: String): Long {
        var h = -0x340d631b7bdddcdbL // 14695981039346656037
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 1099511628211L
        }
        return h
    }

    private fun splitArgs(s: String): Array<String> {
        val out = ArrayList<String>(4)
        val sb = StringBuilder(32)
        var quote = ' '
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(s[i + 1])
                i += 2
                continue
            }
            if (quote != ' ') {
                if (c == quote) quote = ' ' else sb.append(c)
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == ',') {
                out.add(sb.toString().trim())
                sb.setLength(0)
            } else {
                sb.append(c)
            }
            i++
        }
        out.add(sb.toString().trim())
        return out.toTypedArray()
    }

    const val METHOD_GET = 1
    const val METHOD_POST = 2
    const val METHOD_HEAD = 4
}
