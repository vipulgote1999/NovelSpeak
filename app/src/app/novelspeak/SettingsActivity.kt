package app.novelspeak

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.novelspeak.adblock.AdBlockSelfTest
import java.io.File

/**
 * Engine + model + voice settings, styled as the same dark "reader" system as MainActivity.
 *
 * All existing controls are preserved: engine choice, model install/delete/use, voices,
 * reader/playback tuning, install progress and notification permission.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private var installing = false
    private var adblockUpdating = false
    private val ui = Handler(Looper.getMainLooper())
    private var installStartedAt = 0L
    private var lastMessage = ""
    private var wake: PowerManager.WakeLock? = null
    private var ticker: Runnable? = null
    private val voiceRows = ArrayList<ChoiceRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWindowTheme()
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SPalette.BG)
        }
        root.addView(buildTopBar())

        scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(20))
        }
        scroll.addView(container)
        root.addView(scroll)
        root.addView(buildFooter())
        setContentView(root)
        applyInsets(root)

        build()
    }

    /** Keep the top bar below the status bar and the footer above the nav bar. */
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

    private fun applyWindowTheme() {
        window.statusBarColor = SPalette.BG
        window.navigationBarColor = SPalette.BG
        window.setBackgroundDrawable(ColorDrawable(SPalette.BG))
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
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
            setTextColor(SPalette.TEXT)
            background = ripple(SPalette.SURFACE_HIGH, 24, oval = true)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            contentDescription = "Back"
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }
        bar.addView(back)
        bar.addView(TextView(this).apply {
            text = "Settings"
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(SPalette.TEXT)
            setPadding(dp(12), 0, 0, 0)
        })
        return bar
    }

    private fun buildFooter(): LinearLayout {
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SPalette.SURFACE, 18)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), 0, dp(16), dp(8)) }
        }
        status = TextView(this).apply {
            text = "Changes are saved instantly"
            textSize = 12f
            setTextColor(SPalette.MUTED)
            maxLines = 3
        }
        footer.addView(status)
        footer.addView(pillButton("Reset to tuned defaults", danger = false, topMarginDp = 8) {
            prefs.speed = 1.0f
            prefs.prefetch = 4
            prefs.threads = 4
            prefs.nnapi = false
            prefs.maxSentenceChars = Prefs.DEFAULT_MAX_CHARS
            prefs.highlight = true
            prefs.autoScroll = true
            prefs.readerMode = true
            status.text = "Defaults restored"
            build()
        })
        return footer
    }

    private fun build() {
        val y = scroll.scrollY
        container.removeAllViews()
        voiceRows.clear()

        section("SPEECH ENGINE")
        val engineCard = card()
        engineCard.addView(ChoiceRow(
            "Android system TTS",
            "Instant, offline, uses the device's installed voices",
            prefs.engine != Prefs.ENGINE_SHERPA
        ) {
            selectEngine(false)
        })
        engineCard.addView(divider())
        engineCard.addView(ChoiceRow(
            "Neural voices",
            "Kokoro / Pocket via sherpa-onnx · download once",
            prefs.engine == Prefs.ENGINE_SHERPA
        ) {
            selectEngine(true)
        })
        container.addView(engineCard)

        section("NEURAL MODEL")
        val modelCard = card()
        ModelManager.MODELS.forEachIndexed { index, model ->
            if (index > 0) modelCard.addView(divider())
            modelCard.addView(modelBlock(model))
        }
        val active = ModelManager.model(prefs.modelId)?.label ?: prefs.modelId
        modelCard.addView(note("Active model: $active"))
        container.addView(modelCard)

        section("VOICE")
        val voiceCard = card()
        val voices = currentVoices()
        if (voices.isEmpty()) {
            voiceCard.addView(note(
                "No selectable voices for this engine. System TTS voices are chosen from " +
                    "the device's text-to-speech settings."
            ))
        } else {
            for ((id, label) in voices) {
                val row = ChoiceRow(label, null, id == currentVoiceId()) {
                    applyVoice(id)
                    voiceRows.forEach { it.setChecked(it.tag == id) }
                    status.text = "Voice: $label"
                }
                row.tag = id
                voiceRows.add(row)
                voiceCard.addView(row)
            }
        }
        container.addView(voiceCard)

        section("TUNING")
        val tuningCard = card()
        sliderRow(
            tuningCard, "Speech speed", 50, 250, (prefs.speed * 100).toInt()
        ) { v ->
            prefs.speed = v / 100f
            "%.2f×".format(prefs.speed)
        }
        sliderRow(tuningCard, "Sentence look-ahead", 0, 5, prefs.prefetch) { v ->
            prefs.prefetch = v
            "$v sentence(s)"
        }
        sliderRow(
            tuningCard, "Synthesis threads", 1,
            Runtime.getRuntime().availableProcessors(), prefs.threads
        ) { v ->
            prefs.threads = v
            "$v thread(s)"
        }
        sliderRow(tuningCard, "Max sentence length", 80, 400, prefs.maxSentenceChars) { v ->
            prefs.maxSentenceChars = v
            "$v chars"
        }
        container.addView(tuningCard)

        section("READING")
        val readingCard = card()
        toggleRow(
            readingCard, "Hardware acceleration (NNAPI)",
            "Falls back to CPU when the provider is unsupported", prefs.nnapi
        ) { prefs.nnapi = it }
        toggleRow(
            readingCard, "Highlight spoken paragraph",
            "Keep the active paragraph tinted while reading", prefs.highlight
        ) { prefs.highlight = it }
        toggleRow(
            readingCard, "Auto-scroll",
            "Center the active paragraph as playback advances", prefs.autoScroll
        ) { prefs.autoScroll = it }
        toggleRow(
            readingCard, "Auto mode",
            "Read pages automatically and continue to the next chapter", prefs.autoMode
        ) { prefs.autoMode = it }
        toggleRow(
            readingCard, "Reader mode",
            "Hide site chrome, ads and verification dialogs", prefs.readerMode
        ) { prefs.readerMode = it }
        container.addView(readingCard)

        section("PERMISSIONS & BACKGROUND")
        val permissionsCard = card()
        notificationPermissionRow(permissionsCard)
        permissionsCard.addView(divider())
        backgroundPolicyRow(permissionsCard)
        permissionsCard.addView(note(
            "NovelSpeak requests notifications only. It does not use the microphone, camera, " +
                "location or contacts, and it does not read your files. Network, wake-lock and " +
                "media-playback permissions are normal permissions granted at install time."
        ))
        container.addView(permissionsCard)

        section("AD BLOCKING")
        val adblockCard = card()
        toggleRow(
            adblockCard, "Block ads and trackers",
            "EasyList + EasyPrivacy + uBlock lists, token-indexed matcher",
            prefs.adblockEnabled
        ) { enabled ->
            prefs.adblockEnabled = enabled
            if (enabled) AdBlocker.reload()
            status.text = AdBlocker.statusText()
        }
        toggleRow(
            adblockCard, "Hide ad containers and overlays",
            "Cosmetic filters, click-layers and fake notification nags",
            prefs.adblockCosmetic
        ) { prefs.adblockCosmetic = it }
        toggleRow(
            adblockCard, "Block popups and redirects",
            "Refuse suspicious top-level ad landing domains",
            prefs.adblockPopups
        ) { prefs.adblockPopups = it }
        adblockCard.addView(note(AdBlocker.statusText()))

        val adblockActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        adblockActions.addView(pillButton("Update lists", primary = true) {
            updateAdBlockLists()
        })
        adblockActions.addView(pillButton("Self-test") {
            status.text = AdBlockSelfTest.run()
        })
        adblockActions.addView(pillButton("Status") {
            status.text = AdBlocker.statusText()
        })
        adblockCard.addView(adblockActions)
        adblockCard.addView(note(
            "Lists: EasyList, EasyPrivacy, uBlock filters, uBlock Annoyances and uBlock " +
                "Badware. Sources and licences are in assets/adblock/NOTICE.txt. Tap “Ad” in " +
                "the browser bar to disable blocking for one site."
        ))
        container.addView(adblockCard)

        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
    }

    private fun selectEngine(sherpa: Boolean) {
        prefs.engine = if (sherpa) Prefs.ENGINE_SHERPA else Prefs.ENGINE_SYSTEM
        NovelSpeakApp.instance.reader.stop()
        status.text = if (sherpa) "Neural engine selected" else "System TTS selected"
        build()
    }

    private fun modelBlock(m: ModelManager.Model): View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = m.label
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(SPalette.TEXT)
        })
        texts.addView(TextView(this).apply {
            text = "${m.sizeMb} MB · ${m.sha256.take(8)}…"
            textSize = 12f
            setTextColor(SPalette.MUTED)
            setPadding(0, dp(2), 0, 0)
        })
        top.addView(texts)
        val installed = ModelManager.isInstalled(this, m.id)
        val active = prefs.modelId == m.id
        if (installed && active) {
            top.addView(TextView(this).apply {
                text = "ACTIVE"
                textSize = 10f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(SPalette.ACCENT)
                background = rounded(SPalette.SURFACE_HIGH, 8)
                setPadding(dp(8), dp(3), dp(8), dp(3))
            })
        }
        block.addView(top)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        if (installed) {
            if (!active) {
                actions.addView(pillButton("Use", primary = true) {
                    prefs.modelId = m.id
                    NovelSpeakApp.instance.reader.stop()
                    status.text = "Active model: ${m.label}"
                    build()
                })
            }
            actions.addView(pillButton("Delete", danger = true) {
                AlertDialog.Builder(this)
                    .setTitle("Delete ${m.label}?")
                    .setMessage(
                        "The model files (${m.sizeMb} MB) will be removed. " +
                            "Nothing else is affected; you can install it again later."
                    )
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        ModelManager.delete(this, m.id)
                        status.text = "Deleted ${m.label}"
                        build()
                    }
                    .show()
            })
        } else {
            actions.addView(pillButton("Install", primary = true) {
                downloadModel(m)
            })
        }
        block.addView(actions)
        return block
    }

    private fun section(title: String) {
        container.addView(TextView(this).apply {
            text = title
            textSize = 11f
            letterSpacing = 0.1f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(SPalette.MUTED)
            setPadding(dp(4), dp(18), 0, dp(8))
        })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SPalette.SURFACE, 18)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { setMargins(0, dp(2), 0, dp(2)) }
        setBackgroundColor(SPalette.BORDER)
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(SPalette.MUTED)
        setPadding(0, dp(8), 0, 0)
    }

    private fun sliderRow(
        parent: LinearLayout,
        label: String,
        min: Int,
        max: Int,
        value: Int,
        onChange: (Int) -> String
    ) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(SPalette.TEXT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueView = TextView(this).apply {
            text = onChange(value)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(SPalette.ACCENT)
            gravity = Gravity.END
        }
        titleRow.addView(title)
        titleRow.addView(valueView)
        wrap.addView(titleRow)
        wrap.addView(SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            progressTintList = ColorStateList.valueOf(SPalette.ACCENT)
            thumbTintList = ColorStateList.valueOf(SPalette.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(SPalette.BORDER)
            splitTrack = false
            setPadding(dp(12), dp(2), dp(12), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    valueView.text = onChange(p + min)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
        parent.addView(wrap)
    }

    private fun toggleRow(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            minimumHeight = dp(56)
            isClickable = true
            isFocusable = true
            background = ripple(SPalette.SURFACE_HIGH, 12)
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(SPalette.TEXT)
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(SPalette.MUTED)
            setPadding(0, dp(1), 0, 0)
        })
        row.addView(texts)
        val sw = Switch(this).apply {
            isChecked = checked
            showText = false
            thumbTintList = switchThumbColors()
            trackTintList = switchTrackColors()
            contentDescription = title
            setOnCheckedChangeListener { _, b -> onChange(b) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        parent.addView(row)
    }

    private fun switchThumbColors(): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(SPalette.ACCENT, SPalette.MUTED)
    )

    private fun switchTrackColors(): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(0x666EE7B7, SPalette.BORDER)
    )

    private fun pillButton(
        label: String,
        primary: Boolean = false,
        danger: Boolean = false,
        topMarginDp: Int = 0,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(
            when {
                primary -> SPalette.BG
                danger -> SPalette.RED
                else -> SPalette.TEXT
            }
        )
        background = ripple(
            if (primary) SPalette.ACCENT else SPalette.SURFACE_HIGH, 12
        )
        setPadding(dp(14), 0, dp(14), 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)
        ).apply {
            marginEnd = dp(8)
            topMargin = dp(topMarginDp)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun currentVoices(): List<Pair<String, String>> {
        return if (prefs.engine == Prefs.ENGINE_SHERPA) {
            if (prefs.modelId == Prefs.MODEL_POCKET) {
                listOf("" to "Default narrator (bria clip bundled with the model)")
            } else {
                Prefs.KOKORO_VOICES.mapIndexed { i, p -> i.toString() to p.second }
            }
        } else {
            emptyList()
        }
    }

    private fun currentVoiceId(): String =
        if (prefs.engine == Prefs.ENGINE_SHERPA && prefs.modelId != Prefs.MODEL_POCKET) {
            prefs.kokoroSid.toString()
        } else {
            ""
        }

    private fun applyVoice(id: String) {
        if (prefs.modelId == Prefs.MODEL_POCKET) {
            prefs.pocketVoicePath = ""
        } else {
            id.toIntOrNull()?.let { prefs.kokoroSid = it }
        }
    }

    private fun downloadModel(m: ModelManager.Model) {
        if (installing) {
            Toast.makeText(this, "An install is already running", Toast.LENGTH_SHORT).show()
            return
        }
        installing = true
        lastMessage = "Starting ${m.label}…"
        installStartedAt = System.currentTimeMillis()
        status.text = lastMessage
        DiagLog.line(this, "ui install tap model=${m.id}")

        // Long download + single-threaded bzip2 decode: keep CPU and screen alive so the install
        // cannot be suspended when the screen times out.
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "novelspeak:model-install").apply {
                setReferenceCounted(false)
                acquire(20 * 60 * 1000L)
            }
        } catch (e: Throwable) {
        }
        startTicker()

        Thread {
            var lastUiUpdate = 0L
            val ok = ModelManager.install(this, m) { pct, msg ->
                lastMessage = if (pct >= 0) "Downloading ${m.label}: $msg" else "${m.label}: $msg"
                val now = System.currentTimeMillis()
                if (now - lastUiUpdate > 200) {
                    lastUiUpdate = now
                    runOnUiThread { status.text = withElapsed(lastMessage) }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    stopInstallUi()
                    return@runOnUiThread
                }
                stopInstallUi()
                DiagLog.line(this, "ui install result ok=$ok last=\"${lastMessage.take(160)}\"")
                if (ok) {
                    prefs.modelId = m.id
                    prefs.engine = Prefs.ENGINE_SHERPA
                    status.text = "Installed ${m.label} — active"
                    build()
                } else {
                    status.text = lastMessage.ifEmpty { "Install failed" }
                }
            }
        }.start()
    }

    private fun updateAdBlockLists() {
        if (adblockUpdating) {
            Toast.makeText(this, "A list update is already running", Toast.LENGTH_SHORT).show()
            return
        }
        adblockUpdating = true
        status.text = "Updating filter lists…"
        Thread {
            val ok = AdBlocker.updateLists(this) { msg ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) status.text = msg
                }
            }
            runOnUiThread {
                adblockUpdating = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                status.text = AdBlocker.statusText()
                Toast.makeText(
                    this,
                    if (ok) "Filter lists updated" else "List update failed — using bundled lists",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun withElapsed(msg: String): String {
        if (!installing) return msg
        val s = ((System.currentTimeMillis() - installStartedAt) / 1000).toInt()
        val mm = s / 60
        val ss = s % 60
        return msg + "  ·  " + mm + ":" + if (ss < 10) "0$ss" else ss.toString()
    }

    private fun startTicker() {
        stopTicker()
        val r = object : Runnable {
            override fun run() {
                if (!installing) return
                if (isFinishing || isDestroyed) {
                    stopInstallUi()
                    return
                }
                status.text = withElapsed(lastMessage)
                ui.postDelayed(this, 1000)
            }
        }
        ticker = r
        ui.postDelayed(r, 1000)
    }

    private fun stopTicker() {
        ticker?.let { ui.removeCallbacks(it) }
        ticker = null
    }

    private fun stopInstallUi() {
        installing = false
        stopTicker()
        try {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Throwable) {
        }
        try {
            if (wake?.isHeld == true) wake?.release()
        } catch (e: Throwable) {
        }
        wake = null
    }

    override fun onDestroy() {
        if (!installing) stopInstallUi()
        super.onDestroy()
    }

    private fun notificationPermissionRow(parent: LinearLayout) {
        val granted = AppPermissions.notificationsGranted(this)
        val channelOn = PlaybackService.channelEnabled(this)
        val wasAsked = AppPermissions.wasNotificationAsked(this)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        row.addView(TextView(this).apply {
            text = "Playback notification"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(SPalette.TEXT)
        })
        row.addView(TextView(this).apply {
            text = AppPermissions.notificationStatus(this@SettingsActivity)
            textSize = 11f
            setTextColor(
                when {
                    !granted -> SPalette.AMBER
                    !channelOn -> SPalette.AMBER
                    else -> SPalette.MUTED
                }
            )
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(TextView(this).apply {
            text = "Channel: ${AppPermissions.notificationChannelStatus(this@SettingsActivity)}"
            textSize = 11f
            setTextColor(SPalette.MUTED)
            setPadding(0, dp(2), 0, 0)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        when {
            !granted && !wasAsked -> actions.addView(pillButton(
                "Allow notifications",
                primary = true
            ) {
                AppPermissions.requestNotifications(this)
            })
            !granted -> actions.addView(pillButton(
                "Open notification settings",
                primary = true
            ) {
                AppPermissions.openNotificationSettings(this)
            })
            !channelOn -> actions.addView(pillButton(
                "Open channel settings",
                primary = true
            ) {
                AppPermissions.openPlaybackChannelSettings(this)
            })
            else -> actions.addView(pillButton("Manage notifications") {
                AppPermissions.openNotificationSettings(this)
            })
        }
        row.addView(actions)
        parent.addView(row)
    }

    private fun backgroundPolicyRow(parent: LinearLayout) {
        val playing = NovelSpeakApp.instance.reader.state().playing
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        row.addView(TextView(this).apply {
            text = "Background playback"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(SPalette.TEXT)
        })
        row.addView(TextView(this).apply {
            text = if (playing) {
                "Active · foreground media service is running"
            } else {
                "Ready · starts automatically when you tap Read"
            }
            textSize = 11f
            setTextColor(if (playing) SPalette.ACCENT else SPalette.MUTED)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(TextView(this).apply {
            text = "Media playback stays active with the screen off and exposes transport " +
                "controls on the lock screen and in the notification shade."
            textSize = 11f
            setTextColor(SPalette.MUTED)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(TextView(this).apply {
            text = "If your device stops playback with the screen off, allow NovelSpeak in " +
                "system battery settings."
            textSize = 11f
            setTextColor(SPalette.MUTED)
            setPadding(0, dp(4), 0, 0)
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(pillButton("Battery settings") {
            AppPermissions.openBatterySettings(this)
        })
        row.addView(actions)
        parent.addView(row)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AppPermissions.REQ_NOTIFICATIONS) {
            if (AppPermissions.notificationsGranted(this)) {
                PlaybackService.refresh(this)
            }
            build()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!installing && !adblockUpdating && ::container.isInitialized) {
            build()
        }
    }

    // ---- small design system ----

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun ring(checked: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), if (checked) SPalette.ACCENT else SPalette.BORDER)
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
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask)
    }

    private inner class ChoiceRow(
        title: String,
        subtitle: String?,
        checked: Boolean,
        onClick: () -> Unit
    ) : LinearLayout(this@SettingsActivity) {
        private val ringView = FrameLayout(this@SettingsActivity)
        private val dot = View(this@SettingsActivity)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            minimumHeight = dp(56)
            background = ripple(SPalette.SURFACE_HIGH, 12)
            isClickable = true
            isFocusable = true

            ringView.layoutParams = LayoutParams(dp(22), dp(22))
            ringView.background = ring(checked)
            dot.layoutParams = FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER)
            dot.background = oval(SPalette.ACCENT)
            dot.visibility = if (checked) View.VISIBLE else View.INVISIBLE
            ringView.addView(dot)
            addView(ringView)

            val texts = LinearLayout(this@SettingsActivity).apply {
                orientation = VERTICAL
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(this@SettingsActivity).apply {
                text = title
                textSize = 14f
                setTextColor(SPalette.TEXT)
            })
            if (!subtitle.isNullOrEmpty()) {
                texts.addView(TextView(this@SettingsActivity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(SPalette.MUTED)
                    setPadding(0, dp(2), 0, 0)
                })
            }
            addView(texts)
            setOnClickListener { onClick() }
        }

        fun setChecked(checked: Boolean) {
            ringView.background = ring(checked)
            dot.visibility = if (checked) View.VISIBLE else View.INVISIBLE
        }
    }
}

private object SPalette {
    val BG = 0xFF0F1115.toInt()
    val SURFACE = 0xFF171A21.toInt()
    val SURFACE_HIGH = 0xFF222833.toInt()
    val BORDER = 0xFF2C3442.toInt()
    val TEXT = 0xFFF3F6F9.toInt()
    val MUTED = 0xFF9AA6B6.toInt()
    val ACCENT = 0xFF6EE7B7.toInt()
    val AMBER = 0xFFF6C453.toInt()
    val RED = 0xFFF87171.toInt()
}
