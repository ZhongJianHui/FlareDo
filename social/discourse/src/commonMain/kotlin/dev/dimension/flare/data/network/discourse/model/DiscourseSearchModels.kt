package dev.dimension.flare.data.network.discourse.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Search response containing independently side-loaded posts, topics, users, categories, and tags.
 *
 * Search pagination starts at page one. [groupedSearchResult] carries the continuation flags; an
 * empty item array alone must not be interpreted as the end when the server says more results exist.
 */
@Serializable
public data class DiscourseSearchResponse(
    public val posts: List<DiscourseSearchPost> = emptyList(),
    public val topics: List<DiscourseTopicSummary> = emptyList(),
    public val users: List<DiscourseBasicUser> = emptyList(),
    public val categories: List<DiscourseCategory> = emptyList(),
    public val tags: List<DiscourseTag> = emptyList(),
    @SerialName("grouped_search_result")
    public val groupedSearchResult: DiscourseGroupedSearchResult? = null,
)

/** Search-hit post projection. Full cooked content is intentionally fetched from the topic stream. */
@Serializable
public data class DiscourseSearchPost(
    public val id: Long,
    @SerialName("topic_id")
    public val topicId: Long,
    @SerialName("post_number")
    public val postNumber: Int,
    public val username: String = "",
    public val name: String? = null,
    @SerialName("avatar_template")
    public val avatarTemplate: String = "",
    @SerialName("created_at")
    public val createdAt: String? = null,
    @SerialName("like_count")
    public val likeCount: Int = 0,
    public val blurb: String = "",
)

/** Server-owned search continuation and diagnostic metadata. */
@Serializable
public data class DiscourseGroupedSearchResult(
    public val term: String = "",
    @SerialName("more_posts")
    public val morePosts: Boolean? = null,
    @SerialName("more_users")
    public val moreUsers: Boolean? = null,
    @SerialName("more_categories")
    public val moreCategories: Boolean? = null,
    @SerialName("more_full_page_results")
    public val moreFullPageResults: Boolean = false,
    @SerialName("search_log_id")
    public val searchLogId: Long? = null,
    @SerialName("can_create_topic")
    public val canCreateTopic: Boolean = false,
    public val error: String? = null,
    @SerialName("post_ids")
    public val postIds: List<Long> = emptyList(),
    @SerialName("user_ids")
    public val userIds: List<Long> = emptyList(),
    @SerialName("category_ids")
    public val categoryIds: List<Long> = emptyList(),
    @SerialName("tag_ids")
    public val tagIds: List<Long> = emptyList(),
)
