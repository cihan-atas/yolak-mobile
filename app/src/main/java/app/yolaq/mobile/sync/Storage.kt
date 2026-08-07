package app.yolaq.mobile.sync

import android.content.Context
import java.io.File

/**
 * The on-disk locations the recorder and the uploader share.
 *
 * One place so the service, the worker and the screen cannot drift onto
 * different paths — a queue written in one directory and read from another
 * looks exactly like uploads that silently never happen.
 */
object Storage {

    /**
     * The journal of the recording in progress.
     *
     * @param context Any context.
     * @return The journal.
     */
    fun journal(context: Context): TrackJournal =
        TrackJournal(File(context.applicationContext.filesDir, "current-track.csv"))

    /**
     * The queue of finished recordings awaiting upload.
     *
     * @param context Any context.
     * @return The queue.
     */
    fun queue(context: Context): UploadQueue =
        UploadQueue(File(context.applicationContext.filesDir, "pending"))
}
