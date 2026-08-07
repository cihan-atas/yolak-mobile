package app.yolaq.mobile.live

import app.yolaq.mobile.recording.TrackPoint
import java.time.Instant
import java.util.Locale

/**
 * Builds the body the live-tracking endpoint expects.
 *
 * The server accepts a batch under a `locations` key — the shape it already
 * takes from trackers that buffer while offline — so a flush after a tunnel
 * arrives as one request rather than a burst of them.
 *
 * Hand-built rather than serialised: the body is a fixed shape of numbers, and
 * writing it here keeps the payload assertable from a plain JVM test.
 *
 * @param points The fixes to report, oldest first.
 * @return A JSON object body.
 */
fun livePayload(points: List<TrackPoint>): String = buildString {
    append("""{"locations":[""")
    points.forEachIndexed { index, point ->
        if (index > 0) {
            append(',')
        }
        append(pointObject(point))
    }
    append("]}")
}

/**
 * Renders one fix.
 *
 * Field names match the server's own (`lat`, `lon`, `recorded_at`,
 * `elevation`, `speed`) rather than a tracker dialect: the aliases exist for
 * apps that cannot be changed, and speed in particular is read as km/h when it
 * arrives under OwnTracks' `vel`, which would inflate the live pace by 3.6.
 *
 * @param point The fix.
 * @return A JSON object.
 */
private fun pointObject(point: TrackPoint): String = buildString {
    append("""{"lat":""").append(number(point.latitude))
    append(""","lon":""").append(number(point.longitude))
    // ISO-8601 UTC: unambiguous, and the fix's own time rather than arrival
    // time, so a buffered flush does not stack an hour of track onto one minute.
    append(""","recorded_at":"""").append(Instant.ofEpochMilli(point.recordedAt).toString()).append('"')
    point.elevation?.let { append(""","elevation":""").append(number(it)) }
    point.speed?.let { append(""","speed":""").append(number(it)) }
    append('}')
}

/**
 * Formats a number for JSON.
 *
 * Locale-fixed: on a Turkish phone the default formatting produces `41,0`,
 * which is not valid JSON and would have the server drop every fix.
 *
 * @param value The value.
 * @return The formatted number.
 */
private fun number(value: Double): String = String.format(Locale.US, "%.6f", value)
