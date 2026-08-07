package app.yolaq.mobile.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.yolaq.mobile.net.ApiResult
import app.yolaq.mobile.net.ServerSettings
import app.yolaq.mobile.net.YolakApi
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

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = ServerSettings.load(applicationContext)
        if (config == null) {
            // Nothing to send them to yet. The files stay queued and saving the
            // settings schedules this worker again.
            Log.i(TAG, "Sunucu ayarlı değil, yükleme ertelendi")
            return@withContext Result.success()
        }

        val queue = Storage.queue(applicationContext)
        val api = YolakApi(config)
        var deferred = false

        for (file in queue.pending()) {
            when (val result = api.uploadGpx(file)) {
                is ApiResult.Success -> {
                    Log.i(TAG, "Yüklendi: ${file.name}")
                    queue.complete(file)
                }

                is ApiResult.Permanent -> {
                    // The server understood and refused; another attempt would
                    // be refused identically.
                    Log.e(TAG, "Sunucu reddetti (${result.status}): ${file.name} · ${result.body}")
                    queue.reject(file)
                }

                is ApiResult.Transient -> {
                    Log.w(TAG, "Yükleme ertelendi (${result.reason}): ${file.name}")
                    // Stop at the first transient failure rather than hammering
                    // the rest of the queue against the same dead network, and
                    // keep the order intact.
                    deferred = true
                    break
                }
            }
        }

        if (deferred) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "UploadWorker"

        /** One queue, one worker: a second run would race the first. */
        private const val WORK_NAME = "upload-activities"

        /**
         * Asks for the queue to be flushed as soon as there is a network.
         *
         * Safe to call whenever something might have changed — a finished
         * recording, saved settings, the screen opening. An already-scheduled
         * run is kept rather than restarted, so repeated calls cannot push the
         * upload further away.
         *
         * @param context Any context.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
