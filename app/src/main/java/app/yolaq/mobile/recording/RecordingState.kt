package app.yolaq.mobile.recording

/** How long to wait for a good fix before offering to start anyway. */
const val ACQUIRE_OVERRIDE_AFTER_MS = 30_000L

/** Where a recording sits in its lifecycle. */
enum class RecordingStatus {
    /** Nothing is being recorded. */
    IDLE,

    /**
     * Waiting for a fix good enough to start from.
     *
     * The clock is not running and nothing is being recorded yet. Starting on a
     * vague fix is what forces a recorder to filter aggressively afterwards,
     * and filtering aggressively is how real movement gets thrown away — so the
     * signal is fixed at the source instead. RunnerUp does the same thing, and
     * its satellite-counting wait screen is this state.
     */
    ACQUIRING,

    /** Fixes are being collected. */
    RECORDING,

    /** Recording is held: fixes are ignored and the clock is stopped. */
    PAUSED,
}

/**
 * Everything the UI needs to draw the recording screen.
 *
 * Kept as one immutable snapshot so the screen renders from a single value and
 * cannot show a distance from one moment beside a duration from another.
 *
 * @property status Where the recording sits.
 * @property points Fixes accepted so far, oldest first.
 * @property distanceMeters Distance accumulated, excluding rejected jitter.
 * @property movingMillis Time spent recording, excluding paused stretches.
 * @property lastFixAt Epoch millis of the most recent accepted fix, or null.
 * @property waitingForFix Whether recording started but no fix has landed yet.
 * @property lastAccuracy Accuracy of the most recent fix the platform gave us,
 *   accepted or not. Present so a rejected fix is visible rather than silent.
 * @property stretchStartedAt Epoch millis the current recording stretch began,
 *   or null while paused/idle. The screen adds the time since this to
 *   [movingMillis] so the clock ticks live instead of only moving when the
 *   recording is paused or stopped.
 * @property lastSpeed Smoothed speed in m/s, updated from every fix — accepted
 *   or not — and pulled towards zero once the athlete is judged stationary.
 *   Reading it off the last *accepted* point instead would freeze the display
 *   at whatever the anchor fix happened to say. Smoothed because a raw
 *   per-second GPS speed jitters too much to read while moving.
 * @property acquiringSince Epoch millis the wait for a usable fix began, or
 *   null when not waiting. Lets the screen offer to start anyway once the wait
 *   has gone on long enough to look broken.
 * @property weakSignal Whether the last fix was dropped for being too vague.
 *   Without this the screen says "waiting for GPS" while GPS is in fact
 *   arriving, which sends the user hunting for the wrong problem.
 */
data class RecordingState(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val points: List<TrackPoint> = emptyList(),
    val distanceMeters: Double = 0.0,
    val movingMillis: Long = 0L,
    val lastFixAt: Long? = null,
    val waitingForFix: Boolean = false,
    val lastAccuracy: Float? = null,
    val weakSignal: Boolean = false,
    val stretchStartedAt: Long? = null,
    val lastSpeed: Double? = null,
    val acquiringSince: Long? = null,
) {
    /**
     * Whether the wait for a usable fix has gone on long enough to offer
     * starting anyway.
     *
     * Indoors the accuracy may never reach the threshold, and a wait screen
     * with no way past it is worse than a slightly vague recording — the user
     * is standing at the trailhead, not debugging a receiver.
     *
     * @param now Epoch millis to measure against.
     * @return True once the override should be offered.
     */
    fun canOverrideAcquire(now: Long = System.currentTimeMillis()): Boolean =
        acquiringSince?.let { now - it >= ACQUIRE_OVERRIDE_AFTER_MS } ?: false

    /**
     * Moving time including the stretch in progress.
     *
     * @param now Epoch millis to measure against.
     * @return Milliseconds of moving time.
     */
    fun elapsedMillis(now: Long = System.currentTimeMillis()): Long =
        movingMillis + (stretchStartedAt?.let { now - it } ?: 0L)

    /** Current speed in m/s, or null when the receiver has not reported one. */
    val currentSpeed: Double?
        get() = lastSpeed

    /**
     * Current pace in seconds per kilometre, or null while stationary.
     *
     * Runners read pace, not speed; dividing by a speed at or near zero would
     * give a meaningless (or infinite) number, so it stays null until there is
     * real movement to describe.
     */
    val currentPaceSecondsPerKm: Double?
        get() = lastSpeed?.takeIf { it > 0.1 }?.let { 1000.0 / it }

    /** Average speed in m/s over the moving time, or null before it means anything. */
    val averageSpeed: Double?
        get() = if (movingMillis > 0) distanceMeters / (movingMillis / 1000.0) else null
}
