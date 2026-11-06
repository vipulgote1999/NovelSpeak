import app.novelspeak.VoiceCatalog
import app.novelspeak.cast.BookCast
import app.novelspeak.cast.CastAnalysis
import app.novelspeak.cast.CastBlock
import app.novelspeak.cast.CastCoordinator
import app.novelspeak.cast.CastIds
import app.novelspeak.cast.CastMember
import app.novelspeak.cast.CastStore
import app.novelspeak.cast.Confidence
import app.novelspeak.cast.Gender
import app.novelspeak.cast.LineFix
import app.novelspeak.cast.SpeechSegment
import app.novelspeak.cast.SegmentType
import app.novelspeak.cast.VoiceCastPlanner
import app.novelspeak.library.InMemoryStorage
import java.io.File

/**
 * Host-side checks for cast persistence and voice planning.
 *
 *   kotlinc app/src/app/novelspeak/library/LibraryFile.kt \
 *     app/src/app/novelspeak/library/LibraryStore.kt \
 *     app/src/app/novelspeak/VoiceCatalog.kt \
 *     app/src/app/novelspeak/cast/CastModels.kt \
 *     app/src/app/novelspeak/cast/CastStore.kt \
 *     app/src/app/novelspeak/cast/VoiceCastPlanner.kt \
 *     tools/cast-store-test/TestMain.kt -include-runtime -d /tmp/cast-store-test.jar
 *   java -jar /tmp/cast-store-test.jar
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

    val storage = InMemoryStorage()
    val store = CastStore(storage)

    val book = BookCast(
        bookKey = "book-1",
        title = "Test Book",
        narratorVoiceId = "0",
        members = listOf(
            CastMember("mrs-bennet", "Mrs. Bennet", listOf("his wife"), Gender.FEMALE, "1", Confidence.HIGH, false, 12),
            CastMember("mr-bennet", "Mr. Bennet", listOf("Bennet"), Gender.MALE, "5", Confidence.HIGH, false, 9)
        )
    )
    store.save(book)
    val loaded = store.load("book-1")
    check("cast round-trip", loaded != null)
    check("cast title", loaded?.title == "Test Book")
    check("cast narrator", loaded?.narratorVoiceId == "0")
    check("cast member count", loaded?.members?.size == 2, loaded?.members?.size.toString())
    check("cast alias round-trip", loaded?.members?.first { it.id == "mrs-bennet" }?.aliases?.contains("his wife") == true)
    check("cast voice round-trip", loaded?.members?.first { it.id == "mr-bennet" }?.voiceId == "5")
    check("cast dialogue count round-trips", loaded?.members?.first { it.id == "mr-bennet" }?.dialogueLines == 9)
    check("cast confidence round-trips", loaded?.members?.first { it.id == "mr-bennet" }?.confidence == Confidence.HIGH,
        loaded?.members?.first { it.id == "mr-bennet" }?.confidence.toString())

    // Merge a chapter analysis into the stored cast.
    val analysis = CastAnalysis(
        segments = listOf(
            SpeechSegment("\"Hello\"", 0, 0, 7, SegmentType.DIALOGUE, "mrs-bennet", Confidence.HIGH)
        ),
        members = listOf(
            CastMember("mrs-bennet", "Mrs. Bennet", listOf("the lady"), Gender.FEMALE, null, Confidence.HIGH, false, 3),
            CastMember("tomas", "Tomas", listOf("the young man"), Gender.MALE, null, Confidence.MEDIUM, false, 2)
        ),
        unresolved = 0
    )
    val merged = store.merge("book-1", "Test Book", analysis, "0")
    check("merge keeps existing members", merged.members.size == 3, merged.members.size.toString())
    check("merge adds new member", merged.members.any { it.id == "tomas" })
    check("merge combines aliases", merged.members.first { it.id == "mrs-bennet" }.aliases.contains("the lady"))
    check("merge keeps existing voice", merged.members.first { it.id == "mrs-bennet" }.voiceId == "1")
    check("merge keeps narrator", merged.narratorVoiceId == "0")
    check("merge does not inflate line counts", merged.members.first { it.id == "mrs-bennet" }.dialogueLines == 12, merged.members.first { it.id == "mrs-bennet" }.dialogueLines.toString())

    // User lock survives a merge.
    store.setVoice("book-1", "mr-bennet", "9")
    val locked = store.load("book-1")?.members?.first { it.id == "mr-bennet" }
    check("setVoice locks", locked?.userLocked == true && locked.voiceId == "9")
    val remerged = store.merge("book-1", "Test Book", analysis, "0")
    check("merge preserves lock", remerged.members.first { it.id == "mr-bennet" }.voiceId == "9")

    // Line corrections are chapter-scoped, replace in place, and survive a save round-trip.
    store.setLineFix("book-1", "ch1", LineFix(4, 12, "mr-bennet"))
    check("line fix round-trips", store.lineFixes("book-1", "ch1") == listOf(LineFix(4, 12, "mr-bennet")))
    check("line fix is chapter-scoped", store.lineFixes("book-1", "ch2").isEmpty())
    store.setLineFix("book-1", "ch1", LineFix(4, 12, "mrs-bennet"))
    check("line fix replaces the same segment", store.lineFixes("book-1", "ch1") == listOf(LineFix(4, 12, "mrs-bennet")))
    store.save(store.load("book-1")!!)
    check("save keeps line fixes", store.lineFixes("book-1", "ch1").size == 1)
    store.clear("book-1")
    check("clear removes line fixes", store.lineFixes("book-1", "ch1").isEmpty())
    check("clear removes the cast", store.load("book-1") == null)

    // Voice planner: narrator excluded, unique until the pool is exhausted, then reuse.
    val cast = BookCast(
        bookKey = "plan",
        narratorVoiceId = "0",
        members = (1..12).map { i ->
            CastMember(
                id = "c$i",
                name = "Character $i",
                gender = if (i % 2 == 0) Gender.FEMALE else Gender.MALE,
                dialogueLines = 20 - i
            )
        }
    )
    val plan = VoiceCastPlanner.plan(cast, VoiceCatalog.SUPERTONIC)
    check("planner assigns every speaker", plan.size == 12, plan.size.toString())
    check("planner never assigns narrator", plan.none { it.voiceId == "0" })
    check("planner uses a voice once before reuse", plan.take(9).map { it.voiceId }.toSet().size == 9)
    check("planner reuses after pool is exhausted", plan.drop(9).any { it.reused })
    check("planner gender-matches the first female", plan.first { it.memberId == "c2" }.voiceId.let { id ->
        VoiceCatalog.SUPERTONIC.first { it.id == id }.gender == "female"
    })
    check("planner gender-matches the first male", plan.first { it.memberId == "c1" }.voiceId.let { id ->
        VoiceCatalog.SUPERTONIC.first { it.id == id }.gender == "male"
    })

    // End-to-end coordinator: parse, persist, plan.
    val coordinator = CastCoordinator(store)
    val result = coordinator.analyze(
        bookKey = "book-2",
        chapterKey = "chapter-1",
        title = "Scene",
        narratorVoiceId = "0",
        family = "supertonic",
        blocks = listOf(
            CastBlock("Mr. Bennet said, \"Hello.\"", 0)
        )
    )
    check("coordinator creates a speaker", result.analysis.segments.any { it.isDialogue })
    check("coordinator assigns a character voice", result.voiceFor("mr-bennet") != null)
    check("coordinator never assigns narrator", result.voiceFor("mr-bennet") != "0")
    check("coordinator names the speaker", result.nameFor("mr-bennet") == "Mr. Bennet")
    check("coordinator persists the cast", store.load("book-2")?.members?.any { it.name == "Mr. Bennet" } == true)

    // Per-line fix: correct one UNKNOWN line and leave the other untouched.
    val sceneBlocks = File("tools/cast-parser-test/fixtures/unattributed-scene.txt")
        .readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapIndexed { i, s -> CastBlock(s, i) }
    fun scene(chapter: String) = coordinator.analyze(
        bookKey = "book-scene",
        chapterKey = chapter,
        title = "The Lantern",
        narratorVoiceId = "0",
        family = "supertonic",
        blocks = sceneBlocks
    )
    val sceneBefore = scene("ch1")
    val unknown = sceneBefore.analysis.segments.first {
        it.isDialogue && it.speakerId == CastIds.UNKNOWN
    }
    check("scene starts with two unresolved lines", sceneBefore.analysis.unresolved == 2,
        sceneBefore.analysis.unresolved.toString())
    store.setLineFix(
        "book-scene", "ch1", LineFix(unknown.blockIndex, unknown.charStart, "tomas")
    )
    val sceneAfter = scene("ch1")
    val fixed = sceneAfter.analysis.segments.first {
        it.blockIndex == unknown.blockIndex && it.charStart == unknown.charStart
    }
    check("line fix changes the speaker", fixed.speakerId == "tomas", fixed.speakerId)
    check("line fix is high confidence", fixed.confidence == Confidence.HIGH, fixed.confidence.toString())
    check("line fix reduces unresolved count", sceneAfter.analysis.unresolved == 1,
        sceneAfter.analysis.unresolved.toString())
    check("line fix keeps a character voice", sceneAfter.voiceFor("tomas") != null)
    check("line fix never steals the narrator", sceneAfter.voiceFor("tomas") != "0")
    check("line fix is not applied to another chapter", scene("ch2").analysis.unresolved == 2,
        scene("ch2").analysis.unresolved.toString())

    // A locked voice wins even when the planner would prefer another.
    val lockedCast = BookCast(
        bookKey = "locked",
        narratorVoiceId = "0",
        members = listOf(
            CastMember("x", "X", gender = Gender.FEMALE, voiceId = "9", userLocked = true, dialogueLines = 5)
        )
    )
    val lockedPlan = VoiceCastPlanner.plan(lockedCast, VoiceCatalog.SUPERTONIC)
    check("locked voice wins", lockedPlan.single().voiceId == "9")

    println("$checks checks, $failures failure(s)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
