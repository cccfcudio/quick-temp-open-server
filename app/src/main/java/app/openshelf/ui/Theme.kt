package app.openshelf.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF1C1915)
private val Paper = Color(0xFFF4F1EA)
private val Pine = Color(0xFF0F6E56)
private val Mist = Color(0xFF8EEDD2)
private val Night = Color(0xFF121614)
private val CardDark = Color(0xFF1C2421)

private val LightColors = lightColorScheme(
    primary = Pine,
    onPrimary = Color.White,
    secondary = Color(0xFF8A5A00),
    background = Paper,
    surface = Color(0xFFFFFCF7),
    surfaceVariant = Color(0xFFE7F2EC),
    onBackground = Ink,
    onSurface = Ink,
    error = Color(0xFFBA1A1A),
    outline = Color(0xFF6F7F78),
)

private val DarkColors = darkColorScheme(
    primary = Mist,
    onPrimary = Color(0xFF07332A),
    secondary = Color(0xFFF0C674),
    background = Night,
    surface = CardDark,
    surfaceVariant = Color(0xFF24302C),
    onBackground = Color(0xFFF3F6F4),
    onSurface = Color(0xFFE7F2EC),
    error = Color(0xFFFFB4AB),
    outline = Color(0xFF3D524A),
)

@Composable
fun OpenShelfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
