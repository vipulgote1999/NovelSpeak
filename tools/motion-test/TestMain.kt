import app.novelspeak.Motion

/**
 * Host-side checks for reduced-motion decisions.
 *
 *   kotlinc app/src/app/novelspeak/Motion.kt tools/motion-test/TestMain.kt \
 *     -include-runtime -d /tmp/motion-test.jar
 *   java -jar /tmp/motion-test.jar
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

    check("scale 1 enables motion", Motion.enabled(1f))
    check("scale 0 disables motion", !Motion.enabled(0f))
    check("duration follows the scale", Motion.durationMs(220, 1f) == 220L)
    check("reduced motion has zero duration", Motion.durationMs(220, 0f) == 0L)
    check("negative base is clamped", Motion.durationMs(-5, 1f) == 0L)

    println("$checks checks, $failures failure(s)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
