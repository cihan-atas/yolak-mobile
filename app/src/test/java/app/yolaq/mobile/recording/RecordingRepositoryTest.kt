package app.yolaq.mobile.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the jitter filter, which has to get two opposite things right at
 * once: a phone lying on a table must not gain distance, and a phone actually
 * being walked must not lose it. Tuning for one at the expense of the other is
 * the failure mode these tests exist to catch — the filter has been wrong in
 * both directions, and each time it took a multi-minute outing to notice.
 *
 * Fixes are synthesised rather than recorded, so a whole outing is checked in
 * milliseconds and the awkward cases (a receiver reporting 0.0 m/s because it
 * has no velocity solution) can be reproduced on demand.
 */
class RecordingRepositoryTest {

    /** Roughly one metre of latitude, for building fixes at a known spacing. */
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
     * @param speed Reported speed in m/s, or null for "not reported".
     * @return The synthesised fix.
     */
    private fun fix(
        metresNorth: Double,
        secondsIn: Long,
        accuracy: Float = 5f,
        speed: Double? = null,
    ) = TrackPoint(
        latitude = startLat + metresNorth * metreInDegrees,
        longitude = startLon,
        elevation = null,
        speed = speed,
        accuracy = accuracy,
        recordedAt = startTime + secondsIn * 1000L,
    )

    @Test
    fun `stationary phone gains no distance`() {
        RecordingRepository.start(startTime)
        RecordingRepository.offer(fix(0.0, 0, speed = 0.0))

        // Two metres of drift each way, once a second, for two minutes — the
        // shape of a phone sitting still with a good fix.
        repeat(120) { second ->
            val drift = if (second % 2 == 0) 2.0 else -2.0
            RecordingRepository.offer(fix(drift, second + 1L, speed = 0.05))
        }

        assertEquals(0.0, RecordingRepository.state.value.distanceMeters, 0.001)
        assertEquals(1, RecordingRepository.state.value.points.size)
    }

    @Test
    fun `walking accumulates distance even when the receiver reports no speed`() {
        RecordingRepository.start(startTime)
        // 0.0 m/s from a receiver with no velocity solution: the field is
        // present, so it cannot be told apart from "genuinely stopped" by
        // looking at the value alone. Position has to win.
        RecordingRepository.offer(fix(0.0, 0, speed = 0.0))

        // A slow walk: 1.3 m/s, one fix a second, for two minutes.
        repeat(120) { second ->
            RecordingRepository.offer(fix(1.3 * (second + 1), second + 1L, speed = 0.0))
        }

        val distance = RecordingRepository.state.value.distanceMeters
        // 156 m walked. Allow a generous margin: steps below the noise floor
        // are held rather than dropped, so the total lags by at most one step.
        assertTrue("156 m yürüdü ama $distance m sayıldı", distance > 145.0)
        assertTrue("156 m yürüdü ama $distance m sayıldı", distance < 160.0)
    }

    @Test
    fun `walking accumulates distance when the receiver reports a real speed`() {
        RecordingRepository.start(startTime)
        RecordingRepository.offer(fix(0.0, 0, speed = 1.3))
        repeat(60) { second ->
            RecordingRepository.offer(fix(1.3 * (second + 1), second + 1L, speed = 1.3))
        }

        val distance = RecordingRepository.state.value.distanceMeters
        assertTrue("78 m yürüdü ama $distance m sayıldı", distance > 70.0)
    }

    @Test
    fun `running is not mistaken for a position jump`() {
        RecordingRepository.start(startTime)
        // 4 m/s with no reported speed — the jump filter must not cap this at
        // its allowance floor and reject the whole run.
        RecordingRepository.offer(fix(0.0, 0, speed = 0.0))
        repeat(60) { second ->
            RecordingRepository.offer(fix(4.0 * (second + 1), second + 1L, speed = 0.0))
        }

        val distance = RecordingRepository.state.value.distanceMeters
        assertTrue("240 m koştu ama $distance m sayıldı", distance > 230.0)
    }

    @Test
    fun `a teleport is rejected`() {
        RecordingRepository.start(startTime)
        RecordingRepository.offer(fix(0.0, 0, speed = 1.3))
        // 500 m in one second while the receiver insists on a walking pace:
        // a re-acquisition, not travel.
        RecordingRepository.offer(fix(500.0, 1, speed = 1.3))

        assertEquals(0.0, RecordingRepository.state.value.distanceMeters, 0.001)
    }

    @Test
    fun `vague fixes are rejected and surfaced`() {
        RecordingRepository.start(startTime)
        RecordingRepository.offer(fix(0.0, 0, accuracy = 80f))

        val state = RecordingRepository.state.value
        assertTrue("zayıf sinyal bildirilmedi", state.weakSignal)
        assertEquals(0, state.points.size)
    }

    @Test
    fun `paused recordings ignore fixes`() {
        RecordingRepository.start(startTime)
        RecordingRepository.offer(fix(0.0, 0, speed = 1.3))
        RecordingRepository.pause(startTime + 1_000)
        repeat(30) { second ->
            RecordingRepository.offer(fix(1.3 * (second + 1), second + 2L, speed = 1.3))
        }

        assertEquals(0.0, RecordingRepository.state.value.distanceMeters, 0.001)
    }

    @Test
    fun `the clock keeps running between fixes`() {
        RecordingRepository.start(startTime)
        // No fixes at all: a recording with a weak signal must still show time
        // passing rather than sitting frozen at zero.
        assertEquals(30_000L, RecordingRepository.state.value.elapsedMillis(startTime + 30_000))
    }

    @Test
    fun `paused time does not count`() {
        RecordingRepository.start(startTime)
        RecordingRepository.pause(startTime + 10_000)
        assertEquals(10_000L, RecordingRepository.state.value.elapsedMillis(startTime + 60_000))

        RecordingRepository.resume(startTime + 60_000)
        assertEquals(20_000L, RecordingRepository.state.value.elapsedMillis(startTime + 70_000))
    }

    @Test
    fun `speed shown while stationary is zero`() {
        RecordingRepository.start(startTime)
        RecordingRepository.offer(fix(0.0, 0, speed = 0.0))
        RecordingRepository.offer(fix(2.0, 1, speed = 0.05))

        // Non-zero speed beside a distance that is not growing would have the
        // screen contradict itself.
        assertEquals(0.0, RecordingRepository.state.value.currentSpeed!!, 0.001)
        assertEquals(null, RecordingRepository.state.value.currentPaceSecondsPerKm)
    }

    @Test
    fun `pace is reported while moving`() {
        RecordingRepository.start(startTime)
        RecordingRepository.offer(fix(0.0, 0, speed = 3.0))
        repeat(10) { second ->
            RecordingRepository.offer(fix(3.0 * (second + 1), second + 1L, speed = 3.0))
        }

        // 3 m/s is 1000/3 = 333 s/km, i.e. 5:33 per kilometre.
        assertEquals(333.3, RecordingRepository.state.value.currentPaceSecondsPerKm!!, 1.0)
    }
}
