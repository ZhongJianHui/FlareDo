package dev.dimension.flare.data.network.discourse.composer

import dev.dimension.flare.data.network.discourse.DiscourseDataSource
import dev.dimension.flare.data.network.discourse.DiscourseUploadRequest
import dev.dimension.flare.data.network.discourse.error.DiscourseAuthenticationException
import dev.dimension.flare.data.network.discourse.error.DiscoursePostEnqueuedException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationPhase
import dev.dimension.flare.data.network.discourse.model.DiscourseCreatePostRequest
import dev.dimension.flare.data.network.discourse.model.DiscourseEditablePost
import dev.dimension.flare.data.network.discourse.model.DiscoursePostMutationResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseRequiredTagGroup
import dev.dimension.flare.data.network.discourse.model.DiscourseSiteResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTagsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUpdatePostRequest
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** One category-required tag group after matching `/site.json` with `/tags.json` metadata. */
public data class DiscourseRequiredTagGroupConstraint(
    val name: String,
    val minimumCount: Int,
    val maximumCount: Int?,
    /** Canonical names, text values, and slugs accepted for the matched group. */
    val acceptedTags: Set<String>,
    /** False when the site named a group but did not expose its membership to this account. */
    val membershipAvailable: Boolean,
) {
    init {
        require(name.isNotBlank()) { "Required tag group name must not be blank" }
        require(minimumCount >= 0) { "Required tag group minimum cannot be negative" }
        require(maximumCount == null || maximumCount >= minimumCount) {
            "Required tag group maximum cannot be below its minimum"
        }
    }
}

/** Server-advertised validation rules for creating a topic in one category. */
public data class DiscourseNewTopicConstraints(
    val categoryId: Long?,
    val minimumRequiredTags: Int,
    val requiredTagGroups: List<DiscourseRequiredTagGroupConstraint>,
) {
    init {
        require(categoryId == null || categoryId > 0L) { "Constraint category id must be positive" }
        require(minimumRequiredTags >= 0) { "Minimum required tags cannot be negative" }
    }
}

/** Authenticated Markdown source for an edit composer. */
public data class DiscourseEditablePostSource(
    val postId: Long,
    val topicId: Long,
    val postNumber: Int,
    val raw: String,
) {
    init {
        require(postId > 0L) { "Editable post id must be positive" }
        require(topicId > 0L) { "Editable topic id must be positive" }
        require(postNumber > 0) { "Editable post number must be positive" }
        validateComposerDraftStorage(title = null, raw = raw, tags = emptyList())
    }
}

/** Durable identity returned only after Linux.do confirms publication. */
public data class DiscoursePublishedPostRef(
    val postId: Long,
    val topicId: Long,
    val postNumber: Int,
) {
    init {
        require(postId > 0L) { "Published post id must be positive" }
        require(topicId > 0L) { "Published topic id must be positive" }
        require(postNumber > 0) { "Published post number must be positive" }
    }
}

/**
 * Submission result distinguishing a durable post from content accepted for moderator review.
 *
 * Pending content deliberately has no invented post identity and leaves its local draft intact.
 */
public sealed interface DiscoursePostSubmissionOutcome {
    public data class Published(
        val post: DiscoursePublishedPostRef,
    ) : DiscoursePostSubmissionOutcome

    public data class PendingModeration(
        val pendingCount: Int,
        val pendingPostId: Long? = null,
        val topicId: Long? = null,
    ) : DiscoursePostSubmissionOutcome {
        init {
            require(pendingCount >= 0) { "Pending moderation count cannot be negative" }
            require(pendingPostId == null || pendingPostId > 0L) { "Pending post id must be positive" }
            require(topicId == null || topicId > 0L) { "Pending topic id must be positive" }
        }
    }
}

/** Stable, content-free reason for a local composer validation failure. */
public enum class DiscourseComposerValidationFailure {
    DraftNotFound,
    EmptyRaw,
    MissingTitle,
    UnexpectedTitle,
    CategoryUnavailable,
    TooFewTags,
    RequiredTagGroupMinimum,
    RequiredTagGroupMaximum,
    EditableIdentityMismatch,
}

/** Local validation failure that never embeds unpublished title, body, or tag text. */
public class DiscourseComposerValidationException(
    public val failure: DiscourseComposerValidationFailure,
) : IllegalArgumentException("Discourse composer validation failed (${failure.name})")

/** Authenticated post creation, reply, edit, draft, and upload operations. */
public interface DiscourseComposerRepository {
    public suspend fun loadNewTopicConstraints(
        accountId: String,
        categoryId: Long?,
    ): DiscourseNewTopicConstraints

    /**
     * Fetches authoritative `raw` Markdown and verifies every identity in [target].
     * Sanitized `cooked` UI blocks are never reverse-converted into editable source.
     */
    public suspend fun loadEditableSource(
        accountId: String,
        target: DiscourseComposerTarget.Edit,
    ): DiscourseEditablePostSource

    /** Submits one currently stored revision; failed and queued requests remain ordinary drafts. */
    public suspend fun submit(
        accountId: String,
        target: DiscourseComposerTarget,
    ): DiscoursePostSubmissionOutcome

    /** Creates an idle task whose execution remains a structured child of its caller. */
    public fun createUploadTask(
        accountId: String,
        request: DiscourseUploadRequest,
    ): DiscourseUploadTask
}

/** Default implementation shared by all five platform hosts. */
public class DefaultDiscourseComposerRepository internal constructor(
    private val remote: DiscourseComposerRemoteDataSource,
    private val draftStore: DiscourseDraftStore,
    private val sessionManager: DiscourseSessionManager,
) : DiscourseComposerRepository {
    public constructor(
        dataSource: DiscourseDataSource,
        draftStore: DiscourseDraftStore,
        sessionManager: DiscourseSessionManager,
    ) : this(
        remote = DefaultDiscourseComposerRemoteDataSource(dataSource),
        draftStore = draftStore,
        sessionManager = sessionManager,
    )

    override suspend fun loadNewTopicConstraints(
        accountId: String,
        categoryId: Long?,
    ): DiscourseNewTopicConstraints {
        requireValidComposerAccountId(accountId)
        require(categoryId == null || categoryId > 0L) { "Composer category id must be positive" }
        return sessionManager.runForAuthenticatedAccount(accountId) {
            loadConstraints(categoryId)
        }
    }

    override suspend fun loadEditableSource(
        accountId: String,
        target: DiscourseComposerTarget.Edit,
    ): DiscourseEditablePostSource {
        requireValidComposerAccountId(accountId)
        return sessionManager.runForAuthenticatedAccount(accountId) {
            val source = remote.editablePost(target.postId)
            if (
                source.id != target.postId ||
                source.topicId != target.topicId ||
                source.postNumber != target.postNumber
            ) {
                throw DiscourseComposerValidationException(
                    DiscourseComposerValidationFailure.EditableIdentityMismatch,
                )
            }
            DiscourseEditablePostSource(
                postId = source.id,
                topicId = source.topicId,
                postNumber = source.postNumber,
                raw = source.raw,
            )
        }
    }

    override suspend fun submit(
        accountId: String,
        target: DiscourseComposerTarget,
    ): DiscoursePostSubmissionOutcome {
        requireValidComposerAccountId(accountId)
        return sessionManager.runForAuthenticatedAccount(accountId) {
            val draft =
                draftStore.load(accountId, target)
                    ?: throw DiscourseComposerValidationException(
                        DiscourseComposerValidationFailure.DraftNotFound,
                    )
            val prepared = prepareSubmission(draft)
            val outcome =
                try {
                    when (target) {
                        is DiscourseComposerTarget.NewTopic -> {
                            remote
                                .createPost(
                                    DiscourseCreatePostRequest(
                                        raw = prepared.raw,
                                        title = checkNotNull(prepared.title),
                                        category = target.categoryId,
                                        archetype = "regular",
                                        tags = prepared.tags,
                                    ),
                                ).toOutcome(target)
                        }

                        is DiscourseComposerTarget.Reply -> {
                            remote
                                .createPost(
                                    DiscourseCreatePostRequest(
                                        raw = prepared.raw,
                                        topicId = target.topicId,
                                        replyToPostNumber = target.replyToPostNumber,
                                    ),
                                ).toOutcome(target)
                        }

                        is DiscourseComposerTarget.Edit -> {
                            remote
                                .updatePost(
                                    postId = target.postId,
                                    request = DiscourseUpdatePostRequest(raw = prepared.raw),
                                ).toOutcome(target)
                        }
                    }
                } catch (enqueued: DiscoursePostEnqueuedException) {
                    enqueued.toOutcome(target)
                }

            if (outcome is DiscoursePostSubmissionOutcome.Published) {
                // Publication already happened remotely. This compare-and-delete is critical cleanup:
                // it must finish even when logout concurrently cancels the request lease, while the
                // revision CAS preserves any newer text saved during the network request. A local
                // storage failure cannot rewrite the already-published remote fact into a failed
                // submission, which could invite a duplicate retry from the UI.
                try {
                    withContext(NonCancellable) {
                        draftStore.deleteIfRevision(accountId, target, draft.revision)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The published outcome remains authoritative. The terminal presenter state
                    // prevents a second submission; a retained draft is safer than deleting a
                    // concurrently saved revision without a successful CAS.
                }
            }
            outcome
        }
    }

    override fun createUploadTask(
        accountId: String,
        request: DiscourseUploadRequest,
    ): DiscourseUploadTask {
        requireValidComposerAccountId(accountId)
        return DefaultDiscourseUploadTask(
            accountId = accountId,
            request = request,
            remote = remote,
            sessionManager = sessionManager,
        )
    }

    private suspend fun prepareSubmission(draft: DiscourseComposerDraft): PreparedSubmission {
        if (draft.raw.isBlank()) {
            throw DiscourseComposerValidationException(DiscourseComposerValidationFailure.EmptyRaw)
        }
        val tags =
            draft.tags
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
        return when (val target = draft.target) {
            is DiscourseComposerTarget.NewTopic -> {
                val title = draft.title?.trim()
                if (title.isNullOrEmpty()) {
                    throw DiscourseComposerValidationException(
                        DiscourseComposerValidationFailure.MissingTitle,
                    )
                }
                val constraints = loadConstraints(target.categoryId)
                validateTags(tags, constraints)
                PreparedSubmission(raw = draft.raw, title = title, tags = tags)
            }

            is DiscourseComposerTarget.Reply, is DiscourseComposerTarget.Edit -> {
                if (!draft.title.isNullOrBlank()) {
                    throw DiscourseComposerValidationException(
                        DiscourseComposerValidationFailure.UnexpectedTitle,
                    )
                }
                PreparedSubmission(raw = draft.raw, title = null, tags = emptyList())
            }
        }
    }

    private suspend fun loadConstraints(categoryId: Long?): DiscourseNewTopicConstraints {
        val site = remote.site()
        val category =
            categoryId?.let { requestedId ->
                site.categories.firstOrNull { it.id == requestedId }
                    ?: throw DiscourseComposerValidationException(
                        DiscourseComposerValidationFailure.CategoryUnavailable,
                    )
            }
        val requiredGroups = category?.requiredTagGroups.orEmpty()
        val tags = if (requiredGroups.isEmpty()) null else remote.tags()
        return DiscourseNewTopicConstraints(
            categoryId = categoryId,
            minimumRequiredTags = category?.minimumRequiredTags?.coerceAtLeast(0) ?: 0,
            requiredTagGroups = requiredGroups.map { it.toConstraint(tags) },
        )
    }
}

internal interface DiscourseComposerRemoteDataSource : DiscourseUploadProgressSource {
    suspend fun site(): DiscourseSiteResponse

    suspend fun tags(): DiscourseTagsResponse

    suspend fun editablePost(postId: Long): DiscourseEditablePost

    suspend fun createPost(request: DiscourseCreatePostRequest): DiscoursePostMutationResponse

    suspend fun updatePost(
        postId: Long,
        request: DiscourseUpdatePostRequest,
    ): DiscoursePostMutationResponse
}

private class DefaultDiscourseComposerRemoteDataSource(
    private val dataSource: DiscourseDataSource,
) : DiscourseComposerRemoteDataSource {
    override suspend fun site(): DiscourseSiteResponse = dataSource.site()

    override suspend fun tags(): DiscourseTagsResponse = dataSource.tags()

    override suspend fun editablePost(postId: Long): DiscourseEditablePost = dataSource.editablePost(postId)

    override suspend fun createPost(request: DiscourseCreatePostRequest): DiscoursePostMutationResponse = dataSource.api.createPost(request)

    override suspend fun updatePost(
        postId: Long,
        request: DiscourseUpdatePostRequest,
    ): DiscoursePostMutationResponse = dataSource.api.updatePost(postId, request)

    override suspend fun upload(
        request: DiscourseUploadRequest,
        reportProgress: suspend (bytesSent: Long, totalBytes: Long?) -> Unit,
    ) = uploadWithProgress(dataSource.api, request, reportProgress)
}

internal suspend fun <T> DiscourseSessionManager.runForAuthenticatedAccount(
    expectedAccountId: String,
    block: suspend DiscourseSessionState.Authenticated.() -> T,
): T =
    runForCurrentSession {
        val authenticated =
            this as? DiscourseSessionState.Authenticated
                ?: throw DiscourseAuthenticationException()
        if (authenticated.accountId != expectedAccountId) {
            throw DiscourseAuthenticationException()
        }
        authenticated.block()
    }

private fun DiscourseRequiredTagGroup.toConstraint(tagsResponse: DiscourseTagsResponse?): DiscourseRequiredTagGroupConstraint {
    val safeName = name?.trim().orEmpty()
    if (safeName.isEmpty() || safeName.length > MAX_COMPOSER_TAG_CHARS) {
        throw protocolFailure()
    }
    val maximum = maximumCount?.coerceAtLeast(0)
    val minimum = minimumCount.coerceAtLeast(0)
    if (maximum != null && maximum < minimum) throw protocolFailure()
    val matchingGroup = tagsResponse?.extras?.tagGroups?.firstOrNull { it.name == safeName }
    val acceptedTags =
        matchingGroup
            ?.tags
            .orEmpty()
            .flatMap { tag -> listOf(tag.name, tag.text, tag.slug) }
            .filter { it.isNotBlank() }
            .toSet()
    return DiscourseRequiredTagGroupConstraint(
        name = safeName,
        minimumCount = minimum,
        maximumCount = maximum,
        acceptedTags = acceptedTags,
        membershipAvailable = matchingGroup != null,
    )
}

private fun validateTags(
    tags: List<String>,
    constraints: DiscourseNewTopicConstraints,
) {
    if (tags.size < constraints.minimumRequiredTags) {
        throw DiscourseComposerValidationException(DiscourseComposerValidationFailure.TooFewTags)
    }
    constraints.requiredTagGroups
        .filter(DiscourseRequiredTagGroupConstraint::membershipAvailable)
        .forEach { group ->
            val count = tags.count(group.acceptedTags::contains)
            if (count < group.minimumCount) {
                throw DiscourseComposerValidationException(
                    DiscourseComposerValidationFailure.RequiredTagGroupMinimum,
                )
            }
            if (group.maximumCount != null && count > group.maximumCount) {
                throw DiscourseComposerValidationException(
                    DiscourseComposerValidationFailure.RequiredTagGroupMaximum,
                )
            }
        }
}

private fun DiscoursePostMutationResponse.toOutcome(target: DiscourseComposerTarget): DiscoursePostSubmissionOutcome {
    if (isEnqueued) {
        val safePendingCount = pendingCount ?: 0
        val safePendingPostId = pendingPost?.id
        val safeTopicId = topicId ?: pendingPost?.topicId ?: target.topicIdOrNull()
        // A queued review ID is not a durable post ID and its optional post number is not an
        // authoritative edit-target identity. The request route already identifies an edit; only
        // the stable topic identity can be compared across direct and queued response variants.
        val pendingIdentityMatches =
            when (target) {
                is DiscourseComposerTarget.NewTopic -> {
                    true
                }

                is DiscourseComposerTarget.Reply,
                is DiscourseComposerTarget.Edit,
                -> {
                    safeTopicId == target.topicIdOrNull()
                }
            }
        if (
            safePendingCount < 0 ||
            (pendingPost?.topicId != null && topicId != null && pendingPost.topicId != topicId) ||
            (safePendingPostId != null && safePendingPostId <= 0L) ||
            (pendingPost?.postNumber != null && pendingPost.postNumber <= 0) ||
            (safeTopicId != null && safeTopicId <= 0L) ||
            !pendingIdentityMatches
        ) {
            throw protocolFailure()
        }
        return DiscoursePostSubmissionOutcome.PendingModeration(
            pendingCount = safePendingCount,
            pendingPostId = safePendingPostId,
            topicId = safeTopicId,
        )
    }
    val published = post ?: throw protocolFailure()
    val identityMatches =
        when (target) {
            is DiscourseComposerTarget.NewTopic -> {
                published.topicId > 0L
            }

            is DiscourseComposerTarget.Reply -> {
                published.topicId == target.topicId
            }

            is DiscourseComposerTarget.Edit -> {
                published.id == target.postId &&
                    published.topicId == target.topicId &&
                    published.postNumber == target.postNumber
            }
        }
    if (published.id <= 0L || published.postNumber <= 0 || !identityMatches) throw protocolFailure()
    return DiscoursePostSubmissionOutcome.Published(
        DiscoursePublishedPostRef(
            postId = published.id,
            topicId = published.topicId,
            postNumber = published.postNumber,
        ),
    )
}

/**
 * Applies the same stable topic-identity check after the API converts an enqueued wire response to
 * an exception. Reply and edit routes already identify an existing topic, so a different numeric
 * topic in the response is a protocol failure rather than a successful moderation outcome.
 */
private fun DiscoursePostEnqueuedException.toOutcome(target: DiscourseComposerTarget): DiscoursePostSubmissionOutcome.PendingModeration {
    val expectedTopicId = target.topicIdOrNull()
    if (expectedTopicId != null && topicId != null && topicId != expectedTopicId) {
        throw protocolFailure()
    }
    return DiscoursePostSubmissionOutcome.PendingModeration(
        pendingCount = pendingCount,
        pendingPostId = pendingPostId,
        topicId = topicId ?: expectedTopicId,
    )
}

private fun DiscourseComposerTarget.topicIdOrNull(): Long? =
    when (this) {
        is DiscourseComposerTarget.NewTopic -> null
        is DiscourseComposerTarget.Reply -> topicId
        is DiscourseComposerTarget.Edit -> topicId
    }

private data class PreparedSubmission(
    val raw: String,
    val title: String?,
    val tags: List<String>,
)

private fun protocolFailure(): DiscourseSerializationException =
    DiscourseSerializationException(DiscourseSerializationPhase.ResponseDecoding)
