package app.yolaq.mobile.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yolaq.mobile.R
import app.yolaq.mobile.recording.RecordingService
import app.yolaq.mobile.recording.RecordingStatus

/**
 * How long "Bitir" has to be held before it fires.
 *
 * Long enough that a pocket brush or a mistimed tap cannot end an outing,
 * short enough that someone standing at the finish line does not wonder
 * whether the button is broken.
 */
private const val HOLD_MILLIS = 900

/** The diameter of the start button. */
private val START_DIAMETER = 88.dp

/** The height every non-circular control shares, so nothing shifts by a pixel. */
private val CONTROL_HEIGHT = 56.dp

/**
 * Start, pause, resume, finish — whichever the recording allows right now.
 *
 * Always the same height whatever the state, and always at the same place on
 * the screen: these are pressed while moving, often without looking properly,
 * and a button that moves between states is a button pressed by mistake.
 *
 * @param status Where the recording sits.
 * @param canOverride Whether the signal wait has dragged on long enough to skip.
 * @param onStart Begins a recording.
 * @param onAction Sends one of the service's other commands.
 * @param modifier Layout modifier.
 */
@Composable
fun RecordControls(
    status: RecordingStatus,
    canOverride: Boolean,
    onStart: () -> Unit,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Box(
        modifier = modifier.fillMaxWidth().height(START_DIAMETER),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            // One target, as big as the panel allows. This is pressed with a
            // thumb at a trailhead, sometimes through a glove, and the old
            // full-width bar was both harder to hit accurately and easy to
            // catch by accident while putting the phone away.
            RecordingStatus.IDLE -> Surface(
                onClick = onStart,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(START_DIAMETER),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = context.getString(R.string.record_start_short),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                }
            }

            RecordingStatus.ACQUIRING -> ControlRow {
                OutlinedButton(
                    onClick = { onAction(RecordingService.ACTION_STOP) },
                    modifier = Modifier.weight(1f).height(CONTROL_HEIGHT),
                ) {
                    Text(context.getString(R.string.record_cancel))
                }
                if (canOverride) {
                    FilledPill(
                        label = context.getString(R.string.record_start_anyway),
                        onClick = { onAction(RecordingService.ACTION_START_ANYWAY) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            RecordingStatus.RECORDING -> ControlRow {
                FilledPill(
                    label = context.getString(R.string.record_pause),
                    onClick = { onAction(RecordingService.ACTION_PAUSE) },
                    modifier = Modifier.weight(1f),
                )
                HoldToFinish(
                    onComplete = { onAction(RecordingService.ACTION_FINISH) },
                    modifier = Modifier.weight(1f),
                )
            }

            RecordingStatus.PAUSED -> ControlRow {
                FilledPill(
                    label = context.getString(R.string.record_resume),
                    onClick = { onAction(RecordingService.ACTION_RESUME) },
                    modifier = Modifier.weight(1f),
                )
                HoldToFinish(
                    onComplete = { onAction(RecordingService.ACTION_FINISH) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Two controls side by side, at the shared control height. */
@Composable
private fun ControlRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(CONTROL_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * A filled control shaped like the hold button beside it, so the pair reads as
 * one row rather than two different kinds of button.
 *
 * @param label What it says.
 * @param onClick What it does.
 * @param modifier Layout modifier.
 */
@Composable
private fun FilledPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.height(CONTROL_HEIGHT),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/**
 * "Bitir", which has to be held down.
 *
 * It no longer ends anything — the recording is paused and the save sheet
 * opens — but the hold stays. A run that appears to have stopped is a run
 * ruined even when it can be put back, and the moment of the accidental tap is
 * not the moment anyone reads a screen. A hold cannot happen in a pocket, and
 * the fill makes the rule visible the first time someone taps instead of
 * holding.
 *
 * @param onComplete Fired once the hold is seen through.
 * @param modifier Layout modifier.
 */
@Composable
private fun HoldToFinish(onComplete: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var holding by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    // Latched so the fill does not visibly rewind under the finger after the
    // recording has already stopped.
    var fired by remember { mutableStateOf(false) }

    LaunchedEffect(holding) {
        if (holding) {
            progress.animateTo(1f, tween(durationMillis = HOLD_MILLIS, easing = LinearEasing))
            fired = true
            onComplete()
        } else {
            fired = false
            progress.animateTo(0f, tween(durationMillis = 160, easing = LinearEasing))
        }
    }

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .height(CONTROL_HEIGHT)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        holding = true
                        tryAwaitRelease()
                        holding = false
                    },
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            // The fill is the timer: it is the only thing that tells someone
            // who tapped why nothing happened, and how much longer to wait.
            if (progress.value > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.value)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
            }
            Text(
                text = context.getString(
                    if (holding && !fired) R.string.record_stop_holding else R.string.record_stop,
                ),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                color = if (progress.value > 0.5f) {
                    MaterialTheme.colorScheme.onError
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}
