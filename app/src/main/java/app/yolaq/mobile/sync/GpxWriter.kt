package app.yolaq.mobile.sync

import app.yolaq.mobile.recording.SportType
import app.yolaq.mobile.recording.TrackPoint
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns a finished recording into GPX.
 *
 * GPX rather than the live endpoint's JSON: the server already parses GPX from
 * every other source, so an upload from here lands on the same code path as a
 * watch export and inherits its timezone, elevation and stream handling. It is
 * also a file the user can keep or open elsewhere if this app ever goes away.
 *
 * Deliberately free of Android APIs so the output can be asserted on in plain
 * JVM tests — an upload that is silently malformed is only noticed days later,
 * on a phone, after a real outing.
 */
object GpxWriter {

    /** Written into the file so a server-side question can be traced back here. */
    private const val CREATOR = "yolak-mobile"

    /** GPX carries UTC timestamps with a `Z` suffix and no sub-second part. */
    private val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    /**
     * Writes a track as a GPX document.
     *
     * @param points The accepted fixes, oldest first.
     * @param sport What the athlete was doing.
     * @param name Track name shown on the activity.
     * @param description The athlete's note, or empty for none. Carried in the
     *   track's `desc`, which the server reads into the activity's
     *   description — so a note typed on the phone arrives with the upload
     *   instead of needing a second request that can fail on its own.
     * @return The GPX document.
     */
    fun write(
        points: List<TrackPoint>,
        sport: SportType,
        name: String,
        description: String = "",
    ): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append(
            """<gpx version="1.1" creator="$CREATOR" """ +
                """xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """ +
                """xsi:schemaLocation="http://www.topografix.com/GPX/1/1 """ +
                """http://www.topografix.com/GPX/1/1/gpx.xsd">""",
        ).append('\n')

        points.firstOrNull()?.let {
            append("  <metadata><time>").append(TIMESTAMP.format(Instant.ofEpochMilli(it.recordedAt)))
                .append("</time></metadata>\n")
        }

        append("  <trk>\n")
        append("    <name>").append(escape(name)).append("</name>\n")
        // The server maps this to its own activity type; without it every
        // outing arrives as a generic workout.
        if (description.isNotBlank()) {
            append("    <desc>").append(escape(description)).append("</desc>\n")
        }
        append("    <type>").append(sport.gpxType).append("</type>\n")
        append("    <trkseg>\n")
        points.forEach { point -> appendPoint(point) }
        append("    </trkseg>\n")
        append("  </trk>\n")
        append("</gpx>\n")
    }

    /**
     * Appends one track point.
     *
     * @param point The fix to write.
     */
    private fun StringBuilder.appendPoint(point: TrackPoint) {
        // Seven decimals is ~1 cm — past the point where more digits mean
        // anything, and short enough to keep a long outing's file small.
        append("      <trkpt lat=\"").append(coordinate(point.latitude))
            .append("\" lon=\"").append(coordinate(point.longitude)).append("\">")
        point.elevation?.let { append("<ele>").append(number(it, 1)).append("</ele>") }
        append("<time>").append(TIMESTAMP.format(Instant.ofEpochMilli(point.recordedAt))).append("</time>")
        append("</trkpt>\n")
    }

    /**
     * Formats a coordinate.
     *
     * Locale-fixed: a phone set to Turkish formats decimals with a comma, and
     * `lat="41,0"` is not a number any parser will accept.
     *
     * @param value Degrees.
     * @return The formatted coordinate.
     */
    private fun coordinate(value: Double): String = number(value, 7)

    /**
     * Formats a number with a fixed number of decimals, in a fixed locale.
     *
     * @param value The value.
     * @param decimals How many decimal places.
     * @return The formatted number.
     */
    private fun number(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)

    /**
     * Escapes text for XML content.
     *
     * @param text The raw text.
     * @return Text safe to place between tags.
     */
    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
