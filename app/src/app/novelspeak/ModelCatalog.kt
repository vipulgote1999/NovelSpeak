package app.novelspeak

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Bundled, signed-catalog-ready list of downloadable TTS models.
 *
 * Data comes from `assets/model_catalog.json` (lead-owned upstream data is mirrored in
 * [ModelManager.MODELS]); parsing tolerates malformed entries so a bad catalog can never brick
 * the Settings screen. There is **no network use here this round**: the JSON is bundled and
 * [loadBundled] falls back to [ModelManager.MODELS] if the asset is missing or unreadable.
 *
 * TODO(signed OTA, roadmap P1.9): fetch a signed catalog from a pinned URL, verify an
 * Ed25519/RSA signature with an embedded public key, cache it with a minAppVersion gate, and use
 * [parse] on the verified payload. Until then the auto-update pref only records intent.
 *
 * Entry schema (unknown fields are ignored, missing optional fields get sane defaults):
 * ```
 * { "version": 1, "models": [ {
 *     "id": "kokoro-int8-en-v0_19", "family": "kokoro",
 *     "displayName": "Kokoro int8 (English, 11 voices)",
 *     "sha256": "c9f0…", "bytes": 108003328, "sampleRate": 24000,
 *     "license": "Apache-2.0", "url": "https://…/x.tar.bz2", "enabled": true } ] }
 * ```
 * A bare JSON array is also accepted.
 */
object ModelCatalog {

    /** Asset file name; must stay in sync with build.sh's `aapt2 -A app/assets` input. */
    const val ASSET_NAME = "model_catalog.json"

    /** Release assets for a signed catalog (catalog, hex signature, PEM public key). */
    const val CATALOG_ASSET = ASSET_NAME
    const val SIGNATURE_ASSET = "model_catalog.json.sig"
    const val PUBLIC_KEY_ASSET = "model_catalog_pub.pem"

    /** Upper bound on entries parsed from one catalog (defensive against oversized payloads). */
    const val MAX_ENTRIES = 100

    /** Default sample rate of the shipped sherpa bundles. */
    const val DEFAULT_SAMPLE_RATE = 24000

    /** Bundles are validated byte-for-byte, except sizes rounded to MB tolerate this slack. */
    const val SIZE_TOLERANCE_BYTES = 2L * 1024 * 1024

    /** One catalog entry. [bytes] is the expected archive size (0 = unknown). */
    data class CatalogModel(
        val id: String,
        val family: String,
        val displayName: String,
        val sha256: String,
        val bytes: Long,
        val sampleRate: Int,
        val license: String,
        val url: String,
        val enabled: Boolean
    )

    /** Result of [validate]; [reason] is user-facing and empty on success. */
    data class ValidationResult(val ok: Boolean, val reason: String) {
        companion object {
            fun success(): ValidationResult = ValidationResult(true, "")
            fun failure(reason: String): ValidationResult = ValidationResult(false, reason)
        }
    }

    @Volatile
    private var cached: List<CatalogModel>? = null

    /**
     * Parses a catalog JSON document. Malformed entries (missing/blank id, non-http(s) url,
     * invalid sha256, non-object entries, duplicates) are skipped, never fatal. Returns an empty
     * list for null/blank/invalid JSON.
     */
    fun parse(json: String?): List<CatalogModel> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val trimmed = json.trim()
            val array: JSONArray? = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONObject(trimmed).optJSONArray("models")
            }
            if (array == null) return emptyList()
            val byId = LinkedHashMap<String, CatalogModel>()
            val limit = minOf(array.length(), MAX_ENTRIES)
            for (i in 0 until limit) {
                val entry = array.optJSONObject(i) ?: continue
                val model = parseEntry(entry) ?: continue
                byId[model.id] = model // last definition wins
            }
            byId.values.toList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * Returns the bundled catalog, parsed once per process. If [ASSET_NAME] is missing or the
     * asset does not yield at least one valid entry, returns [fallback] built from
     * [ModelManager.MODELS] so model install/delete keeps working.
     */
    fun loadBundled(context: Context): List<CatalogModel> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val parsed = try {
                context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    .let { parse(it) }
            } catch (t: Throwable) {
                emptyList()
            }
            val result = if (parsed.isNotEmpty()) parsed else fallback()
            cached = result
            return result
        }
    }

    /** Catalog equivalent of [ModelManager.MODELS] (used when the asset is unavailable). */
    fun fallback(): List<CatalogModel> = ModelManager.MODELS.map { m ->
        CatalogModel(
            id = m.id,
            family = m.family,
            displayName = m.label,
            sha256 = m.sha256,
            bytes = m.sizeMb.toLong() * 1024L * 1024L,
            sampleRate = m.sampleRate,
            license = "Apache-2.0 (upstream model card)",
            url = m.url,
            enabled = true
        )
    }

    /** Drops the process cache (catalog asset can only change on app update; used by tests). */
    fun clearCache() {
        cached = null
    }

    /** Streaming SHA-256 of [file]; returns "" when the file cannot be read. */
    fun sha256(file: File): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * Verifies [file] against [model]: exists, enabled, expected size (when [CatalogModel.bytes]
     * is known) and SHA-256. Never throws.
     */
    fun validate(model: CatalogModel, file: File): ValidationResult {
        if (!model.enabled) return ValidationResult.failure("Model is disabled in the catalog")
        if (!file.isFile) return ValidationResult.failure("Model file not found: ${file.name}")
        if (model.bytes > 0L) {
            val delta = kotlin.math.abs(file.length() - model.bytes)
            if (delta > SIZE_TOLERANCE_BYTES) {
                return ValidationResult.failure(
                    "Size mismatch: expected ${model.bytes} bytes, found ${file.length()}"
                )
            }
        }
        val digest = sha256(file)
        if (digest.isEmpty()) return ValidationResult.failure("Cannot read ${file.name}")
        if (!digest.equals(model.sha256, ignoreCase = true)) {
            return ValidationResult.failure("SHA-256 mismatch — the download is corrupt or tampered with")
        }
        return ValidationResult.success()
    }

    /** Convenience boolean form of [validate]. */
    fun isValid(model: CatalogModel, file: File): Boolean = validate(model, file).ok

    private fun parseEntry(entry: JSONObject): CatalogModel? {
        val id = entry.optString("id").trim()
        if (id.isEmpty()) return null
        val url = entry.optString("url").trim()
        if (!isHttpUrl(url)) return null
        val sha = entry.optString("sha256").trim().lowercase()
        if (!isSha256(sha)) return null
        var bytes = entry.optLong("bytes", 0L)
        if (bytes <= 0L) {
            // Older/short catalogs sometimes only carry a rounded "sizeMb"; accept it.
            val sizeMb = entry.optLong("sizeMb", 0L)
            if (sizeMb > 0L && sizeMb <= 4096L) bytes = sizeMb * 1024L * 1024L
        }
        val family = entry.optString("family").trim().ifEmpty { inferFamily(id) }
        val displayName = entry.optString("displayName").trim()
            .ifEmpty { entry.optString("label").trim() }
            .ifEmpty { id }
        val license = entry.optString("license").trim().ifEmpty { "unknown" }
        val sampleRate = entry.optInt("sampleRate", DEFAULT_SAMPLE_RATE)
            .let { if (it in 8000..96000) it else DEFAULT_SAMPLE_RATE }
        val enabled = if (entry.has("enabled")) entry.optBoolean("enabled", true) else true
        return CatalogModel(
            id = id,
            family = family,
            displayName = displayName,
            sha256 = sha,
            bytes = bytes,
            sampleRate = sampleRate,
            license = license,
            url = url,
            enabled = enabled
        )
    }

    private fun inferFamily(id: String): String {
        val lower = id.lowercase()
        return when {
            lower.contains("kokoro") -> "kokoro"
            lower.contains("pocket") -> "pocket"
            else -> "unknown"
        }
    }

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("https://") || url.startsWith("http://")

    // ---- signed catalog (OTA) ------------------------------------------------

    /**
     * Verifies a catalog payload against an RSA/SHA-256 signature (hex-encoded signature,
     * PEM or hex public key). The release process signs the catalog's UTF-8 bytes and ships
     * the signature next to it; a tampered download is rejected before any model URL/hash
     * is used. The shipped catalog may be unsigned until a release key exists, in which case
     * the bundled/fallback list is used.
     */
    fun verifySignature(
        json: String,
        signatureHex: String,
        publicKeyPem: String
    ): ValidationResult {
        if (json.isBlank()) return ValidationResult.failure("empty catalog")
        if (signatureHex.isBlank()) return ValidationResult.failure("missing signature")
        return try {
            val signature = hexToBytes(signatureHex)
                ?: return ValidationResult.failure("signature is not hex")
            val keyBytes = parsePublicKey(publicKeyPem)
                ?: return ValidationResult.failure("public key is not PEM/DER")
            val spec = java.security.spec.X509EncodedKeySpec(keyBytes)
            val key = java.security.KeyFactory.getInstance("RSA").generatePublic(spec)
            val verifier = java.security.Signature.getInstance("SHA256withRSA")
            verifier.initVerify(key)
            verifier.update(json.toByteArray(Charsets.UTF_8))
            if (verifier.verify(signature)) {
                ValidationResult.success()
            } else {
                ValidationResult.failure("signature mismatch")
            }
        } catch (t: Throwable) {
            ValidationResult.failure("signature check failed: " + t.javaClass.simpleName)
        }
    }

    /** Parses a catalog only when its signature verifies. */
    fun parseVerified(
        json: String,
        signatureHex: String,
        publicKeyPem: String
    ): Pair<List<CatalogModel>, ValidationResult> {
        val check = verifySignature(json, signatureHex, publicKeyPem)
        if (!check.ok) return emptyList<CatalogModel>() to check
        return parse(json) to ValidationResult.success()
    }

    /**
     * Explicit OTA catalog refresh: downloads a catalog plus its detached signature, verifies
     * the signature with the bundled public key, and only then caches it under filesDir.
     * Returns a user-facing status string. The bundled catalog remains the offline fallback.
     */
    fun refreshSigned(context: Context, catalogUrl: String, signatureUrl: String): String {
        return try {
            val pem = context.assets.open(PUBLIC_KEY_ASSET).bufferedReader().use { it.readText() }
            val catalog = httpGetText(catalogUrl, 512 * 1024)
                ?: return "Catalog update failed: no response"
            val signature = httpGetText(signatureUrl, 8 * 1024)
                ?: return "Catalog update failed: no signature"
            val check = verifySignature(catalog.trimEnd(), signature.trim(), pem)
            if (!check.ok) return "Catalog rejected: " + check.reason
            val models = parse(catalog)
            if (models.isEmpty()) return "Catalog rejected: no valid entries"
            val dir = File(context.filesDir, "catalog")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "model_catalog.json").writeText(catalog)
            File(dir, "model_catalog.json.sig").writeText(signature)
            clearCache()
            "Catalog verified and cached: " + models.size + " entries"
        } catch (t: Throwable) {
            "Catalog update failed: " + t.javaClass.simpleName
        }
    }

    private fun httpGetText(url: String, maxBytes: Int): String? {
        var connection: java.net.HttpURLConnection? = null
        return try {
            connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Accept", "text/plain, application/json")
            }
            if (connection.responseCode != 200) return null
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var read: Int
                while (true) {
                    read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > maxBytes) return null
                }
            }
            out.toString("UTF-8")
        } catch (t: Throwable) {
            null
        } finally {
            try { connection?.disconnect() } catch (t: Throwable) { }
        }
    }

    /** Status of bundled signed assets, for Settings/diagnostics (null-safe). */
    fun bundledSignatureStatus(context: Context): String {
        return try {
            val catalog = context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
            val sigFile = context.assets.open(SIGNATURE_ASSET).bufferedReader().use { it.readText() }
            val pem = context.assets.open(PUBLIC_KEY_ASSET).bufferedReader().use { it.readText() }
            val result = verifySignature(catalog.trimEnd(), sigFile.trim(), pem)
            if (result.ok) "signed catalog verified" else "signed catalog rejected: " + result.reason
        } catch (t: Throwable) {
            "signed catalog assets not bundled (using built-in list)"
        }
    }

    private fun parsePublicKey(value: String): ByteArray? {
        val text = value.trim()
        if (text.contains("-----BEGIN")) {
            val body = text
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace(Regex("\\s"), "")
            return decodeBase64(body.toByteArray(Charsets.US_ASCII))
        }
        return hexToBytes(text)
    }

    /** Tiny Base64 decoder, so verification needs no android.util dependency. */
    private fun decodeBase64(input: ByteArray): ByteArray? {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val map = IntArray(128) { -1 }
        for (i in table.indices) map[table[i].code] = i
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (raw in input) {
            val c = raw.toInt()
            if (c == '='.code) break
            if (c < 0 || c >= map.size) continue
            val v = map[c]
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private fun hexToBytes(value: String): ByteArray? {
        val clean = value.trim()
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun isSha256(value: String): Boolean {
        if (value.length != 64) return false
        for (c in value) {
            if (!(c in '0'..'9' || c in 'a'..'f')) return false
        }
        return true
    }
}
