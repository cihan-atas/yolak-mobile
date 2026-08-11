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
 * How long a recording may go without a single accepted point before the
 * screen stops describing and starts warning.
 *
 * Long enough that stepping out of a doorway or waiting out a cold start does
 * not raise an alarm; short enough that the athlete learns the outing is not
 * being recorded while it is still a two-minute walk to fix it, rather than
 * after an hour.
 */
const val NO_FIX_ESCALATE_AFTER_MS = 90_000L

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
 * @property approximatePosition Roughly where the phone is, from Wi-Fi and
 *   cell towers rather than satellites. Never part of the track — it is far
 *   too vague to measure a distance with — but it is what lets the map show
 *   somewhere indoors instead of an empty grey square.
 * @property awaitingSatellites Whether the recording has begun on nothing but
 *   an approximate position. The clock runs; the distance does not, and cannot
 *   until a real fix arrives. Shown on screen, because a distance stuck at
 *   zero with no explanation looks exactly like a broken recorder.
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
    val approximatePosition: TrackPoint? = null,
    val awaitingSatellites: Boolean = false,
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

    /**
     * Whether the recording has been running this long without a single
     * accepted point.
     *
     * Waiting for satellites is a normal opening state, and the quiet banner
     * says so. Past a certain point it stops being a state and becomes a
     * verdict: nothing has been recorded, nothing will be unless something
     * changes, and finishing here loses the outing. The screen needs to say
     * that while there is still time to walk outside — not at the end, which
     * is where it used to say it.
     *
     * Measured from the start of the recording rather than from the last fix,
     * because indoors there is often no last fix at all: the satellite
     * receiver produces nothing, so nothing arrives to update the state and a
     * timer driven by fixes would never fire.
     *
     * @param now Epoch millis to measure against.
     * @return True when the recording is stranded without a track.
     */
    fun strandedWithoutFix(now: Long = System.currentTimeMillis()): Boolean {
        if (status != RecordingStatus.RECORDING || points.isNotEmpty()) {
            return false
        }
        val since = stretchStartedAt ?: return false
        return now - since >= NO_FIX_ESCALATE_AFTER_MS
    }
}
