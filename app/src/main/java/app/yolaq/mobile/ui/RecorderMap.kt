package app.yolaq.mobile.ui

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.yolaq.mobile.R
import app.yolaq.mobile.net.ServerConfig
import app.yolaq.mobile.recording.TrackPoint
import java.util.Locale

private const val TAG = "RecorderMap"

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
 * @param modifier Layout modifier.
 */
@Composable
fun RecorderMap(
    config: ServerConfig,
    points: List<TrackPoint>,
    route: List<TrackPoint>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Whether the page has finished loading and registered its API.
    var pageReady by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            @SuppressLint("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Matches the shell so the page knows it is inside the app.
            settings.userAgentString = "${settings.userAgentString} yolak-app/1"
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    pageReady = true
                }
            }
            loadUrl("${config.baseUrl}/embed/recorder-map")
        }
    }

    // The whole state is pushed once the page is ready, not just the next fix.
    // Fixes that arrived while it was still loading were being dropped on the
    // floor: indoors, where a single fix may be all there is, the map sat on
    // the empty world view and looked broken.
    LaunchedEffect(pageReady) {
        if (!pageReady) {
            return@LaunchedEffect
        }
        if (points.isEmpty()) {
            // Nothing recorded yet, so the map has no idea where to look and
            // opens on the whole world — which is the first thing anyone sees
            // when they tap record. The receiver's last known fix is usually
            // metres away and costs nothing to ask for.
            lastKnownPosition(context)?.let { (lat, lng) ->
                webView.call(String.format(Locale.US, "setPosition(%.6f, %.6f)", lat, lng))
                // A single stale fix is a hint about where to look, not part
                // of the outing; clearing the line keeps it out of the track.
                webView.call("setTrack([])")
            }
        }
        if (route.isNotEmpty()) {
            webView.call("setRoute(${route.toJsArray()})")
        }
        if (points.isNotEmpty()) {
            webView.call("setTrack(${points.toJsArray()})")
            points.last().let {
                webView.call(
                    String.format(Locale.US, "setPosition(%.6f, %.6f)", it.latitude, it.longitude),
                )
            }
        }
    }

    // Fed whenever the route changes, including the moment one is chosen
    // mid-screen. Sent as a whole because a route does not grow point by point.
    LaunchedEffect(route, pageReady) {
        if (route.isEmpty() || !pageReady) {
            return@LaunchedEffect
        }
        webView.call("setRoute(${route.toJsArray()})")
    }

    // Only the newest fix is pushed; the page keeps the line it has been
    // given. Re-sending the whole track on every fix would grow to megabytes
    // of JavaScript per second on a long outing.
    LaunchedEffect(points.size, pageReady) {
        if (!pageReady) {
            return@LaunchedEffect
        }
        val last = points.lastOrNull() ?: return@LaunchedEffect
        webView.call(
            String.format(Locale.US, "setPosition(%.6f, %.6f)", last.latitude, last.longitude),
        )
    }

    DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }

    Box(modifier = modifier) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { webView })

        // Panning the map is how you look ahead; without a way back, the only
        // way to find yourself again is to guess. Bottom-right, clear of the
        // numbers above and the controls below.
        FilledTonalButton(
            onClick = { webView.call("centerOnMe()") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        ) {
            Text(LocalContext.current.getString(R.string.map_recenter))
        }
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
