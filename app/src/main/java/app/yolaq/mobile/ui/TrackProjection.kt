package app.yolaq.mobile.ui

import app.yolaq.mobile.recording.TrackPoint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * A point in canvas space.
 *
 * @property x Pixels from the left.
 * @property y Pixels from the top.
 */
data class Point2D(val x: Float, val y: Float)

/**
 * Fits a recorded track into a canvas.
 *
 * The recording screen draws the track itself rather than putting it on a
 * basemap. A basemap would mean a map SDK, tile downloads over mobile data, and
 * a renderer running for the whole outing — real battery, for a picture the
 * athlete already knows: they can see the street they are standing on. The
 * shape of the line answers the question that actually gets asked mid-run
 * ("has it been recording?"), it costs nothing, and it works in a dead spot.
 * The full map with a basemap is one tab away, in the web view.
 *
 * Kept free of Compose and Android types so the projection can be tested on the
 * JVM — an off-by-one here draws a track that silently looks wrong.
 */
object TrackProjection {

    /**
     * Projects a track into canvas coordinates.
     *
     * Equirectangular with a cosine correction at the track's own latitude:
     * over the few kilometres of an outing this is indistinguishable from a
     * proper projection, and it keeps the aspect ratio honest so a straight
     * out-and-back does not render as a loop.
     *
     * @param points The fixes, oldest first.
     * @param width Canvas width in pixels.
     * @param height Canvas height in pixels.
     * @param padding Pixels to keep clear on every side.
     * @return The projected points, in the same order.
     */
    fun project(
        points: List<TrackPoint>,
        width: Float,
        height: Float,
        padding: Float,
    ): List<Point2D> {
        if (points.isEmpty()) {
            return emptyList()
        }

        val usableWidth = max(1f, width - 2 * padding)
        val usableHeight = max(1f, height - 2 * padding)

        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }

        // Longitude degrees are shorter than latitude degrees everywhere but
        // the equator; without this a track drawn at 41°N is stretched sideways
        // by a third.
        val lonScale = cos(Math.toRadians((minLat + maxLat) / 2))

        val spanX = (maxLon - minLon) * lonScale
        val spanY = maxLat - minLat

        // A track that has not moved yet — or one that runs dead straight —
        // has no span on at least one axis. Drawing it centred beats dividing
        // by zero.
        val scale = when {
            spanX <= 0.0 && spanY <= 0.0 -> 0.0
            spanX <= 0.0 -> usableHeight / spanY
            spanY <= 0.0 -> usableWidth / spanX
            else -> min(usableWidth / spanX, usableHeight / spanY)
        }

        val drawnWidth = spanX * scale
        val drawnHeight = spanY * scale
        // Centre whatever is left over, so a north-south track sits in the
        // middle of the canvas rather than hugging its left edge.
        val offsetX = padding + (usableWidth - drawnWidth) / 2
        val offsetY = padding + (usableHeight - drawnHeight) / 2

        return points.map { point ->
            Point2D(
                x = (offsetX + (point.longitude - minLon) * lonScale * scale).toFloat(),
                // Canvas y grows downwards while latitude grows northwards, so
                // the track would otherwise be drawn upside down.
                y = (offsetY + (maxLat - point.latitude) * scale).toFloat(),
            )
        }
    }
}
