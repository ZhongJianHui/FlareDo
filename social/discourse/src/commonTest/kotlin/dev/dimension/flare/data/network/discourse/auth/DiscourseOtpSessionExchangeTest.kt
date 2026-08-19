package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import dev.dimension.flare.data.network.discourse.createDiscourseHttpClient
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
import io.ktor.http.parseClientCookiesHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class DiscourseOtpSessionExchangeTest {
    @Test
    fun exchangesOtpRevokesKeyBeforeIdentityAndReturnsNewCookieSnapshot() =
        runTest {
            val requestOrder = mutableListOf<String>()
            val fixture =
                exchangeFixture { request ->
                    when (request.url.encodedPath) {
                        "/session/csrf" -> {
                            requestOrder += "csrf"
                            assertEquals(HttpMethod.Get, request.method)
                            respondJson("{\"csrf\":\"csrf-fixture\"}")
                        }

                        "/session/otp/$OTP" -> {
                            requestOrder += "otp"
                            assertEquals(HttpMethod.Post, request.method)
                            assertEquals("csrf-fixture", request.headers["X-CSRF-Token"])
                            respond(
                                content = "",
                                status = HttpStatusCode.Found,
                                headers =
                                    Headers.build {
                                        append(HttpHeaders.Location, "/")
                                        append(
                                            HttpHeaders.SetCookie,
                                            "_t=new-session-cookie; Path=/; Secure; HttpOnly",
                                        )
                                    },
                            )
                        }

                        "/user-api-key/revoke" -> {
                            requestOrder += "revoke"
                            assertEquals(HttpMethod.Post, request.method)
                            assertEquals(API_KEY, request.headers["User-Api-Key"])
                            assertEquals("_t=new-session-cookie", request.headers[HttpHeaders.Cookie])
                            respondJson("{}")
                        }

                        "/session/current.json" -> {
                            requestOrder += "identity"
                            assertEquals(HttpMethod.Get, request.method)
                            assertEquals("_t=new-session-cookie", request.headers[HttpHeaders.Cookie])
                            respondJson(CURRENT_SESSION_JSON)
                        }

                        else -> {
                            error("Unexpected fake-service request")
                        }
                    }
                }

            try {
                val accepted = acceptedRedirect()
                val result = fixture.transport.exchange(accepted, expectedGeneration = 0L)

                assertEquals(listOf("csrf", "otp", "revoke", "identity"), requestOrder)
                assertEquals("42", result.accountId)
                assertEquals("fixture-user", result.username)
                assertEquals("Fixture User", result.displayName)
                assertEquals(CLIENT_ID, result.clientId)
                assertEquals(4, result.apiVersion)
                assertEquals("new-session-cookie", result.copyCookies().single { it.name == "_t" }.value)
                assertFalse(result.toString().contains("new-session-cookie"))
                assertFalse(result.toString().contains(API_KEY))
                assertFailsWith<IllegalStateException> { accepted.secrets.copyApiKey() }
            } finally {
                fixture.close()
            }
        }

    @Test
    fun refusesA302ThatDoesNotInstallANewRootSessionCookie() =
        runTest {
            val requestOrder = mutableListOf<String>()
            val fixture =
                exchangeFixture { request ->
                    when (request.url.encodedPath) {
                        "/session/csrf" -> {
                            requestOrder += "csrf"
                            respondJson("{\"csrf\":\"csrf-fixture\"}")
                        }

                        "/session/otp/$OTP" -> {
                            requestOrder += "otp"
                            respond(content = "", status = HttpStatusCode.Found)
                        }

                        else -> {
                            error("Revoke and identity must not run without a new _t")
                        }
                    }
                }

            try {
                val failure =
                    assertFailsWith<DiscourseAuthExchangeException> {
                        fixture.transport.exchange(acceptedRedirect(), expectedGeneration = 0L)
                    }
                assertEquals(DiscourseAuthExchangeFailure.SessionCookie, failure.reason)
                assertEquals(listOf("csrf", "otp"), requestOrder)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun explicitCloudflareChallengeCanReplayTheWholeExchangeOnlyOnce() =
        runTest {
            var challengeCount = 0
            var csrfRequestCount = 0
            var otpRequestCount = 0
            val requestOrder = mutableListOf<String>()
            val fixture =
                exchangeFixture(
                    challengeHandlerFactory = { sessionManager ->
                        manualChallengeCookieHandler(sessionManager) { fixedOrigin ->
                            assertEquals(DISCOURSE_ORIGIN, fixedOrigin)
                            challengeCount += 1
                        }
                    },
                ) { request ->
                    when (request.url.encodedPath) {
                        "/session/csrf" -> {
                            csrfRequestCount += 1
                            requestOrder += "csrf"
                            val requestCookies = request.cookies()
                            if (csrfRequestCount == 1) {
                                assertEquals(null, requestCookies["cf_clearance"])
                            } else {
                                assertEquals("bridged-clearance", requestCookies["cf_clearance"])
                            }
                            respondJson("{\"csrf\":\"fresh-csrf\"}")
                        }

                        "/session/otp/$OTP" -> {
                            otpRequestCount += 1
                            val requestCookies = request.cookies()
                            if (otpRequestCount == 1) {
                                assertEquals(null, requestCookies["cf_clearance"])
                                requestOrder += "challenge"
                                respondCloudflareChallenge()
                            } else {
                                assertEquals("bridged-clearance", requestCookies["cf_clearance"])
                                assertEquals(null, requestCookies["_t"])
                                requestOrder += "otp"
                                assertEquals("fresh-csrf", request.headers["X-CSRF-Token"])
                                respondWithSessionCookie()
                            }
                        }

                        "/user-api-key/revoke" -> {
                            val requestCookies = request.cookies()
                            assertEquals("new-session-cookie", requestCookies["_t"])
                            assertEquals("bridged-clearance", requestCookies["cf_clearance"])
                            requestOrder += "revoke"
                            respondJson("{}")
                        }

                        "/session/current.json" -> {
                            requestOrder += "identity"
                            respondJson(CURRENT_SESSION_JSON)
                        }

                        else -> {
                            error("Unexpected fake-service request")
                        }
                    }
                }

            try {
                fixture.transport.exchange(acceptedRedirect(), expectedGeneration = 0L)

                assertEquals(1, challengeCount)
                assertEquals(2, csrfRequestCount)
                assertEquals(2, otpRequestCount)
                assertEquals(
                    listOf("csrf", "challenge", "csrf", "otp", "revoke", "identity"),
                    requestOrder,
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun challengeDuringRevocationRetriesOnlyRevocationAndNeverReusesTheOtp() =
        runTest {
            var challengeCount = 0
            var otpRequestCount = 0
            var revokeRequestCount = 0
            val requestOrder = mutableListOf<String>()
            val fixture =
                exchangeFixture(
                    challengeHandlerFactory = { sessionManager ->
                        manualChallengeCookieHandler(sessionManager) { fixedOrigin ->
                            assertEquals(DISCOURSE_ORIGIN, fixedOrigin)
                            challengeCount += 1
                        }
                    },
                ) { request ->
                    when (request.url.encodedPath) {
                        "/session/csrf" -> {
                            requestOrder += "csrf"
                            respondJson("{\"csrf\":\"csrf-fixture\"}")
                        }

                        "/session/otp/$OTP" -> {
                            otpRequestCount += 1
                            requestOrder += "otp"
                            respondWithSessionCookie()
                        }

                        "/user-api-key/revoke" -> {
                            revokeRequestCount += 1
                            val requestCookies = request.cookies()
                            assertEquals("new-session-cookie", requestCookies["_t"])
                            if (revokeRequestCount == 1) {
                                assertEquals(null, requestCookies["cf_clearance"])
                                requestOrder += "revoke-challenge"
                                respondCloudflareChallenge()
                            } else {
                                assertEquals("bridged-clearance", requestCookies["cf_clearance"])
                                requestOrder += "revoke"
                                respondJson("{}")
                            }
                        }

                        "/session/current.json" -> {
                            requestOrder += "identity"
                            respondJson(CURRENT_SESSION_JSON)
                        }

                        else -> {
                            error("Unexpected fake-service request")
                        }
                    }
                }

            try {
                fixture.transport.exchange(acceptedRedirect(), expectedGeneration = 0L)

                assertEquals(1, challengeCount)
                assertEquals(1, otpRequestCount)
                assertEquals(2, revokeRequestCount)
                assertEquals(
                    listOf("csrf", "otp", "revoke-challenge", "revoke", "identity"),
                    requestOrder,
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun preSessionChallengeConsumesTheSharedBudgetBeforeRevocation() =
        runTest {
            var challengeCount = 0
            var csrfRequestCount = 0
            var otpRequestCount = 0
            var revokeRequestCount = 0
            val fixture =
                exchangeFixture(
                    challengeHandler =
                        DiscourseCloudflareChallengeHandler {
                            challengeCount += 1
                            true
                        },
                ) { request ->
                    when (request.url.encodedPath) {
                        "/session/csrf" -> {
                            csrfRequestCount += 1
                            respondJson("{\"csrf\":\"csrf-fixture\"}")
                        }

                        "/session/otp/$OTP" -> {
                            otpRequestCount += 1
                            if (otpRequestCount == 1) {
                                respondCloudflareChallenge()
                            } else {
                                respondWithSessionCookie()
                            }
                        }

                        "/user-api-key/revoke" -> {
                            revokeRequestCount += 1
                            respondCloudflareChallenge()
                        }

                        else -> {
                            error("Identity must not run after the second challenge")
                        }
                    }
                }

            try {
                assertFailsWith<DiscourseCloudflareChallengeException> {
                    fixture.transport.exchange(acceptedRedirect(), expectedGeneration = 0L)
                }
                assertEquals(1, challengeCount)
                assertEquals(2, csrfRequestCount)
                assertEquals(2, otpRequestCount)
                assertEquals(1, revokeRequestCount)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun aSecondChallengeIsSurfacedWithoutCallingTheHandlerTwice() =
        runTest {
            var challengeCount = 0
            var csrfRequestCount = 0
            var otpRequestCount = 0
            val fixture =
                exchangeFixture(
                    challengeHandler =
                        DiscourseCloudflareChallengeHandler {
                            challengeCount += 1
                            true
                        },
                ) { request ->
                    when (request.url.encodedPath) {
                        "/session/csrf" -> {
                            csrfRequestCount += 1
                            respondJson("{\"csrf\":\"csrf-fixture\"}")
                        }

                        "/session/otp/$OTP" -> {
                            otpRequestCount += 1
                            respondCloudflareChallenge()
                        }

                        else -> {
                            error("Unexpected fake-service request")
                        }
                    }
                }

            try {
                assertFailsWith<DiscourseCloudflareChallengeException> {
                    fixture.transport.exchange(acceptedRedirect(), expectedGeneration = 0L)
                }
                assertEquals(1, challengeCount)
                assertEquals(2, csrfRequestCount)
                assertEquals(2, otpRequestCount)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun ordinaryPermissionFailureNeverOpensTheCloudflareHandler() =
        runTest {
            var challengeCount = 0
            val privateBody = "self-authored ordinary forbidden fixture _t=private-cookie"
            val fixture =
                exchangeFixture(
                    challengeHandler =
                        DiscourseCloudflareChallengeHandler {
                            challengeCount += 1
                            true
                        },
                ) { request ->
                    when (request.url.encodedPath) {
                        "/session/csrf" -> {
                            respondJson("{\"csrf\":\"csrf-fixture\"}")
                        }

                        "/session/otp/$OTP" -> {
                            respond(
                                content = privateBody,
                                status = HttpStatusCode.Forbidden,
                                headers = jsonHeaders(),
                            )
                        }

                        else -> {
                            error("Unexpected fake-service request")
                        }
                    }
                }

            try {
                val failure =
                    assertFailsWith<DiscoursePermissionException> {
                        fixture.transport.exchange(acceptedRedirect(), expectedGeneration = 0L)
                    }
                assertEquals(0, challengeCount)
                assertFalse(failure.toString().contains(privateBody))
                assertFalse(failure.toString().contains("private-cookie"))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun generationReplacementCancelsTheExchangeAndKeepsCancellationSanitized() =
        runTest {
            val requestEntered = CompletableDeferred<Unit>()
            val fixture =
                exchangeFixture {
                    requestEntered.complete(Unit)
                    awaitCancellation()
                }
            val accepted = acceptedRedirect()

            try {
                val exchange =
                    async {
                        runCatching {
                            fixture.transport.exchange(accepted, expectedGeneration = 0L)
                        }.exceptionOrNull()
                    }
                requestEntered.await()
                fixture.sessionManager.startAuthenticatedSession(
                    accountId = "replacement-account",
                    username = "replacement-user",
                )

                val failure = assertIs<StaleDiscourseSessionException>(exchange.await())
                assertEquals(0L, failure.expectedGeneration)
                assertEquals(1L, failure.actualGeneration)
                assertFailsWith<IllegalStateException> { accepted.secrets.copyApiKey() }
            } finally {
                fixture.close()
            }
        }

    @Test
    fun callerCancellationPropagatesWithoutBecomingAProtocolFailure() =
        runTest {
            val requestEntered = CompletableDeferred<Unit>()
            val fixture =
                exchangeFixture {
                    requestEntered.complete(Unit)
                    awaitCancellation()
                }

            try {
                val exchange =
                    async {
                        fixture.transport.exchange(acceptedRedirect(), expectedGeneration = 0L)
                    }
                requestEntered.await()
                exchange.cancel()

                assertFailsWith<CancellationException> { exchange.await() }
                assertTrue(exchange.isCancelled)
            } finally {
                fixture.close()
            }
        }
}

private data class ExchangeFixture(
    val transport: DiscourseOtpSessionExchangeTransport,
    val sessionManager: DiscourseSessionManager,
    val client: HttpClient,
) {
    fun close() {
        client.close()
    }
}

private fun exchangeFixture(
    challengeHandler: DiscourseCloudflareChallengeHandler = DiscourseCloudflareChallengeHandler { false },
    challengeHandlerFactory: ((DiscourseSessionManager) -> DiscourseCloudflareChallengeHandler)? = null,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): ExchangeFixture {
    val storage = DiscourseCookieStorage()
    val sessionManager = DiscourseSessionManager(cookieStorage = storage)
    val client = createDiscourseHttpClient(MockEngine(handler), storage)
    return ExchangeFixture(
        transport =
            DiscourseOtpSessionExchangeTransport(
                client = client,
                sessionManager = sessionManager,
                challengeHandler = challengeHandlerFactory?.invoke(sessionManager) ?: challengeHandler,
            ),
        sessionManager = sessionManager,
        client = client,
    )
}

private fun acceptedRedirect(): DiscourseAuthRedirectResult.Accepted =
    DiscourseAuthRedirectResult.Accepted(
        secrets =
            DiscourseAuthSecrets(
                apiKey = API_KEY.encodeToByteArray(),
                oneTimePassword = OTP.encodeToByteArray(),
            ),
        clientId = CLIENT_ID,
        apiVersion = 4,
    )

private fun manualChallengeCookieHandler(
    sessionManager: DiscourseSessionManager,
    onPresent: (String) -> Unit,
): DiscourseCloudflareChallengeHandler =
    DiscourseManualChallengeCookieHandler(
        presenter =
            DiscourseManualChallengePresenter { fixedOrigin ->
                onPresent(fixedOrigin)
                true
            },
        cookieBridge =
            object : DiscourseWebSessionCookieBridge {
                override suspend fun snapshotLinuxDoCookies(): List<DiscourseCookieSnapshot> =
                    listOf(
                        DiscourseCookieSnapshot(
                            name = "_t",
                            value = "browser-session-must-not-replace",
                            httpOnly = true,
                        ),
                        DiscourseCookieSnapshot(
                            name = "cf_clearance",
                            value = "bridged-clearance",
                            httpOnly = true,
                        ),
                    )

                override suspend fun clearLinuxDoCookies() {
                    error("The challenge flow must not clear browser cookies")
                }
            },
        sessionManager = sessionManager,
    )

private fun HttpRequestData.cookies(): Map<String, String> =
    headers[HttpHeaders.Cookie]
        ?.let(::parseClientCookiesHeader)
        .orEmpty()

private fun MockRequestHandleScope.respondJson(content: String): HttpResponseData =
    respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = jsonHeaders(),
    )

private fun MockRequestHandleScope.respondWithSessionCookie(): HttpResponseData =
    respond(
        content = "",
        status = HttpStatusCode.Found,
        headers =
            Headers.build {
                append(HttpHeaders.Location, "/")
                append(HttpHeaders.SetCookie, "_t=new-session-cookie; Path=/; Secure; HttpOnly")
            },
    )

private fun MockRequestHandleScope.respondCloudflareChallenge(): HttpResponseData =
    respond(
        content = "self-authored /cdn-cgi/challenge-platform/ fixture",
        status = HttpStatusCode.Forbidden,
        headers =
            Headers.build {
                append(HttpHeaders.ContentType, ContentType.Text.Html.toString())
                append("cf-mitigated", "challenge")
            },
    )

private fun jsonHeaders(): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }

private const val API_KEY: String = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val OTP: String = "0123456789abcdef"
private const val CLIENT_ID: String = "client-fixture-42"
private const val CURRENT_SESSION_JSON: String =
    """{"current_user":{"id":42,"username":"fixture-user","name":"Fixture User"}}"""
