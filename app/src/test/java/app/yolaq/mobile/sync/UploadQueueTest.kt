package app.yolaq.mobile.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the queue that stands between a finished outing and the server.
 *
 * Its whole job is to never lose a recording: not to a dead network, not to a
 * server that refuses it, not to a reboot. So the tests are mostly about what
 * must still be on disk afterwards.
 */
class UploadQueueTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun queue(): UploadQueue = UploadQueue(File(folder.root, "pending"))

    @Test
    fun `queues oldest first`() {
        val queue = queue()
        queue.enqueue("<gpx/>", 2_000_000_000_000L)
        queue.enqueue("<gpx/>", 1_000_000_000_000L)

        assertEquals(
            listOf("yolak-1000000000000.gpx", "yolak-2000000000000.gpx"),
            queue.pending().map { it.name },
        )
    }

    @Test
    fun `an accepted recording leaves the queue`() {
        val queue = queue()
        val file = queue.enqueue("<gpx/>", 1_000L)

        queue.complete(file)

        assertTrue(queue.pending().isEmpty())
        assertFalse(file.exists())
    }

    /**
     * A refusal the server will repeat must stop being retried — but the file
     * is an outing, and a fixed key or setting could still make it uploadable,
     * so it is set aside rather than deleted.
     */
    @Test
    fun `a rejected recording is kept out of the way rather than deleted`() {
        val queue = queue()
        val file = queue.enqueue("<gpx/>", 1_000L)

        queue.reject(file)

        assertTrue(queue.pending().isEmpty())
        val kept = File(File(folder.root, "pending"), "rejected/yolak-1000.gpx")
        assertTrue(kept.exists())
        assertEquals("<gpx/>", kept.readText())
    }

    @Test
    fun `the rejected pile is not mistaken for pending work`() {
        val queue = queue()
        queue.reject(queue.enqueue("<gpx/>", 1_000L))
        queue.enqueue("<gpx/>", 2_000L)

        assertEquals(listOf("yolak-2000.gpx"), queue.pending().map { it.name })
    }

    @Test
    fun `an empty queue is not an error`() {
        assertTrue(queue().pending().isEmpty())
    }
}
