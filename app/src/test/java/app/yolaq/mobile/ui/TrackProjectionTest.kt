package app.yolaq.mobile.ui

import app.yolaq.mobile.recording.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for fitting a track into the canvas.
 *
 * A projection that is subtly wrong draws a track that looks plausible and
 * isn't — an out-and-back rendered as a loop, or a line running off the edge —
 * and nobody notices until they compare it with the map on the web.
 */
class TrackProjectionTest {

    private fun point(latitude: Double, longitude: Double) = TrackPoint(
        latitude = latitude,
        longitude = longitude,
        elevation = null,
        speed = null,
        accuracy = 5f,
        recordedAt = 0L,
    )

    private val width = 300f
    private val height = 200f
    private val padding = 20f

    @Test
    fun `every point lands inside the padded canvas`() {
        val points = listOf(
            point(41.000, 29.000),
            point(41.002, 29.003),
            point(41.001, 29.001),
        )

        TrackProjection.project(points, width, height, padding).forEach {
            assertTrue("x=${it.x}", it.x >= padding - 0.01f && it.x <= width - padding + 0.01f)
            assertTrue("y=${it.y}", it.y >= padding - 0.01f && it.y <= height - padding + 0.01f)
        }
    }

    /** Canvas y grows downwards; latitude grows north. Getting this wrong flips the track. */
    @Test
    fun `north is up`() {
        val projected = TrackProjection.project(
            listOf(point(41.000, 29.000), point(41.002, 29.000)),
            width,
            height,
            padding,
        )

        assertTrue(projected[1].y < projected[0].y)
    }

    /**
     * A degree of longitude is shorter than a degree of latitude at 41°N. Without
     * the correction a square loop is drawn a third too wide.
     */
    @Test
    fun `equal ground distances are drawn equally long`() {
        // ~0.0009° of latitude and ~0.0012° of longitude are both roughly 100 m
        // at 41°N, so the projected legs should come out close to equal.
        val projected = TrackProjection.project(
            listOf(point(41.0, 29.0), point(41.0009, 29.0), point(41.0009, 29.0012)),
            width,
            height,
            padding,
        )

        val vertical = abs(projected[1].y - projected[0].y)
        val horizontal = abs(projected[2].x - projected[1].x)

        assertEquals(1.0, (horizontal / vertical).toDouble(), 0.05)
    }

    @Test
    fun `a track that has not moved is centred rather than dividing by zero`() {
        val projected = TrackProjection.project(
            listOf(point(41.0, 29.0), point(41.0, 29.0)),
            width,
            height,
            padding,
        )

        assertEquals(width / 2, projected[0].x, 0.01f)
        assertEquals(height / 2, projected[0].y, 0.01f)
    }

    @Test
    fun `a dead straight track still fits`() {
        val projected = TrackProjection.project(
            listOf(point(41.0, 29.0), point(41.0, 29.002)),
            width,
            height,
            padding,
        )

        assertEquals(padding, projected[0].x, 0.01f)
        assertEquals(width - padding, projected[1].x, 0.01f)
        assertEquals(height / 2, projected[0].y, 0.01f)
    }

    @Test
    fun `an empty track projects to nothing`() {
        assertTrue(TrackProjection.project(emptyList(), width, height, padding).isEmpty())
    }
}
