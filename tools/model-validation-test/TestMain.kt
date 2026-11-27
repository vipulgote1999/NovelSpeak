import app.novelspeak.ModelValidation
import java.io.File
import java.nio.file.Files

/**
 * Completeness checks for installed model trees.
 *
 * `ModelManager.isInstalled` used to validate only a subset of the files that
 * SherpaConfig passes to sherpa-onnx. A partially extracted bundle could therefore
 * pass installation and then fail (or exit the process) at load time. These tests
 * pin every file the config builder requires.
 */
fun main() {
    var failures = 0
    fun check(name: String, ok: Boolean) {
        if (ok) println("PASS $name") else {
            failures++
            println("FAILED $name")
        }
    }

    fun tempDir(): File = Files.createTempDirectory("novelspeak-model-").toFile()
    fun touch(dir: File, path: String, contents: String = "x") {
        val f = File(dir, path)
        f.parentFile?.mkdirs()
        f.writeText(contents)
    }
    fun mkdir(dir: File, path: String) {
        File(dir, path).mkdirs()
    }
    fun cleanup(dir: File) {
        dir.deleteRecursively()
    }

    run {
        val d = tempDir()
        touch(d, "model.int8.onnx")
        touch(d, "voices.bin")
        touch(d, "tokens.txt")
        mkdir(d, "espeak-ng-data")
        check("kokoro complete", ModelValidation.isComplete(d, "kokoro"))
        File(d, "espeak-ng-data").deleteRecursively()
        check("kokoro missing espeak data", !ModelValidation.isComplete(d, "kokoro"))
        cleanup(d)
    }

    run {
        val d = tempDir()
        touch(d, "lm_flow.int8.onnx")
        touch(d, "lm_main.int8.onnx")
        touch(d, "encoder.onnx")
        touch(d, "decoder.int8.onnx")
        touch(d, "text_conditioner.onnx")
        touch(d, "vocab.json")
        touch(d, "token_scores.json")
        check("pocket complete", ModelValidation.isComplete(d, "pocket"))
        File(d, "lm_flow.int8.onnx").delete()
        check("pocket missing lm_flow", !ModelValidation.isComplete(d, "pocket"))
        cleanup(d)
    }

    run {
        val d = tempDir()
        touch(d, "duration_predictor.int8.onnx")
        touch(d, "text_encoder.int8.onnx")
        touch(d, "vector_estimator.int8.onnx")
        touch(d, "vocoder.int8.onnx")
        touch(d, "tts.json")
        touch(d, "unicode_indexer.bin")
        touch(d, "voice.bin")
        check("supertonic complete", ModelValidation.isComplete(d, "supertonic"))
        File(d, "tts.json").delete()
        check("supertonic missing tts.json", !ModelValidation.isComplete(d, "supertonic"))
        cleanup(d)
    }

    run {
        val d = tempDir()
        touch(d, "encoder.int8.onnx")
        touch(d, "decoder.int8.onnx")
        touch(d, "tokens.txt")
        touch(d, "lexicon.txt")
        mkdir(d, "espeak-ng-data")
        touch(d, "vocos_24khz.onnx")
        check("zipvoice complete (local vocoder)", ModelValidation.isComplete(d, "zipvoice"))
        File(d, "espeak-ng-data").deleteRecursively()
        check("zipvoice missing espeak data", !ModelValidation.isComplete(d, "zipvoice"))
        cleanup(d)
    }

    run {
        val d = tempDir()
        touch(d, "encoder.onnx")
        touch(d, "decoder.onnx")
        touch(d, "tokens.txt")
        touch(d, "lexicon.txt")
        mkdir(d, "espeak-ng-data")
        check("zipvoice needs a vocoder", !ModelValidation.isComplete(d, "zipvoice"))
        cleanup(d)
    }

    run {
        val d = tempDir()
        touch(d, "vocos_24khz.onnx")
        check("vocoder complete", ModelValidation.isComplete(d, "vocoder"))
        File(d, "vocos_24khz.onnx").delete()
        check("vocoder missing file", !ModelValidation.isComplete(d, "vocoder"))
        cleanup(d)
    }

    run {
        val d = tempDir()
        check("unknown family is not installable", !ModelValidation.isComplete(d, "unknown"))
        check("missing directory is not installable", !ModelValidation.isComplete(File(d, "nope"), "kokoro"))
        cleanup(d)
    }

    if (failures > 0) {
        println("$failures case(s) failed")
        kotlin.system.exitProcess(1)
    }
    println("ALL PASS")
}
