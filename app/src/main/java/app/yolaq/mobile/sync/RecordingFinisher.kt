package app.yolaq.mobile.sync

import android.content.Context
import android.util.Log
import app.yolaq.mobile.R
import app.yolaq.mobile.recording.SportType
import app.yolaq.mobile.recording.TrackPoint
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
     * @return The queued file, or null when the track was too short to keep.
     */
    fun queue(
        context: Context,
        points: List<TrackPoint>,
        sport: SportType,
        reportOutcome: Boolean = true,
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
        UploadWorker.schedule(context)
        return file
    }

    /**
     * Rescues a recording whose process died before it could be finished.
     *
     * Called on startup rather than only after a crash: there is no reliable
     * signal that the last run ended badly, and a leftover journal is that
     * signal by itself.
     *
     * @param context Any context.
     * @return The queued file, or null when there was nothing to recover.
     */
    fun recoverInterrupted(context: Context): File? {
        val journal = Storage.journal(context)
        val recovered = journal.read() ?: return null

        Log.w(TAG, "Yarım kalmış kayıt bulundu: ${recovered.points.size} nokta")
        val file = queue(context, recovered.points, recovered.sport)
        // Cleared either way: a track too short to queue is also too short to
        // keep offering back on every launch.
        journal.clear()
        return file
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
