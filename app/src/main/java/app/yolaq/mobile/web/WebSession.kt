package app.yolaq.mobile.web

import android.content.Context
import android.webkit.CookieManager
import app.yolaq.mobile.net.LoginService
import app.yolaq.mobile.net.ServerSettings

/**
 * Carries the sign-in from the native screen into the web tab.
 *
 * The web app bootstraps its session on load from an httpOnly refresh cookie.
 * That cookie is captured during login and planted here, so the tab comes up
 * already signed in. Without this the user would sign in on the login screen
 * and then be asked for the very same credentials by the web tab — which is
 * exactly the kind of "why do I have to do this twice" the login screen
 * existed to remove.
 */
object WebSession {

    /**
     * Installs the captured session cookie for the configured server.
     *
     * @param context Any context.
     */
    fun install(context: Context) {
        val config = ServerSettings.load(context) ?: return
        val cookie = ServerSettings.webRefreshCookie(context) ?: return

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Set against the origin: the cookie's own Path attribute
            // (/api/v1/auth) travels inside the value and is respected.
            setCookie(config.baseUrl, cookie)
            flush()
        }
    }

    /**
     * Drops the web session on sign-out.
     *
     * Only the refresh cookie is removed rather than every cookie for the
     * site, so nothing else the user set in the web app is disturbed.
     *
     * @param context Any context.
     */
    fun clear(context: Context) {
        val baseUrl = ServerSettings.load(context)?.baseUrl ?: ServerSettings.DEFAULT_BASE_URL
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // An expiry in the past is how a cookie is deleted.
            setCookie(
                baseUrl,
                "${LoginService.REFRESH_COOKIE_NAME}=; Path=/api/v1/auth; " +
                    "Expires=Thu, 01 Jan 1970 00:00:00 GMT",
            )
            flush()
        }
    }
}
