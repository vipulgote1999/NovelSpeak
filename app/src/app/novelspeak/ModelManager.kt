package app.novelspeak

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * On-demand model manager for the sherpa-onnx backend.
 *
 * Same strategy as HayaiTTS' `VoiceDownloadWorker` (tar.bz2 bundles from the k2-fsa
 * `tts-models` release, SHA-256 verified, extracted into the app's files dir) and the same
 * "download on first use, never bundle 100 MB in the APK" rule NekoSpeak uses.
 *
 * Bundles are published by upstream sherpa-onnx, so we do not rehost anything:
 *   Kokoro  : kokoro-int8-en-v0_19.tar.bz2           (103 MB, sha c9f0dd39...)
 *   Pocket  : sherpa-onnx-pocket-tts-int8-...tar.bz2 ( 98 MB, sha 2f3b8882...)
 */
object ModelManager {

    data class Model(
        val id: String,
        val label: String,
        val url: String,
        val sha256: String,
        val sizeMb: Int,
        val sampleRate: Int,
        val family: String
    )

    val MODELS = listOf(
        Model(
            id = Prefs.MODEL_KOKORO,
            label = "Kokoro int8 (English, 11 voices)",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2",
            sha256 = "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd",
            sizeMb = 103,
            sampleRate = 24000,
            family = "kokoro"
        ),
        Model(
            id = Prefs.MODEL_POCKET,
            label = "Pocket TTS int8 (English, zero-shot clone)",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2",
            sha256 = "2f3b88823cbbb9bf0b2477ec8ae7b3fec417b3a87b6bb5f256dba66f2ad967cb",
            sizeMb = 98,
            sampleRate = 24000,
            family = "pocket"
        )
    )

    fun model(id: String): Model? = MODELS.firstOrNull { it.id == id }

    fun modelDir(ctx: Context, id: String): File = File(File(ctx.filesDir, "models"), id)

    fun isInstalled(ctx: Context, id: String): Boolean {
        val d = modelDir(ctx, id)
        if (!d.isDirectory) return false
        return when (model(id)?.family) {
            "kokoro" -> findFile(d, listOf("model.int8.onnx", "model.onnx")) != null &&
                    File(d, "voices.bin").isFile && File(d, "tokens.txt").isFile
            "pocket" -> findFile(d, listOf("lm_main.int8.onnx", "lm_main.onnx")) != null &&
                    findFile(d, listOf("encoder.onnx")) != null &&
                    findFile(d, listOf("decoder.int8.onnx", "decoder.onnx")) != null &&
                    File(d, "text_conditioner.onnx").isFile &&
                    File(d, "vocab.json").isFile && File(d, "token_scores.json").isFile
            else -> false
        }
    }

    fun findFile(dir: File, names: List<String>): File? {
        for (n in names) {
            val f = File(dir, n)
            if (f.isFile) return f
        }
        // case-insensitive / versioned fallback (e.g. kokoro-multi-lang-v1_1.onnx)
        val stem = names.first().substringBeforeLast('.')
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith(stem.substringBeforeLast(".")) }
    }

    /** Downloads + verifies + extracts. [onProgress] gets 0..100, -1 for extracting. */
    fun install(ctx: Context, model: Model, onProgress: (Int, String) -> Unit): Boolean {
        val dest = modelDir(ctx, model.id)
        val tmp = File(ctx.cacheDir, model.id + ".tar.bz2")
        return try {
            // Download + extracted tree + temp archive; fail fast with a readable message.
            val need = model.sizeMb.toLong() * 1024L * 1024L * 2L + 64L * 1024L * 1024L
            val free = android.os.StatFs(ctx.filesDir.absolutePath).availableBytes
            if (free < need) {
                onProgress(-1, "Not enough storage: need ~${need / 1048576} MB free, have ${free / 1048576} MB")
                return false
            }
            onProgress(0, "Connecting…")
            val downloaded = download(model.url, tmp, model.sizeMb, onProgress)
            if (downloaded != true) {
                tmp.delete()
                onProgress(-1, "Download failed (HTTP error or no connection)")
                return false
            }
            onProgress(-1, "Verifying checksum…")
            val digest = sha256(tmp)
            if (!digest.equals(model.sha256, ignoreCase = true)) {
                tmp.delete()
                onProgress(-1, "Checksum mismatch (got ${digest.take(12)}…)")
                return false
            }
            onProgress(-1, "Extracting…")
            dest.deleteRecursively()
            dest.mkdirs()
            extractTarBz2(tmp, dest) { n ->
                if (n % 40 == 0) onProgress(-1, "Extracting… $n files")
            }
            tmp.delete()
            if (!isInstalled(ctx, model.id)) {
                dest.deleteRecursively()
                onProgress(-1, "Extraction incomplete (missing model files)")
                return false
            }
            onProgress(100, "Installed")
            true
        } catch (e: Throwable) {
            tmp.delete()
            onProgress(-1, "Failed: ${e.javaClass.simpleName}: ${e.message ?: "no details"}")
            false
        }
    }

    fun delete(ctx: Context, id: String): Boolean = modelDir(ctx, id).deleteRecursively()

    // ---- internals ----

    private fun download(url: String, out: File, sizeMb: Int, onProgress: (Int, String) -> Unit): Boolean? {
        var conn: HttpURLConnection? = null
        try {
            var current = url
            var redirects = 0
            while (redirects < 5) {
                conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 30000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "NovelSpeak/0.1")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return false
                    current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                    conn.disconnect()
                    redirects++
                    continue
                }
                if (code != 200) { conn.disconnect(); return false }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: (sizeMb.toLong() * 1024 * 1024)
                conn.inputStream.use { input ->
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(128 * 1024)
                        var read = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                            read += n
                            val pct = ((read * 100) / total).toInt().coerceIn(0, 99)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct, "$pct%  (${read / 1048576} / ${total / 1048576} MB)")
                            }
                        }
                    }
                }
                conn.disconnect()
                return true
            }
            return false
        } catch (e: Throwable) {
            try { conn?.disconnect() } catch (t: Throwable) {}
            throw e
        }
    }

    private fun extractTarBz2(archive: File, dest: File, onEntry: (Int) -> Unit = {}) {
        val raw: InputStream = archive.inputStream().buffered(128 * 1024)
        val bz: InputStream = BZip2CompressorInputStream(raw, true)
        TarArchiveInputStream(bz).use { tar ->
            var count = 0
            var entry = tar.nextEntry
            while (entry != null) {
                val name = entry.name.trimStart('/', '.')
                if (name.isNotEmpty() && !name.contains("..")) {
                    // strip the single leading bundle directory
                    val rel = if (name.contains('/')) name.substringAfter('/') else ""
                    if (rel.isNotEmpty()) {
                        val target = File(dest, rel)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { fos -> tar.copyTo(fos, 128 * 1024) }
                        }
                    }
                }
                count++
                onEntry(count)
                entry = tar.nextEntry
            }
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
