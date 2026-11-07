package app.novelspeak.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.webkit.CookieManager
import app.novelspeak.DiagLog
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Loads manga page bitmaps with a bounded memory cache and an on-disk HTTP cache.
 *
 * Images are decoded at a size that keeps OCR accurate without holding a whole chapter in RAM.
 * The memory cache is sized from the process heap and evicts pages the reader is no longer
 * looking at; disk entries are pruned so a long manga session cannot grow without limit.
 */
class MangaPageLoader(context: Context) {

    private val appContext = context.applicationContext
    private val diskDir = File(appContext.cacheDir, "manga-pages")
    private val lock = Any()
    private val loadLocks = HashMap<String, Any>()
    private val memory: LruCache<String, Bitmap>
    // Keep more detail on devices with room for it; small heaps fall back to a lower ceiling
    // so a single very large scan cannot push the process into OOM.
    private val maxDimension: Int
    private val maxPixels: Long

    init {
        if (!diskDir.exists()) diskDir.mkdirs()
        val heap = Runtime.getRuntime().maxMemory()
        val heapMb = heap / (1024L * 1024L)
        maxDimension = if (heapMb >= 192L) 3600 else 3000
        maxPixels = if (heapMb >= 192L) 10_000_000L else 6_000_000L
        val cacheBytes = (heap / 5).coerceIn(24L * 1024 * 1024, 96L * 1024 * 1024)
        memory = object : LruCache<String, Bitmap>(cacheBytes.toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                val bytes = try { value.byteCount } catch (t: Throwable) { 0 }
                return if (bytes > 0) bytes else 1
            }
        }
    }

    fun load(source: MangaPageSource): Bitmap? = load(source.uri, source.referer)

    fun load(uri: String, referer: String? = null): Bitmap? {
        cached(uri)?.let { return it }
        val perUri = synchronized(loadLocks) { loadLocks.getOrPut(uri) { Any() } }
        val bitmap = synchronized(perUri) {
            // Another thread may have finished the same page while this one waited.
            cached(uri)?.let { return it }
            try {
                decode(uri, referer)
            } catch (t: Throwable) {
                DiagLog.exception(appContext, "manga image load failed: $uri", t)
                null
            }
        } ?: return null
        synchronized(lock) { memory.put(uri, bitmap) }
        return bitmap
    }

    fun cached(uri: String): Bitmap? = synchronized(lock) {
        val b = memory.get(uri)
        if (b != null && !b.isRecycled) b else null
    }

    fun clearMemory() {
        synchronized(lock) { memory.evictAll() }
    }

    private fun decode(uri: String, referer: String?): Bitmap? {
        val streamOpener: () -> InputStream = streamOpener(uri, referer)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        streamOpener().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = sampleSize(bounds.outWidth, bounds.outHeight)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = streamOpener().use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return shrinkIfNeeded(decoded)
    }

    /**
     * Decode bounds: long side at most [maxDimension] and total pixels at most [maxPixels].
     * Sample sizes are powers of two, so the result can still be somewhat above the target;
     * [shrinkIfNeeded] fixes that when the image is small enough to scale safely.
     */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w > maxDimension || h > maxDimension ||
            w.toLong() * h.toLong() > maxPixels
        ) {
            sample *= 2
            w = width / sample
            h = height / sample
        }
        return sample
    }

    private fun shrinkIfNeeded(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = w.toLong() * h.toLong()
        if (w <= maxDimension && h <= maxDimension && pixels <= maxPixels) return bitmap
        var ratio = 1f
        if (w > maxDimension || h > maxDimension) {
            ratio = maxOf(w.toFloat() / maxDimension, h.toFloat() / maxDimension)
        }
        if (pixels > maxPixels) {
            ratio = maxOf(ratio, kotlin.math.sqrt(pixels.toDouble() / maxPixels).toFloat())
        }
        if (ratio <= 1.02f) return bitmap
        val tw = (w / ratio).toInt().coerceAtLeast(1)
        val th = (h / ratio).toInt().coerceAtLeast(1)
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, tw, th, true)
            if (scaled !== bitmap) bitmap.recycle()
            scaled
        } catch (t: Throwable) {
            bitmap
        }
    }

    private fun streamOpener(uri: String, referer: String?): () -> InputStream {
        val parsed = Uri.parse(uri)
        return when (parsed.scheme?.lowercase()) {
            "http", "https" -> {
                val file = download(uri, referer)
                val opener: () -> InputStream = { file.inputStream() }
                opener
            }
            "file" -> {
                val path = parsed.path ?: throw IOException("no file path")
                val file = File(path)
                val opener: () -> InputStream = { file.inputStream() }
                opener
            }
            "content", "android.resource" -> {
                val opener: () -> InputStream = {
                    appContext.contentResolver.openInputStream(parsed)
                        ?: throw IOException("no stream for $uri")
                }
                opener
            }
            else -> {
                val opener: () -> InputStream = { File(uri).inputStream() }
                opener
            }
        }
    }

    private fun download(url: String, referer: String?): File {
        val key = sha1(url)
        val dest = File(diskDir, "$key.img")
        if (dest.isFile && dest.length() > 0L) return dest
        val tmp = File(diskDir, "$key.part")
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 45000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                setRequestProperty(
                    "Accept",
                    "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
                )
                val ref = referer?.takeIf { it.startsWith("http") } ?: originOf(url)
                if (ref != null) setRequestProperty("Referer", ref)
                try {
                    CookieManager.getInstance().getCookie(url)?.let {
                        setRequestProperty("Cookie", it)
                    }
                } catch (ignored: Throwable) {
                }
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code for image")
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (tmp.length() <= 0L) throw IOException("empty image response")
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            pruneDiskCache()
            return dest
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun originOf(url: String): String? {
        return try {
            val u = URL(url)
            val port = if (u.port > 0) ":${u.port}" else ""
            "${u.protocol}://${u.host}$port/"
        } catch (t: Throwable) {
            null
        }
    }

    private fun pruneDiskCache() {
        val files = diskDir.listFiles() ?: return
        if (files.size <= MAX_DISK_FILES) return
        val sorted = files.filter { it.isFile }.sortedBy { it.lastModified() }
        val remove = sorted.size - MAX_DISK_FILES
        for (i in 0 until remove) {
            try { sorted[i].delete() } catch (ignored: Throwable) {}
        }
    }

    private fun sha1(value: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) sb.append("%02x".format(b.toInt() and 0xff))
            sb.toString()
        } catch (t: Throwable) {
            value.hashCode().toUInt().toString(16)
        }
    }

    private companion object {
        const val MAX_DISK_FILES = 320
    }
}
