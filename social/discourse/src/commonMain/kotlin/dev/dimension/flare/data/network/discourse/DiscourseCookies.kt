package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.session.DiscourseCookieRevisionContext
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.renderCookieHeader
import io.ktor.http.setCookie
import io.ktor.util.AttributeKey
import kotlinx.coroutines.currentCoroutineContext

private val REQUEST_COOKIE_REVISION: AttributeKey<Long> =
    AttributeKey("DiscourseRequestCookieRevision")

/** Configuration for the revision-bound Linux.do cookie plugin. */
internal class DiscourseCookiesConfig {
    lateinit var storage: DiscourseCookieStorage
}

/**
 * Cookie transport that cannot cross a [DiscourseSessionManager] session boundary.
 *
 * Ktor's general `HttpCookies` plugin reads the jar when its request hook happens and writes every
 * later `Set-Cookie` response. That is insufficient for account replacement: a cancelled old
 * generation can reach a non-cancellable request after the replacement and read the new account's
 * cookies, while a delayed old response can overwrite them. This plugin instead requires the
 * revision signed by the session manager before the operation starts.
 *
 * Calls made without that lease are deliberately fail-closed: any caller-supplied Cookie header is
 * removed and response cookies are ignored. Login and WebView bridges must import a validated full
 * snapshot through the session manager rather than issuing an unbound HTTP call.
 */
internal val DiscourseCookies =
    createClientPlugin("DiscourseCookies", ::DiscourseCookiesConfig) {
        val storage = pluginConfig.storage

        onRequest { request, _ ->
            // Never preserve a manually supplied Cookie header; only the bounded jar is trusted.
            request.headers.remove(HttpHeaders.Cookie)

            val expectedRevision =
                currentCoroutineContext()[DiscourseCookieRevisionContext]?.revision
                    ?: return@onRequest
            request.attributes.put(REQUEST_COOKIE_REVISION, expectedRevision)

            val lease =
                storage.captureForRequest(
                    requestUrl = request.url.build(),
                    expectedRevision = expectedRevision,
                ) ?: return@onRequest
            if (lease.cookies.isNotEmpty()) {
                request.headers.append(
                    HttpHeaders.Cookie,
                    lease.cookies.joinToString(separator = "; ", transform = ::renderCookieHeader),
                )
            }
        }

        onResponse { response ->
            val expectedRevision =
                response.request.attributes.getOrNull(REQUEST_COOKIE_REVISION)
                    ?: return@onResponse
            storage.addCookiesIfRevision(
                requestUrl = response.request.url,
                cookies = response.setCookie(),
                expectedRevision = expectedRevision,
            )
        }

        onClose {
            storage.close()
        }
    }
