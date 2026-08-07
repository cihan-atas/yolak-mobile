package app.yolaq.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.yolaq.mobile.R

/**
 * One destination in the app's bottom bar.
 *
 * @property labelRes What it is called.
 * @property path Where it goes in the web app, or null for the recorder.
 */
private data class BarItem(val labelRes: Int, val path: String?)

/**
 * The bottom bar, mirroring the web app's own.
 *
 * The recorder covers the whole screen, which took the page's navigation with
 * it: opening the recorder used to mean the bar vanished and the only way back
 * was a "close" link in a corner, so the recorder felt like a different app
 * bolted on. This redraws the same destinations natively, so the bar is
 * continuous across both halves and leaving the recorder is the same gesture
 * as anywhere else — tap where you want to go.
 *
 * The items are duplicated here rather than shared with the web nav, which is
 * the honest cost of the two being written in different languages; the list is
 * short and changes rarely.
 *
 * @param onNavigate Called with the web path to open, closing the recorder.
 * @param modifier Layout modifier.
 */
@Composable
fun AppBottomBar(onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val items = listOf(
        BarItem(R.string.nav_home, "/"),
        BarItem(R.string.nav_gear, "/gears"),
        BarItem(R.string.nav_health, "/health"),
        BarItem(R.string.nav_record, null),
        BarItem(R.string.nav_alerts, "/notifications"),
        BarItem(R.string.nav_menu, "/menu"),
    )

    Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val isRecorder = item.path == null
                Text(
                    text = context.getString(item.labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    // The recorder is the current screen, so its entry is the
                    // highlighted one — same as the web bar marks where you are.
                    color = if (isRecorder) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                        .then(
                            if (isRecorder) {
                                Modifier
                            } else {
                                Modifier.clickable { onNavigate(item.path.orEmpty()) }
                            },
                        ),
                )
            }
        }
    }
}
