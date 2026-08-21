package app.yolaq.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yolaq.mobile.R
import app.yolaq.mobile.recording.RecordingState
import app.yolaq.mobile.recording.RecordingStatus
import app.yolaq.mobile.recording.SportType
import kotlinx.coroutines.delay

/**
 * A clock that ticks on its own.
 *
 * The recording state only changes when a fix lands, so a screen driven purely
 * by it looks frozen on a weak signal — the distance is genuinely not moving,
 * but neither is the duration, and that reads as a crashed app.
 *
 * @param status Ticks only while this is [RecordingStatus.RECORDING].
 * @return Epoch millis, refreshed every second.
 */
@Composable
fun rememberTickingNow(status: RecordingStatus): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status) {
        while (status == RecordingStatus.RECORDING) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }
    return now
}

/**
 * Which number a sport is actually read in.
 *
 * A runner reads minutes per kilometre and a cyclist reads kilometres per
 * hour; showing both everywhere means the one that matters is never the one in
 * the big type. So the sport decides which gets the prominent slot.
 *
 * @return True when pace leads, false when speed does.
 */
private fun SportType.readsPace(): Boolean = this != SportType.CYCLING

/**
 * The live numbers, in the panel below the map.
 *
 * Under the map rather than floating over its top-left corner, where they used
 * to be: over the map they covered exactly the ground being ridden into, and a
 * 250dp-wide card could not hold type big enough to read at arm's length.
 *
 * Tapping or dragging it upwards opens [StatsFullScreen] — the numbers alone,
 * as big as the screen allows, for when the map is not the question.
 *
 * @param state The recording in progress.
 * @param sport What is being recorded, which decides pace against speed.
 * @param now The ticking clock, from [rememberTickingNow].
 * @param onExpand Opens the full-screen numbers.
 * @param modifier Layout modifier.
 */
@Composable
fun StatsStrip(
    state: RecordingState,
    sport: SportType,
    now: Long,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val paceLeads = sport.readsPace()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    // Upwards is negative. The threshold keeps a tap that
                    // wobbles a couple of pixels from counting as a drag.
                    if (dragAmount < -SWIPE_THRESHOLD_PX) {
                        onExpand()
                    }
                }
            }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Metric(
            value = formatKilometres(state.distanceMeters),
            unit = context.getString(R.string.unit_km),
            label = context.getString(R.string.stat_distance),
            large = true,
        )
        Metric(
            value = formatDuration(state.elapsedMillis(now)),
            unit = null,
            label = context.getString(R.string.stat_duration),
        )
        if (paceLeads) {
            Metric(
                value = formatPace(context, state.currentPaceSecondsPerKm),
                unit = context.getString(R.string.unit_per_km),
                label = context.getString(R.string.stat_pace),
            )
        } else {
            Metric(
                value = formatSpeed(context, state.currentSpeed),
                unit = context.getString(R.string.unit_kmh),
                label = context.getString(R.string.stat_speed),
            )
        }
        // The one hint that the strip is a door. Without it nobody would ever
        // discover the full-screen numbers, since nothing else on the panel
        // opens anything.
        Chevron(modifier = Modifier.size(24.dp))
    }
}

/**
 * The numbers, and nothing else.
 *
 * Opened from the strip when the map has stopped being the question — on a
 * track, on a treadmill, or simply in the last kilometre when all anyone wants
 * is the distance in type they can read while moving. The map is not merely
 * hidden here: it is not drawn at all, which is also the cheapest thing this
 * screen can be doing to a battery on a long outing.
 *
 * The controls come along, in the same place they sit on the map screen. An
 * athlete who has to go back to the map to pause is an athlete who does not
 * use this screen twice.
 *
 * @param state The recording in progress.
 * @param sport What is being recorded.
 * @param routeName The route being followed, if any.
 * @param remainingMeters How much of that route is left, when it is known.
 * @param onDismiss Back to the map.
 * @param controls The record controls, drawn at the foot of the screen.
 */
@Composable
fun StatsFullScreen(
    state: RecordingState,
    sport: SportType,
    routeName: String?,
    remainingMeters: Double?,
    onDismiss: () -> Unit,
    controls: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val now = rememberTickingNow(state.status)
    val paceLeads = sport.readsPace()

    BackHandler(enabled = true, onBack = onDismiss)

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Opaque, and the theme's own surface: white in daylight, near
            // black at night. A page that is white either way is a torch in
            // the face on an evening run, and this is a screen people
            // deliberately leave open.
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount > SWIPE_THRESHOLD_PX) {
                        onDismiss()
                    }
                }
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The handle says which way this closes, and matches the drag that
        // opened it.
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .background(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(2.dp),
                )
                .clickable(onClick = onDismiss),
        )
        Spacer(Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SportMark(sport = sport, color = MaterialTheme.colorScheme.primary, size = 20.dp)
            Text(
                // The big number below is distance *covered*; on a followed
                // route the other half of the question is what is left, and
                // this is the screen someone opened to read numbers.
                text = when {
                    routeName == null -> context.getString(sport.labelRes)
                    remainingMeters == null -> routeName
                    else -> context.getString(
                        R.string.route_following_remaining,
                        routeName,
                        formatKilometres(remainingMeters, decimals = 2),
                    )
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = formatKilometres(state.distanceMeters, decimals = 2),
                // Larger than any type scale goes: this is the number the
                // screen exists for, read at arm's length while moving.
                fontSize = 84.sp,
                lineHeight = 88.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = context.getString(R.string.unit_km),
                style = MaterialTheme.typography.titleMedium,
                letterSpacing = 3.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BigMetric(
                    value = formatDuration(state.elapsedMillis(now)),
                    label = context.getString(R.string.stat_duration),
                )
                BigMetric(
                    value = if (paceLeads) {
                        formatPace(context, state.currentPaceSecondsPerKm)
                    } else {
                        formatSpeed(context, state.currentSpeed)
                    },
                    label = context.getString(
                        if (paceLeads) R.string.stat_pace else R.string.stat_speed,
                    ),
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Metric(
                    value = if (paceLeads) {
                        formatPace(context, paceOf(state.averageSpeed))
                    } else {
                        formatSpeed(context, state.averageSpeed)
                    },
                    unit = null,
                    label = context.getString(
                        if (paceLeads) R.string.stat_avg_pace else R.string.stat_avg_speed,
                    ),
                )
                Metric(
                    value = if (paceLeads) {
                        formatSpeed(context, state.currentSpeed)
                    } else {
                        formatPace(context, state.currentPaceSecondsPerKm)
                    },
                    unit = null,
                    label = context.getString(
                        if (paceLeads) R.string.stat_speed else R.string.stat_pace,
                    ),
                )
                Metric(
                    value = state.points.size.toString(),
                    unit = null,
                    label = context.getString(R.string.stat_points),
                )
            }
        }

        controls()
    }
}

/**
 * One number with its name underneath.
 *
 * @param value The number, already formatted.
 * @param unit Its unit, set small beside it, or null when the label says it.
 * @param label What the number is.
 * @param large Whether this is the leading number of its row.
 * @param modifier Layout modifier.
 */
@Composable
private fun Metric(
    value: String,
    unit: String?,
    label: String,
    large: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = if (large) {
                    MaterialTheme.typography.headlineMedium
                } else {
                    MaterialTheme.typography.titleLarge
                },
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            unit?.let {
                Text(
                    text = " $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A metric in the full-screen page's second rank. */
@Composable
private fun BigMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 36.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The upward nudge that says the strip opens. */
@Composable
private fun Chevron(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawPath(
            path = Path().apply {
                moveTo(w * 0.25f, h * 0.60f)
                lineTo(w * 0.50f, h * 0.36f)
                lineTo(w * 0.75f, h * 0.60f)
            },
            color = color,
            style = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * How far a drag has to travel in one frame before it counts as a swipe.
 *
 * In pixels rather than dp because the gesture reports pixels; the value is
 * small enough that a deliberate flick always clears it and large enough that
 * the wobble in a tap never does.
 */
private const val SWIPE_THRESHOLD_PX = 6f
