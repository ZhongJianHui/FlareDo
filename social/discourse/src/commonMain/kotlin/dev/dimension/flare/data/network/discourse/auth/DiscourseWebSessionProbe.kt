package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.DefaultDiscourseApi
import dev.dimension.flare.data.network.discourse.DiscourseHttpUserAgentProvider
import dev.dimension.flare.data.network.discourse.createDiscourseHttpClient
import dev.dimension.flare.data.network.discourse.createDiscourseWireTransport
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieSnapshot
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import dev.dimension.flare.data.network.discourse.session.DiscourseCsrfTokenStore
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext

/** Verified identity and final Cookie jar produced by an isolated fallback-browser probe. */
internal data class DiscourseWebSessionProbeResult(
    val accountId: String,
    val username: String,
    val displayName: String?,
    val cookies: List<DiscourseCookieSnapshot>,
) {
    init {
        require(accountId.toLongOrNull()?.let { it > 0L } == true) {
            "A probed Discourse account id must be a positive integer"
        }
        require(username.isNotBlank()) { "A probed Discourse username must not be blank" }
        require(cookies.any { cookie -> cookie.name == "_t" && cookie.value.isNotEmpty() }) {
            "A verified Discourse web session must retain its authentication cookie"
        }
    }
}

/**
 * Verifies browser-owned Cookies without publishing temporary identity or credentials globally.
 *
 * Implementations must treat [cookies] as a one-use snapshot. In particular, the production probe
 * owns a private Cookie jar and request-generation boundary that are never shared with forum,
 * composer, realtime, or presentation services.
 */
internal fun interface DiscourseWebSessionProbe {
    /** Returns the server-authoritative identity and the post-response bounded Cookie snapshot. */
    suspend fun probe(cookies: List<DiscourseCookieSnapshot>): DiscourseWebSessionProbeResult
}

/**
 * Fixed-origin production probe backed by a fresh Ktor/session graph for every invocation.
 *
 * Importing the browser snapshot directly into this private jar lets the read-only
 * `/session/current.json` endpoint authenticate while the private manager remains a guest. The
 * manager exists because [DefaultDiscourseApi] requires the same generation-signed request lease as
 * the main API graph; it intentionally receives a fresh CSRF store even though this probe performs
 * no mutation. A `Set-Cookie` response is applied to the private jar and included in the returned
 * snapshot, so only server-refreshed material is later committed to the real session lifecycle.
 *
 * The client closes before the Cookie storage. That first stops engine/plugin access to the jar;
 * closing the storage second atomically erases its remaining credentials. Both closes are attempted
 * from `finally`. A cleanup failure is surfaced only when there is no request/cancellation failure
 * to preserve, so cancellation and the authoritative probe error cannot be masked.
 */
internal class DefaultDiscourseWebSessionProbe(
    private val cookieStorageFactory: () -> DiscourseCookieStorage = { DiscourseCookieStorage() },
    private val clientFactory: ((DiscourseCookieStorage) -> HttpClient)? = null,
    private val userAgentProvider: DiscourseHttpUserAgentProvider = DiscourseHttpUserAgentProvider { null },
) : DiscourseWebSessionProbe {
    override suspend fun probe(cookies: List<DiscourseCookieSnapshot>): DiscourseWebSessionProbeResult {
        val storage = cookieStorageFactory()
        var client: HttpClient? = null
        var primaryFailure: Throwable? = null
        try {
            storage.importSnapshot(cookies)
            val isolatedClient =
                clientFactory?.invoke(storage)
                    ?: createDiscourseHttpClient(
                        cookieStorage = storage,
                        userAgent = userAgentProvider.userAgent(),
                    )
            client = isolatedClient
            val isolatedSessionManager =
                DiscourseSessionManager(
                    cookieStorage = storage,
                    csrfTokenStore = DiscourseCsrfTokenStore(),
                )
            val isolatedApi =
                DefaultDiscourseApi(
                    wire = createDiscourseWireTransport(isolatedClient),
                    sessionManager = isolatedSessionManager,
                    client = isolatedClient,
                )
            val user =
                isolatedApi.currentSession().currentUser
                    ?: throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.Identity)
            if (user.id <= 0L || user.username.isBlank()) {
                throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.Identity)
            }

            return DiscourseWebSessionProbeResult(
                accountId = user.id.toString(),
                username = user.username,
                displayName = user.name,
                cookies = storage.snapshot(),
            )
        } catch (cancellation: CancellationException) {
            primaryFailure = cancellation
            throw cancellation
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailure = closeProbeResources(client = client, storage = storage)
            if (primaryFailure == null && cleanupFailure != null) throw cleanupFailure
        }
    }
}

private suspend fun closeProbeResources(
    client: HttpClient?,
    storage: DiscourseCookieStorage,
): Throwable? =
    withContext(NonCancellable) {
        var firstFailure: Throwable? = null
        val clientJob = client?.coroutineContext?.get(Job)
        try {
            client?.close()
        } catch (failure: Throwable) {
            firstFailure = failure
        }
        try {
            // HttpClient.close starts engine shutdown but some targets complete its Job later.
            // Joining here prevents an engine/plugin callback from racing the private jar erase.
            clientJob?.cancelAndJoin()
        } catch (failure: Throwable) {
            if (firstFailure == null) firstFailure = failure
        } finally {
            try {
                storage.close()
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure
    }
