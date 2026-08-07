package app.yolaq.mobile.routes

import app.yolaq.mobile.recording.TrackPoint

/**
 * A route the athlete drew on the web, as the recorder needs it.
 *
 * @property id Server-side route ID.
 * @property name What the athlete called it.
 * @property distanceMeters Its planned length.
 * @property points The line to follow, or empty until it is fetched.
 */
data class FollowableRoute(
    val id: Int,
    val name: String,
    val distanceMeters: Double,
    val points: List<TrackPoint> = emptyList(),
)
