package app.yolaq.mobile.net

import android.content.Context

/**
 * Where to send recordings, and what to authenticate with.
 *
 * The user signs in with their username and password like anywhere else; the
 * API key behind this is minted for them during login and never shown. It has
 * to be a key rather than the login session because the uploader runs from a
 * background worker long after the screen is gone, with nobody there to
 * re-enter a password when a token expires.
 *
 * @property baseUrl Server origin, without a trailing slash (e.g. `https://yolaq.app`).
 * @property apiKey The server-issued key, sent as `X-API-Key`.
 * @property username Who is signed in, for the account line on screen.
 */
data class ServerConfig(
    val baseUrl: String,
    val apiKey: String,
    val username: String = "",
) {
    /** Absolute URL of the live-tracking ingest endpoint. */
    val livePingUrl: String get() = "$baseUrl/api/v1/live/ping"

    /** Absolute URL of the activity file upload endpoint. */
    val uploadUrl: String get() = "$baseUrl/api/v1/activities/create/upload"

    /**
     * Absolute URL for setting one activity's visibility.
     *
     * @param activityId The activity.
     * @param visibility 0 public, 1 followers, 2 private.
     * @return The endpoint.
     */
    fun activityVisibilityUrl(activityId: Long, visibility: Int): String =
        "$baseUrl/api/v1/activities/$activityId/visibility/$visibility"

    /** Absolute URL of the login endpoint. */
    val loginUrl: String get() = "$baseUrl/api/v1/auth/login"

    /** Absolute URL of the API key collection. */
    val apiKeysUrl: String get() = "$baseUrl/api/v1/profile/api_keys"
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
    private const val KEY_USERNAME = "username"
    private const val KEY_WEB_COOKIE = "web_refresh_cookie"
    private const val KEY_WEB_COOKIE_PLANTED = "web_refresh_cookie_planted"

    /**
     * The server this app is for.
     *
     * Prefilled so signing in is a username and a password, nothing else. A
     * different server can still be typed in; only someone running their own
     * copy will ever need to.
     */
    const val DEFAULT_BASE_URL = "https://yolaq.app"

    /**
     * Loads the signed-in session.
     *
     * @param context Any context.
     * @return The configuration, or null while nobody is signed in.
     */
    fun load(context: Context): ServerConfig? {
        val prefs = prefs(context)
        val baseUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val apiKey = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        return ServerConfig(baseUrl, apiKey, prefs.getString(KEY_USERNAME, "").orEmpty())
    }

    /**
     * Stores everything a completed login produced.
     *
     * @param context Any context.
     * @param baseUrl Server address, already normalised.
     * @param apiKey The key minted during login.
     * @param username Who signed in.
     * @param webRefreshCookie The web session cookie, for the web tab.
     */
    fun save(
        context: Context,
        baseUrl: String,
        apiKey: String,
        username: String,
        webRefreshCookie: String?,
    ) {
        prefs(context).edit()
            .putString(KEY_BASE_URL, normaliseBaseUrl(baseUrl))
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_WEB_COOKIE, webRefreshCookie)
            .apply()
    }

    /**
     * The web session cookie captured at login.
     *
     * Handed to the web tab so signing in once covers both halves of the app —
     * the web view would otherwise show its own login screen straight after
     * the user had just signed in.
     *
     * @param context Any context.
     * @return The raw `Set-Cookie` value, or null when there is none.
     */
    fun webRefreshCookie(context: Context): String? =
        prefs(context).getString(KEY_WEB_COOKIE, null)?.takeIf { it.isNotBlank() }

    /**
     * Whether the captured cookie has already been handed to the web view.
     *
     * It may only be handed over once — see `WebSession.install` for what
     * re-planting a spent refresh token does to a working session.
     *
     * @param context Any context.
     * @return True once it has been planted.
     */
    fun webCookiePlanted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WEB_COOKIE_PLANTED, false)

    /**
     * Records that the cookie has been planted.
     *
     * @param context Any context.
     */
    fun markWebCookiePlanted(context: Context) {
        prefs(context).edit().putBoolean(KEY_WEB_COOKIE_PLANTED, true).apply()
    }

    /**
     * Signs out, forgetting the key and the web session.
     *
     * The queue is deliberately left alone: recordings waiting to upload are
     * the user's, not the session's, and they upload after the next sign-in.
     *
     * @param context Any context.
     */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
