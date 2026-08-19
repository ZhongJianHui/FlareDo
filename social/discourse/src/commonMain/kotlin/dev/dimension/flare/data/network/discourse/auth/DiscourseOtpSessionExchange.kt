package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import dev.dimension.flare.data.network.discourse.error.DiscourseCloudflareChallengeException
import dev.dimension.flare.data.network.discourse.error.DiscourseException
import dev.dimension.flare.data.network.discourse.error.DiscourseHttpException
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkException
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkFailureKind
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationPhase
import dev.dimension.flare.data.network.discourse.error.mapDiscourseResponseException
import dev.dimension.flare.data.network.discourse.model.DiscourseCsrfResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseCurrentSessionResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseCurrentUser
import dev.dimension.flare.data.network.discourse.readDiscourseClassificationBodyPrefix
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieSnapshot
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import dev.dimension.flare.data.network.discourse.session.InvalidDiscourseCsrfTokenException
import dev.dimension.flare.data.network.discourse.session.StaleDiscourseSessionException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

private const val DISCOURSE_SESSION_COOKIE_NAME: String = "_t"
private const val DISCOURSE_HOST: String = "linux.do"

/** User-mediated Cloudflare challenge boundary supplied by each foreground platform host. */
public fun interface DiscourseCloudflareChallengeHandler {
    /** Returns true only after a visible challenge completed and its cookies were bridged. */
    public suspend fun handle(challenge: DiscourseCloudflareChallengeException): Boolean
}

/** Sanitized protocol failures specific to the one-time-password exchange. */
public class DiscourseAuthExchangeException(
    public val reason: DiscourseAuthExchangeFailure,
) : Exception("Discourse authorization exchange failed (${reason.name})")

public enum class DiscourseAuthExchangeFailure {
    ActiveSession,
    InvalidSecret,
    Csrf,
    OtpResponse,
    SessionCookie,
    RevokeResponse,
    Identity,
    ChallengeHandler,
}

/**
 * Authenticated identity and cookie material ready for `DiscourseSessionLifecycle.activate`.
 *
 * Cookie values are immutable Kotlin Strings and cannot be reliably erased. They are deliberately
 * excluded from [toString], never logged by this transport, and should be handed directly to the
 * encrypted session store. [copyCookies] returns a new list container, not new String instances.
 */
public class DiscourseOtpSessionExchangeResult internal constructor(
    public val accountId: String,
    public val username: String,
    public val displayName: String?,
    public val clientId: String,
    public val apiVersion: Int?,
    cookies: List<DiscourseCookieSnapshot>,
) {
    private val retainedCookies: List<DiscourseCookieSnapshot> = cookies.toList()

    public fun copyCookies(): List<DiscourseCookieSnapshot> = retainedCookies.toList()

    override fun toString(): String =
        "DiscourseOtpSessionExchangeResult(" +
            "accountId=$accountId, username=$username, clientId=$clientId, " +
            "apiVersion=$apiVersion, cookieCount=${retainedCookies.size})"
}

/**
 * Exchanges a Discourse User API Key callback OTP for a normal Linux.do web session.
 *
 * Every HTTP call runs inside [DiscourseSessionManager.runForCurrentSession], so login/logout
 * cancellation and the revision-bound Cookie plugin apply to CSRF, OTP, key revocation, and identity
 * probing as one operation. The callback key is never persisted: after a 302 produces a new root
 * `_t` cookie, `/user-api-key/revoke` is the very next network request.
 *
 * Only [DiscourseCloudflareChallengeException] enters [challengeHandler]. Before `_t` exists, a
 * successful handler may replay CSRF and OTP once. After `_t` exists, only the failed revoke or
 * identity request is replayed, because the OTP may already be consumed. All phases share one
 * challenge allowance; a second challenge or an ordinary 403 is surfaced. This method consumes and
 * closes [accepted.secrets] on every path.
 */
public class DiscourseOtpSessionExchangeTransport(
    private val client: HttpClient,
    private val sessionManager: DiscourseSessionManager,
    private val challengeHandler: DiscourseCloudflareChallengeHandler,
) {
    public suspend fun exchange(
        accepted: DiscourseAuthRedirectResult.Accepted,
        expectedGeneration: Long,
    ): DiscourseOtpSessionExchangeResult {
        val secrets = accepted.secrets.claimForExchange()
        try {
            validateExchangeSecret(secrets.apiKey, isOtp = false)
            validateExchangeSecret(secrets.otp, isOtp = true)
            requireValidAuthToken(accepted.clientId, "Client id")

            val initialState = sessionManager.state.value
            if (
                initialState !is DiscourseSessionState.Guest ||
                initialState.generation != expectedGeneration
            ) {
                throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.ActiveSession)
            }
            return exchangeOnce(
                apiKey = secrets.apiKey,
                otp = secrets.otp,
                clientId = accepted.clientId,
                apiVersion = accepted.apiVersion,
                expectedGeneration = expectedGeneration,
            )
        } finally {
            secrets.clear()
        }
    }

    private suspend fun exchangeOnce(
        apiKey: ByteArray,
        otp: ByteArray,
        clientId: String,
        apiVersion: Int?,
        expectedGeneration: Long,
    ): DiscourseOtpSessionExchangeResult =
        translateAuthTransportFailures {
            sessionManager.runForCurrentSession {
                if (this !is DiscourseSessionState.Guest || generation != expectedGeneration) {
                    throw StaleDiscourseSessionException(
                        expectedGeneration = expectedGeneration,
                        actualGeneration = generation,
                    )
                }

                val challengeBudget =
                    DiscourseChallengeReplayBudget { challenge ->
                        ensureGeneration(expectedGeneration)
                        val handled = handleChallenge(challenge)
                        ensureGeneration(expectedGeneration)
                        handled
                    }
                establishWebSession(otp, challengeBudget)

                // Do not insert identity probes or other calls before this one-use credential burn.
                revokeApiKey(apiKey, challengeBudget)

                val currentSession = fetchCurrentSession(challengeBudget)
                val currentUser =
                    currentSession.currentUser
                        ?: throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.Identity)
                currentUser.requireValidIdentity()
                val finalCookies = sessionManager.cookieStorage.snapshot()
                if (finalCookies.rootSessionCookie() == null) {
                    throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.SessionCookie)
                }

                DiscourseOtpSessionExchangeResult(
                    accountId = currentUser.id.toString(),
                    username = currentUser.username,
                    displayName = currentUser.name,
                    clientId = clientId,
                    apiVersion = apiVersion,
                    cookies = finalCookies,
                )
            }
        }

    /**
     * Establishes `_t`, replaying the CSRF/OTP portion only while no web session exists yet.
     *
     * The OTP is single-use. Once a response installs a new root `_t`, this method returns and no
     * later Cloudflare challenge can route control back into it. A handled pre-session challenge
     * clears the potentially changed CSRF token before replaying the whole pre-session portion.
     */
    private suspend fun establishWebSession(
        otp: ByteArray,
        challengeBudget: DiscourseChallengeReplayBudget,
    ) {
        while (true) {
            try {
                val csrfToken =
                    sessionManager.csrfTokenStore.getOrFetch {
                        client
                            .get("$DISCOURSE_ORIGIN/session/csrf")
                            .body<DiscourseCsrfResponse>()
                            .csrf
                    }
                val beforeSessionCookie = sessionManager.cookieStorage.snapshot().rootSessionCookie()
                val otpText = otp.decodeToString()
                val otpResponse =
                    client.post("$DISCOURSE_ORIGIN/session/otp/$otpText") {
                        // A 302 is the protocol success response and redirects remain disabled.
                        // Disable only this request's automatic status exception so the response
                        // cookie plugin can commit `_t` before we inspect the local cookie jar.
                        expectSuccess = false
                        header("X-CSRF-Token", csrfToken)
                    }
                if (otpResponse.status != HttpStatusCode.Found) {
                    classifyOtpFailure(otpResponse)
                }

                val afterSessionCookie =
                    sessionManager.cookieStorage.snapshot().rootSessionCookie()
                if (
                    afterSessionCookie == null ||
                    afterSessionCookie.value == beforeSessionCookie?.value
                ) {
                    throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.SessionCookie)
                }
                return
            } catch (challenge: DiscourseCloudflareChallengeException) {
                challengeBudget.handle(challenge)
                sessionManager.csrfTokenStore.clear()
            }
        }
    }

    /** Retries only key revocation after a post-session challenge; the OTP is never replayed. */
    private suspend fun revokeApiKey(
        apiKey: ByteArray,
        challengeBudget: DiscourseChallengeReplayBudget,
    ) {
        retryCurrentRequestAfterChallenge(challengeBudget) {
            val apiKeyText = apiKey.decodeToString()
            val revokeResponse =
                client.post("$DISCOURSE_ORIGIN/user-api-key/revoke") {
                    header("User-Api-Key", apiKeyText)
                }
            if (revokeResponse.status.value !in 200..299) {
                throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.RevokeResponse)
            }
        }
    }

    /** Retries only the identity probe after a post-session challenge; revocation stays complete. */
    private suspend fun fetchCurrentSession(challengeBudget: DiscourseChallengeReplayBudget): DiscourseCurrentSessionResponse =
        retryCurrentRequestAfterChallenge(challengeBudget) {
            client
                .get("$DISCOURSE_ORIGIN/session/current.json")
                .body<DiscourseCurrentSessionResponse>()
        }

    private suspend fun <T> retryCurrentRequestAfterChallenge(
        challengeBudget: DiscourseChallengeReplayBudget,
        request: suspend () -> T,
    ): T =
        try {
            request()
        } catch (challenge: DiscourseCloudflareChallengeException) {
            challengeBudget.handle(challenge)
            request()
        }

    private suspend fun handleChallenge(challenge: DiscourseCloudflareChallengeException): Boolean =
        try {
            challengeHandler.handle(challenge)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.ChallengeHandler)
        }

    private fun ensureGeneration(expectedGeneration: Long) {
        val actualGeneration = sessionManager.state.value.generation
        if (actualGeneration != expectedGeneration) {
            throw StaleDiscourseSessionException(expectedGeneration, actualGeneration)
        }
    }
}

/** One user-mediated Cloudflare challenge allowance shared by every phase of one exchange. */
private class DiscourseChallengeReplayBudget(
    private val handler: suspend (DiscourseCloudflareChallengeException) -> Boolean,
) {
    private var consumed: Boolean = false

    suspend fun handle(challenge: DiscourseCloudflareChallengeException) {
        if (consumed) throw challenge
        consumed = true
        if (!handler(challenge)) throw challenge
    }
}

/**
 * Owns the defensive secret copies consumed by the transport.
 *
 * Construction is all-or-nothing: if copying the OTP fails after the API key was copied, the key
 * copy is overwritten before the failure escapes. The source [DiscourseAuthSecrets] is always
 * closed, and [clear] overwrites both successful copies when the exchange ends or is cancelled.
 */
private class OwnedExchangeSecrets(
    val apiKey: ByteArray,
    val otp: ByteArray,
) {
    fun clear() {
        apiKey.fill(0)
        otp.fill(0)
    }
}

private fun DiscourseAuthSecrets.claimForExchange(): OwnedExchangeSecrets =
    try {
        val apiKeyCopy = copyApiKey()
        try {
            OwnedExchangeSecrets(
                apiKey = apiKeyCopy,
                otp = copyOneTimePassword(),
            )
        } catch (failure: Throwable) {
            apiKeyCopy.fill(0)
            throw failure
        }
    } finally {
        close()
    }

/**
 * Recreates the client's response validator for the one request whose expected status is 302.
 *
 * Only the two headers needed for typed retry/challenge handling cross the boundary. The response
 * body is read through the shared 4096-byte channel limit and is discarded immediately after the
 * classifier checks its fixed markers; no server text can reach the resulting exception.
 */
private suspend fun classifyOtpFailure(response: HttpResponse): Nothing {
    val bodyPrefix =
        try {
            response.bodyAsChannel().readDiscourseClassificationBodyPrefix()
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

private fun List<DiscourseCookieSnapshot>.rootSessionCookie(): DiscourseCookieSnapshot? {
    val matching =
        filter {
            it.name == DISCOURSE_SESSION_COOKIE_NAME &&
                it.domain.equals(DISCOURSE_HOST, ignoreCase = true) &&
                it.path == "/" &&
                it.secure &&
                it.httpOnly &&
                it.value.isNotEmpty()
        }
    return matching.singleOrNull()
}

private fun DiscourseCurrentUser.requireValidIdentity() {
    if (id <= 0L) {
        throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.Identity)
    }
    try {
        DiscourseSessionState.Authenticated(
            generation = 0L,
            accountId = id.toString(),
            username = username,
        )
    } catch (_: IllegalArgumentException) {
        throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.Identity)
    }
}

private fun validateExchangeSecret(
    bytes: ByteArray,
    isOtp: Boolean,
) {
    val valid =
        if (isOtp) {
            bytes.size in 1..256 &&
                bytes.all { byte ->
                    val character = byte.toInt().toChar()
                    character in '0'..'9' || character in 'a'..'f'
                }
        } else {
            bytes.size in 1..512 && bytes.all { byte -> byte.toInt() in 0x21..0x7e }
        }
    if (!valid) {
        throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.InvalidSecret)
    }
}

private suspend fun <T> translateAuthTransportFailures(block: suspend () -> T): T =
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (stale: StaleDiscourseSessionException) {
        throw stale
    } catch (exchange: DiscourseAuthExchangeException) {
        throw exchange
    } catch (known: DiscourseException) {
        throw known
    } catch (_: InvalidDiscourseCsrfTokenException) {
        throw DiscourseAuthExchangeException(DiscourseAuthExchangeFailure.Csrf)
    } catch (response: ResponseException) {
        throw DiscourseHttpException(response.response.status.value)
    } catch (_: HttpRequestTimeoutException) {
        throw DiscourseNetworkException(DiscourseNetworkFailureKind.Timeout)
    } catch (_: ConnectTimeoutException) {
        throw DiscourseNetworkException(DiscourseNetworkFailureKind.Timeout)
    } catch (_: SocketTimeoutException) {
        throw DiscourseNetworkException(DiscourseNetworkFailureKind.Timeout)
    } catch (_: ContentConvertException) {
        throw DiscourseSerializationException(DiscourseSerializationPhase.ResponseDecoding)
    } catch (_: SerializationException) {
        throw DiscourseSerializationException(DiscourseSerializationPhase.ResponseDecoding)
    } catch (_: IOException) {
        throw DiscourseNetworkException(DiscourseNetworkFailureKind.Connection)
    } catch (_: Exception) {
        throw DiscourseNetworkException(DiscourseNetworkFailureKind.Unknown)
    }
