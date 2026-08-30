package dev.dimension.flare.data.network.discourse.auth

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val QR_LOGIN_SCHEME: String = "flaredo"
private const val QR_LOGIN_HOST: String = "qr-login"
internal const val DISCOURSE_QR_LOGIN_VERSION: Int = 1
private const val MAX_QR_LOGIN_URI_CHARS: Int = 8 * 1024
private const val MAX_QR_LOGIN_API_KEY_BYTES: Int = 512
private const val MAX_QR_LOGIN_OTP_BYTES: Int = 256
private const val MAX_QR_LOGIN_USERNAME_CHARS: Int = 254
private val QR_LOGIN_QUERY_NAMES: Set<String> =
    setOf("version", "credential", "ticket", "account", "expires")

/** Stable, non-sensitive QR login failures suitable for presenter state. */
public enum class DiscourseQrLoginFailure {
    InvalidPayload,
    Expired,
    ActiveSession,
    CreateFailed,
    ExchangeFailed,
}

/** QR login failure which never includes a URI, API key, OTP, username, or server body. */
public class DiscourseQrLoginException(
    public val failure: DiscourseQrLoginFailure,
    cause: Throwable? = null,
) : IllegalStateException("QR login failed: ${failure.name}", cause)

/**
 * One short-lived cross-device login capability encoded by [DiscourseQrLoginProtocol].
 *
 * The API key and OTP are intentionally retained as clearable byte arrays. The encoded QR remains a
 * bearer login capability until the OTP expires or [DiscourseQrLoginService] revokes the key, so UI
 * must prevent screenshots where the platform supports that policy and close this value promptly.
 */
public class DiscourseQrLoginPayload internal constructor(
    apiKey: ByteArray,
    otp: ByteArray,
    public val username: String,
    public val expiresAtEpochMillis: Long,
) : AutoCloseable {
    private val retainedApiKey: ByteArray = apiKey.copyOf()
    private val retainedOtp: ByteArray = otp.copyOf()
    private var isClosed: Boolean = false

    init {
        requireValidQrLoginSecrets(retainedApiKey, retainedOtp)
        requireValidQrLoginUsername(username)
        require(expiresAtEpochMillis > 0L) { "QR login expiry must be positive" }
    }

    public fun copyApiKey(): ByteArray {
        check(!isClosed) { "QR login payload is closed" }
        return retainedApiKey.copyOf()
    }

    public fun copyOtp(): ByteArray {
        check(!isClosed) { "QR login payload is closed" }
        return retainedOtp.copyOf()
    }

    public fun isExpired(nowEpochMillis: Long): Boolean = nowEpochMillis < 0L || nowEpochMillis >= expiresAtEpochMillis

    override fun close() {
        if (isClosed) return
        isClosed = true
        retainedApiKey.fill(0)
        retainedOtp.fill(0)
    }

    override fun toString(): String =
        "DiscourseQrLoginPayload(username=<redacted>, expiresAtEpochMillis=$expiresAtEpochMillis, secrets=<redacted>)"
}

/** Strict FlareDo-owned QR wire format; fluxdo application URIs are deliberately not accepted. */
public object DiscourseQrLoginProtocol {
    @OptIn(ExperimentalEncodingApi::class)
    public fun encode(payload: DiscourseQrLoginPayload): String {
        val apiKey = payload.copyApiKey()
        val otp = payload.copyOtp()
        return try {
            val encoded =
                URLBuilder("$QR_LOGIN_SCHEME://$QR_LOGIN_HOST")
                    .apply {
                        parameters.append("version", DISCOURSE_QR_LOGIN_VERSION.toString())
                        parameters.append("credential", Base64.UrlSafe.encode(apiKey).trimEnd('='))
                        parameters.append("ticket", Base64.UrlSafe.encode(otp).trimEnd('='))
                        parameters.append("account", payload.username)
                        parameters.append("expires", payload.expiresAtEpochMillis.toString())
                    }.buildString()
            require(encoded.length <= MAX_QR_LOGIN_URI_CHARS) { "QR login URI exceeds its size limit" }
            encoded
        } finally {
            apiKey.fill(0)
            otp.fill(0)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    public fun parse(rawValue: String): DiscourseQrLoginPayload? {
        val raw = rawValue.trim()
        if (raw.length !in 1..MAX_QR_LOGIN_URI_CHARS) return null
        if (!raw.startsWith("$QR_LOGIN_SCHEME://$QR_LOGIN_HOST?")) return null
        val url =
            try {
                Url(raw)
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (url.protocol.name != QR_LOGIN_SCHEME || url.host != QR_LOGIN_HOST) return null
        if (url.encodedPath.isNotEmpty() || url.fragment.isNotEmpty()) return null
        if (url.port != 0 || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) return null
        if (url.parameters.names() != QR_LOGIN_QUERY_NAMES) return null
        if (url.parameters.singleValue("version") != DISCOURSE_QR_LOGIN_VERSION.toString()) return null

        val apiKey = decodeUrlSafe(url.parameters.singleValue("credential") ?: return null) ?: return null
        val otp =
            decodeUrlSafe(url.parameters.singleValue("ticket") ?: return null)
                ?: run {
                    apiKey.fill(0)
                    return null
                }
        return try {
            val username = url.parameters.singleValue("account") ?: return null
            val expiresAt = url.parameters.singleValue("expires")?.toLongOrNull() ?: return null
            requireValidQrLoginSecrets(apiKey, otp)
            requireValidQrLoginUsername(username)
            DiscourseQrLoginPayload(apiKey, otp, username, expiresAt)
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            apiKey.fill(0)
            otp.fill(0)
        }
    }
}

private fun io.ktor.http.Parameters.singleValue(name: String): String? {
    val values = getAll(name) ?: return null
    return values.singleOrNull()
}

@OptIn(ExperimentalEncodingApi::class)
private fun decodeUrlSafe(value: String): ByteArray? {
    if (value.isEmpty() || value.length > 1_024) return null
    if (value.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '-' && it != '_' }) {
        return null
    }
    val padded = value + "=".repeat((4 - value.length % 4) % 4)
    return try {
        Base64.UrlSafe.decode(padded)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun requireValidQrLoginSecrets(
    apiKey: ByteArray,
    otp: ByteArray,
) {
    require(apiKey.size in 1..MAX_QR_LOGIN_API_KEY_BYTES) { "QR login API key size is invalid" }
    require(apiKey.all { it.toInt() in 0x21..0x7e }) { "QR login API key contains invalid bytes" }
    require(otp.size in 1..MAX_QR_LOGIN_OTP_BYTES) { "QR login OTP size is invalid" }
    require(otp.all { byte -> byte.toInt().toChar() in '0'..'9' || byte.toInt().toChar() in 'a'..'f' }) {
        "QR login OTP contains invalid bytes"
    }
}

private fun requireValidQrLoginUsername(username: String) {
    require(username.length <= MAX_QR_LOGIN_USERNAME_CHARS) { "QR login username is too long" }
    require(username.none { it.code < 0x20 || it.code == 0x7f }) {
        "QR login username contains control characters"
    }
}
