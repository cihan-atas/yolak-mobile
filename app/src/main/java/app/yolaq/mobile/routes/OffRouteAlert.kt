package app.yolaq.mobile.routes

import app.yolaq.mobile.recording.TrackPoint

/**
 * Decides when leaving the route is worth interrupting the athlete for.
 *
 * Stateful on purpose: the interesting event is not "off the route" — which is
 * true for every fix of a long detour — but the *moment* of leaving it. Warning
 * on the state rather than the transition would buzz once a second for as long
 * as somebody stayed off course, which teaches them to ignore it.
 *
 * Lives apart from the recorder screen because the screen is not there when
 * this matters: a phone on an arm or in a pocket has stopped composing, and a
 * warning raised in the UI would only reach someone already looking at it.
 */
class OffRouteAlert {

    /** Whether the last fix counted as off the route. */
    private var strayed = false

    /**
     * Whether this fix is the moment the athlete left the route.
     *
     * Two thresholds rather than one. With a single line at
     * [RouteGuidance.OFF_ROUTE_METERS], someone running along it — which is
     * exactly what a route drawn down the middle of a road they are on the
     * side of produces — crosses back and forth on GPS noise alone, and every
     * crossing outwards is a fresh buzz. Coming back has to be a clearly
     * smaller distance than going out.
     *
     * @param route The line being followed.
     * @param position Where the athlete is.
     * @return True on the fix that strays, false on every other.
     */
    fun update(route: List<TrackPoint>, position: TrackPoint): Boolean {
        val distance = RouteGuidance.distanceFromRoute(route, position) ?: return false

        if (strayed) {
            if (distance <= BACK_ON_ROUTE_METERS) {
                strayed = false
            }
            return false
        }

        if (distance > RouteGuidance.OFF_ROUTE_METERS) {
            strayed = true
            return true
        }
        return false
    }

    /**
     * Forgets where the athlete was, for the start of a new outing.
     *
     * Without this a recording started somewhere off the last route would
     * inherit its "already strayed" state and never warn.
     */
    fun reset() {
        strayed = false
    }

    private companion object {
        /**
         * Below this the athlete counts as back on the route.
         *
         * Well inside [RouteGuidance.OFF_ROUTE_METERS] so returning is a
         * deliberate move back to the line, not a favourable fix.
         */
        const val BACK_ON_ROUTE_METERS = 25.0
    }
}
