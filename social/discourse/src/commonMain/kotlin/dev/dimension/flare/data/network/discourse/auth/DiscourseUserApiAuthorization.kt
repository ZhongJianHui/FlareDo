package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val MAX_PUBLIC_KEY_PEM_CHARS: Int = 8_192
private const val MIN_PRIVATE_KEY_BYTES: Int = 256
private const val MAX_PRIVATE_KEY_BYTES: Int = 8_192
private const val MIN_RSA_KEY_SIZE_BITS: Int = 2_048
private const val MAX_AUTH_TOKEN_CHARS: Int = 256

/** Fixed Linux.do User API Key authorization contract used by every FlareDo host. */
public object DiscourseUserApiAuthorization {
    public const val APPLICATION_NAME: String = "FlareDo"
    public const val SCOPE: String = "one_time_password"
    public const val AUTH_REDIRECT: String = "discourse://auth_redirect"

    /**
     * Builds the only browser authorization URL accepted by FlareDo.
     *
     * [publicKeyPem] must be an X.509 SubjectPublicKeyInfo PEM. Query parameters are added through
     * Ktor's structured URL builder so PEM line breaks, `+`, `/`, and `=` cannot change the query
     * structure. The origin, path, application name, scope, and callback are intentionally not
     * configurable: accepting an arbitrary site here would widen the credential boundary.
     */
    public fun buildUrl(
        publicKeyPem: String,
        clientId: String,
        nonce: String,
    ): Url {
        requireValidSpkiPublicKeyPem(publicKeyPem)
        requireValidAuthToken(clientId, "Client id")
        requireValidAuthToken(nonce, "Authorization nonce")

        return URLBuilder("$DISCOURSE_ORIGIN/user-api-key/new")
            .apply {
                parameters.append("application_name", APPLICATION_NAME)
                parameters.append("client_id", clientId)
                parameters.append("scopes", SCOPE)
                parameters.append("public_key", publicKeyPem)
                parameters.append("nonce", nonce)
                parameters.append("auth_redirect", AUTH_REDIRECT)
            }.build()
    }
}

/**
 * Clearable RSA material returned by a platform key generator before the private key enters a vault.
 *
 * [copyPrivateKeyPkcs8] returns an independently owned copy which its caller must overwrite after
 * saving. [close] overwrites the retained copy. Kotlin and platform crypto providers may create
 * additional runtime copies which cannot be reliably erased, so this is best-effort containment
 * rather than a claim of guaranteed memory erasure.
 */
public class DiscourseRsaPkcs1KeyPair(
    public val publicKeySpkiPem: String,
    privateKeyPkcs8: ByteArray,
) : AutoCloseable {
    private val retainedPrivateKey: ByteArray
    private var isClosed: Boolean = false

    init {
        requireValidSpkiPublicKeyPem(publicKeySpkiPem)
        require(privateKeyPkcs8.size in MIN_PRIVATE_KEY_BYTES..MAX_PRIVATE_KEY_BYTES) {
            "PKCS#8 private key size is outside the supported bound"
        }
        retainedPrivateKey = privateKeyPkcs8.copyOf()
    }

    /** Returns an independent PKCS#8 copy which the caller owns and must overwrite after use. */
    public fun copyPrivateKeyPkcs8(): ByteArray {
        check(!isClosed) { "The RSA key pair is closed" }
        return retainedPrivateKey.copyOf()
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        retainedPrivateKey.fill(0)
    }
}

/** Platform cryptography boundary for generating an RSA key pair used by one authorization flow. */
public fun interface DiscourseRsaPkcs1KeyPairGenerator {
    /**
     * Generates an RSA key pair of at least [minimumKeySizeBits].
     *
     * Implementations must use a cryptographically secure random source and return an SPKI public
     * PEM plus PKCS#8 private bytes. The shared layer never implements or emulates RSA itself.
     */
    public suspend fun generate(minimumKeySizeBits: Int): DiscourseRsaPkcs1KeyPair
}

/** Platform RSA/ECB/PKCS1Padding decryption boundary for encrypted Discourse callback fields. */
public fun interface DiscourseRsaPkcs1Decryptor {
    /** Returns newly owned plaintext bytes or throws without including key/ciphertext in messages. */
    public suspend fun decrypt(
        privateKeyPkcs8: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray
}

/** Enforces the minimum key size at the common/platform boundary. */
public suspend fun DiscourseRsaPkcs1KeyPairGenerator.generateForDiscourse(): DiscourseRsaPkcs1KeyPair =
    generate(minimumKeySizeBits = MIN_RSA_KEY_SIZE_BITS)

@OptIn(ExperimentalEncodingApi::class)
private fun requireValidSpkiPublicKeyPem(value: String) {
    require(value.length in 1..MAX_PUBLIC_KEY_PEM_CHARS) {
        "SPKI public key PEM size is outside the supported bound"
    }
    require(value.none { it.code < 0x20 && it != '\n' && it != '\r' }) {
        "SPKI public key PEM contains a forbidden control character"
    }

    val normalized = value.replace("\r\n", "\n")
    val header = "-----BEGIN PUBLIC KEY-----\n"
    val footer = "\n-----END PUBLIC KEY-----"
    require(normalized.startsWith(header) && normalized.endsWith(footer)) {
        "RSA public key must use an SPKI PUBLIC KEY PEM envelope"
    }
    val body =
        normalized
            .removePrefix(header)
            .removeSuffix(footer)
            .replace("\n", "")
    require(body.isNotEmpty() && body.length % 4 == 0 && body.isStrictBase64()) {
        "SPKI public key PEM body is not canonical Base64"
    }
    val decoded =
        try {
            Base64.Default.decode(body)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("SPKI public key PEM body is not valid Base64")
        }
    require(decoded.size in MIN_PRIVATE_KEY_BYTES..MAX_PRIVATE_KEY_BYTES) {
        "SPKI public key DER size is outside the supported bound"
    }
}

internal fun requireValidAuthToken(
    value: String,
    label: String,
) {
    require(value.length in 1..MAX_AUTH_TOKEN_CHARS) { "$label size is outside the supported bound" }
    require(value.all { it.isAsciiAuthTokenCharacter() }) {
        "$label contains a forbidden character"
    }
}

private fun Char.isAsciiAuthTokenCharacter(): Boolean =
    this in 'a'..'z' ||
        this in 'A'..'Z' ||
        this in '0'..'9' ||
        this == '-' ||
        this == '_' ||
        this == '.' ||
        this == '~'

private fun String.isStrictBase64(): Boolean {
    val paddingStart = indexOf('=')
    val contentEnd = if (paddingStart < 0) length else paddingStart
    if (length - contentEnd > 2) return false
    if (substring(contentEnd).any { it != '=' }) return false
    return substring(0, contentEnd).all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '+' || it == '/'
    }
}
