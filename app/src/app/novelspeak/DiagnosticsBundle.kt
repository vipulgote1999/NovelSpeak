package app.novelspeak

import android.app.Activity
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Local, zero-telemetry support bundle (roadmap P0.10 / P1.10).
 *
 * [build] writes `cacheDir/diagnostics/diagnostics-<ts>.zip` with:
 *  - `meta.txt`        — versionName/versionCode, SDK, device, ABI, active engine/model, catalog size
 *  - `prefs.txt`       — every SharedPreferences key; values redacted for url/token/rule/secret keys
 *  - `events.txt`      — the DiagLog file when readable, otherwise "log unavailable"
 *  - `LAST_CRASH.txt`  — the crash dump, only when a crash file exists
 *
 * Total size is capped at [MAX_BUNDLE_BYTES] (2 MB) and only the newest [MAX_KEPT] bundles are
 * retained (older than [TTL_MS] are deleted). Nothing is uploaded: [share] hands the zip to the
 * user's chosen app through [AUTHORITY] (a framework-only provider — no AndroidX FileProvider
 * is available in this build) with FLAG_GRANT_READ_URI_PERMISSION.
 *
 * If the provider is not registered in the manifest, [share]/[shareFile] return false and the
 * caller can still show the file path; nothing crashes.
 */
object DiagnosticsBundle {

    /** FileProvider-compatible authority; the manifest provider must use exactly this string. */
    const val AUTHORITY = "app.novelspeak.fileprovider"

    /** Hard cap on one zip (uncompressed accounting), per roadmap P1.10. */
    const val MAX_BUNDLE_BYTES = 2 * 1024 * 1024

    /** Keep at most this many bundle files in the cache. */
    const val MAX_KEPT = 5

    /** Bundles older than this are removed on the next build (14 days). */
    const val TTL_MS = 14L * 24 * 60 * 60 * 1000

    private const val FILE_PREFIX = "diagnostics-"
    private const val PREFIX_BUDGET = 16 * 1024
    private const val PREFS_BUDGET = 64 * 1024
    private const val EVENTS_BUDGET = 1536 * 1024
    private const val CRASH_BUDGET = 256 * 1024
    private const val MAX_VALUE_CHARS = 300
    private const val LOG_FILE = "novelspeak-log.txt"
    private const val CRASH_FILE = "novelspeak-crash.txt"

    /** Redact any pref whose key mentions one of these words (matches the P1.10 spec). */
    private val SENSITIVE_KEY_PARTS = arrayOf(
        "url", "token", "rule", "secret", "password", "passphrase"
    )

    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val isoStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)

    /**
     * Builds a fresh diagnostics zip and returns it. Safe on a background thread; throws
     * [IOException] only when the zip cannot be created at all (caller shows a failure status).
     */
    @Throws(IOException::class)
    fun build(context: Context): File {
        cleanOldBundles(context)
        val dir = bundleDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Cannot create diagnostics directory")
        }
        val base = "${FILE_PREFIX}${stamp.format(Date())}"
        var file = File(dir, "$base.zip")
        var suffix = 0
        while (file.exists()) {
            suffix++
            file = File(dir, "$base-$suffix.zip")
        }
        val buffer = java.io.BufferedOutputStream(FileOutputStream(file), 64 * 1024)
        val zip = ZipOutputStream(buffer)
        var budget = MAX_BUNDLE_BYTES.toLong()
        try {
            budget -= addText(zip, "meta.txt", metaText(context), minOf(PREFIX_BUDGET.toLong(), budget))
            budget -= addText(zip, "prefs.txt", prefsText(context), minOf(PREFS_BUDGET.toLong(), budget))
            val eventsBudget = minOf(EVENTS_BUDGET.toLong(), budget - CRASH_BUDGET / 2)
            budget -= addText(zip, "events.txt", eventsText(context), eventsBudget)
            val crash = crashText(context)
            if (crash != null && budget > 512L) {
                addText(zip, "LAST_CRASH.txt", crash, minOf(CRASH_BUDGET.toLong(), budget))
            }
        } catch (t: Throwable) {
            try {
                zip.close()
            } catch (ignored: Throwable) {
            }
            try {
                file.delete()
            } catch (ignored: Throwable) {
            }
            throw if (t is IOException) t else IOException("Diagnostics bundle failed", t)
        }
        zip.close()
        return file
    }

    /**
     * Builds a bundle and launches the system share sheet. Must be called on the main thread
     * (it calls startActivity for an Activity context). Returns false when sharing is not
     * possible (provider missing, no handler, build error).
     */
    fun share(context: Context): Boolean {
        val file = try {
            build(context)
        } catch (t: Throwable) {
            DiagLog.line(context, "diagnostics build failed: ${t.javaClass.simpleName}: ${t.message}")
            return false
        }
        return shareFile(context, file)
    }

    /** Shares an already-built bundle; returns false when the provider/handler is unavailable. */
    fun shareFile(context: Context, file: File): Boolean {
        if (!file.isFile) return false
        if (!providerAvailable(context)) return false
        val uri = uriFor(file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "NovelSpeak diagnostics")
            putExtra(
                Intent.EXTRA_TEXT,
                "NovelSpeak diagnostics bundle. Created on-device; no telemetry is uploaded by the app."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("NovelSpeak diagnostics", uri)
        }
        if (context !is Activity) send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val chooser = Intent.createChooser(send, "Share diagnostics")
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            true
        } catch (t: Throwable) {
            DiagLog.line(context, "diagnostics share failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** True when a content provider with [AUTHORITY] is registered (manifest wiring present). */
    fun providerAvailable(context: Context): Boolean = try {
        context.packageManager.resolveContentProvider(AUTHORITY, 0) != null
    } catch (t: Throwable) {
        false
    }

    /** Cache sub-directory the provider serves from: `cacheDir/diagnostics`. */
    fun bundleDir(context: Context): File = File(context.cacheDir, "diagnostics")

    /** content:// URI for a bundle file, shaped `/diagnostics/<name>.zip`. */
    fun uriFor(file: File): Uri = Uri.Builder()
        .scheme("content")
        .authority(AUTHORITY)
        .appendPath("diagnostics")
        .appendPath(file.name)
        .build()

    // ---- bundle contents ----------------------------------------------------------------

    private fun metaText(context: Context): String {
        val prefs = Prefs(context)
        val packageInfo = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (t: Throwable) {
            null
        }
        val versionName = packageInfo?.versionName ?: "?"
        val versionCode: Long = try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 28) packageInfo?.longVersionCode ?: -1L
            else (packageInfo?.versionCode ?: -1).toLong()
        } catch (t: Throwable) {
            -1L
        }
        val model = ModelManager.model(prefs.modelId)
        val catalog = try {
            ModelCatalog.loadBundled(context).size
        } catch (t: Throwable) {
            0
        }
        return buildString {
            appendLine("NovelSpeak diagnostics")
            appendLine("generated=${isoStamp.format(Date())}")
            appendLine("versionName=$versionName")
            appendLine("versionCode=$versionCode")
            appendLine("sdk=${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("hardware=${Build.HARDWARE} board=${Build.BOARD}")
            appendLine("abi=${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("cpuCores=${Runtime.getRuntime().availableProcessors()}")
            appendLine("maxMemoryMb=${Runtime.getRuntime().maxMemory() / (1024 * 1024)}")
            appendLine("engine=${prefs.engine}")
            appendLine("model=${prefs.modelId}")
            appendLine("modelFamily=${model?.family ?: "unknown"}")
            appendLine(
                "modelInstalled=${try { ModelManager.isInstalled(context, prefs.modelId) } catch (t: Throwable) { false }}"
            )
            appendLine("catalogEntries=$catalog")
            appendLine("modelCatalogAuto=${prefs.modelCatalogAuto}")
            appendLine("readerTheme=${prefs.readerTheme}")
            appendLine("readerFontScale=${prefs.readerFontScale}")
            appendLine("readerFontFamily=${prefs.readerFontFamily}")
            appendLine("readerLineHeight=${prefs.readerLineHeight}")
            appendLine("readerWidthPx=${prefs.readerWidthPx}")
            appendLine("readerWordHighlight=${prefs.readerWordHighlight}")
            appendLine("ttsVolumeBoost=${prefs.ttsVolumeBoost}")
            appendLine("ttsSkipSilence=${prefs.ttsSkipSilence}")
            appendLine("ttsPitch=${prefs.ttsPitch}")
            appendLine("pronunciationRules=${PronunciationRules.parse(prefs.pronunciationRules).size} rule(s)")
            appendLine("sleepTimerMinutes=${prefs.sleepTimerMinutes}")
            appendLine("locale=${Locale.getDefault()}")
        }
    }

    private fun prefsText(context: Context): String {
        // The bucket name is fixed by Prefs.kt ("novelspeak"); keeping it literal lets the
        // bundle work even if Prefs gains new accessors we do not know about yet.
        val all = try {
            context.getSharedPreferences("novelspeak", Context.MODE_PRIVATE).all
        } catch (t: Throwable) {
            emptyMap<String, Any?>()
        }
        return buildString {
            appendLine("# NovelSpeak preferences snapshot")
            appendLine("# values for url/token/rule/secret keys are redacted; nothing is uploaded")
            appendLine("# generated=${isoStamp.format(Date())}")
            for (key in all.keys.sorted()) {
                append(key)
                append('=')
                appendLine(safeValue(key, all[key]))
            }
        }
    }

    private fun safeValue(key: String, value: Any?): String {
        if (value == null) return "null"
        val lower = key.lowercase(Locale.US)
        val sensitive = SENSITIVE_KEY_PARTS.any { lower.contains(it) }
        return when (value) {
            is String -> if (sensitive) "(redacted, ${value.length} chars)"
            else escape(value).take(MAX_VALUE_CHARS)
            is Set<*> -> if (sensitive) "(redacted, ${value.size} items)"
            else value.joinToString("|") { escape(it.toString()) }.take(MAX_VALUE_CHARS)
            is Boolean, is Int, is Long, is Float -> value.toString()
            else -> if (sensitive) "(redacted)" else escape(value.toString()).take(MAX_VALUE_CHARS)
        }
    }

    private fun escape(value: String): String = value
        .replace("\r", " ")
        .replace("\n", "\\n")
        .replace("\t", " ")

    private fun eventsText(context: Context): String {
        // 1) DiagLog's private fallback file (external app dir) — usable without MediaStore.
        readExternalFile(context, LOG_FILE)?.let { if (it.isNotBlank()) return it }
        // 2) The MediaStore Downloads row DiagLog normally writes to (API 29+).
        try {
            readMediaStoreText(context, LOG_FILE)?.let { if (it.isNotBlank()) return it }
        } catch (t: Throwable) {
        }
        return "log unavailable\nNo readable DiagLog file was found (event log may be disabled).\n"
    }

    private fun crashText(context: Context): String? {
        readExternalFile(context, CRASH_FILE)?.let { if (it.isNotBlank()) return it }
        readExternalFile(context, "crash.txt")?.let { if (it.isNotBlank()) return it }
        return try {
            readMediaStoreText(context, CRASH_FILE)?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            null
        }
    }

    private fun readExternalFile(context: Context, name: String): String? = try {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, name)
        if (file.isFile) readTextCapped(file.inputStream(), CRASH_BUDGET) else null
    } catch (t: Throwable) {
        null
    }

    private fun readMediaStoreText(context: Context, displayName: String): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        var id = -1L
        try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf(displayName, "Download%"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) id = cursor.getLong(0)
            }
        } catch (t: Throwable) {
            return null
        }
        if (id < 0L) return null
        val uri = android.content.ContentUris.withAppendedId(collection, id)
        return try {
            context.contentResolver.openInputStream(uri)?.use { readTextCapped(it, EVENTS_BUDGET) }
        } catch (t: Throwable) {
            null
        }
    }

    private fun readTextCapped(input: InputStream, maxBytes: Int): String? {
        try {
            input.use { stream ->
                val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (total < maxBytes) {
                    val n = stream.read(buf, 0, minOf(buf.size, maxBytes - total))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                return String(out.toByteArray(), Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            return null
        }
    }

    // ---- zip helpers --------------------------------------------------------------------

    /** Adds one UTF-8 text entry, truncated to [maxBytes]; returns the bytes written. */
    private fun addText(zip: ZipOutputStream, name: String, text: String, maxBytes: Long): Long {
        if (maxBytes <= 0L) return 0L
        var bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > maxBytes) {
            val limit = (maxBytes - 64L).toInt().coerceAtLeast(0)
            val note = "\n…[entry truncated to keep the bundle under $MAX_BUNDLE_BYTES bytes]\n"
            bytes = (truncateUtf8(text, limit) + note).toByteArray(Charsets.UTF_8)
        }
        val entry = ZipEntry(name)
        entry.time = System.currentTimeMillis()
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
        // Account for the local header + central directory entry too (conservative).
        return bytes.size.toLong() + name.length + 100L
    }

    private fun truncateUtf8(text: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val chars = Character.charCount(codePoint)
            val cost = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (used + cost > maxBytes) break
            sb.appendCodePoint(codePoint)
            used += cost
            i += chars
        }
        return sb.toString()
    }

    private fun cleanOldBundles(context: Context) {
        try {
            val dir = bundleDir(context)
            val files = dir.listFiles { f ->
                f.isFile && f.name.startsWith("diagnostics-") && f.name.endsWith(".zip")
            } ?: return
            val now = System.currentTimeMillis()
            files.sortedByDescending { it.lastModified() }.forEachIndexed { index, file ->
                if (index >= MAX_KEPT - 1 || now - file.lastModified() > TTL_MS) {
                    file.delete()
                }
            }
        } catch (t: Throwable) {
        }
    }
}

/**
 * Minimal framework ContentProvider serving the diagnostics zip from
 * `cacheDir/diagnostics/` as `content://app.novelspeak.fileprovider/diagnostics/<name>.zip`.
 *
 * AndroidX's FileProvider is unavailable in this framework-only build, so this is the smallest
 * correct read-only replacement: path-traversal guard, read-only mode, no cursor/insert/update.
 * The manifest entry is owned by the Lead:
 * ```
 * <provider android:name=".DiagnosticsFileProvider"
 *           android:authorities="app.novelspeak.fileprovider"
 *           android:exported="false"
 *           android:grantUriPermissions="true" />
 * ```
 */
class DiagnosticsFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String = "application/zip"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Diagnostics provider is read-only")
        val ctx = context ?: throw FileNotFoundException("No context")
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != "diagnostics") {
            throw FileNotFoundException("Unknown path: $uri")
        }
        val name = segments[1]
        if (name != File(name).name || name.startsWith(".")) {
            throw FileNotFoundException("Invalid file name")
        }
        val dir = DiagnosticsBundle.bundleDir(ctx)
        val file = File(dir, name)
        val dirPath = dir.canonicalPath + File.separator
        if (!file.canonicalPath.startsWith(dirPath)) {
            throw FileNotFoundException("Invalid file name")
        }
        if (!file.isFile) throw FileNotFoundException("Not found: $name")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        val fd = openFile(uri, mode)
        return AssetFileDescriptor(fd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }
}
