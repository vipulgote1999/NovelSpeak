import app.novelspeak.PrerollPolicy

/**
 * Host-side regression for the Reader's preroll decisions.
 *
 *   kotlinc app/src/app/novelspeak/PrerollPolicy.kt tools/preroll-policy-test/TestMain.kt \
 *     -include-runtime -d /tmp/preroll-policy-test.jar
 *   java -jar /tmp/preroll-policy-test.jar
 *
 * Exit 0 = all checks pass. Any failure prints FAILED and exits 1.
 */
fun main() {
    var failures = 0
    var checks = 0
    fun check(name: String, cond: Boolean, detail: String = "") {
        checks++
        if (cond) println("PASS $name") else {
            failures++
            println("FAILED $name $detail")
        }
    }

    fun queue(
        submitted: Int,
        cursor: Int = 0,
        total: Int = 6,
        buffered: Float = 0f,
        lastRendered: Boolean = false,
        max: Int = 4,
        target: Float = 8f
    ) = PrerollPolicy.shouldQueueNext(
        submitted, cursor, total, buffered, lastRendered, max, target
    )

    // The first clip is always queued when nothing is in flight.
    check("queue first", queue(submitted = -1))
    check("queue first after cursor", queue(submitted = 2, cursor = 3))

    // Never queue ahead before the previous sentence has rendered.
    check("wait for last render", !queue(submitted = 0, lastRendered = false))
    check("queue after render", queue(submitted = 0, lastRendered = true, buffered = 4f))

    // Time and sentence caps.
    check("stop at target", !queue(submitted = 0, lastRendered = true, buffered = 8f))
    check("stop at cap", !queue(submitted = 3, lastRendered = true, buffered = 0f, max = 4))
    check("queue below cap", queue(submitted = 2, lastRendered = true, buffered = 0f, max = 4))

    // End of queue.
    check("stop at last sentence", !queue(submitted = 5, total = 6, lastRendered = true))
    check("stop at only sentence", !queue(submitted = 0, total = 1, lastRendered = true))
    check("empty queue", !queue(submitted = -1, total = 0))

    // Degenerate settings must not queue.
    check("zero max", !queue(submitted = -1, max = 0))
    check("cursor out of range", !queue(submitted = -1, cursor = 6, total = 6))

    // Start policy.
    check("start when requested and rendered", PrerollPolicy.canStart(true, false, true))
    check("do not start without request", !PrerollPolicy.canStart(false, false, true))
    check("do not start while playing", !PrerollPolicy.canStart(true, true, true))
    check("do not start on skipped cursor", !PrerollPolicy.canStart(true, false, false))

    println(if (failures == 0) "ALL PASS ($checks checks)" else "$failures FAILURES ($checks checks)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
