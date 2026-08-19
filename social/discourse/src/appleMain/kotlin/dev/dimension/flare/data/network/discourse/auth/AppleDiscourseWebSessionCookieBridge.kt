@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieEncoding
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieSnapshot
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieStorage
import platform.Foundation.NSURL

/**
 * iOS/macOS browser Cookie bridge backed by the app's shared Foundation Cookie storage.
 *
 * A restricted WKWebView host synchronizes only its `https://linux.do` cookies into this storage
 * before invoking the bridge. The Kotlin side independently filters the URL/domain, normalizes the
 * host, applies the shared bounds, and never enumerates cookies belonging to another origin.
 */
public class AppleDiscourseWebSessionCookieBridge(
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val storage: NSHTTPCookieStorage = NSHTTPCookieStorage.sharedHTTPCookieStorage,
) : DiscourseWebSessionCookieBridge {
    private val originUrl: NSURL = requireNotNull(NSURL(string = DISCOURSE_ORIGIN))

    override suspend fun snapshotLinuxDoCookies(): List<DiscourseCookieSnapshot> =
        withContext(mainDispatcher) {
            storage
                .cookiesForURL(originUrl)
                .orEmpty()
                .filterIsInstance<NSHTTPCookie>()
                .filter { cookie -> cookie.domain.removePrefix(".").equals("linux.do", ignoreCase = true) }
                .map { cookie ->
                    DiscourseCookieSnapshot(
                        name = cookie.name,
                        value = cookie.value,
                        encoding = DiscourseCookieEncoding.RAW,
                        expiresAtEpochMillis =
                            cookie.expiresDate
                                ?.timeIntervalSinceReferenceDate
                                ?.plus(978_307_200.0)
                                ?.times(1_000.0)
                                ?.toLong(),
                        domain = "linux.do",
                        path = cookie.path.ifBlank { "/" },
                        secure = true,
                        httpOnly = cookie.name == "_t",
                    )
                }.also { cookies ->
                    DiscourseCookieStorage().requireValidSnapshot(cookies)
                }
        }

    override suspend fun clearLinuxDoCookies() {
        withContext(mainDispatcher) {
            storage
                .cookiesForURL(originUrl)
                .orEmpty()
                .filterIsInstance<NSHTTPCookie>()
                .filter { cookie -> cookie.domain.removePrefix(".").equals("linux.do", ignoreCase = true) }
                .forEach(storage::deleteCookie)
        }
    }
}
