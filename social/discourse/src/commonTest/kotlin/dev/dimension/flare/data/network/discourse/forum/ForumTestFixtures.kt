package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.network.discourse.model.DiscoursePost
import dev.dimension.flare.data.network.discourse.model.DiscoursePostStream
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicDetail
import dev.dimension.flare.ui.model.DiscoursePostMeta
import dev.dimension.flare.ui.model.DiscourseTopicMeta
import dev.dimension.flare.ui.model.DiscourseTopicRef
import dev.dimension.flare.ui.model.UiArticle
import dev.dimension.flare.ui.model.UiArticleBlock
import dev.dimension.flare.ui.model.UiAuthor
import dev.dimension.flare.ui.model.UiTimelineV2

internal fun forumTopicRow(
    topicId: Long,
    title: String = "Topic $topicId",
    categoryId: Long? = null,
): UiTimelineV2.Topic =
    UiTimelineV2.Topic(
        itemKey = "discourse-topic:$topicId",
        title = title,
        excerpt = "Excerpt $topicId",
        author = UiAuthor(username = "author$topicId", displayName = "Author $topicId"),
        replyCount = 1,
        viewCount = 2,
        lastActivityEpochMillis = topicId,
        categoryName = null,
        discourse =
            DiscourseTopicMeta(
                ref = DiscourseTopicRef(topicId),
                slug = "topic-$topicId",
                categoryId = categoryId,
            ),
    )

internal fun forumFeedPage(
    feed: DiscourseForumFeed = DiscourseForumFeed.Latest,
    page: Int = 0,
    topicIds: List<Long> = listOf(1L),
    nextPage: Int? = null,
    updatedAtEpochMillis: Long = 1L,
    source: DiscourseForumContentSource = DiscourseForumContentSource.Network,
    fallbackFailure: DiscourseForumFailureKind? = null,
): DiscourseForumFeedPage =
    DiscourseForumFeedPage(
        feed = feed,
        page = page,
        topics = topicIds.distinct().map(::forumTopicRow),
        nextPage = nextPage,
        canCreateTopic = false,
        source = source,
        updatedAtEpochMillis = updatedAtEpochMillis,
        fallbackFailure = fallbackFailure,
    )

internal fun discoursePost(
    id: Long,
    topicId: Long = 42L,
    postNumber: Int = id.toInt(),
    cooked: String = "<p>Post $id</p>",
): DiscoursePost =
    DiscoursePost(
        id = id,
        topicId = topicId,
        postNumber = postNumber,
        username = "author$id",
        name = "Author $id",
        avatarTemplate = "/user_avatar/linux.do/author$id/{size}/1.png",
        cooked = cooked,
        createdAt = "2026-08-19T01:02:03Z",
    )

internal fun discourseTopicDetail(
    topicId: Long = 42L,
    stream: List<Long>,
    initialPosts: List<DiscoursePost> = emptyList(),
): DiscourseTopicDetail =
    DiscourseTopicDetail(
        id = topicId,
        title = "Strict stream topic",
        slug = "strict-stream-topic",
        postStream = DiscoursePostStream(posts = initialPosts, stream = stream),
        postsCount = stream.size,
    )

internal fun forumTopic(
    topicId: Long,
    updatedAtEpochMillis: Long = topicId,
    source: DiscourseForumContentSource = DiscourseForumContentSource.Network,
    fallbackFailure: DiscourseForumFailureKind? = null,
): DiscourseForumTopic =
    DiscourseForumTopic(
        topicId = topicId,
        title = "Topic $topicId",
        slug = "topic-$topicId",
        articles =
            listOf(
                UiArticle(
                    itemKey = "discourse-post:$topicId",
                    title = "Topic $topicId",
                    author = UiAuthor("author", "Author"),
                    createdAtEpochMillis = updatedAtEpochMillis,
                    blocks = listOf(UiArticleBlock.Paragraph("Post $topicId")),
                    discourse =
                        DiscoursePostMeta(
                            topicId = topicId,
                            postId = topicId,
                            postNumber = 1,
                        ),
                ),
            ),
        canReply = false,
        source = source,
        updatedAtEpochMillis = updatedAtEpochMillis,
        fallbackFailure = fallbackFailure,
    )
