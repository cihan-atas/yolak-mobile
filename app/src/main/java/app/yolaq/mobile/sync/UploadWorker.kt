package app.yolaq.mobile.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.yolaq.mobile.R
import app.yolaq.mobile.net.ApiResult
import app.yolaq.mobile.net.ServerSettings
import app.yolaq.mobile.net.YolakApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends queued recordings to the server, whenever the network allows.
 *
 * WorkManager rather than a retry loop in the app: the phone that finishes an
 * outing in a dead spot is usually put straight in a pocket, and the upload has
 * to happen later — after the app is gone, possibly after a reboot — the moment
 * a network appears. That is precisely what WorkManager guarantees and an
 * in-process loop cannot.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    /**
     * The notification an expedited run shows on older Androids.
     *
     * Below Android 12 the system grants expedited work by running it as a
     * foreground service, and a worker that cannot describe that service is
     * refused outright — so without this, asking for a prompt upload would
     * make the upload fail on exactly the phones that need every break they
     * can get. On Android 12 and up this is never asked for.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                UPLOAD_CHANNEL_ID,
                applicationContext.getString(R.string.upload_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        val notification = NotificationCompat.Builder(applicationContext, UPLOAD_CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(applicationContext.getString(R.string.upload_running))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(UPLOAD_NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = ServerSettings.load(applicationContext)
        if (config == null) {
            // Nothing to send them to yet. The files stay queued and saving the
            // settings schedules this worker again.
            Log.i(TAG, "Sunucu ayarlı değil, yükleme ertelendi")
            // Said out loud rather than left to a timeout: an athlete whose
            // session has gone was being told their outing was queued for a
            // network that was never the problem.
            RecordingFinisher.reportBlocked(RecordingFinisher.Handover.SignedOut)
            return@withContext Result.success()
        }

        val queue = Storage.queue(applicationContext)
        val api = YolakApi(config)
        var deferred = false

        // Each file gets one attempt per run, and the queue is re-read after
        // every pass: a recording saved while this run was in flight would
        // otherwise sit untouched until the next launch, because the run that
        // could have sent it had already listed the directory.
        val attempted = mutableSetOf<String>()
        while (true) {
            val batch = queue.pending().filterNot { it.name in attempted }
            if (batch.isEmpty()) {
                break
            }

            for (file in batch) {
                attempted += file.name
                when (val result = api.uploadGpx(file)) {
                    is ApiResult.Success -> {
                        Log.i(TAG, "Yüklendi: ${file.name}")
                        val id = createdActivityId(result.body)
                        // Before the file is dropped: the metadata lives beside
                        // it, and completing first would delete the record of
                        // what still had to be applied.
                        if (id != null) {
                            applyMeta(api, queue.meta(file), id)
                        }
                        queue.complete(file)
                        // The strip over the page uses this to say the outing
                        // landed and to offer opening it. An answer without an
                        // id still means it landed.
                        RecordingFinisher.report(
                            file.name,
                            if (id == null) {
                                RecordingFinisher.Handover.Saved
                            } else {
                                RecordingFinisher.Handover.Uploaded(id)
                            },
                        )
                    }

                    is ApiResult.Permanent -> {
                        // The server understood and refused; another attempt
                        // would be refused identically.
                        Log.e(TAG, "Sunucu reddetti (${result.status}): ${file.name} · ${result.body}")
                        queue.reject(file)
                        RecordingFinisher.report(
                            file.name,
                            RecordingFinisher.Handover.Refused(result.status),
                        )
                    }

                    is ApiResult.Transient -> {
                        Log.w(TAG, "Yükleme ertelendi (${result.reason}): ${file.name}")
                        // Carry on down the queue rather than stopping here.
                        // Stopping kept the order, but it also let one outing
                        // the server would never take hold up every recording
                        // made after it — including the one someone is waiting
                        // for with the phone in their hand.
                        deferred = true
                        RecordingFinisher.report(
                            file.name,
                            RecordingFinisher.Handover.Deferred(result.reason),
                        )
                    }
                }
            }
        }

        if (deferred) Result.retry() else Result.success()
    }

    /**
     * Applies the one choice the save sheet collected that the file could not
     * carry.
     *
     * Best-effort by design: the recording is already on the server, and an
     * athlete whose visibility did not take is in a far better place than one
     * whose outing was retried, duplicated or held back over it. So a failure
     * here is logged and the upload still counts as done. A key minted before
     * the app asked for write access lands here as a 403, which is why it is
     * only ever a log line.
     *
     * @param api The client to use.
     * @param meta What was stored beside the file.
     * @param activityId The activity the server created.
     */
    private fun applyMeta(api: YolakApi, meta: Map<String, String>, activityId: Long) {
        val visibility = meta[RecordingFinisher.META_VISIBILITY]?.toIntOrNull() ?: return
        when (val result = api.setActivityVisibility(activityId, visibility)) {
            is ApiResult.Success -> Log.i(TAG, "Görünürlük uygulandı ($visibility): $activityId")
            is ApiResult.Permanent ->
                Log.w(TAG, "Görünürlük uygulanamadı (${result.status}): ${result.body}")

            is ApiResult.Transient -> Log.w(TAG, "Görünürlük uygulanamadı: ${result.reason}")
        }
    }

    /**
     * Picks the created activity's id out of an upload response.
     *
     * Read with a regex rather than a JSON parser: the app carries no JSON
     * dependency for two endpoints, and the one field wanted is the first
     * `"id"` in the array the server answers with. A response shaped
     * differently simply yields null, and the upload still counts as done —
     * the activity is on the server either way, and only the shortcut to its
     * edit form is lost.
     *
     * @param body The response body.
     * @return The activity id, or null when the response did not carry one.
     */
    private fun createdActivityId(body: String): Long? =
        ID_PATTERN.find(body)?.groupValues?.getOrNull(1)?.toLongOrNull()

    companion object {
        private const val TAG = "UploadWorker"

        /** The first `"id": <number>` in the server's answer. */
        private val ID_PATTERN = Regex("\"id\"\\s*:\\s*(\\d+)")

        /** Where the expedited-run notification posts on older Androids. */
        private const val UPLOAD_CHANNEL_ID = "upload"

        private const val UPLOAD_NOTIFICATION_ID = 2

        /** One queue, one worker: a second run would race the first. */
        private const val WORK_NAME = "upload-activities"

        /**
         * How long a failed run waits before the next attempt.
         *
         * WorkManager's default is exponential from thirty seconds, which is
         * sensible for work nobody is watching and wrong for this: a single
         * failed attempt — one tunnel, one restarting server — put every
         * upload half a minute away, and the athlete pressing "Kaydet" in the
         * meantime got silence for twenty seconds and then a message blaming
         * a network that was working. Ten seconds, linear, is WorkManager's
         * floor and comfortably inside the time anyone will wait.
         */
        private const val BACKOFF_SECONDS = 10L

        /**
         * Asks for the queue to be flushed as soon as there is a network.
         *
         * Safe to call whenever something might have changed — a finished
         * recording, saved settings, the screen opening.
         *
         * @param context Any context.
         * @param urgent Whether the athlete is waiting for this upload right
         *   now. Background callers keep an already-scheduled run so repeated
         *   calls cannot push the upload further away; a save cannot, because
         *   the run it would keep may be one sitting out a backoff it earned
         *   an hour ago, and keeping it is how pressing "Kaydet" on a working
         *   network did nothing at all.
         */
        fun schedule(context: Context, urgent: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .apply {
                    // An athlete who has just pressed "Kaydet" is watching for
                    // the outing to appear. Ordinary work is scheduled when the
                    // system feels like it, which was seconds of nothing
                    // happening for no reason anyone could see; expedited work
                    // starts now, and falls back to the ordinary queue rather
                    // than failing when the app has no quota left.
                    if (urgent) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .build()

            val policy = if (urgent) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }
}
