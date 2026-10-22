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
        private const val K_POCKET_VOICE = "pocket_voice"
        private const val K_SPEED = "speed"
        private const val K_THREADS = "threads"
        private const val K_NNAPI = "nnapi"
        private const val K_PREFETCH = "prefetch"
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
