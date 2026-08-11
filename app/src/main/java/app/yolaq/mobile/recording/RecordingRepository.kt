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
 * A recording will not begin until a fix this accurate arrives.
 *
 * This is the real defence against drift, and it belongs here rather than in
 * the filter: a recording that starts on a vague fix has to filter hard to stay
 * honest, and filtering hard is how a slow walk gets thrown away. Fix the
 * signal at the source and the filter can stay gentle. RunnerUp uses the same
 * 10 m threshold for the same reason.
 */
private const val FIX_ACCURACY_M = 10f

/**
 * Movement below this is treated as jitter, not travel. Matches the server's
 * own rule so a track measured here and the same track measured after upload
 * agree with each other.
 */
private const val MIN_MOVEMENT_M = 2.0

/**
 * A step must also clear this fraction of the fix's own accuracy. A ±10 m fix
 * simply cannot resolve a 3 m move, so counting one is counting noise.
 *
 * Kept deliberately gentle: with [FIX_ACCURACY_M] gating the start, the
 * recording begins on a fix good enough that drift is small, so this no longer
 * has to carry the whole burden of rejecting it. An aggressive value here is
 * what previously made real walking register as nothing.
 */
private const val MIN_MOVEMENT_ACCURACY_RATIO = 0.35

/**
 * An approximate position vaguer than this tells us nothing worth acting on.
 *
 * Wi-Fi trilateration indoors lands around 20–50 m, which is useless for a
 * track and perfectly good for "you are in this building". A cell-tower-only
 * fix can be a kilometre wide; centring a map on that, or letting it start a
 * recording, would be the app pretending to know something it does not.
 */
private const val MAX_APPROXIMATE_ACCURACY_M = 200f

/**
 * How long a recording waits for a textbook anchor before settling.
 *
 * The anchor is held to [FIX_ACCURACY_M] because a track begun on a vague fix
 * has to filter hard afterwards, and filtering hard is how a slow walk gets
 * thrown away. But holding out for it *forever* is worse than either: under
 * trees, between buildings, or for the first minutes of a cold start the
 * receiver can sit at fifteen metres indefinitely, and a recording that
 * refuses to anchor records nothing at all and ends with "no GPS points".
 *
 * The wait screen used to have a "start anyway" button for exactly this, and
 * a recording begun from an approximate position never sees that screen. This
 * is that escape hatch, taken automatically.
 */
private const val ANCHOR_RELAX_AFTER_MS = 45_000L

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
 * How much of the recent past the displayed speed is measured over.
 *
 * The screen used to show a low-pass filter over the receiver's own per-fix
 * velocity, and while walking that number was unreadable: 15, then 37, then
 * 40, then 23 minutes per kilometre for one steady walk. Two things were
 * wrong with it. Doppler velocity is accurate to a few tenths of a metre per
 * second, which at a walking 1.4 m/s is a swing of a third either way. And
 * every fix judged stationary — which, at walking pace, is most of them, since
 * a single second's step is smaller than the noise floor — dragged the shown
 * speed towards zero, so the display alternated between walking and stopping
 * while the athlete did neither.
 *
 * Measuring displacement over twenty seconds fixes both. Twenty seconds is
 * about 28 m at walking pace and 70 m at running pace, comfortably outside
 * the ±5 m a good fix is uncertain by, so the ratio is dominated by real
 * movement rather than by error. It is also short enough that the number
 * still responds when the athlete does.
 *
 * (Measuring over a fixed *distance* — five metres, say — was the other
 * candidate and is worse: five metres is inside the noise, and at a standstill
 * the window never closes at all.)
 */
private const val PACE_WINDOW_MS = 20_000L

/**
 * The shortest span the displayed speed will be computed from.
 *
 * Below this the ratio is mostly measurement error. Better to show nothing for
 * the first few seconds than a number that is wrong and looks authoritative.
 */
private const val PACE_WINDOW_MIN_MS = 4_000L

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
     * Whether the phone is being carried, as the accelerometer sees it.
     *
     * The one input in this whole filter that does not come from the GPS
     * receiver, and therefore the only one that cannot be fooled by the same
     * multipath that fools the rest. Defaults to true: a missing or silent
     * sensor must never be able to stop a real outing from being measured.
     *
     * Volatile: written from the sensor's thread, read on whichever thread a
     * fix arrives on.
     */
    @Volatile
    private var deviceMoving: Boolean = true

    /**
     * Tells the recorder whether the phone is in motion.
     *
     * @param moving True when the accelerometer says it is being carried.
     */
    fun setDeviceMoving(moving: Boolean) {
        deviceMoving = moving
    }

    /**
     * Begin waiting for a fix good enough to record from.
     *
     * Nothing is recorded and the clock does not run until that fix arrives —
     * see [RecordingStatus.ACQUIRING]. Time spent hunting for satellites is not
     * part of the outing.
     *
     * @param now Epoch millis to treat as the start of the wait.
     */
    fun start(now: Long = System.currentTimeMillis()) {
        // Cleared as well as the state: this field outlives a recording, and a
        // new outing that never reaches RECORDING would otherwise be stopped
        // against the *previous* outing's start and report its age as elapsed
        // time.
        stretchStartedAt = 0L
        deviceMoving = true
        _state.value = RecordingState(
            status = RecordingStatus.ACQUIRING,
            waitingForFix = true,
            acquiringSince = now,
        )
    }

    /**
     * Start recording on whatever fix is available, abandoning the wait.
     *
     * Offered only after the wait has dragged on, because indoors or under
     * cover the accuracy may never reach [FIX_ACCURACY_M] and a wait screen
     * with no way past it strands the user.
     *
     * @param now Epoch millis to open the first stretch at.
     */
    fun startAnyway(now: Long = System.currentTimeMillis()) {
        if (_state.value.status != RecordingStatus.ACQUIRING) {
            return
        }
        stretchStartedAt = now
        _state.value = _state.value.copy(
            status = RecordingStatus.RECORDING,
            stretchStartedAt = now,
            acquiringSince = null,
        )
    }

    /**
     * Hold the recording. Fixes that arrive while paused are ignored and the
     * clock stops, so a coffee stop does not count as slow running.
     *
     * @param now Epoch millis to close the current stretch at.
     */
    /**
     * Reopens a finished recording the athlete decided not to keep yet.
     *
     * Pressing "Bitir" is not always a decision — it is often a pause taken
     * with the wrong button, or a change of mind at the door. The track is
     * still on disk in the journal at that point, so putting it back is a
     * matter of restoring the state it was in, and the outing carries on
     * rather than becoming two halves that have to be stitched together on
     * the web afterwards.
     *
     * Comes back **paused**: the athlete is standing still deciding, and
     * starting the clock again behind their back would quietly add that time
     * to the outing.
     *
     * @param points The track so far, oldest first.
     * @param distanceMeters The distance already accumulated.
     * @param movingMillis The moving time already accumulated.
     */
    fun reopen(points: List<TrackPoint>, distanceMeters: Double, movingMillis: Long) {
        stretchStartedAt = 0L
        _state.value = RecordingState(
            status = RecordingStatus.PAUSED,
            points = points,
            distanceMeters = distanceMeters,
            movingMillis = movingMillis,
            lastFixAt = points.lastOrNull()?.recordedAt,
            lastAccuracy = points.lastOrNull()?.accuracy,
            stretchStartedAt = null,
            lastSpeed = 0.0,
        )
    }

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
    /**
     * Eases the displayed speed towards a new reading.
     *
     * @param current The speed shown now, or null if none has been shown yet.
     * @param target The speed this fix suggests, in m/s.
     * @return The speed to show.
     */
    private fun rollingSpeed(points: List<TrackPoint>, nowMs: Long): Double? {
        val newest = points.lastOrNull() ?: return null
        val cutoff = nowMs - PACE_WINDOW_MS

        // Walked backwards rather than filtered: a three-hour ride holds
        // thousands of points and only the last twenty seconds of them matter,
        // so this touches a couple of dozen on each fix instead of all of them.
        var distance = 0.0
        var oldest = newest
        var index = points.lastIndex
        while (index > 0) {
            val previous = points[index - 1]
            if (previous.recordedAt < cutoff) {
                break
            }
            distance += distanceBetween(previous, points[index])
            oldest = previous
            index -= 1
        }

        // Measured to *now*, not to the newest point. That is what makes a
        // stop read as a stop: no new points arrive, the span keeps growing
        // over an unchanging distance, and the speed falls to zero on its own
        // instead of having to be pushed there.
        val spanMs = nowMs - oldest.recordedAt
        if (spanMs < PACE_WINDOW_MIN_MS) {
            return null
        }
        return distance / (spanMs / 1000.0)
    }

    /**
     * Handles a fix offered while still waiting for a usable signal.
     *
     * @param current The state to build on.
     * @param point The fix to consider.
     * @return True when this fix was good enough to start the recording.
     */
    private fun acquire(current: RecordingState, point: TrackPoint): Boolean {
        if (point.accuracy > FIX_ACCURACY_M) {
            // Report the accuracy while waiting so the screen can show progress
            // rather than an unexplained delay.
            _state.value = current.copy(lastAccuracy = point.accuracy)
            return false
        }
        stretchStartedAt = point.recordedAt
        _state.value = current.copy(
            status = RecordingStatus.RECORDING,
            points = listOf(point),
            lastFixAt = point.recordedAt,
            waitingForFix = false,
            lastAccuracy = point.accuracy,
            weakSignal = false,
            stretchStartedAt = point.recordedAt,
            acquiringSince = null,
            lastSpeed = 0.0,
        )
        return true
    }

    /**
     * Offers a position that came from Wi-Fi and cell towers, not satellites.
     *
     * These never enter the track. A 40 m fix that jumps between access points
     * would grow a distance while the athlete stands still — the exact bug
     * this recorder was already fixed for once — so it is kept strictly to the
     * things vagueness cannot hurt: showing the map somewhere sensible, and
     * answering the question "does this phone know where it is at all".
     *
     * It also lets a recording begin. Indoors the satellite receiver produces
     * nothing whatsoever — not a poor fix, nothing — so the wait screen has no
     * accuracy to show and no way to end. Starting here means the clock runs
     * from the moment the athlete pressed start, and the track begins at the
     * first real fix, which is usually the moment they step outside.
     *
     * @param point The approximate position.
     * @param now Epoch millis to start the clock from, when this starts the
     *   recording. Deliberately not taken from the fix — see below.
     * @return True when the state changed as a result.
     */
    fun offerApproximate(point: TrackPoint, now: Long = System.currentTimeMillis()): Boolean {
        val current = _state.value
        if (current.status == RecordingStatus.IDLE) {
            return false
        }
        if (point.accuracy > MAX_APPROXIMATE_ACCURACY_M) {
            return false
        }
        // Once the track has an anchor, a coarse position has nothing left to
        // contribute: the map follows the real fixes from then on.
        if (current.points.isNotEmpty()) {
            return false
        }

        val started = current.status == RecordingStatus.ACQUIRING
        _state.value = current.copy(
            status = if (started) RecordingStatus.RECORDING else current.status,
            approximatePosition = point,
            // Deliberately not written to lastAccuracy: that field drives the
            // "waiting for a ±N m fix" readout, and a 40 m Wi-Fi fix reported
            // there would read as the satellite receiver making progress it is
            // not making.
            awaitingSatellites = true,
            acquiringSince = if (started) null else current.acquiringSince,
            // The clock starts *now*, not at the fix's own timestamp. An
            // approximate position is often the one the phone had cached from
            // some other app minutes ago; taking its timestamp as the start of
            // the outing made the very first reading say "02:27" and every
            // later recording start further into the past than the last.
            stretchStartedAt = if (started) now else current.stretchStartedAt,
        )
        if (started) {
            stretchStartedAt = now
        }
        return true
    }

    fun offer(point: TrackPoint): Boolean {
        val current = _state.value
        if (current.status == RecordingStatus.ACQUIRING) {
            return acquire(current, point)
        }
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
                lastSpeed = rollingSpeed(current.points, point.recordedAt) ?: current.lastSpeed,
            )
            return false
        }

        val previous = current.points.lastOrNull()
        if (previous == null) {
            // The anchor prefers the strict threshold, and gives up on it
            // after a while: see ANCHOR_RELAX_AFTER_MS. Anything reaching this
            // line has already cleared MAX_ACCURACY_M above, so relaxing means
            // "take the best on offer" rather than "take anything".
            val waitedLongEnough = current.stretchStartedAt?.let {
                point.recordedAt - it >= ANCHOR_RELAX_AFTER_MS
            } ?: false
            if (!waitedLongEnough && point.accuracy > FIX_ACCURACY_M) {
                _state.value = current.copy(
                    lastAccuracy = point.accuracy,
                    lastSpeed = rollingSpeed(current.points, point.recordedAt) ?: current.lastSpeed,
                )
                return false
            }
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
                // Satellites have arrived; the approximate position has done
                // its job and must not linger where the map can still see it.
                approximatePosition = null,
                awaitingSatellites = false,
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

        // The accelerometer's veto, and unlike the Doppler one it is absolute.
        // A phone that is not being carried has not travelled, whatever its
        // coordinates say — and indoors they say a great deal: this hardware
        // wandered 68 m in two minutes on a desk, with the receiver reporting
        // up to 2.3 m/s to back it up. No amount of arguing with those numbers
        // using other numbers from the same receiver settles it; this ends it.
        val stillOnTheSpot = !deviceMoving

        // Doppler breaks the tie for steps small enough to be either drift or a
        // slow walk. It cannot veto a step that is already too large to be
        // noise; see DOPPLER_VETO_MAX_STEP_M for why that distinction matters.
        val reportedStationary = point.speed?.let {
            it < MIN_MOVING_SPEED_MPS && step < DOPPLER_VETO_MAX_STEP_M
        } ?: false

        if (stillOnTheSpot || belowFixedFloor || withinFixNoise || reportedStationary) {
            // Standing still: refresh the timestamp so the UI shows a live GPS,
            // but do not grow the track or the distance.
            // Judged stationary, so the speed shown must agree with the
            // distance not growing — otherwise the screen contradicts itself.
            _state.value = current.copy(
                lastFixAt = point.recordedAt,
                lastAccuracy = point.accuracy,
                weakSignal = false,
                lastSpeed = rollingSpeed(current.points, point.recordedAt) ?: current.lastSpeed,
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
                lastSpeed = rollingSpeed(current.points, point.recordedAt) ?: current.lastSpeed,
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
            // Measured across the window including this point. The receiver's
            // own velocity is not consulted at all any more: it is noisier
            // than the displacement it is meant to describe.
            lastSpeed = rollingSpeed(current.points + point, point.recordedAt) ?: current.lastSpeed,
        )
        return true
    }
}
