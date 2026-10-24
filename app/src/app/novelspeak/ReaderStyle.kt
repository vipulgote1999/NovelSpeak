package app.novelspeak

/**
 * Reader typography model and the user CSS that reader mode injects into the page.
 *
 * [buildCss] appends a stylesheet *after* the built-in reader CSS in `assets/extract.js`
 * (Lead wires it through `WebExtractor.applyReaderStyle(web, css)`), so every rule uses the same
 * `html.ns-reader` scope and wins by source order + `!important`. Values are sanitized here, not
 * in the UI: a corrupt SharedPreferences value can never produce an invalid stylesheet.
 *
 * Pure JVM, no Android imports — covered by `tools/feature-test/TestMain.kt`.
 *
 * @property fontScalePct 80..160 (100 = 17 px), clamped.
 * @property fontFamily `serif` | `sans` | `dyslexic`; unknown falls back to `serif`.
 * @property theme `dark` | `sepia` | `light`; unknown falls back to `dark`.
 * @property lineHeightPct 120..200 (170 = 1.7), clamped.
 * @property widthPx reading column width; 0 = automatic ([DEFAULT_WIDTH_PX]); clamped 480..1200.
 */
data class ReaderStyle(
    val fontScalePct: Int = 100,
    val fontFamily: String = FAMILY_SERIF,
    val theme: String = THEME_DARK,
    val lineHeightPct: Int = 170,
    val widthPx: Int = 0
) {

    companion object {
        const val FAMILY_SERIF = "serif"
        const val FAMILY_SANS = "sans"
        const val FAMILY_DYSLEXIC = "dyslexic"

        const val THEME_DARK = "dark"
        const val THEME_SEPIA = "sepia"
        const val THEME_LIGHT = "light"

        /** Font size in CSS px at 100%. Matches the built-in reader CSS. */
        const val BASE_FONT_PX = 17.0

        /** Column width used when [widthPx] is 0 (matches the built-in reader CSS). */
        const val DEFAULT_WIDTH_PX = 760
        const val MIN_WIDTH_PX = 480
        const val MAX_WIDTH_PX = 1200

        const val MIN_FONT_SCALE_PCT = 80
        const val MAX_FONT_SCALE_PCT = 160
        const val MIN_LINE_HEIGHT_PCT = 120
        const val MAX_LINE_HEIGHT_PCT = 200

        /** Valid family keys, in menu order. */
        val FAMILIES = listOf(FAMILY_SERIF, FAMILY_SANS, FAMILY_DYSLEXIC)

        /** Valid theme keys, in menu order. */
        val THEMES = listOf(THEME_DARK, THEME_SEPIA, THEME_LIGHT)

        /** Builds a style, sanitizing every field (safe for values read from prefs). */
        fun of(
            fontScalePct: Int,
            fontFamily: String,
            theme: String,
            lineHeightPct: Int,
            widthPx: Int
        ): ReaderStyle = ReaderStyle(fontScalePct, fontFamily, theme, lineHeightPct, widthPx)
            .sanitized()

        /** CSS font stack for a family key; unknown keys fall back to serif. */
        fun fontStack(family: String): String = when (family) {
            FAMILY_SANS -> "system-ui,-apple-system,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif"
            FAMILY_DYSLEXIC ->
                "'OpenDyslexic','OpenDyslexic3','Atkinson Hyperlegible',Verdana,sans-serif"
            else -> "Georgia,'Times New Roman',Times,serif"
        }

        /** Effective font size in CSS px after clamping (e.g. 100 -> 17.0, 150 -> 25.5). */
        fun effectiveFontSizePx(style: ReaderStyle): Double =
            BASE_FONT_PX * style.fontScalePct.coerceIn(MIN_FONT_SCALE_PCT, MAX_FONT_SCALE_PCT) / 100.0

        /** Effective column width in CSS px after clamping (0 -> 760). */
        fun effectiveWidthPx(style: ReaderStyle): Int =
            if (style.widthPx <= 0) DEFAULT_WIDTH_PX
            else style.widthPx.coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX)

        /**
         * Full stylesheet: `:root` custom properties plus `html.ns-reader` body/heading/paragraph
         * overrides and the highlight colors. Always returns valid CSS (finite numbers, quoted
         * font names, valid hex/rgba colors).
         */
        fun buildCss(style: ReaderStyle): String {
            val fontPx = formatDecimal(effectiveFontSizePx(style))
            val lineHeight = formatDecimal(
                style.lineHeightPct.coerceIn(MIN_LINE_HEIGHT_PCT, MAX_LINE_HEIGHT_PCT) / 100.0
            )
            val widthPx = effectiveWidthPx(style)
            val stack = fontStack(style.fontFamily)
            val p = palette(style.theme)
            return "" +
                ":root{" +
                "--ns-font-size:${fontPx}px;" +
                "--ns-font-family:${stack};" +
                "--ns-line-height:${lineHeight};" +
                "--ns-width:${widthPx}px;" +
                "--ns-bg:${p.bg};" +
                "--ns-fg:${p.fg};" +
                "--ns-muted:${p.muted};" +
                "--ns-heading:${p.heading};" +
                "--ns-link:${p.link};" +
                "--ns-surface:${p.surface};" +
                "--ns-border:${p.border};" +
                "--ns-highlight:${p.highlight};" +
                "--ns-highlight-bar:${p.highlightBar};" +
                "}\n" +
                "html.ns-reader,html.ns-reader body{" +
                "background:var(--ns-bg)!important;" +
                "background-image:none!important;" +
                "color:var(--ns-fg)!important" +
                "}\n" +
                "html.ns-reader body{" +
                "max-width:var(--ns-width)!important;" +
                "margin:0 auto!important;" +
                "padding:16px 16px 56px!important;" +
                "font-family:var(--ns-font-family)!important;" +
                "font-size:var(--ns-font-size)!important;" +
                "line-height:var(--ns-line-height)!important" +
                "}\n" +
                "html.ns-reader p,html.ns-reader li,html.ns-reader blockquote," +
                "html.ns-reader td,html.ns-reader dd,html.ns-reader dt{" +
                "color:var(--ns-fg)!important;" +
                "background:transparent!important;" +
                "font-size:var(--ns-font-size)!important;" +
                "line-height:var(--ns-line-height)!important" +
                "}\n" +
                "html.ns-reader h1,html.ns-reader h2,html.ns-reader h3," +
                "html.ns-reader h4,html.ns-reader h5,html.ns-reader h6{" +
                "color:var(--ns-heading)!important" +
                "}\n" +
                "html.ns-reader a,html.ns-reader a:visited{color:var(--ns-link)!important}\n" +
                "html.ns-reader article,html.ns-reader main," +
                "html.ns-reader [class*=\"chapter\"],html.ns-reader [id*=\"chapter\"]," +
                "html.ns-reader [class*=\"content\"],html.ns-reader [id*=\"content\"]{" +
                "max-width:var(--ns-width)!important;" +
                "margin-left:auto!important;" +
                "margin-right:auto!important" +
                "}\n" +
                "html.ns-reader [data-ns].ns-active{" +
                "background:var(--ns-highlight)!important;" +
                "box-shadow:inset 3px 0 0 var(--ns-highlight-bar)!important;" +
                "border-radius:4px!important" +
                "}\n"
        }

        /** Returns a copy with every field clamped/validated. */
        fun sanitized(style: ReaderStyle): ReaderStyle = style.sanitized()

        private class Palette(
            val bg: String,
            val fg: String,
            val muted: String,
            val heading: String,
            val link: String,
            val surface: String,
            val border: String,
            val highlight: String,
            val highlightBar: String
        )

        private val DARK = Palette(
            bg = "#0F1115",
            fg = "#E9EDF2",
            muted = "#9AA6B6",
            heading = "#FFFFFF",
            link = "#6EE7B7",
            surface = "#171A21",
            border = "#2C3442",
            highlight = "rgba(246,196,83,.10)",
            highlightBar = "#F6C453"
        )

        private val SEPIA = Palette(
            bg = "#F4ECD8",
            fg = "#3B2F1F",
            muted = "#6B5B45",
            heading = "#2B2116",
            link = "#8A5A2B",
            surface = "#EFE3C8",
            border = "#D8C7A8",
            highlight = "rgba(160,110,30,.15)",
            highlightBar = "#A06E1E"
        )

        private val LIGHT = Palette(
            bg = "#FFFFFF",
            fg = "#1B1F24",
            muted = "#5B6672",
            heading = "#0A0C0F",
            link = "#0B6BCB",
            surface = "#F2F4F7",
            border = "#D5DAE1",
            highlight = "rgba(255,196,0,.25)",
            highlightBar = "#B8860B"
        )

        private fun palette(theme: String): Palette = when (theme) {
            THEME_SEPIA -> SEPIA
            THEME_LIGHT -> LIGHT
            else -> DARK
        }

        /** 21.25 -> "21.25"; 17.0 -> "17"; 1.7 -> "1.7"; always finite and dot-decimal. */
        private fun formatDecimal(value: Double): String {
            val safe = if (value.isFinite()) value else 0.0
            val rounded = Math.round(safe * 100.0) / 100.0
            if (rounded == Math.floor(rounded)) return rounded.toInt().toString()
            val s = rounded.toString()
            return if (s.endsWith(".0")) s.dropLast(2) else s
        }
    }

    /** Returns a copy with every field clamped/validated. */
    fun sanitized(): ReaderStyle = copy(
        fontScalePct = fontScalePct.coerceIn(MIN_FONT_SCALE_PCT, MAX_FONT_SCALE_PCT),
        fontFamily = if (fontFamily in FAMILIES) fontFamily else FAMILY_SERIF,
        theme = if (theme in THEMES) theme else THEME_DARK,
        lineHeightPct = lineHeightPct.coerceIn(MIN_LINE_HEIGHT_PCT, MAX_LINE_HEIGHT_PCT),
        widthPx = if (widthPx <= 0) 0 else widthPx.coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX)
    )
}

/** Convenience extension so call sites can write `style.css()`. */
fun ReaderStyle.css(): String = ReaderStyle.buildCss(this)
