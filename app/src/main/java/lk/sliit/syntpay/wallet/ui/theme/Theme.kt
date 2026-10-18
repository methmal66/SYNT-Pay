package lk.sliit.syntpay.wallet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The SYNT PAY palette, SYNTPAY_PROJECT.md section 8. The wallet and the terminal share it;
 * the terminal is simply denser, because it is equipment rather than a phone.
 */
object SyntPayPalette {
    val Background = Color(0xFF0E1113)
    val Surface = Color(0xFF171B1E)
    val SurfaceVariant = Color(0xFF1F2428)
    val Outline = Color(0xFF2A3034)
    val OnSurface = Color(0xFFE8EAEB)
    val OnSurfaceVariant = Color(0xFF9BA3A8)
    val Accent = Color(0xFF2FB88A)
    val Error = Color(0xFFE0574F)
    val Warning = Color(0xFFD9A441)
}

private val SyntPayColors = darkColorScheme(
    primary = SyntPayPalette.Accent,
    onPrimary = Color(0xFF05140E),
    secondary = SyntPayPalette.Accent,
    background = SyntPayPalette.Background,
    onBackground = SyntPayPalette.OnSurface,
    surface = SyntPayPalette.Surface,
    onSurface = SyntPayPalette.OnSurface,
    surfaceVariant = SyntPayPalette.SurfaceVariant,
    onSurfaceVariant = SyntPayPalette.OnSurfaceVariant,
    outline = SyntPayPalette.Outline,
    error = SyntPayPalette.Error,
    onError = Color(0xFF1A0605),
)

/** Tabular figures: money and indices must not jitter as digits change. */
val TabularFigures = TextStyle(fontFeatureSettings = "tnum")

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

private val SyntPayTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Light,
        fontSize = 44.sp,
        fontFeatureSettings = "tnum",
    ),
)

@Composable
fun SyntPayWalletTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // The palette is a fixed dark one by design: this is a payment instrument,
    // not a themed consumer app, and the demo must look identical on any handset.
    MaterialTheme(
        colorScheme = SyntPayColors,
        typography = SyntPayTypography,
        content = content,
    )
}
