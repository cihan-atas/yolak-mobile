package app.yolaq.mobile.net

import android.content.Context

/**
 * Where to send recordings, and what to authenticate with.
 *
 * An API key rather than a session: the uploader runs from a background worker
 * long after the screen is gone, and a key scoped to `activities:upload` is the
 * credential the server already offers for exactly that. A full login arrives
 * with the WebView shell (phase 8, step 5); until then the key is what makes
 * the recorder useful.
 *
 * @property baseUrl Server origin, without a trailing slash (e.g. `https://yolaq.app`).
 * @property apiKey The server-issued key, sent as `X-API-Key`.
 */
data class ServerConfig(
    val baseUrl: String,
    val apiKey: String,
) {
    /** Absolute URL of the live-tracking ingest endpoint. */
    val livePingUrl: String get() = "$baseUrl/api/v1/live/ping"

    /** Absolute URL of the activity file upload endpoint. */
    val uploadUrl: String get() = "$baseUrl/api/v1/activities/create/upload"
}

/**
 * Reads and writes the server settings.
 *
 * Stored in ordinary app-private preferences. That keeps the app free of the
 * crypto dependency, and on a non-rooted phone private storage is already out
 * of other apps' reach; the key is upload-scoped, so the worst case is someone
 * with the device posting activities, not reading the account.
 */
object ServerSettings {

    private const val PREFS = "server"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"

    /**
     * Loads the configured server.
     *
     * @param context Any context.
     * @return The configuration, or null while the app is unconfigured.
     */
    fun load(context: Context): ServerConfig? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val baseUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val apiKey = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        return ServerConfig(baseUrl, apiKey)
    }

    /**
     * Saves the server settings, normalising the address first.
     *
     * @param context Any context.
     * @param baseUrl Server address as the user typed it.
     * @param apiKey The API key as the user pasted it.
     */
    fun save(context: Context, baseUrl: String, apiKey: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, normaliseBaseUrl(baseUrl))
            .putString(KEY_API_KEY, apiKey.trim())
            .apply()
    }

    /**
     * Turns a typed address into an origin the URL builders can append to.
     *
     * People type `yolaq.app`, `yolaq.app/`, and `https://yolaq.app/` in equal
     * measure, and a request built from the wrong one of those fails in a way
     * that looks like a broken server rather than a typo.
     *
     * @param input The address as typed.
     * @return Scheme-qualified origin with no trailing slash.
     */
    fun normaliseBaseUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) {
            return ""
        }
        // Default to HTTPS: this credential should not travel in the clear, and
        // a plain-http address stays possible by typing the scheme explicitly.
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return withScheme.trimEnd('/')
    }
}
