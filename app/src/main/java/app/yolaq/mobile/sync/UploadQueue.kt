package app.yolaq.mobile.sync

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

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
     * The sidecar holding what could not be written into the file itself.
     *
     * A GPX carries the name, the sport and the description, and the server
     * reads all three — but not who may see the activity, which only exists
     * once the activity does. So that choice waits here, beside the recording,
     * and is applied by whoever manages to upload it. On disk rather than in
     * memory because the upload may not happen until tomorrow, in a process
     * that has been started from cold.
     *
     * @param file The queued recording.
     * @return Where its metadata lives.
     */
    private fun sidecar(file: File): File = File(file.parentFile, "${file.name}.meta")

    /**
     * Writes a recording into the queue.
     *
     * @param content The GPX document.
     * @param recordedAt Epoch millis the recording started, used for ordering.
     * @param meta What to apply to the activity once the server has made it.
     * @return The queued file.
     */
    fun enqueue(content: String, recordedAt: Long, meta: Map<String, String> = emptyMap()): File {
        directory.mkdirs()
        val file = File(directory, "yolak-$recordedAt.gpx")
        file.writeText(content)
        // Written before the recording itself would be picked up: an uploader
        // that found the GPX without its metadata would apply nothing and
        // delete the file, and the choice would be lost with no way to notice.
        if (meta.isNotEmpty()) {
            sidecar(file).writeText(
                meta.entries.joinToString("\n") { (key, value) ->
                    "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
                },
            )
        }
        return file
    }

    /**
     * Reads what should be applied to a queued recording once it lands.
     *
     * @param file The queued recording.
     * @return The metadata, empty when there is none.
     */
    fun meta(file: File): Map<String, String> {
        val sidecar = sidecar(file)
        if (!sidecar.isFile) {
            return emptyMap()
        }
        return runCatching {
            sidecar.readLines()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) {
                        null
                    } else {
                        line.take(separator) to
                            URLDecoder.decode(line.substring(separator + 1), Charsets.UTF_8.name())
                    }
                }
                .toMap()
        }.getOrDefault(emptyMap())
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
        sidecar(file).delete()
        file.delete()
    }

    /**
     * Sets aside a file the server refused for good.
     *
     * @param file The rejected file.
     */
    fun reject(file: File) {
        rejectedDirectory.mkdirs()
        sidecar(file).delete()
        if (!file.renameTo(File(rejectedDirectory, file.name))) {
            // Keeping it in place would have the queue retry a refusal forever;
            // the copy is the fallback when a rename across the same directory
            // somehow fails.
            file.copyTo(File(rejectedDirectory, file.name), overwrite = true)
            file.delete()
        }
    }
}
