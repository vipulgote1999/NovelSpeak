import app.novelspeak.VoiceCatalog

/**
 * Host-side checks for the static voice catalog. No Android runtime needed.
 *
 *   kotlinc app/src/app/novelspeak/VoiceCatalog.kt tools/voice-catalog-test/TestMain.kt \
 *     -include-runtime -d /tmp/voice-catalog-test.jar
 *   java -jar /tmp/voice-catalog-test.jar
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

    val st = VoiceCatalog.SUPERTONIC
    check("supertonic has 10 styles", st.size == 10, "size=${st.size}")
    check("first is F1 female", st[0].id == "0" && st[0].label.startsWith("F1") && st[0].gender == "female")
    check("second is F2 female", st[1].id == "1" && st[1].label.startsWith("F2"))
    check("third is F3 female", st[2].id == "2" && st[2].label.startsWith("F3"))
    check("sixth is M1 male", st[5].id == "5" && st[5].label.startsWith("M1") && st[5].gender == "male")
    check("last is M5 male", st[9].id == "9" && st[9].label.startsWith("M5") && st[9].gender == "male")
    check("five female and five male", st.count { it.isFemale } == 5 && st.count { it.isMale } == 5)
    check("ids are unique", st.map { it.id }.toSet().size == st.size)

    val kokoro = VoiceCatalog.KOKORO
    check("kokoro has 11 voices", kokoro.size == 11, "size=${kokoro.size}")
    check("kokoro first is Heart", kokoro[0].label.startsWith("Heart"))
    check("kokoro sixth is Adam", kokoro[5].label.startsWith("Adam"))
    check("kokoro has female and male", kokoro.any { it.isFemale } && kokoro.any { it.isMale })
    check("kokoro ids are unique", kokoro.map { it.id }.toSet().size == kokoro.size)

    check("label resolves F2 for sid 1", VoiceCatalog.label("supertonic", "1").startsWith("F2"))
    check("label resolves M1 for sid 5", VoiceCatalog.label("supertonic", "5").startsWith("M1"))
    check("unknown label falls back to id", VoiceCatalog.label("supertonic", "99") == "99")

    val charVoices = VoiceCatalog.characterVoices("supertonic", "0")
    check("narrator voice is excluded", charVoices.none { it.id == "0" })
    check("nine character slots for supertonic", charVoices.size == 9, "size=${charVoices.size}")

    println("$checks checks, $failures failure(s)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
