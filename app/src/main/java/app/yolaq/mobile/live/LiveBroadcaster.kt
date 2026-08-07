package app.yolaq.mobile.live

import android.util.Log
import app.yolaq.mobile.net.ApiResult
import app.yolaq.mobile.net.ServerConfig
import app.yolaq.mobile.net.YolakApi
import app.yolaq.mobile.recording.TrackPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Streams the recording to the server so it can be watched from a share link.
 *
 * Fixes are buffered and flushed on a timer rather than sent one by one: a
 * request per second would cost battery for no benefit, and the server draws a
 * live track from batches just as happily.
 *
 * Everything here is best-effort and must stay that way. Live tracking is a
 * convenience for whoever is watching; the recording itself is the thing that
 * matters, and it is already safe in the journal and, at the end, the upload
 * queue. So a failure here is logged and retried, never surfaced as a recording
 * error and never allowed to block a fix.
 *
 * @param config Where to broadcast to.
 * @param scope The service's scope; the flush loop dies with the service.
 */
class LiveBroadcaster(
    config: ServerConfig,
    private val scope: CoroutineScope,
) {

    private val api = YolakApi(config)

    /** Fixes not yet accepted by the server. */
    private val buffer = mutableListOf<TrackPoint>()

    /** Guards [buffer] between the location callback and the flush loop. */
    private val lock = Mutex()

    private var loop: Job? = null

    /** Begins flushing on a timer. */
    fun start() {
        if (loop?.isActive == true) {
            return
        }
        loop = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    /**
     * Queues a fix for the next flush.
     *
     * @param point The accepted fix.
     */
    fun offer(point: TrackPoint) {
        scope.launch {
            lock.withLock {
                buffer.add(point)
                // A long outage must not grow the buffer without limit. The
                // oldest points go first: a live view is about where the
                // athlete is now, and the full track is uploaded at the end
                // regardless, so nothing is actually lost by dropping them.
                while (buffer.size > MAX_BUFFERED_POINTS) {
                    buffer.removeAt(0)
                }
            }
        }
    }

    /**
     * Stops the loop after one last attempt to send what is buffered.
     *
     * Called when the recording ends, so the live view finishes where the
     * athlete finished rather than wherever the last successful flush left it.
     */
    fun stop() {
        loop?.cancel()
        loop = null
        // On the scope rather than inline: this runs from the service's stop
        // path, which must not block on a network call.
        scope.launch { flush() }
    }

    /** Sends the buffer, keeping it on failure. */
    private suspend fun flush() {
        val batch = lock.withLock { buffer.toList() }
        if (batch.isEmpty()) {
            return
        }

        when (val result = api.postLivePings(livePayload(batch))) {
            is ApiResult.Success -> lock.withLock {
                // Drop exactly what was sent: fixes that arrived during the
                // request are still waiting and must survive.
                buffer.subList(0, minOf(batch.size, buffer.size)).clear()
            }

            is ApiResult.Permanent -> {
                // A refusal that will repeat — a key without the upload scope,
                // most likely. Broadcasting is over for this outing; the
                // recording carries on untouched.
                Log.e(TAG, "Canlı yayın reddedildi (${result.status}): ${result.body}")
                loop?.cancel()
                loop = null
                lock.withLock { buffer.clear() }
            }

            is ApiResult.Transient -> Log.w(TAG, "Canlı yayın ertelendi: ${result.reason}")
        }
    }

    private companion object {
        const val TAG = "LiveBroadcaster"

        /**
         * How often the buffer is sent. Close enough to feel live to someone
         * following a share link, far enough apart to leave the radio asleep
         * between flushes.
         */
        const val FLUSH_INTERVAL_MS = 10_000L

        /**
         * The most fixes held while the network is away — the server's own
         * per-request limit, so a recovered buffer still fits in one call.
         */
        const val MAX_BUFFERED_POINTS = 200
    }
}
