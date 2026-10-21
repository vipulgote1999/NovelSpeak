package app.novelspeak

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Engine + model + voice settings.
 *
 * Backends: Android system TTS (always available, no download) and sherpa-onnx neural voices
 * (Kokoro / Pocket). Tuning knobs mirror the reference apps' exposed settings: threads (NekoSpeak
 * exposes 6 by default, HayaiTTS exposes it too), NNAPI toggle (HayaiTTS "Hardware Acceleration"),
 * plus the sentence prefetch depth that drives the streaming pipeline.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var container: LinearLayout
    private lateinit var status: TextView
    private var installing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }
        scroll.addView(container)
        setContentView(scroll)

        status = TextView(this).apply { setTextColor(Color.DKGRAY); textSize = 12f }
        build()
        maybeAskNotifications()
    }

    private fun header(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            textSize = 16f
            setPadding(0, 24, 0, 8)
        })
    }

    private fun note(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 8)
        })
    }

    private fun build() {
        header("Speech engine")
        val group = RadioGroup(this)
        val sys = RadioButton(this).apply { text = "Android system TTS (instant, offline, all languages)"; id = 1 }
        val neu = RadioButton(this).apply { text = "Neural voices — Kokoro / Pocket (sherpa-onnx, download once)"; id = 2 }
        group.addView(sys)
        group.addView(neu)
        group.check(if (prefs.engine == Prefs.ENGINE_SHERPA) 2 else 1)
        group.setOnCheckedChangeListener { _, id ->
            prefs.engine = if (id == 2) Prefs.ENGINE_SHERPA else Prefs.ENGINE_SYSTEM
            NovelSpeakApp.instance.reader.stop()
            build()
        }
        container.addView(group)

        header("Neural model")
        for (m in ModelManager.MODELS) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val label = TextView(this).apply {
                text = "${m.label}\n${m.sizeMb} MB · ${m.sha256.take(8)}…"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            val installed = ModelManager.isInstalled(this, m.id)
            row.addView(Button(this).apply {
                text = if (installed) "Use" else "Install"
                setOnClickListener {
                    if (installed) {
                        prefs.modelId = m.id
                        status.text = "Active model: ${m.label}"
                        NovelSpeakApp.instance.reader.stop()
                    } else {
                        downloadModel(m)
                    }
                }
            })
            if (installed) {
                row.addView(Button(this).apply {
                    text = "Delete"
                    setOnClickListener {
                        ModelManager.delete(this@SettingsActivity, m.id)
                        status.text = "Deleted ${m.label}"
                        build()
                    }
                })
            }
            container.addView(row)
        }
        note("Active: ${ModelManager.model(prefs.modelId)?.label ?: prefs.modelId}")

        header("Voice")
        val engineVoices = currentVoices()
        if (engineVoices.isEmpty()) {
            note("No selectable voices for the current engine. System TTS voices are chosen from the device's TTS settings.")
        } else {
            for ((id, label) in engineVoices) {
                val rb = RadioButton(this).apply {
                    text = label
                    isChecked = (id == currentVoiceId())
                    setOnClickListener { applyVoice(id) }
                }
                container.addView(rb)
            }
        }

        header("Tuning")
        seekRow("Speech speed", 50, 250, (prefs.speed * 100).toInt()) { v ->
            prefs.speed = v / 100f
            "%.2f×".format(prefs.speed)
        }
        seekRow("Sentence prefetch (streaming look-ahead)", 0, 5, prefs.prefetch) { v ->
            prefs.prefetch = v
            "$v sentence(s)"
        }
        seekRow("Synthesis threads", 1, Runtime.getRuntime().availableProcessors(), prefs.threads) { v ->
            prefs.threads = v
            "$v thread(s)"
        }
        seekRow("Max sentence length", 80, 400, prefs.maxSentenceChars) { v ->
            prefs.maxSentenceChars = v
            "$v chars"
        }

        container.addView(CheckBox(this).apply {
            text = "Hardware acceleration (NNAPI) — falls back to CPU if unsupported"
            isChecked = prefs.nnapi
            setOnCheckedChangeListener { _, b -> prefs.nnapi = b }
        })
        container.addView(CheckBox(this).apply {
            text = "Highlight spoken sentence and auto-scroll"
            isChecked = prefs.highlight
            setOnCheckedChangeListener { _, b -> prefs.highlight = b }
        })

        container.addView(status)
        container.addView(Button(this).apply {
            text = "Reset to tuned defaults"
            setOnClickListener {
                prefs.speed = 1.0f
                prefs.prefetch = 2
                prefs.threads = 4
                prefs.nnapi = false
                prefs.maxSentenceChars = 220
                prefs.highlight = true
                status.text = "Defaults restored"
                build()
            }
        })
    }

    private fun seekRow(label: String, min: Int, max: Int, value: Int, onChange: (Int) -> String) {
        val title = TextView(this).apply { text = "$label: ${onChange(value)}"; setPadding(0, 16, 0, 0) }
        container.addView(title)
        container.addView(SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    title.text = "$label: ${onChange(p + min)}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
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
        if (prefs.engine == Prefs.ENGINE_SHERPA && prefs.modelId != Prefs.MODEL_POCKET) prefs.kokoroSid.toString() else ""

    private fun applyVoice(id: String) {
        if (prefs.modelId == Prefs.MODEL_POCKET) {
            prefs.pocketVoicePath = ""
        } else {
            id.toIntOrNull()?.let { prefs.kokoroSid = it }
        }
        status.text = "Voice updated"
    }

    private fun downloadModel(m: ModelManager.Model) {
        if (installing) {
            Toast.makeText(this, "An install is already running", Toast.LENGTH_SHORT).show()
            return
        }
        installing = true
        status.text = "Downloading ${m.label}…"
        Thread {
            var lastMessage = ""
            val ok = ModelManager.install(this, m) { pct, msg ->
                lastMessage = msg
                runOnUiThread { status.text = if (pct < 0) "${m.label}: $msg" else "Downloading ${m.label}: $msg" }
            }
            runOnUiThread {
                installing = false
                if (ok) {
                    prefs.modelId = m.id
                    prefs.engine = Prefs.ENGINE_SHERPA
                    status.text = "Installed ${m.label} — active"
                    build()
                } else {
                    status.text = if (lastMessage.isNotEmpty()) lastMessage else "Install failed"
                }
            }
        }.start()
    }

    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 10)
            } catch (e: Throwable) {}
        }
    }
}
