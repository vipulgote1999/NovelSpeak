import app.novelspeak.cast.VoiceSelection

/**
 * Host-side checks for per-task voice selection. The engine tasks use these rules so a queued
 * sentence cannot inherit a voice that was selected after it was queued.
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

    check("supertonic task voice wins", VoiceSelection.speakerId("7", 0) == 7)
    check("supertonic fallback when absent", VoiceSelection.speakerId(null, 5) == 5)
    check("malformed task voice falls back", VoiceSelection.speakerId("F2", 9) == 9)
    check("system task voice wins", VoiceSelection.systemVoice("en-us-x-sfg#female_1", "old") == "en-us-x-sfg#female_1")
    check("system blank task voice falls back", VoiceSelection.systemVoice("", "old") == "old")
    check("system null task voice falls back", VoiceSelection.systemVoice(null, "old") == "old")
    check("system null fallback stays null", VoiceSelection.systemVoice(null, null) == null)

    println("$checks checks, $failures failure(s)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
