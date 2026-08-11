package app.yolaq.mobile.recording

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How long a stretch of accelerometer readings is judged over.
 *
 * Long enough to survive the still moment at the top of a step and the pause
 * at a kerb, short enough that the recorder notices a stop before it has
 * collected much drift.
 */
private const val MOTION_WINDOW_MS = 3_000L

/**
 * Readings below this many samples are not a window, they are a glimpse.
 *
 * At the sampling rate the recorder asks for this is well under a second; it
 * only matters in the moment after the sensor is switched on.
 */
private const val MIN_MOTION_SAMPLES = 8

/**
 * The spread in acceleration, in m/s², above which the device is moving.
 *
 * A phone lying on a table reads a steady 9.81 m/s² with a standard deviation
 * under 0.1 — the sensor's own noise. Anything a person is carrying is an
 * order of magnitude noisier than that: a phone in a pocket at walking pace
 * swings by whole metres per second squared, and a phone strapped to a bike
 * picks up the road surface. A quarter of a metre per second squared sits in
 * the empty space between those two worlds, far enough above the noise floor
 * that a still phone never crosses it and far enough below real carrying that
 * a gentle walk always does.
 */
private const val MOVING_STDDEV_THRESHOLD = 0.25

/**
 * Decides whether the phone is being carried, from acceleration alone.
 *
 * This is the signal the recorder was missing. Every earlier attempt to stop a
 * stationary phone from collecting distance was an attempt to out-argue the
 * GPS receiver using the GPS receiver's own numbers, and it cannot be won:
 * indoors this hardware reports a position that wanders tens of metres, an
 * altitude that falls sixty, *and* a Doppler velocity of up to 2.3 m/s — while
 * sitting on a desk. Every one of those inputs says "moving".
 *
 * The accelerometer does not. It is a completely independent instrument, and
 * the question it answers — "is this object being carried" — is the one that
 * actually decides whether the athlete is going anywhere. It is what lets
 * Strava and every watch stop the distance when you stand still, and it needs
 * no permission and no Google dependency to read.
 *
 * Deliberately free of Android types so the judgement can be tested against
 * recorded numbers rather than by carrying a handset around.
 */
class MotionWindow(
    private val windowMs: Long = MOTION_WINDOW_MS,
    private val threshold: Double = MOVING_STDDEV_THRESHOLD,
) {

    private val magnitudes = ArrayDeque<Pair<Long, Double>>()

    /**
     * Records one acceleration reading.
     *
     * @param magnitude Length of the acceleration vector, in m/s². Gravity is
     *   included and does not need removing: it is a constant, and a constant
     *   moves the mean without touching the spread.
     * @param atMs When it was taken.
     */
    fun add(magnitude: Double, atMs: Long) {
        magnitudes.addLast(atMs to magnitude)
        while (magnitudes.isNotEmpty() && atMs - magnitudes.first().first > windowMs) {
            magnitudes.removeFirst()
        }
    }

    /**
     * Whether the readings so far look like a carried phone.
     *
     * Unknown counts as moving. A phone with no accelerometer, or a sensor
     * that has not reported yet, must not be able to silently stop a real
     * recording from measuring anything — the failure this guards against is
     * an inflated distance, and the failure it must never cause is a lost
     * outing.
     *
     * @return True when the device is moving, or when there is not yet enough
     *   evidence to say it is not.
     */
    fun isMoving(): Boolean {
        if (magnitudes.size < MIN_MOTION_SAMPLES) {
            return true
        }
        val mean = magnitudes.sumOf { it.second } / magnitudes.size
        val variance = magnitudes.sumOf { (it.second - mean) * (it.second - mean) } / magnitudes.size
        return sqrt(variance) >= threshold
    }

    /** Forgets everything, for the start of a new recording. */
    fun reset() {
        magnitudes.clear()
    }

    companion object {
        /**
         * The length of an acceleration vector.
         *
         * @param x Acceleration along x, m/s².
         * @param y Acceleration along y, m/s².
         * @param z Acceleration along z, m/s².
         * @return The magnitude, gravity included.
         */
        fun magnitude(x: Float, y: Float, z: Float): Double =
            sqrt((x.toDouble() * x + y.toDouble() * y + z.toDouble() * z))

        /**
         * Whether two magnitudes differ enough to be worth storing.
         *
         * Not used by the window itself; offered for callers that want to
         * thin a very fast sensor stream without changing the verdict.
         *
         * @param a One magnitude.
         * @param b The other.
         * @return True when they differ measurably.
         */
        fun differs(a: Double, b: Double): Boolean = abs(a - b) > 0.001
    }
}
