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

    private val listener = LocationListener { location -> onFix(location) }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> {
                RecordingRepository.pause()
                updateNotification()
            }

            ACTION_RESUME -> {
                RecordingRepository.resume()
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
            null -> if (RecordingRepository.state.value.status == RecordingStatus.RECORDING) {
                Log.i(TAG, "Servis yeniden başlatıldı, kayıt sürdürülüyor")
                resumeLocationUpdates()
            } else {
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
        super.onDestroy()
    }

    /** Starts location updates and raises the ongoing notification. */
    private fun startRecording() {
        if (!hasLocationPermission()) {
            // Nothing useful to do without permission; the UI is responsible
            // for asking before it starts us.
            Log.w(TAG, "Konum izni yok, kayıt başlatılamadı")
            stopSelf()
            return
        }

        RecordingRepository.start()
        resumeLocationUpdates()
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
        runCatching { locationManager.removeUpdates(listener) }
        val finished = RecordingRepository.stop()
        Log.i(
            TAG,
            "Kayıt bitti: %.2f km, %d nokta, %d sn".format(
                finished.distanceMeters / 1000.0,
                finished.points.size,
                finished.elapsedMillis() / 1000,
            ),
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
        val point = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            elevation = if (location.hasAltitude()) location.altitude else null,
            speed = if (location.hasSpeed()) location.speed.toDouble() else null,
            accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
            recordedAt = location.time,
        )
        if (RecordingRepository.offer(point)) {
            updateNotification()
        }
    }

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

        const val ACTION_START = "app.yolaq.mobile.START"
        const val ACTION_PAUSE = "app.yolaq.mobile.PAUSE"
        const val ACTION_RESUME = "app.yolaq.mobile.RESUME"
        const val ACTION_STOP = "app.yolaq.mobile.STOP"

        /**
         * Sends a command to the service, starting it if needed.
         *
         * @param context Any context.
         * @param action One of the `ACTION_*` constants.
         */
        fun send(context: Context, action: String) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
