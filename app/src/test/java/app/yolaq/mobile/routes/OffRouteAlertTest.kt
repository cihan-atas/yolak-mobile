package app.yolaq.mobile.routes

import app.yolaq.mobile.recording.TrackPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for when leaving the route is worth a buzz.
 *
 * The failure modes point opposite ways and both make the warning useless: fire
 * on every fix and the athlete learns to ignore the phone; fire only once ever
 * and a second wrong turn passes in silence. The middle — one buzz per straying
 * — is what these pin down.
 */
class OffRouteAlertTest {

    private fun point(latitude: Double, longitude: Double) = TrackPoint(
        latitude = latitude,
        longitude = longitude,
        elevation = null,
        speed = null,
        accuracy = 5f,
        recordedAt = 0L,
    )

    /** A 1.1 km line due north; 0.001° of longitude at 41°N is about 84 m. */
    private val route = listOf(point(41.0000, 29.0000), point(41.0100, 29.0000))

    private val onRoute = point(41.0050, 29.0000)
    /** ~84 m to the side: clearly off. */
    private val wellOff = point(41.0050, 29.0010)
    /** ~17 m to the side: on the route by both thresholds. */
    private val besideTheLine = point(41.0050, 29.0002)

    @Test
    fun `running along the route never warns`() {
        val alert = OffRouteAlert()

        assertFalse(alert.update(route, onRoute))
        assertFalse(alert.update(route, besideTheLine))
        assertFalse(alert.update(route, onRoute))
    }

    @Test
    fun `the fix that strays warns`() {
        val alert = OffRouteAlert()
        alert.update(route, onRoute)

        assertTrue(alert.update(route, wellOff))
    }

    @Test
    fun `staying off route does not keep warning`() {
        // The whole reason this holds state: a detour is many fixes, and one
        // buzz per second for the length of it is a phone nobody listens to.
        val alert = OffRouteAlert()
        alert.update(route, onRoute)
        alert.update(route, wellOff)

        assertFalse(alert.update(route, wellOff))
        assertFalse(alert.update(route, point(41.0050, 29.0020)))
        assertFalse(alert.update(route, wellOff))
    }

    @Test
    fun `going wrong a second time warns again`() {
        val alert = OffRouteAlert()
        alert.update(route, wellOff)
        alert.update(route, onRoute)

        assertTrue(alert.update(route, wellOff))
    }

    /**
     * The case the second threshold exists for. A route drawn down the middle
     * of a road the athlete is running along the side of sits them permanently
     * near the boundary, where GPS noise alone crosses it back and forth. With
     * one threshold every outward wobble is a fresh buzz.
     */
    @Test
    fun `hovering at the boundary does not buzz repeatedly`() {
        val alert = OffRouteAlert()
        // ~42 m out: just past the 40 m line.
        val justOut = point(41.0050, 29.00050)
        // ~34 m out: back inside the 40 m line, but not inside the 25 m one.
        val justIn = point(41.0050, 29.00040)

        assertTrue(alert.update(route, justOut))
        assertFalse(alert.update(route, justIn))
        assertFalse(alert.update(route, justOut))
        assertFalse(alert.update(route, justIn))
    }

    @Test
    fun `coming properly back re-arms the warning`() {
        val alert = OffRouteAlert()
        alert.update(route, wellOff)

        // Inside the return threshold, not merely inside the leaving one.
        assertFalse(alert.update(route, besideTheLine))
        assertTrue(alert.update(route, wellOff))
    }

    @Test
    fun `no route means nothing to stray from`() {
        val alert = OffRouteAlert()

        assertFalse(alert.update(emptyList(), wellOff))
    }

    @Test
    fun `reset forgets a straying from the last outing`() {
        val alert = OffRouteAlert()
        alert.update(route, wellOff)

        alert.reset()

        assertTrue(alert.update(route, wellOff))
    }
}
