package re.abbot.librecr.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Brand: teal, carried over from the original theme but tuned for Material 3 tonal roles.
val LightColors = lightColorScheme(
    primary = Color(0xFF006A6A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF1F0),
    onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF4A6363),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E7),
    onSecondaryContainer = Color(0xFF051F1F),
    tertiary = Color(0xFF4B607C),
    tertiaryContainer = Color(0xFFD3E4FF),
    onTertiaryContainer = Color(0xFF041C35),
    background = Color(0xFFF4FBFA),
    onBackground = Color(0xFF161D1D),
    surface = Color(0xFFF4FBFA),
    onSurface = Color(0xFF161D1D),
    surfaceVariant = Color(0xFFDAE5E4),
    onSurfaceVariant = Color(0xFF3F4948),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF4CDADA),
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F4F),
    onPrimaryContainer = Color(0xFF9CF1F0),
    secondary = Color(0xFFB0CCCB),
    onSecondary = Color(0xFF1B3534),
    secondaryContainer = Color(0xFF324B4B),
    onSecondaryContainer = Color(0xFFCCE8E7),
    tertiary = Color(0xFFB3C8E8),
    tertiaryContainer = Color(0xFF334863),
    onTertiaryContainer = Color(0xFFD3E4FF),
    background = Color(0xFF0E1514),
    onBackground = Color(0xFFDDE4E3),
    surface = Color(0xFF0E1514),
    onSurface = Color(0xFFDDE4E3),
    surfaceVariant = Color(0xFF3F4948),
    onSurfaceVariant = Color(0xFFBEC9C8),
)

/**
 * Glucose range palette used by the chart, statistics bar and overlays. Buckets follow
 * the clinical AGP convention (very-low <54, target band, very-high >250); the inner
 * low/high edges come from the user's configurable target so coloring stays in sync.
 */
@Immutable
data class GlucoseColors(
    val veryLow: Color,
    val low: Color,
    val inRange: Color,
    val high: Color,
    val veryHigh: Color,
) {
    fun forMgDl(mgDl: Int, targetLow: Int = 70, targetHigh: Int = 180): Color = when {
        mgDl < 54 -> veryLow
        mgDl < targetLow -> low
        mgDl <= targetHigh -> inRange
        mgDl <= 250 -> high
        else -> veryHigh
    }
}

val LightGlucoseColors = GlucoseColors(
    veryLow = Color(0xFFD32F2F),
    low = Color(0xFFF4511E),
    inRange = Color(0xFF2E9E4F),
    high = Color(0xFFF9A825),
    veryHigh = Color(0xFFEF6C00),
)

val DarkGlucoseColors = GlucoseColors(
    veryLow = Color(0xFFFF6E6E),
    low = Color(0xFFFF8A65),
    inRange = Color(0xFF5BD17C),
    high = Color(0xFFFFC95C),
    veryHigh = Color(0xFFFFA040),
)

val LocalGlucoseColors = staticCompositionLocalOf { LightGlucoseColors }
