package app.novelspeak

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.novelspeak.library.LibraryEntry
import app.novelspeak.library.LibraryStore
import app.novelspeak.library.PrefsLibraryStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local library screen: every chapter NovelSpeak has opened, newest first.
 *
 * Rows show title, site and progress ("42%") or "Finished". Tapping a row reopens the chapter in
 * [MainActivity] through an explicit ACTION_VIEW intent carrying [EXTRA_RESUME_SENTENCE] and
 * [EXTRA_FROM_LIBRARY]. Deleting one entry and clearing the whole library are available; clearing
 * asks for confirmation.
 *
 * Framework-only and built programmatically like [MainActivity]/[SettingsActivity] (no AndroidX,
 * no XML layouts). Data comes from [LibraryStore] over [PrefsLibraryStorage].
 */
class LibraryActivity : Activity() {

    private lateinit var store: LibraryStore
    private lateinit var list: LinearLayout
    private lateinit var clearAction: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWindowTheme()
        store = LibraryStore(PrefsLibraryStorage(this))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(LPalette.BG)
        }
        root.addView(buildTopBar())

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(20))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(scroll)
        setContentView(root)
        applyInsets(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** Keep the top bar below the status bar and the list above the navigation bar. */
    private fun applyInsets(root: View) {
        root.setOnApplyWindowInsetsListener { view, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            view.setPadding(0, top, 0, bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun applyWindowTheme() {
        window.statusBarColor = LPalette.BG
        window.navigationBarColor = LPalette.BG
        window.setBackgroundDrawable(ColorDrawable(LPalette.BG))
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
    }

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(6))
        }
        val back = TextView(this).apply {
            text = "←"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(LPalette.TEXT)
            background = ripple(LPalette.SURFACE_HIGH, 24, oval = true)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            contentDescription = "Back"
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }
        bar.addView(back)
        bar.addView(TextView(this).apply {
            text = "Library"
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(LPalette.TEXT)
            setPadding(dp(12), 0, 0, 0)
            contentDescription = "Library"
        })
        bar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        clearAction = TextView(this).apply {
            text = "Clear"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(LPalette.RED)
            background = ripple(LPalette.SURFACE_HIGH, 12)
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
            )
            contentDescription = "Clear library"
            isClickable = true
            isFocusable = true
            setOnClickListener { confirmClear() }
        }
        bar.addView(clearAction)
        return bar
    }

    /** Rebuilds the list from storage; safe against corrupt data (the store never throws). */
    private fun render() {
        list.removeAllViews()
        val entries = try {
            store.listEntries()
        } catch (t: Throwable) {
            emptyList<LibraryEntry>()
        }
        if (entries.isEmpty()) {
            clearAction.visibility = View.GONE
            list.addView(emptyView())
            return
        }
        clearAction.visibility = View.VISIBLE
        for (entry in entries) {
            list.addView(entryRow(entry))
        }
    }

    private fun emptyView(): TextView = TextView(this).apply {
        text = EMPTY_TEXT
        textSize = 14f
        setTextColor(LPalette.MUTED)
        gravity = Gravity.CENTER
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(dp(24), dp(72), dp(24), dp(24))
    }

    private fun entryRow(entry: LibraryEntry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(LPalette.SURFACE, 18)
            setPadding(dp(14), dp(12), dp(6), dp(12))
            isClickable = true
            isFocusable = true
            contentDescription = "Library entry"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setOnClickListener { openEntry(entry) }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        texts.addView(TextView(this).apply {
            text = entry.title.ifBlank { entry.url }
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(LPalette.TEXT)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val meta = ArrayList<String>(3)
        val site = siteName(entry)
        if (site.isNotEmpty()) meta.add(site)
        meta.add(if (entry.finished) "Finished" else "${entry.percent}%")
        val updated = relativeTime(entry.updatedAt)
        if (updated.isNotEmpty()) meta.add(updated)
        texts.addView(TextView(this).apply {
            text = meta.joinToString(" · ")
            textSize = 12f
            setTextColor(if (entry.finished) LPalette.ACCENT else LPalette.MUTED)
            setPadding(0, dp(3), 0, 0)
        })
        if (!entry.finished && entry.percent in 1..99) {
            texts.addView(progressTrack(entry.percent))
        }
        card.addView(texts)
        card.addView(deleteButton(entry))
        return card
    }

    private fun progressTrack(percent: Int): View {
        val clamped = percent.coerceIn(0, 100)
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(LPalette.SURFACE_HIGH, 2)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3)
            ).apply {
                topMargin = dp(8)
                marginEnd = dp(10)
            }
        }
        track.addView(View(this).apply {
            background = rounded(LPalette.ACCENT, 2)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, clamped / 100f)
        })
        track.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - clamped / 100f)
        })
        return track
    }

    private fun deleteButton(entry: LibraryEntry): TextView = TextView(this).apply {
        text = "✕"
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(LPalette.MUTED)
        background = ripple(LPalette.SURFACE_HIGH, 22, oval = true)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) }
        contentDescription = "Delete entry"
        isClickable = true
        isFocusable = true
        setOnClickListener {
            store.remove(entry.url)
            render()
            Toast.makeText(this@LibraryActivity, "Removed from library", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Opens the chapter in [MainActivity]. The intent is the contract MainActivity must handle:
     * ACTION_VIEW + data url + [EXTRA_RESUME_SENTENCE] + [EXTRA_FROM_LIBRARY].
     */
    private fun openEntry(entry: LibraryEntry) {
        if (entry.url.isBlank()) {
            Toast.makeText(this, "This chapter has no saved link", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(entry.url))
            .putExtra(EXTRA_RESUME_SENTENCE, entry.sentenceIndex)
            .putExtra(EXTRA_FROM_LIBRARY, true)
        try {
            startActivity(intent)
            finish()
        } catch (t: Throwable) {
            Toast.makeText(this, "Could not open the chapter", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear library?")
            .setMessage("This removes all saved chapters and reading positions from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                store.clear()
                render()
                Toast.makeText(this, "Library cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** Prefer the stored site label; fall back to the url host for entries saved by other code. */
    private fun siteName(entry: LibraryEntry): String {
        if (entry.site.isNotBlank()) return entry.site
        return try {
            Uri.parse(entry.url).host ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    private fun relativeTime(updatedAt: Long): String {
        if (updatedAt <= 0L) return ""
        val delta = System.currentTimeMillis() - updatedAt
        return when {
            delta < MINUTE_MS -> "just now"
            delta < HOUR_MS -> "${delta / MINUTE_MS} min ago"
            delta < DAY_MS -> "${delta / HOUR_MS} h ago"
            delta < WEEK_MS -> "${delta / DAY_MS} d ago"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(updatedAt))
        }
    }

    // ---- small design system (same constants as MainActivity/SettingsActivity) ----

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun ripple(color: Int, radiusDp: Int, oval: Boolean = false): RippleDrawable {
        val content = GradientDrawable().apply {
            setColor(color)
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(radiusDp).toFloat()
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(radiusDp).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(LPalette.RIPPLE), content, mask)
    }

    companion object {
        /** Extra: sentence index MainActivity should seek to after extraction. */
        const val EXTRA_RESUME_SENTENCE = "resumeSentence"

        /** Extra: true when the launch came from this library screen. */
        const val EXTRA_FROM_LIBRARY = "fromLibrary"

        /** Shown when no chapter has been opened yet. */
        const val EMPTY_TEXT = "No chapters yet — open a page in NovelSpeak and start reading."

        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS
        private const val DAY_MS = 24 * HOUR_MS
        private const val WEEK_MS = 7 * DAY_MS
    }
}

private object LPalette {
    val BG = 0xFF0F1115.toInt()
    val SURFACE = 0xFF171A21.toInt()
    val SURFACE_HIGH = 0xFF222833.toInt()
    val BORDER = 0xFF2C3442.toInt()
    val TEXT = 0xFFF3F6F9.toInt()
    val MUTED = 0xFF9AA6B6.toInt()
    val ACCENT = 0xFF6EE7B7.toInt()
    val AMBER = 0xFFF6C453.toInt()
    val RED = 0xFFF87171.toInt()
    val RIPPLE = 0x33FFFFFF
}
