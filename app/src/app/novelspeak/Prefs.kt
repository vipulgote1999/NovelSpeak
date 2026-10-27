package app.novelspeak

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings + tuning defaults.
 *
 * Every default here is copied from an already-shipped, already-tuned implementation rather than
 * guessed. Source of each value is noted inline and collected in OPTIMIZATIONS.md:
 *  - NekoSpeak v1.4.2 (MIT)  : cpuThreads=6, pocket lsd=10/temp=0.7/eos=3/chunk=15 frames,
 *                              kokoro stream chunk=150 tokens (400 for Kitten), speed=1.0
 *  - HayaiTTS v2.5.1 (GPL)   : engine LRU=2, numSteps=8, silenceScale=1.0, speed=1.0,
 *                              AudioTrack LOW_LATENCY mono 16-bit with buffer=max(bytes,4096)
 */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("novelspeak", Context.MODE_PRIVATE)

    init {
        // One-time tuning migration. Older builds persisted prefetch=2 / max=220 even when the
        // user never touched those sliders. The async PCM writer + shorter chunks need 3 / 150
        // to keep generation ahead of playback.
        if (!sp.getBoolean(K_TUNING_V2, false)) {
            val prefetch = sp.getInt(K_PREFETCH, 4)
            val maxChars = sp.getInt(K_MAX_CHARS, 90)
            val edit = sp.edit()
            if (prefetch == 2) edit.putInt(K_PREFETCH, 4)
            if (maxChars == 220) edit.putInt(K_MAX_CHARS, 90)
            edit.putBoolean(K_TUNING_V2, true).apply()
        }
        if (!sp.getBoolean(K_TUNING_V3, false)) {
            val prefetch = sp.getInt(K_PREFETCH, 4)
            val maxChars = sp.getInt(K_MAX_CHARS, 90)
            val edit = sp.edit()
            if (prefetch == 3) edit.putInt(K_PREFETCH, 4)
            if (maxChars == 150) edit.putInt(K_MAX_CHARS, 90)
            edit.putBoolean(K_TUNING_V3, true).apply()
        }
        if (!sp.getBoolean(K_TUNING_V4, false)) {
            // 90-char chunks cut too many clauses mid-thought. The callback/streaming
            // fix makes 180 chars cheap enough; migrate only the old default.
            val edit = sp.edit()
            if (sp.getInt(K_MAX_CHARS, DEFAULT_MAX_CHARS) == 90) {
                edit.putInt(K_MAX_CHARS, DEFAULT_MAX_CHARS)
            }
            edit.putBoolean(K_TUNING_V4, true).apply()
        }
    }

    var engine: String
        get() = sp.getString(K_ENGINE, ENGINE_SYSTEM) ?: ENGINE_SYSTEM
        set(v) = sp.edit().putString(K_ENGINE, v).apply()

    /** Installed sherpa model id ("kokoro-int8-en-v0_19" / "sherpa-onnx-pocket-tts-int8-2026-01-26"). */
    var modelId: String
        get() = sp.getString(K_MODEL, MODEL_KOKORO) ?: MODEL_KOKORO
        set(v) = sp.edit().putString(K_MODEL, v).apply()

    /** Selected Android system TTS voice name ("" = device default). */
    var systemVoice: String
        get() = sp.getString(K_SYSTEM_VOICE, "") ?: ""
        set(v) = sp.edit().putString(K_SYSTEM_VOICE, v).apply()

    /** Human label for [systemVoice], shown in the player card and Settings. */
    var systemVoiceLabel: String
        get() = sp.getString(K_SYSTEM_VOICE_LABEL, "") ?: ""
        set(v) = sp.edit().putString(K_SYSTEM_VOICE_LABEL, v).apply()

    /** Kokoro speaker index; NekoSpeak default voice is af_heart (sid 0 in sherpa's en bundle). */
    var kokoroSid: Int
        get() = sp.getInt(K_SID, 0)
        set(v) = sp.edit().putInt(K_SID, v).apply()

    /** Pocket reference clip path ("" = bundled bria.wav from the model bundle). */
    var pocketVoicePath: String
        get() = sp.getString(K_POCKET_VOICE, "") ?: ""
        set(v) = sp.edit().putString(K_POCKET_VOICE, v).apply()

    var speed: Float
        get() = sp.getFloat(K_SPEED, 1.0f)
        set(v) = sp.edit().putFloat(K_SPEED, v).apply()

    /** NekoSpeak default is 6; capped at core count in the engine. */
    var threads: Int
        get() = sp.getInt(K_THREADS, 4)
        set(v) = sp.edit().putInt(K_THREADS, v).apply()

    var nnapi: Boolean
        get() = sp.getBoolean(K_NNAPI, false)
        set(v) = sp.edit().putBoolean(K_NNAPI, v).apply()

    /** Sentence look-ahead. With the async PCM writer, 4 chunks keep generation ahead of playback. */
    var prefetch: Int
        get() = sp.getInt(K_PREFETCH, 4)
        set(v) = sp.edit().putInt(K_PREFETCH, v).apply()

    /**
     * Continuous listening: extract and start reading on page load, and advance to the
     * next chapter when the queue finishes. Off by default; manual Read/Next-ch otherwise.
     */
    var autoMode: Boolean
        get() = sp.getBoolean(K_AUTO_MODE, false)
        set(v) = sp.edit().putBoolean(K_AUTO_MODE, v).apply()

    var highlight: Boolean
        get() = sp.getBoolean(K_HIGHLIGHT, true)
        set(v) = sp.edit().putBoolean(K_HIGHLIGHT, v).apply()

    /** Reader mode strips site chrome/ads and uses a book-like dark typography theme. */
    var readerMode: Boolean
        get() = sp.getBoolean(K_READER, true)
        set(v) = sp.edit().putBoolean(K_READER, v).apply()

    var autoScroll: Boolean
        get() = sp.getBoolean(K_AUTOSCROLL, true)
        set(v) = sp.edit().putBoolean(K_AUTOSCROLL, v).apply()

    var maxSentenceChars: Int
        get() = sp.getInt(K_MAX_CHARS, DEFAULT_MAX_CHARS)
        set(v) = sp.edit().putInt(K_MAX_CHARS, v).apply()

    var adblockEnabled: Boolean
        get() = sp.getBoolean(K_ADBLOCK, true)
        set(v) = sp.edit().putBoolean(K_ADBLOCK, v).apply()

    var adblockCosmetic: Boolean
        get() = sp.getBoolean(K_ADBLOCK_COSMETIC, true)
        set(v) = sp.edit().putBoolean(K_ADBLOCK_COSMETIC, v).apply()

    var adblockPopups: Boolean
        get() = sp.getBoolean(K_ADBLOCK_POPUPS, true)
        set(v) = sp.edit().putBoolean(K_ADBLOCK_POPUPS, v).apply()

    /** Registrable domains where the reader explicitly disabled ad blocking. */
    val adblockAllowedSites: Set<String>
        get() = sp.getStringSet(K_ADBLOCK_ALLOW, emptySet()) ?: emptySet()

    fun setAdblockSiteAllowed(site: String, allowed: Boolean) {
        val current = sp.getStringSet(K_ADBLOCK_ALLOW, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (allowed) current.add(site) else current.remove(site)
        sp.edit().putStringSet(K_ADBLOCK_ALLOW, current).apply()
    }

    /**
     * True after the one contextual POST_NOTIFICATIONS request has been shown. The flag stops
     * the app from reopening the system dialog on every Play tap or Settings visit. If the user
     * later changes their mind, Settings links to the system notification controls instead.
     */
    var notificationsAsked: Boolean
        get() = sp.getBoolean(K_NOTIFICATIONS_ASKED, false)
        set(v) = sp.edit().putBoolean(K_NOTIFICATIONS_ASKED, v).apply()

    /** Recent address-bar destinations, newest first. Used by the edit-mode suggestion list. */
    fun recentUrls(): List<String> =
        (sp.getString(K_RECENT_URLS, "") ?: "")
            .split('\n')
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .take(8)

    fun rememberUrl(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val current = recentUrls().toMutableList()
        current.remove(url)
        current.add(0, url)
        sp.edit().putString(K_RECENT_URLS, current.take(8).joinToString("\n")).apply()
    }

    // ---- competitive-parity settings (v1.2) ---------------------------------

    /** Reader typography: font size percent (80..160), applied as a CSS variable. */
    var readerFontScale: Int
        get() = sp.getInt(K_READER_FONT_SCALE, 100).coerceIn(80, 160)
        set(v) = sp.edit().putInt(K_READER_FONT_SCALE, v.coerceIn(80, 160)).apply()

    /** "serif" | "sans" | "dyslexic". Anything else falls back to serif. */
    var readerFontFamily: String
        get() = sp.getString(K_READER_FONT_FAMILY, "serif")
            ?.takeIf { it in FONT_FAMILIES } ?: "serif"
        set(v) = sp.edit().putString(
            K_READER_FONT_FAMILY, if (v in FONT_FAMILIES) v else "serif"
        ).apply()

    /** "dark" | "sepia" | "light". */
    var readerTheme: String
        get() = sp.getString(K_READER_THEME, "dark")
            ?.takeIf { it in READER_THEMES } ?: "dark"
        set(v) = sp.edit().putString(
            K_READER_THEME, if (v in READER_THEMES) v else "dark"
        ).apply()

    /** Line height percent (120..200). */
    var readerLineHeight: Int
        get() = sp.getInt(K_READER_LINE_HEIGHT, 170).coerceIn(120, 200)
        set(v) = sp.edit().putInt(K_READER_LINE_HEIGHT, v.coerceIn(120, 200)).apply()

    /** Reader column width in px; 0 = automatic (760px). Clamped to a sane range. */
    var readerWidthPx: Int
        get() {
            val v = sp.getInt(K_READER_WIDTH, 0)
            return if (v <= 0) 0 else v.coerceIn(480, 1200)
        }
        set(v) = sp.edit().putInt(K_READER_WIDTH, if (v <= 0) 0 else v.coerceIn(480, 1200))
            .apply()

    /** Word-level highlight (falls back to sentence highlight when off/unavailable). */
    var readerWordHighlight: Boolean
        get() = sp.getBoolean(K_READER_WORD_HIGHLIGHT, false)
        set(v) = sp.edit().putBoolean(K_READER_WORD_HIGHLIGHT, v).apply()

    /** Output gain multiplier applied by PcmPlayer (1.0 .. 3.0). */
    var ttsVolumeBoost: Float
        get() = sp.getFloat(K_TTS_VOLUME_BOOST, 1.0f).coerceIn(1.0f, 3.0f)
        set(v) = sp.edit().putFloat(K_TTS_VOLUME_BOOST, v.coerceIn(1.0f, 3.0f)).apply()

    /** Trim leading/trailing silence from synthesized sentences. */
    var ttsSkipSilence: Boolean
        get() = sp.getBoolean(K_TTS_SKIP_SILENCE, false)
        set(v) = sp.edit().putBoolean(K_TTS_SKIP_SILENCE, v).apply()

    /** Pitch multiplier for AudioTrack playback params (0.8 .. 1.2). */
    var ttsPitch: Float
        get() = sp.getFloat(K_TTS_PITCH, 1.0f).coerceIn(0.8f, 1.2f)
        set(v) = sp.edit().putFloat(K_TTS_PITCH, v.coerceIn(0.8f, 1.2f)).apply()

    /** Pronunciation replacement rules, one "from=to" per line. */
    var pronunciationRules: String
        get() = sp.getString(K_PRONUNCIATION_RULES, "") ?: ""
        set(v) = sp.edit().putString(K_PRONUNCIATION_RULES, v).apply()

    /** Sleep timer default: 0=off, 15/30/45/60 minutes, -1=end of chapter. */
    var sleepTimerMinutes: Int
        get() = sp.getInt(K_SLEEP_TIMER, 0).let {
            if (it in intArrayOf(0, 15, 30, 45, 60, -1)) it else 0
        }
        set(v) = sp.edit()
            .putInt(K_SLEEP_TIMER, if (v in intArrayOf(0, 15, 30, 45, 60, -1)) v else 0)
            .apply()

    /** Auto-check the bundled model catalog (network-free for now; reserved for OTA). */
    var modelCatalogAuto: Boolean
        get() = sp.getBoolean(K_MODEL_CATALOG_AUTO, true)
        set(v) = sp.edit().putBoolean(K_MODEL_CATALOG_AUTO, v).apply()

    /**
     * Reader-mode default for a registrable domain. Global [readerMode] is the fallback;
     * a domain can opt out (e.g. sites whose reader CSS breaks navigation).
     */
    fun readerModeForSite(site: String): Boolean {
        if (site.isEmpty()) return readerMode
        val off = sp.getStringSet(K_READER_OFF_SITES, emptySet()) ?: emptySet()
        return if (site in off) false else readerMode
    }

    fun setReaderModeForSite(site: String, on: Boolean) {
        if (site.isEmpty()) { readerMode = on; return }
        val current = sp.getStringSet(K_READER_OFF_SITES, emptySet())?.toMutableSet()
            ?: mutableSetOf()
        if (on) current.remove(site) else current.add(site)
        sp.edit().putStringSet(K_READER_OFF_SITES, current).apply()
    }

    /** Last opened URL, used for continue-reading before the library store is populated. */
    var lastUrl: String?
        get() = sp.getString(K_LAST_URL, null)
        set(v) = sp.edit().putString(K_LAST_URL, v).apply()

    companion object {
        const val ENGINE_SYSTEM = "system"
        const val ENGINE_SHERPA = "sherpa"

        const val MODEL_KOKORO = "kokoro-int8-en-v0_19"
        const val MODEL_POCKET = "sherpa-onnx-pocket-tts-int8-2026-01-26"

        /** Longer than the old 90 so clauses are not chopped; 150-220 also matches NekoSpeak. */
        const val DEFAULT_MAX_CHARS = 180

        private const val K_ENGINE = "engine"
        private const val K_MODEL = "model"
        private const val K_SID = "kokoro_sid"
        private const val K_SYSTEM_VOICE = "system_voice"
        private const val K_SYSTEM_VOICE_LABEL = "system_voice_label"
        private const val K_POCKET_VOICE = "pocket_voice"
        private const val K_SPEED = "speed"
        private const val K_THREADS = "threads"
        private const val K_NNAPI = "nnapi"
        private const val K_PREFETCH = "prefetch"
        private const val K_AUTO_MODE = "auto_mode"
        private const val K_HIGHLIGHT = "highlight"
        private const val K_READER = "reader_mode"
        private const val K_AUTOSCROLL = "autoscroll"
        private const val K_MAX_CHARS = "max_chars"
        private const val K_TUNING_V2 = "tuning_v2"
        private const val K_TUNING_V3 = "tuning_v3"
        private const val K_TUNING_V4 = "tuning_v4"
        private const val K_ADBLOCK = "adblock_enabled"
        private const val K_ADBLOCK_COSMETIC = "adblock_cosmetic"
        private const val K_ADBLOCK_POPUPS = "adblock_popups"
        private const val K_ADBLOCK_ALLOW = "adblock_allow_sites"
        private const val K_NOTIFICATIONS_ASKED = "notifications_asked"
        private const val K_RECENT_URLS = "recent_urls"
        private const val K_READER_FONT_SCALE = "reader_font_scale"
        private const val K_READER_FONT_FAMILY = "reader_font_family"
        private const val K_READER_THEME = "reader_theme"
        private const val K_READER_LINE_HEIGHT = "reader_line_height"
        private const val K_READER_WIDTH = "reader_width_px"
        private const val K_READER_WORD_HIGHLIGHT = "reader_word_highlight"
        private const val K_READER_OFF_SITES = "reader_off_sites"
        private const val K_TTS_VOLUME_BOOST = "tts_volume_boost"
        private const val K_TTS_SKIP_SILENCE = "tts_skip_silence"
        private const val K_TTS_PITCH = "tts_pitch"
        private const val K_PRONUNCIATION_RULES = "pronunciation_rules"
        private const val K_SLEEP_TIMER = "sleep_timer_minutes"
        private const val K_MODEL_CATALOG_AUTO = "model_catalog_auto"
        private const val K_LAST_URL = "last_url"

        val FONT_FAMILIES = setOf("serif", "sans", "dyslexic")
        val READER_THEMES = setOf("dark", "sepia", "light")

        /** Kokoro en-v0_19 speakers, in the order stored in voices.bin. */
        val KOKORO_VOICES = listOf(
            "af" to "Heart (US female)",
            "af_bella" to "Bella (US female)",
            "af_nicole" to "Nicole (US female)",
            "af_sarah" to "Sarah (US female)",
            "af_sky" to "Sky (US female)",
            "am_adam" to "Adam (US male)",
            "am_michael" to "Michael (US male)",
            "bf_emma" to "Emma (UK female)",
            "bf_isabella" to "Isabella (UK female)",
            "bm_george" to "George (UK male)",
            "bm_lewis" to "Lewis (UK male)"
        )
    }
}
