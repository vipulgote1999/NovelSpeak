import app.novelspeak.SentenceSplitter
import app.novelspeak.TtsTextCleaner
import java.io.File

/**
 * Host-side harness for the neural-TTS text path. Not part of the APK.
 *
 *   kotlinc app/src/app/novelspeak/SentenceSplitter.kt \
 *     app/src/app/novelspeak/TtsTextCleaner.kt tools/tts-cleaner-test/TestMain.kt \
 *     -include-runtime -d /tmp/tts-cleaner-test.jar
 *   java -jar /tmp/tts-cleaner-test.jar
 *
 * Exit 0 = all checks pass. Any failure prints FAILED and exits 1.
 */
fun main() {
    var failures = 0
    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) println("PASS $name") else {
            failures++
            println("FAILED $name $detail")
        }
    }
    fun clean(raw: String) = TtsTextCleaner.cleanForNeural(raw)

    // ---- punct-only must be skipped (hallucination trigger) ----
    check("skip-ellipsis", clean("...") == null)
    check("skip-dquote", clean("\"") == null)
    check("skip-star", clean("*") == null)
    check("skip-emdash", clean("—") == null)
    check("skip-empty", clean("   ") == null)
    check("skip-bang-only", clean("!!!") == null)

    // ---- stage directions / censored words ----
    check("stage-direction", clean("*Ahh-wooo!!!*") == "Ahh-wooo!", "got=${clean("*Ahh-wooo!!!*")}")
    val sht = clean("'Oh sh*t!'")
    check("censored", sht != null && '*' !in sht && sht.startsWith("Oh sht"), "got=$sht")
    val fck = clean("\"Who the f*ck cares what they are?!\"")
    check("censored2", fck != null && '*' !in fck && fck.endsWith("?"), "got=$fck")

    // ---- quotes are removed entirely (kokoro#365: they book audible frames) ----
    check("strip-dquote", clean("\"Tom!\"") == "Tom!", "got=${clean("\"Tom!\"")}")
    check(
        "strip-curly",
        clean("“Everyone, we need to get out of here now!”") == "Everyone, we need to get out of here now!",
        "got=${clean("“Everyone, we need to get out of here now!”")}"
    )
    check("unbalanced-lead", clean("“What was that?") == "What was that?", "got=${clean("“What was that?")}")
    check(
        "unbalanced-trail",
        clean("That thing didn’t look like any Altered I ever saw!”") ==
            "That thing didn't look like any Altered I ever saw!",
        "got=${clean("That thing didn’t look like any Altered I ever saw!”")}"
    )
    // nested single quotes: apostrophe survives only inside a word
    check(
        "nested-quotes",
        clean("‘He keeps calling them ‘Werewolves’ instead of ‘Altered’ as if he’s certain.") ==
            "He keeps calling them Werewolves instead of Altered as if he's certain.",
        "got=${clean("‘He keeps calling them ‘Werewolves’ instead of ‘Altered’ as if he’s certain.")}"
    )

    // ---- ellipsis / dashes become a pause, not a hard stop or "dash" ----
    check(
        "ellipsis-mid",
        clean("“There are… two of them!”") == "There are, two of them!",
        "got=${clean("“There are… two of them!”")}"
    )
    check(
        "ellipsis-terminal",
        clean("‘Did I just…wet myself…’") == "Did I just, wet myself.",
        "got=${clean("‘Did I just…wet myself…’")}"
    )
    check(
        "emdash-pause",
        clean("I was going to say — never mind.") == "I was going to say, never mind.",
        "got=${clean("I was going to say — never mind.")}"
    )
    check("trailing-dash", clean("ATTACK-!") == "ATTACK!", "got=${clean("ATTACK-!")}")
    check("compound-hyphen", clean("Tier-3 town") == "Tier-3 town.", "got=${clean("Tier-3 town")}")

    // ---- dotted obfuscation residual; acronyms / decimals survive ----
    check("dotted", clean("large ma.s.s of muscle") == "large mass of muscle.", "got=${clean("large ma.s.s of muscle")}")
    check(
        "dotted-letters",
        clean("s.h.i.+ning its claws") == "shining its claws.",
        "got=${clean("s.h.i.+ning its claws")}"
    )
    val usa = clean("The U.S.A. won 3.5 to 2.")
    check("acronym-decimal", usa != null && "U.S.A." in usa && "3.5" in usa, "got=$usa")

    // ---- terminal punctuation guarantee / normal text untouched ----
    check("terminal-dot", clean("He ran through the trees") == "He ran through the trees.")
    val normal = "There were a lot of dangers in a Tier-3 town like Slough."
    check("normal", clean(normal) == normal, "got=${clean(normal)}")

    // ---- end-to-end split -> merge on the chapter fixture ----
    val fixture = File("tools/tts-cleaner-test/sample-chapters.txt")
    check("fixture-present", fixture.isFile)
    if (!fixture.isFile) {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
    val max = 180
    val rawBlocks = ArrayList<String>()
    fixture.forEachLine { line ->
        val t = line.trim()
        if (t.isNotEmpty() && !t.startsWith("#")) rawBlocks.add(t)
    }
    // split each paragraph, then merge across paragraphs exactly like WebExtractor
    val fragments = ArrayList<String>()
    for (p in rawBlocks) fragments.addAll(SentenceSplitter.split(p, max))
    val groups = SentenceSplitter.mergeGroups(fragments, max)
    val merged = groups.map { g ->
        g.map { fragments[it] }.filter { s -> s.any { it.isLetterOrDigit() } }.joinToString(" ")
    }

    // every merged fragment must clean to something speakable or be skipped
    var empty = 0
    var risky = 0
    var tooShort = 0
    for (m in merged) {
        val c = clean(m)
        if (c == null) { empty++; continue }
        if (c.any { it == '"' || it == '“' || it == '”' || it == '‘' || it == '’' || it == '…' || it == '*' }) {
            risky++
            println("FAILED risky-char in ${c.take(80)}")
        }
        if (c.length < 12 && merged.size > 1) {
            tooShort++
            println("WARN short fragment kept (${c.length}): $c")
        }
    }
    check("fixture-no-risky", risky == 0, "risky=$risky")
    check("fixture-no-empty", empty == 0, "empty=$empty")
    check("fixture-few-short", tooShort <= 1, "short=$tooShort")

    // merge must not exceed the cap (Kokoro phoneme limit headroom)
    check("fixture-cap", merged.all { it.length <= max }, "max=${merged.maxOfOrNull { it.length }}")
    println("fixture: ${rawBlocks.size} paragraphs -> ${fragments.size} fragments -> ${merged.size} merged")

    if (failures > 0) {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
    println("ALL PASS")
}
