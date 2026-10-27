import app.novelspeak.AudioExporter
import app.novelspeak.PronunciationRules
import app.novelspeak.ReaderStyle
import app.novelspeak.WavSink
import app.novelspeak.css
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Host-side harness for the v1.2 feature modules (no Android runtime needed):
 * PronunciationRules, ReaderStyle, AudioExporter.WavSink/AudioExporter.
 *
 *   kotlinc app/src/app/novelspeak/PronunciationRules.kt app/src/app/novelspeak/ReaderStyle.kt app/src/app/novelspeak/AudioExporter.kt tools/feature-test/TestMain.kt -include-runtime -d /tmp/feature-test.jar
 *   java -jar /tmp/feature-test.jar
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
            println("FAILED $name ${detail}")
        }
    }
    fun eq(name: String, actual: Any?, expected: Any?) =
        check(name, actual == expected, "expected=<$expected> actual=<$actual>")

    // =====================================================================================
    // PronunciationRules
    // =====================================================================================
    val parsed = PronunciationRules.parse(
        "# comment line\n" +
            "\n" +
            "Shen Wei=Shen Way\n" +
            "broken-line-without-equals\n" +
            "=empty source\n" +
            "A=B#not-a-comment\n" +
            "  spaced = spaced out  \n" +
            "~=\n"
    )
    eq("parse rule count", parsed.size, 4)
    eq("parse first source", parsed[0].from, "Shen Wei")
    eq("parse first target", parsed[0].to, "Shen Way")
    eq("parse keeps hash in replacement", parsed[1], PronunciationRules.Rule("A", "B#not-a-comment"))
    eq("parse trims both sides", parsed[2], PronunciationRules.Rule("spaced", "spaced out"))
    eq("parse allows empty replacement", parsed[3], PronunciationRules.Rule("~", ""))

    val crlf = PronunciationRules.parse("Cat=Dog\r\nEel=Feel\r\n")
    eq("parse handles CRLF count", crlf.size, 2)
    eq("parse handles CRLF value", crlf[0], PronunciationRules.Rule("Cat", "Dog"))

    val lastWins = PronunciationRules.parse("cat=dog\nCAT=feline\n")
    eq("parse dedupe case-insensitive (last wins) size", lastWins.size, 1)
    eq("parse dedupe case-insensitive target", lastWins[0].to, "feline")

    eq("parse equals inside replacement", PronunciationRules.parse("a=b=c")[0].to, "b=c")
    eq("parse null", PronunciationRules.parse(null).size, 0)
    eq("parse empty", PronunciationRules.parse("").size, 0)
    eq("parse control chars skipped", PronunciationRules.parse("a=\u0001b").size, 0)

    val many = buildString {
        for (i in 0 until 250) appendLine("w$i=z$i")
    }
    eq("parse caps at MAX_RULES", PronunciationRules.parse(many).size, PronunciationRules.MAX_RULES)

    val longFirst = PronunciationRules.apply(
        "New York and York",
        PronunciationRules.parse("New York=NYC\nYork=Y")
    )
    eq("apply longest source first", longFirst, "NYC and Y")

    val boundary = PronunciationRules.apply(
        "cat cats scatter Cat.",
        PronunciationRules.parse("cat=dog")
    )
    eq("apply word boundary + case-insensitive", boundary, "dog cats scatter dog.")

    val punct = PronunciationRules.apply(
        "Main St. then St. Louis",
        PronunciationRules.parse("St.=Street")
    )
    eq("apply punctuation source matches anywhere", punct, "Main Street then Street Louis")

    val noCascade = PronunciationRules.apply("a b", PronunciationRules.parse("a=b\nb=c"))
    eq("apply single pass (no cascades)", noCascade, "b c")

    eq(
        "apply caps replacement length",
        PronunciationRules.apply("x", listOf(PronunciationRules.Rule("x", "y".repeat(500)))).length,
        PronunciationRules.MAX_REPLACEMENT_CHARS
    )
    eq("apply empty text", PronunciationRules.apply("", listOf(PronunciationRules.Rule("a", "b"))), "")
    eq("apply no rules", PronunciationRules.apply("abc", emptyList()), "abc")
    eq(
        "apply ignores empty source",
        PronunciationRules.apply("abc", listOf(PronunciationRules.Rule("", "z"))),
        "abc"
    )
    eq(
        "apply caps rules at MAX_RULES",
        PronunciationRules.apply(
            "w0 w249",
            (0 until 250).map { PronunciationRules.Rule("w$it", "z$it") }
        ),
        "z0 w249"
    )
    eq(
        "applyRules convenience",
        PronunciationRules.applyRules("Shen Wei", "Shen Wei=Shen Way"),
        "Shen Way"
    )
    eq(
        "apply unicode source",
        PronunciationRules.apply("\u6797 said", PronunciationRules.parse("\u6797=Lin")),
        "Lin said"
    )
    eq("apply empty source text + no crash", PronunciationRules.applyRules("", "a=b"), "")

    eq("validate empty", PronunciationRules.validate(""), null)
    eq("validate comment only", PronunciationRules.validate("# hi"), null)
    eq("validate good rules", PronunciationRules.validate("Shen Wei=Shen Way"), null)
    val badRule = PronunciationRules.validate("no-equals-here")
    check("validate reports line", badRule != null && badRule.contains("line 1"), "got=<$badRule>")
    val emptySource = PronunciationRules.validate("=oops")
    check("validate reports empty source", emptySource != null && emptySource.contains("source"))
    val controlRule = PronunciationRules.validate("a=\u0002b")
    check(
        "validate reports control chars",
        controlRule != null && controlRule.contains("control")
    )

    val roundTrip = PronunciationRules.parse("One=1\nTwo=2")
    eq(
        "serialize round trip",
        PronunciationRules.serialize(roundTrip),
        "One=1\nTwo=2"
    )
    eq("toPairs", PronunciationRules.toPairs(roundTrip), listOf("One" to "1", "Two" to "2"))
    eq("fromPairs", PronunciationRules.fromPairs(listOf(" " to "x", "A" to "B")), listOf(PronunciationRules.Rule("A", "B")))

    // =====================================================================================
    // ReaderStyle
    // =====================================================================================
    fun cssVar(css: String, name: String): String? {
        val marker = "$name:"
        val start = css.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val end = css.indexOf(';', valueStart)
        return if (end < 0) css.substring(valueStart) else css.substring(valueStart, end)
    }

    val defaultCss = ReaderStyle.buildCss(ReaderStyle())
    eq("css default font size", cssVar(defaultCss, "--ns-font-size"), "17px")
    eq(
        "css default font family",
        cssVar(defaultCss, "--ns-font-family"),
        "Georgia,'Times New Roman',Times,serif"
    )
    eq("css default line height", cssVar(defaultCss, "--ns-line-height"), "1.7")
    eq("css default width", cssVar(defaultCss, "--ns-width"), "760px")
    eq("css dark background", cssVar(defaultCss, "--ns-bg"), "#0F1115")
    eq("css dark foreground", cssVar(defaultCss, "--ns-fg"), "#E9EDF2")
    eq("css highlight bar", cssVar(defaultCss, "--ns-highlight-bar"), "#F6C453")
    check("css has :root block", defaultCss.startsWith(":root{"))
    check(
        "css has body override",
        defaultCss.contains("html.ns-reader body{") &&
            defaultCss.contains("font-family:var(--ns-font-family)!important")
    )
    check(
        "css has paragraph override",
        defaultCss.contains("html.ns-reader p,") &&
            defaultCss.contains("font-size:var(--ns-font-size)!important")
    )
    check("css has active highlight rule", defaultCss.contains("html.ns-reader [data-ns].ns-active"))
    eq(
        "css braces balanced",
        defaultCss.count { it == '{' },
        defaultCss.count { it == '}' }
    )
    check("css has no NaN", !defaultCss.contains("NaN", ignoreCase = true))
    check("css has no Infinity", !defaultCss.contains("Infinity", ignoreCase = true))

    eq(
        "css scale 150",
        cssVar(ReaderStyle.buildCss(ReaderStyle(fontScalePct = 150)), "--ns-font-size"),
        "25.5px"
    )
    eq(
        "css scale 125 keeps two decimals",
        cssVar(ReaderStyle.buildCss(ReaderStyle(fontScalePct = 125)), "--ns-font-size"),
        "21.25px"
    )
    eq(
        "css scale clamps low",
        cssVar(ReaderStyle.buildCss(ReaderStyle(fontScalePct = 1)), "--ns-font-size"),
        "13.6px"
    )
    eq(
        "css scale clamps high",
        cssVar(ReaderStyle.buildCss(ReaderStyle(fontScalePct = 999)), "--ns-font-size"),
        "27.2px"
    )
    eq("effective font px 125", ReaderStyle.effectiveFontSizePx(ReaderStyle(fontScalePct = 125)), 21.25)

    val sansCss = ReaderStyle.buildCss(ReaderStyle(fontFamily = ReaderStyle.FAMILY_SANS))
    check(
        "css sans stack",
        cssVar(sansCss, "--ns-font-family")!!.contains("sans-serif") &&
            !cssVar(sansCss, "--ns-font-family")!!.contains("Georgia")
    )
    val dyslexicCss = ReaderStyle.buildCss(ReaderStyle(fontFamily = ReaderStyle.FAMILY_DYSLEXIC))
    check(
        "css dyslexic stack mentions OpenDyslexic",
        cssVar(dyslexicCss, "--ns-font-family")!!.contains("OpenDyslexic")
    )
    check(
        "css unknown family falls back to serif",
        ReaderStyle.buildCss(ReaderStyle(fontFamily = "comic")).contains("Georgia")
    )
    eq("fontStack serif", ReaderStyle.fontStack("serif"), "Georgia,'Times New Roman',Times,serif")
    check("fontStack unknown is serif", ReaderStyle.fontStack("bogus").contains("serif"))

    eq(
        "css sepia background",
        cssVar(ReaderStyle.buildCss(ReaderStyle(theme = ReaderStyle.THEME_SEPIA)), "--ns-bg"),
        "#F4ECD8"
    )
    eq(
        "css light background",
        cssVar(ReaderStyle.buildCss(ReaderStyle(theme = ReaderStyle.THEME_LIGHT)), "--ns-bg"),
        "#FFFFFF"
    )
    eq(
        "css unknown theme falls back dark",
        cssVar(ReaderStyle.buildCss(ReaderStyle(theme = "neon")), "--ns-bg"),
        "#0F1115"
    )

    eq(
        "css line height 120",
        cssVar(ReaderStyle.buildCss(ReaderStyle(lineHeightPct = 120)), "--ns-line-height"),
        "1.2"
    )
    eq(
        "css line height 125",
        cssVar(ReaderStyle.buildCss(ReaderStyle(lineHeightPct = 125)), "--ns-line-height"),
        "1.25"
    )
    eq(
        "css line height 200",
        cssVar(ReaderStyle.buildCss(ReaderStyle(lineHeightPct = 200)), "--ns-line-height"),
        "2"
    )
    eq(
        "css line height clamps low",
        cssVar(ReaderStyle.buildCss(ReaderStyle(lineHeightPct = 10)), "--ns-line-height"),
        "1.2"
    )
    eq(
        "css line height clamps high",
        cssVar(ReaderStyle.buildCss(ReaderStyle(lineHeightPct = 999)), "--ns-line-height"),
        "2"
    )

    eq(
        "css width explicit",
        cssVar(ReaderStyle.buildCss(ReaderStyle(widthPx = 900)), "--ns-width"),
        "900px"
    )
    eq(
        "css width auto",
        cssVar(ReaderStyle.buildCss(ReaderStyle(widthPx = 0)), "--ns-width"),
        "760px"
    )
    eq(
        "css width clamps low",
        cssVar(ReaderStyle.buildCss(ReaderStyle(widthPx = 100)), "--ns-width"),
        "480px"
    )
    eq(
        "css width clamps high",
        cssVar(ReaderStyle.buildCss(ReaderStyle(widthPx = 5000)), "--ns-width"),
        "1200px"
    )
    eq("effective width auto", ReaderStyle.effectiveWidthPx(ReaderStyle(widthPx = 0)), 760)

    eq(
        "sanitized clamps everything",
        ReaderStyle(999, "bogus", "bogus", 999, -4).sanitized(),
        ReaderStyle(160, "serif", "dark", 200, 0)
    )
    eq(
        "of() sanitizes",
        ReaderStyle.of(50, "sans", "light", 50, 5000),
        ReaderStyle(80, "sans", "light", 120, 1200)
    )
    check("css extension works", ReaderStyle().css().contains("--ns-font-size:17px"))

    // =====================================================================================
    // AudioExporter / WavSink
    // =====================================================================================
    fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    fun ascii(b: ByteArray, off: Int, len: Int): String =
        String(b, off, len, Charsets.US_ASCII)

    val baos = ByteArrayOutputStream()
    val sink = WavSink(baos)
    check("wav sink exact header mode", sink.exactHeaderSizes)
    eq("wav sample rate default", sink.sampleRate, 24000)
    eq("wav channels default", sink.channels, 1)
    eq("wav bits default", sink.bits, 16)
    val samples = ShortArray(100) { it.toShort() }
    sink.write(samples)
    eq("wav pcm bytes counted", sink.pcmBytes, 200L)
    sink.close()
    val wav = baos.toByteArray()
    eq("wav total size", wav.size, WavSink.HEADER_BYTES + 200)
    eq("wav RIFF magic", ascii(wav, 0, 4), "RIFF")
    eq("wav WAVE magic", ascii(wav, 8, 4), "WAVE")
    eq("wav fmt magic", ascii(wav, 12, 4), "fmt ")
    eq("wav data magic", ascii(wav, 36, 4), "data")
    eq("wav riff size", u32(wav, 4), (wav.size - 8).toLong())
    eq("wav fmt chunk size", u32(wav, 16), 16L)
    eq("wav audio format PCM", u16(wav, 20), 1)
    eq("wav channels field", u16(wav, 22), 1)
    eq("wav sample rate field", u32(wav, 24), 24000L)
    eq("wav byte rate field", u32(wav, 28), 48000L)
    eq("wav block align field", u16(wav, 32), 2)
    eq("wav bits field", u16(wav, 34), 16)
    eq("wav data size field", u32(wav, 40), 200L)
    eq("wav first sample low byte", wav[44].toInt() and 0xFF, 0)
    eq("wav second sample low byte", wav[46].toInt() and 0xFF, 1)

    val le = ByteArrayOutputStream()
    val leSink = WavSink(le)
    leSink.write(shortArrayOf(0x1234, (-1).toShort(), 0x7FFF))
    leSink.close()
    val leBytes = le.toByteArray()
    eq("wav LE 0x1234 low", leBytes[44].toInt() and 0xFF, 0x34)
    eq("wav LE 0x1234 high", leBytes[45].toInt() and 0xFF, 0x12)
    eq("wav LE -1 low", leBytes[46].toInt() and 0xFF, 0xFF)
    eq("wav LE -1 high", leBytes[47].toInt() and 0xFF, 0xFF)

    val empty = ByteArrayOutputStream()
    val emptySink = WavSink(empty)
    emptySink.write(ShortArray(0))
    emptySink.close()
    val emptyBytes = empty.toByteArray()
    eq("wav empty size", emptyBytes.size, 44)
    eq("wav empty riff size", u32(emptyBytes, 4), 36L)
    eq("wav empty data size", u32(emptyBytes, 40), 0L)

    val idem = ByteArrayOutputStream()
    val idemSink = WavSink(idem)
    idemSink.write(shortArrayOf(1, 2))
    idemSink.close()
    val afterFirst = idem.size()
    idemSink.close()
    eq("wav close idempotent", idem.size(), afterFirst)

    val multi = ByteArrayOutputStream()
    val multiSink = WavSink(multi, sampleRate = 16000, channels = 2, bits = 16)
    multiSink.write(ShortArray(100) { 10 })
    multiSink.writePcm16(ByteArray(40) { 4 })
    multiSink.close()
    val multiBytes = multi.toByteArray()
    eq("wav second write sums sizes", u32(multiBytes, 40), 240L)
    eq("wav custom sample rate", u32(multiBytes, 24), 16000L)
    eq("wav stereo channels", u16(multiBytes, 22), 2)
    eq("wav stereo block align", u16(multiBytes, 32), 4)
    eq("wav stereo byte rate", u32(multiBytes, 28), 64000L)

    val clamped = ByteArrayOutputStream()
    val clampedSink = WavSink(clamped, sampleRate = -5, channels = 0, bits = 7)
    clampedSink.writePcm16(byteArrayOf(1, 2))
    clampedSink.close()
    val clampedBytes = clamped.toByteArray()
    eq("wav clamps sample rate", u32(clampedBytes, 24), 24000L)
    eq("wav clamps channels", u16(clampedBytes, 22), 1)
    eq("wav clamps bits", u16(clampedBytes, 34), 16)
    eq("wav writePcm16 counts bytes", u32(clampedBytes, 40), 2L)

    val partial = ByteArrayOutputStream()
    val partialSink = WavSink(partial)
    partialSink.writePcm16(byteArrayOf(1, 2, 3, 4), 3)
    partialSink.writePcm16(byteArrayOf(9), 0)
    eq("wav writePcm16 partial len", partialSink.pcmBytes, 3L)
    partialSink.writePcm16(byteArrayOf(5, 6), 99)
    eq("wav writePcm16 clamps len", partialSink.pcmBytes, 5L)
    partialSink.close()

    val duration = ByteArrayOutputStream()
    val durationSink = WavSink(duration)
    durationSink.write(ShortArray(24000))
    eq("wav duration ms for 1 s", durationSink.durationMs(), 1000L)
    eq("wav pcm bytes for 1 s", durationSink.pcmBytes, 48000L)
    durationSink.close()

    var afterCloseThrew = false
    try {
        durationSink.write(shortArrayOf(1))
    } catch (expected: IllegalStateException) {
        afterCloseThrew = true
    }
    check("wav write after close throws", afterCloseThrew)

    class TrackingStream : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var closedBySink = false
        override fun write(b: Int) {
            bytes.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            bytes.write(b, off, len)
        }
        override fun close() {
            closedBySink = true
        }
    }

    val nonSeek = TrackingStream()
    val nonSeekSink = WavSink(nonSeek, closeUnderlying = false)
    check("wav non-seekable not exact", !nonSeekSink.exactHeaderSizes)
    nonSeekSink.write(shortArrayOf(1, 2, 3))
    nonSeekSink.close()
    val nonSeekBytes = nonSeek.bytes.toByteArray()
    eq("wav non-seekable riff magic", ascii(nonSeekBytes, 0, 4), "RIFF")
    eq("wav non-seekable unknown sizes", u32(nonSeekBytes, 40), 0xFFFFFFFFL)
    eq("wav non-seekable payload size", nonSeekBytes.size, 44 + 6)
    check("wav closeUnderlying=false leaves stream open", !nonSeek.closedBySink)

    eq(
        "wav estimated bytes 1 h",
        WavSink.estimatedBytes(3_600_000L),
        172_800_044L
    )
    eq("wav estimated bytes empty", WavSink.estimatedBytes(0L), 44L)
    eq("exporter estimated matches sink", AudioExporter.estimatedBytes(3_600_000L), 172_800_044L)
    eq("exporter human size hours", AudioExporter.humanSize(172_800_044L), "165 MB")
    eq("exporter human size bytes", AudioExporter.humanSize(512L), "512 B")
    eq("exporter human size kb", AudioExporter.humanSize(1536L), "1.5 KB")

    // =====================================================================================
    println()
    if (failures > 0) {
        println("$failures of $checks CHECKS FAILED")
        kotlin.system.exitProcess(1)
    }
    println("ALL $checks CHECKS PASS")
}
