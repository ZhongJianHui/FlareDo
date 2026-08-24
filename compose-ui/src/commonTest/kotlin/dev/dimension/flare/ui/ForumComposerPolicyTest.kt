package dev.dimension.flare.ui

import androidx.compose.ui.unit.dp
import dev.dimension.flare.data.network.discourse.composer.DiscourseActionNotAllowedReason
import dev.dimension.flare.data.network.discourse.composer.DiscourseActionTarget
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerMode
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerState
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerSubmitStatus
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerTarget
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerUploadState
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerUploadStatus
import dev.dimension.flare.data.network.discourse.composer.DiscoursePostActionPresentationState
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumContentSource
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumState
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumTopic
import dev.dimension.flare.ui.model.DiscoursePostMeta
import dev.dimension.flare.ui.model.DiscourseTopicMeta
import dev.dimension.flare.ui.model.DiscourseTopicRef
import dev.dimension.flare.ui.model.UiArticle
import dev.dimension.flare.ui.model.UiAuthor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ForumComposerPolicyTest {
    @Test
    fun newTopicRequiresAuthenticationAndExplicitFeedPermission() {
        assertFalse(forumCanCreateTopic(DiscourseForumState()))
        assertFalse(
            forumCanCreateTopic(
                DiscourseForumState(isAuthenticated = true, canCreateTopic = false),
            ),
        )
        assertFalse(
            forumCanCreateTopic(
                DiscourseForumState(isAuthenticated = false, canCreateTopic = true),
            ),
        )
        assertTrue(
            forumCanCreateTopic(
                DiscourseForumState(isAuthenticated = true, canCreateTopic = true),
            ),
        )
    }

    @Test
    fun postMutationNeedsMatchingSynchronizedServerState() {
        val article = article(canReply = true, canEdit = true, canLike = true, canBookmark = true)
        val beforeSynchronization = forumPostActionAvailability(article, actionState = null)
        assertTrue(beforeSynchronization.canReply)
        assertTrue(beforeSynchronization.canEdit)
        assertTrue(beforeSynchronization.canLike)
        assertFalse(beforeSynchronization.likeEnabled)
        assertTrue(beforeSynchronization.canBookmark)
        assertFalse(beforeSynchronization.bookmarkEnabled)

        val wrongPost =
            DiscoursePostActionPresentationState(
                target = DiscourseActionTarget.Post(99L),
                canLike = true,
                canBookmark = true,
            )
        val mismatched = forumPostActionAvailability(article, wrongPost)
        assertFalse(mismatched.likeEnabled)
        assertFalse(mismatched.bookmarkEnabled)

        val matching =
            DiscoursePostActionPresentationState(
                target = DiscourseActionTarget.Post(POST_ID),
                canLike = true,
                canBookmark = true,
            )
        val synchronized = forumPostActionAvailability(article, matching)
        assertTrue(synchronized.likeEnabled)
        assertTrue(synchronized.bookmarkEnabled)
    }

    @Test
    fun optimisticStateCannotInventAPermissionDeniedByPost() {
        val denied = article(canReply = false, canEdit = false, canLike = false, canBookmark = false)
        val optimistic =
            DiscoursePostActionPresentationState(
                target = DiscourseActionTarget.Post(POST_ID),
                canLike = true,
                canBookmark = true,
            )

        assertEquals(
            ForumPostActionAvailability(
                canReply = false,
                canEdit = false,
                canLike = false,
                likeEnabled = false,
                canBookmark = false,
                bookmarkEnabled = false,
            ),
            forumPostActionAvailability(denied, optimistic),
        )
    }

    @Test
    fun topicBookmarkAlsoRequiresMatchingSynchronizedState() {
        val topic = topic(canReply = true, canBookmark = true)
        val unsynchronized = forumTopicActionAvailability(topic, null)
        assertTrue(unsynchronized.canReply)
        assertTrue(unsynchronized.canBookmark)
        assertFalse(unsynchronized.bookmarkEnabled)

        val synchronized =
            forumTopicActionAvailability(
                topic,
                DiscoursePostActionPresentationState(
                    target = DiscourseActionTarget.Topic(TOPIC_ID),
                    canBookmark = true,
                ),
            )
        assertTrue(synchronized.bookmarkEnabled)
    }

    @Test
    fun bookmarkedStateRequiresServerBookmarkIdentityBeforeDelete() {
        val article = article(canReply = true, canEdit = true, canLike = true, canBookmark = true)
        val postWithoutBookmarkId =
            DiscoursePostActionPresentationState(
                target = DiscourseActionTarget.Post(POST_ID),
                canLike = true,
                canBookmark = true,
                bookmarked = true,
            )
        assertFalse(
            forumPostActionAvailability(article, postWithoutBookmarkId).bookmarkEnabled,
        )

        val topic = topic(canReply = true, canBookmark = true)
        val topicWithoutBookmarkId =
            DiscoursePostActionPresentationState(
                target = DiscourseActionTarget.Topic(TOPIC_ID),
                canBookmark = true,
                bookmarked = true,
            )
        assertFalse(
            forumTopicActionAvailability(topic, topicWithoutBookmarkId).bookmarkEnabled,
        )

        val topicWithBookmarkId = topicWithoutBookmarkId.copy(bookmarkId = 9L)
        assertTrue(forumTopicActionAvailability(topic, topicWithBookmarkId).bookmarkEnabled)
    }

    @Test
    fun composerOpenAndAttachmentPoliciesAreExplicitlySingleFlight() {
        assertTrue(forumCanOpenComposer(DiscourseComposerState()))
        assertFalse(
            forumCanOpenComposer(
                DiscourseComposerState(submitStatus = DiscourseComposerSubmitStatus.Submitting),
            ),
        )
        assertFalse(
            forumCanDismissComposer(
                DiscourseComposerState(submitStatus = DiscourseComposerSubmitStatus.Submitting),
            ),
        )
        assertTrue(forumCanDismissComposer(DiscourseComposerState()))

        val openState = editableReplyState()
        assertFalse(forumCanOpenComposer(openState))
        assertTrue(forumCanPickAttachment(openState, isPicking = false))
        assertFalse(forumCanPickAttachment(openState, isPicking = true))
        assertFalse(
            forumCanPickAttachment(
                openState.copy(
                    upload =
                        DiscourseComposerUploadState(
                            status = DiscourseComposerUploadStatus.Ready,
                        ),
                ),
                isPicking = false,
            ),
        )
        assertFalse(
            forumCanPickAttachment(
                openState.copy(
                    upload =
                        DiscourseComposerUploadState(
                            status = DiscourseComposerUploadStatus.Uploading,
                        ),
                ),
                isPicking = false,
            ),
        )
    }

    @Test
    fun rapidBodyThenTitleAndTagsUpdatesKeepTheLatestWholeEditorSnapshot() {
        val state =
            editableReplyState().copy(
                mode = DiscourseComposerMode.NewTopic,
                target = DiscourseComposerTarget.NewTopic(categoryId = 7L),
                contentVersion = 11L,
                title = "Old title",
                raw = "Old body",
                tags = listOf("old-tag"),
            )
        var editor = state.toForumComposerEditorSnapshot()

        editor = editor.copy(raw = "Newest body")
        editor = editor.copy(title = "Newest title")
        editor = editor.copy(tagsText = "kotlin, desktop")
        val action = requireNotNull(forumComposerUpdateDraftAction(state, editor))

        assertEquals("Newest title", action.title)
        assertEquals("Newest body", action.raw)
        assertEquals(listOf("kotlin", "desktop"), action.tags)
        assertEquals(11L, action.expectedContentVersion)
        assertEquals(state.sessionGeneration, action.expectedSessionGeneration)
        assertEquals(state.accountId, action.expectedAccountId)
        assertEquals(state.target, action.expectedTarget)
    }

    @Test
    fun actionFeedbackUsesOnlyFixedFailureAndPolicyCategories() {
        val state =
            DiscoursePostActionPresentationState(
                target = DiscourseActionTarget.Post(POST_ID),
                likeFailure = DiscourseForumFailureKind.Network,
                likeNotAllowedReason = DiscourseActionNotAllowedReason.PermissionDenied,
                bookmarkNotAllowedReason = DiscourseActionNotAllowedReason.MissingBookmarkId,
            )

        assertEquals(ForumActionFeedbackKind.Network, state.likeFeedbackKind())
        assertEquals(ForumActionFeedbackKind.MissingBookmarkId, state.bookmarkFeedbackKind())
        assertEquals(
            ForumActionFeedbackKind.MissingServerState,
            state
                .copy(
                    likeFailure = null,
                    likeNotAllowedReason = DiscourseActionNotAllowedReason.MissingServerState,
                ).likeFeedbackKind(),
        )
    }

    @Test
    fun actionFeedbackHasDedicatedStableSemanticsTags() {
        assertEquals(
            "forum_post_${POST_ID}_like_feedback",
            ForumTestTags.postAction(POST_ID, "like_feedback"),
        )
        assertEquals(
            "forum_topic_${TOPIC_ID}_bookmark_feedback",
            ForumTestTags.topicAction(TOPIC_ID, "bookmark_feedback"),
        )
    }

    @Test
    fun tagParserBoundsAndDeduplicatesEditorInput() {
        assertEquals(listOf("kotlin", "desktop"), parseForumComposerTags(" kotlin, desktop, kotlin "))
        assertEquals(listOf("one", "two"), parseForumComposerTags("one，two"))
        assertNull(parseForumComposerTags((1..21).joinToString(",") { "tag$it" }))
        assertNull(parseForumComposerTags("safe, bad\u0001tag"))
    }

    @Test
    fun shortComposerScrollsAndNarrowOrLargeTextActionsWrap() {
        assertEquals(
            ForumComposerLayoutPolicy(scrollEditor = true, wrapActions = true),
            forumComposerLayoutPolicyFor(
                availableWidth = 400.dp,
                availableHeight = 400.dp,
                fontScale = 1f,
            ),
        )
        assertEquals(
            ForumComposerLayoutPolicy(scrollEditor = false, wrapActions = true),
            forumComposerLayoutPolicyFor(
                availableWidth = 480.dp,
                availableHeight = 700.dp,
                fontScale = 1.5f,
            ),
        )
        assertEquals(
            ForumComposerLayoutPolicy(scrollEditor = false, wrapActions = false),
            forumComposerLayoutPolicyFor(
                availableWidth = 480.dp,
                availableHeight = 700.dp,
                fontScale = 1f,
            ),
        )
    }

    private fun editableReplyState(): DiscourseComposerState =
        DiscourseComposerState(
            mode = DiscourseComposerMode.Reply,
            sessionGeneration = 7L,
            accountId = "fixture-account",
            target = DiscourseComposerTarget.Reply(TOPIC_ID),
        )

    private fun article(
        canReply: Boolean,
        canEdit: Boolean,
        canLike: Boolean,
        canBookmark: Boolean,
    ): UiArticle =
        UiArticle(
            itemKey = "post-$POST_ID",
            title = "Fixture post",
            author = UiAuthor("fixture", "Fixture"),
            createdAtEpochMillis = 1L,
            blocks = emptyList(),
            canReply = canReply,
            discourse =
                DiscoursePostMeta(
                    topicId = TOPIC_ID,
                    postId = POST_ID,
                    postNumber = 2,
                    canEdit = canEdit,
                    canLike = canLike,
                    canBookmark = canBookmark,
                ),
        )

    private fun topic(
        canReply: Boolean,
        canBookmark: Boolean,
    ): DiscourseForumTopic =
        DiscourseForumTopic(
            topicId = TOPIC_ID,
            title = "Fixture topic",
            slug = "fixture-topic",
            articles = emptyList(),
            canReply = canReply,
            discourse =
                DiscourseTopicMeta(
                    ref = DiscourseTopicRef(TOPIC_ID),
                    slug = "fixture-topic",
                    canBookmark = canBookmark,
                ),
            source = DiscourseForumContentSource.Network,
            updatedAtEpochMillis = 1L,
        )

    private companion object {
        const val TOPIC_ID: Long = 42L
        const val POST_ID: Long = 84L
    }
}
