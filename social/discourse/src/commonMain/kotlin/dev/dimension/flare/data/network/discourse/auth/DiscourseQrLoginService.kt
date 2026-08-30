package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import dev.dimension.flare.data.network.discourse.DiscourseApi
import dev.dimension.flare.data.network.discourse.model.DiscourseCsrfResponse
import dev.dimension.flare.data.network.discourse.model.discourseJson
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import dev.dimension.flare.data.network.discourse.session.SecureCredentialRef
import dev.dimension.flare.data.network.discourse.session.SecureCredentialStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.time.Clock

internal const val DISCOURSE_QR_LOGIN_EXCHANGE_CLIENT_ID: String = "flaredo-qr-login-v1"

private const val QR_LOGIN_APPLICATION_NAME: String = "FlareDo QR sign-in"
private const val QR_LOGIN_PRIVATE_KEY_ACCOUNT: String = "discourse.qr-login-key"
private const val QR_LOGIN_SCOPE: String = "one_time_password"
private const val QR_LOGIN_LIFETIME_MILLIS: Long = 10L * 60L * 1_000L
private const val QR_LOGIN_TOKEN_BYTES: Int = 32
private const val MAX_QR_CREATE_RESPONSE_BYTES: Long = 16 * 1024L

/**
 * Creates, observes, revokes, and consumes FlareDo cross-device login capabilities.
 *
 * Generation uses the authenticated web session and a transient RSA key. Scanning delegates to
 * [DiscourseLoginService.consumeQrLogin], which exchanges the one-use OTP, revokes the accompanying
 * API key before identity probing, and persists the resulting web session through the platform vault.
 */
public class DiscourseQrLoginService(
    private val client: HttpClient,
    private val api: DiscourseApi,
    private val sessionManager: DiscourseSessionManager,
    private val loginService: DiscourseLoginService,
    private val keyPairGenerator: DiscourseRsaPkcs1KeyPairGenerator,
    private val decryptor: DiscourseRsaPkcs1Decryptor,
    private val tokenGenerator: DiscourseAuthTokenGenerator,
    private val credentialStore: SecureCredentialStore,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** Creates a ten-minute bearer capability for another FlareDo installation. */
    public suspend fun createShare(): DiscourseQrLoginPayload {
        val owner =
            sessionManager.state.value as? DiscourseSessionState.Authenticated
                ?: throw DiscourseQrLoginException(DiscourseQrLoginFailure.ActiveSession)
        val createdAt = nowEpochMillis()
        if (createdAt < 0L || createdAt > Long.MAX_VALUE - QR_LOGIN_LIFETIME_MILLIS) {
            throw DiscourseQrLoginException(DiscourseQrLoginFailure.CreateFailed)
        }
        val attemptId = tokenGenerator.generateQrToken()
        val nonce = tokenGenerator.generateQrToken()
        val clientId = tokenGenerator.generateQrToken()
        val keyPair = keyPairGenerator.generateForDiscourse()
        var privateKeyRef: SecureCredentialRef? = null
        try {
            val privateKey = keyPair.copyPrivateKeyPkcs8()
            try {
                privateKeyRef = credentialStore.save(QR_LOGIN_PRIVATE_KEY_ACCOUNT, privateKey)
            } finally {
                privateKey.fill(0)
            }
            val attemptStore = MemoryDiscourseAuthAttemptStore()
            attemptStore.replace(
                DiscourseAuthAttempt(
                    id = attemptId,
                    privateKeyRef = privateKeyRef,
                    nonce = nonce,
                    clientId = clientId,
                    createdAtEpochMillis = createdAt,
                    expiresAtEpochMillis = createdAt + QR_LOGIN_LIFETIME_MILLIS,
                ),
            )
            val redirect =
                sessionManager.runForAuthenticatedSession(owner.generation, owner.accountId) {
                    createRemoteCredential(
                        publicKeyPem = keyPair.publicKeySpkiPem,
                        nonce = nonce,
                        clientId = clientId,
                    )
                }
            val processed =
                DiscourseAuthRedirectProcessor(
                    attemptStore = attemptStore,
                    credentialStore = credentialStore,
                    decryptor = decryptor,
                    nowEpochMillis = nowEpochMillis,
                ).process(redirect)
            val accepted =
                processed as? DiscourseAuthRedirectResult.Accepted
                    ?: throw DiscourseQrLoginException(DiscourseQrLoginFailure.CreateFailed)
            val apiKey = accepted.secrets.copyApiKey()
            val otp = accepted.secrets.copyOneTimePassword()
            accepted.secrets.close()
            return try {
                DiscourseQrLoginPayload(
                    apiKey = apiKey,
                    otp = otp,
                    username = owner.username.orEmpty(),
                    expiresAtEpochMillis = createdAt + QR_LOGIN_LIFETIME_MILLIS,
                )
            } finally {
                apiKey.fill(0)
                otp.fill(0)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: DiscourseQrLoginException) {
            throw failure
        } catch (failure: Throwable) {
            throw DiscourseQrLoginException(DiscourseQrLoginFailure.CreateFailed, failure)
        } finally {
            keyPair.close()
            privateKeyRef?.let { deleteCredentialBestEffort(it) }
        }
    }

    /** Parses and consumes one scanned FlareDo QR value. */
    public suspend fun login(rawValue: String): DiscourseLoginResult.Authenticated {
        val payload =
            DiscourseQrLoginProtocol.parse(rawValue)
                ?: throw DiscourseQrLoginException(DiscourseQrLoginFailure.InvalidPayload)
        if (payload.isExpired(nowEpochMillis())) {
            payload.close()
            throw DiscourseQrLoginException(DiscourseQrLoginFailure.Expired)
        }
        return loginService.consumeQrLogin(payload)
    }

    /** Best-effort cleanup for a displayed code which was closed or regenerated before consumption. */
    public suspend fun revokeAndClose(payload: DiscourseQrLoginPayload): Boolean {
        val apiKey = payload.copyApiKey()
        payload.close()
        return try {
            val response =
                client.post("$DISCOURSE_ORIGIN/user-api-key/revoke") {
                    expectSuccess = false
                    header("User-Api-Key", apiKey.decodeToString())
                }
            response.status.value in 200..299
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        } finally {
            apiKey.fill(0)
        }
    }

    /** Returns null for transient probe failures, true while waiting, and false after revocation. */
    public suspend fun isShareActive(username: String): Boolean? {
        val owner = sessionManager.state.value as? DiscourseSessionState.Authenticated ?: return null
        if (username.isBlank() || owner.username != username) return null
        return try {
            api
                .user(username)
                .user
                .userApiKeys
                ?.any { it.applicationName == QR_LOGIN_APPLICATION_NAME }
                ?: false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun createRemoteCredential(
        publicKeyPem: String,
        nonce: String,
        clientId: String,
    ): String {
        val csrf =
            sessionManager.csrfTokenStore.getOrFetch {
                client
                    .get("$DISCOURSE_ORIGIN/session/csrf")
                    .body<DiscourseCsrfResponse>()
                    .csrf
            }
        val response =
            client.post("$DISCOURSE_ORIGIN/user-api-key") {
                expectSuccess = false
                contentType(ContentType.Application.FormUrlEncoded)
                header("X-CSRF-Token", csrf)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("application_name", QR_LOGIN_APPLICATION_NAME)
                            append("client_id", clientId)
                            append("scopes", QR_LOGIN_SCOPE)
                            append("public_key", publicKeyPem)
                            append("nonce", nonce)
                            append("auth_redirect", DiscourseUserApiAuthorization.AUTH_REDIRECT)
                        },
                    ),
                )
            }
        if (response.status.value !in 200..399) {
            throw DiscourseQrLoginException(DiscourseQrLoginFailure.CreateFailed)
        }
        response.headers[HttpHeaders.Location]?.takeIf(String::isNotBlank)?.let { return it }
        val bodyBytes =
            response
                .bodyAsChannel()
                .readRemaining(MAX_QR_CREATE_RESPONSE_BYTES + 1L)
                .readByteArray()
        val decoded =
            try {
                if (bodyBytes.size > MAX_QR_CREATE_RESPONSE_BYTES) {
                    null
                } else {
                    val body = bodyBytes.decodeToString(throwOnInvalidSequence = true)
                    discourseJson.decodeFromString<QrCreateResponse>(body)
                }
            } catch (_: CharacterCodingException) {
                null
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } finally {
                bodyBytes.fill(0)
            }
        return decoded?.redirectUrl?.takeIf(String::isNotBlank)
            ?: throw DiscourseQrLoginException(DiscourseQrLoginFailure.CreateFailed)
    }

    private suspend fun DiscourseAuthTokenGenerator.generateQrToken(): String {
        val value = generate(QR_LOGIN_TOKEN_BYTES)
        requireValidAuthToken(value, "Generated QR login token")
        return value
    }

    private suspend fun deleteCredentialBestEffort(reference: SecureCredentialRef) {
        withContext(NonCancellable) {
            try {
                credentialStore.delete(reference)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // The reference never leaves this operation and is not logged.
            }
        }
    }
}

@Serializable
private data class QrCreateResponse(
    @SerialName("redirect_url")
    val redirectUrl: String? = null,
)
