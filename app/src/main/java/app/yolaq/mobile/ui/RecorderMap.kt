package app.yolaq.mobile.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.util.Log
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.yolaq.mobile.net.ServerConfig
import app.yolaq.mobile.recording.TrackPoint
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "RecorderMap"

/**
 * How often to ask whether the page has registered its API yet.
 *
 * `onPageFinished` fires when the document has loaded, which is *before* the
 * Vue app mounts and defines `window.yolak`. Seeding the track on that signal
 * alone threw the whole track away: the call landed on a page that had no API
 * to receive it, was silently dropped, and nothing ever sent the track again —
 * only the per-fix position updates, so the map drew a fresh line from
 * wherever the athlete happened to be standing.
 */
private const val API_POLL_MS = 150L

/** How long to keep asking before giving the page up as broken. */
private const val API_WAIT_MS = 20_000L

/**
 * The live map behind the recording screen.
 *
 * Hosts the web app's own map page rather than drawing one natively. The
 * reasons are the same ones that made the whole app a web shell: the basemap
 * is a self-hosted vector archive that Android has no ready renderer for, and
 * a second map implementation would be a second thing to keep in step with
 * every styling change. The recorder stays native where it must be — reading
 * GPS with the screen off — and borrows the map it already has.
 *
 * Positions are pushed in as they arrive rather than the page polling for
 * them: the fix is already in hand here, and a page asking for it would add
 * latency to the one thing that has to feel immediate.
 *
 * @param config Where the web app lives.
 * @param points The track recorded so far.
 * @param route The route being followed, if any.
 * @param active Whether the map is the thing being looked at. Hidden rather
 *   than removed when it is not — see the `update` block.
 * @param modifier Layout modifier.
 */
@Composable
fun RecorderMap(
    config: ServerConfig,
    points: List<TrackPoint>,
    route: List<TrackPoint>,
    active: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    /**
     * The view, once Compose has built it.
     *
     * Held in state rather than in `remember` and handed to `factory`: a
     * remembered View belongs to whoever added it first, and when this screen
     * was rebuilt Compose tried to attach the same instance to a second
     * parent — "the specified child already has a parent", and the app died
     * the moment the recorder opened. Letting the factory own it means Compose
     * creates and releases it, and this is only a handle for talking to it.
     */
    var web by remember { mutableStateOf<WebView?>(null) }

    /** Whether the document has loaded. Not the same as being able to talk to it. */
    var pageLoaded by remember { mutableStateOf(false) }

    /** Whether `window.yolak` exists, which is when calls actually land. */
    var apiReady by remember { mutableStateOf(false) }

    // Waits for the page's own API rather than trusting the load event; see
    // API_POLL_MS for the outing this cost.
    LaunchedEffect(pageLoaded, web) {
        val view = web ?: return@LaunchedEffect
        if (!pageLoaded) {
            return@LaunchedEffect
        }
        val deadline = System.currentTimeMillis() + API_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (view.hasRecorderApi()) {
                apiReady = true
                return@LaunchedEffect
            }
            delay(API_POLL_MS)
        }
        Log.w(TAG, "Harita sayfası API'sini kaydetmedi, iz çizilemeyecek")
    }

    // The whole state is pushed once the page can receive it, not just the next
    // fix. Fixes that arrived while it was still loading were being dropped on
    // the floor: indoors, where a single fix may be all there is, the map sat
    // on the empty world view and looked broken.
    //
    // Re-run when the map comes back into view as well. It is a local call
    // costing nothing, and it is the one thing that guarantees the line on
    // screen is the whole outing rather than whatever arrived last.
    LaunchedEffect(apiReady, web, active) {
        val view = web ?: return@LaunchedEffect
        if (!apiReady || !active) {
            return@LaunchedEffect
        }
        if (route.isNotEmpty()) {
            view.call("setRoute(${route.toJsArray()})")
        }
        if (points.isNotEmpty()) {
            view.call("setTrack(${points.toJsArray()})")
            points.last().let {
                view.call(String.format(Locale.US, "setPosition(%.6f, %.6f)", it.latitude, it.longitude))
            }
        } else {
            // Nothing recorded yet, so the map has no idea where to look and
            // opens on the whole world — the first thing anyone sees when they
            // tap record. The receiver's last known fix is usually metres away
            // and costs nothing to ask for.
            lastKnownPosition(context)?.let { (lat, lng) ->
                view.call(String.format(Locale.US, "setPosition(%.6f, %.6f)", lat, lng))
                // A stale fix is a hint about where to look, not part of the
                // outing; clearing the line keeps it out of the track.
                view.call("setTrack([])")
            }
        }
    }

    // Fed whenever the route changes, including the moment one is chosen
    // mid-screen. Sent whole because a route does not grow point by point.
    LaunchedEffect(route, apiReady, web) {
        val view = web ?: return@LaunchedEffect
        if (route.isEmpty() || !apiReady) {
            return@LaunchedEffect
        }
        view.call("setRoute(${route.toJsArray()})")
    }

    // Only the newest fix is pushed; the page keeps the line it has been
    // given. Re-sending the whole track on every fix would grow to megabytes
    // of JavaScript per second on a long outing.
    //
    // Skipped entirely while the map is hidden: the page would append every
    // one of them to a line nobody is looking at, and the seeding effect above
    // replaces the whole track when it comes back anyway.
    LaunchedEffect(points.size, apiReady, web, active) {
        val view = web ?: return@LaunchedEffect
        if (!apiReady || !active) {
            return@LaunchedEffect
        }
        val last = points.lastOrNull() ?: return@LaunchedEffect
        view.call(String.format(Locale.US, "setPosition(%.6f, %.6f)", last.latitude, last.longitude))
    }

    // The map's own controls — where am I, layers, 3D — are drawn by the page,
    // bottom-right, exactly as they are on every other map in yolak. There used
    // to be a native "where am I" button painted over this corner instead,
    // which is how the recorder ended up with one control while the activity
    // page and the route editor had different ones.
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                @SuppressLint("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                // Matches the shell so the page knows it is inside the app.
                settings.userAgentString = "${settings.userAgentString} yolak-app/1"
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        pageLoaded = true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String,
                        callback: GeolocationPermissions.Callback,
                    ) {
                        // The map's "where am I" button asks the page, the page
                        // asks the browser, and a WebView with no prompt handler
                        // says no without saying anything. On this screen the
                        // app is already reading GPS itself, so the answer is
                        // always yes when Android has granted the permission.
                        val allowed = context.checkSelfPermission(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED
                        callback.invoke(origin, allowed, false)
                    }
                }
                loadUrl("${config.baseUrl}/embed/recorder-map")
                web = this
            }
        },
        // Hidden, never removed. Taking the map out of the composition destroys
        // the view, and a destroyed view means a reloaded page: the drawn track
        // goes with it, the map reopens on the world, and the outing appears to
        // restart from wherever the athlete is standing. A hidden WebView keeps
        // its map, its zoom and its line for nothing.
        update = { view -> view.visibility = if (active) View.VISIBLE else View.GONE },
        onRelease = { view ->
            web = null
            pageLoaded = false
            apiReady = false
            view.destroy()
        },
    )
}

/**
 * Whether the page has defined the API the recorder drives it through.
 *
 * @return True once `window.yolak` exists.
 */
private suspend fun WebView.hasRecorderApi(): Boolean = suspendCancellableCoroutine { waiting ->
    evaluateJavascript("!!(window.yolak);") { result ->
        waiting.resume(result == "true")
    }
}

/**
 * Calls a function on the page's recorder API.
 *
 * Guarded because the page may still be loading when the first fix lands, and
 * a missing object would throw inside the web view for every fix after it.
 *
 * @param invocation The call, e.g. `setPosition(41.0, 29.0)`.
 */
private fun WebView.call(invocation: String) {
    evaluateJavascript("window.yolak && window.yolak.$invocation;") { result ->
        if (result == "null") {
            // The page has not registered its API yet; the next fix will land.
            Log.d(TAG, "Harita henüz hazır değil: $invocation")
        }
    }
}

/**
 * Renders points as a JavaScript array of `[lat, lng]` pairs.
 *
 * Locale-fixed: a Turkish phone would otherwise write `41,0` and the array
 * would parse as twice as many numbers.
 *
 * @return The array literal.
 */
private fun List<TrackPoint>.toJsArray(): String = joinToString(
    prefix = "[",
    postfix = "]",
) { String.format(Locale.US, "[%.6f,%.6f]", it.latitude, it.longitude) }

/**
 * The receiver's last known position, if it has one.
 *
 * Only ever used to point the map somewhere sensible before recording starts.
 * It may be minutes old and hundreds of metres out, which is fine for choosing
 * a view and useless for a track — so it never becomes one.
 *
 * @param context Any context.
 * @return Latitude and longitude, or null when nothing is known or permission
 *   was withdrawn between the screen opening and this call.
 */
private fun lastKnownPosition(context: android.content.Context): Pair<Double, Double>? {
    // Asked before the recorder has permission as often as after: the map is
    // drawn the moment the screen opens, and the permission prompt only
    // appears when the athlete presses start.
    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return null
    }
    val manager = context.getSystemService(android.content.Context.LOCATION_SERVICE)
        as? android.location.LocationManager ?: return null
    return runCatching {
        // GPS first, then the network provider: indoors the network fix is
        // often the only one there is, and a rough position beats the world.
        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
        )
        providers.firstNotNullOfOrNull { provider ->
            manager.getLastKnownLocation(provider)?.let { it.latitude to it.longitude }
        }
    }.getOrNull()
}
