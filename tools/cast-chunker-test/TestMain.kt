import app.novelspeak.cast.CastChunker

/**
 * Host-side checks for cast TTS chunking.
 *
 *   kotlinc app/src/app/novelspeak/SentenceSplitter.kt \
 *     app/src/app/novelspeak/cast/CastChunker.kt \
 *     tools/cast-chunker-test/TestMain.kt -include-runtime -d /tmp/cast-chunker-test.jar
 *   java -jar /tmp/cast-chunker-test.jar
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

    // A short vocative must not become its own utterance.
    val tomas = "\"Tomas. I told you to wait outside.\""
    val tomasChunks = CastChunker.chunks(tomas)
    check("short vocative merges into the next sentence", tomasChunks.size == 1, tomasChunks.joinToString(" | ") { it.text })
    check("merged text keeps both sentences", tomasChunks.firstOrNull()?.text?.contains("Tomas.") == true &&
        tomasChunks.firstOrNull()?.text?.contains("waited") != true &&
        tomasChunks.firstOrNull()?.text?.contains("wait outside") == true)
    check("merged offsets cover the start", tomasChunks.firstOrNull()?.start == 0, tomasChunks.firstOrNull()?.start.toString())
    check("merged offsets cover the end", tomasChunks.firstOrNull()?.end == tomas.length, tomasChunks.firstOrNull()?.end.toString())

    // A normal sentence stays one chunk.
    val single = "The lantern flickered once in the dark."
    val singleChunks = CastChunker.chunks(single)
    check("single sentence stays one chunk", singleChunks.size == 1 && singleChunks[0].text == single)

    // Long dialogue is still split at sentence boundaries.
    val long = "One. " + "This sentence is deliberately long enough to need its own chunk when the limit is low. " +
        "Another sentence follows it and must remain separately speakable."
    val longChunks = CastChunker.chunks(long, 80)
    check("long text splits into multiple chunks", longChunks.size >= 2, longChunks.size.toString())
    check("no chunk exceeds the cap", longChunks.all { it.text.length <= 80 }, longChunks.maxOfOrNull { it.text.length }.toString())
    check("every chunk has non-empty text", longChunks.all { it.text.isNotBlank() })

    // Offsets are usable for read-along highlighting.
    val quote = "\"You said to.\""
    val q = CastChunker.chunks(quote).single()
    check("offsets match the source substring", quote.substring(q.start, q.end) == q.text, quote.substring(q.start, q.end))

    // Empty input never creates a chunk.
    check("blank input", CastChunker.chunks("   ").isEmpty())

    println("$checks checks, $failures failure(s)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
