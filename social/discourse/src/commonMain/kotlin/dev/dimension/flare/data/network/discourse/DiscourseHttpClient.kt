package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.error.mapDiscourseResponseException
import dev.dimension.flare.data.network.discourse.model.discourseJson
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.BodyProgress
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.parseHeaderValue
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readString

private const val MAX_CLASSIFICATION_BODY_BYTES: Long = 4_096
private const val LINUX_DO_HOST: String = "linux.do"
private const val CONNECT_TIMEOUT_MILLIS: Long = 15_000
private const val REQUEST_TIMEOUT_MILLIS: Long = 30_000
private const val SOCKET_TIMEOUT_MILLIS: Long = 30_000

/**
 * Reads only the allocation-bounded prefix used to identify explicit CSRF or proxy challenges.
 *
 * The limit is expressed in bytes and applied to [ByteReadChannel] before UTF-8 decoding. Calling
 * `bodyAsText().take(...)` would first buffer the entire untrusted response and would therefore not
 * provide a memory bound. A truncated multi-byte sequence is decoded as replacement text; all
 * security markers are ASCII, so this cannot create a false positive beyond the inspected bytes.
 */
internal suspend fun ByteReadChannel.readDiscourseClassificationBodyPrefix(): String =
    readRemaining(MAX_CLASSIFICATION_BODY_BYTES).readString()

/**
 * Rejects any request that has escaped the compile-time Linux.do origin.
 *
 * Ktorfit normally resolves only relative paths against [DISCOURSE_ORIGIN]. A small number of
 * filtered-list endpoints use an internally constructed URL, so this final transport-level check
 * protects cookies and CSRF headers even if a future path builder is changed incorrectly.
 */
private val FixedDiscourseOrigin =
    createClientPlugin("FixedDiscourseOrigin") {
        onRequest { request, _ ->
            val resolvedUrl = request.url.build()
            check(
                resolvedUrl.protocol == URLProtocol.HTTPS &&
                    resolvedUrl.host.equals(LINUX_DO_HOST, ignoreCase = true) &&
                    resolvedUrl.port == URLProtocol.HTTPS.defaultPort &&
                    resolvedUrl.user.isNullOrEmpty() &&
                    resolvedUrl.password.isNullOrEmpty(),
            ) {
                "Discourse requests must remain on the fixed Linux.do HTTPS origin"
            }
        }
    }

private val DiscourseDefaultHeaders =
    createClientPlugin("DiscourseDefaultHeaders") {
        onRequest { request, _ ->
            if (request.headers[HttpHeaders.Accept] == null) {
                request.headers.append(HttpHeaders.Accept, ContentType.Application.Json.toString())
            }
            if (request.headers[HttpHeaders.AcceptLanguage] == null) {
                request.headers.append(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9,en;q=0.7")
            }
            // A full HTML bootstrap must render Discourse's layout and `data-preloaded` script.
            // Rails treats X-Requested-With as an AJAX request, which may select a response variant
            // without that layout. API and MessageBus requests keep the header's CSRF semantics.
            if (!request.headers[HttpHeaders.Accept].acceptsHtml() && request.headers["X-Requested-With"] == null) {
                request.headers.append("X-Requested-With", "XMLHttpRequest")
            }
        }
    }

/**
 * Creates the platform client from an engine factory so Ktor owns and closes the native engine.
 *
 * Passing a pre-created engine to `HttpClient(engine)` sets Ktor's `manageEngine` flag to false and
 * leaks OkHttp/Darwin resources when Koin closes only the client. Each actual implementation uses
 * `HttpClient(OkHttp)` or `HttpClient(Darwin)` and applies [configureDiscourseHttpClient].
 */
internal expect fun createDiscourseHttpClient(cookieStorage: DiscourseCookieStorage): HttpClient

/**
 * Builds a client around [engine]. Tests pass Ktor's `MockEngine` through this overload so request
 * paths, headers, cookies, failures, and cancellation are exercised without production traffic.
 */
internal fun createDiscourseHttpClient(
    engine: HttpClientEngine,
    cookieStorage: DiscourseCookieStorage,
): HttpClient =
    HttpClient(engine) {
        configureDiscourseHttpClient(cookieStorage)
    }

/** Shared protocol configuration used by both managed platform clients and test-owned engines. */
internal fun HttpClientConfig<*>.configureDiscourseHttpClient(cookieStorage: DiscourseCookieStorage) {
    expectSuccess = true
    followRedirects = false

    install(FixedDiscourseOrigin)
    install(DiscourseCookies) {
        storage = cookieStorage
    }
    install(ContentNegotiation) {
        json(
            json = discourseJson,
            contentType = ContentType.Application.Json,
        )
    }
    // Required for request-level `onUpload` listeners used by the composer upload task. The plugin
    // stays inert for ordinary requests and executes callbacks in the request coroutine.
    install(BodyProgress)
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
    }

    HttpResponseValidator {
        handleResponseExceptionWithRequest { exception, _ ->
            if (exception !is ResponseException) return@handleResponseExceptionWithRequest

            val response = exception.response
            val bodyPrefix =
                try {
                    // Bound the channel read itself. Truncating bodyAsText() would allocate the
                    // complete untrusted error response before discarding its tail.
                    response
                        .bodyAsChannel()
                        .readDiscourseClassificationBodyPrefix()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    null
                }
            val relevantHeaders =
                buildMap {
                    response.headers[HttpHeaders.RetryAfter]?.let { put(HttpHeaders.RetryAfter, it) }
                    response.headers["cf-mitigated"]?.let { put("cf-mitigated", it) }
                }

            throw mapDiscourseResponseException(
                statusCode = response.status.value,
                headers = relevantHeaders,
                bodyPrefix = bodyPrefix,
            )
        }
    }

    // Ktor's logging plugin is intentionally absent: Cookie, Set-Cookie, CSRF, draft bodies,
    // and upload bytes must never enter application or CI logs.
    install(DiscourseDefaultHeaders)
}

private fun String?.acceptsHtml(): Boolean =
    parseHeaderValue(this).any { headerValue ->
        try {
            ContentType.parse(headerValue.value).match(ContentType.Text.Html)
        } catch (_: IllegalArgumentException) {
            false
        }
    }
