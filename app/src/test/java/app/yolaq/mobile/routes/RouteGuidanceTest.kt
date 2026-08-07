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
}
