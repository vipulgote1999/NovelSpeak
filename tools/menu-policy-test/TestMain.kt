import app.novelspeak.MenuPolicy

/**
 * Host-side checks for the overflow menu. Home must not offer page-only actions, and reader
 * menus must only offer actions that can actually run.
 *
 *   kotlinc app/src/app/novelspeak/MenuPolicy.kt tools/menu-policy-test/TestMain.kt \
 *     -include-runtime -d /tmp/menu-policy-test.jar
 *   java -jar /tmp/menu-policy-test.jar
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

    fun ids(home: Boolean = false, hasContent: Boolean = false, canForward: Boolean = false) =
        MenuPolicy.items(
            home = home,
            hasContent = hasContent,
            canForward = canForward
        ).map { it.id }

    val home = ids(home = true)
    check("home offers import", MenuPolicy.IMPORT in home)
    check("home offers library", MenuPolicy.LIBRARY in home)
    check("home offers settings", MenuPolicy.SETTINGS in home)
    check("home offers privacy", MenuPolicy.PRIVACY in home)
    check("home hides reload", MenuPolicy.RELOAD !in home)
    check("home hides copy", MenuPolicy.COPY !in home)
    check("home hides share", MenuPolicy.SHARE !in home)
    check("home hides site settings", MenuPolicy.SITE !in home)
    check("home hides bookmark", MenuPolicy.BOOKMARK !in home)
    check("home hides translate", MenuPolicy.TRANSLATE !in home)
    check("home menu is short", home.size <= 6, "size=${home.size}")

    val page = ids()
    check("page offers home", MenuPolicy.HOME in page)
    check("page offers reload", MenuPolicy.RELOAD in page)
    check("page offers share", MenuPolicy.SHARE in page)
    check("page offers translate", MenuPolicy.TRANSLATE in page)
    check("page omits duplicate copy", MenuPolicy.COPY !in page)
    check("page menu fits a phone", page.size <= 12, "size=${page.size}")
    check("page hides bookmark without content", MenuPolicy.BOOKMARK !in page)
    check("page hides sleep without content", MenuPolicy.SLEEP !in page)
    check("page hides forward without history", MenuPolicy.FORWARD !in page)

    val withContent = ids(hasContent = true, canForward = true)
    check("content page offers bookmark", MenuPolicy.BOOKMARK in withContent)
    check("content page offers sleep", MenuPolicy.SLEEP in withContent)
    check("history page offers forward", MenuPolicy.FORWARD in withContent)
    check("forward follows home", withContent.indexOf(MenuPolicy.FORWARD) == 1)

    val imported = MenuPolicy.items(
        home = false,
        hasContent = true,
        canForward = true,
        imported = true
    ).map { it.id }
    check("imported offers home", MenuPolicy.HOME in imported)
    check("imported offers library", MenuPolicy.LIBRARY in imported)
    check("imported offers import", MenuPolicy.IMPORT in imported)
    check("imported hides reload", MenuPolicy.RELOAD !in imported)
    check("imported hides share", MenuPolicy.SHARE !in imported)
    check("imported hides translate", MenuPolicy.TRANSLATE !in imported)
    check("imported hides bookmark", MenuPolicy.BOOKMARK !in imported)
    check("imported menu is short", imported.size <= 6, "size=${imported.size}")

    val labels = MenuPolicy.items(home = false, hasContent = true, canForward = true)
        .map { it.label }
    check("labels are non-blank", labels.all { it.isNotBlank() })
    check("labels are unique", labels.toSet().size == labels.size, labels.toString())
    check("last item is privacy", MenuPolicy.items(false, true, true).last().id == MenuPolicy.PRIVACY)

    println("$checks checks, $failures failure(s)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
