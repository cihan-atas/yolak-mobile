package app.yolaq.mobile.web

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.ValueCallback
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.yolaq.mobile.R
import app.yolaq.mobile.net.ServerSettings

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
 * @param modifier Layout modifier.
 */
@Composable
fun WebScreen(visible: Boolean, onRecordRequested: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Read on every composition, not remembered: the user may configure the
    // server while this tab is already alive, and a cached null would leave
    // the tab stuck on "no server" until the app is killed. A prefs read per
    // recomposition is nothing, and recompositions here are tab switches.
    val config = ServerSettings.load(context)

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
            @SuppressLint("SetJavaScriptEnabled")
            // The web app is a Vue SPA: without scripting there is no page at
            // all. It only ever loads our own server (see the client below).
            settings.javaScriptEnabled = true
            // The session cookie and the app's local state live here; without
            // storage every launch would land back on the login screen.
            settings.domStorageEnabled = true
            // How the page knows it is inside the app, and so whether to offer
            // the recorder at all — a browser cannot record with the screen
            // off, so the entry would be a dead button anywhere else.
            settings.userAgentString = "${settings.userAgentString} yolak-app/1"

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

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    // Fires on SPA route changes too, not just page loads,
                    // which is the only navigation the web app actually does.
                    canGoBack = view.canGoBack()
                }
            }

            webChromeClient = object : WebChromeClient() {
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
