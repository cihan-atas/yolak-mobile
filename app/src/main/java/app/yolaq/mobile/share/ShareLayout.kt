package app.yolaq.mobile.share

/**
 * One movable piece of the card.
 *
 * The card used to be a single block: title, distance and figures stacked in a
 * fixed order, with one choice of top or bottom. That works for a stock photo
 * and fails for a real one, where the sky is on the left, a face is in the
 * middle and the only clear space is a corner. So each piece is its own thing
 * the athlete drags where it fits.
 */
enum class ShareElement {
    /** What the outing is called. */
    TITLE,

    /** Sport and date, on one line. */
    SUBTITLE,

    /** The headline distance. */
    DISTANCE,

    /** Moving time. */
    DURATION,

    /** Average pace. */
    PACE,

    /** Average speed. */
    SPEED,

    /** Total climb. */
    ELEVATION,

    /** The shape of the track. */
    ROUTE,
}

/**
 * How the track is drawn.
 *
 * Two answers to the same question — how does a line stay readable over an
 * arbitrary photograph — and which one is right depends entirely on the
 * photograph, so it is the athlete's call. There was a third, a translucent
 * panel behind the line, and it earned its removal: with a coloured line and
 * its casing the bare drawing already reads on anything, so the panel was a
 * grey box over the picture for no gain.
 */
enum class RouteStyle {
    /** The bare line, over the photo, with a shadow to lift it off. */
    LINE,

    /** The line over a real basemap, cropped to the route. */
    MAP,
}

/**
 * Where a piece sits and how big it is.
 *
 * The position is the piece's *centre*, in fractions of the image, so a layout
 * survives switching between story and square: an element half way across and
 * a third of the way down stays there when the frame changes shape. Anchoring
 * by a corner in pixels would have scattered the card on every format switch.
 *
 * @property x Horizontal centre, 0 at the left edge and 1 at the right.
 * @property y Vertical centre, 0 at the top and 1 at the bottom.
 * @property scale Size multiplier, pinched by the athlete.
 */
data class Placement(
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
) {
    /** Keeps a dragged element from being pushed entirely off the image. */
    fun clamped(): Placement = copy(
        x = x.coerceIn(EDGE, 1f - EDGE),
        y = y.coerceIn(EDGE, 1f - EDGE),
        scale = scale.coerceIn(MIN_SCALE, MAX_SCALE),
    )

    private companion object {
        /**
         * How close to the edge an element's centre may be dragged.
         *
         * Not zero: an element centred exactly on the edge is half outside the
         * picture, and there is no way to grab it back.
         */
        const val EDGE = 0.04f

        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 2.5f
    }
}

/**
 * The arrangement of a card.
 *
 * @property items Every piece currently on the card, and where it is. A piece
 *   absent from the map is a piece the athlete turned off.
 * @property routeStyle How the track is drawn, when it is on.
 * @property mapStyle Which basemap, when the track is drawn on one.
 * @property routeColour The colour of the track.
 */
data class ShareLayout(
    val items: Map<ShareElement, Placement>,
    val routeStyle: RouteStyle = RouteStyle.LINE,
    val mapStyle: MapStyle = MapStyle.STANDARD,
    val routeColour: RouteColour = RouteColour.ORANGE,
) {
    /** Whether a piece is on the card. */
    operator fun contains(element: ShareElement): Boolean = element in items

    /** The same layout with one piece moved or resized. */
    fun with(element: ShareElement, placement: Placement): ShareLayout =
        copy(items = items + (element to placement.clamped()))

    /** The same layout with a piece added back at its default spot, or removed. */
    fun toggled(element: ShareElement, defaults: Map<ShareElement, Placement>): ShareLayout =
        if (element in items) {
            copy(items = items - element)
        } else {
            copy(items = items + (element to (defaults[element] ?: Placement(0.5f, 0.5f))))
        }

    companion object {

        /**
         * The arrangement a card opens with.
         *
         * Bottom-left, reading downwards in the order a person would say it:
         * what it was, how far, then the supporting figures. It is the layout
         * the card had before any of this was movable, which makes moving
         * things an improvement someone opts into rather than a puzzle they
         * are handed.
         *
         * Only the figures the outing actually has are placed; an activity
         * with no elevation gain does not open with an empty tile on it.
         *
         * @param card The outing.
         * @return The starting layout.
         */
        fun initial(card: ShareCard): ShareLayout {
            val defaults = defaults(card)
            val present = defaults.keys.filter { element ->
                when (element) {
                    ShareElement.ROUTE -> card.route.size > 1
                    ShareElement.DURATION -> card.movingSeconds != null
                    ShareElement.PACE -> card.paceSecondsPerKm != null
                    ShareElement.SPEED -> card.speedKmh != null
                    ShareElement.ELEVATION -> card.elevationGainMeters != null
                    else -> true
                }
            }
            // Speed and pace say the same thing twice. Whichever the outing
            // reports first leads; the other is one tap away.
            val trimmed = if (
                ShareElement.PACE in present && ShareElement.SPEED in present
            ) {
                present - ShareElement.SPEED
            } else {
                present
            }
            return ShareLayout(items = trimmed.associateWith { defaults.getValue(it) })
        }

        /**
         * Where each piece goes when it is first placed.
         *
         * @param card The outing, which decides how much room the figures need.
         * @return Default position for every piece.
         */
        fun defaults(card: ShareCard): Map<ShareElement, Placement> {
            // The figures share a row, so they are spread across it rather
            // than stacked on each other.
            val figures = listOfNotNull(
                ShareElement.DURATION.takeIf { card.movingSeconds != null },
                ShareElement.PACE.takeIf { card.paceSecondsPerKm != null },
                ShareElement.SPEED.takeIf { card.speedKmh != null },
                ShareElement.ELEVATION.takeIf { card.elevationGainMeters != null },
            )
            val row = figures.withIndex().associate { (index, element) ->
                element to Placement(
                    x = 0.22f + index * 0.22f,
                    y = 0.88f,
                )
            }
            return buildMap {
                put(ShareElement.ROUTE, Placement(0.5f, 0.44f))
                put(ShareElement.TITLE, Placement(0.5f, 0.66f))
                put(ShareElement.SUBTITLE, Placement(0.5f, 0.71f))
                put(ShareElement.DISTANCE, Placement(0.5f, 0.79f))
                putAll(row)
                // Anything the outing does not report still needs somewhere to
                // land if it is somehow turned on.
                ShareElement.entries.forEach { putIfAbsent(it, Placement(0.5f, 0.88f)) }
            }
        }
    }
}
