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
     * @return The queued file, or null when the track was too short to keep.
     */
    fun queue(context: Context, points: List<TrackPoint>, sport: SportType): File? {
        if (points.size < MIN_POINTS) {
            Log.i(TAG, "Kayıt çok kısa (${points.size} nokta), kuyruğa alınmadı")
            return null
        }

        val startedAt = points.first().recordedAt
        val gpx = GpxWriter.write(points, sport, activityName(context, sport, startedAt))
        val file = Storage.queue(context).enqueue(gpx, startedAt)
        Log.i(TAG, "Kuyruğa alındı: ${file.name} (${points.size} nokta)")

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
