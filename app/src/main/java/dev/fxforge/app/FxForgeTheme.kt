package dev.fxforge.app

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val FxForgeLightColors = lightColorScheme(
    primary = Color(0xFF4659A9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFE2FF),
    onPrimaryContainer = Color(0xFF101B55),
    secondary = Color(0xFF356B63),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC0EEE4),
    onSecondaryContainer = Color(0xFF00201B),
    tertiary = Color(0xFF765A24),
    background = Color(0xFFF8F7FC),
    surface = Color(0xFFF8F7FC),
    surfaceContainer = Color(0xFFECEBF3),
    surfaceContainerLow = Color(0xFFF1F0F7),
    error = Color(0xFFBA1A1A)
)

private val FxForgeDarkColors = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF182765),
    primaryContainer = Color(0xFF303F86),
    onPrimaryContainer = Color(0xFFE0E2FF),
    secondary = Color(0xFF9FD4C9),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF1D5149),
    onSecondaryContainer = Color(0xFFBAEEE3),
    tertiary = Color(0xFFE6C18A),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceContainer = Color(0xFF1D2028),
    surfaceContainerLow = Color(0xFF191C23),
    error = Color(0xFFFFB4AB)
)

@Composable
internal fun FxForgeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> FxForgeDarkColors
        else -> FxForgeLightColors
    }
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}
