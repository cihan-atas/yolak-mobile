package app.yolaq.mobile.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.yolaq.mobile.R
import app.yolaq.mobile.ui.OverlayCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ShareComposer"

/**
 * How wide the preview is rendered, in pixels.
 *
 * A third of the output. Large enough that the preview is a fair likeness of
 * what gets posted, small enough that re-rendering it on every drag frame is
 * affordable on an old phone.
 */
private const val PREVIEW_WIDTH = 360

/**
 * The longest edge a chosen photograph is decoded at.
 *
 * Phone cameras produce 12-megapixel files and the output is 1080 wide; full
 * resolution buys nothing and is how an image editor runs out of memory on a
 * mid-range device.
 */
private const val PHOTO_MAX_EDGE = 2160

/**
 * The smallest a drag handle may be, in device pixels.
 *
 * A figure's label is a few millimetres tall, and a handle the size of what it
 * covers cannot be grabbed with a thumb. Below this the handle grows past the
 * drawing it belongs to, which is the right trade: overlapping handles are
 * recoverable, an ungrabbable one is not.
 */
private val MIN_HANDLE = 44.dp

/**
 * Turning a finished outing into something postable.
 *
 * The thing being reproduced here is what people already do by hand: take a
 * screenshot of the activity, crop it, and drop it over a photo in another
 * app. Doing it here means the numbers are typeset rather than screenshotted,
 * the track is drawn rather than cropped out of a map, and the result is the
 * same size whatever phone made it.
 *
 * Every piece is dragged and pinched into place, because the app cannot know
 * what is in the athlete's photograph. A fixed layout puts the distance across
 * a face as often as it lands on empty sky.
 *
 * @param card The outing, handed over by the page.
 * @param onClose Returns to the page.
 */
@Composable
fun ShareComposerScreen(card: ShareCard, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var format by remember { mutableStateOf(ShareFormat.STORY) }
    var layout by remember { mutableStateOf(ShareLayout.initial(card)) }
    var selected by remember { mutableStateOf<ShareElement?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var basemap by remember { mutableStateOf<Bitmap?>(null) }
    var loadingMap by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val defaults = remember(card) { ShareLayout.defaults(card) }
    var tab by remember { mutableStateOf(ComposerTab.IMAGE) }

    BackHandler(enabled = true, onBack = onClose)

    val pickPhoto = rememberLauncherForActivityResult(
        // The system photo picker: no storage permission, and the athlete only
        // ever hands over the one image they chose.
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            photo = decodePhoto(context, uri)
            if (photo == null) {
                note = context.getString(R.string.share_photo_failed)
            }
        }
    }

    // Fetched only when the basemap or the style actually changes — never on a
    // drag or a pinch. The map is drawn at a fixed resolution and scaled into
    // whatever square the athlete has dragged it to, so resizing it costs
    // nothing and does not touch the network.
    LaunchedEffect(
        layout.routeStyle,
        layout.mapStyle,
        layout.routeColour,
        ShareElement.ROUTE in layout,
    ) {
        if (layout.routeStyle != RouteStyle.MAP || ShareElement.ROUTE !in layout) {
            return@LaunchedEffect
        }
        loadingMap = true
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                // Fetched at the route's own proportions, so the tiles cover
                // the ground the track crosses and nothing much beyond it.
                val aspect = Mercator.aspect(card.route)
                val width = if (aspect >= 1f) BASEMAP_PIXELS else (BASEMAP_PIXELS * aspect).toInt()
                val height = if (aspect >= 1f) (BASEMAP_PIXELS / aspect).toInt() else BASEMAP_PIXELS
                MapTiles.routeMap(
                    route = card.route,
                    style = layout.mapStyle,
                    width = width,
                    height = height,
                    colour = layout.routeColour,
                    cacheDir = context.cacheDir,
                )
            }.getOrElse {
                Log.w(TAG, "Harita alınamadı", it)
                null
            }
        }
        basemap = fetched
        loadingMap = false
        if (fetched == null) {
            note = context.getString(R.string.share_map_failed)
        }
    }

    // Re-rendered whenever anything about the card changes, including every
    // frame of a drag. Off the main thread, so the gesture stays smooth while
    // the bitmap is redrawn behind it.
    LaunchedEffect(photo, format, layout, basemap) {
        preview = withContext(Dispatchers.Default) {
            runCatching {
                ShareRenderer.render(
                    card = card,
                    photo = photo,
                    basemap = basemap,
                    format = format,
                    layout = layout,
                    scale = PREVIEW_WIDTH.toFloat() / format.width,
                )
            }.getOrElse {
                Log.w(TAG, "Önizleme oluşturulamadı", it)
                null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = context.getString(R.string.share_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Row {
                TextButton(
                    onClick = {
                        layout = ShareLayout.initial(card)
                        selected = null
                    },
                ) {
                    Text(context.getString(R.string.share_reset))
                }
                TextButton(onClick = onClose) {
                    Text(context.getString(R.string.share_close))
                }
            }
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Sized to the image's own aspect ratio so preview pixels and
            // image pixels map by a single factor. Letting the image letterbox
            // inside a loose box would have put every drag handle a few
            // millimetres off whatever it belongs to.
            BoxWithConstraints(
                modifier = Modifier
                    .aspectRatio(format.width.toFloat() / format.height)
                    .fillMaxSize(),
            ) {
                val boxWidth = with(density) { maxWidth.toPx() }
                val boxHeight = with(density) { maxHeight.toPx() }

                preview?.let { image ->
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = context.getString(R.string.share_preview),
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .fillMaxSize()
                            // Tapping the picture itself puts the handles away,
                            // so the card can be seen as it will be posted.
                            .pointerInput(Unit) {
                                detectTapGestures { selected = null }
                            },
                    )
                } ?: CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                if (loadingMap) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                ShareRenderer.measure(card, format, layout).forEach { bounds ->
                    ElementHandle(
                        bounds = bounds,
                        selected = selected == bounds.element,
                        imageWidth = format.width.toFloat(),
                        imageHeight = format.height.toFloat(),
                        boxWidth = boxWidth,
                        boxHeight = boxHeight,
                        onSelect = {
                            selected = bounds.element
                            // Touching a thing and then hunting for its
                            // settings is the failure this replaces: the tab
                            // that holds them comes to the selection.
                            if (bounds.element == ShareElement.ROUTE) {
                                tab = ComposerTab.ROUTE
                            }
                        },
                        onTransform = { pan, zoom ->
                            selected = bounds.element
                            val current = layout.items[bounds.element] ?: return@ElementHandle
                            layout = layout.with(
                                bounds.element,
                                current.copy(
                                    x = current.x + pan.x / boxWidth,
                                    y = current.y + pan.y / boxHeight,
                                    scale = current.scale * zoom,
                                ),
                            )
                        },
                    )
                }
            }

            note?.let {
                OverlayCard(modifier = Modifier.align(Alignment.BottomCenter)) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Text(
            text = context.getString(R.string.share_drag_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))

        // Three tabs rather than one long row of chips. Format, which pieces
        // are on, and how the route is drawn are three unrelated questions,
        // and answering them from a single scrolling strip of fifteen
        // identical chips meant hunting for the one that mattered.
        TabRow(selectedTabIndex = tab.ordinal, containerColor = MaterialTheme.colorScheme.surface) {
            ComposerTab.entries.forEach { option ->
                Tab(
                    selected = tab == option,
                    onClick = { tab = option },
                    text = {
                        Text(
                            text = context.getString(option.labelRes),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(TAB_CONTENT_HEIGHT).padding(top = 10.dp)) {
            when (tab) {
                ComposerTab.IMAGE -> ImageTab(
                    format = format,
                    hasPhoto = photo != null,
                    onFormat = { format = it },
                    onPickPhoto = {
                        pickPhoto.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    onRemovePhoto = { photo = null },
                )

                ComposerTab.PARTS -> ChipRow {
                    availableElements(card).forEach { element ->
                        Choice(
                            label = context.getString(element.labelRes()),
                            selected = element in layout,
                            onClick = {
                                layout = layout.toggled(element, defaults)
                                selected = element.takeIf { it in layout }
                            },
                        )
                    }
                }

                ComposerTab.ROUTE -> RouteTab(
                    layout = layout,
                    hasRoute = card.route.size > 1,
                    onLayout = { layout = it },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ShareOutput.canSaveToGallery) {
                OutlinedButton(
                    onClick = {
                        busy = true
                        note = null
                        scope.launch {
                            val full = renderFull(card, photo, basemap, format, layout)
                            val saved = full?.let {
                                ShareOutput.saveToGallery(context, it, card.activityId)
                            }
                            note = context.getString(
                                if (saved != null) R.string.share_saved else R.string.share_failed,
                            )
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Text(context.getString(R.string.share_save), maxLines = 1)
                }
            }
            Button(
                onClick = {
                    busy = true
                    note = null
                    scope.launch {
                        val full = renderFull(card, photo, basemap, format, layout)
                        if (full == null || !ShareOutput.share(context, full, card.activityId)) {
                            note = context.getString(R.string.share_failed)
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Text(context.getString(R.string.share_send), maxLines = 1)
            }
        }
    }
}

/**
 * The invisible grab area over one piece of the card.
 *
 * Invisible until touched: the point of the preview is to look like the
 * finished picture, and a picture covered in boxes does not. Once a piece is
 * selected it gets an outline, so it is obvious which thing the next pinch
 * will resize.
 *
 * @param bounds Where the piece is, in image pixels.
 * @param selected Whether this is the piece being worked on.
 * @param imageWidth The image's width in pixels.
 * @param imageHeight The image's height in pixels.
 * @param boxWidth The preview's width on screen, in device pixels.
 * @param boxHeight The preview's height on screen, in device pixels.
 * @param onSelect Marks this piece as the one being worked on.
 * @param onTransform Reports a drag and a pinch together, in device pixels.
 */
@Composable
private fun ElementHandle(
    bounds: ElementBounds,
    selected: Boolean,
    imageWidth: Float,
    imageHeight: Float,
    boxWidth: Float,
    boxHeight: Float,
    onSelect: () -> Unit,
    onTransform: (pan: androidx.compose.ui.geometry.Offset, zoom: Float) -> Unit,
) {
    val density = LocalDensity.current
    val minHandle = with(density) { MIN_HANDLE.toPx() }

    val scaleX = boxWidth / imageWidth
    val scaleY = boxHeight / imageHeight
    val width = (bounds.width * scaleX).coerceAtLeast(minHandle)
    val height = (bounds.height * scaleY).coerceAtLeast(minHandle)
    // Recentred after the minimum is applied, so a handle grown for the thumb
    // stays over the middle of what it grabs.
    val centreX = (bounds.left + bounds.width / 2f) * scaleX
    val centreY = (bounds.top + bounds.height / 2f) * scaleY

    Box(
        modifier = Modifier
            .offset {
                androidx.compose.ui.unit.IntOffset(
                    (centreX - width / 2f).toInt(),
                    (centreY - height / 2f).toInt(),
                )
            }
            .size(with(density) { width.toDp() }, with(density) { height.toDp() })
            .then(
                if (selected) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(4.dp),
                    )
                } else {
                    Modifier
                },
            )
            .pointerInput(bounds.element) {
                detectTapGestures { onSelect() }
            }
            .pointerInput(bounds.element, boxWidth, boxHeight) {
                // Pan and pinch from one detector: they are the same gesture as
                // far as the athlete is concerned — put this here, this big —
                // and two separate detectors would fight over the pointers.
                detectTransformGestures { _, pan, zoom, _ -> onTransform(pan, zoom) }
            },
    )
}

/**
 * Renders the full-size image off the main thread.
 *
 * A 1080×1920 bitmap with a decoded camera photo scaled into it is tens of
 * milliseconds of work, and doing it inline meant the screen was frozen for
 * exactly as long as the button was supposed to look busy — the disabled state
 * never got a frame to appear in.
 *
 * @return The image, or null when it could not be drawn at all.
 */
private suspend fun renderFull(
    card: ShareCard,
    photo: Bitmap?,
    basemap: Bitmap?,
    format: ShareFormat,
    layout: ShareLayout,
): Bitmap? = withContext(Dispatchers.Default) {
    runCatching {
        ShareRenderer.render(card, photo, basemap, format, layout)
    }.getOrElse {
        Log.w(TAG, "Paylaşım görseli oluşturulamadı", it)
        null
    }
}

/** One option chip. */
@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
    )
}

/** Which pieces this outing actually has to offer. */
private fun availableElements(card: ShareCard): List<ShareElement> =
    ShareElement.entries.filter { element ->
        when (element) {
            ShareElement.ROUTE -> card.route.size > 1
            ShareElement.DURATION -> card.movingSeconds != null
            ShareElement.PACE -> card.paceSecondsPerKm != null
            ShareElement.SPEED -> card.speedKmh != null
            ShareElement.ELEVATION -> card.elevationGainMeters != null
            else -> true
        }
    }

/** Which group of settings the panel under the preview is showing. */
private enum class ComposerTab(@androidx.annotation.StringRes val labelRes: Int) {
    /** Shape of the picture, and the photograph behind it. */
    IMAGE(R.string.share_tab_image),

    /** Which pieces of the outing are on the card at all. */
    PARTS(R.string.share_tab_parts),

    /** How the track is drawn. */
    ROUTE(R.string.share_tab_route),
}

/**
 * How tall the settings panel is, whichever tab is showing.
 *
 * Fixed, so switching tabs does not resize the preview above it. A preview
 * that jumps a centimetre every time a tab is touched makes judging the layout
 * impossible, which is the one thing this screen is for.
 */
private val TAB_CONTENT_HEIGHT = 76.dp

/** A scrolling row of chips, which is what every tab is made of. */
@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Shape of the picture and the photograph behind it.
 *
 * @param format The chosen shape.
 * @param hasPhoto Whether a photograph is loaded.
 * @param onFormat Picks a shape.
 * @param onPickPhoto Opens the photo picker.
 * @param onRemovePhoto Goes back to the plain background.
 */
@Composable
private fun ImageTab(
    format: ShareFormat,
    hasPhoto: Boolean,
    onFormat: (ShareFormat) -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
) {
    val context = LocalContext.current
    ChipRow {
        Choice(
            label = context.getString(R.string.share_format_story),
            selected = format == ShareFormat.STORY,
            onClick = { onFormat(ShareFormat.STORY) },
        )
        Choice(
            label = context.getString(R.string.share_format_square),
            selected = format == ShareFormat.SQUARE,
            onClick = { onFormat(ShareFormat.SQUARE) },
        )
        OutlinedButton(onClick = onPickPhoto, modifier = Modifier.height(40.dp)) {
            Text(
                text = context.getString(
                    if (hasPhoto) R.string.share_change_photo else R.string.share_pick_photo,
                ),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
        if (hasPhoto) {
            OutlinedButton(onClick = onRemovePhoto, modifier = Modifier.height(40.dp)) {
                Text(
                    text = context.getString(R.string.share_remove_photo),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * How the track is drawn: its style, its basemap, and its colour.
 *
 * @param layout The card's arrangement.
 * @param hasRoute Whether the outing has a track at all.
 * @param onLayout Reports a change.
 */
@Composable
private fun RouteTab(layout: ShareLayout, hasRoute: Boolean, onLayout: (ShareLayout) -> Unit) {
    val context = LocalContext.current

    if (!hasRoute || ShareElement.ROUTE !in layout) {
        Text(
            text = context.getString(
                if (hasRoute) R.string.share_route_off else R.string.share_no_route,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChipRow {
            RouteStyle.entries.forEach { option ->
                Choice(
                    label = context.getString(option.labelRes()),
                    selected = layout.routeStyle == option,
                    onClick = { onLayout(layout.copy(routeStyle = option)) },
                )
            }
            if (layout.routeStyle == RouteStyle.MAP) {
                MapStyle.entries.forEach { option ->
                    Choice(
                        label = context.getString(option.labelRes()),
                        selected = layout.mapStyle == option,
                        onClick = { onLayout(layout.copy(mapStyle = option)) },
                    )
                }
            }
        }
        ChipRow {
            Text(
                text = context.getString(R.string.share_route_colour),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RouteColour.entries.forEach { option ->
                Swatch(
                    colour = option,
                    selected = layout.routeColour == option,
                    onClick = { onLayout(layout.copy(routeColour = option)) },
                )
            }
        }
    }
}

/**
 * One colour of line, as the colour itself.
 *
 * A dot of the actual colour rather than its name: "Turkuaz" tells nobody
 * whether it will show up on their photograph, and the swatch is the answer.
 *
 * @param colour Which colour.
 * @param selected Whether it is the chosen one.
 * @param onClick Chooses it.
 */
@Composable
private fun Swatch(colour: RouteColour, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(colour.argb))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = context.getString(colour.labelRes) },
    )
}

/** The chip's name for a way of drawing the route. */
private fun RouteStyle.labelRes(): Int = when (this) {
    RouteStyle.LINE -> R.string.share_route_line
    RouteStyle.MAP -> R.string.share_route_map
}

/** The chip's name for a basemap. */
private fun MapStyle.labelRes(): Int = when (this) {
    MapStyle.STANDARD -> R.string.share_map_standard
    MapStyle.SATELLITE -> R.string.share_map_satellite
    MapStyle.DARK -> R.string.share_map_dark
}

/** The chip's name for a piece. */
private fun ShareElement.labelRes(): Int = when (this) {
    ShareElement.TITLE -> R.string.share_element_title
    ShareElement.SUBTITLE -> R.string.share_element_subtitle
    ShareElement.DISTANCE -> R.string.stat_distance
    ShareElement.DURATION -> R.string.stat_duration
    ShareElement.PACE -> R.string.stat_pace
    ShareElement.SPEED -> R.string.stat_speed
    ShareElement.ELEVATION -> R.string.stat_elevation
    ShareElement.ROUTE -> R.string.share_show_route
}

/**
 * Reads the chosen photograph, downscaled as it is decoded.
 *
 * Sampled during the decode rather than resized afterwards, which is the
 * difference between allocating a 48 MB bitmap and never allocating it: a
 * modern phone camera's file would otherwise be decoded in full before being
 * thrown away.
 *
 * @param context Any context.
 * @param uri What the picker returned.
 * @return The image, or null when it could not be read.
 */
private fun decodePhoto(context: android.content.Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longest / sample > PHOTO_MAX_EDGE) {
        sample *= 2
    }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}.getOrElse {
    Log.w(TAG, "Fotoğraf okunamadı", it)
    null
}
