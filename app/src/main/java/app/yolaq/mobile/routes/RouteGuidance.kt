package app.yolaq.mobile.routes

import app.yolaq.mobile.recording.TrackPoint
import app.yolaq.mobile.recording.distanceBetween
import kotlin.math.max
import kotlin.math.min

/**
 * Where a recording stands against the route it is following.
 *
 * @property distanceFromRoute Metres from the nearest part of the line.
 * @property remainingMeters Metres still to cover along the line, measured
 *   from the nearest point on it to its end.
 */
data class RouteProgress(
    val distanceFromRoute: Double,
    val remainingMeters: Double,
)

/**
 * How a recording is doing against the route it is following.
 *
 * Answers the two questions a route is useful for while moving: am I still on
 * it, and how much is left? Distances rather than turn-by-turn directions —
 * the athlete can see the line, and a number they can glance at beats
 * instructions they have to read.
 */
object RouteGuidance {

    /**
     * Beyond this the athlete is treated as off the route.
     *
     * Wide enough to absorb GPS error and a route drawn down the middle of a
     * road the athlete is running along the side of; narrow enough that a
     * wrong turn shows up within a few seconds of walking.
     */
    const val OFF_ROUTE_METERS = 40.0

    /**
     * Distance from a position to the nearest point of a route.
     *
     * Measured against the route's *segments*, not just its stored points: a
     * routed line can run hundreds of metres between two points along a
     * straight road, and comparing against the points alone would call
     * somebody standing in the middle of that road badly off course.
     *
     * @param route The line being followed.
     * @param position Where the athlete is.
     * @return Metres from the line, or null when there is no route.
     */
    fun distanceFromRoute(route: List<TrackPoint>, position: TrackPoint): Double? =
        progress(route, position)?.distanceFromRoute

    /**
     * How far off the line the athlete is, and how much of it is left.
     *
     * The remaining distance is measured from the *projected* position — the
     * point on the line they are nearest to — not from the nearest stored
     * point. Halfway along a 400 m straight, the two answers differ by 200 m,
     * and the number would sit still for minutes and then jump.
     *
     * Both answers come out of one scan because they need the same thing: the
     * segment the athlete is closest to, and how far along it they are.
     *
     * @param route The line being followed.
     * @param position Where the athlete is.
     * @return The standing, or null when there is no route.
     */
    fun progress(route: List<TrackPoint>, position: TrackPoint): RouteProgress? {
        if (route.isEmpty()) {
            return null
        }
        if (route.size == 1) {
            return RouteProgress(distanceBetween(route.first(), position), 0.0)
        }

        val lengths = DoubleArray(route.size - 1) { distanceBetween(route[it], route[it + 1]) }
        // How much line lies beyond the end of each segment. Built once from
        // the back so the scan below can answer "what is left" in constant
        // time per segment instead of re-summing the tail every time.
        val beyond = DoubleArray(lengths.size)
        var accumulated = 0.0
        for (index in lengths.indices.reversed()) {
            beyond[index] = accumulated
            accumulated += lengths[index]
        }

        var closest = Double.MAX_VALUE
        var remaining = 0.0
        for (index in lengths.indices) {
            val (distance, along) = projectOntoSegment(
                route[index],
                route[index + 1],
                position,
                lengths[index],
            )
            if (distance < closest) {
                closest = distance
                remaining = (1.0 - along) * lengths[index] + beyond[index]
            }
        }
        return RouteProgress(closest, remaining)
    }

    /**
     * Whether the athlete has strayed far enough to warn about.
     *
     * @param route The line being followed.
     * @param position Where the athlete is.
     * @return True when off the route by more than [OFF_ROUTE_METERS].
     */
    fun isOffRoute(route: List<TrackPoint>, position: TrackPoint): Boolean {
        val distance = distanceFromRoute(route, position) ?: return false
        return distance > OFF_ROUTE_METERS
    }

    /**
     * Projects a position onto a route segment.
     *
     * Works in local metres rather than degrees: a degree of longitude is
     * shorter than a degree of latitude everywhere but the equator, and
     * projecting in raw degrees would put the nearest point in the wrong place
     * — worse the further from the equator, which is everywhere we run.
     *
     * @param start Segment start.
     * @param end Segment end.
     * @param position The position to measure.
     * @param segmentLength The segment's length in metres, already known to
     *   the caller — recomputing it here would double the haversines.
     * @return Metres from the segment, and how far along it the nearest point
     *   lies as a fraction from 0 (the start) to 1 (the end).
     */
    private fun projectOntoSegment(
        start: TrackPoint,
        end: TrackPoint,
        position: TrackPoint,
        segmentLength: Double,
    ): Pair<Double, Double> {
        if (segmentLength < 1e-6) {
            return distanceBetween(start, position) to 0.0
        }

        // Local flat-earth frame anchored at the segment start. Over the tens
        // of metres a segment spans, the error from ignoring curvature is far
        // below GPS noise.
        val metresPerDegreeLat = 111_320.0
        val metresPerDegreeLon = metresPerDegreeLat * kotlin.math.cos(Math.toRadians(start.latitude))

        val endX = (end.longitude - start.longitude) * metresPerDegreeLon
        val endY = (end.latitude - start.latitude) * metresPerDegreeLat
        val posX = (position.longitude - start.longitude) * metresPerDegreeLon
        val posY = (position.latitude - start.latitude) * metresPerDegreeLat

        // How far along the segment the closest point lies, clamped to its
        // ends so a position beyond the finish measures from the finish.
        val squared = endX * endX + endY * endY
        val along = max(0.0, min(1.0, (posX * endX + posY * endY) / squared))

        val nearestX = along * endX
        val nearestY = along * endY
        return kotlin.math.hypot(posX - nearestX, posY - nearestY) to along
    }
}
