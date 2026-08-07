package app.yolaq.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button

import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.yolaq.mobile.net.ServerSettings
import app.yolaq.mobile.recording.RecordingRepository
import app.yolaq.mobile.recording.RecordingService
import app.yolaq.mobile.recording.RecordingState
import app.yolaq.mobile.recording.RecordingStatus
import app.yolaq.mobile.recording.SportType
import app.yolaq.mobile.routes.FollowedRoute
import app.yolaq.mobile.routes.RouteGuidance
import app.yolaq.mobile.sync.RecordingFinisher
import app.yolaq.mobile.sync.Storage
import app.yolaq.mobile.sync.UploadWorker
import app.yolaq.mobile.ui.AppBottomBar
import app.yolaq.mobile.ui.AppTopBar
import app.yolaq.mobile.ui.LoginScreen
import app.yolaq.mobile.ui.RecorderMap
import app.yolaq.mobile.ui.RoutePicker
import app.yolaq.mobile.ui.RouteStatus
import app.yolaq.mobile.ui.TrackCanvas
import app.yolaq.mobile.ui.YolakTheme
import app.yolaq.mobile.web.WebScreen
import app.yolaq.mobile.web.WebSession
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
            YolakTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    YolakApp()
                }
            }
        }
    }
}

/**
 * The shell: yolak as it already is, with recording inside it.
 *
 * The app *is* the web app — same screens, same navigation, nothing rebuilt in
 * Compose and nothing to keep in sync. A second tab would have split one
 * product into two halves and made the phone version the odd one out.
 *
 * Recording is the single thing a browser cannot do, so it is the single thing
 * that is native: a button over the page opens it, and it closes back onto the
 * page it came from.
 */
@Composable
private fun YolakApp() {
    val context = LocalContext.current
    val state by RecordingRepository.state.collectAsState()
    var showRecorder by remember { mutableStateOf(false) }
    // Handed over by the web half once its view exists; lets the recorder's
    // bottom bar move the page underneath instead of merely closing.
    var navigateWeb by remember { mutableStateOf<((String) -> Unit)?>(null) }

    // Nothing in the app works signed out — not uploading, not live tracking,
    // not the web tab — so the login screen stands in front of all of it
    // rather than being a setting to find later.
    var session by remember { mutableStateOf(ServerSettings.load(context)) }
    if (session == null) {
        LoginScreen(
            onSignedIn = {
                session = ServerSettings.load(context)
                // Anything recorded before signing in can go up now.
                UploadWorker.schedule(context)
            },
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Always composed, so the page and the user's place in it survive a
        // trip to the recorder. It hides itself through the view's own
        // visibility; see WebScreen for why nothing gentler works.
        WebScreen(
            visible = !showRecorder,
            onRecordRequested = { showRecorder = true },
            onNavigatorReady = { navigateWeb = it },
        )

        if (showRecorder) {
            RecordScreen(
                onClose = { showRecorder = false },
                onNavigate = { path ->
                    showRecorder = false
                    navigateWeb?.invoke(path)
                },
            )
        } else if (state.status != RecordingStatus.IDLE) {
            // Only while an outing is running. Idle, the way in is the record
            // entry in the page's own navigation — floating furniture over
            // someone else's layout is exactly what this replaced.
            RecordingStrip(
                state = state,
                onClick = { showRecorder = true },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    // An outing beginning is worth interrupting the page for — and once it is
    // running, the numbers are what the user came back to the phone to see.
    LaunchedEffect(state.status) {
        if (state.status == RecordingStatus.ACQUIRING) {
            showRecorder = true
        }
    }
}

/**
 * A thin band across the top while an outing is running.
 *
 * Someone who wandered off into the feed mid-run needs to see at a glance that
 * the recording is still going — that reassurance is worth a strip of screen,
 * where an idle button hovering over the page was not.
 *
 * @param state The recording in progress.
 * @param onClick Opens the recorder.
 * @param modifier Layout modifier.
 */
@Composable
private fun RecordingStrip(state: RecordingState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(
            text = when (state.status) {
                RecordingStatus.ACQUIRING -> context.getString(R.string.record_acquiring_short)
                RecordingStatus.PAUSED -> context.getString(R.string.recording_paused)
                else -> context.getString(
                    R.string.strip_recording,
                    state.distanceMeters / 1000.0,
                )
            },
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
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

/**
 * The recorder, opened over the web app.
 *
 * Signing out lives in the web menu, not here: the recorder is one screen of
 * the app, and account actions belong where every other setting is.
 *
 * @param onClose Returns to the page underneath.
 * @param onNavigate Closes the recorder and opens a page in the web half.
 */
@Composable
private fun RecordScreen(onClose: () -> Unit, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val state by RecordingRepository.state.collectAsState()
    val session = remember { ServerSettings.load(context) }

    // Back means "put this away", not "leave the app" — the recording carries
    // on either way, since it lives in the service.
    BackHandler(enabled = true, onBack = onClose)

    var hasPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    var sport by remember { mutableStateOf(SportType.DEFAULT) }
    var showRoutePicker by remember { mutableStateOf(false) }
    val followed by FollowedRoute.selected.collectAsState()
    val outcome by RecordingFinisher.lastOutcome.collectAsState()

    val offRoute = followed?.points?.takeIf { it.isNotEmpty() }?.let { line ->
        state.points.lastOrNull()?.let { here ->
            RouteGuidance.distanceFromRoute(line, here)
                ?.takeIf { it > RouteGuidance.OFF_ROUTE_METERS }
        }
    }

    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        hasPermission = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasPermission) {
            RecordingFinisher.clearOutcome()
            RecordingService.send(context, RecordingService.ACTION_START, sport)
        }
    }

    if (showRoutePicker) {
        RoutePicker(
            onDismiss = { showRoutePicker = false },
            onSelected = { route ->
                FollowedRoute.select(route)
                showRoutePicker = false
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        AppTopBar()

        // The map is the screen, not a panel on it. Everything else floats
        // over it: a recording is something you glance at while moving, and a
        // postage-stamp map with the numbers stacked underneath answers
        // neither "where am I" nor "how am I doing" at arm's length.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (session != null) {
                RecorderMap(
                    config = session,
                    points = state.points,
                    route = followed?.points.orEmpty(),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                TrackCanvas(
                    points = state.points,
                    route = followed?.points.orEmpty(),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            StatsOverlay(
                state = state,
                routeName = followed?.name,
                offRouteMeters = offRoute,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                outcome?.let { last ->
                    OverlayCard {
                        Text(
                            text = when (last) {
                                is RecordingFinisher.Outcome.Queued ->
                                    context.getString(R.string.outcome_queued, last.points)

                                is RecordingFinisher.Outcome.TooShort ->
                                    context.getString(R.string.outcome_too_short)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = when (last) {
                                is RecordingFinisher.Outcome.Queued ->
                                    MaterialTheme.colorScheme.onSurfaceVariant

                                is RecordingFinisher.Outcome.TooShort ->
                                    MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (state.status == RecordingStatus.IDLE) {
                    OverlayCard {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SportType.entries.forEach { option ->
                                    FilterChip(
                                        selected = option == sport,
                                        onClick = { sport = option },
                                        label = { Text(context.getString(option.labelRes)) },
                                    )
                                }
                            }
                            TextButton(onClick = { showRoutePicker = true }) {
                                Text(
                                    context.getString(
                                        if (followed == null) {
                                            R.string.route_choose
                                        } else {
                                            R.string.route_change
                                        },
                                    ),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                RecordControls(
                    status = state.status,
                    canOverride = state.canOverrideAcquire(),
                    onStart = {
                        if (hasPermission) {
                            RecordingFinisher.clearOutcome()
                            RecordingService.send(context, RecordingService.ACTION_START, sport)
                        } else {
                            requestPermissions.launch(requiredPermissions())
                        }
                    },
                    onAction = { action -> RecordingService.send(context, action) },
                )

                if (!hasPermission) {
                    Spacer(Modifier.height(8.dp))
                    OverlayCard {
                        Text(
                            text = stringResourceCompat(context, R.string.record_permission_needed),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        AppBottomBar(onNavigate = onNavigate)
    }
}

/**
 * A translucent slab for anything floating over the map.
 *
 * Readable over streets and parks alike without hiding them: the map is the
 * context these numbers belong to, and a solid panel would take it away.
 *
 * @param content What sits on the slab.
 */
@Composable
private fun OverlayCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { content() }
    }
}

/**
 * The live numbers, over the map.
 *
 * Distance leads because it is the one people look for; time and pace sit
 * beside it. While the receiver is still settling this says so instead,
 * because a row of zeroes reads as a broken recording.
 *
 * @param state The recording in progress.
 * @param routeName The route being followed, if any.
 * @param offRouteMeters How far off that route, when off it.
 * @param modifier Layout modifier.
 */
@Composable
private fun StatsOverlay(
    state: RecordingState,
    routeName: String?,
    offRouteMeters: Double?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // The clock has to tick on its own: state only changes when a fix lands,
    // and a recording with a weak signal would otherwise look frozen.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.status) {
        while (state.status == RecordingStatus.RECORDING) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    OverlayCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.status == RecordingStatus.ACQUIRING) {
                Text(
                    text = state.lastAccuracy
                        ?.let { context.getString(R.string.record_acquiring_accuracy, it) }
                        ?: stringResourceCompat(context, R.string.record_acquiring),
                    style = MaterialTheme.typography.titleMedium,
                )
                return@Column
            }

            val totalSeconds = state.elapsedMillis(now) / 1000
            Text(
                text = String.format(Locale.US, "%.3f km", state.distanceMeters / 1000.0),
                style = MaterialTheme.typography.displaySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(
                    text = String.format(
                        Locale.US,
                        "%02d:%02d:%02d",
                        totalSeconds / 3600,
                        (totalSeconds % 3600) / 60,
                        totalSeconds % 60,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = state.currentPaceSecondsPerKm?.let {
                        val seconds = it.toInt()
                        context.getString(R.string.record_pace_value, seconds / 60, seconds % 60)
                    } ?: stringResourceCompat(context, R.string.record_pace_idle),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            when {
                offRouteMeters != null -> Text(
                    text = context.getString(R.string.route_off, offRouteMeters),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                routeName != null -> Text(
                    text = context.getString(R.string.route_following, routeName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                state.weakSignal && state.lastAccuracy != null -> Text(
                    text = context.getString(R.string.record_weak_signal, state.lastAccuracy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> Text(
                    text = context.getString(
                        R.string.record_points_count,
                        state.points.size,
                        state.lastAccuracy ?: 0f,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Start, pause, resume, finish — whichever the recording allows right now.
 *
 * @param status Where the recording sits.
 * @param canOverride Whether the signal wait has dragged on long enough to skip.
 * @param onStart Begins a recording.
 * @param onAction Sends one of the service's other commands.
 */
@Composable
private fun RecordControls(
    status: RecordingStatus,
    canOverride: Boolean,
    onStart: () -> Unit,
    onAction: (String) -> Unit,
) {
    val context = LocalContext.current

    when (status) {
        RecordingStatus.IDLE -> Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResourceCompat(context, R.string.record_start))
        }

        RecordingStatus.ACQUIRING -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { onAction(RecordingService.ACTION_STOP) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResourceCompat(context, R.string.record_cancel))
            }
            if (canOverride) {
                Button(
                    onClick = { onAction(RecordingService.ACTION_START_ANYWAY) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResourceCompat(context, R.string.record_start_anyway))
                }
            }
        }

        RecordingStatus.RECORDING -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { onAction(RecordingService.ACTION_PAUSE) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResourceCompat(context, R.string.record_pause))
            }
            Button(
                onClick = { onAction(RecordingService.ACTION_STOP) },
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
                onClick = { onAction(RecordingService.ACTION_RESUME) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResourceCompat(context, R.string.record_resume))
            }
            OutlinedButton(
                onClick = { onAction(RecordingService.ACTION_STOP) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResourceCompat(context, R.string.record_stop))
            }
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
            // Three decimals: at two, a slow walk's first hundred metres tick
            // over in jumps of 10 m and the screen looks stuck. Metre
            // resolution shows the distance actually moving.
            text = String.format(Locale.US, "%.3f km", km),
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
