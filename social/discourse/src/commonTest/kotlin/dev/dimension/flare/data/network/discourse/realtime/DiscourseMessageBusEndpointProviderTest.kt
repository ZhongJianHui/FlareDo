package dev.dimension.flare.data.network.discourse.realtime

import dev.dimension.flare.data.network.discourse.createDiscourseHttpClient
import dev.dimension.flare.data.network.discourse.error.DiscourseAuthenticationException
import dev.dimension.flare.data.network.discourse.error.DiscourseCloudflareChallengeException
import dev.dimension.flare.data.network.discourse.error.DiscoursePermissionException
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieSnapshot
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.StaleDiscourseSessionException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class DiscourseMessageBusEndpointProviderTest {
    @Test
    fun guestDiscoversPublicCookieLessCrossOrigin() =
        runTest {
            val client =
                bootstrapClient { request ->
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("linux.do", request.url.host)
                    assertEquals("/", request.url.encodedPath)
                    assertEquals(ContentType.Text.Html.toString(), request.headers[HttpHeaders.Accept])
                    assertNull(request.headers["X-Requested-With"])
                    assertNull(request.headers[HttpHeaders.Cookie])
                    respond(
                        content = bootstrapHtml(sharedSessionKey = null),
                        headers = htmlHeaders(),
                    )
                }

            try {
                val manager = DiscourseSessionManager()
                val endpoint = resolve(client, manager)

                assertEquals(
                    "https://events.example.test",
                    assertIs<DiscourseMessageBusEndpoint.CrossOrigin>(endpoint).pollingOrigin,
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun authenticatedDiscoveryUsesProtectedCookieAndReturnsFreshSharedSession() =
        runTest {
            val storage = DiscourseCookieStorage()
            val manager = DiscourseSessionManager(cookieStorage = storage)
            manager.startAuthenticatedSession(
                accountId = "42",
                cookieSnapshot = listOf(sessionCookie()),
            )
            var requestCount = 0
            val client =
                bootstrapClient(storage) { request ->
                    requestCount += 1
                    assertEquals("_t=fixture-session", request.headers[HttpHeaders.Cookie])
                    assertNull(request.headers["X-Requested-With"])
                    respond(
                        content = bootstrapHtml(sharedSessionKey = SHARED_SESSION_KEY),
                        headers = htmlHeaders(),
                    )
                }

            val first = resolve(client, manager)
            val second = resolve(client, manager)
            try {
                val firstShared = assertIs<DiscourseMessageBusEndpoint.SharedSession>(first)
                val secondShared = assertIs<DiscourseMessageBusEndpoint.SharedSession>(second)
                assertTrue(firstShared !== secondShared)
                assertEquals("https://events.example.test", firstShared.pollingOrigin)
                assertEquals(SHARED_SESSION_KEY, firstShared.headerValue())
                assertEquals(2, requestCount)
            } finally {
                (first as? DiscourseMessageBusEndpoint.SharedSession)?.clear()
                (second as? DiscourseMessageBusEndpoint.SharedSession)?.clear()
                client.close()
            }
        }

    @Test
    fun authenticatedCrossOriginWithoutKeyFailsSoftToSameOrigin() =
        runTest {
            val manager = DiscourseSessionManager()
            manager.startAuthenticatedSession(accountId = "42")
            val client =
                bootstrapClient {
                    respond(
                        content = bootstrapHtml(sharedSessionKey = null),
                        headers = htmlHeaders(),
                    )
                }

            try {
                assertSame(DiscourseMessageBusEndpoint.SameOrigin, resolve(client, manager))
            } finally {
                client.close()
            }
        }

    @Test
    fun malformedMissingWrongContentTypeAndNetworkFailuresFailSoft() =
        runTest {
            val responses =
                listOf<suspend () -> HttpClient>(
                    {
                        bootstrapClient {
                            respond(content = "<html></html>", headers = htmlHeaders())
                        }
                    },
                    {
                        bootstrapClient {
                            respond(
                                content = "<script id=data-preloaded type=application/json>{broken</script>",
                                headers = htmlHeaders(),
                            )
                        }
                    },
                    {
                        bootstrapClient {
                            respond(content = bootstrapHtml(SHARED_SESSION_KEY), headers = jsonHeaders())
                        }
                    },
                    {
                        bootstrapClient { throw IOException("fixture connection failure") }
                    },
                )

            responses.forEach { clientFactory ->
                val client = clientFactory()
                try {
                    assertSame(
                        DiscourseMessageBusEndpoint.SameOrigin,
                        resolve(client, DiscourseSessionManager()),
                    )
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun oversizedBootstrapIsRejectedBeforeParsing() =
        runTest {
            val oversized = "x".repeat(MAX_DISCOURSE_BOOTSTRAP_HTML_BYTES.toInt() + 1)
            val client =
                bootstrapClient {
                    respond(
                        content = oversized,
                        headers = htmlHeaders(includeContentLength = false),
                    )
                }

            try {
                assertSame(
                    DiscourseMessageBusEndpoint.SameOrigin,
                    resolve(client, DiscourseSessionManager()),
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun explicitTerminalFailuresPropagateToSessionRecovery() =
        runTest {
            val fixtures =
                listOf(
                    TerminalFailureFixture(
                        status = HttpStatusCode.Unauthorized,
                        headers = htmlHeaders(),
                        expectedType = DiscourseAuthenticationException::class,
                    ),
                    TerminalFailureFixture(
                        status = HttpStatusCode.Forbidden,
                        headers = htmlHeaders(),
                        expectedType = DiscoursePermissionException::class,
                    ),
                    TerminalFailureFixture(
                        status = HttpStatusCode.Forbidden,
                        headers =
                            Headers.build {
                                append(HttpHeaders.ContentType, ContentType.Text.Html.toString())
                                append("cf-mitigated", "challenge")
                            },
                        expectedType = DiscourseCloudflareChallengeException::class,
                    ),
                )

            fixtures.forEach { fixture ->
                val client =
                    bootstrapClient {
                        respond(
                            content = "fixture terminal response",
                            status = fixture.status,
                            headers = fixture.headers,
                        )
                    }
                try {
                    val failure =
                        assertFailsWith<Throwable> {
                            resolve(client, DiscourseSessionManager())
                        }
                    assertTrue(fixture.expectedType.isInstance(failure))
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun generationChangeCancelsInFlightBootstrapRequest() =
        runTest {
            val requestStarted = CompletableDeferred<Unit>()
            val requestCancelled = CompletableDeferred<Unit>()
            val manager = DiscourseSessionManager()
            manager.startAuthenticatedSession(accountId = "42")
            val client =
                bootstrapClient {
                    requestStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        requestCancelled.complete(Unit)
                    }
                }

            try {
                supervisorScope {
                    // The resolution must remain a real structured child so logout cancels its
                    // request. Supervision only prevents its expected domain failure from cancelling
                    // runTest before this assertion can observe the completed Deferred.
                    val resolution = async { resolve(client, manager) }
                    requestStarted.await()
                    manager.logout()

                    assertFailsWith<StaleDiscourseSessionException> { resolution.await() }
                    requestCancelled.await()
                }
            } finally {
                client.close()
            }
        }

    private suspend fun resolve(
        client: HttpClient,
        manager: DiscourseSessionManager,
    ): DiscourseMessageBusEndpoint =
        manager.runForCurrentSession {
            DefaultDiscourseMessageBusEndpointProvider(
                client = client,
                parserDispatcher = UnconfinedTestDispatcher(),
            ).endpoint(this)
        }

    private companion object {
        const val SHARED_SESSION_KEY: String = "0123456789abcdef0123456789abcdef"
    }
}

private fun bootstrapClient(
    storage: DiscourseCookieStorage = DiscourseCookieStorage(),
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): HttpClient = createDiscourseHttpClient(MockEngine(handler), storage)

private data class TerminalFailureFixture(
    val status: HttpStatusCode,
    val headers: Headers,
    val expectedType: kotlin.reflect.KClass<out Throwable>,
)

private fun bootstrapHtml(sharedSessionKey: String?): String {
    val keyMeta =
        sharedSessionKey
            ?.let { key ->
                "<meta name=\"shared_session_key\" content=\"$key\">"
            }.orEmpty()
    return """
        <!doctype html>
        <html>
          <head>
            $keyMeta
            <script type="application/json" id="data-preloaded">
              {"siteSettings":"{\"long_polling_base_url\":\"https://events.example.test/\"}"}
            </script>
          </head>
          <body>Fixture</body>
        </html>
        """.trimIndent()
}

private fun sessionCookie(): DiscourseCookieSnapshot =
    DiscourseCookieSnapshot(
        name = "_t",
        value = "fixture-session",
        httpOnly = true,
    )

private fun htmlHeaders(includeContentLength: Boolean = false): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, "${ContentType.Text.Html}; charset=utf-8")
        if (includeContentLength) append(HttpHeaders.ContentLength, "512")
    }

private fun jsonHeaders(): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
