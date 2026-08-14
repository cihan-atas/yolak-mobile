package app.yolaq.mobile.share

import android.util.Log
import org.json.JSONObject

private const val TAG = "ShareCard"

/**
 * An outing, as much of it as a shareable image needs.
 *
 * Handed over by the web page rather than fetched from the API. The page is
 * already showing the activity — it has the name, the figures and the whole
 * track loaded to draw its own map — so asking the server a second time would
 * be a round trip for data sitting in the WebView a few pixels away. It also
 * keeps the feature off the API key, which is minted for uploading and would
 * have needed a read scope and every existing user to sign in again.
 *
 * Every figure is optional except the distance: a treadmill run has no track,
 * a walk has no power, and a card that refuses to render without them would be
 * useless for exactly the outings people most want to post.
 *
 * @property activityId Which activity, used to name the file.
 * @property title What the athlete called it.
 * @property sportLabel The sport, already translated by the page.
 * @property dateLabel When, already formatted by the page in the user's locale.
 * @property distanceMeters How far.
 * @property movingSeconds How long, moving.
 * @property paceSecondsPerKm Average pace, when the sport has one.
 * @property speedKmh Average speed.
 * @property elevationGainMeters Total climb.
 * @property route The track as latitude/longitude pairs, thinned by the page.
 */
data class ShareCard(
    val activityId: Long,
    val title: String,
    val sportLabel: String,
    val dateLabel: String,
    val distanceMeters: Double,
    val movingSeconds: Long?,
    val paceSecondsPerKm: Double?,
    val speedKmh: Double?,
    val elevationGainMeters: Double?,
    val route: List<Pair<Double, Double>>,
) {
    companion object {

        /**
         * Reads a card out of the payload the page sends across the bridge.
         *
         * Tolerant by design: the bridge carries whatever the page could work
         * out, and a missing figure drops a tile from the card rather than
         * failing the share. Only a malformed payload returns null, and that
         * is a bug in the page, not a state a user can reach.
         *
         * @param json The bridge payload.
         * @return The card, or null when the payload cannot be read at all.
         */
        fun fromJson(json: String): ShareCard? = runCatching {
            val root = JSONObject(json)
            val route = root.optJSONArray("route")
            ShareCard(
                activityId = root.optLong("id"),
                title = root.optString("title").ifBlank { root.optString("sport") },
                sportLabel = root.optString("sport"),
                dateLabel = root.optString("date"),
                distanceMeters = root.optDouble("distanceMeters", 0.0),
                movingSeconds = root.optDouble("movingSeconds").takeIf { !it.isNaN() && it > 0 }
                    ?.toLong(),
                paceSecondsPerKm = root.optDouble("paceSecondsPerKm")
                    .takeIf { !it.isNaN() && it > 0 },
                speedKmh = root.optDouble("speedKmh").takeIf { !it.isNaN() && it > 0 },
                elevationGainMeters = root.optDouble("elevationGainMeters")
                    .takeIf { !it.isNaN() && it > 0 },
                route = buildList {
                    for (index in 0 until (route?.length() ?: 0)) {
                        val pair = route?.optJSONArray(index) ?: continue
                        if (pair.length() < 2) {
                            continue
                        }
                        add(pair.getDouble(0) to pair.getDouble(1))
                    }
                },
            )
        }.getOrElse {
            Log.w(TAG, "Paylaşım verisi çözümlenemedi", it)
            null
        }
    }
}

/** The shape of the image, which is really the question of where it is posted. */
enum class ShareFormat(val width: Int, val height: Int) {
    /** Instagram and WhatsApp stories, and the default for that reason. */
    STORY(1080, 1920),

    /** A feed post, and the one that survives being viewed on a desktop. */
    SQUARE(1080, 1080),
}
