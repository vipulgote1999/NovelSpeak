import app.novelspeak.library.LibraryGrid

/**
 * Host-side checks for the adaptive Library grid.
 *
 *   kotlinc app/src/app/novelspeak/library/LibraryGrid.kt \
 *     tools/library-grid-test/TestMain.kt -include-runtime -d /tmp/library-grid-test.jar
 *   java -jar /tmp/library-grid-test.jar
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

    check("phone is one column", LibraryGrid.columns(360) == 1)
    check("large phone is one column", LibraryGrid.columns(599) == 1)
    check("600dp starts two columns", LibraryGrid.columns(600) == 2)
    check("tablet is two columns", LibraryGrid.columns(800) == 2)
    check("expanded is two columns", LibraryGrid.columns(1200) == 2)

    check("empty input has no pairs", LibraryGrid.pairs(emptyList<Int>()).isEmpty())
    val single = LibraryGrid.pairs(listOf("a"))
    check("single item keeps a null right", single.size == 1 && single[0].second == null)
    val even = LibraryGrid.pairs(listOf("a", "b", "c", "d"))
    check("even items pair in order",
        even.map { it.first } == listOf("a", "c") &&
            even.map { it.second } == listOf("b", "d"))
    val odd = LibraryGrid.pairs(listOf(1, 2, 3, 4, 5))
    check("odd items leave one spacer", odd.size == 3 && odd[2].first == 5 && odd[2].second == null)
    check("pairing preserves every item",
        LibraryGrid.pairs((1..7).toList()).flatMap { listOfNotNull(it.first, it.second) } == (1..7).toList())

    println("$checks checks, $failures failure(s)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
