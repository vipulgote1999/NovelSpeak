import app.novelspeak.adblock.AdBlockEngine
import app.novelspeak.adblock.AdBlockSelfTest
import app.novelspeak.adblock.FilterParser
import app.novelspeak.adblock.PublicSuffixList
import java.io.File

/**
 * Host-side harness for the pure-Kotlin engine. Not part of the APK.
 *
 *   kotlinc app/src/app/novelspeak/adblock tools/adblock-test/TestMain.kt \
 *     -include-runtime -d /tmp/adblock-test.jar
 *   java -jar /tmp/adblock-test.jar app/assets/adblock --stats
 */
fun main(args: Array<String>) {
    val base = if (args.isNotEmpty() && !args[0].startsWith("--")) args[0] else "app/assets/adblock"
    PublicSuffixList.load(File("$base/public_suffix_list.dat").readText())

    println(AdBlockSelfTest.run())

    if (args.contains("--stats")) {
        val files = listOf(
            "easylist.txt",
            "easyprivacy.txt",
            "ublock-filters.txt",
            "ublock-annoyances.txt",
            "ublock-badware.txt"
        )
        val lines = ArrayList<String>(180_000)
        for (f in files) {
            File("$base/$f").forEachLine { lines.add(it) }
        }
        val t0 = System.currentTimeMillis()
        val parsed = FilterParser.parse(lines)
        val t1 = System.currentTimeMillis()
        val engine = AdBlockEngine.build(parsed.network, parsed.cosmetic)
        val t2 = System.currentTimeMillis()
        println("parsed network=${parsed.network.size} cosmetic=${parsed.cosmetic.size} skipped=${parsed.skipped} in ${t1 - t0}ms")
        println("built in ${t2 - t1}ms")
        println(engine.debugInfo())
        println("stats=${engine.stats().networkRules}/${engine.stats().cosmeticRules} scriptlets=${engine.stats().scriptletRules}")

        fun probe(name: String, url: String, doc: String, type: Int) {
            val d = engine.check(app.novelspeak.adblock.RequestInfo(url, doc, type, "GET", false))
            println("$name -> block=${d.block} rule=${d.rule?.raw ?: "-"}")
        }
        probe("ad.doubleclick", "https://ad.doubleclick.net/ads.js", "https://empirenovel.com/chapter/1", app.novelspeak.adblock.ResourceType.SCRIPT)
        probe("doubleclick-popup", "https://doubleclick.net/popup", "https://empirenovel.com/chapter/1", app.novelspeak.adblock.ResourceType.DOCUMENT)
        println("popup document blocked=" + engine.shouldBlockDocument("https://doubleclick.net/popup", "https://empirenovel.com/chapter/1"))
        probe("googletagmanager", "https://www.googletagmanager.com/gtm.js", "https://empirenovel.com/chapter/1", app.novelspeak.adblock.ResourceType.SCRIPT)
        probe("google-analytics", "https://www.google-analytics.com/analytics.js", "https://empirenovel.com/chapter/1", app.novelspeak.adblock.ResourceType.SCRIPT)
        probe("first-party", "https://empirenovel.com/chapter/2", "https://empirenovel.com/chapter/1", app.novelspeak.adblock.ResourceType.SUBDOCUMENT)
        probe("cdn", "https://cdn.jsdelivr.net/npm/jquery/dist/jquery.min.js", "https://empirenovel.com/chapter/1", app.novelspeak.adblock.ResourceType.SCRIPT)
    }
}
