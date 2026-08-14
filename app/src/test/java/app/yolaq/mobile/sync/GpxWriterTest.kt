package app.yolaq.mobile.sync

import app.yolaq.mobile.recording.SportType
import app.yolaq.mobile.recording.TrackPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Tests for the GPX the server is asked to parse.
 *
 * A malformed upload is the kind of failure that only shows up days later, on
 * a phone, after a real outing — the file is written in the background and
 * refused somewhere else entirely. Asserting the shape here is much cheaper.
 */
class GpxWriterTest {

    private val startTime = 1_754_500_000_000L

    private fun point(latitude: Double, longitude: Double, offsetSeconds: Long) = TrackPoint(
        latitude = latitude,
        longitude = longitude,
        elevation = 120.5,
        speed = 1.4,
        accuracy = 5f,
        recordedAt = startTime + offsetSeconds * 1000,
    )

    private val track = listOf(point(41.0, 29.0, 0), point(41.0001, 29.0001, 10))

    @Test
    fun `produces well-formed xml`() {
        val gpx = GpxWriter.write(track, SportType.RUNNING, "Koşu")

        // Parsed rather than string-matched: the point is that a parser
        // accepts it, which is what the server will do.
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(gpx.byteInputStream())

        assertTrue(document.documentElement.tagName == "gpx")
        assertTrue(document.getElementsByTagName("trkpt").length == 2)
    }

    @Test
    fun `carries the sport the server maps to an activity type`() {
        val gpx = GpxWriter.write(track, SportType.CYCLING, "Tur")

        assertTrue(gpx.contains("<type>cycling</type>"))
    }

    /**
     * The bug this guards against is invisible in an English locale: on a
     * Turkish phone the default number formatting writes `41,0`, which no GPX
     * parser accepts, and every upload from a Turkish device would be refused.
     */
    @Test
    fun `formats coordinates with a dot on a comma-decimal locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            val gpx = GpxWriter.write(track, SportType.WALKING, "Yürüyüş")

            assertTrue(gpx.contains("""lat="41.0000000""""))
            assertFalse(gpx.contains(","))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `escapes a name that would otherwise break the document`() {
        val gpx = GpxWriter.write(track, SportType.WALKING, "Yürüyüş & <tur>")

        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(gpx.byteInputStream())
        assertTrue(gpx.contains("&amp;"))
    }

    @Test
    fun `timestamps are utc so the server can place the outing in time`() {
        val gpx = GpxWriter.write(track, SportType.RUNNING, "Koşu")

        assertTrue(gpx.contains("<time>2025-08-06T"))
        assertTrue(gpx.contains("Z</time>"))
    }

    /**
     * The note typed on the save sheet has to reach the server with the
     * upload. It travels in the track's `desc`, which is what the importer
     * reads into the activity's description — the alternative was a second
     * request that could fail on its own and leave the athlete's words
     * nowhere.
     */
    @Test
    fun `carries the athlete's note as the track description`() {
        val gpx = GpxWriter.write(track, SportType.RUNNING, "Koşu", "Rüzgâra karşı")

        assertTrue(gpx.contains("<desc>Rüzgâra karşı</desc>"))
        // Order matters to the schema: name, then desc, then type.
        assertTrue(gpx.indexOf("<name>") < gpx.indexOf("<desc>"))
        assertTrue(gpx.indexOf("<desc>") < gpx.indexOf("<type>"))
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(gpx.byteInputStream())
    }

    /** An empty note is no note, not an empty element for the server to store. */
    @Test
    fun `writes no description when there is nothing to say`() {
        val gpx = GpxWriter.write(track, SportType.RUNNING, "Koşu", "   ")

        assertFalse(gpx.contains("<desc>"))
    }

    /** A note is free text, so it has to survive the athlete typing markup. */
    @Test
    fun `escapes the note`() {
        val gpx = GpxWriter.write(track, SportType.RUNNING, "Koşu", "5 < 6 & bitti")

        assertTrue(gpx.contains("<desc>5 &lt; 6 &amp; bitti</desc>"))
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(gpx.byteInputStream())
    }
}