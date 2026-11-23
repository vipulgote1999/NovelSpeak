import app.novelspeak.Design
import app.novelspeak.PlayerChrome

/**
 * Host-side checks for the full-card / mini-player visibility rules, the narrow action strip,
 * and the compact transport target sizes.
 *
 *   kotlinc app/src/app/novelspeak/Design.kt app/src/app/novelspeak/PlayerChrome.kt \
 *     tools/player-chrome-test/TestMain.kt -include-runtime -d /tmp/player-chrome-test.jar
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

    check("no content labels Read", PlayerChrome.readActionLabel(0) == "Read")
    check("no content describes Read", PlayerChrome.readActionDescription(0) == "Read chapter")
    check("content labels Restart", PlayerChrome.readActionLabel(1) == "Restart")
    check(
        "content describes Restart",
        PlayerChrome.readActionDescription(1) == "Restart chapter"
    )

    check("speed row stays inline on a normal phone", !PlayerChrome.stackSpeedRow(411, 1.0f))
    check("speed row stacks on a 320 dp phone", PlayerChrome.stackSpeedRow(320, 1.0f))
    check("speed row stacks at 1.3x text", PlayerChrome.stackSpeedRow(411, 1.3f))
    check("speed row stays inline on a tablet", !PlayerChrome.stackSpeedRow(800, 1.0f))

    check("actions stay inline on a normal phone", !PlayerChrome.stackActionRow(411, 1.0f))
    check("actions stack on a 320 dp phone", PlayerChrome.stackActionRow(320, 1.0f))
    check("actions stack at 2x text", PlayerChrome.stackActionRow(411, 2.0f))
    check("actions stay inline at 1.3x on a phone", !PlayerChrome.stackActionRow(411, 1.3f))

    val compact = PlayerChrome.compactTransportTargets()
    check("compact previous target meets the dense minimum", compact.previousDp >= Design.DENSE_TOUCH_DP, "got=${compact.previousDp}")
    check("compact next target meets the dense minimum", compact.nextDp >= Design.DENSE_TOUCH_DP, "got=${compact.nextDp}")
    check("compact stop target meets the dense minimum", compact.stopDp >= Design.DENSE_TOUCH_DP, "got=${compact.stopDp}")
    check("compact sleep target meets the dense minimum", compact.sleepDp >= Design.DENSE_TOUCH_DP, "got=${compact.sleepDp}")
    check("compact play target meets the primary minimum", compact.playDp >= Design.PRIMARY_TOUCH_DP, "got=${compact.playDp}")

    println("$checks checks, $failures failure(s)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
