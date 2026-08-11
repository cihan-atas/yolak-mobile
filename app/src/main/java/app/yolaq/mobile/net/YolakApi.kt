package app.yolaq.mobile.net

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * What became of a request.
 *
 * The distinction that matters is [Transient] versus [Permanent]: a queued
 * activity must be retried forever through tunnels and flight mode, but a key
 * the server rejects will be rejected identically on every retry, and a queue
 * that cannot tell the two apart either loses outings or spins on them.
 */
sealed interface ApiResult {

    /**
     * The server accepted the request.
     *
     * @property body The response, as sent. Carried because the upload
     *   endpoint answers with the activities it created, and the app needs
     *   their ids to take the athlete straight to what they just recorded.
     */
    data class Success(val body: String) : ApiResult

    /**
     * The request failed in a way a later attempt may survive: no network, a
     * timeout, a 5xx, a proxy hiccup.
     *
     * @property reason Short description, for the log.
     */
    data class Transient(val reason: String) : ApiResult

    /**
     * The server understood and refused. Retrying changes nothing — a bad key,
     * a missing scope, a file it will not parse.
     *
     * @property status HTTP status code.
     * @property body Beginning of the response body, for the log.
     */
    data class Permanent(val status: Int, val body: String) : ApiResult
}

/**
 * The server endpoints this app talks to.
 *
 * Built on [HttpURLConnection] rather than an HTTP library: two endpoints, one
 * of them a fixed-shape multipart POST, do not justify the dependency, and the
 * APK stays small enough to hand around as a file.
 */
class YolakApi(private val config: ServerConfig) {

    /**
     * Sends buffered positions to the live-tracking endpoint.
     *
     * The server opens and closes the session on its own — first position
     * starts one, silence ends it — so there is nothing to set up or tear down
     * from here.
     *
     * @param body A JSON body as built by `livePayload`.
     * @return How the request went.
     */
    fun postLivePings(body: String): ApiResult = post(
        url = config.livePingUrl,
        contentType = "application/json; charset=utf-8",
        writeBody = { output -> output.write(body.toByteArray(Charsets.UTF_8)) },
    )

    /**
     * Uploads a finished recording as a GPX file.
     *
     * @param file The GPX to send.
     * @return How the request went.
     */
    fun uploadGpx(file: File): ApiResult {
        val boundary = "----yolak${System.currentTimeMillis()}"
        return post(
            url = config.uploadUrl,
            contentType = "multipart/form-data; boundary=$boundary",
            writeBody = { output ->
                val header = buildString {
                    append("--").append(boundary).append("\r\n")
                    append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                        .append(file.name).append("\"\r\n")
                    append("Content-Type: application/gpx+xml\r\n\r\n")
                }
                output.write(header.toByteArray(Charsets.UTF_8))
                FileInputStream(file).use { it.copyTo(output) }
                output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            },
        )
    }

    /**
     * Performs a POST and classifies the outcome.
     *
     * @param url Absolute URL.
     * @param contentType Value for the Content-Type header.
     * @param writeBody Writes the request body to the connection's stream.
     * @return How the request went.
     */
    private fun post(
        url: String,
        contentType: String,
        writeBody: (java.io.OutputStream) -> Unit,
    ): ApiResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("X-API-Key", config.apiKey)
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("Accept", "application/json")
                // Streamed rather than buffered so a long recording's GPX does
                // not have to fit in memory twice on the way out.
                setChunkedStreamingMode(0)
            }
            connection.outputStream.use(writeBody)

            when (val status = connection.responseCode) {
                in 200..299 -> ApiResult.Success(connection.readBody())

                // 408 and 429 are refusals that a later attempt genuinely may
                // survive, unlike the rest of the 4xx range.
                408, 429 -> ApiResult.Transient("HTTP $status")

                in 400..499 -> ApiResult.Permanent(status, connection.readErrorBody())
                else -> ApiResult.Transient("HTTP $status")
            }
        } catch (error: Exception) {
            // No network, DNS failure, timeout, TLS problem: all worth retrying.
            Log.w(TAG, "İstek başarısız: $url", error)
            ApiResult.Transient(error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Reads a successful response.
     *
     * Capped like the error reader: the app only ever looks for ids near the
     * front, and a server answering with something unexpected should not be
     * able to pull an arbitrary amount of it into memory.
     *
     * @return The first characters of the body, or an empty string.
     */
    private fun HttpURLConnection.readBody(): String = runCatching {
        inputStream?.bufferedReader()?.use(BufferedReader::readText)?.take(BODY_CHARS).orEmpty()
    }.getOrDefault("")

    /**
     * Reads the beginning of an error response.
     *
     * @return The first characters of the body, or an empty string.
     */
    private fun HttpURLConnection.readErrorBody(): String = runCatching {
        errorStream?.bufferedReader()?.use(BufferedReader::readText)?.take(ERROR_BODY_CHARS).orEmpty()
    }.getOrDefault("")

    private companion object {
        const val TAG = "YolakApi"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000

        /** Enough of a refusal to diagnose it, not enough to fill the log. */
        const val ERROR_BODY_CHARS = 500

        /**
         * How much of a successful response is read.
         *
         * The upload endpoint answers with the created activities; only their
         * ids are wanted, and they are at the front.
         */
        const val BODY_CHARS = 2000
    }
}
