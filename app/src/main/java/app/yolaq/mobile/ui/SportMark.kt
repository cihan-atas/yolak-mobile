package app.yolaq.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.yolaq.mobile.recording.SportType

/**
 * The sport, as a mark rather than a word.
 *
 * Drawn rather than shipped as an asset, for the same reasons the logo is (see
 * [AppTopBar]): it takes the theme's colour, stays sharp at any density, and
 * costs nothing to ship. There is no icon set to borrow from either — the
 * bundled Material core set has no sports in it, and pulling in the extended
 * one for four glyphs would be several megabytes of icons nobody asked for.
 *
 * Abstract on purpose: the shapes are read as a group, and four figures that
 * differ only in the angle of a leg would not be told apart at 24dp. The bike
 * and the ridgeline are the shapes of the thing; the two walkers are told
 * apart by posture, which is exactly how they differ in life.
 *
 * @param sport Which mark to draw.
 * @param color The stroke colour.
 * @param size How big.
 * @param modifier Layout modifier.
 */
@Composable
fun SportMark(
    sport: SportType,
    color: Color,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        when (sport) {
            SportType.RUNNING -> drawRunner(color, leaning = true)
            SportType.WALKING -> drawRunner(color, leaning = false)
            SportType.CYCLING -> drawBicycle(color)
            SportType.HIKING -> drawRidge(color)
        }
    }
}

/**
 * A figure, upright for walking and pitched forward for running.
 *
 * @param color The stroke colour.
 * @param leaning Whether the figure is running.
 */
private fun DrawScope.drawRunner(color: Color, leaning: Boolean) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // The lean is the whole difference between the two marks: a runner's mass
    // is ahead of their feet, a walker's is over them.
    val lean = if (leaning) w * 0.12f else 0f

    drawCircle(
        color = color,
        radius = w * 0.11f,
        center = Offset(w * 0.50f + lean, h * 0.17f),
    )
    drawPath(
        path = Path().apply {
            // Spine.
            moveTo(w * 0.48f + lean, h * 0.30f)
            lineTo(w * 0.44f, h * 0.55f)
            // Leading leg.
            moveTo(w * 0.44f, h * 0.55f)
            lineTo(w * 0.30f - lean, h * 0.86f)
            // Trailing leg.
            moveTo(w * 0.44f, h * 0.55f)
            lineTo(w * 0.66f + lean, h * 0.86f)
            // Arm, swung the other way from the leading leg.
            moveTo(w * 0.47f + lean * 0.5f, h * 0.38f)
            lineTo(w * 0.70f + lean, h * 0.48f)
        },
        color = color,
        style = stroke,
    )
}

/** Two wheels and a frame. */
private fun DrawScope.drawBicycle(color: Color) {
    val w = size.width
    val h = size.height
    val radius = w * 0.20f
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    drawCircle(color, radius, Offset(w * 0.22f, h * 0.70f), style = stroke)
    drawCircle(color, radius, Offset(w * 0.78f, h * 0.70f), style = stroke)
    drawPath(
        path = Path().apply {
            moveTo(w * 0.22f, h * 0.70f)
            lineTo(w * 0.46f, h * 0.70f)
            lineTo(w * 0.60f, h * 0.36f)
            lineTo(w * 0.78f, h * 0.70f)
            // Handlebar.
            moveTo(w * 0.52f, h * 0.34f)
            lineTo(w * 0.70f, h * 0.34f)
        },
        color = color,
        style = stroke,
    )
}

/** A ridgeline: the trail's own shape, and what hiking is for. */
private fun DrawScope.drawRidge(color: Color) {
    val w = size.width
    val h = size.height

    drawPath(
        path = Path().apply {
            moveTo(w * 0.06f, h * 0.80f)
            lineTo(w * 0.36f, h * 0.30f)
            lineTo(w * 0.54f, h * 0.58f)
            lineTo(w * 0.68f, h * 0.42f)
            lineTo(w * 0.94f, h * 0.80f)
        },
        color = color,
        style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
