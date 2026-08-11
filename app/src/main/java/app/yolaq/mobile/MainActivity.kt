package app.yolaq.mobile

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

    /**
     * Writes the web view's cookies to disk before the app can be killed.
     *
     * The session cookie rotates on every use: each refresh mints a new one
     * and the server treats a *replayed* old one as theft, cancelling every
     * session. Android keeps new cookies in memory until something flushes
     * them, so a process killed after a rotation came back holding the
     * previous cookie — and got the account signed out for reuse. That is
     * what "the session drops far too quickly" was.
     */
    override fun onPause() {
        super.onPause()
        android.webkit.CookieManager.getInstance().flush()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A journal left behind means the last process died mid-outing. Doing
        // this on every launch rather than only after a detected crash: there
        // is no reliable crash signal, and the leftover file is the signal.
        // Off the main thread — it reads a file and may write a GPX.
        lifecycleScope.launch(Dispatchers.IO) {
            RecordingFinisher.restore(this@MainActivity)
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
    val pendingReview by RecordingFinisher.pendingReview.collectAsState()

    // A recording nobody has decided about is a question the app owes an
    // answer to, and one it cannot ask from a screen the athlete has to go
    // looking for. It arrives either from a recording just finished or from a
    // process that died holding one, and both open the recorder to ask.
    LaunchedEffect(pendingReview) {
        if (pendingReview != null) {
            showRecorder = true
        }
    }
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

    // Whether the phone's own location switch is on. Nothing this app does
    // works without it — not satellites, not Wi-Fi positioning, not even the
    // cached last position — and the app used to say nothing at all, leaving
    // the athlete on "waiting for GPS" and eventually telling them to go
    // outside to fix a toggle in their own settings.
    var locationEnabled by remember { mutableStateOf(isLocationEnabled(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(received: Context?, intent: Intent?) {
                locationEnabled = isLocationEnabled(context)
            }
        }
        // The system announces the switch being flipped, so the warning can
        // clear itself the moment the athlete acts on it rather than making
        // them come back and look again.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(LocationManager.MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    var sport by remember { mutableStateOf(SportType.DEFAULT) }
    var showRoutePicker by remember { mutableStateOf(false) }
    val followed by FollowedRoute.selected.collectAsState()
    val outcome by RecordingFinisher.lastOutcome.collectAsState()
    val review by RecordingFinisher.pendingReview.collectAsState()
    val handover by RecordingFinisher.handover.collectAsState()
    val scope = rememberCoroutineScope()
    // Driven by the finisher, not by this screen: pressing "Bitir" now hands
    // the recording to the uploader from the service, so the screen learns
    // that a save is in flight rather than being the one to start it.
    val saving by RecordingFinisher.awaitingUpload.collectAsState()
    /** Why the kept recording is not open in its edit form, once it is not. */
    var notDelivered by remember { mutableStateOf<RecordingFinisher.Handover?>(null) }

    // The point of keeping a recording is deciding what it is called, who can
    // see it and what to hide — and all of that already exists as the web
    // app's edit form. So the moment the server has made the activity, the app
    // hands over to it rather than growing a second copy of the same form.
    // Every other ending is the uploader's own word for what happened, which
    // is the only way the athlete gets told the truth about it.
    LaunchedEffect(handover) {
        val result = handover ?: return@LaunchedEffect
        RecordingFinisher.clearHandover()
        if (result is RecordingFinisher.Handover.Uploaded) {
            onNavigate("/activity/${result.activityId}?edit=1")
        } else {
            notDelivered = result
        }
    }

    // The uploader normally answers within a second or two. This is for when
    // it does not answer at all — the run never started, the process holding
    // it was killed — because a spinner with no end is worse than a wrong
    // guess, and the guess is at least labelled as one.
    LaunchedEffect(saving) {
        if (!saving) {
            return@LaunchedEffect
        }
        delay(HANDOVER_WAIT_MILLIS)
        if (saving) {
            RecordingFinisher.clearHandover()
            notDelivered = RecordingFinisher.Handover.Deferred("timeout")
        }
    }

    // Leaving the recorder withdraws the offer: the upload may land tomorrow,
    // and opening an edit form over whatever the athlete is doing then would
    // be the app taking over the screen.
    DisposableEffect(Unit) {
        onDispose { RecordingFinisher.clearHandover() }
    }

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
            // Before the first real fix there is no track to draw, and an
            // empty map indoors is the thing that reads as "the app is
            // broken". The approximate position stands in until satellites
            // arrive — it is a single point, so it centres the map and draws
            // no line anyone could mistake for a route.
            val shown = state.points.ifEmpty { listOfNotNull(state.approximatePosition) }
            if (session != null) {
                RecorderMap(
                    config = session,
                    points = shown,
                    route = followed?.points.orEmpty(),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                TrackCanvas(
                    points = shown,
                    route = followed?.points.orEmpty(),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            StatsOverlay(
                state = state,
                routeName = followed?.name,
                offRouteMeters = offRoute,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp).widthIn(max = 250.dp),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                review?.let { pending ->
                    // Everything else on this screen belongs to a recording
                    // that is running; while this is up, the only questions
                    // are keep or discard.
                    ReviewCard(
                        review = pending,
                        onKeep = {
                            notDelivered = null
                            // Writes the GPX and clears the journal: disk work
                            // that has no business on the main thread while
                            // the card it belongs to is animating away.
                            scope.launch(Dispatchers.IO) { RecordingFinisher.keep(context) }
                        },
                        onDiscard = { RecordingFinisher.discard(context) },
                        onBack = {
                            notDelivered = null
                            // Restored paused, then the service is told to pick
                            // the recording back up. Order matters: the service
                            // reads the repository's state to draw its
                            // notification.
                            val resumed = RecordingFinisher.reopen(context)
                            if (resumed != null) {
                                RecordingRepository.reopen(
                                    points = resumed.points,
                                    distanceMeters = resumed.distanceMeters,
                                    movingMillis = resumed.elapsedMillis,
                                )
                                RecordingService.send(
                                    context,
                                    RecordingService.ACTION_REOPEN,
                                    resumed.sport,
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (saving) {
                    OverlayCard {
                        Text(
                            text = stringResourceCompat(context, R.string.review_uploading),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                notDelivered?.let { result ->
                    OverlayCard {
                        Text(
                            text = handoverMessage(context, result),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = if (result is RecordingFinisher.Handover.Refused) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                outcome?.takeIf { review == null && !saving && notDelivered == null }?.let { last ->
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

                if (state.status == RecordingStatus.IDLE && review == null) {
                    // Left, and no wider than it has to be: this card used to
                    // be a full-width slab in the middle of the screen, which
                    // put the choice of sport over the map at the moment
                    // someone is looking at where they are about to go. It is
                    // a setting you touch once, so it gets a corner — and the
                    // opposite corner from the map's own controls, which now
                    // sit bottom-right.
                    OverlayCard(compact = true, modifier = Modifier.align(Alignment.Start)) {
                        Column {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SportType.entries.forEach { option ->
                                    FilterChip(
                                        selected = option == sport,
                                        onClick = { sport = option },
                                        label = {
                                            Text(
                                                text = context.getString(option.labelRes),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                    )
                                }
                            }
                            TextButton(
                                onClick = { showRoutePicker = true },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp),
                            ) {
                                Text(
                                    text = context.getString(
                                        if (followed == null) {
                                            R.string.route_choose
                                        } else {
                                            R.string.route_change
                                        },
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Not full width: the map draws its attribution mark in the
                // bottom-left corner and its controls in the bottom-right, and
                // a button spanning the screen buried the first of them. The
                // controls it commands are few and centred, so it loses
                // nothing by being narrower than the screen.
                if (review == null) {
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
                    modifier = Modifier.fillMaxWidth(RECORD_CONTROLS_WIDTH_FRACTION),
                )
                }

                // Ranked above everything else the screen might say. A missing
                // system switch makes every other message a lie: "waiting for
                // GPS" is waiting for something that cannot arrive.
                if (!locationEnabled && review == null) {
                    OverlayCard {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResourceCompat(context, R.string.record_location_off),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = { openLocationSettings(context) }) {
                                Text(stringResourceCompat(context, R.string.record_location_open))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (!hasPermission && review == null) {
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
 * Whether the phone will hand out a location at all.
 *
 * Separate from the app's own permission: the athlete can have granted
 * everything and still have the system switch off, in which case every
 * provider is silent and no amount of waiting helps.
 *
 * @param context Any context.
 * @return True when location services are on.
 */
private fun isLocationEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        manager.isLocationEnabled
    } else {
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}

/**
 * Opens the system's location settings.
 *
 * Offered as a button rather than an instruction: "turn on location services"
 * is three taps away through a menu the athlete is not in, and they are
 * standing at the trailhead.
 *
 * @param context Any context.
 */
private fun openLocationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * How much of the screen the record controls take.
 *
 * Enough for two buttons side by side with their labels, and no more: the map
 * puts its attribution in the bottom-left corner and its own controls in the
 * bottom-right, and a full-width button sat on top of the first.
 */
private const val RECORD_CONTROLS_WIDTH_FRACTION = 0.72f

/**
 * How long the app waits for a saved recording to reach the server.
 *
 * Long enough for a slow connection to finish a real outing's GPX, short
 * enough that someone standing in a dead spot is told so rather than left
 * watching a spinner.
 */
private const val HANDOVER_WAIT_MILLIS = 20_000L

/**
 * Says what actually became of a kept recording.
 *
 * Every one of these used to read "Bağlantı yok", including the cases where
 * the connection was fine and the recording was not queued at all — a refused
 * key leaves the file set aside, never to be sent, and telling someone it is
 * waiting for a network is how an outing is quietly lost.
 *
 * @param context Any context, for the strings.
 * @param result How the upload ended.
 * @return The line to show.
 */
private fun handoverMessage(context: Context, result: RecordingFinisher.Handover): String =
    when (result) {
        // Only reached when the answer carried no id to follow; the outing is
        // on the server either way.
        is RecordingFinisher.Handover.Saved,
        is RecordingFinisher.Handover.Uploaded,
        -> context.getString(R.string.review_saved)

        is RecordingFinisher.Handover.Refused ->
            context.getString(R.string.review_refused, result.status)

        is RecordingFinisher.Handover.SignedOut ->
            context.getString(R.string.review_signed_out)

        is RecordingFinisher.Handover.Deferred ->
            context.getString(R.string.review_queued_offline)
    }

/**
 * A translucent slab for anything floating over the map.
 *
 * Readable over streets and parks alike without hiding them: the map is the
 * context these numbers belong to, and a solid panel would take it away.
 *
 * @param modifier Layout modifier — the sport card uses it to sit left while
 *   the record controls stay centred.
 * @param compact Tighter padding, for a card that shares the corner with the
 *   map rather than leading the screen.
 * @param content What sits on the slab.
 */
@Composable
private fun OverlayCard(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.padding(
                horizontal = if (compact) 8.dp else 16.dp,
                vertical = if (compact) 6.dp else 10.dp,
            ),
        ) { content() }
    }
}

/**
 * Renders a duration as hours:minutes:seconds.
 *
 * Always three parts, zero-padded, so the numbers do not jump sideways as the
 * clock passes an hour — the strip is read at a glance while moving.
 *
 * @param millis How long, in milliseconds.
 * @return The formatted clock.
 */
private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    return String.format(
        Locale.US,
        "%02d:%02d:%02d",
        totalSeconds / 3600,
        (totalSeconds % 3600) / 60,
        totalSeconds % 60,
    )
}

/**
 * The question a finished recording asks before it goes anywhere.
 *
 * Pressing "Bitir" used to publish the outing on the spot. That is the one
 * moment it is least decided: it may be a false start, it may want a name, it
 * may be somewhere the athlete would rather not put on a public map. Strava
 * asks here and so does this — and "Kaydet" then hands straight over to the
 * activity's own edit form on the web, where the name, the visibility, the
 * per-metric hiding and the photos already live.
 *
 * @param review The recording waiting to be decided about.
 * @param onKeep Keep it: queue, upload, then open its edit form.
 * @param onDiscard Throw it away.
 * @param onBack Neither: put it back and carry on recording.
 */
@Composable
private fun ReviewCard(
    review: RecordingFinisher.Review,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // Deleting an outing cannot be undone — the track is gone from the phone
    // and was never anywhere else — so the second press is the safeguard.
    var confirmingDiscard by remember { mutableStateOf(false) }

    OverlayCard {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResourceCompat(context, R.string.review_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = context.getString(
                    R.string.review_summary,
                    String.format(Locale.US, "%.2f km", review.distanceMeters / 1000.0),
                    formatDuration(review.elapsedMillis),
                    review.points.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResourceCompat(
                    context,
                    if (confirmingDiscard) R.string.review_discard_confirm else R.string.review_hint,
                ),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = if (confirmingDiscard) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        if (confirmingDiscard) onDiscard() else confirmingDiscard = true
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResourceCompat(context, R.string.review_discard))
                }
                Button(onClick = onKeep, modifier = Modifier.weight(1f)) {
                    Text(stringResourceCompat(context, R.string.review_keep))
                }
            }

            // The third answer, and the one a finish screen most often needs:
            // "Bitir" is frequently pressed by mistake or reconsidered at the
            // door, and without this the only way back was to record a second
            // outing and stitch the two together afterwards.
            TextButton(onClick = onBack) {
                Text(stringResourceCompat(context, R.string.review_back))
            }
        }
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

                // Ranked above the weak-signal warning on purpose. Both are
                // true indoors — the receiver is reporting nothing usable, and
                // the distance is not counting — but only this one explains
                // what the recording is actually doing. "Go outside" told an
                // athlete who had deliberately started indoors to go and fix a
                // problem the app had already handled.
                state.awaitingSatellites -> Text(
                    text = stringResourceCompat(context, R.string.record_awaiting_satellites),
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    when (status) {
        RecordingStatus.IDLE -> Button(onClick = onStart, modifier = modifier.fillMaxWidth()) {
            Text(stringResourceCompat(context, R.string.record_start))
        }

        RecordingStatus.ACQUIRING -> Row(
            modifier = modifier.fillMaxWidth(),
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
            modifier = modifier.fillMaxWidth(),
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
            modifier = modifier.fillMaxWidth(),
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
    val duration = formatDuration(state.elapsedMillis(now))
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
            // Same ordering as the overlay: while the track has no anchor yet,
            // "waiting for satellites" is the state, and a weak-signal warning
            // on top of it is noise about a problem already being described.
            state.awaitingSatellites ->
                context.getString(R.string.record_awaiting_satellites)

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
