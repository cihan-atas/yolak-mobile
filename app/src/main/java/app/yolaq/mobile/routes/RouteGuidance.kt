package app.yolaq.mobile.routes

import app.yolaq.mobile.recording.TrackPoint
import app.yolaq.mobile.recording.distanceBetween
import kotlin.math.max
import kotlin.math.min

/**
 * How far a recording has strayed from the route it is following.
 *
 * Answers the one question a route is useful for while moving: am I still on
 * it? A distance rather than turn-by-turn directions — the athlete can see the
 * line, and a number they can glance at beats instructions they have to read.
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
    fun distanceFromRoute(route: List<TrackPoint>, position: TrackPoint): Double? {
        if (route.isEmpty()) {
            return null
        }
        if (route.size == 1) {
            return distanceBetween(route.first(), position)
        }

        var closest = Double.MAX_VALUE
        for (index in 0 until route.size - 1) {
            closest = min(closest, distanceToSegment(route[index], route[index + 1], position))
        }
        return closest
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
     * Perpendicular distance from a point to a route segment.
     *
     * Works in local metres rather than degrees: a degree of longitude is
     * shorter than a degree of latitude everywhere but the equator, and
     * projecting in raw degrees would put the nearest point in the wrong place
     * — worse the further from the equator, which is everywhere we run.
     *
     * @param start Segment start.
     * @param end Segment end.
     * @param position The position to measure.
     * @return Metres from the segment.
     */
    private fun distanceToSegment(start: TrackPoint, end: TrackPoint, position: TrackPoint): Double {
        val segmentLength = distanceBetween(start, end)
        if (segmentLength < 1e-6) {
            return distanceBetween(start, position)
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
        return kotlin.math.hypot(posX - nearestX, posY - nearestY)
    }
}
