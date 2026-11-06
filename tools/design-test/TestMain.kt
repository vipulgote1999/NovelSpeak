import app.novelspeak.Design

/**
 * Host-side checks for the shared design frame. No Android runtime needed.
 *
 *   kotlinc app/src/app/novelspeak/Design.kt tools/design-test/TestMain.kt \
 *     -include-runtime -d /tmp/design-test.jar
 *   java -jar /tmp/design-test.jar
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

    check("phone gutter", Design.gutter(411) == 20, Design.gutter(411).toString())
    check("phone content width", Design.contentWidth(411) == 371, Design.contentWidth(411).toString())
    check("phone content padding", Design.contentPadding(411) == 20, Design.contentPadding(411).toString())

    check("medium gutter", Design.gutter(700) == 24, Design.gutter(700).toString())
    check("medium content width", Design.contentWidth(700) == 652, Design.contentWidth(700).toString())
    check("medium padding", Design.contentPadding(700) == 24, Design.contentPadding(700).toString())

    check("tablet width is capped", Design.contentWidth(800) == 720, Design.contentWidth(800).toString())
    check("tablet padding centers", Design.contentPadding(800) == 40, Design.contentPadding(800).toString())
    check("wide tablet width is capped", Design.contentWidth(1200) == 720, Design.contentWidth(1200).toString())
    check("wide tablet padding", Design.contentPadding(1200) == 240, Design.contentPadding(1200).toString())

    check("tiny width never goes below 280", Design.contentWidth(300) == 280, Design.contentWidth(300).toString())
    check("medium classification", Design.isMedium(700) && !Design.isMedium(599) && !Design.isMedium(840))
    check("expanded classification", Design.isExpanded(840) && Design.isExpanded(1200) && !Design.isExpanded(839))
    check("touch tokens", Design.TOUCH_DP == 48 && Design.DENSE_TOUCH_DP == 44)
    check("motion tokens ordered", Design.MOTION_FAST < Design.MOTION_NORMAL && Design.MOTION_NORMAL < Design.MOTION_SLOW)

    println("$checks checks, $failures failure(s)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
