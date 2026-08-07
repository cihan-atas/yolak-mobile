package app.yolaq.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.dp

/**
 * The app's header, matching the one the web half draws.
 *
 * The recorder covers the page completely, so without this the header would
 * disappear whenever recording opened and reappear on leaving — the app
 * flickering between two identities. Same mark, same wordmark, same bar.
 *
 * @param modifier Layout modifier.
 */
@Composable
fun AppTopBar(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TrailMark()
            Text(text = "yolak", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * The logo: a winding trail rising from a trailhead ring.
 *
 * Drawn rather than shipped as an asset so it takes the theme's colour and
 * stays sharp at any density; it is three strokes.
 */
@Composable
private fun TrailMark() {
    val trail = MaterialTheme.colorScheme.primary
    val head = MaterialTheme.colorScheme.secondary

    Canvas(modifier = Modifier.size(24.dp)) {
        val width = size.width
        val height = size.height
        val stroke = width * 0.22f

        drawPath(
            path = Path().apply {
                moveTo(width * 0.28f, height * 0.78f)
                cubicTo(
                    width * 0.28f, height * 0.52f,
                    width * 0.62f, height * 0.60f,
                    width * 0.62f, height * 0.40f,
                )
                cubicTo(
                    width * 0.62f, height * 0.22f,
                    width * 0.80f, height * 0.28f,
                    width * 0.80f, height * 0.12f,
                )
            },
            color = trail,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawCircle(
            color = head,
            radius = width * 0.13f,
            center = Offset(width * 0.28f, height * 0.78f),
        )
    }
}
