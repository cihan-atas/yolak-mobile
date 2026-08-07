package app.yolaq.mobile.sync

import app.yolaq.mobile.recording.SportType
import app.yolaq.mobile.recording.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for crash recovery.
 *
 * The recording lives in memory, so a killed process would take a whole outing
 * with it — an hour of someone's life that cannot be re-run. These tests cover
 * the cases that actually happen when a process dies: a journal cut off
 * mid-line, and one that never got past its header.
 */
class TrackJournalTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun journalFile(): File = File(folder.root, "current-track.csv")

    private fun point(offsetSeconds: Long) = TrackPoint(
        latitude = 41.0 + offsetSeconds * 0.0001,
        longitude = 29.0,
        elevation = 100.0,
        speed = 1.5,
        accuracy = 6f,
        recordedAt = 1_754_500_000_000L + offsetSeconds * 1000,
    )

    @Test
    fun `recovers the points and the sport`() {
        val journal = TrackJournal(journalFile())
        journal.begin(SportType.CYCLING)
        journal.append(point(0))
        journal.append(point(1))

        val recovered = TrackJournal(journalFile()).read()

        assertNotNull(recovered)
        assertEquals(SportType.CYCLING, recovered!!.sport)
        assertEquals(2, recovered.points.size)
        assertEquals(point(1).recordedAt, recovered.points[1].recordedAt)
        assertEquals(41.0, recovered.points[0].latitude, 1e-9)
    }

    /**
     * A process killed mid-write leaves a truncated final line. Losing that
     * one fix is fine; losing the outing because of it is not.
     */
    @Test
    fun `keeps everything before a half-written last line`() {
        val file = journalFile()
        val journal = TrackJournal(file)
        journal.begin(SportType.RUNNING)
        journal.append(point(0))
        journal.append(point(1))
        file.appendText("41.0002;29.0")

        val recovered = TrackJournal(file).read()

        assertEquals(2, recovered!!.points.size)
    }

    @Test
    fun `a header with no fixes is nothing to recover`() {
        val journal = TrackJournal(journalFile())
        journal.begin(SportType.WALKING)

        assertNull(TrackJournal(journalFile()).read())
    }

    @Test
    fun `an absent journal is nothing to recover`() {
        assertNull(TrackJournal(journalFile()).read())
    }

    @Test
    fun `a journal written by another format version is ignored`() {
        val file = journalFile()
        file.writeText("v0;RUNNING\n41.0;29.0;;;5.0;1754500000000\n")

        assertNull(TrackJournal(file).read())
    }

    @Test
    fun `starting a recording discards the previous journal`() {
        val file = journalFile()
        val journal = TrackJournal(file)
        journal.begin(SportType.RUNNING)
        journal.append(point(0))
        journal.append(point(1))

        journal.begin(SportType.WALKING)
        journal.append(point(5))

        val recovered = TrackJournal(file).read()

        assertEquals(SportType.WALKING, recovered!!.sport)
        assertEquals(1, recovered.points.size)
    }

    @Test
    fun `a point without elevation or speed survives the round trip`() {
        val file = journalFile()
        val journal = TrackJournal(file)
        journal.begin(SportType.WALKING)
        journal.append(point(0).copy(elevation = null, speed = null))

        val recovered = TrackJournal(file).read()!!.points.single()

        assertNull(recovered.elevation)
        assertNull(recovered.speed)
        assertEquals(41.0, recovered.latitude, 1e-9)
    }
}
