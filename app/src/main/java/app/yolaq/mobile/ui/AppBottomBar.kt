package app.yolaq.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * @property icon The Lucide path data the web bar draws for it.
 */
private data class BarItem(val labelRes: Int, val path: String?, val icon: String)

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
 * Icons above the labels, and every entry at least the 48dp Android asks for.
 * Six words in a row at label size were both unreadable at a glance and barely
 * tappable: the target was the height of the text and nothing more, so the bar
 * missed as often as it hit.
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
        BarItem(R.string.nav_home, "/", Lucide.HOUSE),
        BarItem(R.string.nav_gear, "/gears", Lucide.BIKE),
        BarItem(R.string.nav_health, "/health", Lucide.HEART),
        BarItem(R.string.nav_record, null, Lucide.CIRCLE_DOT),
        BarItem(R.string.nav_alerts, "/notifications", Lucide.BELL),
        BarItem(R.string.nav_menu, "/menu", Lucide.MENU),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val isRecorder = item.path == null
                // The recorder is the current screen, so its entry is the
                // highlighted one — same as the web bar marks where you are.
                val tint = if (isRecorder) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 56.dp)
                        .then(
                            if (isRecorder) {
                                Modifier
                            } else {
                                Modifier.clickable { onNavigate(item.path.orEmpty()) }
                            },
                        )
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                ) {
                    Lucide.Icon(
                        data = item.icon,
                        color = tint,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = context.getString(item.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        color = tint,
                    )
                }
            }
        }
    }
}
