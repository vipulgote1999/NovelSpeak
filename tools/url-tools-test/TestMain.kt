import app.novelspeak.adblock.UrlTools

/**
 * Host checks for the URL helpers used by ad-block policy and the next-link guard.
 * These are security-relevant: a `file:`, `content:`, `data:` or `javascript:` URL
 * must never look like a web navigation.
 */
fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean) {
        if (ok) println("PASS $name") else {
            failures++
            println("FAILED $name")
        }
    }

    check("https scheme", UrlTools.isHttpUrl("https://example.com/chapter"))
    check("http scheme", UrlTools.isHttpUrl("http://127.0.0.1:8765/chapter"))
    check("file is not web", !UrlTools.isHttpUrl("file:///etc/passwd"))
    check("content is not web", !UrlTools.isHttpUrl("content://provider/book.epub"))
    check("data is not web", !UrlTools.isHttpUrl("data:text/html,hi"))
    check("javascript is not web", !UrlTools.isHttpUrl("javascript:alert(1)"))
    check("intent is not web", !UrlTools.isHttpUrl("intent://scan/#Intent;end"))
    check("blank is not web", !UrlTools.isHttpUrl(""))

    check("scheme lowercases", UrlTools.scheme("HTTPS://example.com") == "https")
    check("host strips userinfo and port", UrlTools.host("https://user:pass@Example.COM:8080/x") == "example.com")
    check("host handles ipv6", UrlTools.host("https://[::1]:8765/x") == "::1")
    check("host without authority is null", UrlTools.host("not a url") == null)
    check("host handles bare path", UrlTools.host("https://example.com") == "example.com")

    check("tokens lowercase alphanumeric", UrlTools.tokens("https://Example.com/Foo-Bar").contains("example"))
    check("tokens include path word", UrlTools.tokens("https://example.com/Foo-Bar").contains("foo"))
    check("tokens skip one-letter noise", !UrlTools.tokens("https://example.com/a/b").contains("a"))

    if (failures > 0) {
        println("$failures case(s) failed")
        kotlin.system.exitProcess(1)
    }
    println("ALL PASS")
}
