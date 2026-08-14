package app.yolaq.mobile.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projection the route frame is shaped by.
 *
 * Worth testing because getting it wrong is invisible in code review and
 * obvious on the finished picture: a frame with the wrong proportions is
 * exactly the "map too big, route too small" the athlete reported.
 */
class MercatorTest {

    /** A route running four times further east-west than north-south. */
    @Test
    fun `wide route gives a wide frame`() {
        // At this latitude a degree of longitude is about three quarters of a
        // degree of latitude on the ground, so the ratio is not simply 4.
        val route = listOf(
            41.0 to 29.0,
            41.1 to 29.4,
        )
        val aspect = Mercator.aspect(route)
        assertTrue("beklenen: 1'den büyük, gelen: $aspect", aspect > 1f)
        assertTrue("beklenen: 3 veya altı, gelen: $aspect", aspect <= 3f)
    }

    /** A route running mostly north-south. */
    @Test
    fun `tall route gives a tall frame`() {
        val route = listOf(
            41.0 to 29.0,
            41.4 to 29.02,
        )
        val aspect = Mercator.aspect(route)
        assertTrue("beklenen: 1'den küçük, gelen: $aspect", aspect < 1f)
        assertTrue("beklenen: 1/3 veya üstü, gelen: $aspect", aspect >= 1f / 3f)
    }

    /**
     * A dead-straight out and back.
     *
     * Its projected height is nearly zero, and the honest ratio would be a
     * frame a few pixels tall with a line lost in it. The clamp is what keeps
     * that drawable.
     */
    @Test
    fun `straight line is clamped rather than collapsing`() {
        val route = listOf(41.0 to 29.0, 41.0 to 29.5)
        assertEquals(3f, Mercator.aspect(route), 0.001f)
    }

    /** Fewer than two points is not a shape. */
    @Test
    fun `single point falls back to square`() {
        assertEquals(1f, Mercator.aspect(listOf(41.0 to 29.0)), 0.001f)
    }

    /** North and south of the equator project symmetrically. */
    @Test
    fun `projection is symmetric about the equator`() {
        assertEquals(0.5, Mercator.y(0.0), 1e-9)
        assertEquals(1.0 - Mercator.y(45.0), Mercator.y(-45.0), 1e-9)
    }

    /** Longitude maps linearly across the world. */
    @Test
    fun `longitude spans the world linearly`() {
        assertEquals(0.0, Mercator.x(-180.0), 1e-9)
        assertEquals(0.5, Mercator.x(0.0), 1e-9)
        assertEquals(1.0, Mercator.x(180.0), 1e-9)
    }
}
