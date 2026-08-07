package app.yolaq.mobile.net

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * How a sign-in attempt ended.
 */
sealed interface LoginResult {

    /** Signed in; the key is stored and the app is ready. */
    data object Success : LoginResult

    /**
     * The account has two-factor authentication switched on.
     *
     * Not supported here yet, and saying so plainly beats a generic failure —
     * the user would otherwise retype a password that was never the problem.
     */
    data object MfaRequired : LoginResult

    /**
     * The attempt failed.
     *
     * @property message What to show the user.
     */
    data class Failed(val message: String) : LoginResult
}

/**
 * Signs the user in with a username and a password.
 *
 * The app needs three different things from one sign-in, which is why this is
 * more than a single request:
 *
 *  1. An **API key**, because uploads run from a background worker hours later
 *     with nobody there to re-enter a password. The server mints keys for
 *     exactly this, so login creates one and the user never sees it.
 *  2. A **web session cookie**, so the web tab is signed in too. Without it the
 *     user would sign in here and immediately face the web app's own login
 *     screen — the same credentials, twice, for no reason they could guess.
 *  3. The **username**, to show whose account this is.
 */
object LoginService {

    private const val TAG = "LoginService"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Name the key is filed under on the server's API key page. */
    private val KEY_NAME = "yolak mobil (${Build.MODEL})"

    /**
     * Performs the whole sign-in.
     *
     * @param context Any context, for storing the result.
     * @param baseUrl Server address as typed.
     * @param username The account name.
     * @param password The account password.
     * @return How it went.
     */
    fun signIn(context: Context, baseUrl: String, username: String, password: String): LoginResult {
        val origin = ServerSettings.normaliseBaseUrl(baseUrl)
        if (origin.isEmpty()) {
            return LoginResult.Failed("Sunucu adresi boş")
        }
        val config = ServerConfig(origin, apiKey = "")

        val mobile = postLogin(config.loginUrl, username, password, clientType = "mobile")
        when (mobile) {
            is HttpOutcome.Failure -> return LoginResult.Failed(mobile.message)
            is HttpOutcome.Ok -> Unit
        }

        val body = runCatching { JSONObject(mobile.body) }.getOrElse {
            return LoginResult.Failed("Sunucudan beklenmeyen yanıt")
        }
        if (body.optBoolean("mfa_required", false)) {
            return LoginResult.MfaRequired
        }
        val accessToken = body.optString("access_token").takeIf { it.isNotBlank() }
            ?: return LoginResult.Failed("Sunucu erişim jetonu döndürmedi")

        val apiKey = createApiKey(config.apiKeysUrl, accessToken, password)
            ?: return LoginResult.Failed("API anahtarı oluşturulamadı")

        // Best effort: a failure here costs the web tab its session, not the
        // recorder its ability to upload, so it must not fail the sign-in.
        val webCookie = (postLogin(config.loginUrl, username, password, clientType = "web") as? HttpOutcome.Ok)
            ?.refreshCookie

        ServerSettings.save(context, origin, apiKey, username, webCookie)
        return LoginResult.Success
    }

    /** A response, reduced to what the caller needs. */
    private sealed interface HttpOutcome {
        data class Ok(val body: String, val refreshCookie: String?) : HttpOutcome
        data class Failure(val message: String) : HttpOutcome
    }

    /**
     * Posts credentials to the login endpoint.
     *
     * @param url The login URL.
     * @param username The account name.
     * @param password The account password.
     * @param clientType `mobile` for tokens in the body, `web` for a cookie.
     * @return The outcome.
     */
    private fun postLogin(
        url: String,
        username: String,
        password: String,
        clientType: String,
    ): HttpOutcome {
        // The endpoint takes an OAuth2 password form, not JSON. Both fields are
        // encoded: a password with '&' or '+' in it would otherwise be
        // silently truncated and read as a wrong password.
        val form = "username=${encode(username)}&password=${encode(password)}"

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("X-Client-Type", clientType)
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }

            when (val status = connection.responseCode) {
                in 200..299 -> HttpOutcome.Ok(
                    body = connection.inputStream.bufferedReader().use(BufferedReader::readText),
                    refreshCookie = pickRefreshCookie(connection.headerFields["Set-Cookie"]),
                )

                401 -> HttpOutcome.Failure("Kullanıcı adı veya parola hatalı")
                429 -> HttpOutcome.Failure("Çok fazla deneme yapıldı, biraz bekleyin")
                else -> HttpOutcome.Failure("Sunucu hatası (HTTP $status)")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Giriş isteği başarısız", error)
            HttpOutcome.Failure("Sunucuya ulaşılamadı — adresi ve bağlantını kontrol et")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Mints the upload key the background worker will use.
     *
     * The password goes along a second time because the server treats key
     * creation as a sensitive action and re-checks it — a stolen access token
     * alone must not be enough to mint a long-lived credential.
     *
     * @param url The API key collection URL.
     * @param accessToken Bearer token from the login just performed.
     * @param password The account password, for the server's step-up check.
     * @return The raw key, or null when creation failed.
     */
    private fun createApiKey(url: String, accessToken: String, password: String): String? {
        val payload = JSONObject()
            .put("name", KEY_NAME)
            .put("scopes", org.json.JSONArray().put("activities:upload"))
            .put("current_password", password)
            .toString()

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("X-Client-Type", "mobile")
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            if (connection.responseCode !in 200..299) {
                Log.e(
                    TAG,
                    "API anahtarı reddedildi (${connection.responseCode}): " +
                        connection.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty().take(300),
                )
                return null
            }
            JSONObject(connection.inputStream.bufferedReader().use(BufferedReader::readText))
                .optString("key")
                .takeIf { it.isNotBlank() }
        } catch (error: Exception) {
            Log.w(TAG, "API anahtarı isteği başarısız", error)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Picks the session cookie out of the login response.
     *
     * The server sends three `Set-Cookie` headers under this one name: two
     * that clear stale cookies from older paths (empty value, `Max-Age=0`) and
     * then the real one. Taking the first match — the obvious thing to write —
     * stores an empty cookie, and the web tab greets a user who has just
     * signed in with its own login screen.
     *
     * @param headers Every `Set-Cookie` value from the response.
     * @return The cookie that actually carries a session, or null.
     */
    private fun pickRefreshCookie(headers: List<String>?): String? = headers
        ?.filter { it.startsWith("$REFRESH_COOKIE_NAME=") }
        ?.lastOrNull { header ->
            val value = header.substringAfter('=').substringBefore(';').trim('"', ' ')
            value.isNotEmpty() && !header.contains("Max-Age=0", ignoreCase = true)
        }

    /**
     * Percent-encodes a form field.
     *
     * @param value The raw value.
     * @return The encoded value.
     */
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** The cookie the web app bootstraps its session from. */
    const val REFRESH_COOKIE_NAME = "endurain_refresh_token"
}
