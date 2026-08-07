package app.yolaq.mobile.web

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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

/**
 * The rest of yolak, as the web app already implements it.
 *
 * Activities, segments, challenges, routes and settings all exist and are
 * maintained on the web; rewriting those screens in Compose would mean
 * carrying every feature twice. What a browser genuinely cannot do — record
 * GPS with the screen off — is the native part, and this is everything else.
 *
 * @param modifier Layout modifier.
 */
@Composable
fun WebScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val config = remember { ServerSettings.load(context) }

    if (config == null) {
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

    val webView = remember {
        WebView(context).apply {
            @SuppressLint("SetJavaScriptEnabled")
            // The web app is a Vue SPA: without scripting there is no page at
            // all. It only ever loads our own server (see the client below).
            settings.javaScriptEnabled = true
            // The session cookie and the app's local state live here; without
            // storage every launch would land back on the login screen.
            settings.domStorageEnabled = true

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val target = request.url
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

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    // Fires on SPA route changes too, not just page loads,
                    // which is the only navigation the web app actually does.
                    canGoBack = view.canGoBack()
                }
            }

            loadUrl(config.baseUrl)
        }
    }

    // Back walks the web history first; once there is none left the handler
    // stands down and back means what it usually does.
    BackHandler(enabled = canGoBack) {
        webView.goBack()
    }

    AndroidView(modifier = modifier.fillMaxSize(), factory = { webView })
}
