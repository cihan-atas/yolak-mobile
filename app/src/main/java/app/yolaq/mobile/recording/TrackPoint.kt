package app.yolaq.mobile.recording

/**
 * One recorded GPS fix.
 *
 * Mirrors what the server's live-tracking endpoint accepts, so a point can go
 * straight onto the wire without a second model in between.
 *
 * @property latitude Degrees.
 * @property longitude Degrees.
 * @property elevation Metres above sea level, when the fix carried it.
 * @property speed Metres per second, when the fix carried it.
 * @property accuracy Horizontal accuracy in metres; used to reject bad fixes.
 * @property recordedAt Epoch milliseconds the fix was taken on the device.
 */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double?,
    val speed: Double?,
    val accuracy: Float,
    val recordedAt: Long,
)
