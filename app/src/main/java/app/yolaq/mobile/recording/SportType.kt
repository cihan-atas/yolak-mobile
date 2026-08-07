package app.yolaq.mobile.recording

import androidx.annotation.StringRes
import app.yolaq.mobile.R

/**
 * What the athlete is doing, as far as the server needs to know.
 *
 * The value travels in the GPX `<type>` element, which the server maps to its
 * own activity type. Without one every outing lands as a generic "Workout",
 * which breaks the sport filters on challenges and segments — so the choice is
 * asked for before recording rather than corrected afterwards on the web.
 *
 * @property gpxType The string the server's type mapping recognises.
 * @property labelRes What the sport is called on screen.
 */
enum class SportType(val gpxType: String, @StringRes val labelRes: Int) {
    RUNNING("running", R.string.sport_running),
    WALKING("walking", R.string.sport_walking),
    CYCLING("cycling", R.string.sport_cycling),
    HIKING("hiking", R.string.sport_hiking),
    ;

    companion object {
        /** What a recording uses when nothing was chosen. */
        val DEFAULT = WALKING

        /**
         * Resolves a stored name back to a sport.
         *
         * @param name The enum name as written to the journal or an intent.
         * @return The matching sport, or [DEFAULT] when unrecognised.
         */
        fun fromName(name: String?): SportType =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
