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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.lifecycleScope
import app.yolaq.mobile.net.ServerSettings
import app.yolaq.mobile.recording.RecordingRepository
import app.yolaq.mobile.recording.RecordingService
import app.yolaq.mobile.recording.RecordingState
import app.yolaq.mobile.recording.RecordingStatus
import app.yolaq.mobile.recording.SportType
import app.yolaq.mobile.sync.RecordingFinisher
import app.yolaq.mobile.sync.Storage
import app.yolaq.mobile.sync.UploadWorker
import app.yolaq.mobile.ui.TrackCanvas
import app.yolaq.mobile.web.WebScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Entry point. For now it is the recording screen alone — the browsing
 * surfaces arrive later as a WebView over the existing web app, so the only
 * thing written natively is what a browser cannot do.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A journal left behind means the last process died mid-outing. Doing
        // this on every launch rather than only after a detected crash: there
        // is no reliable crash signal, and the leftover file is the signal.
        // Off the main thread — it reads a file and may write a GPX.
        lifecycleScope.launch(Dispatchers.IO) {
            RecordingFinisher.recoverInterrupted(this@MainActivity)
            // Anything stranded by a previous failure gets another chance the
            // moment the app is opened, without waiting for the next outing.
            UploadWorker.schedule(this@MainActivity)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    YolakApp()
                }
            }
        }
    }
}

/** The two halves of the app. */
private enum class Tab { RECORD, WEB }

/**
 * The shell: recording on one tab, the web app on the other.
 *
 * Both tabs stay alive rather than being swapped out — the web view keeps its
 * page and scroll position, and the recording screen is never rebuilt from
 * scratch mid-outing.
 */
@Composable
private fun YolakApp() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(Tab.RECORD) }
    val state by RecordingRepository.state.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.RECORD,
                    onClick = { tab = Tab.RECORD },
                    icon = {},
                    label = { Text(context.getString(R.string.tab_record)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.WEB,
                    onClick = { tab = Tab.WEB },
                    icon = {},
                    label = { Text(context.getString(R.string.tab_web)) },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Kept in the tree while hidden: rebuilding the web view on every
            // tab switch would reload the page and lose the user's place, and
            // rebuilding the recording screen mid-outing would restart its
            // clock ticker for no reason.
            Box(modifier = if (tab == Tab.RECORD) Modifier.fillMaxSize() else Modifier.size(0.dp)) {
                RecordScreen()
            }
            Box(modifier = if (tab == Tab.WEB) Modifier.fillMaxSize() else Modifier.size(0.dp)) {
                WebScreen()
            }
        }
    }

    // Recording is the reason the app exists: if an outing starts while the
    // web tab is open, the numbers should be what comes back into view.
    LaunchedEffect(state.status) {
        if (state.status == RecordingStatus.ACQUIRING) {
            tab = Tab.RECORD
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

    var sport by remember { mutableStateOf(SportType.DEFAULT) }
    var showSettings by remember { mutableStateOf(false) }

    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        hasPermission = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasPermission) {
            RecordingService.send(context, RecordingService.ACTION_START, sport)
        }
    }

    if (showSettings) {
        ServerSettingsDialog(onDismiss = { showSettings = false })
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

        if (state.status == RecordingStatus.ACQUIRING) {
            AcquiringNotice(state)
        } else {
            Metrics(state)
            if (state.status != RecordingStatus.IDLE) {
                Spacer(Modifier.height(16.dp))
                // The shape of the line is the quickest answer to "is this
                // thing actually recording?" — the one question worth asking
                // mid-outing.
                TrackCanvas(state.points)
            }
        }

        Spacer(Modifier.height(32.dp))

        when (state.status) {
            // Waiting for a usable fix. No metrics yet and no pause button —
            // there is nothing to pause — but always a way out, and after a
            // while a way past, since indoors the threshold may never be met.
            RecordingStatus.ACQUIRING -> {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(state.status) {
                    while (state.status == RecordingStatus.ACQUIRING) {
                        now = System.currentTimeMillis()
                        delay(1_000)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { RecordingService.send(context, RecordingService.ACTION_STOP) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResourceCompat(context, R.string.record_cancel))
                    }
                    if (state.canOverrideAcquire(now)) {
                        Button(
                            onClick = {
                                RecordingService.send(
                                    context,
                                    RecordingService.ACTION_START_ANYWAY,
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResourceCompat(context, R.string.record_start_anyway))
                        }
                    }
                }
            }

            // The sport is chosen before starting rather than corrected on the
            // web afterwards: it travels in the GPX, and an activity that
            // arrives as a generic workout drops out of the sport filters on
            // challenges and segments.
            RecordingStatus.IDLE -> Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SportType.entries.forEach { option ->
                        FilterChip(
                            selected = option == sport,
                            onClick = { sport = option },
                            label = { Text(context.getString(option.labelRes)) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (hasPermission) {
                            RecordingService.send(context, RecordingService.ACTION_START, sport)
                        } else {
                            requestPermissions.launch(requiredPermissions())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResourceCompat(context, R.string.record_start))
                }
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

        Spacer(Modifier.height(24.dp))
        PendingUploads()

        TextButton(onClick = { showSettings = true }) {
            Text(stringResourceCompat(context, R.string.settings_open))
        }
    }
}

/**
 * How many finished recordings are still waiting to reach the server.
 *
 * Shown because a silent queue is indistinguishable from a lost outing: someone
 * who finishes a run in a dead spot needs to see that the track exists and is
 * waiting, not wonder whether it was ever saved.
 */
@Composable
private fun PendingUploads() {
    val context = LocalContext.current
    var count by remember { mutableStateOf(0) }

    // Polled rather than observed: the queue changes from a background worker
    // in another process lifetime, and a few seconds of staleness on a status
    // line is not worth a broadcast to keep in sync.
    LaunchedEffect(Unit) {
        while (true) {
            count = withContext(Dispatchers.IO) { Storage.queue(context).pending().size }
            delay(5_000)
        }
    }

    if (count > 0) {
        Text(
            text = context.getString(R.string.upload_pending, count),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Server address and API key.
 *
 * A key rather than a username and password: the uploader runs from a
 * background worker with no screen to log in on, and the server already issues
 * keys scoped to `activities:upload` for exactly this. A real login arrives
 * with the WebView shell.
 *
 * @param onDismiss Closes the dialog.
 */
@Composable
private fun ServerSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val existing = remember { ServerSettings.load(context) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(existing?.apiKey ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.settings_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(context.getString(R.string.settings_server)) },
                    placeholder = { Text("yolaq.app") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(context.getString(R.string.settings_api_key)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = context.getString(R.string.settings_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    ServerSettings.save(context, baseUrl, apiKey)
                    // Whatever was stranded for want of a server can go now.
                    UploadWorker.schedule(context)
                    onDismiss()
                },
            ) {
                Text(context.getString(R.string.settings_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.record_cancel))
            }
        },
    )
}

/**
 * The wait for a fix good enough to record from.
 *
 * Shows the accuracy it is waiting on rather than a bare spinner: an unexplained
 * delay reads as a broken app, while a number that visibly falls reads as
 * progress — and explains why the wait is worth it.
 */
@Composable
private fun AcquiringNotice(state: RecordingState) {
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResourceCompat(context, R.string.record_acquiring),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(12.dp))
        state.lastAccuracy?.let {
            Text(
                text = context.getString(R.string.record_acquiring_accuracy, it),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = stringResourceCompat(context, R.string.record_acquiring_hint),
            style = MaterialTheme.typography.bodySmall,
        )
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
