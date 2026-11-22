import app.novelspeak.DrainGuard

/**
 * Regression for the PcmPlayer drain timeout.
 *
 * The drain watchdog must never fire while playback is deliberately held for preroll;
 * otherwise a loaded-but-not-playing chapter silently advances its cursor after the
 * 120 s cap. It must still fire when playback is running and frames are truly lost.
 */
fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean) {
        if (ok) println("PASS $name") else {
            failures++
            println("FAILED $name")
        }
    }

    check(
        "held playback never times out",
        !DrainGuard.timedOut(elapsedMs = 10 * 60_000L, maxMs = 120_000L, held = true)
    )
    check(
        "held playback does not time out exactly at the cap",
        !DrainGuard.timedOut(elapsedMs = 120_000L, maxMs = 120_000L, held = true)
    )
    check(
        "running playback times out at the cap",
        DrainGuard.timedOut(elapsedMs = 120_001L, maxMs = 120_000L, held = false)
    )
    check(
        "running playback times out exactly at the cap",
        DrainGuard.timedOut(elapsedMs = 120_000L, maxMs = 120_000L, held = false)
    )
    check(
        "running playback does not time out early",
        !DrainGuard.timedOut(elapsedMs = 119_999L, maxMs = 120_000L, held = false)
    )
    check(
        "zero elapsed is not a timeout",
        !DrainGuard.timedOut(elapsedMs = 0L, maxMs = 120_000L, held = false)
    )

    if (failures > 0) kotlin.system.exitProcess(1)
    println("ALL PASS")
}
