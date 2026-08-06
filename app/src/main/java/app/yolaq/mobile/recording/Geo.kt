package app.yolaq.mobile.recording

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Mean Earth radius in metres (IUGG), matching the server's own constant. */
private const val EARTH_RADIUS_M = 6_371_008.8

/**
 * Great-circle distance between two fixes.
 *
 * @param from The earlier fix.
 * @param to The later fix.
 * @return Distance in metres.
 */
fun distanceBetween(from: TrackPoint, to: TrackPoint): Double {
    val phi1 = Math.toRadians(from.latitude)
    val phi2 = Math.toRadians(to.latitude)
    val deltaPhi = Math.toRadians(to.latitude - from.latitude)
    val deltaLambda = Math.toRadians(to.longitude - from.longitude)

    val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
        cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)

    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
}
