package app.yolaq.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for address normalisation.
 *
 * People type the address four different ways, and three of them would build a
 * URL that fails in a way indistinguishable from a broken server.
 */
class ServerSettingsTest {

    @Test
    fun `bare host gets https`() {
        assertEquals("https://yolaq.app", ServerSettings.normaliseBaseUrl("yolaq.app"))
    }

    @Test
    fun `trailing slashes and spaces are trimmed`() {
        assertEquals("https://yolaq.app", ServerSettings.normaliseBaseUrl("  https://yolaq.app/  "))
    }

    /** A local test server has no certificate; typing the scheme keeps it usable. */
    @Test
    fun `an explicit scheme is respected`() {
        assertEquals("http://10.0.0.5:8080", ServerSettings.normaliseBaseUrl("http://10.0.0.5:8080/"))
    }

    @Test
    fun `endpoints hang off the origin`() {
        val config = ServerConfig("https://yolaq.app", "key")

        assertEquals("https://yolaq.app/api/v1/live/ping", config.livePingUrl)
        assertEquals("https://yolaq.app/api/v1/activities/create/upload", config.uploadUrl)
    }
}
