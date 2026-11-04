import app.novelspeak.AudioWritePolicy
import app.novelspeak.AudioWritePolicy.Outcome

/**
 * Host-side regression for the PCM writer's AudioTrack.write() decision table.
 *
 *   kotlinc app/src/app/novelspeak/AudioWritePolicy.kt \
 *     tools/audio-write-policy-test/TestMain.kt -include-runtime -d /tmp/audio-write-policy-test.jar
 *   java -jar /tmp/audio-write-policy-test.jar
 *
 * If the local kotlinc cannot find its bundled stdlib:
 *   kotlinc -no-stdlib -classpath "$KOTLIN_STDLIB" ... -d /tmp/audio-write-policy-test.jar
 *   java -cp "/tmp/audio-write-policy-test.jar:$KOTLIN_STDLIB" TestMainKt
 *
 * The important case is a short/zero write while pause() or flush() is in progress. Android
 * documents that as a normal short transfer; it must abort the chunk, not terminate the writer
 * thread (the pre-fix behaviour that left TTS silent after every interrupted pause).
 */
fun main() {
    var failures = 0
    var checks = 0

    fun check(name: String, expected: Outcome, actual: Outcome) {
        checks++
        if (expected == actual) {
            println("PASS $name")
        } else {
            failures++
            println("FAILED $name expected=$expected actual=$actual")
        }
    }

    fun decide(
        returned: Int,
        requested: Int = 100,
        written: Int = 0,
        stopped: Boolean = false,
        epochChanged: Boolean = false
    ) = AudioWritePolicy.classify(returned, requested, written, stopped, epochChanged)

    check("full write completes", Outcome.COMPLETE, decide(returned = 100))
    check("partial write progresses", Outcome.PROGRESS, decide(returned = 40))
    check("zero write waits", Outcome.WAIT, decide(returned = 0))
    check("final partial write completes", Outcome.COMPLETE, decide(returned = 60, written = 40))
    check("negative write is an error", Outcome.ERROR, decide(returned = -6))

    // The regression: pause()/flush() interrupts a blocking write with a short count. It must
    // discard the chunk and keep the writer thread alive, not treat it as a fatal failure.
    check("short write during stop aborts", Outcome.ABORT,
        decide(returned = 40, stopped = true))
    check("zero write during stop aborts", Outcome.ABORT,
        decide(returned = 0, stopped = true))
    check("short write during reset aborts", Outcome.ABORT,
        decide(returned = 40, epochChanged = true))
    check("negative write during stop still aborts", Outcome.ABORT,
        decide(returned = -6, stopped = true))

    // A paused track with no stop/reset is WAIT, so the writer can resume after a call.
    check("paused track waits", Outcome.WAIT, decide(returned = 0, written = 40))

    println(if (failures == 0) "ALL PASS ($checks checks)" else "$failures FAILURES ($checks checks)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
