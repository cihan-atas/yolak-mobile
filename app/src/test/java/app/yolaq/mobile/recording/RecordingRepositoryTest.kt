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
     * Starts a recording that is already past the acquire gate.
     *
     * Most tests are about the filter, not the gate, so they need a track that
     * is anchored and running; the gate itself is covered separately.
     */
    private fun startRecording() {
        RecordingRepository.start(startTime)
        RecordingRepository.offer(fix(0.0, 0, accuracy = 5f, speed = 0.0))
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
        startRecording()

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
        startRecording()
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
        startRecording()
        // 500 m in one second while the receiver insists on a walking pace:
        // a re-acquisition, not travel.
        RecordingRepository.offer(fix(500.0, 1, speed = 1.3))

        assertEquals(0.0, RecordingRepository.state.value.distanceMeters, 0.001)
    }

    @Test
    fun `vague fixes are rejected and surfaced`() {
        startRecording()
        RecordingRepository.offer(fix(50.0, 1, accuracy = 80f))

        val state = RecordingRepository.state.value
        assertTrue("zayıf sinyal bildirilmedi", state.weakSignal)
        assertEquals(1, state.points.size)
        assertEquals(0.0, state.distanceMeters, 0.001)
    }

    @Test
    fun `paused recordings ignore fixes`() {
        startRecording()
        RecordingRepository.pause(startTime + 1_000)
        repeat(30) { second ->
            RecordingRepository.offer(fix(1.3 * (second + 1), second + 2L, speed = 1.3))
        }

        assertEquals(0.0, RecordingRepository.state.value.distanceMeters, 0.001)
    }

    @Test
    fun `the clock keeps running between fixes`() {
        startRecording()
        // No further fixes: a recording with a weak signal must still show time
        // passing rather than sitting frozen at zero.
        assertEquals(30_000L, RecordingRepository.state.value.elapsedMillis(startTime + 30_000))
    }

    @Test
    fun `paused time does not count`() {
        startRecording()
        RecordingRepository.pause(startTime + 10_000)
        assertEquals(10_000L, RecordingRepository.state.value.elapsedMillis(startTime + 60_000))

        RecordingRepository.resume(startTime + 60_000)
        assertEquals(20_000L, RecordingRepository.state.value.elapsedMillis(startTime + 70_000))
    }

    @Test
    fun `speed shown while stationary is zero`() {
        startRecording()
        RecordingRepository.offer(fix(2.0, 1, speed = 0.05))

        // Non-zero speed beside a distance that is not growing would have the
        // screen contradict itself.
        assertEquals(0.0, RecordingRepository.state.value.currentSpeed!!, 0.001)
        assertEquals(null, RecordingRepository.state.value.currentPaceSecondsPerKm)
    }

    @Test
    fun `recording does not begin until the fix is good enough`() {
        RecordingRepository.start(startTime)
        assertEquals(RecordingStatus.ACQUIRING, RecordingRepository.state.value.status)

        // Vague fixes keep the gate shut, but report what is being waited on.
        repeat(5) { second ->
            RecordingRepository.offer(fix(20.0 * second, second.toLong(), accuracy = 25f))
        }
        var state = RecordingRepository.state.value
        assertEquals(RecordingStatus.ACQUIRING, state.status)
        assertEquals(25f, state.lastAccuracy!!, 0.01f)
        assertEquals(0, state.points.size)

        // The clock must not run while hunting for satellites — standing at the
        // trailhead is not part of the outing.
        assertEquals(0L, state.elapsedMillis(startTime + 30_000))

        // A good fix opens the gate and anchors the track.
        RecordingRepository.offer(fix(0.0, 10, accuracy = 8f))
        state = RecordingRepository.state.value
        assertEquals(RecordingStatus.RECORDING, state.status)
        assertEquals(1, state.points.size)
        assertEquals(0.0, state.distanceMeters, 0.001)
    }

    @Test
    fun `the wait can be overridden once it drags on`() {
        RecordingRepository.start(startTime)
        val waiting = RecordingRepository.state.value
        assertTrue("hemen atlama teklif edilmemeli", !waiting.canOverrideAcquire(startTime + 5_000))
        assertTrue(
            "uzun beklemede atlama teklif edilmeli",
            waiting.canOverrideAcquire(startTime + ACQUIRE_OVERRIDE_AFTER_MS),
        )

        RecordingRepository.startAnyway(startTime + ACQUIRE_OVERRIDE_AFTER_MS)
        assertEquals(RecordingStatus.RECORDING, RecordingRepository.state.value.status)
    }

    @Test
    fun `overriding the wait still says the distance is not counting`() {
        RecordingRepository.start(startTime)
        RecordingRepository.startAnyway(startTime + ACQUIRE_OVERRIDE_AFTER_MS)

        // Cihazda bulundu: bu yoldan başlayan kayıt dakikalarca sıradan
        // görünüp hiçbir şey saymıyor ve hiçbir şey söylemiyordu; sonunda
        // "GPS noktası alınamadı" çıkıyordu. Bayrak ekranın nedeni açıkladığı
        // tek yer.
        assertTrue(
            "atlayarak başlayan kayıt uydu beklediğini söylemeli",
            RecordingRepository.state.value.awaitingSatellites,
        )
    }

    @Test
    fun `a recording with no points at all eventually warns instead of describing`() {
        RecordingRepository.start(startTime)
        RecordingRepository.startAnyway(startTime)
        val state = RecordingRepository.state.value

        assertTrue(
            "ilk saniyelerde beklemek normaldir, uyarı olmamalı",
            !state.strandedWithoutFix(startTime + 10_000),
        )
        assertTrue(
            "uzun süre tek nokta bile gelmediyse uyarmalı",
            state.strandedWithoutFix(startTime + NO_FIX_ESCALATE_AFTER_MS),
        )
    }

    @Test
    fun `a recording that has points never warns however long it runs`() {
        startRecording()

        // Çapası olan kayıt kaydediyor demektir; sporcu durakta beklerken
        // saatlerce yeni nokta gelmemesi hata değil.
        assertTrue(
            RecordingRepository.state.value.points.isNotEmpty(),
        )
        assertTrue(
            "çapası olan kayıt uyarı vermemeli",
            !RecordingRepository.state.value.strandedWithoutFix(startTime + 10 * NO_FIX_ESCALATE_AFTER_MS),
        )
    }

    @Test
    fun `a wild reported speed does not move the display`() {
        startRecording()
        repeat(5) { second ->
            RecordingRepository.offer(fix(3.0 * (second + 1), second + 1L, speed = 3.0))
        }
        val settled = RecordingRepository.state.value.currentSpeed!!

        // The receiver claims 12 m/s while its own coordinates advance the
        // usual three metres. The display is measured from the coordinates, so
        // it must not budge — the velocity field is not consulted at all.
        RecordingRepository.offer(fix(3.0 * 6, 6, speed = 12.0))
        val jolted = RecordingRepository.state.value.currentSpeed!!

        assertEquals("bildirilen hız ekranı sürükledi", settled, jolted, 0.05)
    }

    @Test
    fun `a steady walk reads as a steady pace`() {
        // The complaint this window exists for: one unbroken walk that showed
        // 15, then 37, then 40, then 23 minutes per kilometre. Doppler noise
        // of ±0.4 m/s around a 1.4 m/s walk, which is what a receiver really
        // reports, must not reach the display any more.
        startRecording()
        val noise = listOf(0.4, -0.35, 0.3, -0.4, 0.2, -0.3, 0.35, -0.25)
        repeat(40) { second ->
            RecordingRepository.offer(
                fix(
                    metresNorth = 1.4 * (second + 1),
                    secondsIn = second + 1L,
                    speed = 1.4 + noise[second % noise.size],
                ),
            )
        }

        // 1.4 m/s is 714 s/km — 11:54 per kilometre.
        val pace = RecordingRepository.state.value.currentPaceSecondsPerKm!!
        assertEquals(714.0, pace, 40.0)
    }

    @Test
    fun `a walk that stops reads as stopped rather than as a crawl`() {
        startRecording()
        repeat(20) { second ->
            RecordingRepository.offer(fix(1.4 * (second + 1), second + 1L, speed = 1.4))
        }
        assertTrue(RecordingRepository.state.value.currentSpeed!! > 1.0)

        // Standing still: fixes keep arriving from the same spot. The window
        // stretches over an unchanging distance and the speed falls away by
        // itself.
        repeat(25) { second ->
            RecordingRepository.offer(fix(1.4 * 20, 21L + second, speed = 0.0))
        }
        assertEquals(0.0, RecordingRepository.state.value.currentSpeed!!, 0.15)
    }

    @Test
    fun `stopping hands back the recording and clears the state`() {
        startRecording()
        repeat(30) { second ->
            RecordingRepository.offer(fix(1.3 * (second + 1), second + 1L, speed = 1.3))
        }

        val finished = RecordingRepository.stop(startTime + 31_000)

        // The finished recording has to come back, or the outing is lost.
        assertTrue("bitmiş kayıt boş döndü", finished.distanceMeters > 30.0)
        assertEquals(31_000L, finished.movingMillis)

        // And the live state has to be clear, or the screen keeps showing a
        // running clock and a "Bitir" button while nothing is being recorded.
        val state = RecordingRepository.state.value
        assertEquals(RecordingStatus.IDLE, state.status)
        assertEquals(0.0, state.distanceMeters, 0.001)
        assertEquals(0, state.points.size)
    }

    @Test
    fun `pace is reported while moving`() {
        startRecording()
        repeat(10) { second ->
            RecordingRepository.offer(fix(3.0 * (second + 1), second + 1L, speed = 3.0))
        }

        // 3 m/s is 1000/3 = 333 s/km, i.e. 5:33 per kilometre. The tolerance is
        // wide because the displayed speed is smoothed: it approaches the true
        // value over several fixes rather than landing on it, which is the
        // whole point of the filter.
        assertEquals(333.3, RecordingRepository.state.value.currentPaceSecondsPerKm!!, 8.0)
    }
}
