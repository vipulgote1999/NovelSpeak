import app.novelspeak.ModelInstallPolicy

/**
 * Regression for model installation: model entries can be tar.bz2 bundles or a
 * single .onnx file (the 24 kHz vocoder). The installer must not try to bunzip2
 * a bare .onnx download.
 */
fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean) {
        if (ok) println("PASS $name") else {
            failures++
            println("FAILED $name")
        }
    }

    check(
        "tar.bz2 model is an archive",
        ModelInstallPolicy.isArchive("https://example.test/kokoro.tar.bz2")
    )
    check(
        "vocoder onnx is not an archive",
        !ModelInstallPolicy.isArchive("https://example.test/vocos_24khz.onnx")
    )
    check(
        "archive name handles query strings",
        ModelInstallPolicy.isArchive("https://example.test/model.tar.bz2?download=1")
    )
    check(
        "bare model target name",
        ModelInstallPolicy.targetFileName("https://example.test/vocos_24khz.onnx?download=1") ==
            "vocos_24khz.onnx"
    )

    if (failures > 0) kotlin.system.exitProcess(1)
    println("ALL PASS")
}
