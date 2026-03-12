@file:OptIn(ExperimentalWasmJsInterop::class)
package io.github.kdroidfilter.webview.cookie

import kotlinx.browser.document

/**
 * Converts a timestamp to a cookie expiration date string in the browser's expected format.
 */
actual fun getCookieExpirationDate(
    expiresDate: Long
): String = js(
    //language=javascript
    "(new Date(expiresDate).toUTCString())"
)

@Suppress("FunctionName")
actual fun WebViewCookieManager(): CookieManager = WasmJsCookieManager

object WasmJsCookieManager : CookieManager {
    override suspend fun setCookie(
        url: String,
        cookie: Cookie
    ) {
        // Set the cookie using the document.cookie API
        document.cookie = cookie.toString()
    }

    override suspend fun getCookies(
        url: String
    ): List<Cookie> {
        val cookiesStr = document.cookie
        if (cookiesStr.isEmpty()) {
            return emptyList()
        }

        return cookiesStr.split(";").map { cookieStr ->
            val parts = cookieStr.trim().split("=", limit = 2)
            val name = parts[0]
            val value = if (parts.size > 1) {
                parts[1]
            } else {
                ""
            }

            Cookie(
                name = name,
                value = value
            )
        }
    }

    override suspend fun removeAllCookies() {
        val cookies = getCookies("")
        for (cookie in cookies) {
            // To delete a cookie, set it with an expired date
            document.cookie = buildString {
                append("${cookie.name}=")
                append("; path=/")
                append("; expires=Thu, 01 Jan 1970 00:00:00 GMT")
            }
        }
    }

    override suspend fun removeCookies(url: String) {
        /**
         * In a browser context, we can't easily remove cookies for a specific URL,
         * So we'll use the same approach as removeAllCookies
         * Alternative: use CookieStore (https://developer.mozilla.org/en-US/docs/Web/API/CookieStore)
         */
        removeAllCookies()
    }
}
