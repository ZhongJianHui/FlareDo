package dev.dimension.flare.data.network.discourse.composer

import dev.dimension.flare.data.network.discourse.forum.DiscourseForumFailureKind

/** Composer shape exported unchanged to Compose and SwiftUI hosts. */
public enum class DiscourseComposerMode {
    Closed,
    NewTopic,
    Reply,
    Edit,
}

/** Local persistence state. Drafts are never treated as an offline delivery queue. */
public enum class DiscourseComposerDraftStatus {
    None,
    Loading,
    Clean,
    Dirty,
    Saving,
    Saved,
    Failed,
}

/** Submission state kept separate from draft persistence and upload state. */
public enum class DiscourseComposerSubmitStatus {
    Idle,
    Submitting,
    Published,
    PendingModeration,
    Failed,
}

/** Presentation-only upload phases for the single attachment picker task. */
public enum class DiscourseComposerUploadStatus {
    None,
    Ready,
    Uploading,
    Succeeded,
    Failed,
    Cancelled,
}

/** Moderation metadata deliberately excludes unpublished title and body text. */
public data class DiscourseComposerPendingModeration(
    val pendingCount: Int,
    val pendingPostId: Long? = null,
    val topicId: Long? = null,
) {
    init {
        require(pendingCount >= 0) { "Pending moderation count cannot be negative" }
        require(pendingPostId == null || pendingPostId > 0L) {
            "Pending moderation post id must be positive"
        }
        require(topicId == null || topicId > 0L) {
            "Pending moderation topic id must be positive"
        }
    }
}

/**
 * Immutable upload state suitable for Kotlin/Native export.
 *
 * [taskEpoch] identifies a whole selected file, while [attempt] identifies execute/retry calls on
 * that task. Hosts must use both values when animating progress: a late callback from a replaced
 * task or attempt is intentionally ignored by the presenter.
 */
public data class DiscourseComposerUploadState(
    val status: DiscourseComposerUploadStatus = DiscourseComposerUploadStatus.None,
    val taskEpoch: Long = 0L,
    val attempt: Long? = null,
    val bytesSent: Long = 0L,
    val totalBytes: Long? = null,
    val attachment: DiscourseUploadedAttachment? = null,
    val failure: DiscourseForumFailureKind? = null,
) {
    init {
        require(taskEpoch >= 0L) { "Upload task epoch cannot be negative" }
        require(attempt == null || attempt > 0L) { "Upload attempt must be positive" }
        require(bytesSent >= 0L) { "Uploaded bytes cannot be negative" }
        require(totalBytes == null || totalBytes >= bytesSent) {
            "Upload total cannot be below uploaded bytes"
        }
    }

    /**
     * The upload reached Linux.do, but its safe Markdown could not fit the current local draft.
     *
     * Keeping the descriptor allows Retry to re-attempt only the local insertion after the user
     * shortens the body. Ordinary network failures have no attachment and do not block submission.
     */
    public val isComposerInsertionPending: Boolean
        get() = status == DiscourseComposerUploadStatus.Failed && attachment != null
}

/**
 * List-shaped optimistic state for predictable Objective-C collection bridging.
 *
 * The domain repository uses a map for atomic keyed replacement. Presentation flattens that map and
 * keeps stable scalar fields here so Swift never needs to downcast map keys merely to render a like
 * or bookmark control.
 */
public data class DiscoursePostActionPresentationState(
    val target: DiscourseActionTarget,
    val liked: Boolean = false,
    val likeCount: Int = 0,
    val canLike: Boolean = false,
    val bookmarked: Boolean = false,
    val bookmarkId: Long? = null,
    val canBookmark: Boolean = false,
    val isLikeInFlight: Boolean = false,
    val isBookmarkInFlight: Boolean = false,
    val likeFailure: DiscourseForumFailureKind? = null,
    val bookmarkFailure: DiscourseForumFailureKind? = null,
    val likeNotAllowedReason: DiscourseActionNotAllowedReason? = null,
    val bookmarkNotAllowedReason: DiscourseActionNotAllowedReason? = null,
) {
    init {
        require(likeCount >= 0) { "Like count cannot be negative" }
        require(bookmarkId == null || bookmarkId > 0L) { "Bookmark id must be positive" }
    }
}

/**
 * One immutable composer snapshot shared by Compose and SwiftUI.
 *
 * The model uses enums and nullable data objects instead of platform callbacks or Kotlin `Result`,
 * which keeps the generated Objective-C surface predictable. A closed state never retains account
 * content. Durable text remains in [DiscourseDraftStore] and is reloaded only after an authenticated
 * open request for the same account and target.
 */
public data class DiscourseComposerState(
    val mode: DiscourseComposerMode = DiscourseComposerMode.Closed,
    val sessionGeneration: Long = -1L,
    /**
     * Monotonic identity of the currently rendered editor content.
     *
     * Hosts return this value with whole-editor updates and delayed picker results. The presenter can
     * then distinguish an input based on the snapshot immediately before an upload insertion from a
     * genuinely stale input belonging to an older editor owner.
     */
    val contentVersion: Long = 0L,
    val accountId: String? = null,
    val target: DiscourseComposerTarget? = null,
    val title: String? = null,
    val raw: String = "",
    val tags: List<String> = emptyList(),
    val constraints: DiscourseNewTopicConstraints? = null,
    val isInitializing: Boolean = false,
    val initializationFailure: DiscourseForumFailureKind? = null,
    val draftStatus: DiscourseComposerDraftStatus = DiscourseComposerDraftStatus.None,
    val draftRevision: Long? = null,
    val draftUpdatedAtEpochMillis: Long? = null,
    val draftFailure: DiscourseForumFailureKind? = null,
    val submitStatus: DiscourseComposerSubmitStatus = DiscourseComposerSubmitStatus.Idle,
    val publishedPost: DiscoursePublishedPostRef? = null,
    val pendingModeration: DiscourseComposerPendingModeration? = null,
    val submitFailure: DiscourseForumFailureKind? = null,
    val validationFailure: DiscourseComposerValidationFailure? = null,
    val upload: DiscourseComposerUploadState = DiscourseComposerUploadState(),
    val postActions: List<DiscoursePostActionPresentationState> = emptyList(),
) {
    init {
        require(contentVersion >= 0L) { "Composer content version cannot be negative" }
    }

    /** True only while an authenticated account owns an initialized editable snapshot. */
    public val canEdit: Boolean
        get() =
            mode != DiscourseComposerMode.Closed &&
                accountId != null &&
                !isInitializing &&
                initializationFailure == null &&
                submitStatus in
                setOf(
                    DiscourseComposerSubmitStatus.Idle,
                    DiscourseComposerSubmitStatus.Failed,
                )

    /** Submission is intentionally disabled until all local state is initialized and stable. */
    public val canSubmit: Boolean
        get() =
            canEdit &&
                upload.status !in
                setOf(
                    DiscourseComposerUploadStatus.Ready,
                    DiscourseComposerUploadStatus.Uploading,
                ) &&
                !upload.isComposerInsertionPending
}

/**
 * Whole-editor update sent through a conflated path.
 *
 * Keeping title, body, and tags in one value prevents independently conflated fields from producing
 * a mixed snapshot. Validation here bounds memory before the value reaches the presenter actor while
 * still allowing incomplete drafts such as an empty title or body.
 */
public data class DiscourseComposerDraftInput(
    val title: String? = null,
    val raw: String,
    val tags: List<String> = emptyList(),
) {
    init {
        validateComposerDraftStorage(title = title, raw = raw, tags = tags)
    }
}

internal fun DiscourseComposerTarget.toPresentationMode(): DiscourseComposerMode =
    when (this) {
        is DiscourseComposerTarget.NewTopic -> DiscourseComposerMode.NewTopic
        is DiscourseComposerTarget.Reply -> DiscourseComposerMode.Reply
        is DiscourseComposerTarget.Edit -> DiscourseComposerMode.Edit
    }
