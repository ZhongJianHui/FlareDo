package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.model.discourseJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Stable, non-sensitive categories returned by the Linux.do password-login endpoint. */
public enum class DiscoursePasswordLoginFailureKind {
    InvalidCredentials,
    SecondFactorRequired,
    NotActivated,
    NotApproved,
    PasswordExpired,
    Unknown,
}

/** Capabilities advertised by a Discourse second-factor response. */
public data class DiscoursePasswordLoginSecondFactor(
    public val totpEnabled: Boolean = false,
    public val backupCodeEnabled: Boolean = false,
    public val securityKeyEnabled: Boolean = false,
)

/** Parsed result of a WebView-owned `POST /session.json` response. */
public sealed interface DiscoursePasswordLoginResponse {
    public data object Success : DiscoursePasswordLoginResponse

    public data class Failure(
        public val kind: DiscoursePasswordLoginFailureKind,
        public val secondFactor: DiscoursePasswordLoginSecondFactor? = null,
    ) : DiscoursePasswordLoginResponse

    /** The response was not a valid, bounded login envelope. */
    public data class Unexpected(
        public val statusCode: Int,
    ) : DiscoursePasswordLoginResponse
}

/**
 * Parses only the protocol fields needed by the native login surface.
 *
 * Server response text is deliberately bounded and never returned to callers. This keeps error
 * pages, proxy HTML, and plugin-owned fields out of UI state and diagnostics.
 */
public object DiscoursePasswordLoginResponseParser {
    public const val MAX_RESPONSE_CHARS: Int = 64 * 1024

    public fun parse(
        statusCode: Int,
        body: String,
    ): DiscoursePasswordLoginResponse {
        if (body.length > MAX_RESPONSE_CHARS || statusCode !in 100..599) {
            return DiscoursePasswordLoginResponse.Unexpected(statusCode)
        }

        val payload =
            runCatching { discourseJson.parseToJsonElement(body) as? JsonObject }
                .getOrNull()
                ?: return DiscoursePasswordLoginResponse.Unexpected(statusCode)

        // Discourse returns a user envelope on successful password authentication. A few plugin
        // versions use `current_user`; accepting both keeps the client forward-compatible without
        // treating an arbitrary non-empty response as an authenticated session.
        val hasUserEnvelope = payload["user"] is JsonObject || payload["current_user"] is JsonObject
        if (statusCode in 200..299 && hasUserEnvelope) {
            return DiscoursePasswordLoginResponse.Success
        }

        val reason = payload.string("reason")
        return when (reason) {
            "invalid_credentials" -> {
                DiscoursePasswordLoginResponse.Failure(
                    DiscoursePasswordLoginFailureKind.InvalidCredentials,
                )
            }

            "invalid_second_factor",
            "second_factor",
            -> {
                DiscoursePasswordLoginResponse.Failure(
                    kind = DiscoursePasswordLoginFailureKind.SecondFactorRequired,
                    secondFactor =
                        DiscoursePasswordLoginSecondFactor(
                            totpEnabled = payload.boolean("totp_enabled"),
                            backupCodeEnabled = payload.boolean("backup_enabled"),
                            securityKeyEnabled = payload.boolean("security_key_enabled"),
                        ),
                )
            }

            "not_activated" -> {
                DiscoursePasswordLoginResponse.Failure(
                    DiscoursePasswordLoginFailureKind.NotActivated,
                )
            }

            "not_approved" -> {
                DiscoursePasswordLoginResponse.Failure(
                    DiscoursePasswordLoginFailureKind.NotApproved,
                )
            }

            "expired" -> {
                DiscoursePasswordLoginResponse.Failure(
                    DiscoursePasswordLoginFailureKind.PasswordExpired,
                )
            }

            else -> {
                DiscoursePasswordLoginResponse.Failure(
                    DiscoursePasswordLoginFailureKind.Unknown,
                )
            }
        }
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.let { element ->
            runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
        }

    private fun JsonObject.boolean(name: String): Boolean =
        this[name]?.let { element ->
            runCatching { element.jsonPrimitive.booleanOrNull }.getOrNull()
        } ?: false
}
