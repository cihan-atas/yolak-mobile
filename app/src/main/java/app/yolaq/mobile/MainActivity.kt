package app.yolaq.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import app.yolaq.mobile.net.ServerSettings
import app.yolaq.mobile.recording.RecordingRepository
import app.yolaq.mobile.recording.RecordingStatus
import app.yolaq.mobile.share.ShareCard
import app.yolaq.mobile.share.ShareComposerScreen
import app.yolaq.mobile.sync.RecordingFinisher
import app.yolaq.mobile.sync.UploadWorker
import app.yolaq.mobile.ui.LoginScreen
import app.yolaq.mobile.ui.RecordScreen
import app.yolaq.mobile.ui.RecordingStrip
import app.yolaq.mobile.ui.UploadStrip
import app.yolaq.mobile.ui.YolakTheme
import app.yolaq.mobile.web.WebScreen
import app.yolaq.mobile.web.WebSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

    // The outing the athlete asked to make a picture of, sent across from the
    // activity page. Null the rest of the time, which is nearly always.
    var shareCard by remember { mutableStateOf<ShareCard?>(null) }

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
            visible = !showRecorder && shareCard == null,
            onRecordRequested = { showRecorder = true },
            onShareRequested = { shareCard = it },
            // The page's logout only ever ended the browser's half. The key
            // the recorder uploads with is here, and it was never asked for a
            // second time, so a phone stayed signed in as its first user
            // forever: the next person recorded into that account and saw
            // nothing under their own.
            onSignedOut = {
                // Before the settings go: this reads the server address out of
                // them to know which origin's cookie to drop.
                WebSession.clear(context)
                ServerSettings.clear(context)
                session = null
            },
            onNavigatorReady = { navigateWeb = it },
        )

        // Composing an image is the athlete looking at a picture, not at a
        // recording, so it takes the whole screen and outranks even the
        // recorder underneath — which carries on regardless, in the service.
        shareCard?.let { card ->
            ShareComposerScreen(card = card, onClose = { shareCard = null })
            return@Box
        }

        if (showRecorder) {
            RecordScreen(
                onClose = { showRecorder = false },
                onNavigate = { path ->
                    showRecorder = false
                    navigateWeb?.invoke(path)
                },
            )
        } else {
            Column(modifier = Modifier.align(Alignment.TopCenter)) {
                // Only while an outing is running. Idle, the way in is the
                // record entry in the page's own navigation — floating
                // furniture over someone else's layout is exactly what this
                // replaced.
                if (state.status != RecordingStatus.IDLE) {
                    RecordingStrip(state = state, onClick = { showRecorder = true })
                }
                // Where a save reports itself, now that saving no longer holds
                // the recorder open waiting for the server.
                UploadStrip(onOpenActivity = { id -> navigateWeb?.invoke("/activity/$id") })
            }
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

