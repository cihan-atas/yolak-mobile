package app.yolaq.mobile.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.collection.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min

private const val TAG = "MapTiles"

/** The side of a raster tile, in pixels. Every provider here serves 256. */
private const val TILE = 256

/**
 * A basemap the athlete can put behind their route.
 *
 * Third-party raster tiles rather than yolak's own basemap, which is a vector
 * archive (`.pmtiles`) with no renderer on the phone and none on the server
 * either — it is why the activity thumbnails have no map behind them today.
 * Rendering vector tiles would mean shipping a map engine to draw one small
 * square, so these are borrowed instead.
 *
 * @property template Tile URL with `{z}`, `{x}` and `{y}` placeholders.
 * @property attribution The credit line the licence requires, drawn on the
 *   image. Not optional and not negotiable: it is the condition these tiles
 *   are free under.
 * @property maxZoom The deepest zoom the provider serves.
 */
enum class MapStyle(
    val template: String,
    val attribution: String,
    val maxZoom: Int,
) {
    /** Roads and names — the most legible thing to lay a coloured line over. */
    STANDARD(
        template = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        attribution = "© OpenStreetMap",
        maxZoom = 18,
    ),

    /** Imagery, which is what most people mean by "put a map on it". */
    SATELLITE(
        template = "https://server.arcgisonline.com/ArcGIS/rest/services/" +
            "World_Imagery/MapServer/tile/{z}/{y}/{x}",
        attribution = "Esri, Maxar, Earthstar Geographics",
        maxZoom = 18,
    ),

    /** Dark and quiet, so a white route line is the brightest thing on it. */
    DARK(
        template = "https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        attribution = "© OpenStreetMap © CARTO",
        maxZoom = 18,
    ),
}

/**
 * Builds the basemap image behind a route.
 *
 * Stitched on the phone from a handful of tiles rather than rendered by the
 * server. The route is already here — it came across the bridge — so the phone
 * knows exactly which tiles it needs, fetches nine or so of them, and is done
 * without a new endpoint, a new API scope, or the server's one gigabyte of
 * memory being asked to rasterise anything.
 */
object MapTiles {

    /**
     * Tiles already fetched this session.
     *
     * Sized in tiles rather than bytes for simplicity: a 256×256 ARGB tile is
     * 256 KB, so this caps at roughly 16 MB. It matters because switching
     * basemap style and back, or nudging the map's size, would otherwise
     * re-download everything.
     */
    private val memory = LruCache<String, Bitmap>(64)

    /**
     * Fetches the tiles for a route and draws the route on them.
     *
     * The line is drawn here rather than by the card renderer, and that is the
     * whole point of this function returning a finished picture. Tiles are Web
     * Mercator; the card's own route drawing is a plain linear fit. Drawing one
     * over the other put the track beside the roads it was run on instead of
     * along them. Projecting both here, from the same zoom and centre, is the
     * only way they can agree.
     *
     * @param route The track, as latitude/longitude pairs.
     * @param style Which basemap.
     * @param width Output width in pixels.
     * @param height Output height in pixels.
     * @param colour The track's colour.
     * @param cacheDir Where tiles are kept between runs.
     * @return The map with the route on it, or null when no tile could be
     *   fetched — offline, or the provider refused. The caller falls back to
     *   drawing the bare line.
     */
    fun routeMap(
        route: List<Pair<Double, Double>>,
        style: MapStyle,
        width: Int,
        height: Int,
        colour: RouteColour,
        cacheDir: File,
    ): Bitmap? {
        if (route.size < 2 || width <= 0 || height <= 0) {
            return null
        }

        val minLat = route.minOf { it.first }
        val maxLat = route.maxOf { it.first }
        val minLon = route.minOf { it.second }
        val maxLon = route.maxOf { it.second }

        val zoom = zoomFor(minLat, maxLat, minLon, maxLon, width, height, style.maxZoom)
        val scale = (TILE shl zoom).toDouble()

        // World pixel coordinates of the route's centre at this zoom.
        val centreX = (Mercator.x(minLon) + Mercator.x(maxLon)) / 2.0 * scale
        val centreY = (Mercator.y(minLat) + Mercator.y(maxLat)) / 2.0 * scale

        // The window of the world the output shows.
        val left = centreX - width / 2.0
        val top = centreY - height / 2.0

        val firstTileX = floor(left / TILE).toInt()
        val firstTileY = floor(top / TILE).toInt()
        val lastTileX = floor((left + width) / TILE).toInt()
        val lastTileY = floor((top + height) / TILE).toInt()

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        // Something to look at where a tile is missing, rather than a
        // transparent hole that shows the photograph through the map.
        canvas.drawColor(if (style == MapStyle.DARK) Color.rgb(18, 18, 18) else Color.rgb(221, 221, 221))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        val span = 1 shl zoom
        var drew = false
        for (tileY in firstTileY..lastTileY) {
            if (tileY < 0 || tileY >= span) {
                continue
            }
            for (tileX in firstTileX..lastTileX) {
                // Longitude wraps; latitude does not.
                val wrappedX = ((tileX % span) + span) % span
                val tile = tile(style, zoom, wrappedX, tileY, cacheDir) ?: continue
                canvas.drawBitmap(
                    tile,
                    (tileX * TILE - left).toFloat(),
                    (tileY * TILE - top).toFloat(),
                    paint,
                )
                drew = true
            }
        }

        if (!drew) {
            // Every tile failed: better to show no map at all than a grey
            // square, which looks like a bug rather than a choice.
            return null
        }

        drawRoute(canvas, route, zoom, left, top, colour)
        return output
    }

    /**
     * Draws the track onto the stitched tiles.
     *
     * Same projection, same zoom, same origin as the tiles underneath, so the
     * line lands on the roads it was recorded on.
     *
     * @param canvas The stitched map.
     * @param route The track.
     * @param zoom The zoom the tiles were fetched at.
     * @param left World pixel x of the image's left edge.
     * @param top World pixel y of the image's top edge.
     * @param colour The track's colour.
     */
    private fun drawRoute(
        canvas: Canvas,
        route: List<Pair<Double, Double>>,
        zoom: Int,
        left: Double,
        top: Double,
        colour: RouteColour,
    ) {
        val scale = (TILE shl zoom).toDouble()
        val path = android.graphics.Path()
        route.forEachIndexed { index, (latitude, longitude) ->
            val x = (Mercator.x(longitude) * scale - left).toFloat()
            val y = (Mercator.y(latitude) * scale - top).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // A dark casing under the line, so the track survives both a pale
        // satellite beach and a dark basemap.
        canvas.drawPath(path, linePaint(9f, RouteColour.CASING))
        canvas.drawPath(path, linePaint(5f, colour.argb))

        val (startLat, startLon) = route.first()
        canvas.drawCircle(
            (Mercator.x(startLon) * scale - left).toFloat(),
            (Mercator.y(startLat) * scale - top).toFloat(),
            7f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE },
        )
    }

    /** A round-capped stroke for the track. */
    private fun linePaint(width: Float, color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }

    /**
     * The deepest zoom at which the route still fits the output.
     *
     * Deeper is better — more detail — so this takes the largest zoom whose
     * projected span still fits, with a margin so the line does not touch the
     * edges of its own frame.
     */
    private fun zoomFor(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        width: Int,
        height: Int,
        maxZoom: Int,
    ): Int {
        val spanX = (Mercator.x(maxLon) - Mercator.x(minLon)).coerceAtLeast(1e-9)
        // Latitude's projection is inverted, so the min/max swap.
        val spanY = (Mercator.y(minLat) - Mercator.y(maxLat)).coerceAtLeast(1e-9)
        val usableWidth = width * (1 - 2 * MARGIN)
        val usableHeight = height * (1 - 2 * MARGIN)

        val fit = min(usableWidth / spanX, usableHeight / spanY) / TILE
        val zoom = floor(ln(fit) / ln(2.0)).toInt()
        return zoom.coerceIn(1, maxZoom)
    }

    /**
     * One tile, from memory, from disk, or from the provider.
     *
     * Cached on disk as well as in memory: composing a card is a fiddly
     * business of trying a style, changing your mind and coming back, and
     * re-downloading the same nine tiles each time is both slow on a phone
     * connection and rude to a free tile server.
     */
    private fun tile(style: MapStyle, zoom: Int, x: Int, y: Int, cacheDir: File): Bitmap? {
        val key = "${style.name}-$zoom-$x-$y"
        memory.get(key)?.let { return it }

        val directory = File(cacheDir, "tiles").apply { mkdirs() }
        val file = File(directory, "$key.png")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.path)?.let {
                memory.put(key, it)
                return it
            }
        }

        val url = style.template
            .replace("{z}", zoom.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())

        val bytes = runCatching { download(url) }.getOrElse {
            Log.w(TAG, "Tile alınamadı: $url", it)
            null
        } ?: return null

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        memory.put(key, bitmap)
        runCatching { file.writeBytes(bytes) }
        return bitmap
    }

    /**
     * Fetches one tile.
     *
     * The user agent is not decoration: OpenStreetMap's tile policy refuses
     * requests from generic library agents, and a phone that identifies itself
     * is the difference between tiles and 403s.
     */
    private fun download(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "Tile reddedildi (${connection.responseCode}): $url")
                return null
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * How much of the frame is kept clear around the route.
     *
     * A track drawn edge to edge reads as cropped; the breathing room is what
     * makes it look like a map of a ride rather than a screenshot of one.
     */
    private const val MARGIN = 0.08

    private const val TIMEOUT_MS = 12_000

    private const val USER_AGENT = "yolak/1.0 (self-hosted activity tracker; share image)"
}

/** Largest dimension the basemap is fetched at, whatever size it is drawn. */
const val BASEMAP_PIXELS = 900
