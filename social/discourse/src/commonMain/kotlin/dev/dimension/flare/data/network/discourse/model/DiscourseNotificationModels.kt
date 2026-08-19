package dev.dimension.flare.data.network.discourse.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Offset-paginated notification response.
 *
 * [loadMoreNotifications] is a server-generated continuation URL. Callers may derive an offset for
 * diagnostics, but should use their own monotonically increasing offset contract rather than trusting
 * arbitrary hosts or paths embedded in this field.
 */
@Serializable
public data class DiscourseNotificationResponse(
    public val notifications: List<DiscourseNotification>,
    @SerialName("total_rows_notifications")
    public val totalRowsNotifications: Int = 0,
    @SerialName("seen_notification_id")
    public val seenNotificationId: Long = 0,
    @SerialName("load_more_notifications")
    public val loadMoreNotifications: String? = null,
)

/**
 * One notification row.
 *
 * [id], [userId], and [notificationType] are required identity fields. The notification `data` field
 * is deliberately retained as [JsonElement]: Discourse core may emit an object while older serializers
 * and plugins may emit the same object as a JSON string. The mapping layer can normalize known keys
 * without discarding unknown plugin notification data.
 */
@Serializable
public data class DiscourseNotification(
    public val id: Long,
    @SerialName("user_id")
    public val userId: Long,
    @SerialName("notification_type")
    public val notificationType: Int,
    public val read: Boolean = false,
    @SerialName("high_priority")
    public val highPriority: Boolean = false,
    @SerialName("created_at")
    public val createdAt: String? = null,
    @SerialName("post_number")
    public val postNumber: Int? = null,
    @SerialName("topic_id")
    public val topicId: Long? = null,
    public val slug: String? = null,
    public val data: JsonElement? = null,
    @SerialName("fancy_title")
    public val fancyTitle: String? = null,
    @SerialName("acting_user_avatar_template")
    public val actingUserAvatarTemplate: String? = null,
)
