package dev.dimension.flare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Bookmark
import compose.icons.fontawesomeicons.solid.CircleCheck
import compose.icons.fontawesomeicons.solid.Heart
import compose.icons.fontawesomeicons.solid.Paperclip
import compose.icons.fontawesomeicons.solid.PenToSquare
import compose.icons.fontawesomeicons.solid.Plus
import compose.icons.fontawesomeicons.solid.Reply
import compose.icons.fontawesomeicons.solid.RotateRight
import compose.icons.fontawesomeicons.solid.Trash
import compose.icons.fontawesomeicons.solid.Xmark
import dev.dimension.flare.compose.ui.Res
import dev.dimension.flare.compose.ui.forum_action_feedback
import dev.dimension.flare.compose.ui.forum_action_missing_bookmark
import dev.dimension.flare.compose.ui.forum_action_state_unavailable
import dev.dimension.flare.compose.ui.forum_bookmark
import dev.dimension.flare.compose.ui.forum_composer_attach
import dev.dimension.flare.compose.ui.forum_composer_body
import dev.dimension.flare.compose.ui.forum_composer_cancel_upload
import dev.dimension.flare.compose.ui.forum_composer_close
import dev.dimension.flare.compose.ui.forum_composer_discard
import dev.dimension.flare.compose.ui.forum_composer_edit
import dev.dimension.flare.compose.ui.forum_composer_failure_authentication
import dev.dimension.flare.compose.ui.forum_composer_failure_category
import dev.dimension.flare.compose.ui.forum_composer_failure_edit_identity
import dev.dimension.flare.compose.ui.forum_composer_failure_empty_body
import dev.dimension.flare.compose.ui.forum_composer_failure_file_too_large
import dev.dimension.flare.compose.ui.forum_composer_failure_invalid_response
import dev.dimension.flare.compose.ui.forum_composer_failure_missing_title
import dev.dimension.flare.compose.ui.forum_composer_failure_network
import dev.dimension.flare.compose.ui.forum_composer_failure_permission
import dev.dimension.flare.compose.ui.forum_composer_failure_rate_limited
import dev.dimension.flare.compose.ui.forum_composer_failure_read_file
import dev.dimension.flare.compose.ui.forum_composer_failure_server
import dev.dimension.flare.compose.ui.forum_composer_failure_tags
import dev.dimension.flare.compose.ui.forum_composer_failure_unexpected_title
import dev.dimension.flare.compose.ui.forum_composer_failure_unknown
import dev.dimension.flare.compose.ui.forum_composer_loading
import dev.dimension.flare.compose.ui.forum_composer_new_topic
import dev.dimension.flare.compose.ui.forum_composer_publish
import dev.dimension.flare.compose.ui.forum_composer_published
import dev.dimension.flare.compose.ui.forum_composer_queued
import dev.dimension.flare.compose.ui.forum_composer_reply
import dev.dimension.flare.compose.ui.forum_composer_retry_upload
import dev.dimension.flare.compose.ui.forum_composer_save_edit
import dev.dimension.flare.compose.ui.forum_composer_saved
import dev.dimension.flare.compose.ui.forum_composer_saving
import dev.dimension.flare.compose.ui.forum_composer_submitting
import dev.dimension.flare.compose.ui.forum_composer_tags
import dev.dimension.flare.compose.ui.forum_composer_tags_hint
import dev.dimension.flare.compose.ui.forum_composer_title
import dev.dimension.flare.compose.ui.forum_composer_upload_cancelled
import dev.dimension.flare.compose.ui.forum_composer_upload_complete
import dev.dimension.flare.compose.ui.forum_composer_uploading
import dev.dimension.flare.compose.ui.forum_composer_uploading_unknown
import dev.dimension.flare.compose.ui.forum_edit_post
import dev.dimension.flare.compose.ui.forum_failure_authentication
import dev.dimension.flare.compose.ui.forum_failure_challenge
import dev.dimension.flare.compose.ui.forum_failure_http
import dev.dimension.flare.compose.ui.forum_like
import dev.dimension.flare.compose.ui.forum_remove_bookmark
import dev.dimension.flare.compose.ui.forum_reply
import dev.dimension.flare.compose.ui.forum_reply_to_post
import dev.dimension.flare.compose.ui.forum_retry
import dev.dimension.flare.compose.ui.forum_unlike
import dev.dimension.flare.data.network.discourse.DiscourseUploadRequest
import dev.dimension.flare.data.network.discourse.composer.DiscourseActionNotAllowedReason
import dev.dimension.flare.data.network.discourse.composer.DiscourseActionTarget
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerDraftStatus
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerMode
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerPresenter
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerState
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerSubmitStatus
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerTarget
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerUploadStatus
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerValidationFailure
import dev.dimension.flare.data.network.discourse.composer.DiscoursePostActionPresentationState
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumState
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumTopic
import dev.dimension.flare.ui.model.UiArticle
import org.jetbrains.compose.resources.stringResource

/** UI commands are data-only so screenshot and host tests never need a live write repository. */
internal sealed interface ForumComposerAction {
    data class OpenNewTopic(
        val categoryId: Long?,
    ) : ForumComposerAction

    data class OpenReply(
        val topicId: Long,
        val replyToPostNumber: Int?,
    ) : ForumComposerAction

    data class OpenEdit(
        val topicId: Long,
        val postId: Long,
        val postNumber: Int,
    ) : ForumComposerAction

    data object Close : ForumComposerAction

    data object Discard : ForumComposerAction

    data object RetryInitialization : ForumComposerAction

    data class UpdateDraft(
        val title: String?,
        val raw: String,
        val tags: List<String>,
        val expectedSessionGeneration: Long,
        val expectedAccountId: String,
        val expectedTarget: DiscourseComposerTarget,
        val expectedContentVersion: Long,
    ) : ForumComposerAction

    data object Submit : ForumComposerAction

    data class StartUpload(
        val request: DiscourseUploadRequest,
        val expectedSessionGeneration: Long,
        val expectedAccountId: String,
        val expectedTarget: DiscourseComposerTarget,
        val expectedContentVersion: Long,
    ) : ForumComposerAction

    data object CancelUpload : ForumComposerAction

    data object RetryUpload : ForumComposerAction

    data class SynchronizeTopic(
        val topic: DiscourseForumTopic,
    ) : ForumComposerAction

    data class SynchronizePost(
        val article: UiArticle,
    ) : ForumComposerAction

    data class ToggleLike(
        val postId: Long,
    ) : ForumComposerAction

    data class TogglePostBookmark(
        val postId: Long,
    ) : ForumComposerAction

    data class ToggleTopicBookmark(
        val topicId: Long,
    ) : ForumComposerAction
}

/** Maps UI commands to the presenter's bounded actor without launching unstructured coroutines. */
internal fun DiscourseComposerPresenter.dispatchForumAction(action: ForumComposerAction): Boolean =
    when (action) {
        is ForumComposerAction.OpenNewTopic -> {
            openNewTopic(action.categoryId)
        }

        is ForumComposerAction.OpenReply -> {
            openReply(action.topicId, action.replyToPostNumber)
        }

        is ForumComposerAction.OpenEdit -> {
            openEdit(action.topicId, action.postId, action.postNumber)
        }

        ForumComposerAction.Close -> {
            closeComposer()
        }

        ForumComposerAction.Discard -> {
            discardDraft()
        }

        ForumComposerAction.RetryInitialization -> {
            retryInitialization()
        }

        is ForumComposerAction.UpdateDraft -> {
            runCatching {
                updateDraft(
                    title = action.title,
                    raw = action.raw,
                    tags = action.tags,
                    expectedContentVersion = action.expectedContentVersion,
                    expectedSessionGeneration = action.expectedSessionGeneration,
                    expectedAccountId = action.expectedAccountId,
                    expectedTarget = action.expectedTarget,
                )
            }.getOrDefault(false)
        }

        ForumComposerAction.Submit -> {
            submit()
        }

        is ForumComposerAction.StartUpload -> {
            startUpload(
                request = action.request,
                expectedSessionGeneration = action.expectedSessionGeneration,
                expectedAccountId = action.expectedAccountId,
                expectedTarget = action.expectedTarget,
                expectedContentVersion = action.expectedContentVersion,
            )
        }

        ForumComposerAction.CancelUpload -> {
            cancelUpload()
        }

        ForumComposerAction.RetryUpload -> {
            retryUpload()
        }

        is ForumComposerAction.SynchronizeTopic -> {
            synchronizeTopicActions(action.topic)
        }

        is ForumComposerAction.SynchronizePost -> {
            synchronizePostActions(action.article)
        }

        is ForumComposerAction.ToggleLike -> {
            toggleLike(action.postId)
        }

        is ForumComposerAction.TogglePostBookmark -> {
            togglePostBookmark(action.postId)
        }

        is ForumComposerAction.ToggleTopicBookmark -> {
            toggleTopicBookmark(action.topicId)
        }
    }

/** Permissions shown by the topic header, derived only from the current server response. */
internal data class ForumTopicActionAvailability(
    val canReply: Boolean,
    val canBookmark: Boolean,
    val bookmarkEnabled: Boolean,
)

/** Permissions shown beneath one post, with optimistic state required before mutation is enabled. */
internal data class ForumPostActionAvailability(
    val canReply: Boolean,
    val canEdit: Boolean,
    val canLike: Boolean,
    val likeEnabled: Boolean,
    val canBookmark: Boolean,
    val bookmarkEnabled: Boolean,
)

internal fun forumCanCreateTopic(state: DiscourseForumState): Boolean = state.isAuthenticated && state.canCreateTopic

/** A second open command must never replace an active editor or a terminal submit transition. */
internal fun forumCanOpenComposer(state: DiscourseComposerState): Boolean =
    state.mode == DiscourseComposerMode.Closed &&
        state.submitStatus != DiscourseComposerSubmitStatus.Submitting

/** A submitting editor consumes dismissal gestures until its terminal server result is known. */
internal fun forumCanDismissComposer(state: DiscourseComposerState): Boolean =
    state.submitStatus != DiscourseComposerSubmitStatus.Submitting

/** File selection is single-flight from selection through upload start. */
internal fun forumCanPickAttachment(
    state: DiscourseComposerState,
    isPicking: Boolean,
): Boolean =
    state.canEdit &&
        !isPicking &&
        state.upload.status !in
        setOf(
            DiscourseComposerUploadStatus.Ready,
            DiscourseComposerUploadStatus.Uploading,
        )

/** One owner-local editor value prevents title/tag input from reintroducing an older body. */
internal data class ForumComposerEditorSnapshot(
    val title: String,
    val raw: String,
    val tagsText: String,
)

/** Identity frozen before any conflated editor dispatch or asynchronous platform picker. */
internal data class ForumComposerContentOwnerSnapshot(
    val sessionGeneration: Long,
    val accountId: String,
    val target: DiscourseComposerTarget,
    val contentVersion: Long,
)

internal fun DiscourseComposerState.toForumComposerContentOwnerOrNull(): ForumComposerContentOwnerSnapshot? {
    val validGeneration = sessionGeneration.takeIf { it >= 0L } ?: return null
    val validAccountId = accountId ?: return null
    val validTarget = target ?: return null
    return ForumComposerContentOwnerSnapshot(
        sessionGeneration = validGeneration,
        accountId = validAccountId,
        target = validTarget,
        contentVersion = contentVersion,
    )
}

internal fun DiscourseComposerState.toForumComposerEditorSnapshot(): ForumComposerEditorSnapshot =
    ForumComposerEditorSnapshot(
        title = title.orEmpty(),
        raw = raw,
        tagsText = tags.joinToString(", "),
    )

/** Builds a CAS-protected whole-editor update from one internally consistent local snapshot. */
internal fun forumComposerUpdateDraftAction(
    state: DiscourseComposerState,
    editor: ForumComposerEditorSnapshot,
): ForumComposerAction.UpdateDraft? {
    val owner = state.toForumComposerContentOwnerOrNull() ?: return null
    val parsedTags = parseForumComposerTags(editor.tagsText) ?: return null
    return ForumComposerAction.UpdateDraft(
        title = editor.title.takeIf { state.mode == DiscourseComposerMode.NewTopic },
        raw = editor.raw,
        tags = parsedTags.takeIf { state.mode == DiscourseComposerMode.NewTopic }.orEmpty(),
        expectedSessionGeneration = owner.sessionGeneration,
        expectedAccountId = owner.accountId,
        expectedTarget = owner.target,
        expectedContentVersion = owner.contentVersion,
    )
}

internal fun forumTopicActionAvailability(
    topic: DiscourseForumTopic,
    actionState: DiscoursePostActionPresentationState?,
): ForumTopicActionAvailability {
    val metadata = topic.discourse
    val matchingState =
        actionState?.takeIf { it.target == DiscourseActionTarget.Topic(topic.topicId) }
    val canBookmark = metadata?.canBookmark == true
    return ForumTopicActionAvailability(
        canReply = topic.canReply,
        canBookmark = canBookmark,
        bookmarkEnabled =
            canBookmark &&
                matchingState?.canBookmark == true &&
                !matchingState.isBookmarkInFlight &&
                (!matchingState.bookmarked || matchingState.bookmarkId != null),
    )
}

internal fun forumPostActionAvailability(
    article: UiArticle,
    actionState: DiscoursePostActionPresentationState?,
): ForumPostActionAvailability {
    val metadata = article.discourse
    val matchingState =
        metadata?.let { post ->
            actionState?.takeIf { it.target == DiscourseActionTarget.Post(post.postId) }
        }
    val canLike = metadata?.canLike == true
    val canBookmark = metadata?.canBookmark == true
    return ForumPostActionAvailability(
        canReply = article.canReply && metadata != null,
        canEdit = metadata?.canEdit == true,
        canLike = canLike,
        likeEnabled = canLike && matchingState?.canLike == true && !matchingState.isLikeInFlight,
        canBookmark = canBookmark,
        bookmarkEnabled =
            canBookmark &&
                matchingState?.canBookmark == true &&
                !matchingState.isBookmarkInFlight &&
                (!matchingState.bookmarked || matchingState.bookmarkId != null),
    )
}

/** Fixed local feedback categories; arbitrary server response bodies never enter UI state. */
internal enum class ForumActionFeedbackKind {
    Network,
    Authentication,
    Permission,
    RateLimited,
    ChallengeRequired,
    Server,
    InvalidResponse,
    Http,
    MissingServerState,
    MissingBookmarkId,
}

internal fun DiscoursePostActionPresentationState.likeFeedbackKind(): ForumActionFeedbackKind? =
    likeFailure?.toForumActionFeedbackKind()
        ?: likeNotAllowedReason?.toForumActionFeedbackKind()

internal fun DiscoursePostActionPresentationState.bookmarkFeedbackKind(): ForumActionFeedbackKind? =
    bookmarkFailure?.toForumActionFeedbackKind()
        ?: bookmarkNotAllowedReason?.toForumActionFeedbackKind()

private fun DiscourseForumFailureKind.toForumActionFeedbackKind(): ForumActionFeedbackKind =
    when (this) {
        DiscourseForumFailureKind.Network -> ForumActionFeedbackKind.Network
        DiscourseForumFailureKind.Authentication -> ForumActionFeedbackKind.Authentication
        DiscourseForumFailureKind.Permission -> ForumActionFeedbackKind.Permission
        DiscourseForumFailureKind.RateLimited -> ForumActionFeedbackKind.RateLimited
        DiscourseForumFailureKind.ChallengeRequired -> ForumActionFeedbackKind.ChallengeRequired
        DiscourseForumFailureKind.Server -> ForumActionFeedbackKind.Server
        DiscourseForumFailureKind.InvalidResponse -> ForumActionFeedbackKind.InvalidResponse
        DiscourseForumFailureKind.Http -> ForumActionFeedbackKind.Http
    }

private fun DiscourseActionNotAllowedReason.toForumActionFeedbackKind(): ForumActionFeedbackKind =
    when (this) {
        DiscourseActionNotAllowedReason.MissingServerState -> {
            ForumActionFeedbackKind.MissingServerState
        }

        DiscourseActionNotAllowedReason.PermissionDenied -> {
            ForumActionFeedbackKind.Permission
        }

        DiscourseActionNotAllowedReason.MissingBookmarkId -> {
            ForumActionFeedbackKind.MissingBookmarkId
        }
    }

/** Flat action row for the selected topic; unavailable server actions are not rendered. */
@Composable
internal fun ForumTopicActionBar(
    topic: DiscourseForumTopic,
    composerState: DiscourseComposerState,
    onAction: (ForumComposerAction) -> Unit,
) {
    val actionState = composerState.actionStateFor(DiscourseActionTarget.Topic(topic.topicId))
    val availability = forumTopicActionAvailability(topic, actionState)
    if (!availability.canReply && !availability.canBookmark) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (availability.canReply) {
                ForumInlineAction(
                    icon = FontAwesomeIcons.Solid.Reply,
                    label = stringResource(Res.string.forum_reply),
                    enabled = forumCanOpenComposer(composerState),
                    onClick = {
                        onAction(
                            ForumComposerAction.OpenReply(
                                topic.topicId,
                                replyToPostNumber = null,
                            ),
                        )
                    },
                )
            }
            if (availability.canBookmark) {
                val bookmarked = actionState?.bookmarked ?: topic.discourse?.bookmarked == true
                ForumInlineAction(
                    icon = FontAwesomeIcons.Solid.Bookmark,
                    label =
                        stringResource(
                            if (bookmarked) {
                                Res.string.forum_remove_bookmark
                            } else {
                                Res.string.forum_bookmark
                            },
                        ),
                    enabled = availability.bookmarkEnabled,
                    selected = bookmarked,
                    testTag = ForumTestTags.topicAction(topic.topicId, "bookmark"),
                    onClick = { onAction(ForumComposerAction.ToggleTopicBookmark(topic.topicId)) },
                )
            }
        }
        actionState?.bookmarkFeedbackKind()?.let { feedback ->
            ForumActionFeedbackLine(
                actionLabel = stringResource(Res.string.forum_bookmark),
                feedback = feedback,
                testTag = ForumTestTags.topicAction(topic.topicId, "bookmark_feedback"),
            )
        }
    }
}

/** Per-post reply/edit/like/bookmark row kept visually subordinate to sanitized content. */
@Composable
internal fun ForumPostActionBar(
    article: UiArticle,
    composerState: DiscourseComposerState,
    onAction: (ForumComposerAction) -> Unit,
) {
    val metadata = article.discourse ?: return
    val actionState = composerState.actionStateFor(DiscourseActionTarget.Post(metadata.postId))
    val availability = forumPostActionAvailability(article, actionState)
    if (
        !availability.canReply &&
        !availability.canEdit &&
        !availability.canLike &&
        !availability.canBookmark
    ) {
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (availability.canReply) {
                ForumInlineAction(
                    icon = FontAwesomeIcons.Solid.Reply,
                    label = stringResource(Res.string.forum_reply_to_post, metadata.postNumber),
                    enabled = forumCanOpenComposer(composerState),
                    onClick = {
                        onAction(
                            ForumComposerAction.OpenReply(
                                topicId = metadata.topicId,
                                replyToPostNumber = metadata.postNumber,
                            ),
                        )
                    },
                )
            }
            if (availability.canEdit) {
                ForumInlineAction(
                    icon = FontAwesomeIcons.Solid.PenToSquare,
                    label = stringResource(Res.string.forum_edit_post),
                    enabled = forumCanOpenComposer(composerState),
                    onClick = {
                        onAction(
                            ForumComposerAction.OpenEdit(
                                topicId = metadata.topicId,
                                postId = metadata.postId,
                                postNumber = metadata.postNumber,
                            ),
                        )
                    },
                )
            }
            if (availability.canLike) {
                val liked = actionState?.liked ?: metadata.liked
                val count = actionState?.likeCount ?: metadata.likeCount
                ForumInlineAction(
                    icon = FontAwesomeIcons.Solid.Heart,
                    label =
                        buildString {
                            append(
                                stringResource(
                                    if (liked) Res.string.forum_unlike else Res.string.forum_like,
                                ),
                            )
                            if (count > 0) append("  $count")
                        },
                    enabled = availability.likeEnabled,
                    selected = liked,
                    testTag = ForumTestTags.postAction(metadata.postId, "like"),
                    onClick = { onAction(ForumComposerAction.ToggleLike(metadata.postId)) },
                )
            }
            if (availability.canBookmark) {
                val bookmarked = actionState?.bookmarked ?: metadata.bookmarked
                ForumInlineAction(
                    icon = FontAwesomeIcons.Solid.Bookmark,
                    label =
                        stringResource(
                            if (bookmarked) {
                                Res.string.forum_remove_bookmark
                            } else {
                                Res.string.forum_bookmark
                            },
                        ),
                    enabled = availability.bookmarkEnabled,
                    selected = bookmarked,
                    testTag = ForumTestTags.postAction(metadata.postId, "bookmark"),
                    onClick = { onAction(ForumComposerAction.TogglePostBookmark(metadata.postId)) },
                )
            }
        }
        actionState?.likeFeedbackKind()?.let { feedback ->
            ForumActionFeedbackLine(
                actionLabel = stringResource(Res.string.forum_like),
                feedback = feedback,
                testTag = ForumTestTags.postAction(metadata.postId, "like_feedback"),
            )
        }
        actionState?.bookmarkFeedbackKind()?.let { feedback ->
            ForumActionFeedbackLine(
                actionLabel = stringResource(Res.string.forum_bookmark),
                feedback = feedback,
                testTag = ForumTestTags.postAction(metadata.postId, "bookmark_feedback"),
            )
        }
    }
}

@Composable
private fun ForumActionFeedbackLine(
    actionLabel: String,
    feedback: ForumActionFeedbackKind,
    testTag: String,
) {
    Text(
        text =
            stringResource(
                Res.string.forum_action_feedback,
                actionLabel,
                feedback.forumActionFeedbackText(),
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp)
                .testTag(testTag)
                .semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ForumActionFeedbackKind.forumActionFeedbackText(): String =
    stringResource(
        when (this) {
            ForumActionFeedbackKind.Network -> {
                Res.string.forum_composer_failure_network
            }

            ForumActionFeedbackKind.Authentication -> {
                Res.string.forum_failure_authentication
            }

            ForumActionFeedbackKind.Permission -> {
                Res.string.forum_composer_failure_permission
            }

            ForumActionFeedbackKind.RateLimited -> {
                Res.string.forum_composer_failure_rate_limited
            }

            ForumActionFeedbackKind.ChallengeRequired -> {
                Res.string.forum_failure_challenge
            }

            ForumActionFeedbackKind.Server -> {
                Res.string.forum_composer_failure_server
            }

            ForumActionFeedbackKind.InvalidResponse -> {
                Res.string.forum_composer_failure_invalid_response
            }

            ForumActionFeedbackKind.Http -> {
                Res.string.forum_failure_http
            }

            ForumActionFeedbackKind.MissingServerState -> {
                Res.string.forum_action_state_unavailable
            }

            ForumActionFeedbackKind.MissingBookmarkId -> {
                Res.string.forum_action_missing_bookmark
            }
        },
    )

@Composable
private fun ForumInlineAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    selected: Boolean = false,
    testTag: String? = null,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = testTag?.let(Modifier::testTag) ?: Modifier,
        shape = RoundedCornerShape(4.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint =
                if (selected) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

/** Adaptive overlay: compact uses a bottom work sheet; wider windows use an end work pane. */
@Composable
internal fun ForumComposerLayer(
    layoutClass: ForumLayoutClass,
    state: DiscourseComposerState,
    onAction: (ForumComposerAction) -> Unit,
    attachmentPicker: ForumAttachmentPicker,
    modifier: Modifier = Modifier,
) {
    if (state.mode == DiscourseComposerMode.Closed) return
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
    ) {
        val paneModifier =
            when (layoutClass) {
                ForumLayoutClass.Compact -> {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.94f)
                }

                ForumLayoutClass.Medium,
                ForumLayoutClass.Expanded,
                -> {
                    Modifier
                        .align(Alignment.CenterEnd)
                        .widthIn(min = 400.dp, max = 480.dp)
                        .fillMaxHeight()
                }
            }
        Surface(
            modifier = paneModifier.testTag(ForumTestTags.COMPOSER),
            shape =
                if (layoutClass == ForumLayoutClass.Compact) {
                    RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                } else {
                    RoundedCornerShape(0.dp)
                },
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Row(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .width(ForumActiveSpineWidth)
                        .fillMaxHeight()
                        .background(state.composerSpineColor()),
                )
                ForumComposerPane(
                    state = state,
                    onAction = onAction,
                    attachmentPicker = attachmentPicker,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ForumComposerPane(
    state: DiscourseComposerState,
    onAction: (ForumComposerAction) -> Unit,
    attachmentPicker: ForumAttachmentPicker,
    modifier: Modifier,
) {
    var pickFailure by remember(state.target) { mutableStateOf<ForumAttachmentPickResult?>(null) }
    var isPicking by remember(state.target) { mutableStateOf(false) }
    val initializationFailure = state.initializationFailure
    Column(modifier = modifier.fillMaxHeight()) {
        ForumComposerHeader(state, onAction)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            state.isInitializing -> {
                ForumComposerLoading(Modifier.weight(1f))
            }

            initializationFailure != null -> {
                ForumComposerInitializationFailure(
                    failure = initializationFailure,
                    onRetry = { onAction(ForumComposerAction.RetryInitialization) },
                    onDiscard = { onAction(ForumComposerAction.Discard) },
                    canDiscard = forumCanDismissComposer(state),
                    modifier = Modifier.weight(1f),
                )
            }

            else -> {
                key(state.sessionGeneration, state.target, state.isInitializing) {
                    ForumComposerEditor(
                        state = state,
                        pickFailure = pickFailure,
                        isPicking = isPicking,
                        onAction = onAction,
                        onPickAttachment = {
                            if (isPicking) return@ForumComposerEditor
                            val owner =
                                state.toForumComposerContentOwnerOrNull()
                                    ?: return@ForumComposerEditor
                            isPicking = true
                            pickFailure = null
                            attachmentPicker.launch { result ->
                                isPicking = false
                                when (result) {
                                    is ForumAttachmentPickResult.Selected -> {
                                        val request =
                                            runCatching { result.attachment.toUploadRequest() }
                                                .getOrNull()
                                        if (request == null) {
                                            pickFailure = ForumAttachmentPickResult.ReadFailed
                                        } else {
                                            onAction(
                                                ForumComposerAction.StartUpload(
                                                    request = request,
                                                    expectedSessionGeneration =
                                                        owner.sessionGeneration,
                                                    expectedAccountId = owner.accountId,
                                                    expectedTarget = owner.target,
                                                    expectedContentVersion = owner.contentVersion,
                                                ),
                                            )
                                        }
                                    }

                                    ForumAttachmentPickResult.Cancelled -> {}

                                    ForumAttachmentPickResult.ReadFailed,
                                    ForumAttachmentPickResult.TooLarge,
                                    -> {
                                        pickFailure = result
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ForumComposerHeader(
    state: DiscourseComposerState,
    onAction: (ForumComposerAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(66.dp).padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                state.composerTitle(),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "linux.do  /  Markdown",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = { onAction(ForumComposerAction.Close) },
            enabled = forumCanDismissComposer(state),
            modifier = Modifier.testTag(ForumTestTags.COMPOSER_CLOSE),
        ) {
            Icon(
                FontAwesomeIcons.Solid.Xmark,
                stringResource(Res.string.forum_composer_close),
                Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ForumComposerEditor(
    state: DiscourseComposerState,
    pickFailure: ForumAttachmentPickResult?,
    isPicking: Boolean,
    onAction: (ForumComposerAction) -> Unit,
    onPickAttachment: () -> Unit,
) {
    var editor by remember { mutableStateOf(state.toForumComposerEditorSnapshot()) }
    LaunchedEffect(state.contentVersion) {
        editor = state.toForumComposerEditorSnapshot()
    }
    val editable =
        state.canEdit &&
            state.submitStatus != DiscourseComposerSubmitStatus.Published &&
            state.submitStatus != DiscourseComposerSubmitStatus.PendingModeration

    fun publishDraft(next: ForumComposerEditorSnapshot) {
        val action = forumComposerUpdateDraftAction(state, next) ?: return
        editor = next
        onAction(action)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.mode == DiscourseComposerMode.NewTopic) {
            OutlinedTextField(
                value = editor.title,
                onValueChange = { value ->
                    if (isForumComposerTitleInputValid(value)) {
                        publishDraft(editor.copy(title = value))
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag(ForumTestTags.COMPOSER_TITLE),
                enabled = editable,
                singleLine = true,
                label = { Text(stringResource(Res.string.forum_composer_title)) },
            )
        }
        OutlinedTextField(
            // This owner-local whole-editor snapshot keeps rapid body/title/tag edits coherent. The
            // content-version effect still adopts the presenter's atomic upload Markdown insertion.
            value = editor.raw,
            onValueChange = { value ->
                if (isForumComposerRawInputValid(value)) {
                    publishDraft(editor.copy(raw = value))
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 80.dp)
                    .testTag(ForumTestTags.COMPOSER_BODY),
            enabled = editable,
            label = { Text(stringResource(Res.string.forum_composer_body)) },
        )
        if (state.mode == DiscourseComposerMode.NewTopic) {
            OutlinedTextField(
                value = editor.tagsText,
                onValueChange = { value ->
                    if (parseForumComposerTags(value) != null) {
                        publishDraft(editor.copy(tagsText = value))
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag(ForumTestTags.COMPOSER_TAGS),
                enabled = editable,
                singleLine = true,
                label = { Text(stringResource(Res.string.forum_composer_tags)) },
                placeholder = { Text(stringResource(Res.string.forum_composer_tags_hint)) },
            )
        }
        ForumComposerMessages(state, pickFailure)
        ForumUploadStatus(state, onAction)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onPickAttachment,
                enabled = forumCanPickAttachment(state, isPicking),
                modifier = Modifier.testTag(ForumTestTags.COMPOSER_ATTACH),
                shape = RoundedCornerShape(5.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (isPicking) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                } else {
                    Icon(FontAwesomeIcons.Solid.Paperclip, null, Modifier.size(15.dp))
                }
                Spacer(Modifier.width(7.dp))
                Text(stringResource(Res.string.forum_composer_attach))
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { onAction(ForumComposerAction.Discard) },
                enabled = forumCanDismissComposer(state),
                modifier = Modifier.testTag(ForumTestTags.COMPOSER_DISCARD),
            ) {
                Icon(FontAwesomeIcons.Solid.Trash, null, Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.forum_composer_discard))
            }
            Button(
                onClick = { onAction(ForumComposerAction.Submit) },
                enabled = editable && state.canSubmit,
                modifier = Modifier.testTag(ForumTestTags.COMPOSER_SUBMIT),
                shape = RoundedCornerShape(5.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (state.submitStatus == DiscourseComposerSubmitStatus.Submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(FontAwesomeIcons.Solid.Plus, null, Modifier.size(14.dp))
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    stringResource(
                        if (state.mode == DiscourseComposerMode.Edit) {
                            Res.string.forum_composer_save_edit
                        } else {
                            Res.string.forum_composer_publish
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun ForumUploadStatus(
    state: DiscourseComposerState,
    onAction: (ForumComposerAction) -> Unit,
) {
    val upload = state.upload
    when (upload.status) {
        DiscourseComposerUploadStatus.None,
        DiscourseComposerUploadStatus.Ready,
        -> {}

        DiscourseComposerUploadStatus.Uploading -> {
            val fraction =
                upload.totalBytes
                    ?.takeIf { it > 0L }
                    ?.let { total -> upload.bytesSent.toFloat() / total.toFloat() }
                    ?.coerceIn(0f, 1f)
            Column(
                modifier = Modifier.fillMaxWidth().testTag(ForumTestTags.COMPOSER_UPLOAD),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (fraction == null) {
                            stringResource(Res.string.forum_composer_uploading_unknown)
                        } else {
                            stringResource(Res.string.forum_composer_uploading, (fraction * 100).toInt())
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { onAction(ForumComposerAction.CancelUpload) },
                        modifier = Modifier.testTag(ForumTestTags.COMPOSER_CANCEL_UPLOAD),
                    ) {
                        Text(stringResource(Res.string.forum_composer_cancel_upload))
                    }
                }
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        DiscourseComposerUploadStatus.Succeeded -> {
            ForumComposerStatusLine(
                icon = FontAwesomeIcons.Solid.CircleCheck,
                text = stringResource(Res.string.forum_composer_upload_complete),
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        DiscourseComposerUploadStatus.Failed -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    upload.failure?.composerFailureText()
                        ?: stringResource(Res.string.forum_composer_failure_unknown),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(
                    onClick = { onAction(ForumComposerAction.RetryUpload) },
                    modifier = Modifier.testTag(ForumTestTags.COMPOSER_RETRY_UPLOAD),
                ) {
                    Icon(FontAwesomeIcons.Solid.RotateRight, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.forum_composer_retry_upload))
                }
            }
        }

        DiscourseComposerUploadStatus.Cancelled -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.forum_composer_upload_cancelled),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { onAction(ForumComposerAction.RetryUpload) },
                    modifier = Modifier.testTag(ForumTestTags.COMPOSER_RETRY_UPLOAD),
                ) {
                    Text(stringResource(Res.string.forum_composer_retry_upload))
                }
            }
        }
    }
}

@Composable
private fun ForumComposerMessages(
    state: DiscourseComposerState,
    pickFailure: ForumAttachmentPickResult?,
) {
    val validationFailure = state.validationFailure
    val submitFailure = state.submitFailure
    val draftFailure = state.draftFailure
    val message =
        when {
            pickFailure == ForumAttachmentPickResult.TooLarge -> {
                stringResource(Res.string.forum_composer_failure_file_too_large)
            }

            pickFailure == ForumAttachmentPickResult.ReadFailed -> {
                stringResource(Res.string.forum_composer_failure_read_file)
            }

            validationFailure != null -> {
                validationFailure.composerValidationText()
            }

            submitFailure != null -> {
                submitFailure.composerFailureText()
            }

            draftFailure != null -> {
                draftFailure.composerFailureText()
            }

            else -> {
                null
            }
        }
    if (message != null) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    when (state.submitStatus) {
        DiscourseComposerSubmitStatus.Submitting -> {
            ForumComposerStatusLine(
                icon = null,
                text = stringResource(Res.string.forum_composer_submitting),
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        DiscourseComposerSubmitStatus.Published -> {
            ForumComposerStatusLine(
                icon = FontAwesomeIcons.Solid.CircleCheck,
                text = stringResource(Res.string.forum_composer_published),
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        DiscourseComposerSubmitStatus.PendingModeration -> {
            ForumComposerStatusLine(
                icon = FontAwesomeIcons.Solid.CircleCheck,
                text = stringResource(Res.string.forum_composer_queued),
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        DiscourseComposerSubmitStatus.Idle,
        DiscourseComposerSubmitStatus.Failed,
        -> {
            val draftText =
                when (state.draftStatus) {
                    DiscourseComposerDraftStatus.Saving -> {
                        stringResource(Res.string.forum_composer_saving)
                    }

                    DiscourseComposerDraftStatus.Saved -> {
                        stringResource(Res.string.forum_composer_saved)
                    }

                    else -> {
                        null
                    }
                }
            if (draftText != null) {
                ForumComposerStatusLine(
                    icon = null,
                    text = draftText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ForumComposerStatusLine(
    icon: ImageVector?,
    text: String,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp), tint = color)
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun ForumComposerLoading(modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(Res.string.forum_composer_loading),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ForumComposerInitializationFailure(
    failure: DiscourseForumFailureKind,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    canDiscard: Boolean,
    modifier: Modifier,
) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 340.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                failure.composerFailureText(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRetry, shape = RoundedCornerShape(5.dp)) {
                Text(stringResource(Res.string.forum_retry))
            }
            TextButton(onClick = onDiscard, enabled = canDiscard) {
                Text(stringResource(Res.string.forum_composer_discard))
            }
        }
    }
}

@Composable
private fun DiscourseComposerState.composerTitle(): String =
    when (mode) {
        DiscourseComposerMode.NewTopic -> {
            stringResource(Res.string.forum_composer_new_topic)
        }

        DiscourseComposerMode.Reply -> {
            stringResource(Res.string.forum_composer_reply)
        }

        DiscourseComposerMode.Edit -> {
            val postNumber = (target as? DiscourseComposerTarget.Edit)?.postNumber ?: 0
            stringResource(Res.string.forum_composer_edit, postNumber)
        }

        DiscourseComposerMode.Closed -> {
            stringResource(Res.string.forum_composer_close)
        }
    }

@Composable
private fun DiscourseComposerState.composerSpineColor(): Color =
    when {
        submitStatus == DiscourseComposerSubmitStatus.Failed ||
            initializationFailure != null ||
            validationFailure != null -> {
            MaterialTheme.colorScheme.error
        }

        submitStatus == DiscourseComposerSubmitStatus.Published ||
            submitStatus == DiscourseComposerSubmitStatus.PendingModeration -> {
            MaterialTheme.colorScheme.tertiary
        }

        upload.status == DiscourseComposerUploadStatus.Uploading -> {
            MaterialTheme.colorScheme.secondary
        }

        else -> {
            MaterialTheme.colorScheme.primary
        }
    }

@Composable
private fun DiscourseForumFailureKind.composerFailureText(): String =
    stringResource(
        when (this) {
            DiscourseForumFailureKind.Network -> Res.string.forum_composer_failure_network
            DiscourseForumFailureKind.Authentication -> Res.string.forum_composer_failure_authentication
            DiscourseForumFailureKind.Permission -> Res.string.forum_composer_failure_permission
            DiscourseForumFailureKind.RateLimited -> Res.string.forum_composer_failure_rate_limited
            DiscourseForumFailureKind.ChallengeRequired -> Res.string.forum_failure_challenge
            DiscourseForumFailureKind.Server -> Res.string.forum_composer_failure_server
            DiscourseForumFailureKind.InvalidResponse -> Res.string.forum_composer_failure_invalid_response
            DiscourseForumFailureKind.Http -> Res.string.forum_failure_http
        },
    )

@Composable
private fun DiscourseComposerValidationFailure.composerValidationText(): String =
    stringResource(
        when (this) {
            DiscourseComposerValidationFailure.EmptyRaw -> {
                Res.string.forum_composer_failure_empty_body
            }

            DiscourseComposerValidationFailure.MissingTitle -> {
                Res.string.forum_composer_failure_missing_title
            }

            DiscourseComposerValidationFailure.UnexpectedTitle -> {
                Res.string.forum_composer_failure_unexpected_title
            }

            DiscourseComposerValidationFailure.CategoryUnavailable -> {
                Res.string.forum_composer_failure_category
            }

            DiscourseComposerValidationFailure.TooFewTags,
            DiscourseComposerValidationFailure.RequiredTagGroupMinimum,
            DiscourseComposerValidationFailure.RequiredTagGroupMaximum,
            -> {
                Res.string.forum_composer_failure_tags
            }

            DiscourseComposerValidationFailure.EditableIdentityMismatch -> {
                Res.string.forum_composer_failure_edit_identity
            }

            DiscourseComposerValidationFailure.DraftNotFound -> {
                Res.string.forum_composer_failure_unknown
            }
        },
    )

private fun DiscourseComposerState.actionStateFor(target: DiscourseActionTarget): DiscoursePostActionPresentationState? =
    postActions.firstOrNull { it.target == target }

internal fun parseForumComposerTags(value: String): List<String>? {
    if (value.length > MAX_FORUM_COMPOSER_TAG_EDITOR_CHARS) return null
    val tags =
        value
            .split(',', '，')
            .map(String::trim)
            .filter(String::isNotEmpty)
    if (tags.size > MAX_FORUM_COMPOSER_TAGS) return null
    if (tags.any { it.length > MAX_FORUM_COMPOSER_TAG_CHARS || it.any(Char::isForumControl) }) {
        return null
    }
    return tags.distinct()
}

private fun isForumComposerTitleInputValid(value: String): Boolean =
    value.length <= MAX_FORUM_COMPOSER_TITLE_CHARS && value.none(Char::isForumUnsupportedControl)

private fun isForumComposerRawInputValid(value: String): Boolean =
    value.length <= MAX_FORUM_COMPOSER_RAW_CHARS && value.none(Char::isForumUnsupportedControl)

private fun Char.isForumUnsupportedControl(): Boolean = isForumControl() && this != '\n' && this != '\t'

private fun Char.isForumControl(): Boolean = code < 0x20 || code == 0x7f

private const val MAX_FORUM_COMPOSER_RAW_CHARS: Int = 2_000_000
private const val MAX_FORUM_COMPOSER_TITLE_CHARS: Int = 512
private const val MAX_FORUM_COMPOSER_TAGS: Int = 20
private const val MAX_FORUM_COMPOSER_TAG_CHARS: Int = 256
private const val MAX_FORUM_COMPOSER_TAG_EDITOR_CHARS: Int = 5_200
