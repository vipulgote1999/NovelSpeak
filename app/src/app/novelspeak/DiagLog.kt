package app.novelspeak

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic log that lands in shared storage so it can be inspected from Termux without adb:
 *
 *   /sdcard/Download/novelspeak-log.txt
 *
 * MediaStore inserts are allowed on API 29+ without storage permission. The file is truncated on
 * every app start, then appended to synchronously, so a crash still leaves the last line on disk.
 * Also mirrors to logcat under the "NovelSpeak" tag.
 */
object DiagLog {

    private const val FILE_NAME = "novelspeak-log.txt"
    private const val TAG = "NovelSpeak"
    private val lock = Any()
    private var uri: Uri? = null
    private var file: java.io.File? = null
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        synchronized(lock) {
            if (uri != null || file != null) return
            if (Build.VERSION.SDK_INT < 29) { initFile(ctx); return }
            try {
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                // Repair rows left pending by older builds before looking for the log row.
                unpendStale(ctx)

                var target: Uri? = null
                ctx.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                    arrayOf(FILE_NAME, "Download%"),
                    null
                )?.use { c ->
                    if (c.moveToFirst()) target = ContentUris.withAppendedId(collection, c.getLong(0))
                }
                if (target == null) target = newRow(ctx, collection)

                if (target != null) {
                    try {
                        // Write while the row is still pending: MediaProvider's demand scan deletes
                        // non-pending rows whose backing file does not exist yet.
                        writeHeader(ctx, target)
                        publish(ctx, target)
                    } catch (e: Throwable) {
                        // The MediaStore row can outlive its file (snapshot restore, cleanup, or a
                        // stale row from a previous install). openOutputStream then throws forever,
                        // which used to disable file logging for the whole session.
                        Log.w(TAG, "stale log row, recreating", e)
                        try { ctx.contentResolver.delete(target, null, null) } catch (ignored: Throwable) {}
                        target = newRow(ctx, collection)
                        if (target != null) {
                            writeHeader(ctx, target)
                            publish(ctx, target)
                        }
                    }
                    uri = target
                    if (target != null) {
                        // Now that the log is readable, rescue orphaned crash files from older
                        // builds: read them as their owning package and copy the text in.
                        recoverStale(ctx, target)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "DiagLog init failed", e)
            }
            if (uri == null) initFile(ctx)
        }
    }

    /**
     * MediaStore can be unavailable (or every row can be deleted by a scanner). Keep a private
     * file fallback so a bug report still has a log; adb can read it from the app's external dir.
     */
    private fun initFile(ctx: Context) {
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val f = java.io.File(dir, FILE_NAME)
            f.writeText(header(ctx))
            file = f
            Log.i(TAG, "DiagLog file fallback: ${f.absolutePath}")
        } catch (e: Throwable) {
            Log.w(TAG, "DiagLog file fallback failed", e)
        }
    }

    private fun newRow(ctx: Context, collection: Uri): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return try { ctx.contentResolver.insert(collection, values) } catch (e: Throwable) {
            Log.w(TAG, "insert log row failed", e)
            null
        }
    }

    private fun writeHeader(ctx: Context, target: Uri) {
        // Throw instead of silently returning when the row has no backing file, so init can
        // recreate the row.
        val os = ctx.contentResolver.openOutputStream(target, "wt")
            ?: throw IllegalStateException("no output stream for $target")
        os.use { it.write(header(ctx).toByteArray()) }
    }

    private fun header(ctx: Context): String {
        val v = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        } catch (e: Throwable) {
            "?"
        }
        return buildString {
            appendLine("NovelSpeak diagnostics — ${time.format(Date())}")
            appendLine("version=$v sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("---")
        }
    }

    /**
     * Earlier builds created crash/log rows without clearing IS_PENDING. Those files stay invisible
     * and unreadable (even for `cat` from Termux). As the same package we can still update them, so
     * flip IS_PENDING to 0 and list how many were recovered.
     */
    private fun unpendStale(ctx: Context) {
        try {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val rows = ArrayList<Uri>()
            ctx.contentResolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "(${MediaStore.Downloads.DISPLAY_NAME} LIKE ? OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?) AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("novelspeak-crash%", "novelspeak-log%", "Download%"),
                null
            )?.use { c ->
                while (c.moveToNext()) rows.add(ContentUris.withAppendedId(collection, c.getLong(0)))
            }
            for (u in rows) publish(ctx, u)
            if (rows.isNotEmpty()) Log.i(TAG, "unpended ${rows.size} stale diagnostic files")
        } catch (e: Throwable) {
            Log.w(TAG, "unpendStale failed", e)
        }
    }

    private fun recoverStale(ctx: Context, logUri: Uri) {
        try {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            data class Row(val uri: Uri, val name: String)
            val rows = ArrayList<Row>()
            ctx.contentResolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("novelspeak-crash%", "Download%"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    rows.add(
                        Row(
                            ContentUris.withAppendedId(collection, c.getLong(0)),
                            c.getString(1) ?: "crash"
                        )
                    )
                }
            }
            if (rows.isEmpty()) return
            var rescued = 0
            for (r in rows) {
                val text = try {
                    ctx.contentResolver.openInputStream(r.uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: Throwable) {
                    null
                }
                if (!text.isNullOrBlank()) {
                    append(
                        ctx, logUri,
                        "\n===== recovered ${r.name} =====\n${text.take(20000)}\n"
                    )
                    rescued++
                }
                publish(ctx, r.uri)
            }
            append(ctx, logUri, "\nrecovered $rescued of ${rows.size} stale crash files\n")
        } catch (e: Throwable) {
            Log.w(TAG, "recoverStale failed", e)
        }
    }

    private fun append(ctx: Context, target: Uri, text: String) {
        try {
            ctx.contentResolver.openOutputStream(target, "wa")?.use { it.write(text.toByteArray()) }
        } catch (e: Throwable) {
            Log.w(TAG, "append failed", e)
        }
    }

    fun publish(ctx: Context, uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                ctx.contentResolver.update(uri, v, null, null)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "publish failed", e)
        }
    }

    fun line(ctx: Context, msg: String) {
        Log.i(TAG, msg)
        synchronized(lock) {
            val text = "${time.format(Date())} $msg\n"
            val u = uri
            if (u != null) {
                try {
                    val os = ctx.contentResolver.openOutputStream(u, "wa")
                    if (os != null) {
                        os.use { it.write(text.toByteArray()) }
                        return
                    }
                } catch (e: Throwable) {
                    // Row disappeared (MediaProvider scan); switch to the file fallback.
                    uri = null
                }
            }
            if (file == null) initFile(ctx)
            try {
                file?.appendText(text)
            } catch (e: Throwable) {
                // last resort: keep going, install must not fail because logging failed
            }
        }
    }

    fun exception(ctx: Context, msg: String, e: Throwable) {
        line(ctx, "$msg: ${e.javaClass.name}: ${e.message}")
        try {
            line(ctx, Log.getStackTraceString(e).trim().take(4000))
        } catch (ignored: Throwable) {
        }
    }
}
