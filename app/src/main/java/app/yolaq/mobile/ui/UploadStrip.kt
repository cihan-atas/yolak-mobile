package app.yolaq.mobile.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.yolaq.mobile.R
import app.yolaq.mobile.sync.RecordingFinisher

/**
 * What became of the outing the athlete just saved, shown over the page.
 *
 * Saving used to hold the recorder open until the server answered and then
 * push the athlete into the new activity's edit form. Both halves of that were
 * wrong. The wait was however long the upload and the server's parsing took —
 * a frozen screen with nothing on it — and the jump moved the app somewhere
 * nobody had asked to go. Meanwhile every way an upload can fail was collected
 * and then displayed nowhere at all.
 *
 * So the save returns the athlete to the page they came from and this reports
 * from up there: it says the upload is running, says when the activity is
 * ready and offers to open it, and says plainly when it did not land. Nothing
 * takes the screen; the offer is a strip you can ignore.
 *
 * @param onOpenActivity Opens a saved activity in the web half.
 * @param modifier Layout modifier.
 */
@Composable
fun UploadStrip(onOpenActivity: (Long) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uploading by RecordingFinisher.awaitingUpload.collectAsState()
    val handover by RecordingFinisher.handover.collectAsState()

    val result = handover
    if (!uploading && result == null) {
        return
    }

    // A failed upload is not the same news as a landed one, and the colour is
    // read before the words are.
    val failed = result is RecordingFinisher.Handover.Refused ||
        result is RecordingFinisher.Handover.SignedOut ||
        result is RecordingFinisher.Handover.Deferred

    val text = when (result) {
        null -> context.getString(R.string.upload_running)
        is RecordingFinisher.Handover.Uploaded -> context.getString(R.string.upload_done)
        is RecordingFinisher.Handover.Saved -> context.getString(R.string.upload_saved)
        is RecordingFinisher.Handover.Refused ->
            context.getString(R.string.upload_refused, result.status)

        is RecordingFinisher.Handover.SignedOut -> context.getString(R.string.upload_signed_out)
        is RecordingFinisher.Handover.Deferred -> context.getString(R.string.upload_offline)
    }

    Surface(
        onClick = {
            // The one strip with somewhere to go opens it; the rest are read
            // and dismissed. Either way the strip is done afterwards — it
            // reports a moment, it is not a permanent status line.
            (result as? RecordingFinisher.Handover.Uploaded)?.let { onOpenActivity(it.activityId) }
            if (result != null) {
                RecordingFinisher.clearHandover()
            }
        },
        modifier = modifier.fillMaxWidth(),
        color = if (failed) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (failed) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (result == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
        }
    }
}
