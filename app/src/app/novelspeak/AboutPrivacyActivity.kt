package app.novelspeak

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Privacy, permission and licence disclosure — the page P0.8/P0.10 promised for store review and
 * for users who want to know exactly what leaves the device.
 *
 * Programmatic UI on purpose: this project has no XML layouts and no AndroidX. Registered in the
 * manifest by the Lead as `app.novelspeak.AboutPrivacyActivity` (non-exported). Opened from
 * Settings → Diagnostics & privacy → "Open privacy".
 */
class AboutPrivacyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWindowTheme()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }
        root.addView(buildTopBar())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(28))
        }
        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
        applyInsets(root)

        populate(content)
    }

    private fun populate(content: LinearLayout) {
        heading(content, "PRIVACY")
        paragraph(
            content,
            "NovelSpeak has no account system, no analytics, no advertising and no third-party " +
                "SDKs. Your reading position, library, pronunciation rules and settings stay on " +
                "this device. Nothing is uploaded automatically."
        )

        heading(content, "WHAT USES THE NETWORK")
        bullet(content, "Pages you open in the browser are loaded from their own site, exactly as a browser would fetch them.")
        bullet(content, "Neural voices are downloaded once, on demand, from the official k2-fsa/sherpa-onnx release when you tap Install.")
        bullet(content, "Ad-block filter lists are updated only when you tap Update lists, from the EasyList/uBlock sources listed below.")
        bullet(content, "There are no other destinations. NovelSpeak does not contact an update server, an analytics endpoint or an ad network.")
        paragraph(
            content,
            "Everything else works offline: extraction, system TTS, playback, the reader, the " +
                "library and the network-free features in Settings. Neural voices work fully " +
                "offline once their model file has been installed."
        )

        heading(content, "DIAGNOSTICS")
        paragraph(
            content,
            "Share diagnostics creates a zip on this device containing the app version, SDK, " +
                "device model, ABI, settings snapshot (URLs, tokens and pronunciation rules are " +
                "redacted), the event log and the last crash if one exists. It is capped at 2 MB, " +
                "handed to the app you choose through the system share sheet, and never uploaded " +
                "by NovelSpeak. Delete it like any other file."
        )

        heading(content, "PERMISSIONS")
        permission(
            content, "INTERNET",
            "Load pages, download a neural voice model once, update filter lists."
        )
        permission(
            content, "ACCESS_NETWORK_STATE",
            "Check connectivity before a model download or list update."
        )
        permission(
            content, "WAKE_LOCK",
            "Keep playback alive while the screen is off."
        )
        permission(
            content, "FOREGROUND_SERVICE · FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "Run screen-off playback as a media foreground service with lock-screen controls."
        )
        permission(
            content, "POST_NOTIFICATIONS",
            "Show the playback notification (Android 13+; requested on first use, optional)."
        )
        paragraph(
            content,
            "NovelSpeak does not request the microphone, camera, location, contacts, calendar " +
                "or storage permissions. System TTS voices come from your device's TTS engine; " +
                "neural voices are files you choose to install in the app's private storage."
        )

        heading(content, "LICENCES")
        licence(
            content, "NovelSpeak",
            "Apache License 2.0. The full licence text ships in the repository's LICENSE file."
        )
        licence(
            content, "Mozilla Readability",
            "Apache License 2.0. Used to extract the main article text (assets/readability.js)."
        )
        licence(
            content, "EasyList · EasyPrivacy · uBlock Origin filters",
            "EasyList/EasyPrivacy are CC BY-SA 3.0; uBlock Origin filter lists are GPLv3. " +
                "Lists are bundled unmodified and redistributed under their own terms — see " +
                "assets/adblock/NOTICE.txt for sources and attributions."
        )
        licence(
            content, "sherpa-onnx",
            "Apache License 2.0 (k2-fsa/sherpa-onnx). Runs the offline neural voices."
        )
        licence(
            content, "onnxruntime",
            "MIT License (Microsoft). Inference runtime bundled inside sherpa-onnx."
        )
        licence(
            content, "Kotlin standard library",
            "Apache License 2.0."
        )
        paragraph(
            content,
            "If you redistribute this app or its assets, keep these notices and the upstream " +
                "licence texts with it."
        )

        heading(content, "VERSION")
        paragraph(content, versionLine())
        paragraph(
            content,
            "NovelSpeak is a local-first reader: it turns any web page into speech and keeps " +
                "your place without an account. Questions or bugs? Use Share diagnostics in " +
                "Settings and send the file to the developer yourself."
        )
    }

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
        }
        val back = TextView(this).apply {
            text = "←"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Palette.TEXT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.SURFACE_HIGH)
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            contentDescription = "Back"
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }
        bar.addView(back)
        bar.addView(TextView(this).apply {
            text = "Privacy & licences"
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.TEXT)
            setPadding(dp(12), 0, 0, 0)
        })
        return bar
    }

    private fun heading(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 11f
            letterSpacing = 0.1f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.MUTED)
            setPadding(dp(2), dp(20), 0, dp(8))
        })
    }

    private fun paragraph(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Palette.TEXT)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
    }

    private fun bullet(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = "•  $text"
            textSize = 14f
            setTextColor(Palette.TEXT)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(4), 0, dp(4))
        })
    }

    private fun permission(parent: LinearLayout, name: String, detail: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.SURFACE, 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        card.addView(TextView(this).apply {
            text = name
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.ACCENT)
        })
        card.addView(TextView(this).apply {
            text = detail
            textSize = 13f
            setTextColor(Palette.MUTED)
            setPadding(0, dp(2), 0, 0)
        })
        parent.addView(card)
    }

    private fun licence(parent: LinearLayout, title: String, detail: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.SURFACE, 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.TEXT)
        })
        card.addView(TextView(this).apply {
            text = detail
            textSize = 12f
            setTextColor(Palette.MUTED)
            setPadding(0, dp(2), 0, 0)
        })
        parent.addView(card)
    }

    private fun versionLine(): String {
        val name = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (t: Throwable) {
            "?"
        }
        return "NovelSpeak $name · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    private fun applyWindowTheme() {
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG
        window.setBackgroundDrawable(ColorDrawable(Palette.BG))
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
    }

    /** Keep the top bar below the status bar and the last paragraph above the nav bar. */
    private fun applyInsets(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
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
            v.setPadding(0, top, 0, bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private object Palette {
        val BG = 0xFF0F1115.toInt()
        val SURFACE = 0xFF171A21.toInt()
        val SURFACE_HIGH = 0xFF222833.toInt()
        val TEXT = 0xFFF3F6F9.toInt()
        val MUTED = 0xFF9AA6B6.toInt()
        val ACCENT = 0xFF6EE7B7.toInt()
    }
}
