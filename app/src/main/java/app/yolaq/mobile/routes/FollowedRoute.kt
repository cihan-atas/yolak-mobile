package app.yolaq.mobile.routes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The route the athlete chose to follow, if any.
 *
 * A singleton for the same reason the recording is: the screen and the
 * recording outlive each other, and both need the same answer to "which route
 * are we on?".
 *
 * Deliberately *not* persisted. A route is chosen for one outing; carrying the
 * last one into the next launch would have people following a line they picked
 * days ago without noticing.
 */
object FollowedRoute {

    private val _selected = MutableStateFlow<FollowableRoute?>(null)

    /** The chosen route with its full line, or null when free-running. */
    val selected: StateFlow<FollowableRoute?> = _selected.asStateFlow()

    /**
     * Chooses a route to follow.
     *
     * @param route The route including its geometry, or null to stop following.
     */
    fun select(route: FollowableRoute?) {
        _selected.value = route
    }
}
