import app.novelspeak.PlayerChrome

/**
 * Host-side checks for the full-card / mini-player visibility rules.
 *
 *   kotlinc app/src/app/novelspeak/PlayerChrome.kt tools/player-chrome-test/TestMain.kt \
 *     -include-runtime -d /tmp/player-chrome-test.jar
 *   java -jar /tmp/player-chrome-test.jar
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

    check("reader expanded shows card", PlayerChrome.showCard(false, false, false))
    check("reader expanded hides mini", !PlayerChrome.showMini(false, false, false, true))
    check("reader collapsed shows mini", PlayerChrome.showMini(true, false, false, true))
    check("reader collapsed hides card", !PlayerChrome.showCard(true, false, true))
    check("collapsed with no content cannot strand", !PlayerChrome.showMini(true, false, true, false))

    check("home playing shows card", PlayerChrome.showCard(false, true, true))
    check("home paused hides card", !PlayerChrome.showCard(false, true, false))
    check("home playing collapsed shows mini", PlayerChrome.showMini(true, true, true, true))
    check("home paused collapsed hides mini", !PlayerChrome.showMini(true, true, false, true))

    println("$checks checks, $failures failure(s)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
