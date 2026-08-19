package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.error.DiscourseRateLimitException
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieSnapshot
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.StaleDiscourseSessionException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

internal class DiscourseHttpClientIntegrationTest {
    @Test
    fun originAndHeadersAreHardened() =
        runTest {
            var handledRequestCount = 0
            val engine =
                MockEngine { request ->
                    handledRequestCount += 1
                    assertEquals(URLProtocol.HTTPS, request.url.protocol)
                    assertEquals("linux.do", request.url.host)
                    assertEquals(ContentType.Application.Json.toString(), request.headers[HttpHeaders.Accept])
                    assertEquals("zh-CN,zh;q=0.9,en;q=0.7", request.headers[HttpHeaders.AcceptLanguage])
                    assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
                    respond(
                        content = "{}",
                        headers = jsonHeaders(),
                    )
                }
            val client = createDiscourseHttpClient(engine, DiscourseCookieStorage())

            try {
                client.get("$DISCOURSE_ORIGIN/site.json")
                client.get("https://linux.do:443/site.json")
                assertFailsWith<IllegalStateException> {
                    client.get("https://outside.invalid/site.json")
                }
                assertFailsWith<IllegalStateException> {
                    client.get("https://linux.do:444/site.json")
                }
                assertFailsWith<IllegalStateException> {
                    client.get("https://fixture-user:fixture-password@linux.do/site.json")
                }
                assertEquals(2, handledRequestCount)
            } finally {
                client.close()
            }
        }

    @Test
    fun rateLimitFailureIsSanitized() =
        runTest {
            val privateBody = "bounded fixture content must never reach an exception"
            val engine =
                MockEngine {
                    respond(
                        content = privateBody,
                        status = HttpStatusCode.TooManyRequests,
                        headers =
                            Headers.build {
                                append(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                                append(HttpHeaders.RetryAfter, "7")
                            },
                    )
                }
            val client = createDiscourseHttpClient(engine, DiscourseCookieStorage())

            try {
                val failure =
                    assertFailsWith<DiscourseRateLimitException> {
                        client.get("$DISCOURSE_ORIGIN/latest.json")
                    }

                assertEquals(7L, failure.retryAfterSeconds)
                assertFalse(failure.toString().contains(privateBody))
            } finally {
                client.close()
            }
        }

    @Test
    fun errorClassificationConsumesOnlyTheFixedBytePrefix() =
        runTest {
            val challengeMarker = "/cdn-cgi/challenge-platform/"
            val prefix = challengeMarker.padEnd(CLASSIFICATION_PREFIX_BYTES, 'x').encodeToByteArray()
            val unreadTail = "fixture tail that the classifier must not consume".encodeToByteArray()
            val responseChannel = ReadAccountingChannel(prefix + unreadTail)

            val bodyPrefix = responseChannel.readDiscourseClassificationBodyPrefix()

            assertEquals(prefix.decodeToString(), bodyPrefix)
            assertEquals(unreadTail.size.toLong(), responseChannel.remainingByteCount)
        }

    @Test
    fun lateOldResponseCannotReplaceNewAccountCookies() =
        runTest {
            val firstRequestEntered = CompletableDeferred<Unit>()
            val releaseFirstResponse = CompletableDeferred<Unit>()
            val observedCookies = mutableListOf<String?>()
            var requestCount = 0
            val engine =
                MockEngine { request ->
                    requestCount += 1
                    observedCookies += request.headers[HttpHeaders.Cookie]
                    if (requestCount == 1) {
                        firstRequestEntered.complete(Unit)
                        releaseFirstResponse.await()
                        respond(
                            content = "{}",
                            headers =
                                jsonHeaders(
                                    setCookie = "_t=late-account-a; Path=/; Secure; HttpOnly",
                                ),
                        )
                    } else {
                        respond(content = "{}", headers = jsonHeaders())
                    }
                }
            val storage = DiscourseCookieStorage()
            val manager = DiscourseSessionManager(cookieStorage = storage)
            manager.startAuthenticatedSession(
                accountId = "account-a",
                cookieSnapshot = listOf(sessionCookie("account-a-cookie")),
            )
            val client = createDiscourseHttpClient(engine, storage)

            try {
                val oldRequest =
                    async {
                        runCatching {
                            manager.runForCurrentSession {
                                // Simulate an engine response that arrives after cancellation.
                                withContext(NonCancellable) {
                                    client.get("$DISCOURSE_ORIGIN/latest.json")
                                }
                            }
                        }.exceptionOrNull()
                    }

                firstRequestEntered.await()
                manager.startAuthenticatedSession(
                    accountId = "account-b",
                    cookieSnapshot = listOf(sessionCookie("account-b-cookie")),
                )
                releaseFirstResponse.complete(Unit)

                assertIs<StaleDiscourseSessionException>(oldRequest.await())
                assertEquals("account-b-cookie", storage.snapshot().single().value)

                manager.runForCurrentSession {
                    client.get("$DISCOURSE_ORIGIN/latest.json")
                }

                assertEquals(
                    listOf<String?>("_t=account-a-cookie", "_t=account-b-cookie"),
                    observedCookies,
                )
                assertEquals("account-b-cookie", storage.snapshot().single().value)
            } finally {
                client.close()
            }
        }

    @Test
    fun oldGenerationStartingAfterReplacementCannotReadNewAccountCookies() =
        runTest {
            val oldLeaseCaptured = CompletableDeferred<Unit>()
            val releaseOldRequest = CompletableDeferred<Unit>()
            val observedCookies = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    observedCookies += request.headers[HttpHeaders.Cookie]
                    respond(
                        content = "{}",
                        headers =
                            if (observedCookies.size == 1) {
                                jsonHeaders(setCookie = "_t=late-account-a; Path=/; Secure; HttpOnly")
                            } else {
                                jsonHeaders()
                            },
                    )
                }
            val storage = DiscourseCookieStorage()
            val manager = DiscourseSessionManager(cookieStorage = storage)
            manager.startAuthenticatedSession(
                accountId = "account-a",
                cookieSnapshot = listOf(sessionCookie("account-a-cookie")),
            )
            val client = createDiscourseHttpClient(engine, storage)

            try {
                val oldRequest =
                    async {
                        runCatching {
                            manager.runForCurrentSession {
                                withContext(NonCancellable) {
                                    oldLeaseCaptured.complete(Unit)
                                    releaseOldRequest.await()
                                    client.get("$DISCOURSE_ORIGIN/latest.json")
                                }
                            }
                        }.exceptionOrNull()
                    }

                oldLeaseCaptured.await()
                manager.startAuthenticatedSession(
                    accountId = "account-b",
                    cookieSnapshot = listOf(sessionCookie("account-b-cookie")),
                )
                releaseOldRequest.complete(Unit)

                assertIs<StaleDiscourseSessionException>(oldRequest.await())
                assertNull(observedCookies.single())
                assertEquals("account-b-cookie", storage.snapshot().single().value)

                manager.runForCurrentSession {
                    client.get("$DISCOURSE_ORIGIN/latest.json")
                }
                assertEquals(listOf(null, "_t=account-b-cookie"), observedCookies)
            } finally {
                client.close()
            }
        }

    @Test
    fun unboundClientCallCannotReadOrWriteCookies() =
        runTest {
            var observedCookie: String? = "not-called"
            val engine =
                MockEngine { request ->
                    observedCookie = request.headers[HttpHeaders.Cookie]
                    respond(
                        content = "{}",
                        headers = jsonHeaders(setCookie = "_t=unbound-response; Path=/; Secure"),
                    )
                }
            val storage = DiscourseCookieStorage()
            storage.importSnapshot(listOf(sessionCookie("bound-cookie")))
            val client = createDiscourseHttpClient(engine, storage)

            try {
                client.get("$DISCOURSE_ORIGIN/latest.json") {
                    headers.append(HttpHeaders.Cookie, "_t=caller-supplied")
                }

                assertNull(observedCookie)
                assertEquals("bound-cookie", storage.snapshot().single().value)
            } finally {
                client.close()
            }
        }
}

private const val CLASSIFICATION_PREFIX_BYTES: Int = 4_096

/**
 * Test channel whose remaining buffer is observable after the bounded classification reader runs.
 *
 * A regression to an unbounded read followed by `String.take(...)` would drain this entire buffer.
 * Keeping a known tail in the channel therefore proves that the transport read, rather than only the
 * resulting String, is bounded.
 */
@OptIn(InternalAPI::class)
private class ReadAccountingChannel(
    content: ByteArray,
) : ByteReadChannel {
    private val buffer = Buffer().apply { write(content) }

    val remainingByteCount: Long
        get() = buffer.size

    override val closedCause: Throwable? = null

    override val isClosedForRead: Boolean
        get() = buffer.exhausted()

    override val readBuffer: Source
        get() = buffer

    override suspend fun awaitContent(min: Int): Boolean = buffer.size >= min

    override fun cancel(cause: Throwable?) = Unit
}

private fun sessionCookie(value: String): DiscourseCookieSnapshot =
    DiscourseCookieSnapshot(
        name = "_t",
        value = value,
        httpOnly = true,
    )

private fun jsonHeaders(setCookie: String? = null): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setCookie?.let { append(HttpHeaders.SetCookie, it) }
    }
