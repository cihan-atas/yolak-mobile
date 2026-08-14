package app.yolaq.mobile.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.max

/**
 * A piece of the card, measured and ready to draw.
 *
 * @property element Which piece.
 * @property left Left edge in image pixels.
 * @property top Top edge in image pixels.
 * @property width How wide, in image pixels.
 * @property height How tall, in image pixels.
 */
data class ElementBounds(
    val element: ShareElement,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/**
 * Draws the shareable image.
 *
 * Rendered straight onto a fixed-size [Bitmap] rather than screenshotted from
 * the preview. The output is then the same 1080-wide image from every phone,
 * where a screenshot would be whatever resolution the device happens to have,
 * cropped to whatever the status bar left over. The preview on screen is this
 * same function at a smaller size, so what is previewed is what is posted.
 *
 * Every piece carries its own drop shadow instead of the card sitting on one
 * big darkening wash. The wash was the right answer while the text was a
 * single block pinned to an edge; once the athlete can put a figure anywhere,
 * a gradient covering the whole lower half is covering the part of the
 * photograph they moved the figure away from.
 */
object ShareRenderer {

    /**
     * Renders the card.
     *
     * @param card The outing.
     * @param photo The athlete's own photograph, or null for the fallback.
     * @param basemap The stitched map tiles behind the route, when the route
     *   is drawn on a map and the tiles arrived. Passed in rather than fetched
     *   here: this runs on every frame of a drag, and the network does not.
     * @param format Story or square.
     * @param layout Where every piece sits.
     * @param scale 1.0 for the full-size image; smaller for the preview.
     * @return The finished image.
     */
    fun render(
        card: ShareCard,
        photo: Bitmap?,
        basemap: Bitmap?,
        format: ShareFormat,
        layout: ShareLayout,
        scale: Float = 1f,
    ): Bitmap {
        val width = (format.width * scale).toInt()
        val height = (format.height * scale).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawBackground(canvas, photo, width, height)

        // Drawn in a fixed order rather than the map's iteration order, so two
        // overlapping pieces always stack the same way rather than swapping
        // depth when the layout map is rebuilt.
        ShareElement.entries.forEach { element ->
            val placement = layout.items[element] ?: return@forEach
            drawElement(canvas, card, element, placement, layout, basemap, format, scale)
        }

        drawWordmark(canvas, width, height, scale)
        return bitmap
    }

    /**
     * Measures every piece on the card, in full-size image pixels.
     *
     * The composer needs this to put a drag handle exactly over what the
     * athlete sees. Measuring here rather than in the composer is what keeps
     * the handle and the drawing from disagreeing: there is one description of
     * how big a figure is, and both callers ask it.
     *
     * @param card The outing.
     * @param format Story or square.
     * @param layout Where every piece sits.
     * @return Bounds for each placed piece.
     */
    fun measure(card: ShareCard, format: ShareFormat, layout: ShareLayout): List<ElementBounds> =
        ShareElement.entries.mapNotNull { element ->
            val placement = layout.items[element] ?: return@mapNotNull null
            val size = sizeOf(card, element, placement, format, scale = 1f)
            ElementBounds(
                element = element,
                left = placement.x * format.width - size.first / 2f,
                top = placement.y * format.height - size.second / 2f,
                width = size.first,
                height = size.second,
            )
        }

    /** How big a piece is, at a given scale, in pixels. */
    private fun sizeOf(
        card: ShareCard,
        element: ShareElement,
        placement: Placement,
        format: ShareFormat,
        scale: Float,
    ): Pair<Float, Float> {
        val unit = format.width * scale * placement.scale / BASE_WIDTH
        return when (element) {
            ShareElement.ROUTE -> {
                // The frame takes the route's own proportions. A square frame
                // around a valley ride left the line as a thread across a
                // large empty map; matching the shape means the track fills
                // what it is drawn in, whichever style is on.
                val aspect = Mercator.aspect(card.route)
                val longest = ROUTE_SIDE * unit
                if (aspect >= 1f) longest to longest / aspect else longest * aspect to longest
            }

            ShareElement.TITLE -> {
                val paint = textPaint(TITLE_SIZE * unit, bold = true)
                paint.measureText(card.title) to TITLE_SIZE * unit * LINE_HEIGHT
            }

            ShareElement.SUBTITLE -> {
                val paint = textPaint(SUBTITLE_SIZE * unit, bold = false)
                paint.measureText(subtitle(card)) to SUBTITLE_SIZE * unit * LINE_HEIGHT
            }

            ShareElement.DISTANCE -> {
                val value = distanceText(card)
                val paint = textPaint(DISTANCE_SIZE * unit, bold = true)
                val unitPaint = textPaint(DISTANCE_UNIT_SIZE * unit, bold = true)
                (paint.measureText(value) + unitPaint.measureText(" km")) to
                    DISTANCE_SIZE * unit * LINE_HEIGHT
            }

            else -> {
                val (value, label) = figure(card, element) ?: ("" to "")
                val valuePaint = textPaint(FIGURE_SIZE * unit, bold = true)
                val labelPaint = textPaint(LABEL_SIZE * unit, bold = false)
                max(valuePaint.measureText(value), labelPaint.measureText(label)) to
                    (FIGURE_SIZE * unit * LINE_HEIGHT + LABEL_SIZE * unit * LINE_HEIGHT)
            }
        }
    }

    /** Draws one piece, centred on its placement. */
    private fun drawElement(
        canvas: Canvas,
        card: ShareCard,
        element: ShareElement,
        placement: Placement,
        layout: ShareLayout,
        basemap: Bitmap?,
        format: ShareFormat,
        scale: Float,
    ) {
        val (width, height) = sizeOf(card, element, placement, format, scale)
        val centreX = placement.x * format.width * scale
        val centreY = placement.y * format.height * scale
        val left = centreX - width / 2f
        val top = centreY - height / 2f
        val unit = format.width * scale * placement.scale / BASE_WIDTH

        when (element) {
            ShareElement.ROUTE -> drawRoute(
                canvas = canvas,
                card = card,
                left = left,
                top = top,
                width = width,
                height = height,
                unit = unit,
                routeStyle = layout.routeStyle,
                mapStyle = layout.mapStyle,
                colour = layout.routeColour,
                basemap = basemap,
            )

            ShareElement.TITLE -> {
                val paint = textPaint(TITLE_SIZE * unit, bold = true)
                canvas.drawText(card.title, left, top + TITLE_SIZE * unit * BASELINE, paint)
            }

            ShareElement.SUBTITLE -> {
                val paint = textPaint(SUBTITLE_SIZE * unit, bold = false, alpha = 200)
                canvas.drawText(subtitle(card), left, top + SUBTITLE_SIZE * unit * BASELINE, paint)
            }

            ShareElement.DISTANCE -> {
                val value = distanceText(card)
                val paint = textPaint(DISTANCE_SIZE * unit, bold = true)
                val unitPaint = textPaint(DISTANCE_UNIT_SIZE * unit, bold = true, alpha = 205)
                val baseline = top + DISTANCE_SIZE * unit * BASELINE
                canvas.drawText(value, left, baseline, paint)
                canvas.drawText(" km", left + paint.measureText(value), baseline, unitPaint)
            }

            else -> {
                val (value, label) = figure(card, element) ?: return
                val valuePaint = textPaint(FIGURE_SIZE * unit, bold = true)
                val labelPaint = textPaint(LABEL_SIZE * unit, bold = false, alpha = 185)
                canvas.drawText(value, left, top + FIGURE_SIZE * unit * BASELINE, valuePaint)
                canvas.drawText(
                    label,
                    left,
                    top + FIGURE_SIZE * unit * LINE_HEIGHT + LABEL_SIZE * unit * BASELINE,
                    labelPaint,
                )
            }
        }
    }

    /** The photograph, filled to the frame, or the fallback when there is none. */
    private fun drawBackground(canvas: Canvas, photo: Bitmap?, width: Int, height: Int) {
        if (photo == null) {
            // Not a flat colour: a gradient reads as a deliberate card rather
            // than a failed image load, and it is the app's own two greens.
            val paint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    TRAIL_GREEN, PINE,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            return
        }

        // Centre-cropped, the way every photo frame on a phone behaves: the
        // alternative is letterboxing somebody's summit shot.
        val scale = max(width.toFloat() / photo.width, height.toFloat() / photo.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                (width - photo.width * scale) / 2f,
                (height - photo.height * scale) / 2f,
            )
        }
        canvas.drawBitmap(photo, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /**
     * The shape of the outing, in whichever of its two styles.
     *
     * Projected with longitude squeezed by the cosine of the latitude, so a
     * north-south ride is not drawn twice as wide as it was ridden.
     */
    private fun drawRoute(
        canvas: Canvas,
        card: ShareCard,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        unit: Float,
        routeStyle: RouteStyle,
        mapStyle: MapStyle,
        colour: RouteColour,
        basemap: Bitmap?,
    ) {
        val route = card.route.takeIf { it.size > 1 } ?: return

        // The map already carries the track, drawn in the tiles' own
        // projection (see MapTiles.routeMap for why it cannot be drawn here),
        // so this only frames it and adds the credit the licence requires.
        if (routeStyle == RouteStyle.MAP && basemap != null) {
            val radius = PANEL_RADIUS * unit
            val frame = RectF(left, top, left + width, top + height)
            val rounded = Path().apply { addRoundRect(frame, radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(rounded)
            canvas.drawBitmap(
                basemap,
                null,
                frame,
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            // Not decoration and not optional: these tiles are free on the
            // condition that the credit travels with the picture.
            val creditSize = 20f * unit
            canvas.drawText(
                mapStyle.attribution,
                left + 12f * unit,
                top + height - 10f * unit,
                textPaint(creditSize, bold = false, alpha = 190),
            )
            canvas.restore()
            canvas.drawRoundRect(
                frame,
                radius,
                radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f * unit
                    color = PANEL_EDGE
                },
            )
            return
        }

        // Inset only so the line's round caps and the start marker are not
        // clipped by the edge of the frame they are measured into.
        val inset = ROUTE_INSET * unit
        val innerLeft = left + inset
        val innerTop = top + inset
        val innerWidth = width - inset * 2
        val innerHeight = height - inset * 2

        // Web Mercator, the same projection the tiles use, so the bare line
        // and the mapped line are the same shape.
        val xs = route.map { Mercator.x(it.second) }
        val ys = route.map { Mercator.y(it.first) }
        val spanX = (xs.max() - xs.min()).coerceAtLeast(1e-9)
        val spanY = (ys.max() - ys.min()).coerceAtLeast(1e-9)
        // One scale for both axes, so the line keeps the shape it was ridden in.
        val fit = minOf(innerWidth / spanX, innerHeight / spanY).toFloat()
        val drawnWidth = (spanX * fit).toFloat()
        val drawnHeight = (spanY * fit).toFloat()
        val offsetX = innerLeft + (innerWidth - drawnWidth) / 2f
        val offsetY = innerTop + (innerHeight - drawnHeight) / 2f

        val projected = route.indices.map { index ->
            val x = offsetX + ((xs[index] - xs.min()) * fit).toFloat()
            // Mercator's y already grows downwards, like the canvas.
            val y = offsetY + ((ys[index] - ys.min()) * fit).toFloat()
            x to y
        }

        val path = Path().apply {
            moveTo(projected.first().first, projected.first().second)
            projected.drop(1).forEach { lineTo(it.first, it.second) }
        }
        // Over a bare photograph the line needs a dark stroke underneath to
        // survive a bright background; inside a panel that is already handled,
        // and the extra weight only muddies a small drawing.
        run {
            canvas.drawPath(
                path,
                strokePaint(width = 14f * unit, color = RouteColour.CASING),
            )
        }
        canvas.drawPath(path, strokePaint(width = 8f * unit, color = colour.argb))
        // Where it began, so a loop can be told from an out-and-back.
        canvas.drawCircle(
            projected.first().first,
            projected.first().second,
            11f * unit,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE },
        )
    }

    /**
     * The app's mark, small, bottom-right.
     *
     * Fixed rather than movable: it is the one thing on the card that is not
     * the athlete's, so it does not get to compete for the good spots — and a
     * card with no name on it is a card nobody can trace back.
     */
    private fun drawWordmark(canvas: Canvas, width: Int, height: Int, scale: Float) {
        val unit = width / (BASE_WIDTH * 1f)
        val size = 34f * unit
        val paint = textPaint(size, bold = true, alpha = 165)
        val text = "yolak"
        canvas.drawText(
            text,
            width - 72f * unit - paint.measureText(text),
            height - 72f * unit,
            paint,
        )
    }

    /** Sport and date on one line, skipping whichever the page could not supply. */
    private fun subtitle(card: ShareCard): String =
        listOf(card.sportLabel, card.dateLabel).filter { it.isNotBlank() }.joinToString(" · ")

    /**
     * The headline number.
     *
     * Two decimals, not three: this is read across a room on someone else's
     * phone, and the metre nobody can see costs a character of the number they
     * can.
     */
    private fun distanceText(card: ShareCard): String =
        String.format(Locale.US, "%.2f", card.distanceMeters / 1000.0)

    /** A figure's value and its label, or null when the outing has no such figure. */
    private fun figure(card: ShareCard, element: ShareElement): Pair<String, String>? =
        when (element) {
            ShareElement.DURATION -> card.movingSeconds?.let { formatClock(it) to LABEL_DURATION }

            ShareElement.PACE -> card.paceSecondsPerKm?.let {
                String.format(Locale.US, "%d:%02d", it.toInt() / 60, it.toInt() % 60) to LABEL_PACE
            }

            ShareElement.SPEED -> card.speedKmh?.let {
                String.format(Locale.US, "%.1f", it) to LABEL_SPEED
            }

            ShareElement.ELEVATION -> card.elevationGainMeters?.let {
                String.format(Locale.US, "%.0f m", it) to LABEL_ELEVATION
            }

            else -> null
        }

    /**
     * A white text paint with a drop shadow.
     *
     * The shadow is what replaced the darkening wash: a piece dragged into the
     * middle of a bright sky has nothing behind it, and a soft dark halo is
     * the least intrusive thing that keeps white type readable there.
     */
    private fun textPaint(size: Float, bold: Boolean, alpha: Int = 255): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alpha, 255, 255, 255)
            textSize = size
            typeface = Typeface.create(
                if (bold) "sans-serif-medium" else "sans-serif",
                if (bold) Typeface.BOLD else Typeface.NORMAL,
            )
            isSubpixelText = true
            setShadowLayer(size * 0.18f, 0f, size * 0.03f, 0xB0000000.toInt())
        }

    /** A round-capped stroke. */
    private fun strokePaint(width: Float, color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }

    /** Hours:minutes:seconds, dropping the hours when there are none. */
    private fun formatClock(seconds: Long): String = if (seconds >= 3600) {
        String.format(
            Locale.US,
            "%d:%02d:%02d",
            seconds / 3600,
            (seconds % 3600) / 60,
            seconds % 60,
        )
    } else {
        String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }

    /**
     * The width every size below is written against.
     *
     * Sizes are expressed for a 1080-wide image and scaled from there, so the
     * preview and the output cannot drift apart and a square card's type is
     * the same size as a story's.
     */
    private const val BASE_WIDTH = 1080f

    private const val TITLE_SIZE = 52f
    private const val SUBTITLE_SIZE = 32f
    private const val DISTANCE_SIZE = 148f
    private const val DISTANCE_UNIT_SIZE = 44f
    private const val FIGURE_SIZE = 62f
    private const val LABEL_SIZE = 34f
    private const val ROUTE_SIDE = 460f
    private const val PANEL_RADIUS = 28f
    private const val ROUTE_INSET = 14f

    /** How far below the top of a line its baseline sits, as a fraction of the size. */
    private const val BASELINE = 0.82f

    /** Line spacing as a fraction of the text size. */
    private const val LINE_HEIGHT = 1.18f

    // Labels are not translated: they are a word or two that read the same in
    // both languages the app ships, and an image posted to Instagram is read
    // by people who do not have the app at all.
    private const val LABEL_DURATION = "SÜRE"
    private const val LABEL_PACE = "TEMPO /KM"
    private const val LABEL_SPEED = "KM/SA"
    private const val LABEL_ELEVATION = "TIRMANIŞ"

    private val TRAIL_GREEN = Color.rgb(0x1D, 0x9E, 0x75)
    private val PINE = Color.rgb(0x04, 0x34, 0x2C)
    private val PANEL_EDGE = 0x40FFFFFF
}
