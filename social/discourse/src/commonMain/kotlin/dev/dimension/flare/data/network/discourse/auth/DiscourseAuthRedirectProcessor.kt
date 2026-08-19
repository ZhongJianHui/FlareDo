package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.session.SecureCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val MAX_DECRYPTED_PAYLOAD_BYTES: Int = 8_192
private const val MAX_CIPHERTEXT_BYTES: Int = 8_192
private const val MIN_PRIVATE_KEY_BYTES: Int = 256
private const val MAX_PRIVATE_KEY_BYTES: Int = 8_192
private const val MAX_API_KEY_BYTES: Int = 512
private const val MAX_OTP_BYTES: Int = 256

/** Sanitized outcome of processing an authorization redirect. */
public sealed interface DiscourseAuthRedirectResult {
    /** No active matching attempt exists, including an already-consumed callback replay. */
    public data object Stale : DiscourseAuthRedirectResult

    /** The matching attempt exceeded its ten-minute lifetime and was consumed. */
    public data object Expired : DiscourseAuthRedirectResult

    /** The callback failed a bounded structural or cryptographic check. */
    public data class Malformed(
        public val reason: DiscourseAuthMalformedReason,
    ) : DiscourseAuthRedirectResult

    /** Authenticated callback material; callers must close [secrets] as soon as exchange finishes. */
    public class Accepted(
        public val secrets: DiscourseAuthSecrets,
        public val clientId: String,
        public val apiVersion: Int?,
    ) : DiscourseAuthRedirectResult
}

/** Coarse failure categories which never retain a URI, ciphertext, key, nonce, or parser message. */
public enum class DiscourseAuthMalformedReason {
    Redirect,
    CredentialUnavailable,
    PayloadCiphertext,
    PayloadDecryption,
    PayloadJson,
    ApiKey,
    OtpCiphertext,
    OtpDecryption,
    Otp,
}

/**
 * Best-effort clearable API key and OTP returned after nonce authentication.
 *
 * Kotlin Strings created by JSON decoding cannot be overwritten. The processor therefore converts
 * the temporary User API Key to bytes immediately and exposes only defensive byte-array copies.
 * Callers own those copies and must overwrite them after constructing request headers. [close]
 * overwrites the retained arrays; it is idempotent.
 */
public class DiscourseAuthSecrets internal constructor(
    apiKey: ByteArray,
    oneTimePassword: ByteArray,
) : AutoCloseable {
    private val retainedApiKey: ByteArray = apiKey
    private val retainedOneTimePassword: ByteArray = oneTimePassword
    private var isClosed: Boolean = false

    public fun copyApiKey(): ByteArray {
        check(!isClosed) { "Authorization secrets are closed" }
        return retainedApiKey.copyOf()
    }

    public fun copyOneTimePassword(): ByteArray {
        check(!isClosed) { "Authorization secrets are closed" }
        return retainedOneTimePassword.copyOf()
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        retainedApiKey.fill(0)
        retainedOneTimePassword.fill(0)
    }
}

/**
 * Validates and consumes an encrypted User API Key callback for the current pending attempt.
 *
 * Structurally invalid, undecryptable, and nonce-mismatched callbacks leave the attempt active so an
 * unauthenticated app cannot cancel a legitimate browser flow by sending random callbacks. Once the
 * RSA-authenticated nonce matches, [DiscourseAuthAttemptStore.consume] runs before API key and OTP
 * validation. All subsequent success or failure paths are therefore single-use and delete the
 * private key reference in a non-cancellable, best-effort cleanup block.
 */
public class DiscourseAuthRedirectProcessor(
    private val attemptStore: DiscourseAuthAttemptStore,
    private val credentialStore: SecureCredentialStore,
    private val decryptor: DiscourseRsaPkcs1Decryptor,
    private val nowEpochMillis: () -> Long,
) {
    public suspend fun process(rawUri: String): DiscourseAuthRedirectResult {
        val redirect =
            DiscourseAuthRedirectParser.parse(rawUri)
                ?: return DiscourseAuthRedirectResult.Malformed(DiscourseAuthMalformedReason.Redirect)
        val attempt = attemptStore.peek() ?: return DiscourseAuthRedirectResult.Stale
        val currentTime = nowEpochMillis()
        if (
            currentTime < attempt.createdAtEpochMillis ||
            currentTime >= attempt.expiresAtEpochMillis
        ) {
            val expired = attemptStore.consume(attempt.id) ?: return DiscourseAuthRedirectResult.Stale
            deleteCredentialBestEffort(expired)
            return DiscourseAuthRedirectResult.Expired
        }

        val privateKey =
            try {
                credentialStore.load(attempt.privateKeyRef)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            } ?: return DiscourseAuthRedirectResult.Malformed(
                DiscourseAuthMalformedReason.CredentialUnavailable,
            )
        if (privateKey.size !in MIN_PRIVATE_KEY_BYTES..MAX_PRIVATE_KEY_BYTES) {
            privateKey.fill(0)
            return DiscourseAuthRedirectResult.Malformed(
                DiscourseAuthMalformedReason.CredentialUnavailable,
            )
        }

        try {
            val encryptedPayload =
                decodeCiphertext(redirect.payload)
                    ?: return DiscourseAuthRedirectResult.Malformed(
                        DiscourseAuthMalformedReason.PayloadCiphertext,
                    )
            val decryptedPayload =
                try {
                    decryptor.decrypt(privateKey, encryptedPayload)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                } finally {
                    encryptedPayload.fill(0)
                } ?: return DiscourseAuthRedirectResult.Malformed(
                    DiscourseAuthMalformedReason.PayloadDecryption,
                )

            val payload =
                try {
                    decodePayload(decryptedPayload)
                } finally {
                    decryptedPayload.fill(0)
                } ?: return DiscourseAuthRedirectResult.Malformed(
                    DiscourseAuthMalformedReason.PayloadJson,
                )
            if (!constantTimeEquals(attempt.nonce, payload.nonce.orEmpty())) {
                return DiscourseAuthRedirectResult.Stale
            }

            var consumedAttempt: DiscourseAuthAttempt? = null
            try {
                consumedAttempt =
                    attemptStore.consume(attempt.id)
                        ?: return DiscourseAuthRedirectResult.Stale

                val apiKey = payload.key?.encodeToByteArray()
                if (apiKey == null || !apiKey.isValidApiKey()) {
                    apiKey?.fill(0)
                    return DiscourseAuthRedirectResult.Malformed(DiscourseAuthMalformedReason.ApiKey)
                }

                val encryptedOtp =
                    decodeCiphertext(redirect.oneTimePassword)
                        ?: run {
                            apiKey.fill(0)
                            return DiscourseAuthRedirectResult.Malformed(
                                DiscourseAuthMalformedReason.OtpCiphertext,
                            )
                        }
                val otp =
                    try {
                        decryptor.decrypt(privateKey, encryptedOtp)
                    } catch (cancellation: CancellationException) {
                        apiKey.fill(0)
                        throw cancellation
                    } catch (_: Exception) {
                        null
                    } finally {
                        encryptedOtp.fill(0)
                    }
                if (otp == null) {
                    apiKey.fill(0)
                    return DiscourseAuthRedirectResult.Malformed(
                        DiscourseAuthMalformedReason.OtpDecryption,
                    )
                }
                if (!otp.isValidOtp()) {
                    apiKey.fill(0)
                    otp.fill(0)
                    return DiscourseAuthRedirectResult.Malformed(DiscourseAuthMalformedReason.Otp)
                }

                return DiscourseAuthRedirectResult.Accepted(
                    secrets = DiscourseAuthSecrets(apiKey, otp),
                    clientId = attempt.clientId,
                    apiVersion = payload.apiVersion,
                )
            } finally {
                consumedAttempt?.let { deleteCredentialBestEffort(it) }
            }
        } finally {
            privateKey.fill(0)
        }
    }

    private suspend fun deleteCredentialBestEffort(attempt: DiscourseAuthAttempt) {
        withContext(NonCancellable) {
            try {
                credentialStore.delete(attempt.privateKeyRef)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Cleanup failure is intentionally not logged because references are private metadata.
            }
        }
    }
}

@Serializable
private data class DiscourseUserApiKeyPayload(
    val key: String? = null,
    val nonce: String? = null,
    @SerialName("api") val apiVersion: Int? = null,
)

private val AUTH_PAYLOAD_JSON: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = false
        explicitNulls = false
    }

private fun decodePayload(bytes: ByteArray): DiscourseUserApiKeyPayload? {
    if (bytes.size !in 1..MAX_DECRYPTED_PAYLOAD_BYTES) return null
    val text =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return null
        }
    return try {
        AUTH_PAYLOAD_JSON.decodeFromString<DiscourseUserApiKeyPayload>(text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun decodeCiphertext(value: String): ByteArray? {
    if (!value.isCanonicalBase64()) return null
    val decoded =
        try {
            Base64.Default.decode(value)
        } catch (_: IllegalArgumentException) {
            return null
        }
    if (decoded.size !in 1..MAX_CIPHERTEXT_BYTES) {
        decoded.fill(0)
        return null
    }
    return decoded
}

private fun constantTimeEquals(
    expected: String,
    actual: String,
): Boolean {
    val expectedBytes = expected.encodeToByteArray()
    val actualBytes = actual.encodeToByteArray()
    return try {
        var difference = expectedBytes.size xor actualBytes.size
        val comparedLength = maxOf(expectedBytes.size, actualBytes.size)
        for (index in 0 until comparedLength) {
            val expectedByte = if (index < expectedBytes.size) expectedBytes[index].toInt() else 0
            val actualByte = if (index < actualBytes.size) actualBytes[index].toInt() else 0
            difference = difference or (expectedByte xor actualByte)
        }
        difference == 0
    } finally {
        expectedBytes.fill(0)
        actualBytes.fill(0)
    }
}

private fun ByteArray.isValidApiKey(): Boolean = size in 1..MAX_API_KEY_BYTES && all { byte -> byte.toInt() in 0x21..0x7e }

private fun ByteArray.isValidOtp(): Boolean =
    size in 1..MAX_OTP_BYTES && all { byte -> byte.toInt().toChar() in '0'..'9' || byte.toInt().toChar() in 'a'..'f' }
