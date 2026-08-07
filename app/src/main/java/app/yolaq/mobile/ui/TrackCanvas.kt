package app.yolaq.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.yolaq.mobile.R
import app.yolaq.mobile.recording.TrackPoint

/**
 * Draws the track recorded so far.
 *
 * The question this answers mid-outing is "is it actually recording?", and a
 * line that grows answers it at a glance — better than a point count, and
 * without the battery cost of a basemap (see [TrackProjection]).
 *
 * @param points The accepted fixes, oldest first.
 * @param modifier Layout modifier.
 */
@Composable
fun TrackCanvas(points: List<TrackPoint>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val trackColor = MaterialTheme.colorScheme.primary
    val startColor = MaterialTheme.colorScheme.tertiary
    val surface = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (points.size < 2) {
            // One point is not a shape, and an empty canvas reads as a broken
            // screen rather than a recording that has just begun.
            Text(
                text = context.getString(R.string.track_waiting),
                style = MaterialTheme.typography.bodySmall,
            )
            return@Box
        }

        Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            val projected = TrackProjection.project(
                points = points,
                width = size.width,
                height = size.height,
                padding = PADDING_PX,
            )

            drawPath(
                path = Path().apply {
                    moveTo(projected.first().x, projected.first().y)
                    projected.drop(1).forEach { lineTo(it.x, it.y) }
                },
                color = trackColor,
                style = Stroke(width = STROKE_PX),
            )

            // Where the outing began, so a loop can be told from an
            // out-and-back at a glance.
            drawCircle(
                color = startColor,
                radius = MARKER_RADIUS_PX,
                center = Offset(projected.first().x, projected.first().y),
            )
            // Where the athlete is now: filled, with a ring so it stays visible
            // when the track doubles back over itself.
            drawCircle(
                color = trackColor,
                radius = MARKER_RADIUS_PX,
                center = Offset(projected.last().x, projected.last().y),
            )
            drawCircle(
                color = surface,
                radius = MARKER_RADIUS_PX / 2,
                center = Offset(projected.last().x, projected.last().y),
            )
        }
    }
}

/** Keeps the line clear of the edges, including its own stroke width. */
private const val PADDING_PX = 24f

private const val STROKE_PX = 6f

private const val MARKER_RADIUS_PX = 10f
