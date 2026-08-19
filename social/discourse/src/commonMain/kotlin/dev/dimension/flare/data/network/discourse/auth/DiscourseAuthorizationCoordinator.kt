package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.session.SecureCredentialRef
import dev.dimension.flare.data.network.discourse.session.SecureCredentialStore
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Clock

private const val AUTH_ATTEMPT_LIFETIME_MILLIS: Long = 10L * 60L * 1_000L
private const val AUTH_TOKEN_BYTES: Int = 32
private const val PENDING_PRIVATE_KEY_ACCOUNT: String = "discourse.pending-auth-key"

/** Cryptographically random, URL-safe token source supplied by each native platform. */
public fun interface DiscourseAuthTokenGenerator {
    /** Returns an unguessable token containing only RFC 3986 unreserved ASCII characters. */
    public suspend fun generate(byteCount: Int): String
}

/** Platform secure-random implementation used by the default dependency graph. */
internal expect fun createPlatformDiscourseAuthTokenGenerator(): DiscourseAuthTokenGenerator

/** Browser request plus non-secret timing metadata suitable for presentation. */
public data class DiscoursePendingAuthorization(
    public val url: Url,
    public val expiresAtEpochMillis: Long,
)

/**
 * Creates one pending User API Key authorization and owns replacement cleanup.
 *
 * The private key is persisted before its opaque reference reaches the attempt store. If any later
 * operation fails, the new key is deleted in a non-cancellable cleanup section. Replacing an older
 * attempt returns its metadata so the obsolete private key can likewise be made unreachable.
 */
public class DiscourseAuthorizationCoordinator(
    private val keyPairGenerator: DiscourseRsaPkcs1KeyPairGenerator,
    private val tokenGenerator: DiscourseAuthTokenGenerator,
    private val credentialStore: SecureCredentialStore,
    private val attemptStore: DiscourseAuthAttemptStore,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    public suspend fun begin(): DiscoursePendingAuthorization {
        val createdAt = nowEpochMillis()
        require(createdAt >= 0L) { "The platform clock cannot represent authorization time" }
        require(createdAt <= Long.MAX_VALUE - AUTH_ATTEMPT_LIFETIME_MILLIS) {
            "The platform clock is outside the supported authorization range"
        }

        val id = tokenGenerator.generateValidatedToken()
        val nonce = tokenGenerator.generateValidatedToken()
        val clientId = tokenGenerator.generateValidatedToken()
        val keyPair = keyPairGenerator.generateForDiscourse()
        var privateKeyRef: SecureCredentialRef? = null
        try {
            val privateKey = keyPair.copyPrivateKeyPkcs8()
            try {
                privateKeyRef = credentialStore.save(PENDING_PRIVATE_KEY_ACCOUNT, privateKey)
            } finally {
                privateKey.fill(0)
            }

            val attempt =
                DiscourseAuthAttempt(
                    id = id,
                    privateKeyRef = privateKeyRef,
                    nonce = nonce,
                    clientId = clientId,
                    createdAtEpochMillis = createdAt,
                    expiresAtEpochMillis = createdAt + AUTH_ATTEMPT_LIFETIME_MILLIS,
                )
            val previous = attemptStore.replace(attempt)
            privateKeyRef = null
            previous?.let { deleteCredentialBestEffort(it.privateKeyRef) }

            return DiscoursePendingAuthorization(
                url =
                    DiscourseUserApiAuthorization.buildUrl(
                        publicKeyPem = keyPair.publicKeySpkiPem,
                        clientId = clientId,
                        nonce = nonce,
                    ),
                expiresAtEpochMillis = attempt.expiresAtEpochMillis,
            )
        } finally {
            keyPair.close()
            privateKeyRef?.let { deleteCredentialBestEffort(it) }
        }
    }

    /** Cancels the currently observed attempt without deleting a concurrently replacing key. */
    public suspend fun cancelPending(): Boolean {
        val current = attemptStore.peek() ?: return false
        val consumed = attemptStore.consume(current.id) ?: return false
        deleteCredentialBestEffort(consumed.privateKeyRef)
        return true
    }

    private suspend fun DiscourseAuthTokenGenerator.generateValidatedToken(): String {
        val value = generate(AUTH_TOKEN_BYTES)
        requireValidAuthToken(value, "Generated authorization token")
        return value
    }

    private suspend fun deleteCredentialBestEffort(reference: SecureCredentialRef) {
        withContext(NonCancellable) {
            try {
                credentialStore.delete(reference)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Vault references and platform error strings are intentionally never logged.
            }
        }
    }
}
