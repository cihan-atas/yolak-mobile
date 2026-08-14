package app.yolaq.mobile.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.yolaq.mobile.R
import app.yolaq.mobile.net.ServerSettings
import app.yolaq.mobile.recording.RecordingRepository
import app.yolaq.mobile.recording.RecordingService
import app.yolaq.mobile.recording.RecordingState
import app.yolaq.mobile.recording.RecordingStatus
import app.yolaq.mobile.recording.SportType
import app.yolaq.mobile.routes.FollowedRoute
import app.yolaq.mobile.routes.RouteGuidance
import app.yolaq.mobile.sync.RecordingFinisher

/**
 * How tall the control panel is, whatever it currently holds.
 *
 * Fixed on purpose. The panel's contents change with every state — sport
 * chooser, live numbers, a signal wait — and when it sized itself to them, the
 * buttons underneath moved a centimetre every time something appeared. On a
 * screen operated with one thumb while moving, a button that moves is a button
 * pressed by mistake, so the height is decided once and the contents live
 * inside it.
 */
private val PANEL_HEIGHT = 176.dp

/**
 * The recorder, opened over the web app.
 *
 * The map is the screen and the panel below it holds everything else: a
 * recording is something glanced at while moving, and stacking numbers,
 * warnings and buttons over the map answered neither "where am I" nor "how am
 * I doing". Only messages that are genuinely about the ground — a lost signal,
 * a route strayed from — float over it now.
 *
 * Signing out lives in the web menu, not here: the recorder is one screen of
 * the app, and account actions belong where every other setting is.
 *
 * @param onClose Returns to the page underneath.
 * @param onNavigate Closes the recorder and opens a page in the web half.
 */
@Composable
fun RecordScreen(onClose: () -> Unit, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val state by RecordingRepository.state.collectAsState()
    val session = remember { ServerSettings.load(context) }

    var hasPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var sport by remember { mutableStateOf(SportType.DEFAULT) }
    var showRoutePicker by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    val followed by FollowedRoute.selected.collectAsState()
    val outcome by RecordingFinisher.lastOutcome.collectAsState()
    val review by RecordingFinisher.pendingReview.collectAsState()
    val saving by RecordingFinisher.awaitingUpload.collectAsState()

    /**
     * Puts the save sheet away without answering it.
     *
     * The recording stays paused and undecided: the athlete gets the recorder
     * back with "Devam et" and "Bitir" where they always are, and pressing
     * "Bitir" again brings this sheet back. A recording recovered from disk has
     * no service holding it, so that one has to be put back by hand.
     */
    fun dismissSheet(resume: Boolean) {
        val pending = review
        if (pending?.live == true) {
            RecordingFinisher.dismissReview()
            if (resume) {
                RecordingService.send(context, RecordingService.ACTION_RESUME)
            }
            return
        }
        // Restored paused, then the service is told to pick the recording back
        // up. Order matters: the service reads the repository's state to draw
        // its notification.
        val resumed = RecordingFinisher.reopen(context) ?: return
        RecordingRepository.reopen(
            points = resumed.points,
            distanceMeters = resumed.distanceMeters,
            movingMillis = resumed.elapsedMillis,
        )
        RecordingService.send(context, RecordingService.ACTION_REOPEN, resumed.sport)
        if (resume) {
            RecordingService.send(context, RecordingService.ACTION_RESUME)
        }
    }

    // Back means "put this away", not "leave the app" — the recording carries
    // on either way, since it lives in the service. The full-screen numbers
    // handle their own back, and close to here rather than to the web app.
    //
    // From the save sheet it means "not now": the outing goes back to being a
    // paused recording rather than being decided by a gesture. It used to be
    // swallowed entirely, which left the athlete stuck on a screen with three
    // buttons and no way past them.
    BackHandler(enabled = !showStats) {
        if (review != null) {
            dismissSheet(resume = false)
        } else {
            onClose()
        }
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

    // The offer to open a landed activity is withdrawn when the recorder
    // closes only in the sense that this screen stops caring: the strip over
    // the page picks the result up instead, so a save made in a tunnel still
    // reports itself tomorrow without opening anything over whatever the
    // athlete is doing then.

    // The numbers are not a place to be stuck: a recording that ends while
    // they are open has a decision waiting behind them.
    LaunchedEffect(review, state.status) {
        if (review != null || state.status == RecordingStatus.IDLE) {
            showStats = false
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

    val controls: @Composable () -> Unit = {
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
    }

    val offRoute = followed?.points?.takeIf { it.isNotEmpty() }?.let { line ->
        state.points.lastOrNull()?.let { here ->
            RouteGuidance.distanceFromRoute(line, here)
                ?.takeIf { it > RouteGuidance.OFF_ROUTE_METERS }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        RecorderLayout(
            state = state,
            session = session,
            sport = sport,
            followed = followed,
            offRoute = offRoute,
            review = review,
            saving = saving,
            showStats = showStats,
            locationEnabled = locationEnabled,
            hasPermission = hasPermission,
            outcome = outcome,
            controls = controls,
            onSportChange = { sport = it },
            onPickRoute = { showRoutePicker = true },
            onExpandStats = { showStats = true },
            onOpenLocationSettings = { openLocationSettings(context) },
            onSave = { name, chosenSport, visibility, description ->
                // Launched on the finisher's own scope, not this screen's: the
                // recorder closes in the same breath, and a GPX write cancelled
                // half way through because the composable it was started from
                // went away would lose the outing it was saving.
                RecordingFinisher.keepAsync(
                    context = context,
                    name = name,
                    sport = chosenSport,
                    description = description,
                    visibility = visibility,
                )
                RecordingService.send(context, RecordingService.ACTION_DISMISS)
                // Straight back to the page the athlete came from, exactly as
                // Strava returns you to the feed. The upload carries on in the
                // background and reports itself in the strip up there.
                onClose()
            },
            onResume = { dismissSheet(resume = true) },
            onDiscard = {
                RecordingFinisher.discard(context)
                RecordingService.send(context, RecordingService.ACTION_DISMISS)
            },
            onNavigate = onNavigate,
        )

        if (showStats) {
            // Drawn over the recorder rather than replacing it. Replacing it
            // took the map out of the composition, which destroyed the web view
            // and reloaded the page — and the track drawn on it went with it,
            // so coming back showed a line starting from wherever the athlete
            // was standing. The map is hidden instead; see RecorderMap.
            StatsFullScreen(
                state = state,
                sport = sport,
                routeName = followed?.name,
                onDismiss = { showStats = false },
                controls = controls,
            )
        }
    }
}

/**
 * The recorder proper: the map, what is on it, and what to do about it.
 *
 * Pulled out of [RecordScreen] so the screen itself is only the state and the
 * decisions, and the layout can be read in one piece.
 *
 * @param state The recording.
 * @param session The signed-in server, or null when the map falls back to the
 *   native canvas.
 * @param sport The chosen sport.
 * @param followed The route being followed, if any.
 * @param offRoute How far off that route, when off it.
 * @param review The recording waiting to be saved, if any.
 * @param saving Whether a save is already under way.
 * @param showStats Whether the full-screen numbers are covering this.
 * @param locationEnabled Whether the phone's location switch is on.
 * @param hasPermission Whether the app may read location.
 * @param outcome What became of the last finished recording.
 * @param controls The record controls.
 * @param onSportChange Picks a different sport.
 * @param onPickRoute Opens the route picker.
 * @param onExpandStats Opens the full-screen numbers.
 * @param onOpenLocationSettings Opens the system's location settings.
 * @param onSave Keeps the recording, with the choices made on the sheet.
 * @param onResume Carries on recording.
 * @param onDiscard Throws the recording away.
 * @param onNavigate Opens a page in the web half.
 */
@Composable
private fun RecorderLayout(
    state: RecordingState,
    session: app.yolaq.mobile.net.ServerConfig?,
    sport: SportType,
    followed: app.yolaq.mobile.routes.FollowableRoute?,
    offRoute: Double?,
    review: RecordingFinisher.Review?,
    saving: Boolean,
    showStats: Boolean,
    locationEnabled: Boolean,
    hasPermission: Boolean,
    outcome: RecordingFinisher.Outcome?,
    controls: @Composable () -> Unit,
    onSportChange: (SportType) -> Unit,
    onPickRoute: () -> Unit,
    onExpandStats: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onSave: (String, SportType, RecordingFinisher.Visibility, String) -> Unit,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        AppTopBar()

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
                    // Hidden, not removed, while the numbers cover the screen.
                    active = !showStats,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                TrackCanvas(
                    points = shown,
                    route = followed?.points.orEmpty(),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Only what is genuinely about the ground underneath. Everything
            // that describes the recording itself moved to the panel, which is
            // what freed the map to be a map.
            MapNotices(
                state = state,
                locationEnabled = locationEnabled,
                hasPermission = hasPermission,
                offRouteMeters = offRoute,
                routeName = followed?.name,
                outcome = outcome,
                onOpenLocationSettings = onOpenLocationSettings,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
            )
        }

        // The sheet takes the panel's place rather than covering the screen:
        // the map stays where it is, frozen on the track just recorded, which
        // is what makes it obvious the outing is paused and still there.
        if (review != null) {
            SaveActivitySheet(
                summary = SaveSummary(
                    distanceMeters = review.distanceMeters,
                    elapsedMillis = review.elapsedMillis,
                    points = review.points,
                    sport = review.sport,
                    defaultName = RecordingFinisher.defaultName(context, review.sport, review.startedAt),
                    live = review.live,
                ),
                busy = saving,
                message = context.getString(R.string.save_saving).takeIf { saving },
                messageIsError = false,
                onSave = onSave,
                onResume = onResume,
                onDiscard = onDiscard,
                modifier = Modifier.heightIn(max = 520.dp),
            )
        } else {
            ControlPanel(
                state = state,
                sport = sport,
                hasRoute = followed != null,
                onSportChange = onSportChange,
                onPickRoute = onPickRoute,
                onExpandStats = onExpandStats,
                controls = controls,
            )

            AppBottomBar(onNavigate = onNavigate)
        }
    }
}

/**
 * The fixed band under the map: what the recording is, and what to do about it.
 *
 * Its contents follow the state — choose a sport, wait for a fix, read the
 * numbers — but its height and the position of the buttons never move.
 *
 * @param state The recording.
 * @param sport The chosen sport.
 * @param hasRoute Whether a route is being followed.
 * @param onSportChange Picks a different sport.
 * @param onPickRoute Opens the route picker.
 * @param onExpandStats Opens the full-screen numbers.
 * @param controls The record controls, always at the foot of the panel.
 */
@Composable
private fun ControlPanel(
    state: RecordingState,
    sport: SportType,
    hasRoute: Boolean,
    onSportChange: (SportType) -> Unit,
    onPickRoute: () -> Unit,
    onExpandStats: () -> Unit,
    controls: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val now = rememberTickingNow(state.status)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(PANEL_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (state.status) {
                RecordingStatus.IDLE -> SportChooser(
                    sport = sport,
                    hasRoute = hasRoute,
                    onSportChange = onSportChange,
                    onPickRoute = onPickRoute,
                )

                // The wait has its own line here rather than over the map,
                // where it used to sit on top of the place being started from.
                RecordingStatus.ACQUIRING -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = context.getString(R.string.record_acquiring),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.lastAccuracy?.let {
                        Text(
                            text = context.getString(R.string.record_acquiring_accuracy, it),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                RecordingStatus.RECORDING,
                RecordingStatus.PAUSED,
                -> StatsStrip(
                    state = state,
                    sport = sport,
                    now = now,
                    onExpand = onExpandStats,
                )
            }
        }
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { controls() }
    }
}

/**
 * What is about to be recorded, and along what.
 *
 * Marks rather than words alone: four text chips in a row are read one at a
 * time, and this is a choice made in the ten seconds before setting off.
 *
 * @param sport The chosen sport.
 * @param hasRoute Whether a route is being followed.
 * @param onSportChange Picks a different sport.
 * @param onPickRoute Opens the route picker.
 */
@Composable
private fun SportChooser(
    sport: SportType,
    hasRoute: Boolean,
    onSportChange: (SportType) -> Unit,
    onPickRoute: () -> Unit,
) {
    val context = LocalContext.current

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SportType.entries.forEach { option ->
                val selected = option == sport
                FilterChip(
                    selected = selected,
                    onClick = { onSportChange(option) },
                    leadingIcon = {
                        SportMark(
                            sport = option,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            size = 18.dp,
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
        TextButton(
            onClick = onPickRoute,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
        ) {
            Text(
                text = context.getString(
                    if (hasRoute) R.string.route_change else R.string.route_choose,
                ),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * The messages that belong over the map, in the order they matter.
 *
 * One at a time, and always in the same corner. Stacking every applicable
 * message is how the screen ended up with four cards pushing each other around
 * — and a missing system switch makes every message below it a lie, so the
 * ranking does the choosing.
 *
 * @param state The recording.
 * @param locationEnabled Whether the phone's location switch is on.
 * @param hasPermission Whether the app may read location.
 * @param offRouteMeters How far off the followed route, when off it.
 * @param routeName The route being followed, if any.
 * @param outcome What became of the last finished recording, if anything.
 * @param onOpenLocationSettings Opens the system's location settings.
 * @param modifier Layout modifier.
 */
@Composable
private fun MapNotices(
    state: RecordingState,
    locationEnabled: Boolean,
    hasPermission: Boolean,
    offRouteMeters: Double?,
    routeName: String?,
    outcome: RecordingFinisher.Outcome?,
    onOpenLocationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            // Ranked first. A missing system switch makes every other message
            // a lie: "waiting for GPS" is waiting for something that cannot
            // arrive.
            !locationEnabled -> OverlayCard {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = context.getString(R.string.record_location_off),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onOpenLocationSettings) {
                        Text(context.getString(R.string.record_location_open))
                    }
                }
            }

            !hasPermission -> Notice(
                text = context.getString(R.string.record_permission_needed),
                error = true,
            )

            // The only message here that reports a recording in trouble rather
            // than a recording in progress.
            state.strandedWithoutFix() -> OverlayCard {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = context.getString(R.string.record_stranded_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = context.getString(R.string.record_stranded_body),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            offRouteMeters != null -> Notice(
                text = context.getString(R.string.route_off, offRouteMeters),
                error = true,
            )

            // Ranked above the weak-signal warning on purpose. Both are true
            // indoors — the receiver is reporting nothing usable, and the
            // distance is not counting — but only this one explains what the
            // recording is actually doing. "Go outside" told an athlete who
            // had deliberately started indoors to go and fix a problem the app
            // had already handled.
            state.awaitingSatellites -> Notice(
                text = context.getString(R.string.record_awaiting_satellites),
                error = false,
            )

            state.weakSignal && state.lastAccuracy != null -> Notice(
                text = context.getString(R.string.record_weak_signal, state.lastAccuracy),
                error = true,
            )

            outcome is RecordingFinisher.Outcome.Queued -> Notice(
                text = context.getString(R.string.outcome_queued, outcome.points),
                error = false,
            )

            outcome is RecordingFinisher.Outcome.TooShort -> Notice(
                text = context.getString(R.string.outcome_too_short),
                error = true,
            )

            routeName != null -> Notice(
                text = context.getString(R.string.route_following, routeName),
                error = false,
            )
        }
    }
}

/** One line over the map. */
@Composable
private fun Notice(text: String, error: Boolean) {
    OverlayCard {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = if (error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * A thin band across the top while an outing is running, shown over the web
 * app rather than in the recorder.
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
fun RecordingStrip(state: RecordingState, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
                else -> context.getString(R.string.strip_recording, state.distanceMeters / 1000.0)
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
