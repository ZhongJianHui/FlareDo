package dev.dimension.flare.data.network.discourse.realtime

import dev.dimension.flare.data.network.discourse.createDiscourseHttpClient
import dev.dimension.flare.data.network.discourse.error.DiscourseAuthenticationException
import dev.dimension.flare.data.network.discourse.error.DiscoursePermissionException
import dev.dimension.flare.data.network.discourse.error.DiscourseRateLimitException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.model.discourseJson
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieSnapshot
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class DiscourseMessageBusTransportTest {
    @Test
    fun pollUsesFixedProtectedOriginAndExactJsonContract() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("https", request.url.protocol.name)
                    assertEquals("linux.do", request.url.host)
                    assertEquals("/message-bus/$CLIENT_ID/poll", request.url.encodedPath)
                    assertEquals(ContentType.Application.Json, request.body.contentType)
                    assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
                    assertEquals("_t=fixture-session", request.headers[HttpHeaders.Cookie])
                    assertEquals(null, request.headers["X-Shared-Session-Key"])

                    val body =
                        discourseJson
                            .parseToJsonElement(request.body.toByteArray().decodeToString())
                            .jsonObject
                    assertEquals(setOf("/latest", "/topic/42", "__seq"), body.keys)
                    assertEquals(
                        -1L,
                        body
                            .getValue("/latest")
                            .jsonPrimitive.content
                            .toLong(),
                    )
                    assertEquals(
                        17L,
                        body
                            .getValue("/topic/42")
                            .jsonPrimitive.content
                            .toLong(),
                    )
                    assertEquals(
                        9L,
                        body
                            .getValue("__seq")
                            .jsonPrimitive.content
                            .toLong(),
                    )

                    respond(
                        content =
                            """[{"global_id":21,"message_id":17,"channel":"/topic/42",""" +
                                """"data":{"post_id":8},"ignored_private":"discard"}]""",
                        headers = jsonHeaders(),
                    )
                }
            val cookieStorage = DiscourseCookieStorage()
            val sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage)
            sessionManager.startAuthenticatedSession(
                accountId = "42",
                cookieSnapshot =
                    listOf(
                        DiscourseCookieSnapshot(
                            name = "_t",
                            value = "fixture-session",
                            httpOnly = true,
                        ),
                    ),
            )
            val client = createDiscourseHttpClient(engine, cookieStorage)

            try {
                val batches =
                    sessionManager.runForCurrentSession {
                        DefaultDiscourseMessageBusTransport(client)
                            .poll(
                                pollRequest(
                                    sequence = 9L,
                                    channels = linkedMapOf("/latest" to -1L, "/topic/42" to 17L),
                                ),
                            ).toList()
                    }

                assertEquals(1, batches.size)
                val message = assertIs<DiscourseMessageBusMessage>(batches.single().events.single())
                assertEquals(21L, message.globalId)
                assertEquals(17L, message.messageId)
                assertEquals("/topic/42", message.channel)
                assertEquals(
                    8L,
                    message.data.jsonObject
                        .getValue("post_id")
                        .jsonPrimitive.content
                        .toLong(),
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun sharedSessionEndpointUsesSeparateCookieLessClientAndRedactsKey() =
        runTest {
            var protectedClientCalls = 0
            val protectedEngine =
                MockEngine {
                    protectedClientCalls += 1
                    error("Protected cookie client must never reach a cross-origin MessageBus host")
                }
            val crossEngine =
                MockEngine { request ->
                    assertEquals("events.example.test", request.url.host)
                    assertEquals("/message-bus/$CLIENT_ID/poll", request.url.encodedPath)
                    assertEquals(null, request.headers[HttpHeaders.Cookie])
                    assertEquals(SHARED_SESSION_KEY, request.headers["X-Shared-Session-Key"])
                    respond(content = "[]", headers = jsonHeaders())
                }
            val protectedClient = createDiscourseHttpClient(protectedEngine, DiscourseCookieStorage())
            val cookieLessClient = HttpClient(crossEngine) { expectSuccess = true }
            val endpoint =
                DiscourseMessageBusEndpoint.SharedSession(
                    pollingOrigin = "https://events.example.test/",
                    sharedSessionKey = SHARED_SESSION_KEY,
                )
            val transport =
                DefaultDiscourseMessageBusTransport(
                    client = protectedClient,
                    crossOriginClientFactory = { cookieLessClient },
                )

            try {
                val result =
                    transport
                        .poll(pollRequest(endpoint = endpoint))
                        .toList()

                assertEquals(1, result.size)
                assertTrue(result.single().events.isEmpty())
                assertEquals(0, protectedClientCalls)
                assertTrue(endpoint.toString().contains("<redacted>"))
                assertTrue(!endpoint.toString().contains(SHARED_SESSION_KEY))
            } finally {
                transport.close()
                protectedClient.close()
            }
        }

    @Test
    fun anonymousCrossOriginEndpointUsesSeparateCookieLessClientWithoutSharedKey() =
        runTest {
            var protectedClientCalls = 0
            val protectedEngine =
                MockEngine {
                    protectedClientCalls += 1
                    error("Protected cookie client must never reach a cross-origin MessageBus host")
                }
            val crossEngine =
                MockEngine { request ->
                    assertEquals("events.example.test", request.url.host)
                    assertEquals("/message-bus/$CLIENT_ID/poll", request.url.encodedPath)
                    assertEquals(null, request.headers[HttpHeaders.Cookie])
                    assertEquals(null, request.headers["X-Shared-Session-Key"])
                    respond(content = "[]", headers = jsonHeaders())
                }
            val protectedClient = createDiscourseHttpClient(protectedEngine, DiscourseCookieStorage())
            val cookieLessClient = HttpClient(crossEngine) { expectSuccess = true }
            val transport =
                DefaultDiscourseMessageBusTransport(
                    client = protectedClient,
                    crossOriginClientFactory = { cookieLessClient },
                )

            try {
                val result =
                    transport
                        .poll(
                            pollRequest(
                                endpoint =
                                    DiscourseMessageBusEndpoint.CrossOrigin(
                                        "https://events.example.test/",
                                    ),
                            ),
                        ).toList()

                assertEquals(1, result.size)
                assertTrue(result.single().events.isEmpty())
                assertEquals(0, protectedClientCalls)
            } finally {
                transport.close()
                protectedClient.close()
            }
        }

    @Test
    fun chunkedResponseEmitsEveryBatchAcrossArbitraryNetworkFragments() =
        runTest {
            val first = "[]"
            val second =
                """[{"global_id":31,"message_id":4,"channel":"/latest","data":{"topic_id":7}},""" +
                    """{"global_id":-1,"message_id":-1,"channel":"/__status","data":{"/latest":4,"/new":9}}]"""
            val wire = "$first$SEPARATOR$second$SEPARATOR".encodeToByteArray()
            val engine =
                MockEngine {
                    respond(
                        content = FragmentedByteReadChannel(wire.map { byteArrayOf(it) }),
                        headers = textHeaders(),
                    )
                }
            val client = createDiscourseHttpClient(engine, DiscourseCookieStorage())

            try {
                val batches =
                    DefaultDiscourseMessageBusTransport(client)
                        .poll(pollRequest(channels = mapOf("/latest" to -1L, "/new" to -1L)))
                        .toList()

                assertEquals(2, batches.size)
                assertTrue(batches[0].events.isEmpty())
                val message = assertIs<DiscourseMessageBusMessage>(batches[1].events[0])
                val status = assertIs<DiscourseMessageBusStatus>(batches[1].events[1])
                assertEquals(4L, message.messageId)
                assertEquals(mapOf("/latest" to 4L, "/new" to 9L), status.cursors)
            } finally {
                client.close()
            }
        }

    @Test
    fun duplicateMessagesRemainVisibleForCoordinatorCursorDeduplication() =
        runTest {
            val duplicate =
                """{"global_id":51,"message_id":12,"channel":"/latest","data":{"topic_id":77}}"""
            val engine =
                MockEngine {
                    respond(content = "[$duplicate,$duplicate]", headers = jsonHeaders())
                }
            val client = createDiscourseHttpClient(engine, DiscourseCookieStorage())

            try {
                val events =
                    DefaultDiscourseMessageBusTransport(client)
                        .poll(pollRequest())
                        .toList()
                        .single()
                        .events

                assertEquals(2, events.size)
                assertEquals(listOf(12L, 12L), events.map(DiscourseMessageBusEvent::messageId))
            } finally {
                client.close()
            }
        }

    @Test
    fun successfulPollCancelsTheConsumedResponseBody() =
        runTest {
            val body = TrackingByteReadChannel("[]".asFragmentedChannel())
            val engine = MockEngine { respond(content = body, headers = jsonHeaders()) }
            val client = createDiscourseHttpClient(engine, DiscourseCookieStorage())

            try {
                assertEquals(
                    1,
                    DefaultDiscourseMessageBusTransport(client).poll(pollRequest()).toList().size,
                )
                assertTrue(body.cancellationCauses.isNotEmpty())
            } finally {
                client.close()
            }
        }

    @Test
    fun decodeAndResponseLimitFailuresCancelTheResponseBody() =
        runTest {
            suspend fun verify(
                content: String,
                frameDecoder: DiscourseMessageBusFrameDecoder = DiscourseMessageBusFrameDecoder(),
            ) {
                val body = TrackingByteReadChannel(content.asFragmentedChannel())
                val engine = MockEngine { respond(content = body, headers = jsonHeaders()) }
                val client = createDiscourseHttpClient(engine, DiscourseCookieStorage())

                try {
                    assertFailsWith<DiscourseSerializationException> {
                        DefaultDiscourseMessageBusTransport(
                            client = client,
                            frameDecoder = frameDecoder,
                        ).poll(pollRequest()).toList()
                    }
                    assertTrue(body.cancellationCauses.isNotEmpty())
                } finally {
                    client.close()
                }
            }

            verify(content = "[")
            verify(
                content = "123456789",
                frameDecoder = DiscourseMessageBusFrameDecoder(maxFrameBytes = 8, maxResponseBytes = 8),
            )
        }

    @Test
    fun unsupportedContentTypeCancelsTheUnreadResponseBody() =
        runTest {
            val body = TrackingByteReadChannel("unread response".asFragmentedChannel())
            val engine =
                MockEngine {
                    respond(
                        content = body,
                        headers =
                            Headers.build {
                                append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                            },
                    )
                }
            val client = createDiscourseHttpClient(engine, DiscourseCookieStorage())

            try {
                assertFailsWith<DiscourseSerializationException> {
                    DefaultDiscourseMessageBusTransport(client).poll(pollRequest()).toList()
                }
                assertTrue(body.cancellationCauses.isNotEmpty())
            } finally {
                client.close()
            }
        }

    @Test
    fun downstreamEmitFailureCancelsTheResponseBodyWithoutTranslation() =
        runTest {
            val body =
                TrackingByteReadChannel(
                    FragmentedByteReadChannel(
                        listOf(
                            "[]$SEPARATOR".encodeToByteArray(),
                            "[]$SEPARATOR".encodeToByteArray(),
                        ),
                    ),
                )
            val engine = MockEngine { respond(content = body, headers = textHeaders()) }
            val client = createDiscourseHttpClient(engine, DiscourseCookieStorage())
            val expected = IOException("fixture collector failure")

            try {
                val failure =
                    assertFailsWith<IOException> {
                        DefaultDiscourseMessageBusTransport(client)
                            .poll(pollRequest())
                            .collect { throw expected }
                    }

                assertSame(expected, failure)
                assertTrue(body.cancellationCauses.isNotEmpty())
            } finally {
                client.close()
            }
        }

    @Test
    fun typedAuthenticationPermissionAndRateLimitFailuresPassThrough() =
        runTest {
            assertIs<DiscourseAuthenticationException>(pollFailure(HttpStatusCode.Unauthorized))
            assertIs<DiscoursePermissionException>(pollFailure(HttpStatusCode.Forbidden))
            val rateLimit =
                assertIs<DiscourseRateLimitException>(
                    pollFailure(HttpStatusCode.TooManyRequests, retryAfter = "23"),
                )
            assertEquals(23L, rateLimit.retryAfterSeconds)
        }

    @Test
    fun cancellationFromResponseChannelIsRethrownWithoutDomainTranslation() =
        runTest {
            val cancellingBody = CancellingByteReadChannel()
            val trackedBody = TrackingByteReadChannel(cancellingBody)
            val engine =
                MockEngine {
                    respond(content = trackedBody, headers = textHeaders())
                }
            val client = createDiscourseHttpClient(engine, DiscourseCookieStorage())

            try {
                val failure =
                    assertFailsWith<CancellationException> {
                        DefaultDiscourseMessageBusTransport(client).poll(pollRequest()).toList()
                    }

                assertEquals(CANCELLATION_MESSAGE, failure.message)
                // Ktor may copy a channel CancellationException at more than one coroutine
                // boundary, but every copy retains the source in its cause chain. The transport
                // must not replace that chain with a network or serialization failure.
                assertTrue(
                    generateSequence<Throwable>(failure, Throwable::cause)
                        .any { it === cancellingBody.failure },
                )
                assertTrue(trackedBody.cancellationCauses.isNotEmpty())
            } finally {
                client.close()
            }
        }

    @Test
    fun invalidRequestIdentityChannelAndSequenceFailBeforeNetwork() {
        assertFailsWith<IllegalArgumentException> {
            DiscourseMessageBusPollRequest(
                clientId = "not-a-client-id",
                sequence = 1L,
                channels = mapOf("/latest" to -1L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            pollRequest(sequence = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            pollRequest(channels = mapOf("latest" to -1L))
        }
        assertFailsWith<IllegalArgumentException> {
            pollRequest(channels = mapOf("/__status" to -1L))
        }
        assertFailsWith<IllegalArgumentException> {
            pollRequest(channels = mapOf("/latest" to -1_001L))
        }
    }

    @Test
    fun crossOriginEndpointsRejectUnsafeOriginsAndSharedKeys() {
        listOf(
            "http://events.example.test",
            "https://linux.do",
            "https://fixture-user@events.example.test",
            "https://events.example.test:444",
            "https://events.example.test/path",
            "https://events.example.test?query=1",
            "https://events.example.test#fragment",
            "https://localhost",
            "https://127.0.0.1",
        ).forEach { origin ->
            assertFailsWith<IllegalArgumentException>(origin) {
                DiscourseMessageBusEndpoint.CrossOrigin(origin)
            }
            assertFailsWith<IllegalArgumentException>(origin) {
                DiscourseMessageBusEndpoint.SharedSession(origin, SHARED_SESSION_KEY)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            DiscourseMessageBusEndpoint.SharedSession(
                pollingOrigin = "https://events.example.test",
                sharedSessionKey = "fixture-key-that-is-not-lowercase-hex",
            )
        }
    }

    @Test
    fun malformedOrUnexpectedResponseEnvelopeFailsClosed() {
        val decoder = DiscourseMessageBusBatchDecoder()
        val expected = setOf("/latest")
        val invalidFixtures =
            listOf(
                // Missing stable identity.
                """[{"message_id":1,"channel":"/latest","data":{}}]""",
                // String-encoded IDs are not coerced.
                """[{"global_id":"1","message_id":1,"channel":"/latest","data":{}}]""",
                """[{"global_id":1,"message_id":"1","channel":"/latest","data":{}}]""",
                // A server response cannot inject an unsubscribed channel.
                """[{"global_id":1,"message_id":1,"channel":"/new","data":{}}]""",
                // Status has fixed synthetic identities and numeric cursor values.
                """[{"global_id":1,"message_id":-1,"channel":"/__status","data":{"/latest":1}}]""",
                """[{"global_id":-1,"message_id":-1,"channel":"/__status","data":{"/latest":"1"}}]""",
                """[{"global_id":-1,"message_id":-1,"channel":"/__status","data":{"/new":1}}]""",
            )

        invalidFixtures.forEach { fixture ->
            assertFailsWith<DiscourseSerializationException> {
                decoder.decode(fixture.encodeToByteArray(), expected)
            }
        }
    }

    private suspend fun pollFailure(
        status: HttpStatusCode,
        retryAfter: String? = null,
    ): Throwable {
        val engine =
            MockEngine {
                respond(
                    content = "{}",
                    status = status,
                    headers =
                        Headers.build {
                            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            retryAfter?.let { append(HttpHeaders.RetryAfter, it) }
                        },
                )
            }
        val client = createDiscourseHttpClient(engine, DiscourseCookieStorage())
        return try {
            assertFailsWith<Throwable> {
                DefaultDiscourseMessageBusTransport(client).poll(pollRequest()).toList()
            }
        } finally {
            client.close()
        }
    }
}

internal class DiscourseMessageBusFrameDecoderTest {
    @Test
    fun escapedDelimiterIsRestoredWithoutEndingFrame() =
        runTest {
            val decoder = DiscourseMessageBusFrameDecoder(maxFrameBytes = 64, maxResponseBytes = 128)
            val wire = "before${ESCAPED_SEPARATOR}after$SEPARATOR".encodeToByteArray()
            val frames = mutableListOf<ByteArray>()

            decoder.decode(
                channel = FragmentedByteReadChannel(wire.map { byteArrayOf(it) }),
                framed = true,
                onFrame = { frames += it },
            )

            assertEquals(1, frames.size)
            assertEquals("before${SEPARATOR}after", frames.single().decodeToString())
        }

    @Test
    fun frameAndCumulativeResponseLimitsAreAppliedToBytes() =
        runTest {
            assertFailsWith<DiscourseSerializationException> {
                DiscourseMessageBusFrameDecoder(maxFrameBytes = 4, maxResponseBytes = 16).decode(
                    channel = FragmentedByteReadChannel(listOf("12345".encodeToByteArray())),
                    framed = false,
                    onFrame = {},
                )
            }

            assertFailsWith<DiscourseSerializationException> {
                DiscourseMessageBusFrameDecoder(maxFrameBytes = 8, maxResponseBytes = 12).decode(
                    channel =
                        FragmentedByteReadChannel(
                            listOf("1234$SEPARATOR".encodeToByteArray(), "5678$SEPARATOR".encodeToByteArray()),
                        ),
                    framed = true,
                    onFrame = {},
                )
            }
        }

    @Test
    fun truncatedFramedResponseIsRejected() =
        runTest {
            assertFailsWith<DiscourseSerializationException> {
                DiscourseMessageBusFrameDecoder(maxFrameBytes = 32, maxResponseBytes = 64).decode(
                    channel = FragmentedByteReadChannel(listOf("[]\r\n|".encodeToByteArray())),
                    framed = true,
                    onFrame = {},
                )
            }
        }
}

private const val CLIENT_ID: String = "0123456789ab4def8abc0123456789ab"
private const val SEPARATOR: String = "\r\n|\r\n"
private const val ESCAPED_SEPARATOR: String = "\r\n||\r\n"
private const val CANCELLATION_MESSAGE: String = "fixture cancellation"
private const val SHARED_SESSION_KEY: String = "0123456789abcdef0123456789abcdef"

private fun pollRequest(
    sequence: Long = 1L,
    channels: Map<String, Long> = mapOf("/latest" to DISCOURSE_MESSAGE_BUS_INITIAL_CURSOR),
    endpoint: DiscourseMessageBusEndpoint = DiscourseMessageBusEndpoint.SameOrigin,
): DiscourseMessageBusPollRequest =
    DiscourseMessageBusPollRequest(
        clientId = CLIENT_ID,
        sequence = sequence,
        channels = channels,
        endpoint = endpoint,
    )

private fun jsonHeaders(): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }

private fun textHeaders(): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
    }

private fun String.asFragmentedChannel(): ByteReadChannel = FragmentedByteReadChannel(listOf(encodeToByteArray()))

@OptIn(InternalAPI::class)
private class TrackingByteReadChannel(
    private val delegate: ByteReadChannel,
) : ByteReadChannel by delegate {
    val cancellationCauses: MutableList<Throwable?> = mutableListOf()

    override fun cancel(cause: Throwable?) {
        cancellationCauses += cause
        delegate.cancel(cause)
    }
}

/** Supplies a new fragment only after Ktor has consumed the previous fragment. */
@OptIn(InternalAPI::class)
private class FragmentedByteReadChannel(
    fragments: List<ByteArray>,
) : ByteReadChannel {
    private val pendingFragments: List<ByteArray> = fragments.map { it.copyOf() }
    private val buffer: Buffer = Buffer()
    private var nextFragment: Int = 0

    init {
        loadUntilReadable(1)
    }

    override val closedCause: Throwable? = null

    override val isClosedForRead: Boolean
        get() = buffer.exhausted() && nextFragment >= pendingFragments.size

    override val readBuffer: Source
        get() = buffer

    override suspend fun awaitContent(min: Int): Boolean = loadUntilReadable(min)

    override fun cancel(cause: Throwable?) = Unit

    private fun loadUntilReadable(min: Int): Boolean {
        while (buffer.size < min && nextFragment < pendingFragments.size) {
            buffer.write(pendingFragments[nextFragment])
            nextFragment += 1
        }
        return buffer.size >= min
    }
}

@OptIn(InternalAPI::class)
private class CancellingByteReadChannel : ByteReadChannel {
    private val buffer: Buffer = Buffer()
    val failure: CancellationException = CancellationException(CANCELLATION_MESSAGE)

    override val closedCause: Throwable? = null
    override val isClosedForRead: Boolean = false
    override val readBuffer: Source
        get() = buffer

    override suspend fun awaitContent(min: Int): Boolean = throw failure

    override fun cancel(cause: Throwable?) = Unit
}
