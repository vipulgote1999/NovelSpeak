import app.novelspeak.SentenceSplitter
import app.novelspeak.TtsOverrides
import app.novelspeak.TtsTextCleaner
import java.io.File

/**
 * Host-side harness for the neural-TTS text path. Not part of the APK.
 *
 *   kotlinc app/src/app/novelspeak/SentenceSplitter.kt \
 *     app/src/app/novelspeak/TtsTextCleaner.kt app/src/app/novelspeak/TtsOverrides.kt \
 *     tools/tts-cleaner-test/TestMain.kt \
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
    // Rules default to empty here; the production one-arg call reads TtsOverrides (tested below).
    fun clean(raw: String, rules: List<Pair<String, String>> = emptyList()) =
        TtsTextCleaner.cleanForNeural(raw, rules)

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

    // ---- pronunciation overrides (P1.2): literal, case-insensitive, longest-first ----
    val shenWay = listOf("Shen Wei" to "Shen Way")
    check("pron-basic", clean("Shen Wei walked in", shenWay) == "Shen Way walked in.", "got=${clean("Shen Wei walked in", shenWay)}")
    check("pron-case-insens", clean("SHEN WEI shouted", shenWay) == "Shen Way shouted.", "got=${clean("SHEN WEI shouted", shenWay)}")
    check("pron-possessive", clean("Shen Wei's plan", shenWay) == "Shen Way's plan.", "got=${clean("Shen Wei's plan", shenWay)}")
    // Longest source wins even when the shorter rule is listed first (overlap).
    val overlap = listOf("Shen" to "Shenn", "Shen Wei" to "Shen Way")
    check("pron-longest-first", clean("Shen Wei and Shen left", overlap) == "Shen Way and Shenn left.", "got=${clean("Shen Wei and Shen left", overlap)}")
    // Single pass: replacements never cascade ("cat" -> "dog" must not become "bird").
    check("pron-no-cascade", clean("cat", listOf("cat" to "dog", "dog" to "bird")) == "dog.", "got=${clean("cat", listOf("cat" to "dog", "dog" to "bird"))}")
    // Word boundary: an alphanumeric source must not fire inside another word.
    check("pron-boundary", clean("scatter the cat", listOf("cat" to "dog")) == "scatter the dog.", "got=${clean("scatter the cat", listOf("cat" to "dog"))}")
    // Regex metacharacters in sources are literal, not patterns (applied before sanitisation).
    check("pron-regex-special", clean("I code in C++ daily", listOf("C++" to "C plus plus")) == "I code in C plus plus daily.", "got=${clean("I code in C++ daily", listOf("C++" to "C plus plus"))}")
    check("pron-bracket-literal", clean("He [sigh]ed twice", listOf("[sigh]" to "sigh")) == "He sighed twice.", "got=${clean("He [sigh]ed twice", listOf("[sigh]" to "sigh"))}")
    check("pron-dotted-acronym", clean("The U.S.A. is big", listOf("U.S.A." to "U S A")) == "The U S A is big.", "got=${clean("The U.S.A. is big", listOf("U.S.A." to "U S A"))}")
    // Empty replacement deletes the source.
    check("pron-empty-replacement", clean("Hello um world", listOf("um" to "")) == "Hello world.", "got=${clean("Hello um world", listOf("um" to ""))}")
    // Unsafe rules are ignored, not fatal.
    check("pron-control-source-ignored", clean("abc", listOf("a\u0000" to "x")) == "abc.", "got=${clean("abc", listOf("a\u0000" to "x"))}")
    check("pron-control-replacement-ignored", clean("abc", listOf("abc" to "x\u0007")) == "abc.", "got=${clean("abc", listOf("abc" to "x\u0007"))}")
    check("pron-blank-source-ignored", clean("abc", listOf("  " to "x")) == "abc.", "got=${clean("abc", listOf("  " to "x"))}")

    // ---- TtsOverrides validation + default path ----
    TtsOverrides.set(listOf("" to "x", "  " to "y", "a\u0000b" to "x", "ok" to "yes\u0007", "Shen Wei" to "Shen Way"))
    check("overrides-validation", TtsOverrides.pronunciationRules == listOf("Shen Wei" to "Shen Way"), "got=${TtsOverrides.pronunciationRules}")
    TtsOverrides.set(listOf("q" to "x".repeat(250)))
    check("overrides-replacement-cap", TtsOverrides.pronunciationRules.single().second.length == TtsOverrides.MAX_REPLACEMENT_CHARS, "len=${TtsOverrides.pronunciationRules.single().second.length}")
    TtsOverrides.set((0 until 250).map { "w$it" to "x" })
    check("overrides-rule-cap", TtsOverrides.pronunciationRules.size == TtsOverrides.MAX_RULES, "size=${TtsOverrides.pronunciationRules.size}")
    TtsOverrides.set(listOf("Shen Wei" to "Shen Way"))
    check("overrides-default-path", TtsTextCleaner.cleanForNeural("Shen Wei left") == "Shen Way left.", "got=${TtsTextCleaner.cleanForNeural("Shen Wei left")}")
    // System TTS path: rules only, presentation (quotes/case/punctuation) untouched.
    TtsOverrides.set(listOf("C++" to "C plus plus"))
    check(
        "apply-pronunciation-only",
        TtsTextCleaner.applyPronunciation("Say \"C++\" now!") == "Say \"C plus plus\" now!",
        "got=${TtsTextCleaner.applyPronunciation("Say \"C++\" now!")}"
    )
    TtsOverrides.clear()
    check("overrides-clear", TtsOverrides.pronunciationRules.isEmpty())
    check("pron-none-unchanged", clean("Shen Wei left") == "Shen Wei left.")

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
