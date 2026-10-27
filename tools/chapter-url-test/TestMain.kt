import app.novelspeak.ChapterUrls

fun main() {
    var failed = 0
    fun check(input: String, expected: String) {
        val got = ChapterUrls.nextNumbered(input)
        if (got != expected) {
            failed++
            println("FAIL: " + input + " -> " + got + " (expected " + expected + ")")
        } else {
            println("ok: " + input + " -> " + got)
        }
    }
    check("https://www.empirenovel.com/novel/my-werewolf-system/143", "https://www.empirenovel.com/novel/my-werewolf-system/144")
    check("https://site.test/chapter/142/", "https://site.test/chapter/143/")
    check("https://site.test/story/9?x=1", "https://site.test/story/10?x=1")
    check("https://site.test/story/9#top", "https://site.test/story/10#top")
    check("https://site.test/article/42", "")
    check("https://site.test/novel", "")
    check("", "")
    check("https://site.test/novel/19/extra", "")
    check("https://site.test/read/book/0007", "https://site.test/read/book/8")
    if (failed > 0) {
        println("FAILED " + failed)
        kotlin.system.exitProcess(1)
    }
    println("ALL PASS")
}
