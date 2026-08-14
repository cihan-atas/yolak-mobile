package app.yolaq.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.yolaq.mobile.R
import app.yolaq.mobile.recording.SportType
import app.yolaq.mobile.recording.TrackPoint
import app.yolaq.mobile.sync.RecordingFinisher

/**
 * What a held recording amounts to, for the sheet that asks about it.
 *
 * @property distanceMeters How far.
 * @property elapsedMillis How long, moving.
 * @property points The track itself.
 * @property sport What it was recorded as.
 * @property defaultName What it will be called if the athlete types nothing.
 * @property live Whether the recording is merely paused, so carrying on is a
 *   resume rather than a rebuild.
 */
data class SaveSummary(
    val distanceMeters: Double,
    val elapsedMillis: Long,
    val points: List<TrackPoint>,
    val sport: SportType,
    val defaultName: String,
    val live: Boolean,
)

/**
 * The decisions a finished outing needs, asked before it goes anywhere.
 *
 * "Bitir" does not end a recording any more; it pauses it and opens this. That
 * is the order Strava uses — pause, finish, then a save screen you can still
 * back out of — and it is the order that matches what people actually do at
 * the end of an outing: stop moving, look at the numbers, then decide.
 *
 * What is asked here is what the athlete knows at that moment and nothing
 * else: what to call it, what it was, who may see it, and anything worth
 * writing down. The name, the sport and the note travel inside the GPX, so
 * they arrive with the upload rather than as a second request that can fail on
 * its own; only the visibility waits for the activity to exist.
 *
 * Sits under the map rather than over it: the track is still on screen, frozen
 * where the athlete stopped, which is what makes it obvious that the recording
 * has not gone anywhere and can still be carried on.
 *
 * @param summary The recording waiting to be decided about.
 * @param busy Whether a save is already under way.
 * @param message What became of it, once there is something to say.
 * @param messageIsError Whether that message is bad news.
 * @param onSave Keeps it, with the choices made here.
 * @param onResume Carries on recording.
 * @param onDiscard Throws it away.
 * @param modifier Layout modifier.
 */
@Composable
fun SaveActivitySheet(
    summary: SaveSummary,
    busy: Boolean,
    message: String?,
    messageIsError: Boolean,
    onSave: (name: String, sport: SportType, visibility: RecordingFinisher.Visibility, description: String) -> Unit,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var name by remember { mutableStateOf(summary.defaultName) }
    var sport by remember { mutableStateOf(summary.sport) }
    var visibility by remember { mutableStateOf(RecordingFinisher.Visibility.DEFAULT) }
    var description by remember { mutableStateOf("") }
    // Deleting an outing cannot be undone — the track is on the phone and
    // nowhere else — so it is asked as a question rather than done on a tap.
    var confirmingDiscard by remember { mutableStateOf(false) }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(context.getString(R.string.save_discard_title)) },
            text = { Text(context.getString(R.string.save_discard_body)) },
            confirmButton = {
                TextButton(onClick = { confirmingDiscard = false; onDiscard() }) {
                    Text(
                        text = context.getString(R.string.save_discard),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = false }) {
                    Text(context.getString(R.string.record_cancel))
                }
            },
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Says the sheet belongs to the screen behind it rather than being
            // a different place the app has jumped to.
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
            Spacer(Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SportMark(sport = sport, color = MaterialTheme.colorScheme.primary, size = 20.dp)
                Text(
                    text = context.getString(
                        if (summary.live) R.string.save_title else R.string.save_title_recovered,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(12.dp))

            // The three numbers an athlete looks at first, and the only ones
            // worth the width. The point count that used to sit here is a
            // diagnostic, not something anyone decides on.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Figure(
                    value = formatKilometres(summary.distanceMeters, decimals = 2),
                    label = context.getString(R.string.unit_km),
                )
                Figure(
                    value = formatDuration(summary.elapsedMillis),
                    label = context.getString(R.string.stat_duration),
                )
                val averageSpeed = summary.distanceMeters
                    .takeIf { summary.elapsedMillis > 0 }
                    ?.div(summary.elapsedMillis / 1000.0)
                Figure(
                    value = if (sport == SportType.CYCLING) {
                        formatSpeed(context, averageSpeed)
                    } else {
                        formatPace(context, paceOf(averageSpeed))
                    },
                    label = context.getString(
                        if (sport == SportType.CYCLING) R.string.stat_avg_speed else R.string.stat_avg_pace,
                    ),
                )
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(context.getString(R.string.save_name)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            // Offered again here because the sport is chosen in the ten seconds
            // before setting off, which is exactly when it is chosen wrongly.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SportType.entries.forEach { option ->
                    val selected = option == sport
                    FilterChip(
                        selected = selected,
                        onClick = { sport = option },
                        enabled = !busy,
                        leadingIcon = {
                            SportMark(
                                sport = option,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                size = 16.dp,
                            )
                        },
                        label = {
                            Text(
                                text = context.getString(option.labelRes),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = context.getString(R.string.save_visibility),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                VisibilityChoice.entries.forEach { choice ->
                    FilterChip(
                        selected = visibility == choice.value,
                        onClick = { visibility = choice.value },
                        enabled = !busy,
                        label = {
                            Text(
                                text = context.getString(choice.labelRes),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(context.getString(R.string.save_description)) },
                enabled = !busy,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            message?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = if (messageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { onSave(name, sport, visibility, description) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(
                    text = context.getString(
                        if (busy) R.string.save_saving else R.string.save_keep,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            Spacer(Modifier.height(8.dp))

            // The answer a finish screen most often needs. It used to be the
            // smallest thing on the card; here it is a button, because "Bitir"
            // is pressed by mistake and reconsidered at the door in equal
            // measure.
            OutlinedButton(
                onClick = onResume,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(context.getString(R.string.record_resume))
            }

            // Destructive, so it is the quietest thing here and still asks.
            TextButton(
                onClick = { confirmingDiscard = true },
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(
                    text = context.getString(R.string.save_discard),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * The visibility options, in the order they are least to most private.
 *
 * @property value What the finisher stores.
 * @property labelRes What it is called on screen.
 */
private enum class VisibilityChoice(
    val value: RecordingFinisher.Visibility,
    val labelRes: Int,
) {
    // First, and the one selected to begin with: an athlete who does not touch
    // this wants whatever their profile already says, and quietly overriding
    // that would be the app deciding something it was not asked to.
    DEFAULT(RecordingFinisher.Visibility.DEFAULT, R.string.visibility_default),
    PUBLIC(RecordingFinisher.Visibility.PUBLIC, R.string.visibility_public),
    FOLLOWERS(RecordingFinisher.Visibility.FOLLOWERS, R.string.visibility_followers),
    PRIVATE(RecordingFinisher.Visibility.PRIVATE, R.string.visibility_private),
}

/** One figure in the summary row. */
@Composable
private fun Figure(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 26.sp,
            lineHeight = 30.sp,
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
