import app.novelspeak.cast.CastAnalysis
import app.novelspeak.cast.CastBlock
import app.novelspeak.cast.CastMember
import app.novelspeak.cast.Confidence
import app.novelspeak.cast.DialogueParser
import app.novelspeak.cast.Gender
import java.io.File

/**
 * Host-side checks for the deterministic cast parser. No Android runtime needed.
 *
 *   kotlinc app/src/app/novelspeak/cast/CastModels.kt \
 *     app/src/app/novelspeak/cast/DialogueParser.kt \
 *     tools/cast-parser-test/TestMain.kt -include-runtime -d /tmp/cast-parser-test.jar
 *   java -jar /tmp/cast-parser-test.jar
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

    fun blocks(path: String): List<CastBlock> =
        File(path).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { i, s -> CastBlock(s, i) }

    fun analyze(path: String, existing: List<CastMember> = emptyList()): CastAnalysis =
        DialogueParser.analyze(blocks(path), existing)

    fun nameOf(a: CastAnalysis, id: String): String =
        if (id == "NARRATOR" || id == "UNKNOWN") id
        else a.members.firstOrNull { it.id == id }?.name ?: id

    fun speakerOf(a: CastAnalysis, needle: String): String =
        a.segments.firstOrNull { it.isDialogue && it.text.contains(needle, ignoreCase = true) }
            ?.let { nameOf(a, it.speakerId) } ?: "<missing:$needle>"

    fun confidenceOf(a: CastAnalysis, needle: String): Confidence? =
        a.segments.firstOrNull { it.isDialogue && it.text.contains(needle, ignoreCase = true) }
            ?.confidence

    fun coversInput(a: CastAnalysis, input: List<CastBlock>): Boolean {
        fun nonWs(s: String) = s.filterNot { it.isWhitespace() }
        return nonWs(input.joinToString("") { it.text }) ==
            nonWs(a.segments.joinToString("") { it.text })
    }

    // ------------------------------------------------------------------ explicit tags
    val pp = analyze("tools/cast-parser-test/fixtures/pride-and-prejudice-ch1.txt")
    val ppDialogue = pp.segments.filter { it.isDialogue }
    check("austen has dialogue", ppDialogue.size >= 10, "dialogue=${ppDialogue.size}")
    check(
        "austen speakers are the Bennets",
        ppDialogue.map { nameOf(pp, it.speakerId) }.all { it.contains("Bennet") },
        ppDialogue.map { nameOf(pp, it.speakerId) }.distinct().toString()
    )
    check("austen has no unknown speakers", ppDialogue.none { it.speakerId == "UNKNOWN" })
    check("austen coverage", coversInput(pp, blocks("tools/cast-parser-test/fixtures/pride-and-prejudice-ch1.txt")))

    check("austen first line is Mrs Bennet", speakerOf(pp, "My dear Mr. Bennet,") == "Mrs. Bennet", speakerOf(pp, "My dear Mr. Bennet,"))
    check("austen reply is Mr Bennet", speakerOf(pp, "You want to tell me") == "Mr. Bennet", speakerOf(pp, "You want to tell me"))
    check("austen Bingley is Mrs Bennet", speakerOf(pp, "Bingley.") == "Mrs. Bennet", speakerOf(pp, "Bingley."))
    check("austen married/single is Mr Bennet", speakerOf(pp, "Is he married or single") == "Mr. Bennet", speakerOf(pp, "Is he married or single"))
    check("austen how-so is Mr Bennet", speakerOf(pp, "How so?") == "Mr. Bennet", speakerOf(pp, "How so?"))
    check("austen tiresome is Mrs Bennet", speakerOf(pp, "how can you be so tiresome") == "Mrs. Bennet", speakerOf(pp, "how can you be so tiresome"))
    check("austen design is Mrs Bennet", speakerOf(pp, "Design! nonsense") == "Mrs. Bennet", speakerOf(pp, "Design! nonsense"))
    check("austen no-occasion is Mr Bennet", speakerOf(pp, "I see no occasion") == "Mr. Bennet", speakerOf(pp, "I see no occasion"))
    check(
        "austen characters are confidently attributed",
        ppDialogue.map { it.speakerId }.distinct().all { id ->
            pp.members.firstOrNull { it.id == id }?.confidence != Confidence.LOW
        },
        pp.members.joinToString { "${it.name}=${it.confidence}" }
    )

    // ------------------------------------------------------------------ unattributed / aliases
    val cast = listOf(
        CastMember("rennick", "Rennick", listOf("marshal rennick", "the marshal"), Gender.MALE),
        CastMember("mara", "Mara", listOf("the woman in the grey coat"), Gender.FEMALE),
        CastMember("tomas", "Tomas", listOf("the young man"), Gender.MALE)
    )
    val scenePath = "tools/cast-parser-test/fixtures/unattributed-scene.txt"
    val scene = analyze(scenePath, cast)
    check("scene coverage", coversInput(scene, blocks(scenePath)))
    check("scene came-alone is Rennick", speakerOf(scene, "You came alone") == "Rennick", speakerOf(scene, "You came alone"))
    check("scene you-said-to is Mara", speakerOf(scene, "You said to") == "Mara", speakerOf(scene, "You said to"))
    check("scene not-bring-boy is Rennick", speakerOf(scene, "I said not to bring") == "Rennick", speakerOf(scene, "I said not to bring"))
    check("scene isn't-a-boy is Mara", speakerOf(scene, "He isn't a boy") == "Mara", speakerOf(scene, "He isn't a boy"))
    check("scene right-here is unknown", speakerOf(scene, "He's right here") == "UNKNOWN", speakerOf(scene, "He's right here"))
    check("scene right-here is low confidence", confidenceOf(scene, "He's right here") == Confidence.LOW, confidenceOf(scene, "He's right here").toString())
    check("scene Tomas line is Rennick", speakerOf(scene, "Tomas. I told you") == "Rennick", speakerOf(scene, "Tomas. I told you"))
    check("scene wouldn't line is Tomas", speakerOf(scene, "And I told you I wouldn't") == "Tomas", speakerOf(scene, "And I told you I wouldn't"))
    check("scene finished line is Mara", speakerOf(scene, "If you two are finished") == "Mara", speakerOf(scene, "If you two are finished"))
    check("scene whose is Rennick", speakerOf(scene, "Whose?") == "Rennick", speakerOf(scene, "Whose?"))
    check("scene mine is unknown", speakerOf(scene, "Mine,") == "UNKNOWN", speakerOf(scene, "Mine,"))
    check("scene mine is low confidence", confidenceOf(scene, "Mine,") == Confidence.LOW, confidenceOf(scene, "Mine,").toString())

    // ------------------------------------------------------------------ automatic discovery
    // No pre-seeded cast: the parser must still find the cast from tags, vocatives and
    // descriptions, and must leave only the genuinely unattributable lines unresolved.
    val auto = analyze(scenePath)
    check("auto scene discovers Rennick", auto.members.any { it.name.contains("Rennick") }, auto.members.joinToString { it.name })
    check("auto scene discovers Mara", auto.members.any { it.name == "Mara" }, auto.members.joinToString { it.name })
    check("auto scene discovers Tomas", auto.members.any { it.name == "Tomas" }, auto.members.joinToString { it.name })
    check("auto scene you-said-to is Mara", speakerOf(auto, "You said to") == "Mara", speakerOf(auto, "You said to"))
    check("auto scene wouldn't is Tomas", speakerOf(auto, "And I told you I wouldn't") == "Tomas", speakerOf(auto, "And I told you I wouldn't"))
    check("auto scene unresolved is two", auto.unresolved == 2, auto.unresolved.toString())
    check("auto scene coverage", coversInput(auto, blocks(scenePath)))
    val rennick = auto.members.firstOrNull { it.name == "Marshal Rennick" }
    val maraAuto = auto.members.firstOrNull { it.name == "Mara" }
    val tomasAuto = auto.members.firstOrNull { it.name == "Tomas" }
    check("rennick has a preview line", rennick?.sampleLine?.contains("Tomas") == true, rennick?.sampleLine ?: "")
    check("mara has a preview line", maraAuto?.sampleLine?.contains("body in the stable") == true, maraAuto?.sampleLine ?: "")
    check("tomas has a preview line", tomasAuto?.sampleLine?.contains("wouldn't") == true, tomasAuto?.sampleLine ?: "")
    val longLine = "This is a deliberately long narration line that must be shortened at a word boundary " +
        "so a voice preview never cuts a word in half or speaks an unfinished sentence."
    val preview = DialogueParser.previewLine(longLine, 80)
    check("preview line respects the cap", preview.length <= 80, preview.length.toString())
    check("preview line ends cleanly", preview.endsWith("."), preview)
    check("preview line keeps whole words", !preview.dropLast(1).endsWith(" "))
    check(
        "auto scene confidence is promoted",
        auto.members.filter { it.dialogueLines > 0 }.all { it.confidence != Confidence.LOW },
        auto.members.joinToString { "${it.name}=${it.confidence}" }
    )

    // ------------------------------------------------------------------ alias resolution
    val mara = scene.members.firstOrNull { it.id == "mara" }
    check("existing cast is retained", mara != null && mara.name == "Mara")
    check("alias resolves to Mara", mara?.matches("the woman in the grey coat") == true)

    println("$checks checks, $failures failure(s)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
