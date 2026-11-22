package app.novelspeak

import java.io.File

/**
 * Pure validation of an extracted model tree.
 *
 * The sherpa config builder passes every path below to native code and expects it to exist.
 * Validating only a subset used to let a partially extracted bundle be marked "installed" and
 * then fail at load time. Kept Android-free so the host harness can exercise every family.
 */
object ModelValidation {

    fun isComplete(dir: File, family: String?): Boolean {
        if (!dir.isDirectory) return false
        return when (family) {
            "kokoro" -> findFile(dir, listOf("model.int8.onnx", "model.onnx")) != null &&
                File(dir, "voices.bin").isFile && File(dir, "tokens.txt").isFile &&
                File(dir, "espeak-ng-data").isDirectory
            "pocket" -> findFile(dir, listOf("lm_flow.int8.onnx", "lm_flow.onnx")) != null &&
                findFile(dir, listOf("lm_main.int8.onnx", "lm_main.onnx")) != null &&
                findFile(dir, listOf("encoder.onnx")) != null &&
                findFile(dir, listOf("decoder.int8.onnx", "decoder.onnx")) != null &&
                File(dir, "text_conditioner.onnx").isFile &&
                File(dir, "vocab.json").isFile && File(dir, "token_scores.json").isFile
            "supertonic" -> findFile(dir, listOf("duration_predictor.int8.onnx", "duration_predictor.onnx")) != null &&
                findFile(dir, listOf("text_encoder.int8.onnx", "text_encoder.onnx")) != null &&
                findFile(dir, listOf("vector_estimator.int8.onnx", "vector_estimator.onnx")) != null &&
                findFile(dir, listOf("vocoder.int8.onnx", "vocoder.onnx")) != null &&
                File(dir, "tts.json").isFile && File(dir, "unicode_indexer.bin").isFile &&
                File(dir, "voice.bin").isFile
            "zipvoice" -> findFile(dir, listOf("encoder.int8.onnx", "encoder.onnx")) != null &&
                findFile(dir, listOf("decoder.int8.onnx", "decoder.onnx")) != null &&
                File(dir, "tokens.txt").isFile && File(dir, "lexicon.txt").isFile &&
                File(dir, "espeak-ng-data").isDirectory &&
                (File(dir, "vocos_24khz.onnx").isFile ||
                    File(File(dir.parentFile, "vocos-24khz"), "vocos_24khz.onnx").isFile)
            "vocoder" -> File(dir, "vocos_24khz.onnx").isFile
            else -> false
        }
    }

    fun findFile(dir: File, names: List<String>): File? {
        for (name in names) {
            val file = File(dir, name)
            if (file.isFile) return file
        }
        // Case-insensitive / versioned fallback (e.g. kokoro-multi-lang-v1_1.onnx).
        val stem = names.first().substringBeforeLast('.')
        return dir.listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith(stem.substringBeforeLast('.'))
        }
    }
}
