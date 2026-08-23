package dev.dimension.flare.data.network.discourse.realtime

import com.fleeksoft.ksoup.Ksoup
import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import dev.dimension.flare.data.network.discourse.model.discourseJson
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val MAX_DISCOURSE_BOOTSTRAP_HTML_BYTES: Long = 2L * 1_024L * 1_024L

private const val DATA_PRELOADED_SELECTOR: String = "script#data-preloaded"
private const val SHARED_SESSION_KEY_SELECTOR: String = "meta[name=shared_session_key]"
private const val SITE_SETTINGS_KEY: String = "siteSettings"
private const val LONG_POLLING_BASE_URL_KEY: String = "long_polling_base_url"

/** The two values Discourse exposes separately in its same-origin bootstrap HTML. */
private data class DiscourseMessageBusBootstrap(
    val pollingOrigin: String,
    val sharedSessionKey: String?,
)

/**
 * Resolves Discourse's optional cross-origin MessageBus endpoint for one session-generation lease.
 *
 * The protected client is intentionally reused only for `GET https://linux.do/`, where its cookie
 * plugin enforces the current [dev.dimension.flare.data.network.discourse.session.DiscourseCookieRevisionContext].
 * The discovered polling host is never requested by that client. [DefaultDiscourseMessageBusTransport]
 * owns a separate client without cookie storage for both cross-origin endpoint variants.
 *
 * Bootstrap discovery is an optimization boundary. Missing or malformed HTML, oversized bodies,
 * transient network failures, and incomplete cross-origin configuration all fall back to
 * [DiscourseMessageBusEndpoint.SameOrigin]. Caller cancellation and explicit terminal session
 * failures still propagate so a replaced account cannot finish discovery and 401/403/challenge
 * responses enter the coordinator's ordinary recovery flow.
 */
internal class DefaultDiscourseMessageBusEndpointProvider(
    private val client: HttpClient,
    private val parserDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : DiscourseMessageBusEndpointProvider {
    override suspend fun endpoint(session: DiscourseSessionState): DiscourseMessageBusEndpoint {
        return try {
            val html = readBootstrapHtml() ?: return DiscourseMessageBusEndpoint.SameOrigin
            val bootstrap =
                withContext(parserDispatcher) {
                    parseBootstrap(html)
                } ?: return DiscourseMessageBusEndpoint.SameOrigin

            if (session is DiscourseSessionState.Authenticated) {
                val key = bootstrap.sharedSessionKey ?: return DiscourseMessageBusEndpoint.SameOrigin
                DiscourseMessageBusEndpoint.SharedSession(
                    pollingOrigin = bootstrap.pollingOrigin,
                    sharedSessionKey = key,
                )
            } else {
                DiscourseMessageBusEndpoint.CrossOrigin(bootstrap.pollingOrigin)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (failure.toSessionRecoveryReasonOrNull() != null) throw failure
            DiscourseMessageBusEndpoint.SameOrigin
        }
    }

    private suspend fun readBootstrapHtml(): String? {
        val response =
            client.get("$DISCOURSE_ORIGIN/") {
                header(HttpHeaders.Accept, ContentType.Text.Html.toString())
            }
        val body = response.bodyAsChannel()
        return try {
            if (response.contentType()?.match(ContentType.Text.Html) != true) return null

            response.headers[HttpHeaders.ContentLength]?.let { rawLength ->
                val declaredLength = rawLength.toLongOrNull() ?: return null
                if (declaredLength !in 0L..MAX_DISCOURSE_BOOTSTRAP_HTML_BYTES) return null
            }

            // Read one byte beyond the accepted bound. This detects a streaming response that omits
            // Content-Length without first allocating its complete, attacker-controlled body.
            val bytes =
                body
                    .readRemaining(MAX_DISCOURSE_BOOTSTRAP_HTML_BYTES + 1L)
                    .readByteArray()
            if (bytes.size.toLong() > MAX_DISCOURSE_BOOTSTRAP_HTML_BYTES) return null
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            body.cancel()
        }
    }
}

private fun parseBootstrap(html: String): DiscourseMessageBusBootstrap? {
    return try {
        val document = Ksoup.parse(html, DISCOURSE_ORIGIN)
        val preloadedScripts = document.select(DATA_PRELOADED_SELECTOR)
        if (preloadedScripts.size != 1) return null
        val preloadedScript = preloadedScripts.single()
        if (!preloadedScript.attr("type").equals(ContentType.Application.Json.toString(), ignoreCase = true)) {
            return null
        }

        // The script is an outer JSON object whose values are themselves serialized JSON strings.
        // Decode both layers structurally; HTML regex extraction would mishandle entity and quote
        // boundaries and could pair a key with an unrelated script fragment.
        val outer = discourseJson.parseToJsonElement(preloadedScript.data()) as? JsonObject ?: return null
        val encodedSiteSettings = outer[SITE_SETTINGS_KEY] as? JsonPrimitive ?: return null
        if (!encodedSiteSettings.isString) return null
        val siteSettings =
            discourseJson.parseToJsonElement(encodedSiteSettings.content) as? JsonObject
                ?: return null
        val pollingOriginValue = siteSettings[LONG_POLLING_BASE_URL_KEY] as? JsonPrimitive ?: return null
        if (!pollingOriginValue.isString) return null
        val pollingOrigin = pollingOriginValue.content
        if (pollingOrigin.isEmpty() || pollingOrigin != pollingOrigin.trim() || pollingOrigin == "/") {
            return null
        }

        val keyElements = document.select(SHARED_SESSION_KEY_SELECTOR)
        if (keyElements.size > 1) return null
        val key =
            keyElements
                .singleOrNull()
                ?.attr("content")
                ?.takeIf { it.isNotEmpty() && it == it.trim() }
        DiscourseMessageBusBootstrap(
            pollingOrigin = pollingOrigin,
            sharedSessionKey = key,
        )
    } catch (_: Exception) {
        null
    }
}
