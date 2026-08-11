package app.yolaq.mobile.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for starting a recording indoors on a Wi-Fi position.
 *
 * Indoors the satellite receiver reports nothing at all, so the recorder used
 * to sit on a wait screen with no accuracy to show and no way past it. A
 * coarse position from Wi-Fi and cell towers ends that wait — and is the most
 * dangerous thing that could be let near this code, because it is exactly the
 * kind of drifting, tens-of-metres input that once had a stationary phone
 * recording distance (BUG-4).
 *
 * So the line these tests hold is: a coarse position may start the clock and
 * move the map, and may never touch the track or the distance.
 */
class ApproximatePositionTest {

    private val metreInDegrees = 1.0 / 111_320.0

    private val startLat = 41.0
    private val startLon = 29.0
    private val startTime = 1_700_000_000_000L

    @Before
    fun reset() {
        RecordingRepository.stop()
    }

    /**
     * Builds a fix north of the start point.
     *
     * @param metresNorth Displacement from the start, in metres.
     * @param secondsIn Offset from the start time, in seconds.
     * @param accuracy Reported accuracy in metres.
     * @return The synthesised fix.
     */
    private fun fix(
        metresNorth: Double,
        secondsIn: Long,
        accuracy: Float,
    ) = TrackPoint(
        latitude = startLat + metresNorth * metreInDegrees,
        longitude = startLon,
        elevation = null,
        speed = null,
        accuracy = accuracy,
        recordedAt = startTime + secondsIn * 1000L,
    )

    @Test
    fun `a coarse position ends the wait and starts the clock`() {
        RecordingRepository.start(startTime)
        assertEquals(RecordingStatus.ACQUIRING, RecordingRepository.state.value.status)

        // A typical indoor Wi-Fi fix: useless for a track, fine for "you are
        // in this building".
        assertTrue(RecordingRepository.offerApproximate(fix(0.0, 0, accuracy = 40f), now = startTime))

        val state = RecordingRepository.state.value
        assertEquals(RecordingStatus.RECORDING, state.status)
        assertTrue(state.awaitingSatellites)
        assertNotNull(state.approximatePosition)
        assertEquals(startTime, state.stretchStartedAt)
    }

    @Test
    fun `the clock starts now, not when the cached fix was taken`() {
        // The phone's last known position is routinely minutes old — it comes
        // from whichever app asked for location last. Starting the outing's
        // clock at that timestamp made a recording report minutes of elapsed
        // time in its first second.
        RecordingRepository.start(startTime)
        val stale = fix(0.0, -300, accuracy = 40f) // five minutes old

        RecordingRepository.offerApproximate(stale, now = startTime)

        assertEquals(startTime, RecordingRepository.state.value.stretchStartedAt)
        assertEquals(0L, RecordingRepository.state.value.elapsedMillis(startTime))
    }

    @Test
    fun `a new recording does not inherit the previous one's clock`() {
        RecordingRepository.start(startTime)
        RecordingRepository.offerApproximate(fix(0.0, 0, accuracy = 40f), now = startTime)
        RecordingRepository.stop(startTime + 60_000)

        // A second outing that never gets a fix at all must still report
        // nothing, rather than the first outing's age.
        RecordingRepository.start(startTime + 600_000)
        val finished = RecordingRepository.stop(startTime + 600_005)

        assertEquals(0L, finished.movingMillis)
    }

    @Test
    fun `a coarse position never becomes a track point`() {
        RecordingRepository.start(startTime)
        RecordingRepository.offerApproximate(fix(0.0, 0, accuracy = 40f), now = startTime)

        assertTrue(RecordingRepository.state.value.points.isEmpty())
        assertEquals(0.0, RecordingRepository.state.value.distanceMeters, 0.0)
    }

    @Test
    fun `a drifting coarse position gains no distance`() {
        // The BUG-4 scenario, replayed through the new door: a phone on a
        // table indoors, its Wi-Fi position hopping between access points.
        RecordingRepository.start(startTime)
        repeat(60) { second ->
            val drift = if (second % 2 == 0) 30.0 else -30.0
            RecordingRepository.offerApproximate(fix(drift, second.toLong(), accuracy = 45f), now = startTime)
        }

        val state = RecordingRepository.state.value
        assertEquals(0.0, state.distanceMeters, 0.0)
        assertTrue(state.points.isEmpty())
    }

    @Test
    fun `a hopeless position is ignored entirely`() {
        RecordingRepository.start(startTime)

        // Cell-tower-only: knowing the athlete is somewhere in a two-kilometre
        // circle is not knowing where they are.
        assertFalse(RecordingRepository.offerApproximate(fix(0.0, 0, accuracy = 2_000f), now = startTime))
        assertEquals(RecordingStatus.ACQUIRING, RecordingRepository.state.value.status)
    }

    @Test
    fun `nothing is offered while idle`() {
        assertFalse(RecordingRepository.offerApproximate(fix(0.0, 0, accuracy = 40f), now = startTime))
        assertEquals(RecordingStatus.IDLE, RecordingRepository.state.value.status)
    }

    @Test
    fun `the track begins at the first real fix and the approximation is dropped`() {
        RecordingRepository.start(startTime)
        RecordingRepository.offerApproximate(fix(0.0, 0, accuracy = 40f), now = startTime)

        // Stepping outside: a proper fix arrives.
        assertTrue(RecordingRepository.offer(fix(0.0, 30, accuracy = 6f)))

        val state = RecordingRepository.state.value
        assertEquals(1, state.points.size)
        assertFalse(state.awaitingSatellites)
        assertNull(state.approximatePosition)
        // The anchor is not movement.
        assertEquals(0.0, state.distanceMeters, 0.0)
    }

    @Test
    fun `the anchor is held to the strict threshold, not the loose one`() {
        RecordingRepository.start(startTime)
        RecordingRepository.offerApproximate(fix(0.0, 0, accuracy = 40f), now = startTime)

        // 20 m is good enough to keep a running recording going but not to
        // begin one: a track anchored on a vague fix has to filter hard
        // afterwards, and filtering hard is how a slow walk gets thrown away.
        assertFalse(RecordingRepository.offer(fix(0.0, 10, accuracy = 20f)))
        assertTrue(RecordingRepository.state.value.points.isEmpty())
        assertTrue(RecordingRepository.state.value.awaitingSatellites)

        assertTrue(RecordingRepository.offer(fix(0.0, 20, accuracy = 8f)))
        assertEquals(1, RecordingRepository.state.value.points.size)
    }

    @Test
    fun `a stubbornly mediocre signal still anchors rather than recording nothing`() {
        // Under trees or between buildings the receiver can sit at fifteen
        // metres indefinitely. Holding out for the textbook ten forever means
        // the outing ends with no points at all, which is worse than a track
        // begun on a slightly vague fix.
        RecordingRepository.start(startTime)
        RecordingRepository.offerApproximate(fix(0.0, 0, accuracy = 40f), now = startTime)

        // Nothing good enough arrives for the first three quarters of a minute.
        repeat(40) { second ->
            assertFalse(RecordingRepository.offer(fix(0.0, second.toLong(), accuracy = 15f)))
        }
        assertTrue(RecordingRepository.state.value.points.isEmpty())

        // Past the grace period the same fix is taken.
        assertTrue(RecordingRepository.offer(fix(0.0, 46, accuracy = 15f)))
        assertEquals(1, RecordingRepository.state.value.points.size)
        assertFalse(RecordingRepository.state.value.awaitingSatellites)
    }

    @Test
    fun `relaxing the anchor never lets a hopeless fix in`() {
        RecordingRepository.start(startTime)
        RecordingRepository.offerApproximate(fix(0.0, 0, accuracy = 40f), now = startTime)

        // Well past the grace period, but 40 m is beyond what any recording
        // accepts — relaxing means "take the best on offer", not "take
        // anything".
        assertFalse(RecordingRepository.offer(fix(0.0, 120, accuracy = 40f)))
        assertTrue(RecordingRepository.state.value.points.isEmpty())
    }

    @Test
    fun `a coarse position stops mattering once the track has an anchor`() {
        RecordingRepository.start(startTime)
        RecordingRepository.offer(fix(0.0, 0, accuracy = 5f))
        assertEquals(1, RecordingRepository.state.value.points.size)

        // Wi-Fi keeps reporting throughout the outing; from here it is noise.
        assertFalse(RecordingRepository.offerApproximate(fix(100.0, 5, accuracy = 40f), now = startTime))
        val state = RecordingRepository.state.value
        assertNull(state.approximatePosition)
        assertFalse(state.awaitingSatellites)
        assertEquals(0.0, state.distanceMeters, 0.0)
    }

    @Test
    fun `a real outing measures the same whether or not it began indoors`() {
        // Two recordings of the same walk: one started outdoors, one started
        // in a hallway. The distance must not depend on where the athlete
        // happened to press start.
        RecordingRepository.start(startTime)
        RecordingRepository.offer(fix(0.0, 0, accuracy = 5f))
        repeat(10) { step ->
            RecordingRepository.offer(fix((step + 1) * 5.0, step + 1L, accuracy = 5f))
        }
        val outdoors = RecordingRepository.state.value.distanceMeters

        RecordingRepository.stop()
        RecordingRepository.start(startTime)
        RecordingRepository.offerApproximate(fix(0.0, 0, accuracy = 40f), now = startTime)
        RecordingRepository.offer(fix(0.0, 0, accuracy = 5f))
        repeat(10) { step ->
            RecordingRepository.offer(fix((step + 1) * 5.0, step + 1L, accuracy = 5f))
        }
        val indoors = RecordingRepository.state.value.distanceMeters

        assertEquals(outdoors, indoors, 0.001)
        assertTrue(outdoors > 45.0)
    }
}
