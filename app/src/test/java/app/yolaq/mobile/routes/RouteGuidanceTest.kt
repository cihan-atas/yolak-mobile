package app.yolaq.mobile.routes

import app.yolaq.mobile.recording.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the "am I still on the route?" measurement.
 *
 * Getting this wrong is quietly harmful in both directions: too generous and
 * the athlete runs half a kilometre the wrong way before anything says so; too
 * strict and it cries off-route while they are running correctly down the
 * pavement beside the drawn line.
 */
class RouteGuidanceTest {

    private fun point(latitude: Double, longitude: Double) = TrackPoint(
        latitude = latitude,
        longitude = longitude,
        elevation = null,
        speed = null,
        accuracy = 5f,
        recordedAt = 0L,
    )

    /** ~111 m of latitude per 0.001°, at any longitude. */
    private val straightRoute = listOf(point(41.0000, 29.0000), point(41.0100, 29.0000))

    @Test
    fun `on the line reads as no distance`() {
        val distance = RouteGuidance.distanceFromRoute(straightRoute, point(41.0050, 29.0000))

        assertEquals(0.0, distance!!, 1.0)
        assertFalse(RouteGuidance.isOffRoute(straightRoute, point(41.0050, 29.0000)))
    }

    /**
     * The case that matters: standing between two stored points, far from
     * both. Measuring to the nearest *point* rather than the nearest part of
     * the line would report over half a kilometre here.
     */
    @Test
    fun `midway between distant points is measured against the line`() {
        val sparse = listOf(point(41.0000, 29.0000), point(41.0200, 29.0000))

        val distance = RouteGuidance.distanceFromRoute(sparse, point(41.0100, 29.0000))

        assertEquals(0.0, distance!!, 1.0)
    }

    @Test
    fun `a step to the side is the perpendicular distance`() {
        // 0.0002° of longitude at 41°N is about 17 m.
        val distance = RouteGuidance.distanceFromRoute(straightRoute, point(41.0050, 29.0002))

        assertEquals(16.8, distance!!, 2.0)
        assertFalse(RouteGuidance.isOffRoute(straightRoute, point(41.0050, 29.0002)))
    }

    @Test
    fun `a wrong turn is called off route`() {
        // ~0.001° of longitude at 41°N is about 84 m.
        val position = point(41.0050, 29.0010)

        assertTrue(RouteGuidance.isOffRoute(straightRoute, position))
        assertEquals(84.0, RouteGuidance.distanceFromRoute(straightRoute, position)!!, 5.0)
    }

    @Test
    fun `past the finish measures from the finish, not the infinite line`() {
        // Directly beyond the end: the segment stops, so the distance is the
        // gap to its last point rather than zero.
        val distance = RouteGuidance.distanceFromRoute(straightRoute, point(41.0110, 29.0000))

        assertEquals(111.0, distance!!, 5.0)
    }

    @Test
    fun `no route means nothing to measure`() {
        assertNull(RouteGuidance.distanceFromRoute(emptyList(), point(41.0, 29.0)))
        assertFalse(RouteGuidance.isOffRoute(emptyList(), point(41.0, 29.0)))
    }

    @Test
    fun `a single-point route falls back to that point`() {
        val distance = RouteGuidance.distanceFromRoute(listOf(point(41.0, 29.0)), point(41.001, 29.0))

        assertEquals(111.0, distance!!, 2.0)
    }

    /**
     * How much of the route is left.
     *
     * The number an athlete looks down for, so it has to behave like a
     * distance and not like a counter: falling steadily as they move, never
     * sitting still and then jumping.
     */
    @Test
    fun `at the start the whole route is left`() {
        // 0.01 degrees of latitude is about 1113 m.
        val remaining = RouteGuidance.progress(straightRoute, point(41.0000, 29.0000))!!.remainingMeters

        assertEquals(1113.0, remaining, 5.0)
    }

    @Test
    fun `at the end nothing is left`() {
        val remaining = RouteGuidance.progress(straightRoute, point(41.0100, 29.0000))!!.remainingMeters

        assertEquals(0.0, remaining, 5.0)
    }

    /**
     * The case that decides whether the number is usable. Standing halfway
     * along a segment with nothing stored in between, measuring from the
     * nearest stored *point* would answer either the whole route or none of
     * it — so the figure would freeze for minutes and then drop in one step.
     */
    @Test
    fun `midway along a long segment counts down from where they actually are`() {
        // Two points 0.02 degrees apart: about 2226 m with nothing in between.
        val sparse = listOf(point(41.0000, 29.0000), point(41.0200, 29.0000))

        val remaining = RouteGuidance.progress(sparse, point(41.0100, 29.0000))!!.remainingMeters

        assertEquals(1113.0, remaining, 5.0)
    }

    @Test
    fun `standing beside the line still counts down along it`() {
        // 17 m to the side, halfway along: the remaining distance follows the
        // route, not the athlete's own detour.
        val remaining = RouteGuidance.progress(straightRoute, point(41.0050, 29.0002))!!.remainingMeters

        assertEquals(557.0, remaining, 10.0)
    }

    @Test
    fun `past the finish nothing is left`() {
        val remaining = RouteGuidance.progress(straightRoute, point(41.0110, 29.0000))!!.remainingMeters

        assertEquals(0.0, remaining, 5.0)
    }

    @Test
    fun `a multi-segment route sums the segments still ahead`() {
        // Three legs of about 1113 m each, standing at the first corner.
        val legs = listOf(
            point(41.0000, 29.0000),
            point(41.0100, 29.0000),
            point(41.0200, 29.0000),
            point(41.0300, 29.0000),
        )

        val remaining = RouteGuidance.progress(legs, point(41.0100, 29.0000))!!.remainingMeters

        assertEquals(2226.0, remaining, 10.0)
    }

    @Test
    fun `progress reports the same distance from the line as the standalone reading`() {
        val position = point(41.0050, 29.0010)

        val progress = RouteGuidance.progress(straightRoute, position)!!

        assertEquals(
            RouteGuidance.distanceFromRoute(straightRoute, position)!!,
            progress.distanceFromRoute,
            0.001,
        )
    }

    @Test
    fun `no route means no progress`() {
        assertNull(RouteGuidance.progress(emptyList(), point(41.0, 29.0)))
    }

    @Test
    fun `a single-point route has nothing left to cover`() {
        val progress = RouteGuidance.progress(listOf(point(41.0, 29.0)), point(41.001, 29.0))!!

        assertEquals(0.0, progress.remainingMeters, 0.001)
        assertEquals(111.0, progress.distanceFromRoute, 2.0)
    }
}
