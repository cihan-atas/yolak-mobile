package app.yolaq.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.yolaq.mobile.recording.RecordingRepository
import app.yolaq.mobile.recording.RecordingService
import app.yolaq.mobile.recording.RecordingState
import app.yolaq.mobile.recording.RecordingStatus
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Entry point. For now it is the recording screen alone — the browsing
 * surfaces arrive later as a WebView over the existing web app, so the only
 * thing written natively is what a browser cannot do.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RecordScreen()
                }
            }
        }
    }
}

/** Permissions the recorder needs before the service can start. */
private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
private fun RecordScreen() {
    val context = LocalContext.current
    val state by RecordingRepository.state.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        hasPermission = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasPermission) {
            RecordingService.send(context, RecordingService.ACTION_START)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "yolak", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        Metrics(state)

        Spacer(Modifier.height(32.dp))

        when (state.status) {
            RecordingStatus.IDLE -> Button(
                onClick = {
                    if (hasPermission) {
                        RecordingService.send(context, RecordingService.ACTION_START)
                    } else {
                        requestPermissions.launch(requiredPermissions())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResourceCompat(context, R.string.record_start))
            }

            RecordingStatus.RECORDING -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { RecordingService.send(context, RecordingService.ACTION_PAUSE) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResourceCompat(context, R.string.record_pause))
                }
                Button(
                    onClick = { RecordingService.send(context, RecordingService.ACTION_STOP) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResourceCompat(context, R.string.record_stop))
                }
            }

            RecordingStatus.PAUSED -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { RecordingService.send(context, RecordingService.ACTION_RESUME) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResourceCompat(context, R.string.record_resume))
                }
                OutlinedButton(
                    onClick = { RecordingService.send(context, RecordingService.ACTION_STOP) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResourceCompat(context, R.string.record_stop))
                }
            }
        }

        if (!hasPermission) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResourceCompat(context, R.string.record_permission_needed),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The live numbers: distance, duration, speed, and the raw fix count. */
@Composable
private fun Metrics(state: RecordingState) {
    // The clock has to tick on its own: the state only changes when a fix
    // lands, and a recording with a weak signal would otherwise look frozen.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.status) {
        while (state.status == RecordingStatus.RECORDING) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val km = state.distanceMeters / 1000.0
    val totalSeconds = state.elapsedMillis(now) / 1000
    val duration = String.format(
        Locale.US,
        "%02d:%02d:%02d",
        totalSeconds / 3600,
        (totalSeconds % 3600) / 60,
        totalSeconds % 60,
    )
    val context = LocalContext.current
    // km/h, not km/s — the value is metres per second times 3.6.
    val speed = state.currentSpeed
        ?.let { context.getString(R.string.record_speed_value, it * 3.6) }
        ?: "—"
    // Runners read pace, so it gets the prominent line and speed sits beside it.
    val pace = state.currentPaceSecondsPerKm?.let {
        val seconds = it.toInt()
        context.getString(R.string.record_pace_value, seconds / 60, seconds % 60)
    } ?: context.getString(R.string.record_pace_idle)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = String.format(Locale.US, "%.2f km", km),
            style = MaterialTheme.typography.displaySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(text = duration, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(text = pace, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(text = speed, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        // Signal quality is developer-and-user facing: while the recorder is
        // being proven on real outings, "is GPS arriving, and is it good
        // enough?" is the question that decides whether a track exists at all.
        val status = when {
            state.weakSignal && state.lastAccuracy != null ->
                context.getString(R.string.record_weak_signal, state.lastAccuracy)

            state.waitingForFix -> context.getString(R.string.record_waiting)

            else -> context.getString(
                R.string.record_points_count,
                state.points.size,
                state.lastAccuracy ?: 0f,
            )
        }
        Text(text = status, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Reads a string resource without pulling in the Compose resource APIs, which
 * keeps this screen buildable from a plain context in previews and tests.
 *
 * @param context Any context.
 * @param id The string resource id.
 * @return The resolved string.
 */
private fun stringResourceCompat(context: android.content.Context, id: Int): String =
    context.getString(id)
