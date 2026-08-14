package app.yolaq.mobile.share

import kotlin.math.asinh
import kotlin.math.tan

/**
 * Web Mercator, shared by the map tiles and the card's own route drawing.
 *
 * One projection in one place because the two have to agree. The tiles are
 * Mercator by definition; the card used to fit the line with a plain
 * cosine-of-latitude squeeze, which is close but not the same — close enough
 * that the drawn shape changed subtly when the athlete switched between the
 * bare line and the map, which reads as the app being unsure what the route
 * looks like.
 */
internal object Mercator {

    /** Web Mercator cannot represent the poles. */
    private const val LATITUDE_LIMIT = 85.05112878

    /**
     * Longitude to a fraction of the world, 0 at the antimeridian.
     *
     * @param longitude Degrees.
     * @return 0 to 1, west to east.
     */
    fun x(longitude: Double): Double = (longitude + 180.0) / 360.0

    /**
     * Latitude to a fraction of the world, 0 at the top.
     *
     * @param latitude Degrees.
     * @return 0 to 1, north to south.
     */
    fun y(latitude: Double): Double {
        val clamped = latitude.coerceIn(-LATITUDE_LIMIT, LATITUDE_LIMIT)
        return (1.0 - asinh(tan(Math.toRadians(clamped))) / Math.PI) / 2.0
    }

    /**
     * How wide a route is against how tall, projected.
     *
     * This is what decides the shape of the frame the route is drawn in. A
     * square frame was the original mistake: a ride out along a valley and
     * back is four times wider than it is tall, and forcing it into a square
     * left a large map with a thin line across the middle of it — the map
     * dominating the route it was supposed to be showing.
     *
     * Clamped, because the degenerate cases are real: a straight
     * out-and-back's projected height is nearly zero, and a frame with the
     * true ratio would be a few pixels tall.
     *
     * @param route The track.
     * @return Width divided by height, between a third and three.
     */
    fun aspect(route: List<Pair<Double, Double>>): Float {
        if (route.size < 2) {
            return 1f
        }
        val spanX = (x(route.maxOf { it.second }) - x(route.minOf { it.second }))
            .coerceAtLeast(1e-9)
        // Latitude's projection is inverted, so the min and max swap.
        val spanY = (y(route.minOf { it.first }) - y(route.maxOf { it.first }))
            .coerceAtLeast(1e-9)
        return (spanX / spanY).toFloat().coerceIn(MIN_ASPECT, MAX_ASPECT)
    }

    /** The widest a route frame may be against its height. */
    private const val MAX_ASPECT = 3f

    /** The tallest, which is the same limit the other way up. */
    private const val MIN_ASPECT = 1f / 3f
}
