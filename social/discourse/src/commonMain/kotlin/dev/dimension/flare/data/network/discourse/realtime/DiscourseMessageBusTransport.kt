package dev.dimension.flare.data.network.discourse.realtime

import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import dev.dimension.flare.data.network.discourse.error.DiscourseException
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkException
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkFailureKind
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationPhase
import dev.dimension.flare.data.network.discourse.error.mapDiscourseResponseException
import dev.dimension.flare.data.network.discourse.model.discourseJson
import dev.dimension.flare.data.network.discourse.readDiscourseClassificationBodyPrefix
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.utils.io.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Low-level transport for one Linux.do MessageBus poll per collected [Flow]. */
public interface DiscourseMessageBusTransport {
    /**
     * Returns a cold flow. Each collection performs exactly one POST and may emit several batches
     * when Discourse uses application-level chunking. Cancellation closes the request through Ktor
     * structured concurrency and is always rethrown rather than translated into a domain failure.
     */
    public fun poll(request: DiscourseMessageBusPollRequest): Flow<DiscourseMessageBusBatch>
}

/**
 * MessageBus transport backed by the same fixed-origin, cookie-protected client as the REST API.
 *
 * Same-origin polling intentionally does not accept a base URL. Linux.do session cookies therefore
 * remain governed by the existing origin guard and generation-aware cookie storage. Explicit
 * [DiscourseMessageBusEndpoint.CrossOrigin] and [DiscourseMessageBusEndpoint.SharedSession]
 * endpoints instead select a separately owned cookie-less client. Only
 * [DiscourseMessageBusEndpoint.SharedSession] adds an authentication header. HTTP 401, 403, and 429
 * responses pass through sanitized typed exceptions, including parsed `Retry-After` metadata.
 */
internal class DefaultDiscourseMessageBusTransport(
    private val client: HttpClient,
    private val frameDecoder: DiscourseMessageBusFrameDecoder = DiscourseMessageBusFrameDecoder(),
    private val batchDecoder: DiscourseMessageBusBatchDecoder = DiscourseMessageBusBatchDecoder(),
    crossOriginClientFactory: () -> HttpClient = ::createCookieLessMessageBusClient,
) : DiscourseMessageBusTransport {
    private val crossOriginClient: Lazy<HttpClient> = lazy(crossOriginClientFactory)

    override fun poll(request: DiscourseMessageBusPollRequest): Flow<DiscourseMessageBusBatch> =
        flow {
            translateMessageBusTransportFailures {
                val endpoint = request.endpoint
                val requestClient =
                    when (endpoint) {
                        DiscourseMessageBusEndpoint.SameOrigin -> client
                        is DiscourseMessageBusEndpoint.CrossOrigin -> crossOriginClient.value
                        is DiscourseMessageBusEndpoint.SharedSession -> crossOriginClient.value
                    }
                val origin =
                    when (endpoint) {
                        DiscourseMessageBusEndpoint.SameOrigin -> DISCOURSE_ORIGIN
                        is DiscourseMessageBusEndpoint.CrossOrigin -> endpoint.pollingOrigin
                        is DiscourseMessageBusEndpoint.SharedSession -> endpoint.pollingOrigin
                    }
                requestClient
                    .preparePost("$origin/message-bus/${request.clientId}/poll") {
                        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                        header(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9,en;q=0.7")
                        header("X-Requested-With", "XMLHttpRequest")
                        header("X-SILENCE-LOGGER", "true")
                        if (endpoint is DiscourseMessageBusEndpoint.SharedSession) {
                            header("X-Shared-Session-Key", endpoint.headerValue())
                        }
                        setBody(
                            TextContent(
                                text = request.toJsonBody(),
                                contentType = ContentType.Application.Json,
                            ),
                        )
                    }.execute { response ->
                        // The execute callback opts out of Ktor's default SaveBody buffering. The
                        // decoder must see the live channel so its frame and cumulative byte limits
                        // are actual network bounds rather than checks applied after full buffering.
                        val bodyChannel = response.bodyAsChannel()
                        try {
                            val contentType = response.contentType()
                            val framed =
                                when {
                                    contentType?.match(ContentType.Application.Json) == true -> false
                                    contentType?.match(ContentType.Text.Plain) == true -> true
                                    else -> throw DiscourseSerializationException(DiscourseSerializationPhase.ResponseDecoding)
                                }
                            frameDecoder.decode(
                                channel = bodyChannel,
                                framed = framed,
                            ) { frame ->
                                val batch = batchDecoder.decode(frame, request.channels.keys)
                                try {
                                    emit(batch)
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (collectorFailure: Throwable) {
                                    throw DiscourseMessageBusCollectorFailure(collectorFailure)
                                }
                            }
                        } finally {
                            // The streaming statement also performs cleanup after this callback,
                            // while this cancellation releases the body before failure translation.
                            bodyChannel.cancel()
                        }
                    }
            }
        }

    private fun DiscourseMessageBusPollRequest.toJsonBody(): String {
        val payload =
            buildMap<String, JsonPrimitive> {
                channels.forEach { (channel, cursor) -> put(channel, JsonPrimitive(cursor)) }
                put("__seq", JsonPrimitive(sequence))
            }
        return discourseJson.encodeToString(JsonObject.serializer(), JsonObject(payload))
    }

    /** Closes only the optional cookie-less client; ownership of the shared REST client stays external. */
    fun close() {
        if (crossOriginClient.isInitialized()) crossOriginClient.value.close()
    }
}

/**
 * Builds the cross-origin client without any cookie plugin or cookie storage.
 *
 * It deliberately has less configuration than the REST client: no redirects, no logging, and no
 * content conversion. The endpoint/key request builder above is the only authentication input.
 */
private fun createCookieLessMessageBusClient(): HttpClient =
    HttpClient {
        expectSuccess = true
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
        HttpResponseValidator {
            handleResponseExceptionWithRequest { exception, _ ->
                if (exception !is ResponseException) return@handleResponseExceptionWithRequest
                val response = exception.response
                val bodyPrefix =
                    try {
                        response.bodyAsChannel().readDiscourseClassificationBodyPrefix()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        null
                    }
                val headers =
                    buildMap {
                        response.headers[HttpHeaders.RetryAfter]?.let { put(HttpHeaders.RetryAfter, it) }
                        response.headers["cf-mitigated"]?.let { put("cf-mitigated", it) }
                    }
                throw mapDiscourseResponseException(
                    statusCode = response.status.value,
                    headers = headers,
                    bodyPrefix = bodyPrefix,
                )
            }
        }
    }

/** Keeps downstream collector failures outside transport/network exception classification. */
private class DiscourseMessageBusCollectorFailure(
    val original: Throwable,
) : RuntimeException()

private suspend fun <T> translateMessageBusTransportFailures(block: suspend () -> T): T =
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (collectorFailure: DiscourseMessageBusCollectorFailure) {
        throw collectorFailure.original
    } catch (known: DiscourseException) {
        throw known
    } catch (_: HttpRequestTimeoutException) {
        throw DiscourseNetworkException(DiscourseNetworkFailureKind.Timeout)
    } catch (_: ConnectTimeoutException) {
        throw DiscourseNetworkException(DiscourseNetworkFailureKind.Timeout)
    } catch (_: SocketTimeoutException) {
        throw DiscourseNetworkException(DiscourseNetworkFailureKind.Timeout)
    } catch (_: SerializationException) {
        throw DiscourseSerializationException(DiscourseSerializationPhase.ResponseDecoding)
    } catch (_: IOException) {
        throw DiscourseNetworkException(DiscourseNetworkFailureKind.Connection)
    }
