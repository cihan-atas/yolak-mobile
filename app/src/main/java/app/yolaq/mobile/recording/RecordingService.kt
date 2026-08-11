package app.yolaq.mobile.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.yolaq.mobile.MainActivity
import app.yolaq.mobile.R
import app.yolaq.mobile.live.LiveBroadcaster
import app.yolaq.mobile.net.ServerSettings
import app.yolaq.mobile.sync.RecordingFinisher
import app.yolaq.mobile.sync.Storage
import app.yolaq.mobile.sync.TrackJournal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Records GPS while the app is in the background or the screen is off.
 *
 * A foreground service is the only way Android will keep delivering location
 * once the screen locks — which is exactly the case this whole app exists for,
 * since a browser cannot do it. The persistent notification is the price the
 * platform charges, and it doubles as the "still recording" reminder.
 *
 * Location comes from the platform [LocationManager] rather than Play
 * Services' fused provider: it keeps the app free of Google dependencies (so
 * an F-Droid build stays possible) at the cost of slightly less clever
 * batching, which matters little when a recording wants every fix anyway.
 */
class RecordingService : Service() {

    private lateinit var locationManager: LocationManager

    private lateinit var journal: TrackJournal

    /**
     * Outlives individual commands but dies with the service, which is exactly
     * the lifetime broadcasting should have.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Null when no server is configured, or once the server has refused. */
    private var broadcaster: LiveBroadcaster? = null

    /** What is being recorded; needed at the end, to write the GPX. */
    private var sport: SportType = SportType.DEFAULT

    private val listener = LocationListener { location -> onFix(location) }

    /** Reads the accelerometer; see [MotionWindow] for why this exists. */
    private val motionWindow = MotionWindow()

    private var sensorManager: SensorManager? = null

    /**
     * Feeds acceleration into the window and publishes the verdict.
     *
     * Sampled at the "normal" rate rather than the fastest available: the
     * question is whether the phone has been carried in the last few seconds,
     * and answering it fifty times a second would cost battery for a number
     * that changes on the scale of a footstep.
     */
    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            motionWindow.add(
                MotionWindow.magnitude(event.values[0], event.values[1], event.values[2]),
                System.currentTimeMillis(),
            )
            RecordingRepository.setDeviceMoving(motionWindow.isMoving())
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Starts watching the accelerometer.
     *
     * A phone with no accelerometer, or one whose sensor refuses to register,
     * simply leaves the recorder as it was before this existed: the repository
     * defaults to "moving", so the GPS filter carries on alone rather than the
     * recording measuring nothing.
     */
    private fun startMotionUpdates() {
        motionWindow.reset()
        RecordingRepository.setDeviceMoving(true)
        val manager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || accelerometer == null) {
            Log.w(TAG, "İvmeölçer yok, hareket denetimi devre dışı")
            return
        }
        sensorManager = manager
        manager.registerListener(motionListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    /** Stops watching the accelerometer. */
    private fun stopMotionUpdates() {
        runCatching { sensorManager?.unregisterListener(motionListener) }
        sensorManager = null
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        journal = Storage.journal(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every command is logged: a recording that ends by itself is the
        // worst failure this app has, and the first question is always whether
        // something asked it to stop or it died on its own.
        Log.i(TAG, "Komut: ${intent?.action ?: "(yeniden başlatma)"} flags=$flags startId=$startId")
        when (intent?.action) {
            ACTION_START -> startRecording(SportType.fromName(intent.getStringExtra(EXTRA_SPORT)))
            ACTION_PAUSE -> {
                RecordingRepository.pause()
                updateNotification()
            }

            ACTION_RESUME -> {
                RecordingRepository.resume()
                updateNotification()
            }

            // Back from the finish screen. The recording is restored paused,
            // so this has to raise the notification and subscribe again — the
            // service was torn down when "Bitir" was pressed, and a service
            // started without startForeground is killed within seconds.
            ACTION_REOPEN -> {
                sport = SportType.fromName(intent?.getStringExtra(EXTRA_SPORT))
                startBroadcasting()
                resumeLocationUpdates()
                updateNotification()
            }

            ACTION_START_ANYWAY -> {
                RecordingRepository.startAnyway()
                updateNotification()
            }

            ACTION_STOP -> {
                stopRecording()
                return START_NOT_STICKY
            }

            // A null action means the system restarted us after a kill (see
            // START_STICKY below). Resume if a recording is still open,
            // otherwise stand down rather than lingering as a service that
            // holds a notification and records nothing.
            null -> if (RecordingRepository.state.value.status in
                setOf(RecordingStatus.RECORDING, RecordingStatus.ACQUIRING)
            ) {
                Log.i(TAG, "Servis yeniden başlatıldı, kayıt sürdürülüyor")
                startBroadcasting()
                resumeLocationUpdates()
            } else {
                // The whole process was killed, not just the service: the
                // in-memory recording is gone, but the journal survived it.
                // Bank what was recorded rather than resuming into a state
                // whose distance and clock no longer exist.
                RecordingFinisher.restore(this)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Restart if the system kills us mid-recording: losing an outing to a
        // low-memory kill would be the worst possible failure for this app.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(listener) }
        stopMotionUpdates()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Starts location updates and raises the ongoing notification.
     *
     * @param sport What the athlete is about to do.
     */
    private fun startRecording(sport: SportType) {
        if (!hasLocationPermission()) {
            // Nothing useful to do without permission; the UI is responsible
            // for asking before it starts us.
            Log.w(TAG, "Konum izni yok, kayıt başlatılamadı")
            stopSelf()
            return
        }

        this.sport = sport
        journal.begin(sport)
        startBroadcasting()
        RecordingRepository.start()
        resumeLocationUpdates()
    }

    /**
     * Begins streaming to the server, if one is configured.
     *
     * An unconfigured app still records: the recording is the point, and live
     * viewing is something the athlete may never have set up. Refusing to start
     * without a server would make the recorder useless to someone who just
     * wants a track.
     */
    private fun startBroadcasting() {
        val config = ServerSettings.load(this)
        if (config == null) {
            Log.i(TAG, "Sunucu ayarlı değil, canlı yayın yapılmayacak")
            broadcaster = null
            return
        }
        broadcaster = LiveBroadcaster(config, scope).apply { start() }
    }

    /**
     * Raises the ongoing notification and subscribes to location.
     *
     * Split out from [startRecording] so a service restarted by the system can
     * pick an open recording back up without discarding it, which starting over
     * would do.
     */
    private fun resumeLocationUpdates() {
        startForeground(NOTIFICATION_ID, buildNotification())
        startMotionUpdates()
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                listener,
            )
        }.onFailure { error ->
            Log.e(TAG, "Konum güncellemeleri başlatılamadı", error)
        }

        // Wi-Fi and cell towers as well as satellites. Indoors the satellite
        // receiver reports *nothing at all* — not a poor fix, silence — which
        // is why the wait screen used to sit there with no accuracy to show
        // and no way past it. This provider answers in seconds under a roof.
        // It is the same thing the fused provider does for other apps, minus
        // the Play Services dependency this app deliberately avoids.
        runCatching {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    APPROXIMATE_INTERVAL_MS,
                    0f,
                    listener,
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Yaklaşık konum sağlayıcısı açılamadı", error)
        }

        seedApproximatePosition()
    }

    /**
     * Hands over the last position the platform already knew about.
     *
     * Both providers take time to answer, and until one does the screen has
     * nothing to draw. The phone almost always has a recent position cached
     * from some other app; using it means the map opens on the athlete rather
     * than on an empty square, at the cost of nothing — it is offered as an
     * approximate position, so it can never reach the track.
     */
    private fun seedApproximatePosition() {
        if (!hasLocationPermission()) {
            return
        }
        val cached = runCatching {
            listOfNotNull(
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
            )
                // Stale beyond this it is a different outing, possibly a
                // different city, and worse than showing nothing.
                .filter { System.currentTimeMillis() - it.time <= MAX_CACHED_FIX_AGE_MS }
                .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
        }.getOrNull() ?: return

        RecordingRepository.offerApproximate(cached.toTrackPoint())
    }

    /**
     * Stops updates, closes the recording, and leaves the foreground.
     *
     * Closing the recording is the part that must not be skipped: the state is
     * held by [RecordingRepository], not by this service, so tearing the
     * service down without it leaves the screen showing a running clock and
     * "Bitir" while no location is being requested at all — the app claiming to
     * record when it is not, which is the worst thing it could do.
     */
    private fun stopRecording() {
        // Cancelling from the signal-wait is not a failed recording; it never
        // started. Remembered before stop() resets the state.
        val cancelledBeforeStart = RecordingRepository.state.value.status == RecordingStatus.ACQUIRING
        runCatching { locationManager.removeUpdates(listener) }
        broadcaster?.stop()
        broadcaster = null

        val finished = RecordingRepository.stop()
        Log.i(
            TAG,
            "Kayıt bitti: %.2f km, %d nokta, %d sn".format(
                finished.distanceMeters / 1000.0,
                finished.points.size,
                finished.elapsedMillis() / 1000,
            ),
        )

        // Handed over for the athlete to keep or throw away rather than
        // uploaded on the spot. The journal is deliberately left in place: it
        // is what makes that decision survive the app being killed, and it is
        // cleared by whichever way the decision goes.
        RecordingFinisher.hold(
            context = this,
            points = finished.points,
            sport = sport,
            distanceMeters = finished.distanceMeters,
            elapsedMillis = finished.elapsedMillis(),
            reportOutcome = !cancelledBeforeStart,
            // Pressing "Bitir" is the decision. Everything after it — the
            // name, the visibility, the photos, and throwing it away — lives
            // in the activity's own edit form, so the recording goes up and
            // the screen opens that form instead of asking the same questions
            // twice in two different places.
            keepImmediately = true,
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Hands a platform fix to the repository and refreshes the notification.
     *
     * @param location The fix reported by the provider.
     */
    private fun onFix(location: Location) {
        val point = location.toTrackPoint()

        // Only satellites are allowed near the track. A Wi-Fi position is
        // accurate to tens of metres and drifts between access points without
        // anyone moving; letting one into the distance is how a phone sitting
        // on a table records a kilometre.
        if (location.provider != LocationManager.GPS_PROVIDER) {
            if (RecordingRepository.offerApproximate(point)) {
                updateNotification()
            }
            return
        }

        val accepted = RecordingRepository.offer(point)
        if (accepted) {
            // Write before broadcasting: the journal is what makes the outing
            // survivable, and it must not wait behind a network call.
            runCatching { journal.append(point) }
                .onFailure { error -> Log.e(TAG, "Nokta diske yazılamadı", error) }
            broadcaster?.offer(point)
        }
        // While acquiring, a rejected fix still changes what the notification
        // should say — it carries the accuracy the user is waiting on.
        if (accepted || RecordingRepository.state.value.status == RecordingStatus.ACQUIRING) {
            updateNotification()
        }
    }

    /**
     * Converts a platform fix into the app's own point.
     *
     * @return The point, with a hopeless accuracy when the fix carried none.
     */
    private fun Location.toTrackPoint(): TrackPoint = TrackPoint(
        latitude = latitude,
        longitude = longitude,
        elevation = if (hasAltitude()) altitude else null,
        speed = if (hasSpeed()) speed.toDouble() else null,
        accuracy = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
        recordedAt = time,
    )

    /** Whether the user has granted foreground location access. */
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Creates the notification channel the ongoing notification posts to. */
    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording_channel_name),
            // Low importance: the notification must exist for the service to
            // run, but it should not buzz on every fix.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.recording_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Builds the ongoing notification from the current recording state. */
    private fun buildNotification(): Notification {
        val state = RecordingRepository.state.value
        val km = state.distanceMeters / 1000.0
        // elapsedMillis, not movingMillis: the latter only advances on pause or
        // stop, so the notification would sit at "0 dk" for a whole outing.
        val minutes = state.elapsedMillis() / 60_000

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = when {
            state.status == RecordingStatus.ACQUIRING ->
                state.lastAccuracy?.let { getString(R.string.recording_acquiring_accuracy, it) }
                    ?: getString(R.string.recording_waiting_for_fix)

            state.weakSignal -> getString(R.string.recording_weak_signal)
            state.waitingForFix -> getString(R.string.recording_waiting_for_fix)
            state.status == RecordingStatus.PAUSED -> getString(R.string.recording_paused)
            else -> getString(R.string.recording_progress, km, minutes)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /** Refreshes the ongoing notification in place. */
    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        /**
         * Ask for a fix every second and on every metre. Both are floors, not
         * promises — the provider gives what it has. A recording wants dense
         * data; thinning happens later, in the repository's jitter filter.
         */
        private const val MIN_INTERVAL_MS = 1_000L
        private const val MIN_DISTANCE_M = 1f

        /**
         * How often to accept an approximate position.
         *
         * Far slower than the satellite stream: these only answer "roughly
         * where are we" for the map and the start gate, and asking Wi-Fi
         * scanning to run every second would cost battery for a number that
         * barely changes.
         */
        private const val APPROXIMATE_INTERVAL_MS = 10_000L

        /**
         * How old a cached position may be and still be worth showing.
         *
         * Beyond a few minutes it is likely to be somewhere the athlete no
         * longer is, and a map centred confidently on the wrong place is
         * worse than one that admits it is waiting.
         */
        private const val MAX_CACHED_FIX_AGE_MS = 5 * 60 * 1000L

        /** Intent extra carrying the [SportType] name on [ACTION_START]. */
        const val EXTRA_SPORT = "sport"

        const val ACTION_START = "app.yolaq.mobile.START"
        const val ACTION_START_ANYWAY = "app.yolaq.mobile.START_ANYWAY"
        const val ACTION_PAUSE = "app.yolaq.mobile.PAUSE"
        const val ACTION_RESUME = "app.yolaq.mobile.RESUME"
        const val ACTION_STOP = "app.yolaq.mobile.STOP"
        const val ACTION_REOPEN = "app.yolaq.mobile.REOPEN"

        /**
         * Sends a command to the service, starting it if needed.
         *
         * @param context Any context.
         * @param action One of the `ACTION_*` constants.
         * @param sport The sport to record; only read on [ACTION_START].
         */
        fun send(context: Context, action: String, sport: SportType? = null) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            sport?.let { intent.putExtra(EXTRA_SPORT, it.name) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
