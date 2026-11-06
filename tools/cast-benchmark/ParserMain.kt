import app.novelspeak.cast.CastBlock
import app.novelspeak.cast.CastChunker
import app.novelspeak.cast.DialogueParser
import java.io.File

/**
 * Prints the exact TTS chunks the Story Cast reader would queue for a chapter.
 *
 * The app parses paragraph-level text, then re-splits each segment with
 * [SentenceSplitter] before synthesis. This CLI mirrors that so the benchmark measures
 * what the device actually speaks, not a hand-written approximation.
 *
 *   kotlinc app/src/app/novelspeak/SentenceSplitter.kt \
 *     app/src/app/novelspeak/cast/CastModels.kt \
 *     app/src/app/novelspeak/cast/DialogueParser.kt \
 *     tools/cast-benchmark/ParserMain.kt -include-runtime -d /tmp/parser.jar
 *   java -jar /tmp/parser.jar tools/cast-parser-test/fixtures/unattributed-scene.txt
 */
private fun escape(value: String): String {
    val sb = StringBuilder(value.length + 16)
    for (c in value) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.toString()
}

fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: "tools/cast-parser-test/fixtures/unattributed-scene.txt"
    val maxChars = args.getOrNull(1)?.toIntOrNull() ?: 180
    val paragraphs = File(path).readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapIndexed { i, s -> CastBlock(s, i) }

    val analysis = DialogueParser.analyze(paragraphs)
    val chunks = StringBuilder()
    var count = 0
    for (segment in analysis.segments) {
        for (chunk in CastChunker.chunks(segment.text, maxChars)) {
            val start = if (segment.isDialogue && segment.charStart >= 0) {
                segment.charStart + chunk.start
            } else {
                -1
            }
            val end = if (start >= 0) start + (chunk.end - chunk.start) else -1
            if (chunks.isNotEmpty()) chunks.append(",\n")
            chunks.append("  {\"blockIndex\":").append(segment.blockIndex)
                .append(",\"type\":\"").append(segment.type.name).append('"')
                .append(",\"speakerId\":\"").append(escape(segment.speakerId)).append('"')
                .append(",\"confidence\":\"").append(segment.confidence.name).append('"')
                .append(",\"charStart\":").append(start)
                .append(",\"charEnd\":").append(end)
                .append(",\"text\":\"").append(escape(chunk.text)).append("\"}")
            count++
        }
    }
    val members = analysis.members.joinToString(",") { m ->
        "{\"id\":\"${escape(m.id)}\",\"name\":\"${escape(m.name)}\"," +
            "\"gender\":\"${m.gender.name}\",\"dialogueLines\":${m.dialogueLines}}"
    }
    println("{\"source\":\"${escape(path)}\",\"unresolved\":${analysis.unresolved}," +
        "\"chunkCount\":$count,\"members\":[$members],\"chunks\":[\n$chunks\n]}")
}
