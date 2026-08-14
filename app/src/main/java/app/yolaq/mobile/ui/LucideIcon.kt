package app.yolaq.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser

/**
 * The icon set the web half already draws, rendered natively.
 *
 * The bottom bar exists so the app is one product across its two halves, and
 * hand-drawn approximations undid exactly that: opening the recorder swapped a
 * bicycle for a cog and a heart for a pulse line, so the bar visibly changed
 * identity at the moment it was supposed to prove continuity.
 *
 * These are the same Lucide glyphs the page uses, as their own SVG path data,
 * parsed and stroked the way Lucide strokes them — 24-unit grid, 2-unit round
 * stroke. Copying the path strings rather than the pictures means the two bars
 * cannot drift apart by eye; if the web swaps an icon, the string is what
 * changes here too.
 */
object Lucide {

    /** The grid Lucide's path data is authored on. */
    private const val GRID = 24f

    /** Lucide's stroke width, in grid units. */
    private const val STROKE = 2f

    const val HOUSE = "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8 " +
        "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"

    // Lucide draws the bicycle's wheels and crank as circles; written here as
    // arc pairs because path data has no circle command.
    const val BIKE = "M22 17.5a3.5 3.5 0 1 1-7 0 3.5 3.5 0 1 1 7 0z " +
        "M9 17.5a3.5 3.5 0 1 1-7 0 3.5 3.5 0 1 1 7 0z " +
        "M16 5a1 1 0 1 1-2 0 1 1 0 1 1 2 0z " +
        "M12 17.5V14l-3-3 4-3 2 3h2"

    const val HEART = "M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5" +
        "c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5"

    const val CIRCLE_DOT = "M22 12a10 10 0 1 1-20 0 10 10 0 1 1 20 0z " +
        "M13 12a1 1 0 1 1-2 0 1 1 0 1 1 2 0z"

    const val BELL = "M10.268 21a2 2 0 0 0 3.464 0 " +
        "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8" +
        "c0 4.499-1.411 5.956-2.738 7.326"

    const val MENU = "M4 5h16 M4 12h16 M4 19h16"

    /**
     * Draws one glyph.
     *
     * The parse is remembered on the path string: it walks a few dozen
     * commands and allocates a [Path], which is nothing once but wasteful on
     * every frame of a bar that recomposes with the navigation.
     *
     * @param data One of the path constants above.
     * @param color The stroke colour.
     * @param modifier Layout modifier, which must give the icon a size.
     */
    @Composable
    fun Icon(data: String, color: Color, modifier: Modifier = Modifier) {
        val path: Path = remember(data) { PathParser().parsePathString(data).toPath() }
        Canvas(modifier = modifier) {
            val scale = size.minDimension / GRID
            scale(scale, scale, pivot = Offset.Zero) {
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = STROKE,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}
