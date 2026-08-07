package app.yolaq.mobile.sync

import java.io.File

/**
 * Finished recordings waiting to reach the server.
 *
 * A recording is written here the moment it ends, before any upload is
 * attempted, so an outing finished in flight mode or a dead spot is already
 * safe on disk. Nothing is ever deleted until the server has accepted it.
 *
 * Refusals the server will repeat — a rejected key, a file it cannot parse —
 * are moved aside rather than deleted or retried: retrying spins forever,
 * deleting throws away an outing that a fixed setting would have uploaded fine.
 *
 * @param directory Where pending files live.
 */
class UploadQueue(private val directory: File) {

    /** Files the server refused for good, kept for inspection. */
    private val rejectedDirectory = File(directory, "rejected")

    /**
     * Writes a recording into the queue.
     *
     * @param content The GPX document.
     * @param recordedAt Epoch millis the recording started, used for ordering.
     * @return The queued file.
     */
    fun enqueue(content: String, recordedAt: Long): File {
        directory.mkdirs()
        val file = File(directory, "yolak-$recordedAt.gpx")
        file.writeText(content)
        return file
    }

    /**
     * Lists what is waiting, oldest first.
     *
     * Order matters: activities arriving out of order is confusing, and the
     * oldest is the one most likely to be forgotten about.
     *
     * @return The queued files.
     */
    fun pending(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(".gpx") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * Drops a file the server has accepted.
     *
     * @param file The uploaded file.
     */
    fun complete(file: File) {
        file.delete()
    }

    /**
     * Sets aside a file the server refused for good.
     *
     * @param file The rejected file.
     */
    fun reject(file: File) {
        rejectedDirectory.mkdirs()
        if (!file.renameTo(File(rejectedDirectory, file.name))) {
            // Keeping it in place would have the queue retry a refusal forever;
            // the copy is the fallback when a rename across the same directory
            // somehow fails.
            file.copyTo(File(rejectedDirectory, file.name), overwrite = true)
            file.delete()
        }
    }
}
