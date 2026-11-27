import app.novelspeak.manga.MangaHtmlParser
import app.novelspeak.manga.MangaProvider

/**
 * Host-side checks for provider URL detection and MangaDex page URL building.
 *
 *   kotlinc app/src/app/novelspeak/manga/MangaProvider.kt \
 *     tools/manga-provider-test/TestMain.kt -include-runtime -d /tmp/manga-provider-test.jar
 *   java -jar /tmp/manga-provider-test.jar
 */
fun main() {
    var failures = 0
    var checks = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        checks++
        if (ok) println("PASS $name") else {
            failures++
            println("FAILED $name $detail")
        }
    }

    val md = "https://mangadex.org/chapter/6A0A63C1-3F57-4685-BE6C-14E3CA7EB180/1"
    check("detect-mangadex", MangaProvider.detect(md) == MangaProvider.Kind.MANGADEX)
    check(
        "chapter-id",
        MangaProvider.mangaDexChapterId(md) == "6a0a63c1-3f57-4685-be6c-14e3ca7eb180"
    )
    check(
        "chapter-id-query",
        MangaProvider.mangaDexChapterId("$md?foo=bar#page") ==
            "6a0a63c1-3f57-4685-be6c-14e3ca7eb180"
    )
    check("reject-generic", MangaProvider.detect("https://weebcentral.com/chapters/abc") == MangaProvider.Kind.GENERIC)
    check("reject-other", MangaProvider.detect("https://webtoons.com/viewer?title_no=95") == MangaProvider.Kind.GENERIC)
    check("reject-null", MangaProvider.detect(null) == MangaProvider.Kind.GENERIC)
    check(
        "detect-webtoons",
        MangaProvider.detect(
            "https://www.webtoons.com/en/fantasy/tower-of-god/season-1-ep-0/viewer?episode_no=1&title_no=95"
        ) == MangaProvider.Kind.WEBTOONS
    )
    check(
        "detect-tapas",
        MangaProvider.detect("https://m.tapas.io/episode/1959019") == MangaProvider.Kind.TAPAS
    )
    check(
        "reject-tapas-series",
        MangaProvider.detect("https://tapas.io/series/Amalon") == MangaProvider.Kind.GENERIC
    )

    val pages = MangaProvider.pageUrls(
        "https://cmdxd98sb0x3yprd.mangadex.network/",
        "35092a1304efdb2a09fae552550773d6",
        listOf("x1-abc.png", "/x2-def.png", ""),
    )
    check("page-count", pages.size == 2, "got=$pages")
    check(
        "page-url",
        pages[0] == "https://cmdxd98sb0x3yprd.mangadex.network/data/35092a1304efdb2a09fae552550773d6/x1-abc.png",
        "got=${pages.getOrNull(0)}"
    )
    check(
        "page-trim",
        pages[1].endsWith("/x2-def.png"),
        "got=${pages.getOrNull(1)}"
    )
    val saver = MangaProvider.pageUrls("https://x/", "h", listOf("p.jpg"), dataSaver = true)
    check("page-saver", saver.single() == "https://x/data-saver/h/p.jpg", "got=$saver")
    check("page-empty", MangaProvider.pageUrls("", "h", listOf("p.jpg")).isEmpty())
    check("page-hash", MangaProvider.pageUrls("https://x", "", listOf("p.jpg")).isEmpty())


    // Provider HTML: Webtoons page images live in img._images[data-url].
    val webtoonHtml = """
        <html><head><title>Tower of God - [Season 1] Ep. 0 | WEBTOON</title></head><body>
        <div id="_imageList">
          <img src="https://webtoons-static.pstatic.net/image/bg_transparency.png"
               width="700" height="1140" class="_images"
               data-url="https://webtoon-phinf.pstatic.net/a/page1.jpg?type=q90">
          <img src="https://webtoons-static.pstatic.net/image/bg_transparency.png"
               width="700" height="1140" class="_images"
               data-url="https://webtoon-phinf.pstatic.net/b/page2.jpg?type=q90">
        </div>
        <img src="https://webtoon-phinf.pstatic.net/thumb.jpg" class="_thumbnailImages"
             data-url="https://webtoon-phinf.pstatic.net/thumb.jpg">
        </body></html>
    """.trimIndent()
    val webtoonPages = MangaHtmlParser.webtoonPageUrls(webtoonHtml)
    check("webtoon-pages", webtoonPages.size == 2, "got=$webtoonPages")
    check("webtoon-order", webtoonPages[0].contains("page1") && webtoonPages[1].contains("page2"))
    check("webtoon-title", MangaHtmlParser.webtoonTitle(webtoonHtml)!!.contains("Tower of God"))

    // Tapas page images live in img.content__img[data-src]; tokens keep the &amp; entity.
    val tapasHtml = """
        <html><head><title>Read Amalon :: Episode 2 | Tapas Community</title></head><body>
        <img class="content__img js-lazy" data-src="https://us-a.tapas.io/c/01/one.jpg?__token__=a&amp;version=v4">
        <img class="content__img js-lazy" data-src="https://us-a.tapas.io/c/02/two.png?__token__=b&amp;version=v4">
        <img class="logo" data-src="https://us-a.tapas.io/logo.jpg">
        </body></html>
    """.trimIndent()
    val tapasPages = MangaHtmlParser.tapasPageUrls(tapasHtml)
    check("tapas-pages", tapasPages.size == 2, "got=$tapasPages")
    check("tapas-entity", tapasPages[0].contains("&version=v4") && !tapasPages[0].contains("&amp;"))
    check("tapas-title", MangaHtmlParser.tapasTitle(tapasHtml) == "Amalon · Episode 2", "got=${MangaHtmlParser.tapasTitle(tapasHtml)}")

    println("$checks checks, $failures failure(s)")
    if (failures > 0) {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
    println("ALL PASS")
}
