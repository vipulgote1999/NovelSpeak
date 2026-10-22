package app.novelspeak.adblock

/**
 * Deterministic checks for the engine. They run against inline rules so a list update cannot make
 * them flaky, and they are exposed from Settings ("Run self-test") so a device can prove the
 * parser/matcher works after a build without adb.
 */
object AdBlockSelfTest {

    private val RULES = listOf(
        "||ads.example.com^",
        "||tracker.net^\$third-party",
        "||cdn.example.com^\$script",
        "@@||cdn.example.com/ok.js^\$script",
        "||promo.example.com^\$domain=news.example",
        "|https://exact.example/path|",
        "/banner\\d+\\.gif/\$image",
        "@@||exempt.example^\$document",
        "example.com##.ad-box",
        "##.sponsored",
        "example.com##.promo:style(display:none!important)",
        "example.com##+js(set-constant, adsEnabled, false)"
    )

    fun run(): String {
        if (!PublicSuffixList.isLoaded()) return "Self-test skipped: public suffix list not loaded"
        val parsed = FilterParser.parse(RULES)
        val engine = AdBlockEngine.build(parsed.network, parsed.cosmetic)
        val failures = ArrayList<String>()
        var total = 0

        fun expectBlock(name: String, url: String, doc: String, type: Int, expected: Boolean) {
            total++
            val got = engine.check(RequestInfo(url, doc, type, "GET", false)).block
            if (got != expected) failures.add("$name: expected block=$expected, got $got")
        }

        expectBlock("host anchor", "https://ads.example.com/ad.js", "https://news.example/", ResourceType.SCRIPT, true)
        expectBlock("host anchor subdomain", "https://sub.ads.example.com/x", "https://news.example/", ResourceType.SCRIPT, true)
        expectBlock("host anchor negative", "https://notads.example.com/x", "https://news.example/", ResourceType.SCRIPT, false)
        expectBlock("third party", "https://tracker.net/pixel", "https://news.example/", ResourceType.IMAGE, true)
        expectBlock("first party allowed", "https://tracker.net/pixel", "https://tracker.net/page", ResourceType.IMAGE, false)
        expectBlock("script type", "https://cdn.example.com/app.js", "https://news.example/", ResourceType.SCRIPT, true)
        expectBlock("script type negative", "https://cdn.example.com/app.css", "https://news.example/", ResourceType.STYLESHEET, false)
        expectBlock("exception", "https://cdn.example.com/ok.js", "https://news.example/", ResourceType.SCRIPT, false)
        expectBlock("domain option", "https://promo.example.com/x", "https://news.example/", ResourceType.SCRIPT, true)
        expectBlock("domain option negative", "https://promo.example.com/x", "https://other.example/", ResourceType.SCRIPT, false)
        expectBlock("left/right anchor", "https://exact.example/path", "https://news.example/", ResourceType.OTHER, true)
        expectBlock("right anchor mismatch", "https://exact.example/path?q=1", "https://news.example/", ResourceType.OTHER, false)
        expectBlock("regex image", "https://x.example/banner12.gif", "https://news.example/", ResourceType.IMAGE, true)
        expectBlock("regex type mismatch", "https://x.example/banner12.gif", "https://news.example/", ResourceType.SCRIPT, false)
        expectBlock("document exception", "https://ads.example.com/x", "https://exempt.example/", ResourceType.SCRIPT, false)

        total++
        val siteCosmetic = engine.cosmeticFor("example.com")
        if (!siteCosmetic.hideSelectors.contains(".ad-box")) failures.add("cosmetic: .ad-box missing")
        if (siteCosmetic.procedural.none { it.selector == ".promo" && it.action == CosmeticAction.STYLE }) {
            failures.add("cosmetic: .promo style action missing")
        }
        if (siteCosmetic.scriptlets.none { it.contains("adsEnabled") }) {
            failures.add("scriptlet: set-constant not rendered")
        }

        total++
        val generic = engine.genericSelectorsFor(listOf("sponsored"), emptyList(), "example.com")
        if (!generic.contains(".sponsored")) failures.add("generic cosmetic: .sponsored missing")

        total++
        if (PublicSuffixList.registrableDomain("a.b.co.uk") != "b.co.uk") {
            failures.add("psl: a.b.co.uk -> ${PublicSuffixList.registrableDomain("a.b.co.uk")}")
        }
        total++
        if (!PublicSuffixList.sameSite("news.example.com", "ads.example.com")) {
            failures.add("psl: same-site example mismatch")
        }
        total++
        if (PublicSuffixList.sameSite("news.example.com", "ads.example.net")) {
            failures.add("psl: unrelated sites reported same-site")
        }

        total++
        if (!WildcardMatcher.match("https://x.test/a/banner12.gif?x=1", "/a/banner", -1, false, false)) {
            failures.add("wildcard: substring match failed")
        }
        total++
        if (WildcardMatcher.match("https://x.test/a", "b", -1, true, false)) {
            failures.add("wildcard: right anchor accepted")
        }

        return if (failures.isEmpty()) {
            "Self-test: $total/$total passed (${engine.stats().networkRules} network, ${engine.stats().cosmeticRules} cosmetic rules)"
        } else {
            "Self-test failed ${failures.size}/$total:\n" + failures.joinToString("\n")
        }
    }
}
