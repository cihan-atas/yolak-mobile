package app.yolaq.mobile.sync

import app.yolaq.mobile.recording.SportType
import app.yolaq.mobile.recording.TrackPoint
import java.io.File

/**
 * An append-only record of the outing in progress.
 *
 * The recording itself lives in memory, which means a low-memory kill, a crash,
 * or a battery pull takes the whole outing with it — and an outing is an hour
 * of someone's life that cannot be re-run. Every accepted fix is therefore
 * written here as it arrives, so the worst a lost process costs is the seconds
 * since the last fix.
 *
 * The format is one line per fix, appended and flushed immediately: a file
 * truncated mid-write by a kill loses its last line and stays readable, which
 * a structured format would not. Free of Android APIs so recovery can be tested
 * on the JVM.
 *
 * @param file Where the journal is kept.
 */
class TrackJournal(private val file: File) {

    /**
     * What a journal held.
     *
     * @property sport The sport the recording was started with.
     * @property points The fixes recovered, oldest first.
     */
    data class Recovered(val sport: SportType, val points: List<TrackPoint>)

    /**
     * Starts a fresh journal, discarding anything left in the old one.
     *
     * @param sport The sport being recorded.
     */
    fun begin(sport: SportType) {
        file.parentFile?.mkdirs()
        file.writeText("$FORMAT_VERSION;${sport.name}\n")
    }

    /**
     * Appends one accepted fix.
     *
     * @param point The fix to record.
     */
    fun append(point: TrackPoint) {
        val line = buildString {
            append(point.latitude).append(';')
            append(point.longitude).append(';')
            append(point.elevation ?: "").append(';')
            append(point.speed ?: "").append(';')
            append(point.accuracy).append(';')
            append(point.recordedAt)
        }
        file.appendText("$line\n")
    }

    /**
     * Reads back an interrupted recording.
     *
     * @return What the journal held, or null when there is nothing worth
     *   recovering — no file, no header, or a header with no fixes after it.
     */
    fun read(): Recovered? {
        if (!file.exists()) {
            return null
        }
        val lines = runCatching { file.readLines() }.getOrElse { return null }
        val header = lines.firstOrNull()?.split(';') ?: return null
        if (header.getOrNull(0) != FORMAT_VERSION) {
            return null
        }

        val points = lines.drop(1).mapNotNull(::parsePoint)
        return if (points.isEmpty()) null else Recovered(SportType.fromName(header.getOrNull(1)), points)
    }

    /** Discards the journal once its recording has been dealt with. */
    fun clear() {
        file.delete()
    }

    /**
     * Parses one journal line.
     *
     * @param line The line as written by [append].
     * @return The fix, or null when the line is incomplete — which the last
     *   line of a killed process legitimately is.
     */
    private fun parsePoint(line: String): TrackPoint? {
        val parts = line.split(';')
        if (parts.size != FIELD_COUNT) {
            return null
        }
        val latitude = parts[0].toDoubleOrNull() ?: return null
        val longitude = parts[1].toDoubleOrNull() ?: return null
        val accuracy = parts[4].toFloatOrNull() ?: return null
        val recordedAt = parts[5].toLongOrNull() ?: return null
        return TrackPoint(
            latitude = latitude,
            longitude = longitude,
            elevation = parts[2].toDoubleOrNull(),
            speed = parts[3].toDoubleOrNull(),
            accuracy = accuracy,
            recordedAt = recordedAt,
        )
    }

    private companion object {
        /** Guards against reading a journal written by an older layout. */
        const val FORMAT_VERSION = "v1"

        const val FIELD_COUNT = 6
    }
}
