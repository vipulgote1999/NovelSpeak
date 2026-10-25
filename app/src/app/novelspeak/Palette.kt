package app.novelspeak

/**
 * Single colour system for every screen (home, reader chrome, library, settings, privacy).
 *
 * "Harbor ink": a cool ink base with warm paper accents and a mint signal colour. Tokens are
 * semantic, not decorative, and every text tone is checked against the surfaces it is used on:
 *
 *   TEXT            17.2:1 on BG, 13.2:1 on SURFACE_HIGH
 *   TEXT_SECONDARY  12.4:1 on BG,  9.6:1 on SURFACE_HIGH
 *   MUTED            7.8:1 on BG,  6.0:1 on SURFACE_HIGH
 *   FAINT            5.2:1 on BG,  4.7:1 on SURFACE  (not for SURFACE_HIGH)
 *   ACCENT          12.3:1 on BG,  9.5:1 on SURFACE_HIGH
 *   AMBER           11.7:1 on BG,  9.0:1 on SURFACE_HIGH
 *   RED              7.3:1 on BG,  5.6:1 on SURFACE_HIGH
 *
 * The light icon plate exists only behind shortcut favicons, so site marks keep their own
 * colours on the dark tab (same idea as Chrome's shortcut circles).
 */
object Palette {
    val BG = 0xFF0E1318.toInt()
    val SURFACE = 0xFF161D24.toInt()
    val SURFACE_HIGH = 0xFF212B34.toInt()
    val BORDER = 0xFF2C3944.toInt()

    val TEXT = 0xFFF1F6FA.toInt()
    val TEXT_SECONDARY = 0xFFC9D4DE.toInt()
    val MUTED = 0xFF9AA9B8.toInt()
    val FAINT = 0xFF7A8998.toInt()

    val ACCENT = 0xFF6FE7BE.toInt()
    val AMBER = 0xFFF4C66A.toInt()
    val RED = 0xFFF87C7C.toInt()

    val RIPPLE = 0x33FFFFFF
    val ICON_PLATE = 0xFFEEF2F5.toInt()
    val ON_LIGHT = 0xFF1B2430.toInt()
}
