package app.yolaq.mobile.share

import android.graphics.Color
import androidx.annotation.StringRes
import app.yolaq.mobile.R

/**
 * The colour of the track on a shared image.
 *
 * White was the only option and it was the wrong default: over a satellite
 * view of a beach, a snowfield or a bright road it disappears into the ground
 * it is drawn on, which is exactly the picture people most want to post. A
 * saturated line reads on anything, and which saturated line is a matter of
 * taste — so it is the athlete's to pick.
 *
 * Every one of these is light enough to sit over the dark casing drawn beneath
 * it, which is what makes them legible on a dark basemap as well as a bright
 * photograph. That is the constraint the set is chosen against, not fashion.
 *
 * @property argb The line colour.
 * @property labelRes What the swatch is called, for the accessibility label.
 */
enum class RouteColour(val argb: Int, @StringRes val labelRes: Int) {
    /** The default. Reads on greenery, tarmac, water and snow alike. */
    ORANGE(Color.rgb(0xFC, 0x4C, 0x02), R.string.colour_orange),

    WHITE(Color.WHITE, R.string.colour_white),

    /** The app's own green, lightened enough to carry on a dark basemap. */
    GREEN(Color.rgb(0x5D, 0xCA, 0xA5), R.string.colour_green),

    YELLOW(Color.rgb(0xFF, 0xD4, 0x00), R.string.colour_yellow),

    CYAN(Color.rgb(0x00, 0xD1, 0xFF), R.string.colour_cyan),

    MAGENTA(Color.rgb(0xFF, 0x3D, 0xA5), R.string.colour_magenta),
    ;

    companion object {
        /**
         * The dark outline drawn under every line.
         *
         * One casing colour for the whole set rather than a contrasting one
         * per colour: all six are light, so a dark casing lifts each of them
         * off both a pale sky and a dark basemap, and a per-colour rule would
         * be six ways to get the same answer.
         */
        const val CASING = 0x99000000.toInt()
    }
}
