import app.novelspeak.UrlInput

/**
 * Host-side checks for the address-bar normalizer. Not part of the APK.
 *
 *   kotlinc app/src/app/novelspeak/UrlInput.kt tools/url-input-test/TestMain.kt \
 *     -include-runtime -d /tmp/url-input-test.jar
 *   java -jar /tmp/url-input-test.jar
 */
fun main() {
    val cases = listOf(
        "https://example.com/a" to "https://example.com/a",
        "http://example.com" to "http://example.com",
        "example.com" to "https://example.com",
        "example.com/path?q=1" to "https://example.com/path?q=1",
        "localhost:3000" to "https://localhost:3000",
        "about:blank" to "about:blank",
        "hello world" to "https://www.google.com/search?q=hello+world",
        "javascript:alert(1)" to "https://www.google.com/search?q=javascript%3Aalert%281%29",
        "file:///etc/passwd" to "https://www.google.com/search?q=file%3A%2F%2F%2Fetc%2Fpasswd",
        "ftp://example.com" to "https://www.google.com/search?q=ftp%3A%2F%2Fexample.com",
        "" to null
    )

    var failed = 0
    for ((input, expected) in cases) {
        val actual = UrlInput.normalize(input)
        val ok = actual == expected
        println((if (ok) "PASS" else "FAIL") + "  " + input + " -> " + actual)
        if (!ok) failed++
    }
    if (failed > 0) {
        println("$failed case(s) failed")
        kotlin.system.exitProcess(1)
    }
}
