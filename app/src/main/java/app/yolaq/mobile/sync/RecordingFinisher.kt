package app.yolaq.mobile.sync

import android.content.Context
import android.util.Log
import app.yolaq.mobile.R
import app.yolaq.mobile.recording.SportType
import app.yolaq.mobile.recording.TrackPoint
import app.yolaq.mobile.recording.distanceBetween
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Turns a track into a queued upload.
 *
 * Shared by the two ways a recording ends: the athlete pressing "Bitir", and
 * the app discovering a journal left behind by a process that was killed. Both
 * have to produce the same thing — a GPX in the queue — and having one path do
 * it slightly differently is how the rarer of the two ends up broken.
 */
object RecordingFinisher {

    private const val TAG = "RecordingFinisher"

    /**
     * How the last finished recording ended.
     *
     * Exists because the alternative is silence: a recording that captured no
     * usable fix is dropped on the floor, and without this the athlete presses
     * stop, sees the screen reset, and finds nothing on the web — with no way
     * to tell a lost outing from one that was never recorded. The screen shows
     * this and clears it on the next start.
     */
    sealed interface Outcome {
        /**
         * The track is on disk and waiting to upload.
         *
         * @property points How many fixes it holds.
         */
        data class Queued(val points: Int) : Outcome

        /**
         * Nothing was kept: the recording never got enough fixes.
         *
         * Almost always means indoors or no sky — the receiver produced
         * nothing to record, not that the app failed.
         *
         * @property points How many fixes there were, usually zero.
         */
        data class TooShort(val points: Int) : Outcome
    }

    /**
     * A finished recording the athlete has not decided about yet.
     *
     * Pressing "Bitir" used to upload immediately, which is the one moment a
     * recording is *not* finished being decided: it might be a false start, it
     * might want a different name, it might not be something to publish at
     * all. So the track waits here — already safe on disk in the journal —
     * until the athlete keeps it or throws it away.
     *
     * @property points The fixes recorded, oldest first.
     * @property sport What was recorded.
     * @property distanceMeters How far, along the track.
     * @property elapsedMillis How long, from first fix to last.
     */
    data class Review(
        val points: List<TrackPoint>,
        val sport: SportType,
        val distanceMeters: Double,
        val elapsedMillis: Long,
    )

    private val _pendingReview = MutableStateFlow<Review?>(null)

    /** The recording waiting to be kept or discarded, if there is one. */
    val pendingReview: StateFlow<Review?> = _pendingReview.asStateFlow()

    /**
     * What became of the upload the athlete is standing there waiting for.
     *
     * The screen used to be told only about success, and inferred everything
     * else from a stopwatch: twenty seconds without an activity id meant "no
     * connection". That guess is wrong for every other way an upload ends —
     * a rejected key, a file the server will not parse, nobody signed in —
     * and it told the athlete their outing was safely queued when it had in
     * fact been set aside and would never be sent. The uploader knows which
     * of these happened, so it says so.
     */
    sealed interface Handover {

        /**
         * The server made an activity and said which one.
         *
         * @property activityId The new activity's id.
         */
        data class Uploaded(val activityId: Long) : Handover

        /**
         * The server accepted it but the answer carried no id to follow.
         *
         * Harmless — the outing is on the server; only the shortcut to its
         * edit form is lost.
         */
        data object Saved : Handover

        /**
         * The server understood and refused, and will refuse again.
         *
         * @property status HTTP status code.
         */
        data class Refused(val status: Int) : Handover

        /**
         * The attempt failed in a way a later one may survive; the file is
         * still in the queue.
         *
         * @property reason Short description, for the log.
         */
        data class Deferred(val reason: String) : Handover

        /** There is no server to send it to: nobody is signed in. */
        data object SignedOut : Handover
    }

    private val _awaitingUpload = MutableStateFlow(false)

    /**
     * Whether a kept recording is on its way to the server right now.
     *
     * Exposed as a flow rather than left to the screen to remember, because
     * the keep is no longer always started by the screen: pressing "Bitir"
     * hands the recording straight to the uploader from the service, and the
     * screen has to be able to find out that it happened.
     */
    val awaitingUpload: StateFlow<Boolean> = _awaitingUpload.asStateFlow()

    private val _handover = MutableStateFlow<Handover?>(null)

    /** How the awaited upload ended, or null while none has. */
    val handover: StateFlow<Handover?> = _handover.asStateFlow()

    /**
     * The queued file someone is standing in front of the phone waiting for.
     *
     * The queue uploads whatever it holds whenever a network appears — an
     * outing finished in a tunnel yesterday may land while the athlete is
     * reading their feed today. Throwing them into an edit form then would be
     * the app grabbing the wheel. So the handover is armed by the athlete
     * pressing "Kaydet" and disarmed the moment they leave that screen.
     *
     * It is the file's name rather than a flag because the queue is ordered:
     * a stranded older outing uploads first, and a flag would send the athlete
     * to *its* edit form instead of the one they just recorded.
     *
     * Volatile: written by the screen's thread, read by the worker's.
     */
    @Volatile
    private var awaitedFile: String? = null

    /**
     * The file the screen is waiting on, for the uploader to recognise.
     *
     * @return The queued file's name, or null when nobody is waiting.
     */
    fun awaitedFileName(): String? = awaitedFile

    /**
     * Called by the uploader with the fate of a queued file.
     *
     * Anything that is not the awaited file is ignored: the queue's other
     * traffic is not this athlete's question.
     *
     * @param fileName The queued file the result belongs to.
     * @param result What became of it.
     */
    fun report(fileName: String, result: Handover) {
        if (awaitedFile != fileName) {
            return
        }
        awaitedFile = null
        _awaitingUpload.value = false
        _handover.value = result
    }

    /**
     * Called by the uploader when it cannot send anything at all.
     *
     * @param result Why not.
     */
    fun reportBlocked(result: Handover) {
        if (awaitedFile == null) {
            return
        }
        awaitedFile = null
        _awaitingUpload.value = false
        _handover.value = result
    }

    /** Called once the screen has acted on the result — or has stopped waiting. */
    fun clearHandover() {
        awaitedFile = null
        _awaitingUpload.value = false
        _handover.value = null
    }

    private val _lastOutcome = MutableStateFlow<Outcome?>(null)

    /** What became of the last recording, for the screen to explain. */
    val lastOutcome: StateFlow<Outcome?> = _lastOutcome.asStateFlow()

    /** Clears the message, called when a new recording begins. */
    fun clearOutcome() {
        _lastOutcome.value = null
    }

    /**
     * A track this short is a false start, not an outing.
     *
     * Two points are the minimum for any distance at all, and uploading a
     * recording that was stopped seconds after starting just leaves rubbish to
     * delete on the web.
     */
    private const val MIN_POINTS = 2

    /**
     * Queues a finished track and asks for it to be uploaded.
     *
     * @param context Any context.
     * @param points The accepted fixes, oldest first.
     * @param sport What was recorded.
     * @param reportOutcome Whether to surface the result on screen.
     * @param urgent Whether someone is waiting for this one right now.
     * @param arm Run on the queued file before the upload is asked for, so a
     *   caller can register interest in it without racing an uploader that may
     *   already be running.
     * @return The queued file, or null when the track was too short to keep.
     */
    fun queue(
        context: Context,
        points: List<TrackPoint>,
        sport: SportType,
        reportOutcome: Boolean = true,
        urgent: Boolean = false,
        arm: (File) -> Unit = {},
    ): File? {
        if (points.size < MIN_POINTS) {
            Log.i(TAG, "Kayıt çok kısa (${points.size} nokta), kuyruğa alınmadı")
            // Not reported when the athlete backed out before the recording
            // ever began: telling someone who pressed cancel that their
            // recording failed is noise dressed as an error.
            if (reportOutcome) {
                _lastOutcome.value = Outcome.TooShort(points.size)
            }
            return null
        }

        val startedAt = points.first().recordedAt
        val gpx = GpxWriter.write(points, sport, activityName(context, sport, startedAt))
        val file = Storage.queue(context).enqueue(gpx, startedAt)
        Log.i(TAG, "Kuyruğa alındı: ${file.name} (${points.size} nokta)")

        _lastOutcome.value = Outcome.Queued(points.size)
        arm(file)
        UploadWorker.schedule(context, urgent = urgent)
        return file
    }

    /**
     * Holds a finished recording for the athlete to decide about.
     *
     * Nothing is written here: the journal already holds every fix, written as
     * they arrived, and it is deliberately *not* cleared when a recording ends
     * any more. That makes the journal the draft — a recording survives the
     * process being killed between finishing and deciding, and comes back on
     * the next launch as the same question.
     *
     * @param context Any context.
     * @param points The accepted fixes, oldest first.
     * @param sport What was recorded.
     * @param distanceMeters How far, as the recorder measured it.
     * @param elapsedMillis How long the outing ran.
     * @param reportOutcome Whether a track too short to keep is worth saying so.
     * @param keepImmediately Whether to send it straight up rather than ask.
     *   True when the athlete has just pressed "Bitir" — they decided by
     *   pressing it, and the decisions that remain (what to call it, who can
     *   see it, whether to keep it at all) are the ones the activity's own
     *   edit form already asks, so the recording goes up and that form opens.
     *   False when the recording is being *offered back* after a crash: that
     *   athlete never pressed anything, and publishing an outing they might
     *   have deleted is exactly what this flow was built to stop.
     * @return True when there is something to decide about.
     */
    fun hold(
        context: Context,
        points: List<TrackPoint>,
        sport: SportType,
        distanceMeters: Double,
        elapsedMillis: Long,
        reportOutcome: Boolean = true,
        keepImmediately: Boolean = false,
    ): Boolean {
        if (points.size < MIN_POINTS) {
            Log.i(TAG, "Kayıt çok kısa (${points.size} nokta), sorulmadan atıldı")
            // Nothing to decide about: an outing with no fixes is not a choice
            // between keeping and discarding, it is nothing at all.
            if (reportOutcome) {
                _lastOutcome.value = Outcome.TooShort(points.size)
            }
            Storage.journal(context).clear()
            return false
        }
        _pendingReview.value = Review(points, sport, distanceMeters, elapsedMillis)
        if (keepImmediately) {
            keep(context)
        }
        return true
    }

    /**
     * Offers back a recording left unreviewed by an earlier run.
     *
     * Covers both a process killed mid-outing and one killed between finishing
     * and deciding — indistinguishable from here, and the same question either
     * way. It used to upload such a track without asking, which is how an
     * outing someone would have deleted ended up published.
     *
     * @param context Any context.
     * @return True when a recording is waiting to be decided about.
     */
    fun restore(context: Context): Boolean {
        if (_pendingReview.value != null) {
            return true
        }
        val recovered = Storage.journal(context).read() ?: return false
        Log.w(TAG, "Karara bağlanmamış kayıt bulundu: ${recovered.points.size} nokta")
        val points = recovered.points
        return hold(
            context = context,
            points = points,
            sport = recovered.sport,
            distanceMeters = points.zipWithNext().sumOf { (from, to) -> distanceBetween(from, to) },
            // First fix to last: the journal keeps no clock of its own, and a
            // paused outing's gaps are already in these timestamps.
            elapsedMillis = (points.lastOrNull()?.recordedAt ?: 0) - (points.firstOrNull()?.recordedAt ?: 0),
            reportOutcome = false,
        )
    }

    /**
     * Keeps the recording being reviewed: queues it and asks for the upload.
     *
     * Touches the disk, so it belongs off the main thread.
     *
     * @param context Any context.
     * @return The queued file, or null when there was nothing under review.
     */
    fun keep(context: Context): File? {
        val review = _pendingReview.value ?: return null
        _handover.value = null
        // Armed from inside, between the file being written and the upload
        // being asked for: an uploader already running would otherwise finish
        // this file before there was a name to match its report against.
        val file = queue(
            context = context,
            points = review.points,
            sport = review.sport,
            urgent = true,
            arm = { queued ->
                awaitedFile = queued.name
                _awaitingUpload.value = true
            },
        )
        Storage.journal(context).clear()
        _pendingReview.value = null
        return file
    }

    /**
     * Hands the recording under review back to the recorder.
     *
     * The third answer to "keep or discard", and the one Strava's own finish
     * screen has: neither. The journal was deliberately left in place when the
     * recording was held, so nothing has to be reconstructed — the track is
     * exactly where it was.
     *
     * @param context Any context.
     * @return The recording to resume, or null when there was nothing held.
     */
    fun reopen(context: Context): Review? {
        val review = _pendingReview.value ?: return null
        clearHandover()
        _lastOutcome.value = null
        _pendingReview.value = null
        // The journal is untouched: it is still the draft, and the recording
        // that resumes keeps appending to it.
        Log.i(TAG, "Kayda geri dönüldü: ${review.points.size} nokta")
        return review
    }

    /**
     * Throws the recording being reviewed away.
     *
     * @param context Any context.
     */
    fun discard(context: Context) {
        Log.i(TAG, "Kayıt atıldı: ${_pendingReview.value?.points?.size ?: 0} nokta")
        Storage.journal(context).clear()
        _pendingReview.value = null
        _lastOutcome.value = null
        clearHandover()
    }

    /**
     * Names the activity the way it will read in the feed.
     *
     * @param context Any context, for the sport's label.
     * @param sport What was recorded.
     * @param startedAt Epoch millis the recording began.
     * @return The activity name.
     */
    private fun activityName(context: Context, sport: SportType, startedAt: Long): String {
        val time = SimpleDateFormat("d MMMM HH:mm", Locale("tr")).format(Date(startedAt))
        return context.getString(R.string.activity_name, context.getString(sport.labelRes), time)
    }
}
