package dev.dimension.flare.data.network.discourse.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Result of the side-effect-free session probe at `GET /session/current.json`.
 *
 * A null current user represents an anonymous session. Authentication code must still use HTTP status,
 * cookie state, and session generation when deciding whether a previously authenticated session has
 * expired; this DTO alone is not proof that an in-flight request belongs to the active generation.
 */
@Serializable
public data class DiscourseCurrentSessionResponse(
    @SerialName("current_user")
    public val currentUser: DiscourseCurrentUser? = null,
)

/** Minimal authenticated user identity returned by the session probe. */
@Serializable
public data class DiscourseCurrentUser(
    public val id: Long,
    public val username: String,
    public val name: String? = null,
    @SerialName("avatar_template")
    public val avatarTemplate: String = "",
    @SerialName("unread_notifications")
    public val unreadNotifications: Int = 0,
    @SerialName("unread_high_priority_notifications")
    public val unreadHighPriorityNotifications: Int = 0,
    @SerialName("unread_private_messages")
    public val unreadPrivateMessages: Int = 0,
    @SerialName("trust_level")
    public val trustLevel: Int = 0,
    public val moderator: Boolean = false,
    public val admin: Boolean = false,
)

/**
 * CSRF token response returned by `GET /session/csrf`.
 *
 * The token has no default and must never be persisted. A missing or null value is a protocol error,
 * not an empty-token success; request middleware is responsible for holding it only in memory and
 * refreshing it at most once after an explicit CSRF rejection.
 */
@Serializable
public data class DiscourseCsrfResponse(
    public val csrf: String,
)
