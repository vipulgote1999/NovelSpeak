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
        val unpackedMb: Int,
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
            unpackedMb = 152,
            sampleRate = 24000,
            family = "kokoro"
        ),
        Model(
            id = Prefs.MODEL_POCKET,
            label = "Pocket TTS int8 (English, zero-shot clone)",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2",
            sha256 = "2f3b88823cbbb9bf0b2477ec8ae7b3fec417b3a87b6bb5f256dba66f2ad967cb",
            sizeMb = 98,
            unpackedMb = 200,
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
        if (installInProgress) {
            onProgress(-1, "Another install is already running")
            return false
        }
        installInProgress = true
        try {
            return installLocked(ctx, model, onProgress)
        } finally {
            installInProgress = false
        }
    }

    @Volatile
    private var installInProgress = false

    private fun installLocked(ctx: Context, model: Model, onProgress: (Int, String) -> Unit): Boolean {
        val dest = modelDir(ctx, model.id)
        val tmp = File(ctx.cacheDir, model.id + ".tar.bz2")
        val started = System.currentTimeMillis()
        DiagLog.line(ctx, "install start id=${model.id} size=${model.sizeMb}MB unpacked~${model.unpackedMb}MB")
        return try {
            // Download + extracted tree + temp archive; fail fast with a readable message.
            val need = model.sizeMb.toLong() * 1024L * 1024L * 2L + 64L * 1024L * 1024L
            val free = android.os.StatFs(ctx.filesDir.absolutePath).availableBytes
            DiagLog.line(ctx, "storage free=${free / 1048576}MB need=${need / 1048576}MB")
            if (free < need) {
                onProgress(-1, "Not enough storage: need ~${need / 1048576} MB free, have ${free / 1048576} MB")
                return false
            }
            onProgress(0, "Connecting…")
            val downloaded = download(model.url, tmp, model.sizeMb, onProgress)
            if (downloaded != true) {
                tmp.delete()
                DiagLog.line(ctx, "download FAILED url=${model.url}")
                onProgress(-1, "Download failed (HTTP error or no connection)")
                return false
            }
            DiagLog.line(ctx, "download done bytes=${tmp.length()} in ${(System.currentTimeMillis() - started) / 1000}s")
            onProgress(-1, "Verifying checksum…")
            val digest = sha256(tmp)
            DiagLog.line(ctx, "sha256=$digest expected=${model.sha256}")
            if (!digest.equals(model.sha256, ignoreCase = true)) {
                tmp.delete()
                onProgress(-1, "Checksum mismatch (got ${digest.take(12)}…)")
                return false
            }
            onProgress(-1, "Extracting…")
            dest.deleteRecursively()
            dest.mkdirs()
            DiagLog.line(ctx, "extract start dest=$dest")
            var lastLoggedMb = -1L
            extractTarBz2(tmp, dest) { name, done, size, total ->
                val pct = if (size > 0) ((done * 100) / size).toInt() else 0
                val totalMb = total / 1048576
                val bucket = totalMb / 16
                if (bucket != lastLoggedMb) {
                    lastLoggedMb = bucket
                    DiagLog.line(ctx, "extract $name ${done / 1048576}/${size / 1048576}MB total=${totalMb}MB")
                }
                onProgress(
                    -1,
                    "Extracting $name  $pct%  (${done / 1048576}/${size / 1048576} MB) · " +
                        "total $totalMb/${model.unpackedMb} MB"
                )
            }
            tmp.delete()
            if (!isInstalled(ctx, model.id)) {
                dest.deleteRecursively()
                DiagLog.line(ctx, "extract INCOMPLETE — required files missing")
                onProgress(-1, "Extraction incomplete (missing model files)")
                return false
            }
            DiagLog.line(ctx, "install OK in ${(System.currentTimeMillis() - started) / 1000}s")
            onProgress(100, "Installed")
            true
        } catch (e: Throwable) {
            tmp.delete()
            DiagLog.exception(ctx, "install exception", e)
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

    /**
     * Streams the tar.bz2 into [dest]. bzip2 is single-threaded and the model files are huge
     * (Kokoro's ONNX entry alone is 134 MB), so progress is reported per chunk and a stall
     * watchdog aborts with a readable error instead of appearing hung forever.
     */
    private fun extractTarBz2(archive: File, dest: File, onProgress: (String, Long, Long, Long) -> Unit) {
        val raw: InputStream = archive.inputStream().buffered(256 * 1024)
        val bz: InputStream = BZip2CompressorInputStream(raw, true)
        var totalBytes = 0L
        var lastReport = 0L
        var lastProgressAt = System.currentTimeMillis()
        TarArchiveInputStream(bz).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val name = entry.name.trimStart('/', '.')
                if (name.isNotEmpty() && !name.contains("..")) {
                    val rel = if (name.contains('/')) name.substringAfter('/') else ""
                    if (rel.isNotEmpty()) {
                        val target = File(dest, rel)
                        val label = target.name
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            val size = entry.size
                            var done = 0L
                            FileOutputStream(target).buffered(256 * 1024).use { out ->
                                val buf = ByteArray(256 * 1024)
                                while (true) {
                                    val n = tar.read(buf)
                                    if (n < 0) break
                                    if (n == 0) continue
                                    out.write(buf, 0, n)
                                    done += n
                                    totalBytes += n
                                    val now = System.currentTimeMillis()
                                    if (done - lastReport >= 4L * 1048576 || now - lastProgressAt > 1000) {
                                        lastReport = done
                                        lastProgressAt = now
                                        onProgress(label, done, size, totalBytes)
                                    }
                                }
                            }
                            if (size > 0 && done != size) {
                                throw java.io.IOException("Truncated entry $label ($done/$size bytes)")
                            }
                        }
                    }
                }
                if (System.currentTimeMillis() - lastProgressAt > 45_000) {
                    throw java.io.IOException("Extraction stalled for 45 s — keep the screen on and retry")
                }
                lastReport = 0
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
