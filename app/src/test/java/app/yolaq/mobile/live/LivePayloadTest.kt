package app.yolaq.mobile.live

import app.yolaq.mobile.recording.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Tests for the live-tracking request body.
 *
 * The server drops a fix it cannot parse and answers 200 either way, so a
 * malformed payload shows up as a live view that mysteriously never moves —
 * with nothing on the phone to suggest why.
 */
class LivePayloadTest {

    private fun point(offsetSeconds: Long, speed: Double? = 1.4) = TrackPoint(
        latitude = 41.0,
        longitude = 29.0,
        elevation = 120.0,
        speed = speed,
        accuracy = 5f,
        recordedAt = 1_754_500_000_000L + offsetSeconds * 1000,
    )

    @Test
    fun `sends a batch under the key the server reads`() {
        val body = livePayload(listOf(point(0), point(1)))

        assertTrue(body.startsWith("""{"locations":["""))
        assertEquals(2, body.split("\"lat\":").size - 1)
    }

    /**
     * Field names matter more than they look: the server also accepts tracker
     * dialects, and speed arriving under OwnTracks' `vel` is read as km/h,
     * which would show the athlete moving 3.6 times too fast.
     */
    @Test
    fun `uses the server's own field names`() {
        val body = livePayload(listOf(point(0)))

        assertTrue(body.contains("\"lat\":"))
        assertTrue(body.contains("\"lon\":"))
        assertTrue(body.contains("\"speed\":"))
        assertFalse(body.contains("\"vel\":"))
    }

    @Test
    fun `sends the time of the fix, not the time of the request`() {
        val body = livePayload(listOf(point(0)))

        assertTrue(body.contains("\"recorded_at\":\"2025-08-06T"))
    }

    @Test
    fun `omits fields the receiver did not report`() {
        val body = livePayload(listOf(point(0, speed = null)))

        assertFalse(body.contains("\"speed\":"))
    }

    /** A Turkish phone would otherwise write `41,000000` and be dropped. */
    @Test
    fun `formats numbers as json on a comma-decimal locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            val body = livePayload(listOf(point(0)))

            assertTrue(body.contains("\"lat\":41.000000"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `an empty batch is still valid json`() {
        assertEquals("""{"locations":[]}""", livePayload(emptyList()))
    }
}
