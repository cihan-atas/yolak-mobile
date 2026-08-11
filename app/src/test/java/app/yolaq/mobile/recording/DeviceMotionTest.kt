package app.yolaq.mobile.recording

import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the accelerometer verdict and the distance gate it drives.
 *
 * This is the input that finally settles "did the athlete actually go
 * anywhere". Every other signal in the recorder comes from the GPS receiver,
 * and indoors that receiver reports a wandering position, a falling altitude
 * *and* a Doppler velocity of up to 2.3 m/s while the phone lies on a desk —
 * measured on a real handset, see plan/gps-drift-s23fe.csv. So the two things
 * worth pinning are: a resting phone must read as still, and a carried one
 * must never read as resting, because that would silently stop measuring a
 * real outing.
 */
class DeviceMotionTest {

    private val startTime = 1_700_000_000_000L

    /**
     * Feeds a stretch of readings into a window.
     *
     * @param window The window to fill.
     * @param seconds How long a stretch to simulate.
     * @param sample Produces the magnitude for a sample index.
     */
    private fun feed(window: MotionWindow, seconds: Int, sample: (Int) -> Double) {
        // Five readings a second, roughly what SENSOR_DELAY_NORMAL gives.
        repeat(seconds * 5) { index ->
            window.add(sample(index), startTime + index * 200L)
        }
    }

    @Test
    fun `a phone at rest reads as still`() {
        val window = MotionWindow()
        // Gravity plus the sensor's own noise, which is a few hundredths.
        feed(window, 5) { index -> 9.81 + if (index % 2 == 0) 0.03 else -0.03 }

        assertFalse(window.isMoving())
    }

    @Test
    fun `a phone being walked with reads as moving`() {
        val window = MotionWindow()
        // A pocket at walking cadence: roughly two steps a second, swinging a
        // couple of metres per second squared either side of gravity.
        feed(window, 5) { index -> 9.81 + 2.0 * sin(index * 0.8) }

        assertTrue(window.isMoving())
    }

    @Test
    fun `even a gentle carry clears the threshold`() {
        val window = MotionWindow()
        // Held in a hand, barely swinging — the quietest real carrying gets.
        feed(window, 5) { index -> 9.81 + 0.6 * sin(index * 0.5) }

        assertTrue(window.isMoving())
    }

    @Test
    fun `too few readings count as moving`() {
        val window = MotionWindow()
        // Right after the sensor is switched on there is no evidence either
        // way, and the safe answer is the one that keeps recording.
        window.add(9.81, startTime)
        window.add(9.81, startTime + 200)

        assertTrue(window.isMoving())
    }

    @Test
    fun `the window forgets, so putting the phone down is noticed`() {
        val window = MotionWindow()
        feed(window, 5) { index -> 9.81 + 2.0 * sin(index * 0.8) }
        assertTrue(window.isMoving())

        // Set down: the walking readings age out of the three-second window.
        repeat(30) { index -> window.add(9.81, startTime + 5_000 + index * 200L) }

        assertFalse(window.isMoving())
    }

    @Test
    fun `picking it up again is noticed just as fast`() {
        val window = MotionWindow()
        feed(window, 5) { 9.81 }
        assertFalse(window.isMoving())

        repeat(20) { index ->
            window.add(9.81 + 2.0 * sin(index * 0.8), startTime + 5_000 + index * 200L)
        }

        assertTrue(window.isMoving())
    }

    @Test
    fun `magnitude includes gravity and ignores orientation`() {
        // However the phone is held, a resting one reads about 9.81.
        assertEquals(9.81, MotionWindow.magnitude(0f, 0f, 9.81f), 0.001)
        assertEquals(9.81, MotionWindow.magnitude(9.81f, 0f, 0f), 0.001)
        assertEquals(9.81, MotionWindow.magnitude(5.66f, 5.66f, 5.66f), 0.02)
    }
}

/**
 * Tests that the verdict actually stops the distance.
 *
 * The window can be right and the recorder still wrong if nothing consults
 * it, which is what these cover: the real drift trace replayed with the
 * accelerometer saying "resting" must produce nothing at all.
 */
class MotionGatedRecordingTest {

    private val metreInDegrees = 1.0 / 111_320.0
    private val startLat = 41.0
    private val startTime = 1_700_000_000_000L

    @Before
    fun reset() {
        RecordingRepository.stop()
    }

    private fun fix(metresNorth: Double, secondsIn: Long, speed: Double?) = TrackPoint(
        latitude = startLat + metresNorth * metreInDegrees,
        longitude = 29.0,
        elevation = null,
        speed = speed,
        accuracy = 6f,
        recordedAt = startTime + secondsIn * 1000L,
    )

    @Test
    fun `a resting phone gains nothing however convincing the receiver is`() {
        RecordingRepository.start(startTime)
        RecordingRepository.offer(fix(0.0, 0, speed = 0.0))
        RecordingRepository.setDeviceMoving(false)

        // The measured failure: four-metre jumps, and a receiver claiming
        // 1–2 m/s to go with them. Every GPS-derived signal says "walking".
        repeat(60) { second ->
            val drift = if (second % 2 == 0) 4.0 else -4.0
            RecordingRepository.offer(fix(drift, second + 1L, speed = 1.5))
        }

        assertEquals(0.0, RecordingRepository.state.value.distanceMeters, 0.0)
        assertEquals(1, RecordingRepository.state.value.points.size)
    }

    @Test
    fun `a carried phone measures the walk it is on`() {
        RecordingRepository.start(startTime)
        RecordingRepository.offer(fix(0.0, 0, speed = 0.0))
        RecordingRepository.setDeviceMoving(true)

        // Forty seconds at 1.4 m/s.
        repeat(40) { second ->
            RecordingRepository.offer(fix(1.4 * (second + 1), second + 1L, speed = 1.4))
        }

        assertEquals(56.0, RecordingRepository.state.value.distanceMeters, 3.0)
    }

    @Test
    fun `the gate lifts the moment the athlete sets off again`() {
        RecordingRepository.start(startTime)
        RecordingRepository.offer(fix(0.0, 0, speed = 0.0))

        RecordingRepository.setDeviceMoving(false)
        repeat(20) { second -> RecordingRepository.offer(fix(4.0, second + 1L, speed = 1.5)) }
        assertEquals(0.0, RecordingRepository.state.value.distanceMeters, 0.0)

        RecordingRepository.setDeviceMoving(true)
        repeat(20) { second ->
            RecordingRepository.offer(fix(1.4 * (second + 1), 21L + second, speed = 1.4))
        }

        assertTrue(RecordingRepository.state.value.distanceMeters > 20.0)
    }
}
