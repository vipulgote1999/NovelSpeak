package app.novelspeak.adblock

import java.util.Locale

/**
 * Public Suffix List based eTLD+1 resolution.
 *
 * This is the same job adblock-rust delegates to the `addr` crate: `$third-party` and `$domain=`
 * are only meaningful when `a.example.co.uk` and `b.example.co.uk` are recognised as one site.
 *
 * The parsed rules are published as an immutable snapshot. A list reload can happen while WebView
 * request threads are matching, so readers never see a partially cleared set.
 */
object PublicSuffixList {

    private class Snapshot(
        @JvmField val rules: Set<String>,
        @JvmField val wildcards: Set<String>,
        @JvmField val exceptions: Set<String>
    )

    @Volatile
    private var snapshot: Snapshot? = null

    fun load(text: String) {
        val rules = HashSet<String>(16384)
        val wildcards = HashSet<String>(256)
        val exceptions = HashSet<String>(64)
        for (raw in text.lineSequence()) {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("//")) continue
            val isException = line.startsWith("!")
            if (isException) line = line.substring(1)
            if (line.startsWith("*.")) {
                wildcards.add(line.substring(2).lowercase(Locale.US))
                continue
            }
            line = line.lowercase(Locale.US)
            if (isException) exceptions.add(line) else rules.add(line)
        }
        snapshot = Snapshot(rules, wildcards, exceptions)
    }

    fun isLoaded(): Boolean = snapshot != null

    /**
     * Returns the registrable domain (eTLD+1) of [host], or [host] itself when the host is an IP
     * address, localhost, or a public suffix with no registrable part.
     */
    fun registrableDomain(host: String): String {
        if (host.isEmpty()) return host
        var h = host.lowercase(Locale.US)
        if (h.endsWith(".")) h = h.substring(0, h.length - 1)
        if (h.isEmpty() || h == "localhost") return h
        if (isIpAddress(h)) return h

        val labels = h.split('.')
        if (labels.size < 2) return h
        val s = snapshot ?: return h

        var suffixLen = 0
        var i = 0
        while (i < labels.size) {
            val suffix = join(labels, i, labels.size)
            if (s.exceptions.contains(suffix)) {
                suffixLen = labels.size - i - 1
                break
            }
            if (s.rules.contains(suffix)) {
                suffixLen = labels.size - i
                break
            }
            // "*.ck" makes any "<label>.ck" a public suffix.
            if (i + 1 < labels.size) {
                val base = join(labels, i + 1, labels.size)
                if (s.wildcards.contains(base)) {
                    suffixLen = labels.size - i
                    break
                }
            }
            i++
        }
        if (suffixLen == 0) suffixLen = 1
        if (suffixLen >= labels.size) return h
        return join(labels, labels.size - suffixLen - 1, labels.size)
    }

    fun sameSite(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return registrableDomain(a) == registrableDomain(b)
    }

    private fun join(labels: List<String>, from: Int, to: Int): String {
        val sb = StringBuilder()
        var i = from
        while (i < to) {
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(labels[i])
            i++
        }
        return sb.toString()
    }

    private fun isIpAddress(host: String): Boolean {
        if (host.indexOf(':') >= 0) return true // IPv6
        var dots = 0
        for (c in host) {
            if (c == '.') {
                dots++
            } else if (c !in '0'..'9') {
                return false
            }
        }
        return dots == 3
    }
}
