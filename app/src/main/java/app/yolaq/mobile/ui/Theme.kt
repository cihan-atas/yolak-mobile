package app.yolaq.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * yolak's colours, taken from the web app's own logo.
 *
 * The native recording screen is one tab away from the web app, and the two
 * sitting side by side in the same shell have to look like one product —
 * Material's default purple made the recorder look like a different app that
 * happened to be bundled in.
 *
 * Deliberately not dynamic colour: on a phone with wallpaper theming the
 * recorder would drift to whatever the wallpaper suggests while the web tab
 * stayed green, which is worse than either choice on its own.
 */

/** The trail in the logo. */
private val TrailGreen = Color(0xFF1D9E75)

/** The trailhead ring: the darker of the two logo greens. */
private val DeepGreen = Color(0xFF0F6E56)

/** The trail, lightened — legible on a dark background. */
private val LightGreen = Color(0xFF5DCAA5)

/** Barely-tinted green, for surfaces that should read as "ours" but stay quiet. */
private val Mist = Color(0xFFE1F5EE)

/** Near-black with the same green cast, so dark mode is not a neutral grey. */
private val Pine = Color(0xFF04342C)

private val Light = lightColorScheme(
    primary = TrailGreen,
    onPrimary = Color.White,
    primaryContainer = Mist,
    onPrimaryContainer = Pine,
    secondary = DeepGreen,
    onSecondary = Color.White,
    // The start-of-track marker, which has to stand apart from the line itself.
    tertiary = DeepGreen,
    surfaceVariant = Mist,
)

private val Dark = darkColorScheme(
    primary = LightGreen,
    onPrimary = Pine,
    primaryContainer = DeepGreen,
    onPrimaryContainer = Mist,
    secondary = TrailGreen,
    onSecondary = Pine,
    tertiary = Mist,
    surfaceVariant = Pine,
)

/**
 * Applies yolak's colours.
 *
 * @param content The UI to theme.
 */
@Composable
fun YolakTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
