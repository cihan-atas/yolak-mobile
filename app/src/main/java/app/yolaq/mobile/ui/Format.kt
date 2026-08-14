package app.yolaq.mobile.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yolaq.mobile.R
import java.util.Locale

/**
 * A translucent slab for anything floating over the map.
 *
 * Readable over streets and parks alike without hiding them: the map is the
 * context these messages belong to, and a solid panel would take it away.
 *
 * @param modifier Layout modifier.
 * @param compact Tighter padding, for a card that shares a corner with the map
 *   rather than leading the screen.
 * @param content What sits on the slab.
 */
@Composable
fun OverlayCard(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.padding(
                horizontal = if (compact) 8.dp else 16.dp,
                vertical = if (compact) 6.dp else 10.dp,
            ),
        ) { content() }
    }
}

/**
 * Renders a duration as hours:minutes:seconds.
 *
 * Always three parts, zero-padded, so the numbers do not jump sideways as the
 * clock passes an hour — these are read at a glance while moving.
 *
 * @param millis How long, in milliseconds.
 * @return The formatted clock.
 */
fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    return String.format(
        Locale.US,
        "%02d:%02d:%02d",
        totalSeconds / 3600,
        (totalSeconds % 3600) / 60,
        totalSeconds % 60,
    )
}

/**
 * Renders a distance in kilometres.
 *
 * Three decimals: at two, a slow walk's first hundred metres tick over in jumps
 * of 10 m and the screen looks stuck. Metre resolution shows the distance
 * actually moving.
 *
 * @param meters The distance.
 * @param decimals How many places — the big readouts use three, summaries two.
 * @return The formatted number, without a unit.
 */
fun formatKilometres(meters: Double, decimals: Int = 3): String =
    String.format(Locale.US, "%.${decimals}f", meters / 1000.0)

/**
 * Renders a pace as minutes and seconds per kilometre.
 *
 * @param context Any context, for the placeholder.
 * @param secondsPerKm The pace, or null while stationary.
 * @return The formatted pace, or an em-dash placeholder.
 */
fun formatPace(context: Context, secondsPerKm: Double?): String =
    secondsPerKm?.takeIf { it.isFinite() && it > 0 }?.let {
        val seconds = it.toInt()
        String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    } ?: context.getString(R.string.value_none)

/**
 * Renders a speed in kilometres per hour.
 *
 * @param context Any context, for the placeholder.
 * @param metresPerSecond The speed, or null when the receiver has not said.
 * @return The formatted speed, without a unit.
 */
fun formatSpeed(context: Context, metresPerSecond: Double?): String =
    metresPerSecond?.takeIf { it.isFinite() }?.let {
        String.format(Locale.US, "%.1f", it * 3.6)
    } ?: context.getString(R.string.value_none)

/**
 * Pace in seconds per kilometre for a speed, or null when there is no movement
 * to describe.
 *
 * @param metresPerSecond The speed.
 * @return Seconds per kilometre, or null.
 */
fun paceOf(metresPerSecond: Double?): Double? =
    metresPerSecond?.takeIf { it > 0.1 }?.let { 1000.0 / it }
