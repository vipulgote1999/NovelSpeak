package app.novelspeak

import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import java.io.File

/**
 * Builds the sherpa-onnx model configuration from an extracted model directory.
 *
 * Kept separate from [SherpaEngine] so the benchmark harness (and future tests) can instantiate
 * exactly the same model graph the reader uses, without duplicating the 60-line branch table.
 */
internal object SherpaConfig {

    fun build(
        dir: File,
        family: String,
        threads: Int,
        provider: String = "cpu"
    ): OfflineTtsConfig {
        val kokoro: OfflineTtsKokoroModelConfig
        val pocket: OfflineTtsPocketModelConfig
        val zipvoice: OfflineTtsZipVoiceModelConfig
        val supertonic: OfflineTtsSupertonicModelConfig
        if (family == "pocket") {
            kokoro = OfflineTtsKokoroModelConfig()
            supertonic = OfflineTtsSupertonicModelConfig()
            zipvoice = OfflineTtsZipVoiceModelConfig()
            pocket = OfflineTtsPocketModelConfig(
                ModelManager.findFile(dir, listOf("lm_flow.int8.onnx", "lm_flow.onnx"))!!.absolutePath,
                ModelManager.findFile(dir, listOf("lm_main.int8.onnx", "lm_main.onnx"))!!.absolutePath,
                ModelManager.findFile(dir, listOf("encoder.onnx", "encoder.int8.onnx"))!!.absolutePath,
                ModelManager.findFile(dir, listOf("decoder.int8.onnx", "decoder.onnx"))!!.absolutePath,
                File(dir, "text_conditioner.onnx").absolutePath,
                File(dir, "vocab.json").absolutePath,
                File(dir, "token_scores.json").absolutePath,
                16 // HayaiTTS: pocket voice embedding cache capacity
            )
        } else if (family == "supertonic") {
            kokoro = OfflineTtsKokoroModelConfig()
            pocket = OfflineTtsPocketModelConfig()
            zipvoice = OfflineTtsZipVoiceModelConfig()
            supertonic = OfflineTtsSupertonicModelConfig(
                ModelManager.findFile(dir, listOf("duration_predictor.int8.onnx", "duration_predictor.onnx"))!!.absolutePath,
                ModelManager.findFile(dir, listOf("text_encoder.int8.onnx", "text_encoder.onnx"))!!.absolutePath,
                ModelManager.findFile(dir, listOf("vector_estimator.int8.onnx", "vector_estimator.onnx"))!!.absolutePath,
                ModelManager.findFile(dir, listOf("vocoder.int8.onnx", "vocoder.onnx"))!!.absolutePath,
                File(dir, "tts.json").absolutePath,
                File(dir, "unicode_indexer.bin").absolutePath,
                File(dir, "voice.bin").absolutePath
            )
        } else if (family == "zipvoice") {
            kokoro = OfflineTtsKokoroModelConfig()
            pocket = OfflineTtsPocketModelConfig()
            supertonic = OfflineTtsSupertonicModelConfig()
            val vocoderFile = if (File(dir, "vocos_24khz.onnx").isFile) File(dir, "vocos_24khz.onnx")
                else File(File(dir.parentFile, "vocos-24khz"), "vocos_24khz.onnx")
            zipvoice = OfflineTtsZipVoiceModelConfig(
                ModelManager.findFile(dir, listOf("tokens.txt"))!!.absolutePath,
                ModelManager.findFile(dir, listOf("encoder.int8.onnx", "encoder.onnx"))!!.absolutePath,
                ModelManager.findFile(dir, listOf("decoder.int8.onnx", "decoder.onnx"))!!.absolutePath,
                vocoderFile.absolutePath,
                // The AAR's ZipVoice frontend (v1.13.8) still needs eSpeak data for
                // non-Chinese text; upstream 2.x deprecates it but tolerates it.
                File(dir, "espeak-ng-data").absolutePath,
                File(dir, "lexicon.txt").absolutePath,
                1.0f, // featScale
                0.5f, // tShift
                0.1f, // targetRms
                1.0f  // guidanceScale
            )
        } else {
            pocket = OfflineTtsPocketModelConfig()
            supertonic = OfflineTtsSupertonicModelConfig()
            zipvoice = OfflineTtsZipVoiceModelConfig()
            kokoro = OfflineTtsKokoroModelConfig(
                ModelManager.findFile(dir, listOf("model.int8.onnx", "model.onnx"))!!.absolutePath,
                File(dir, "voices.bin").absolutePath,
                File(dir, "tokens.txt").absolutePath,
                File(dir, "espeak-ng-data").absolutePath,
                "", // lexicon: en bundle relies on espeak-ng-data
                "en-us",
                "", // dictDir
                1.0f
            )
        }
        val modelConfig = OfflineTtsModelConfig(
            OfflineTtsVitsModelConfig(),
            OfflineTtsMatchaModelConfig(),
            kokoro,
            zipvoice,
            OfflineTtsKittenModelConfig(),
            pocket,
            supertonic,
            threads.coerceIn(1, Runtime.getRuntime().availableProcessors()),
            false,
            provider
        )
        // maxNumSentences = 1: we already split text, so the engine must not re-chunk it.
        return OfflineTtsConfig(modelConfig, "", "", 1, 0.2f)
    }
}
