package com.bohemian.odoo.pos.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * ZenZebra brand palette, read from the live site (zenzebra.in, computed styles 2026-10-27):
 * rich black #0A0A0A (+ #1A1A1A surfaces), cream white #F5F2EC (+ #DDD8D0 / #CFCBC4),
 * blood red #E0000F, gold #E6A525. Headings there are Montserrat 900, all caps; the E700 has
 * no Montserrat, so the till uses the system sans at Black weight with letter spacing.
 * The brand name itself is always written "ZenZebra", never upper-cased (2026-10-27).
 */
object Zz {
    val Black = Color(0xFF0A0A0A)
    val Ink = Color(0xFF1A1A1A)
    val Cream = Color(0xFFF5F2EC)
    val CreamDeep = Color(0xFFEAE5DC)
    val Line = Color(0xFFDDD8D0)
    val Muted = Color(0xFF6B655D)       // secondary text on cream (contrast ≥ 4.5:1)
    val MutedOnBlack = Color(0xFFCFCBC4)
    val Red = Color(0xFFE0000F)
    val RedDark = Color(0xFFB0000C)
    val Gold = Color(0xFFE6A525)
    val GoldSoft = Color(0xFFF6E3B8)
    val RedSoft = Color(0xFFFBD5D7)
    val Green = Color(0xFF1E8E3E)          // points earned
    /** Selected cart line (Odoo highlights it the same way). */
    val Selected = Color(0xFFE6EEF0)

    /**
     * Odoo's 12-colour index (pos.category.color, pos.note.color) → the hex the Odoo web
     * client uses. 0 = no colour.
     */
    private val odoo = longArrayOf(
        0xFFFFFFFF, 0xFFF06050, 0xFFF4A460, 0xFFF7CD1F, 0xFF6CC1ED, 0xFF814968,
        0xFFEB7E7F, 0xFF2C8397, 0xFF475577, 0xFFD6145F, 0xFF30C381, 0xFF9365B8,
    )

    /** Strong colour: the bar under a product tile. */
    fun odooColor(index: Int): Color = Color(odoo[index.coerceIn(0, 11)])

    /** Soft tint for a tab or tag background, the way the POS shows them; white for 0. */
    fun odooTint(index: Int): Color =
        if (index !in 1..11) Color.White else lerp(Color.White, odooColor(index), 0.38f)

    private fun lerp(a: Color, b: Color, t: Float) =
        Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t)
}

// Primary is rich black, not blood red: red is kept for the pay buttons and real alarms, so a
// normal switch, focused field or progress bar never reads as an error.
private val ZzColors = lightColorScheme(
    primary = Zz.Black,
    onPrimary = Zz.Cream,
    primaryContainer = Zz.CreamDeep,
    onPrimaryContainer = Zz.Black,
    secondary = Zz.Black,
    onSecondary = Zz.Cream,
    secondaryContainer = Zz.CreamDeep,
    onSecondaryContainer = Zz.Black,
    tertiary = Zz.Gold,
    onTertiary = Zz.Black,
    background = Zz.Cream,
    onBackground = Zz.Black,
    surface = Zz.Cream,
    onSurface = Zz.Black,
    surfaceVariant = Zz.CreamDeep,
    onSurfaceVariant = Zz.Muted,
    outline = Zz.Line,
    error = Zz.Red,
    onError = Zz.Cream,
    // Material 3 cards/dialogs/menus use these; left unset they default to Google's lavender greys.
    surfaceTint = Zz.Cream,
    surfaceBright = Zz.Cream,
    surfaceDim = Zz.CreamDeep,
    surfaceContainerLowest = Color(0xFFFFFDF9),
    surfaceContainerLow = Color(0xFFFBF9F5),
    surfaceContainer = Zz.Cream,
    surfaceContainerHigh = Color(0xFFF8F5F0),
    surfaceContainerHighest = Color(0xFFFFFDF9), // product cards: a touch lighter than the cream page
    inverseSurface = Zz.Black,
    inverseOnSurface = Zz.Cream,
    inversePrimary = Zz.Cream,
    outlineVariant = Zz.Line,
)

private val ZzType = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 0.5.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Bold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp),
    )
}

/** Heading style used for section titles and the top bar: heavy and tracked (caps come from the text, not the style). */
val ZzHeading = TextStyle(fontWeight = FontWeight.Black, letterSpacing = 1.sp)

@Composable
fun ZenZebraTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = ZzColors, typography = ZzType, content = content)
