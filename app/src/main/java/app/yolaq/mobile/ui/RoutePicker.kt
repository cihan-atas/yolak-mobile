package app.yolaq.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.yolaq.mobile.R
import app.yolaq.mobile.net.ServerSettings
import app.yolaq.mobile.routes.FollowableRoute
import app.yolaq.mobile.routes.RoutesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Choosing a route to follow on this outing.
 *
 * The list loads when the dialog opens rather than being kept warm: routes
 * change rarely and the picker is opened rarely, so a fetch at the moment of
 * asking is simpler than a cache to keep honest.
 *
 * @param onDismiss Closes without changing the selection.
 * @param onSelected Called with the chosen route including its line, or null
 *   when the athlete chooses to follow nothing.
 */
@Composable
fun RoutePicker(onDismiss: () -> Unit, onSelected: (FollowableRoute?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var routes by remember { mutableStateOf<List<FollowableRoute>?>(null) }
    var busy by remember { mutableStateOf(false) }
    /**
     * Why the last attempt did not produce a route to follow.
     *
     * Held separately from the selection because "it failed" and "I chose to
     * run without a route" are different answers that used to arrive as the
     * same null — the dialog would close on a network hiccup and the recorder
     * would follow nothing, with nothing on screen to say so.
     */
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val config = ServerSettings.load(context)
        if (config == null) {
            error = context.getString(R.string.route_pick_error)
            routes = emptyList()
        } else {
            routes = withContext(Dispatchers.IO) { RoutesApi(config).list() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.route_pick_title)) },
        text = {
            when {
                busy || routes == null -> CircularProgressIndicator()

                routes.orEmpty().isEmpty() -> Text(
                    text = error ?: context.getString(R.string.route_pick_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (error == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )

                else -> Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    error?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    routes.orEmpty().forEach { route ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) {
                                    // The line itself is fetched only for the
                                    // route actually chosen: pulling every
                                    // route's geometry to fill a list would be
                                    // megabytes for one pick.
                                    busy = true
                                    error = null
                                    scope.launch {
                                        val config = ServerSettings.load(context)
                                        val full = if (config == null) {
                                            null
                                        } else {
                                            withContext(Dispatchers.IO) {
                                                RoutesApi(config).withGeometry(route)
                                            }
                                        }
                                        busy = false
                                        // A failure keeps the dialog open and
                                        // says so. Passing the null on would
                                        // have been indistinguishable from the
                                        // athlete tapping "record without a
                                        // route", which is the one thing they
                                        // did not just ask for.
                                        if (full == null) {
                                            error = context.getString(R.string.route_pick_error)
                                        } else {
                                            onSelected(full)
                                        }
                                    }
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(text = route.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%.2f km",
                                    route.distanceMeters / 1000.0,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelected(null) }) {
                Text(context.getString(R.string.route_pick_none))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.record_cancel))
            }
        },
    )
}
