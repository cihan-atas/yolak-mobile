package app.yolaq.mobile.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fixes worse than this are thrown away. Consumer GPS reports accuracy
 * honestly; a 50 m fix under a building would otherwise teleport the track and
 * inflate the distance by more than the athlete actually moved.
 */
private const val MAX_ACCURACY_M = 30f

/**
 * Movement below this is treated as jitter, not travel. Matches the server's
 * own rule so a track measured here and the same track measured after upload
 * agree with each other.
 */
private const val MIN_MOVEMENT_M = 3.0

/**
 * A step must also clear this fraction of the fix's own accuracy. A ±10 m fix
 * simply cannot resolve a 4 m move, so counting one is counting noise. This is
 * what stops a phone sitting on a table from slowly gaining distance: with a
 * fixed threshold alone, per-second drift of 3-5 m accumulates into hundreds of
 * metres over an hour.
 */
private const val MIN_MOVEMENT_ACCURACY_RATIO = 0.5

/**
 * Below this the receiver is saying the athlete is standing still. GPS speed
 * comes from Doppler shift rather than differencing positions, which makes it
 * far steadier than the coordinates themselves. 0.5 m/s is well under a slow
 * walk (~1.2 m/s).
 *
 * It is only ever a tiebreaker — see [DOPPLER_VETO_MAX_STEP_M].
 */
private const val MIN_MOVING_SPEED_MPS = 0.5

/**
 * The largest step a reported-stationary speed is allowed to veto.
 *
 * A receiver with no velocity solution — indoors, under cover, right after a
 * cold start — reports 0.0 m/s rather than "unknown", and `hasSpeed()` still
 * returns true. Treating that as proof of standing still rejects *every* fix
 * no matter how far the athlete actually walks, which is a far worse failure
 * than the drift it was meant to stop: the recording silently stays at 0.00 km
 * for the whole outing.
 *
 * So Doppler only settles the ambiguous cases. Past this displacement the
 * positions have moved further than any plausible amount of noise, and they
 * win regardless of what the speed field claims.
 */
private const val DOPPLER_VETO_MAX_STEP_M = 12.0

/**
 * How far the distance between two fixes may outrun the speed the receiver
 * reports before the step is treated as a position correction rather than
 * travel. A receiver that says "1.2 m/s" while its coordinates jump 20 m in a
 * second is re-acquiring, not describing a sprint — and Doppler speed is the
 * more trustworthy of the two. Generous enough (3x) to survive ordinary
 * acceleration and a late fix.
 */
private const val MAX_IMPLIED_SPEED_RATIO = 3.0

/** Floor for the jump check, so a near-zero reported speed cannot reject everything. */
private const val MIN_IMPLIED_SPEED_ALLOWANCE_MPS = 2.0

/**
 * The single source of truth for the recording in progress.
 *
 * Deliberately an object rather than an injected dependency: the foreground
 * service and the UI are separate Android components with no shared lifetime,
 * and both need the same live state. State lives in memory only — persistence
 * arrives with the upload queue.
 */
object RecordingRepository {

    private val _state = MutableStateFlow(RecordingState())

    /** The current recording, observed by the UI. */
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /** Wall-clock millis when the current recording stretch began. */
    private var stretchStartedAt: Long = 0L

    /**
     * Begin a recording, discarding anything previously held.
     *
     * @param now Epoch millis to treat as the start.
     */
    fun start(now: Long = System.currentTimeMillis()) {
        stretchStartedAt = now
        _state.value = RecordingState(
            status = RecordingStatus.RECORDING,
            waitingForFix = true,
            stretchStartedAt = now,
        )
    }

    /**
     * Hold the recording. Fixes that arrive while paused are ignored and the
     * clock stops, so a coffee stop does not count as slow running.
     *
     * @param now Epoch millis to close the current stretch at.
     */
    fun pause(now: Long = System.currentTimeMillis()) {
        val current = _state.value
        if (current.status != RecordingStatus.RECORDING) {
            return
        }
        _state.value = current.copy(
            status = RecordingStatus.PAUSED,
            movingMillis = current.movingMillis + (now - stretchStartedAt),
            stretchStartedAt = null,
        )
    }

    /**
     * Resume after a pause.
     *
     * @param now Epoch millis to open the new stretch at.
     */
    fun resume(now: Long = System.currentTimeMillis()) {
        if (_state.value.status != RecordingStatus.PAUSED) {
            return
        }
        stretchStartedAt = now
        _state.value = _state.value.copy(
            status = RecordingStatus.RECORDING,
            stretchStartedAt = now,
        )
    }

    /**
     * Finish the recording and hand back what was collected.
     *
     * @param now Epoch millis to close the final stretch at.
     * @return The completed recording, for saving and upload.
     */
    fun stop(now: Long = System.currentTimeMillis()): RecordingState {
        val current = _state.value
        val finished = when (current.status) {
            RecordingStatus.RECORDING -> current.copy(
                status = RecordingStatus.IDLE,
                movingMillis = current.movingMillis + (now - stretchStartedAt),
                stretchStartedAt = null,
            )

            else -> current.copy(status = RecordingStatus.IDLE, stretchStartedAt = null)
        }
        _state.value = RecordingState()
        return finished
    }

    /**
     * Offer a fix to the recording.
     *
     * Rejection is the interesting part: an inaccurate fix, or one that has not
     * meaningfully moved, is dropped rather than stored. Both would otherwise
     * show up as distance the athlete never covered.
     *
     * @param point The fix to consider.
     * @return True when the fix was accepted into the track.
     */
    fun offer(point: TrackPoint): Boolean {
        val current = _state.value
        if (current.status != RecordingStatus.RECORDING) {
            return false
        }
        if (point.accuracy > MAX_ACCURACY_M) {
            // Surface the rejection: a track that refuses to start because
            // every fix is vague looks identical to one with no GPS at all,
            // and the two need very different responses from the user.
            _state.value = current.copy(
                lastAccuracy = point.accuracy,
                weakSignal = true,
                lastSpeed = point.speed,
            )
            return false
        }

        val previous = current.points.lastOrNull()
        if (previous == null) {
            // The first accepted fix anchors the track; there is no movement to
            // measure yet, so it is stored unconditionally.
            _state.value = current.copy(
                points = listOf(point),
                lastFixAt = point.recordedAt,
                waitingForFix = false,
                lastAccuracy = point.accuracy,
                weakSignal = false,
                // The anchor fix is not movement, so it reports no speed.
                lastSpeed = 0.0,
            )
            return true
        }

        val step = distanceBetween(previous, point)

        // Two noise floors, both of which a step must clear to count as travel.
        // Note that a rejected fix is not added to the track, so `previous`
        // stays put and the next step is measured from there — a slow walk
        // accumulates across several fixes rather than being lost.
        val belowFixedFloor = step < MIN_MOVEMENT_M
        val withinFixNoise = step < point.accuracy * MIN_MOVEMENT_ACCURACY_RATIO

        // Doppler breaks the tie for steps small enough to be either drift or a
        // slow walk. It cannot veto a step that is already too large to be
        // noise; see DOPPLER_VETO_MAX_STEP_M for why that distinction matters.
        val reportedStationary = point.speed?.let {
            it < MIN_MOVING_SPEED_MPS && step < DOPPLER_VETO_MAX_STEP_M
        } ?: false

        if (belowFixedFloor || withinFixNoise || reportedStationary) {
            // Standing still: refresh the timestamp so the UI shows a live GPS,
            // but do not grow the track or the distance.
            // Judged stationary, so the speed shown must agree with the
            // distance not growing — otherwise the screen contradicts itself.
            _state.value = current.copy(
                lastFixAt = point.recordedAt,
                lastAccuracy = point.accuracy,
                weakSignal = false,
                lastSpeed = 0.0,
            )
            return false
        }

        // A step that implies far faster travel than the receiver reports is
        // the receiver correcting itself, not the athlete moving. Counting it
        // is how a phone on a table gains hundreds of metres in an hour.
        val elapsedSeconds = (point.recordedAt - previous.recordedAt) / 1000.0
        val impliedSpeed = if (elapsedSeconds > 0) step / elapsedSeconds else Double.MAX_VALUE

        // Only a real velocity solution can call a step a jump. A reported 0.0
        // means the receiver has no solution at all, and comparing against it
        // would cap every recording at the allowance floor — rejecting anything
        // faster than a jog for no reason.
        val reportedSpeed = point.speed?.takeIf { it >= MIN_MOVING_SPEED_MPS }
        if (reportedSpeed != null &&
            impliedSpeed > maxOf(reportedSpeed * MAX_IMPLIED_SPEED_RATIO, MIN_IMPLIED_SPEED_ALLOWANCE_MPS)
        ) {
            _state.value = current.copy(
                lastFixAt = point.recordedAt,
                lastAccuracy = point.accuracy,
                weakSignal = false,
                lastSpeed = 0.0,
            )
            return false
        }

        _state.value = current.copy(
            points = current.points + point,
            distanceMeters = current.distanceMeters + step,
            lastFixAt = point.recordedAt,
            waitingForFix = false,
            lastAccuracy = point.accuracy,
            weakSignal = false,
            // Fall back to the implied speed when the receiver has no velocity
            // solution: showing 0.0 km/h beside a growing distance would have
            // the screen contradict itself.
            lastSpeed = reportedSpeed ?: impliedSpeed,
        )
        return true
    }
}
