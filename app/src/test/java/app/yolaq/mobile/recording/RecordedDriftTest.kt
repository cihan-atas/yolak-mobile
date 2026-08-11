package app.yolaq.mobile.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Replays a drift trace recorded on real hardware.
 *
 * Not synthesised. These are the points a Galaxy S23 FE's receiver produced
 * while the phone lay on a desk indoors, taken straight from the recorder's
 * own journal on 10 August 2026. Under the filter of the day they were all
 * *accepted*, and the recording reported 68.5 m of travel in 2.1 minutes with
 * a net displacement of 20.8 m.
 *
 * The trace is kept because the numbers in it are the ones that defeated two
 * earlier attempts at this filter: a position wandering by four metres a
 * fix, an altitude falling sixty metres, and — the part that made every
 * GPS-only defence useless — a reported speed of up to 2.35 m/s. Anything
 * that only reasons about the receiver's output can be talked into believing
 * this was a walk.
 */
class RecordedDriftTest {

    private lateinit var points: List<TrackPoint>

    @Before
    fun load() {
        RecordingRepository.stop()
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("gps-drift-s23fe.csv"))
            .bufferedReader()
            .readText()
        points = text.lineSequence()
            .filter { it.contains(';') && !it.startsWith("v1") }
            .map { line ->
                val parts = line.split(';')
                TrackPoint(
                    latitude = parts[0].toDouble(),
                    longitude = parts[1].toDouble(),
                    elevation = parts[2].toDoubleOrNull(),
                    speed = parts[3].toDoubleOrNull(),
                    accuracy = parts[4].toFloat(),
                    recordedAt = parts[5].toLong(),
                )
            }
            .toList()
    }

    @Test
    fun `the trace is the one that was recorded`() {
        // Guards the fixture itself: if it is ever replaced by something
        // tamer, the tests below would pass for the wrong reason.
        assertTrue("izde yeterli nokta yok", points.size >= 15)
        assertTrue("izde sahte hız yok", points.any { (it.speed ?: 0.0) > 2.0 })
        assertTrue("izde geniş hassasiyet yok", points.any { it.accuracy >= 9f })
    }

    @Test
    fun `a resting phone records none of it`() {
        RecordingRepository.start(points.first().recordedAt)
        RecordingRepository.offer(points.first())
        RecordingRepository.setDeviceMoving(false)

        points.drop(1).forEach { RecordingRepository.offer(it) }

        assertEquals(0.0, RecordingRepository.state.value.distanceMeters, 0.0)
        assertEquals(1, RecordingRepository.state.value.points.size)
    }

    @Test
    fun `the displayed pace stays at a standstill too`() {
        // The pace window reads the accepted track, so a gate that stops the
        // distance must stop the pace with it — otherwise the screen reports
        // a pace for a walk that is not happening.
        RecordingRepository.start(points.first().recordedAt)
        RecordingRepository.offer(points.first())
        RecordingRepository.setDeviceMoving(false)

        points.drop(1).forEach { RecordingRepository.offer(it) }

        assertEquals(0.0, RecordingRepository.state.value.currentSpeed ?: 0.0, 0.01)
        assertEquals(null, RecordingRepository.state.value.currentPaceSecondsPerKm)
    }

    @Test
    fun `without the accelerometer the old failure is still there`() {
        // Documents *why* the gate had to exist rather than another threshold:
        // with the GPS-only filter these points still add up to tens of
        // metres, because every one of them clears both noise floors and the
        // receiver's own velocity vouches for them.
        RecordingRepository.start(points.first().recordedAt)
        RecordingRepository.offer(points.first())
        RecordingRepository.setDeviceMoving(true)

        points.drop(1).forEach { RecordingRepository.offer(it) }

        assertTrue(
            "GPS-only filtre bu izi zaten eliyorsa gate'in gerekçesi değişmiştir",
            RecordingRepository.state.value.distanceMeters > 20.0,
        )
    }
}
