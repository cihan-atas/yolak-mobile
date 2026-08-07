package app.yolaq.mobile.routes

import android.util.Log
import app.yolaq.mobile.net.ServerConfig
import app.yolaq.mobile.recording.TrackPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the athlete's routes so one can be followed while recording.
 *
 * Read-only, and reached with the same API key the uploader uses — the server
 * exposes these two endpoints to key auth for exactly this.
 */
class RoutesApi(private val config: ServerConfig) {

    /**
     * Lists the routes available to follow.
     *
     * @return The routes without geometry, or an empty list on any failure —
     *   a picker that cannot be filled is a missing convenience, not a reason
     *   to stop someone recording.
     */
    fun list(): List<FollowableRoute> {
        val body = get("${config.baseUrl}/api/v1/routes/followable") ?: return emptyList()
        return runCatching {
            val records = JSONObject(body).optJSONArray("records") ?: JSONArray()
            (0 until records.length()).map { index ->
                val record = records.getJSONObject(index)
                FollowableRoute(
                    id = record.getInt("id"),
                    name = record.optString("name").ifBlank { "Rota ${record.getInt("id")}" },
                    distanceMeters = record.optDouble("distance", 0.0),
                )
            }
        }.getOrElse {
            Log.w(TAG, "Rota listesi çözümlenemedi", it)
            emptyList()
        }
    }

    /**
     * Fetches one route's line.
     *
     * @param route The route to fill in.
     * @return The route with its points, or null when it could not be read.
     */
    fun withGeometry(route: FollowableRoute): FollowableRoute? {
        val body = get("${config.baseUrl}/api/v1/routes/followable/${route.id}") ?: return null
        return runCatching {
            val geometry = JSONObject(body).optJSONArray("geometry") ?: JSONArray()
            val points = (0 until geometry.length()).map { index ->
                // Each entry is [latitude, longitude, elevation].
                val point = geometry.getJSONArray(index)
                TrackPoint(
                    latitude = point.getDouble(0),
                    longitude = point.getDouble(1),
                    elevation = point.optDouble(2).takeIf { !it.isNaN() },
                    speed = null,
                    // A planned line has no measurement error; the value is
                    // here only because the shared point type carries it.
                    accuracy = 0f,
                    recordedAt = 0L,
                )
            }
            route.copy(points = points)
        }.getOrElse {
            Log.w(TAG, "Rota geometrisi çözümlenemedi", it)
            null
        }
    }

    /**
     * Performs an authenticated GET.
     *
     * @param url Absolute URL.
     * @return The response body, or null on any failure.
     */
    private fun get(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("X-API-Key", config.apiKey)
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "Rota isteği reddedildi (${connection.responseCode}): $url")
                return null
            }
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } catch (error: Exception) {
            Log.w(TAG, "Rota isteği başarısız: $url", error)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TAG = "RoutesApi"
        const val TIMEOUT_MS = 15_000
    }
}
