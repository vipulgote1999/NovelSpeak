package app.novelspeak

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.novelspeak.cast.BookCast
import app.novelspeak.cast.CastIds
import app.novelspeak.cast.CastMember
import app.novelspeak.cast.Confidence

/**
 * Branded bottom sheet for Story Cast.
 *
 * The platform `AlertDialog` made the feature's front door look like a system prompt. This sheet
 * uses the same Harbor-ink surface as the rest of the app, shows who speaks with which voice and
 * how confident the attribution is, and keeps every action at a 44–48 dp target. It works on a
 * phone and caps at 560 dp on a tablet.
 */
class CastSheet(
    private val activity: Activity,
    private val chapterTitle: String,
    private val cast: BookCast?,
    private val family: String,
    private val unresolved: Int,
    private val currentSpeakerId: String,
    private val storyCastOn: Boolean,
    private val canFixLine: Boolean,
    private val onToggle: () -> Unit,
    private val onRecast: (CastMember) -> Unit,
    private val onPreview: (CastMember) -> Unit,
    private val onFixLine: () -> Unit,
    private val onDismiss: () -> Unit
) {

    private val density = activity.resources.displayMetrics.density
    private val speakers = cast?.members
        ?.filter { it.dialogueLines > 0 }
        ?.sortedWith(compareByDescending<CastMember> { it.dialogueLines }.thenBy { it.name })
        .orEmpty()

    fun show() {
        val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val motion = MotionAndroid.animationsEnabled(activity)
        DiagLog.line(activity, "cast sheet motion=$motion")
        dialog.setOnDismissListener { onDismiss() }
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.SURFACE, 20, Palette.BORDER)
            setPadding(dp(20), dp(10), dp(20), dp(22))
        }

        sheet.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(View(activity).apply {
                background = rounded(Palette.BORDER, 2)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(4))
            })
        })

        sheet.addView(label("STORY CAST", 11f, Palette.ACCENT).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(14), 0, dp(4))
        })
        sheet.addView(label("Cast", 23f, Palette.TEXT).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        sheet.addView(label(chapterTitle.ifEmpty { "Current chapter" }, 12f, Palette.MUTED).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        })

        val count = speakers.size
        val summary = if (cast == null || count == 0) {
            "Not cast yet"
        } else {
            "Narrator + $count character" + (if (count == 1) "" else "s")
        }
        sheet.addView(label(summary, 13f, Palette.TEXT_SECONDARY).apply {
            setPadding(0, dp(10), 0, dp(6))
        })

        if (unresolved > 0) {
            sheet.addView(unresolvedBanner(unresolved, dialog))
        }

        sheet.addView(divider())

        val narratorVoice = cast?.narratorVoiceId?.let { VoiceCatalog.label(family, it) }
            ?: "Current narrator voice"
        sheet.addView(
            row(
                initial = "N",
                title = "Narrator",
                subtitle = narratorVoice,
                confidence = null,
                lines = 0,
                current = currentSpeakerId == CastIds.NARRATOR,
                onPreview = null,
                onChange = null
            )
        )

        if (speakers.isEmpty()) {
            sheet.addView(label(
                "Cast this chapter to give the narrator and every character a different on-device " +
                    "voice. The author's words are preserved; only speaker labels and voices are added.",
                13f,
                Palette.TEXT_SECONDARY
            ).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(6), 0, dp(10))
            })
        } else {
            for (member in speakers) {
                sheet.addView(
                    row(
                        initial = member.name.take(1).uppercase(),
                        title = member.name,
                        subtitle = member.voiceId?.let { VoiceCatalog.label(family, it) }
                            ?: "Auto-assigned",
                        confidence = member.confidence,
                        lines = member.dialogueLines,
                        current = currentSpeakerId == member.id,
                        onPreview = { onPreview(member) },
                        onChange = {
                            // Close the sheet first so the voice picker is not hidden behind it.
                            dialog.dismiss()
                            onRecast(member)
                        }
                    )
                )
            }
        }

        sheet.addView(divider())

        val primary = actionButton(
            if (storyCastOn) "Narrator only" else "Cast chapter",
            primary = true
        )
        primary.setOnClickListener {
            dialog.dismiss()
            onToggle()
        }
        sheet.addView(primary, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        val close = actionButton("Close", primary = false)
        close.setOnClickListener { dialog.dismiss() }
        sheet.addView(close, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // A 2x font scale can make the full roster taller than the screen. Scroll the sheet
        // rather than clipping the footer or the last character row.
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(sheet)
        }
        dialog.setContentView(scroll)
        val dialogWidth = minOf(
            activity.resources.displayMetrics.widthPixels - dp(16),
            dp(560)
        )
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setGravity(Gravity.BOTTOM)
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = window.attributes
            params.dimAmount = 0.58f
            params.width = dialogWidth
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            window.attributes = params
            window.setWindowAnimations(
                if (motion) android.R.style.Animation_Dialog else 0
            )
        }
        dialog.show()
        dialog.window?.let { window ->
            val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.92f).toInt()
            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                dialogWidth, View.MeasureSpec.EXACTLY
            )
            sheet.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            window.setLayout(dialogWidth, minOf(sheet.measuredHeight, maxHeight))
        }
    }

    private fun row(
        initial: String,
        title: String,
        subtitle: String,
        confidence: Confidence?,
        lines: Int,
        current: Boolean,
        onPreview: (() -> Unit)?,
        onChange: (() -> Unit)?
    ): LinearLayout {
        val largeText = activity.resources.configuration.fontScale >= 1.5f
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(10), dp(8), dp(6), dp(8))
            background = if (current) {
                rounded(withAlpha(Palette.ACCENT, 0.10f), Design.RADIUS_MD)
            } else {
                ripple(Palette.SURFACE, Design.RADIUS_MD)
            }
            isClickable = onChange != null
            isFocusable = onChange != null
            contentDescription = buildString {
                append(title)
                append(", ")
                append(subtitle)
                if (confidence != null) append(", ").append(confidenceLabel(confidence))
                if (lines > 0) append(", ").append(lines).append(" lines")
                if (current) append(", current speaker")
            }
            if (onChange != null) setOnClickListener { onChange() }
        }

        row.addView(TextView(activity).apply {
            text = initial
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(if (current) Palette.BG else Palette.ACCENT)
            background = rounded(if (current) Palette.ACCENT else Palette.SURFACE_HIGH, 18, oval = true)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })

        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(label(title, 15f, if (current) Palette.ACCENT else Palette.TEXT).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = if (largeText) 2 else 1
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(label(subtitle, 12f, Palette.MUTED).apply {
            maxLines = if (largeText) 2 else 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(texts)

        val right = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        if (confidence != null) {
            val text = if (largeText) {
                when (confidence) {
                    Confidence.HIGH -> "High"
                    Confidence.MEDIUM -> "Medium"
                    Confidence.LOW -> "Review"
                }
            } else {
                confidenceLabel(confidence)
            }
            right.addView(label(text, 10.5f, confidenceColor(confidence)))
        }
        val actions = LinearLayout(activity).apply {
            orientation = if (largeText) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(2), 0, 0)
        }
        if (onPreview != null) {
            actions.addView(smallAction(
                text = "Preview",
                description = "Preview voice for $title with their line"
            ) { onPreview() })
        }
        if (onChange != null) {
            actions.addView(smallAction(
                text = "Change",
                description = "Change voice for $title"
            ) { onChange() })
        }
        if (actions.childCount > 0) {
            right.addView(actions)
        } else {
            right.addView(label("From settings", 10.5f, Palette.FAINT))
        }
        row.addView(right)
        return row
    }

    private fun smallAction(
        text: String,
        description: String,
        onClick: () -> Unit
    ): TextView = TextView(activity).apply {
        this.text = text
        textSize = 11.5f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(Palette.ACCENT)
        background = ripple(Palette.SURFACE_HIGH, 12)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        minWidth = dp(62)
        minimumHeight = dp(Design.DENSE_TOUCH_DP)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(4); topMargin = dp(2) }
        contentDescription = description
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun unresolvedBanner(count: Int, dialog: Dialog): LinearLayout {
        val plural = count != 1
        val banner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(withAlpha(Palette.AMBER, 0.12f), Design.RADIUS_MD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            contentDescription = "$count line" + (if (plural) "s" else "") +
                " had no confident speaker and " + (if (plural) "stay" else "stays") +
                " with the narrator"
        }
        val line = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(TextView(activity).apply {
            text = "●"
            textSize = 12f
            setTextColor(Palette.AMBER)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        line.addView(label(
            "$count line" + (if (plural) "s" else "") + " had no confident speaker and " +
                (if (plural) "stay" else "stays") + " with the narrator.",
            12f,
            Palette.AMBER
        ).apply {
            setPadding(dp(8), 0, 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        banner.addView(line)

        if (canFixLine) {
            val fix = actionButton("Fix current line", primary = false).apply {
                setTextColor(Palette.AMBER)
                background = ripple(withAlpha(Palette.AMBER, 0.18f), 12)
                contentDescription = "Fix the current unresolved line"
                setOnClickListener {
                    dialog.dismiss()
                    onFixLine()
                }
            }
            banner.addView(fix, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(Design.DENSE_TOUCH_DP)
            ).apply { topMargin = dp(8) })
        } else {
            banner.addView(label(
                "Play an unresolved line, then reopen Cast to fix it.",
                11f,
                Palette.MUTED
            ).apply { setPadding(0, dp(6), 0, 0) })
        }
        return banner
    }

    private fun actionButton(text: String, primary: Boolean): TextView = TextView(activity).apply {
        this.text = text
        textSize = 14f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(if (primary) Palette.BG else Palette.TEXT)
        background = ripple(if (primary) Palette.ACCENT else Palette.SURFACE_HIGH, Design.RADIUS_MD)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        minimumHeight = dp(Design.TOUCH_DP)
        isClickable = true
        isFocusable = true
        contentDescription = text
    }

    private fun label(text: String, size: Float, color: Int): TextView = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        includeFontPadding = false
    }

    private fun divider(): View = View(activity).apply {
        setBackgroundColor(Palette.BORDER)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { setMargins(0, dp(8), 0, dp(2)) }
    }

    private fun confidenceLabel(confidence: Confidence): String = when (confidence) {
        Confidence.HIGH -> "High confidence"
        Confidence.MEDIUM -> "Medium confidence"
        Confidence.LOW -> "Review"
    }

    private fun confidenceColor(confidence: Confidence): Int = when (confidence) {
        Confidence.HIGH -> Palette.ACCENT
        Confidence.MEDIUM -> Palette.AMBER
        Confidence.LOW -> Palette.FAINT
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha.coerceIn(0f, 1f) * 255).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null, oval: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = if (oval) dp(radiusDp).toFloat() else dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun ripple(color: Int, radiusDp: Int, oval: Boolean = false): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(Palette.RIPPLE),
            rounded(color, radiusDp, oval = oval),
            null
        )

    private fun dp(v: Int): Int = (v * density).toInt()
}
