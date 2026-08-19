package dev.dimension.flare.data.network.discourse.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Public, mostly static Linux.do capabilities returned by `GET /site.json`.
 *
 * The endpoint is intentionally modeled as a capability document rather than a complete mirror of
 * every Discourse setting. Optional features and plugin-provided settings use defaults so their
 * absence never prevents anonymous browsing.
 */
@Serializable
public data class DiscourseSiteResponse(
    public val categories: List<DiscourseCategory> = emptyList(),
    @SerialName("top_tags")
    public val topTags: List<DiscourseSiteTag> = emptyList(),
    @SerialName("post_action_types")
    public val postActionTypes: List<DiscourseActionType> = emptyList(),
    @SerialName("topic_flag_types")
    public val topicFlagTypes: List<DiscourseActionType> = emptyList(),
    @SerialName("auth_providers")
    public val authProviders: List<DiscourseAuthProvider> = emptyList(),
    @SerialName("can_create_tag")
    public val canCreateTag: Boolean = false,
    @SerialName("can_tag_topics")
    public val canTagTopics: Boolean = false,
    @SerialName("uncategorized_category_id")
    public val uncategorizedCategoryId: Long? = null,
    @SerialName("privacy_policy_url")
    public val privacyPolicyUrl: String? = null,
    @SerialName("tos_url")
    public val termsOfServiceUrl: String? = null,
)

/**
 * A forum category that can be used for list filtering and composer permission checks.
 *
 * [id], [name], and [slug] form the stable category identity and therefore deliberately have no
 * defaults. Presentation and capability fields may be omitted by serializer variants used on
 * category-specific endpoints.
 */
@Serializable
public data class DiscourseCategory(
    public val id: Long,
    public val name: String,
    public val slug: String,
    public val color: String = "",
    @SerialName("text_color")
    public val textColor: String = "",
    public val description: String? = null,
    @SerialName("description_text")
    public val descriptionText: String? = null,
    @SerialName("description_excerpt")
    public val descriptionExcerpt: String? = null,
    public val position: Int = 0,
    @SerialName("parent_category_id")
    public val parentCategoryId: Long? = null,
    @SerialName("topic_count")
    public val topicCount: Int = 0,
    @SerialName("post_count")
    public val postCount: Int = 0,
    @SerialName("subcategory_count")
    public val subcategoryCount: Int = 0,
    @SerialName("has_children")
    public val hasChildren: Boolean = false,
    @SerialName("read_restricted")
    public val readRestricted: Boolean = false,
    @SerialName("can_edit")
    public val canEdit: Boolean = false,
    public val permission: Int? = null,
    @SerialName("notification_level")
    public val notificationLevel: Int? = null,
    public val icon: String? = null,
    public val emoji: String? = null,
    @SerialName("uploaded_logo")
    public val uploadedLogo: DiscourseUploadedImage? = null,
    @SerialName("uploaded_background")
    public val uploadedBackground: DiscourseUploadedImage? = null,
    @SerialName("minimum_required_tags")
    public val minimumRequiredTags: Int = 0,
    @SerialName("required_tag_groups")
    public val requiredTagGroups: List<DiscourseRequiredTagGroup> = emptyList(),
    @SerialName("custom_fields")
    public val customFields: JsonObject = JsonObject(emptyMap()),
)

/** A category image descriptor. Dimensions are absent for some legacy uploads. */
@Serializable
public data class DiscourseUploadedImage(
    public val id: Long? = null,
    public val url: String,
    public val width: Int? = null,
    public val height: Int? = null,
)

/** Tag constraints advertised by a category for topic creation. */
@Serializable
public data class DiscourseRequiredTagGroup(
    public val name: String? = null,
    @SerialName("min_count")
    public val minimumCount: Int = 0,
    @SerialName("max_count")
    public val maximumCount: Int? = null,
)

/**
 * A top tag from `/site.json`.
 *
 * Linux.do exposes a numeric database ID here, unlike `/tags.json`, whose tag identity is textual.
 */
@Serializable
public data class DiscourseSiteTag(
    public val id: Long,
    public val name: String,
    public val slug: String,
)

/** A tag record returned by `/tags.json`; [text] is the canonical composer value. */
@Serializable
public data class DiscourseTag(
    public val id: Long,
    public val text: String,
    public val name: String = text,
    public val slug: String = name,
    public val description: String? = null,
    public val count: Int = 0,
    @SerialName("pm_only")
    public val privateMessageOnly: Boolean = false,
    @SerialName("target_tag")
    public val targetTag: String? = null,
)

/** Envelope returned by `GET /tags.json`. */
@Serializable
public data class DiscourseTagsResponse(
    public val tags: List<DiscourseTag> = emptyList(),
    public val extras: DiscourseTagExtras? = null,
)

/** Optional tag-list metadata supplied for authenticated users. */
@Serializable
public data class DiscourseTagExtras(
    @SerialName("tag_groups")
    public val tagGroups: List<DiscourseTagGroup> = emptyList(),
    @SerialName("can_create_tag")
    public val canCreateTag: Boolean = false,
    @SerialName("can_tag_pms")
    public val canTagPrivateMessages: Boolean = false,
)

/** A named group used by the composer to present related tag choices. */
@Serializable
public data class DiscourseTagGroup(
    public val id: Long,
    public val name: String,
    public val tags: List<DiscourseTag> = emptyList(),
)

/** Envelope returned by the composer tag autocomplete endpoint. */
@Serializable
public data class DiscourseTagSearchResponse(
    public val results: List<DiscourseTagSearchResult> = emptyList(),
)

/** One autocomplete result; [id] is the canonical tag name sent back to the server. */
@Serializable
public data class DiscourseTagSearchResult(
    public val id: String,
    public val text: String = id,
    public val count: Int = 0,
)

/** A post action or flag type advertised by the site capability document. */
@Serializable
public data class DiscourseActionType(
    public val id: Long,
    public val name: String,
    @SerialName("name_key")
    public val nameKey: String? = null,
    public val description: String? = null,
    @SerialName("short_description")
    public val shortDescription: String? = null,
    @SerialName("is_flag")
    public val isFlag: Boolean = false,
    public val enabled: Boolean = true,
    @SerialName("require_message")
    public val requireMessage: Boolean = false,
    public val position: Int = 0,
)

/** An authentication provider advertised by `/site.json`. */
@Serializable
public data class DiscourseAuthProvider(
    public val name: String,
    @SerialName("provider_url")
    public val providerUrl: String? = null,
    @SerialName("can_connect")
    public val canConnect: Boolean = false,
    @SerialName("can_revoke")
    public val canRevoke: Boolean = false,
    @SerialName("custom_url")
    public val customUrl: String? = null,
)

/** Envelope used by `/categories.json`. */
@Serializable
public data class DiscourseCategoryListResponse(
    @SerialName("category_list")
    public val categoryList: DiscourseCategoryList,
)

/** Category collection and the current user's creation capabilities. */
@Serializable
public data class DiscourseCategoryList(
    public val categories: List<DiscourseCategory> = emptyList(),
    @SerialName("can_create_category")
    public val canCreateCategory: Boolean = false,
    @SerialName("can_create_topic")
    public val canCreateTopic: Boolean = false,
)
