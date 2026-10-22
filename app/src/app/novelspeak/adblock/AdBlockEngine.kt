package app.novelspeak.adblock

import java.util.Locale

/**
 * Immutable, token-indexed filter engine.
 *
 * The indexing strategy is the one that makes adblock-rust fast (Brave's engine): every network
 * rule is filed under one rare token from its pattern, and a request only checks the buckets for
 * the tokens that appear in its URL. Host-anchored `||host^` rules get a second, cheaper index
 * keyed by hostname so the common case never touches a regex. The breakage handling (document
 * exceptions, `$generichide`, same-site bypass) follows DuckDuckGo's WebView pipeline.
 */
class AdBlockEngine private constructor(
    private val hostBuckets: HashMap<String, Array<NetworkFilter>>,
    private val tokenBuckets: HashMap<String, Array<NetworkFilter>>,
    private val regexBuckets: HashMap<String, Array<NetworkFilter>>,
    private val fallback: Array<NetworkFilter>,
    private val documentExceptionHosts: HashSet<String>,
    private val popupRules: Array<NetworkFilter>,
    private val cosmeticHostBuckets: HashMap<String, Array<CosmeticFilter>>,
    private val genericByKey: HashMap<String, Array<CosmeticFilter>>,
    private val genericAlways: Array<CosmeticFilter>,
    private val cosmeticExceptions: HashMap<String, Array<String>>,
    private val genericExceptions: Array<String>,
    private val policies: Array<CosmeticPolicy>,
    private val buildStats: BuildStats
) {

    fun stats(): BuildStats = buildStats

    /** Diagnostic summary used by the Settings self-test. */
    fun debugInfo(): String =
        "docExceptions=${documentExceptionHosts.size} hostBuckets=${hostBuckets.size} " +
            "tokenBuckets=${tokenBuckets.size} regexBuckets=${regexBuckets.size} fallback=${fallback.size}"

    fun check(request: RequestInfo): Decision {
        if (request.isMainFrame) return Decision.NONE
        val documentUrl = request.documentUrl ?: return Decision.NONE
        val host = UrlTools.host(request.url) ?: return Decision.NONE
        val documentHost = UrlTools.host(documentUrl) ?: return Decision.NONE
        if (isDocumentAllowed(documentHost)) return Decision.NONE

        val third = if (UrlTools.isThirdParty(request.url, documentUrl)) 1 else 0
        val lower = request.url.lowercase(Locale.US)
        var block: NetworkFilter? = null
        var exception: NetworkFilter? = null

        for (suffix in UrlTools.suffixes(host)) {
            val bucket = hostBuckets[suffix] ?: continue
            for (f in bucket) {
                if (f.isGenerichide || f.isElemhide || f.isDocument) continue
                if (!evaluate(f, request, host, documentHost, third, lower)) continue
                if (f.isException) {
                    if (exception == null) exception = f
                } else if (f.isImportant) {
                    return Decision(true, f, f.redirect)
                } else if (block == null) {
                    block = f
                }
            }
        }

        val tokens = UrlTools.tokens(lower)
        for (token in tokens) {
            val bucket = tokenBuckets[token] ?: continue
            for (f in bucket) {
                if (f.isGenerichide || f.isElemhide || f.isDocument) continue
                if (!evaluate(f, request, host, documentHost, third, lower)) continue
                if (f.isException) {
                    if (exception == null) exception = f
                } else if (f.isImportant) {
                    return Decision(true, f, f.redirect)
                } else if (block == null) {
                    block = f
                }
            }
        }

        // Regex rules are bucketed by a 3-character prefix: /banner\d+\.gif/ must still find
        // the URL token "banner12" even though the regex token is "banner".
        if (regexBuckets.isNotEmpty()) {
            val seen = HashSet<Int>()
            for (token in tokens) {
                if (token.length < 3) continue
                val bucket = regexBuckets[token.substring(0, 3)] ?: continue
                for (f in bucket) {
                    if (!seen.add(f.id)) continue
                    if (f.isGenerichide || f.isElemhide || f.isDocument) continue
                    if (!evaluate(f, request, host, documentHost, third, lower)) continue
                    if (f.isException) {
                        if (exception == null) exception = f
                    } else if (f.isImportant) {
                        return Decision(true, f, f.redirect)
                    } else if (block == null) {
                        block = f
                    }
                }
            }
        }

        for (f in fallback) {
            if (f.isGenerichide || f.isElemhide || f.isDocument) continue
            if (!evaluate(f, request, host, documentHost, third, lower)) continue
            if (f.isException) {
                if (exception == null) exception = f
            } else if (f.isImportant) {
                return Decision(true, f, f.redirect)
            } else if (block == null) {
                block = f
            }
        }

        if (block != null && exception == null) return Decision(true, block, block.redirect)
        return Decision.NONE
    }

    /**
     * Main-frame protection. Only `$popup` rules (and popup exceptions) are considered: blocking
     * a normal top-level navigation would leave the reader on a blank page.
     */
    fun shouldBlockDocument(url: String, documentUrl: String?): Boolean {
        if (popupRules.isEmpty()) return false
        val host = UrlTools.host(url) ?: return false
        val docHost = documentUrl?.let { UrlTools.host(it) }
        if (docHost != null && isDocumentAllowed(docHost)) return false
        val third = if (documentUrl == null) 1 else if (UrlTools.isThirdParty(url, documentUrl)) 1 else 0
        val lower = url.lowercase(Locale.US)
        val req = RequestInfo(url, documentUrl, ResourceType.DOCUMENT, "GET", true)
        var blocked = false
        for (f in popupRules) {
            if (f.isException) {
                if (evaluate(f, req, host, docHost ?: "", third, lower)) return false
            } else if (evaluate(f, req, host, docHost ?: "", third, lower)) {
                blocked = true
            }
        }
        return blocked
    }

    private fun isDocumentAllowed(documentHost: String): Boolean {
        if (documentExceptionHosts.isEmpty()) return false
        for (suffix in UrlTools.suffixes(documentHost)) {
            if (documentExceptionHosts.contains(suffix)) return true
        }
        return false
    }

    private fun evaluate(
        f: NetworkFilter,
        request: RequestInfo,
        host: String,
        documentHost: String,
        third: Int,
        lowerUrl: String
    ): Boolean {
        val types = if (f.types == 0) ResourceType.NETWORK else f.types
        if ((types and request.type) == 0) return false
        if ((f.excludedTypes and request.type) != 0) return false

        if (f.thirdParty != -1 && f.thirdParty != third) return false
        if (!domainsMatch(f.includeDomains, documentHost, true)) return false
        if (!domainsMatch(f.excludeDomains, documentHost, false)) return false

        if (f.methods != 0) {
            val bit = methodBit(request.method)
            if (bit == 0 || (f.methods and bit) == 0) return false
        }

        val text = if (f.matchCase) request.url else lowerUrl
        if (f.isRegex) {
            val regex = f.compiledRegex() ?: return false
            return regex.matcher(text).find()
        }
        if (f.hostAnchored) return matchHostAnchored(f, text, host)
        return WildcardMatcher.match(
            text = text,
            pattern = f.pattern,
            from = if (f.leftAnchor) 0 else -1,
            requireEnd = f.rightAnchor,
            caseSensitive = f.matchCase
        )
    }

    private fun matchHostAnchored(f: NetworkFilter, text: String, host: String): Boolean {
        val site = f.hostname ?: return false
        if (!UrlTools.hostMatches(host, site)) return false
        val hostStart = UrlTools.hostStart(text) ?: return false
        // For a subdomain, the rule's domain begins at a label boundary inside the host.
        var labelStart = text.indexOf(site, hostStart, ignoreCase = true)
        if (labelStart < 0) return false
        if (labelStart > hostStart) {
            val before = text[labelStart - 1]
            if (before != '.') return false
        }
        return WildcardMatcher.match(
            text = text,
            pattern = f.pattern,
            from = labelStart,
            requireEnd = f.rightAnchor,
            caseSensitive = f.matchCase
        )
    }

    private fun domainsMatch(domains: Array<String>?, documentHost: String, include: Boolean): Boolean {
        if (domains == null || domains.isEmpty()) return true
        for (d in domains) {
            if (UrlTools.hostMatches(documentHost, d)) return include
        }
        return !include
    }

    private fun methodBit(method: String): Int = when (method.uppercase(Locale.US)) {
        "GET" -> FilterParser.METHOD_GET
        "POST" -> FilterParser.METHOD_POST
        "HEAD" -> FilterParser.METHOD_HEAD
        else -> 0
    }

    // ---------------------------------------------------------------- cosmetic

    fun cosmeticFor(host: String): CosmeticConfig {
        if (host.isEmpty()) return CosmeticConfig.EMPTY
        val hide = LinkedHashSet<String>()
        val procedural = ArrayList<ProceduralRule>()
        val scriptlets = ArrayList<String>()
        val exceptions = LinkedHashSet<String>()
        var generichide = false
        var elemhide = false

        for (p in policies) {
            if (matchesAnyHost(host, p.hostnames) && !matchesAnyHost(host, p.excludeHostnames)) {
                if (p.generichide) generichide = true
                if (p.elemhide) elemhide = true
            }
        }

        // Exceptions first, so they can suppress host rules collected below.
        for (suffix in UrlTools.suffixes(host)) {
            cosmeticHostBuckets[suffix]?.forEach { if (it.isException) exceptions.add(it.selector) }
        }
        val entity = entityKey(host)
        if (entity != null) {
            cosmeticHostBuckets[entity]?.forEach { if (it.isException) exceptions.add(it.selector) }
        }

        for (suffix in UrlTools.suffixes(host)) {
            collectCosmetic(cosmeticHostBuckets[suffix], host, hide, procedural, scriptlets, exceptions)
        }
        if (entity != null) {
            collectCosmetic(cosmeticHostBuckets[entity], host, hide, procedural, scriptlets, exceptions)
        }

        if (!generichide && !elemhide) {
            for (f in genericAlways) {
                if (f.isException || excludedForHost(f, host)) continue
                addCosmetic(f, hide, procedural, scriptlets, exceptions)
            }
        }

        if (elemhide) {
            hide.clear()
            procedural.clear()
        }

        return CosmeticConfig(
            hideSelectors = hide.take(if (elemhide) 0 else 4000),
            procedural = procedural.take(500),
            scriptlets = scriptlets.distinct().take(30),
            generichide = generichide,
            elemhide = elemhide,
            exceptions = exceptions.toList()
        )
    }

    fun genericSelectorsFor(classes: List<String>, ids: List<String>, host: String): List<String> {
        if (host.isEmpty() || genericByKey.isEmpty()) return emptyList()
        val exceptions = HashSet<String>()
        for (suffix in UrlTools.suffixes(host)) {
            cosmeticExceptions[suffix]?.let { exceptions.addAll(it) }
        }
        val entity = entityKey(host)
        if (entity != null) cosmeticExceptions[entity]?.let { exceptions.addAll(it) }
        for (e in genericExceptions) exceptions.add(e)

        val out = LinkedHashSet<String>()
        for (name in classes) {
            addGenericFor(name, host, exceptions, out)
            if (out.size >= 800) break
        }
        if (out.size < 800) {
            for (name in ids) {
                addGenericFor(name, host, exceptions, out)
                if (out.size >= 800) break
            }
        }
        return out.toList()
    }

    private fun addGenericFor(
        name: String,
        host: String,
        exceptions: Set<String>,
        out: MutableSet<String>
    ) {
        if (name.length < 2 || name.length > 80) return
        val bucket = genericByKey[name] ?: return
        for (f in bucket) {
            if (f.isException || f.procedural || f.action != CosmeticAction.HIDE) continue
            if (excludedForHost(f, host)) continue
            if (exceptions.contains(f.selector)) continue
            out.add(f.selector)
        }
    }

    private fun collectCosmetic(
        bucket: Array<CosmeticFilter>?,
        host: String,
        hide: MutableSet<String>,
        procedural: MutableList<ProceduralRule>,
        scriptlets: MutableList<String>,
        exceptions: MutableSet<String>
    ) {
        if (bucket == null) return
        for (f in bucket) {
            if (f.isException || excludedForHost(f, host)) continue
            addCosmetic(f, hide, procedural, scriptlets, exceptions)
        }
    }

    private fun addCosmetic(
        f: CosmeticFilter,
        hide: MutableSet<String>,
        procedural: MutableList<ProceduralRule>,
        scriptlets: MutableList<String>,
        exceptions: MutableSet<String>
    ) {
        if (f.isScriptlet) {
            val js = Scriptlets.render(f.scriptletName, f.scriptletArgs)
            if (js != null) scriptlets.add(js)
            return
        }
        if (f.procedural || f.action != CosmeticAction.HIDE) {
            procedural.add(ProceduralRule(f.selector, f.action, f.actionArg))
        } else if (!exceptions.contains(f.selector)) {
            hide.add(f.selector)
        }
    }

    private fun excludedForHost(f: CosmeticFilter, host: String): Boolean {
        if (f.excludeHostnames.isEmpty()) return false
        for (h in f.excludeHostnames) {
            if (UrlTools.hostMatches(host, h)) return true
        }
        return false
    }

    private fun matchesAnyHost(host: String, hostnames: Array<String>?): Boolean {
        if (hostnames == null) return false
        for (h in hostnames) {
            if (h.isEmpty()) continue
            if (h.endsWith(".*")) {
                if (entityMatches(host, h)) return true
            } else if (UrlTools.hostMatches(host, h)) {
                return true
            }
        }
        return false
    }

    private fun entityMatches(host: String, entity: String): Boolean {
        val base = entity.removeSuffix(".*")
        val reg = PublicSuffixList.registrableDomain(host)
        return reg == base || reg.startsWith(base + ".")
    }

    private fun entityKey(host: String): String? {
        val reg = PublicSuffixList.registrableDomain(host)
        if (reg.isEmpty() || reg.indexOf('.') < 0) return null
        val first = reg.substringBefore('.')
        if (first.isEmpty()) return null
        return first + ".*"
    }

    companion object {

        private val BAD_TOKENS = arrayOf(
            "https", "http", "www", "com", "net", "org", "io", "de", "fr", "es", "it", "nl",
            "se", "ru", "pl", "co", "js", "css", "img", "jpg", "html", "png", "cdn", "static",
            "images", "api", "wp", "ad", "ads", "content", "doubleclick", "analytics", "assets",
            "id", "min", "amazon", "google", "googlesyndication", "googleapis"
        )

        fun build(
            network: List<NetworkFilter>,
            cosmetic: List<CosmeticFilter>
        ): AdBlockEngine {
            val start = System.currentTimeMillis()

            // 1. Token usage frequency, with the same "avoid these" bias Brave uses.
            val usage = HashMap<String, Int>(1 shl 16)
            for (t in BAD_TOKENS) usage[t] = Int.MAX_VALUE / 2
            val perRuleTokens = ArrayList<ArrayList<String>>(network.size)
            for (f in network) {
                if (f.hostname != null) {
                    perRuleTokens.add(ArrayList(0))
                    continue
                }
                val unique = LinkedHashSet<String>()
                val toks = UrlTools.patternTokens(f.pattern)
                for (t in toks) if (t.length >= 2) unique.add(t)
                for (t in unique) usage[t] = (usage[t] ?: 0) + 1
                perRuleTokens.add(ArrayList(unique))
            }

            // 2. Bucket rules.
            val hostBuckets = HashMap<String, MutableList<NetworkFilter>>(4096)
            val tokenBuckets = HashMap<String, MutableList<NetworkFilter>>(1 shl 15)
            val regexBuckets = HashMap<String, MutableList<NetworkFilter>>(512)
            val fallback = ArrayList<NetworkFilter>(1024)
            for (i in network.indices) {
                val f = network[i]
                val h = f.hostname
                if (h != null) {
                    hostBuckets.getOrPut(h) { ArrayList(4) }.add(f)
                    continue
                }
                val toks = perRuleTokens[i]
                if (toks.isEmpty()) {
                    fallback.add(f)
                    continue
                }
                var best = toks[0]
                var bestUse = usage[best] ?: 0
                for (t in toks) {
                    val u = usage[t] ?: 0
                    if (u < bestUse) {
                        best = t
                        bestUse = u
                    }
                }
                f.token = best
                if (f.isRegex) {
                    if (best.length >= 3) {
                        regexBuckets.getOrPut(best.substring(0, 3)) { ArrayList(4) }.add(f)
                    } else {
                        fallback.add(f)
                    }
                } else {
                    tokenBuckets.getOrPut(best) { ArrayList(4) }.add(f)
                }
            }

            // 3. Site-wide document exceptions: @@||site^$document means "leave this site alone".
            val documentExceptions = HashSet<String>(256)
            val popup = ArrayList<NetworkFilter>(256)
            val cosmeticPolicies = ArrayList<CosmeticPolicy>(128)
            for (f in network) {
                if (f.isPopup) popup.add(f)
                if (f.isException && f.isDocument) {
                    val h = f.hostname
                    if (h != null) documentExceptions.add(h)
                    f.includeDomains?.let { for (d in it) documentExceptions.add(d) }
                }
                if (f.isGenerichide || f.isElemhide) {
                    val hosts = when {
                        f.hostname != null -> arrayOf(f.hostname!!)
                        f.includeDomains != null -> f.includeDomains!!
                        else -> emptyArray()
                    }
                    if (hosts.isNotEmpty()) {
                        cosmeticPolicies.add(
                            CosmeticPolicy(hosts, f.excludeDomains, f.isGenerichide, f.isElemhide)
                        )
                    }
                }
            }

            // 4. Cosmetic indexes.
            val cosmeticHostBuckets = HashMap<String, MutableList<CosmeticFilter>>(4096)
            val genericByKey = HashMap<String, MutableList<CosmeticFilter>>(1 shl 14)
            val genericAlways = ArrayList<CosmeticFilter>(1024)
            val cosmeticExceptions = HashMap<String, MutableList<String>>(512)
            val genericExceptions = ArrayList<String>(256)
            var scriptletCount = 0

            for (f in cosmetic) {
                if (f.isScriptlet) scriptletCount++
                if (f.isException) {
                    if (f.isScriptlet) continue // scriptlet exceptions are not modeled
                    if (f.hostnames.isEmpty()) {
                        genericExceptions.add(f.selector)
                    } else {
                        for (h in f.hostnames) {
                            cosmeticExceptions.getOrPut(h) { ArrayList(4) }.add(f.selector)
                        }
                    }
                    continue
                }
                if (f.hostnames.isEmpty()) {
                    if (!f.procedural && f.action == CosmeticAction.HIDE && f.genericKey != null) {
                        genericByKey.getOrPut(f.genericKey) { ArrayList(4) }.add(f)
                    } else {
                        genericAlways.add(f)
                    }
                } else {
                    for (h in f.hostnames) {
                        cosmeticHostBuckets.getOrPut(h) { ArrayList(4) }.add(f)
                    }
                }
            }

            val stats = BuildStats(
                networkRules = network.size,
                cosmeticRules = cosmetic.size,
                scriptletRules = scriptletCount,
                skipped = 0,
                buildMs = System.currentTimeMillis() - start
            )

            return AdBlockEngine(
                hostBuckets = freeze(hostBuckets),
                tokenBuckets = freeze(tokenBuckets),
                regexBuckets = freeze(regexBuckets),
                fallback = fallback.toTypedArray(),
                documentExceptionHosts = documentExceptions,
                popupRules = popup.toTypedArray(),
                cosmeticHostBuckets = freezeCosmetic(cosmeticHostBuckets),
                genericByKey = freezeCosmetic(genericByKey),
                genericAlways = genericAlways.toTypedArray(),
                cosmeticExceptions = freezeList(cosmeticExceptions),
                genericExceptions = genericExceptions.toTypedArray(),
                policies = cosmeticPolicies.toTypedArray(),
                buildStats = stats
            )
        }

        private fun freeze(src: HashMap<String, MutableList<NetworkFilter>>): HashMap<String, Array<NetworkFilter>> {
            val out = HashMap<String, Array<NetworkFilter>>(src.size * 2)
            for ((k, v) in src) out[k] = v.toTypedArray()
            return out
        }

        private fun freezeCosmetic(src: HashMap<String, MutableList<CosmeticFilter>>): HashMap<String, Array<CosmeticFilter>> {
            val out = HashMap<String, Array<CosmeticFilter>>(src.size * 2)
            for ((k, v) in src) out[k] = v.toTypedArray()
            return out
        }

        private fun freezeList(src: HashMap<String, MutableList<String>>): HashMap<String, Array<String>> {
            val out = HashMap<String, Array<String>>(src.size * 2)
            for ((k, v) in src) out[k] = v.toTypedArray()
            return out
        }
    }
}

/** Wildcard matcher for ABP patterns containing `*` and `^`. */
object WildcardMatcher {

    fun match(text: String, pattern: String, from: Int, requireEnd: Boolean, caseSensitive: Boolean): Boolean {
        if (pattern.isEmpty()) return false
        var hasWildcard = false
        var hasSeparator = false
        for (c in pattern) {
            if (c == '*') hasWildcard = true
            if (c == '^') hasSeparator = true
        }

        if (!hasWildcard && !hasSeparator) {
            return if (from >= 0) {
                if (!text.regionMatches(from, pattern, 0, pattern.length, ignoreCase = !caseSensitive)) false
                else !requireEnd || from + pattern.length == text.length
            } else if (requireEnd) {
                text.endsWith(pattern, ignoreCase = !caseSensitive)
            } else {
                text.indexOf(pattern, 0, ignoreCase = !caseSensitive) >= 0
            }
        }

        if (from >= 0) return matchAt(text, from, pattern, requireEnd, caseSensitive)

        // Non-anchored: use the literal prefix before the first wildcard to find candidates.
        var prefixEnd = 0
        while (prefixEnd < pattern.length && pattern[prefixEnd] != '*' && pattern[prefixEnd] != '^') prefixEnd++
        val prefix = pattern.substring(0, prefixEnd)
        if (prefix.isNotEmpty()) {
            var idx = text.indexOf(prefix, 0, ignoreCase = !caseSensitive)
            while (idx >= 0) {
                if (matchAt(text, idx, pattern, requireEnd, caseSensitive)) return true
                idx = text.indexOf(prefix, idx + 1, ignoreCase = !caseSensitive)
            }
            return false
        }
        var i = 0
        while (i <= text.length) {
            if (matchAt(text, i, pattern, requireEnd, caseSensitive)) return true
            i++
        }
        return false
    }

    private fun matchAt(
        text: String,
        start: Int,
        pattern: String,
        requireEnd: Boolean,
        caseSensitive: Boolean
    ): Boolean {
        var ti = start
        var pi = 0
        var starPi = -1
        var starTi = -1
        while (ti < text.length) {
            if (pi >= pattern.length) break
            if (pi < pattern.length) {
                val pc = pattern[pi]
                if (pc == '*') {
                    starPi = pi
                    starTi = ti
                    pi++
                    continue
                }
                if (pc == '^') {
                    if (isSeparator(text[ti])) {
                        pi++
                        ti++
                        continue
                    }
                } else if (charEquals(pc, text[ti], caseSensitive)) {
                    pi++
                    ti++
                    continue
                }
            }
            if (starPi >= 0) {
                starTi++
                ti = starTi
                pi = starPi + 1
                continue
            }
            return false
        }
        while (pi < pattern.length) {
            val pc = pattern[pi]
            if (pc == '*') {
                pi++
            } else if (pc == '^' && ti == text.length) {
                pi++
            } else {
                break
            }
        }
        if (pi != pattern.length) return false
        return !requireEnd || ti == text.length
    }

    private fun charEquals(a: Char, b: Char, caseSensitive: Boolean): Boolean {
        if (a == b) return true
        if (caseSensitive) return false
        return a.lowercaseChar() == b.lowercaseChar()
    }

    /** ABP separator: anything that is not a letter, digit, or one of `_-.%`. */
    private fun isSeparator(c: Char): Boolean {
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') return false
        return c != '_' && c != '-' && c != '.' && c != '%'
    }
}
