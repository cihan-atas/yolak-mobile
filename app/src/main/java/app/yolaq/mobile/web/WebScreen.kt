package app.yolaq.mobile.web

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.Manifest
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.yolaq.mobile.R
import app.yolaq.mobile.net.ServerSettings
import app.yolaq.mobile.share.ShareCard

/** URL scheme the page uses to ask for the native recorder. */
private const val RECORD_SCHEME = "yolak"

private const val TAG = "WebScreen"

/**
 * The rest of yolak, as the web app already implements it.
 *
 * Activities, segments, challenges, routes and settings all exist and are
 * maintained on the web; rewriting those screens in Compose would mean
 * carrying every feature twice. What a browser genuinely cannot do — record
 * GPS with the screen off — is the native part, and this is everything else.
 *
 * Stays in the composition even while the record tab is shown — leaving would
 * destroy the WebView and reload the page on every tab switch. But a WebView
 * is a platform view: it is a real child of the window, drawn above Compose
 * content, and no Compose-side trick (zero size, alpha, z-order) reliably
 * hides it. The only switch that works is the view's own visibility, hence
 * [visible] rather than conditional composition.
 *
 * @param visible Whether the page is the thing on screen.
 * @param onRecordRequested Called when the page's record entry is tapped.
 * @param onShareRequested Called with an activity's figures and track when the
 *   page's share entry is tapped, to open the native image composer.
 * @param onSignedOut Called when the page logs out, so the native half can
 *   forget its own credentials too. The page cannot do this itself: the upload
 *   key lives in Android's preferences, out of reach of anything in a browser.
 * @param onNavigatorReady Hands back a function that navigates the page, so
 *   the recorder's own bar can move the app underneath it.
 * @param modifier Layout modifier.
 */
@Composable
fun WebScreen(
    visible: Boolean,
    onRecordRequested: () -> Unit,
    onShareRequested: (ShareCard) -> Unit = {},
    onSignedOut: () -> Unit = {},
    onNavigatorReady: ((String) -> Unit) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Read on every composition, not remembered: the user may configure the
    // server while this tab is already alive, and a cached null would leave
    // the tab stuck on "no server" until the app is killed. A prefs read per
    // recomposition is nothing, and recompositions here are tab switches.
    val config = ServerSettings.load(context)

    // The web view outlives the composition that built it, so the callback it
    // captured would be a stale one after any recomposition; this keeps the
    // bridge pointing at the current handler.
    val shareRequested by rememberUpdatedState(onShareRequested)
    val signedOut by rememberUpdatedState(onSignedOut)

    if (config == null) {
        if (!visible) {
            return
        }
        // Nowhere to go yet. Saying so beats a blank page or a DNS error.
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = context.getString(R.string.web_no_server),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = context.getString(R.string.web_no_server_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }

    // Whether the web view has history of its own. Tracked as state because
    // `canGoBack()` is not observable, and the back handler must be disabled
    // when it is false — otherwise back stops working entirely on this tab and
    // the only way out of the app is the home button.
    var canGoBack by remember { mutableStateOf(false) }

    // Keyed on the config: pointing the app at a different server must
    // rebuild the page, but ordinary recompositions keep it — and with it the
    // session and scroll position.
    // A WebView refuses every <input type="file"> unless the host app opens the
    // picker for it — silently, with no error anywhere. That is what made "add
    // activity" look broken: the button worked, the file dialog simply never
    // came, so uploading a GPX from the phone was impossible.
    var pendingFiles by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // The callback must be answered even when the user backs out, or the
        // page's file input stays wedged and never opens again.
        pendingFiles?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
        pendingFiles = null
    }

    val webView = remember(config) {
        // Before the first load: the page reads the session cookie during
        // bootstrap, so planting it afterwards would be one reload too late.
        WebSession.install(context)
        WebView(context).apply {
            // Explicit MATCH_PARENT, not the default. Compose hands a view
            // without layout params WRAP_CONTENT ones, and a WebView measured
            // that way reports a CSS viewport with no height: `vh` units and
            // percentage heights resolve to zero while the view still paints
            // full screen. Full-height sections then collapse with no error
            // anywhere — the territory map came through as 1.4px of border
            // beside a perfectly correct scoreboard, and the same cause once
            // emptied every form dialog (see FormDialog's min-height guard).
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            @SuppressLint("SetJavaScriptEnabled")
            // The web app is a Vue SPA: without scripting there is no page at
            // all. It only ever loads our own server (see the client below).
            settings.javaScriptEnabled = true
            // The session cookie and the app's local state live here; without
            // storage every launch would land back on the login screen.
            settings.domStorageEnabled = true
            // A bare WebView ignores <meta name="viewport"> — it is off by
            // default, unlike every actual browser. The page then lays out
            // against a viewport that is not the one it was written for:
            // responsive rules land on the wrong side, full-height sections
            // stop reaching the bottom of the screen, and wide rows spill off
            // the edge. Both of these together are what a browser does.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // How the page knows it is inside the app, and so whether to offer
            // the recorder at all — a browser cannot record with the screen
            // off, so the entry would be a dead button anywhere else.
            settings.userAgentString = "${settings.userAgentString} yolak-app/1"

            // How the activity page hands an outing to the native image
            // composer. A bridge object rather than another custom URL: the
            // payload carries the whole track, which is far past what a URL
            // can hold, and the page already has it loaded to draw its own map
            // — so nothing has to be fetched a second time and the feature
            // needs no read scope on the upload key.
            //
            // Safe to expose here because this view only ever loads our own
            // server (see the client below), and since API 17 only annotated
            // methods are reachable from JavaScript at all.
            addJavascriptInterface(
                object {
                    @android.webkit.JavascriptInterface
                    fun share(payload: String) {
                        val card = ShareCard.fromJson(payload) ?: return
                        // The bridge is called on a WebView worker thread;
                        // opening a screen from there would crash Compose.
                        post { shareRequested(card) }
                    }

                    /**
                     * The page's logout, extended to this half of the app.
                     *
                     * A bridge call rather than watching for the login route:
                     * `shouldOverrideUrlLoading` never fires on the SPA's own
                     * navigation — nothing is loaded — and even if it did, an
                     * expired session lands on the same route without anyone
                     * having asked to sign out.
                     */
                    @android.webkit.JavascriptInterface
                    fun signOut() {
                        post { signedOut() }
                    }
                },
                "yolakApp",
            )

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val target = request.url
                    // The page's own record entry. A custom scheme rather than
                    // a real route: nothing needs to exist server-side, and a
                    // browser that somehow followed it would simply do nothing.
                    if (target.scheme == RECORD_SCHEME) {
                        onRecordRequested()
                        return true
                    }
                    // Anything that is not our server — a link to Codeberg, a
                    // mail address — belongs in a real browser. Loading it in
                    // here would leave the user stranded inside a shell with no
                    // address bar and no way back.
                    if (target.host != null && target.host == Uri.parse(config.baseUrl).host) {
                        return false
                    }
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, target)) }
                    return true
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    super.onReceivedError(view, request, error)
                    // Only the main document is worth reporting: a failed
                    // avatar should not raise an alarm about the whole page.
                    if (request.isForMainFrame) {
                        Log.w(TAG, "Sayfa yüklenemedi: ${error.description}")
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    // A page load is where the session cookie is most likely
                    // to have just rotated; getting it on disk now is what
                    // keeps the next launch signed in.
                    android.webkit.CookieManager.getInstance().flush()
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    // Fires on SPA route changes too, not just page loads,
                    // which is the only navigation the web app actually does.
                    canGoBack = view.canGoBack()
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback,
                ) {
                    // Without this the WebView denies `navigator.geolocation`
                    // silently — no prompt, no error, the map's "where am I"
                    // button simply does nothing while the phone's location is
                    // switched on and the app already holds the permission.
                    //
                    // Granted only to our own server, and only when Android has
                    // actually given the app the permission; the page is ours,
                    // so a second in-page prompt would be asking the same
                    // question twice.
                    val allowed = origin.startsWith(config.baseUrl) &&
                        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
                    callback.invoke(origin, allowed, false)
                }

                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    // A page failing inside a shell has no devtools and no
                    // address bar; without this, "the screen went blank" is all
                    // anyone can report. Warnings and errors only — the SPA is
                    // chatty at info level.
                    if (message.messageLevel() in
                        setOf(ConsoleMessage.MessageLevel.ERROR, ConsoleMessage.MessageLevel.WARNING)
                    ) {
                        Log.w(TAG, "web: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                    }
                    return true
                }

                override fun onShowFileChooser(
                    view: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams,
                ): Boolean {
                    // Replace any callback left behind by an abandoned pick,
                    // so the page is never left waiting on a dead one.
                    pendingFiles?.onReceiveValue(null)
                    pendingFiles = callback
                    return runCatching { filePicker.launch(params.createIntent()) }
                        .onFailure {
                            Log.w(TAG, "Dosya seçici açılamadı", it)
                            callback.onReceiveValue(null)
                            pendingFiles = null
                        }
                        .isSuccess
                }
            }

            loadUrl(config.baseUrl)
        }
    }

    // Handed up once the view exists, so the recorder's bar can move the page
    // underneath it.
    //
    // A plain load rather than driving the page's own router from the outside:
    // pushing history state and firing popstate looked cheaper — no reload —
    // but the router ignored it and the tap did nothing at all. Leaving the
    // recorder is a rare enough moment to spend a page load on, and a load is
    // the one thing that cannot silently fail to navigate.
    LaunchedEffect(webView) {
        onNavigatorReady { path -> webView.loadUrl("${config.baseUrl}$path") }
    }

    // Back walks the web history first — but only while this tab is the one
    // on screen; a hidden tab must not swallow the back button.
    BackHandler(enabled = visible && canGoBack) {
        webView.goBack()
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { webView },
        update = { view ->
            // The one hiding mechanism a platform view respects.
            view.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        },
    )
}
