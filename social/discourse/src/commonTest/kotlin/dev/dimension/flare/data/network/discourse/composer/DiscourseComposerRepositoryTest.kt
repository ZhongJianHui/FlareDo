package dev.dimension.flare.data.network.discourse.composer

import dev.dimension.flare.data.network.discourse.DiscourseUploadRequest
import dev.dimension.flare.data.network.discourse.error.DiscourseAuthenticationException
import dev.dimension.flare.data.network.discourse.error.DiscoursePostEnqueuedException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationPhase
import dev.dimension.flare.data.network.discourse.model.DiscourseCategory
import dev.dimension.flare.data.network.discourse.model.DiscourseCreatePostRequest
import dev.dimension.flare.data.network.discourse.model.DiscourseEditablePost
import dev.dimension.flare.data.network.discourse.model.DiscoursePost
import dev.dimension.flare.data.network.discourse.model.DiscoursePostMutationResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseRequiredTagGroup
import dev.dimension.flare.data.network.discourse.model.DiscourseSiteResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTag
import dev.dimension.flare.data.network.discourse.model.DiscourseTagExtras
import dev.dimension.flare.data.network.discourse.model.DiscourseTagGroup
import dev.dimension.flare.data.network.discourse.model.DiscourseTagsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUpdatePostRequest
import dev.dimension.flare.data.network.discourse.model.DiscourseUploadResponse
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class DiscourseComposerRepositoryTest {
    @Test
    fun publishedSubmissionDeletesOnlyTheCapturedDraftRevision() =
        runTest {
            val sessionManager = authenticatedSession()
            val store = MemoryDiscourseDraftStore()
            val remote = FakeComposerRemote()
            val requestStarted = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            remote.createBlock = { request ->
                requestStarted.complete(Unit)
                releaseResponse.await()
                publishedResponse(topicId = 42L, raw = request.raw)
            }
            val repository = DefaultDiscourseComposerRepository(remote, store, sessionManager)
            val target = DiscourseComposerTarget.Reply(topicId = 42L)
            val first = store.save(ACCOUNT_ID, target, raw = "First", updatedAtEpochMillis = 1L)

            val submitting = async { repository.submit(ACCOUNT_ID, target) }
            requestStarted.await()
            val newer = store.save(ACCOUNT_ID, target, raw = "Newer", updatedAtEpochMillis = 2L)
            releaseResponse.complete(Unit)

            val published = assertIs<DiscoursePostSubmissionOutcome.Published>(submitting.await())
            assertEquals(501L, published.post.postId)
            assertEquals(1L, first.revision)
            assertEquals(2L, newer.revision)
            assertEquals(newer, store.load(ACCOUNT_ID, target))
        }

    @Test
    fun moderationQueueKeepsDraftAndDoesNotInventPublishedIdentity() =
        runTest {
            val sessionManager = authenticatedSession()
            val store = MemoryDiscourseDraftStore()
            val remote = FakeComposerRemote()
            remote.createBlock = {
                DiscoursePostMutationResponse(
                    action = "enqueued",
                    pendingCount = 3,
                    topicId = 42L,
                )
            }
            val repository = DefaultDiscourseComposerRepository(remote, store, sessionManager)
            val target = DiscourseComposerTarget.Reply(topicId = 42L, replyToPostNumber = 2)
            val draft = store.save(ACCOUNT_ID, target, raw = "Needs review", updatedAtEpochMillis = 1L)

            val outcome = assertIs<DiscoursePostSubmissionOutcome.PendingModeration>(repository.submit(ACCOUNT_ID, target))

            assertEquals(3, outcome.pendingCount)
            assertEquals(42L, outcome.topicId)
            assertEquals(draft, store.load(ACCOUNT_ID, target))
        }

    @Test
    fun enqueuedReplyExceptionRejectsMismatchedExistingTopicAndKeepsDraft() =
        runTest {
            val sessionManager = authenticatedSession()
            val store = MemoryDiscourseDraftStore()
            val remote =
                FakeComposerRemote().apply {
                    createBlock = {
                        throw DiscoursePostEnqueuedException(
                            pendingCount = 1,
                            pendingPostId = 701L,
                            topicId = 99L,
                        )
                    }
                }
            val repository = DefaultDiscourseComposerRepository(remote, store, sessionManager)
            val target = DiscourseComposerTarget.Reply(topicId = 42L)
            val draft = store.save(ACCOUNT_ID, target, raw = "Queued reply", updatedAtEpochMillis = 1L)

            val failure =
                assertFailsWith<DiscourseSerializationException> {
                    repository.submit(ACCOUNT_ID, target)
                }

            assertEquals(DiscourseSerializationPhase.ResponseDecoding, failure.phase)
            assertEquals(draft, store.load(ACCOUNT_ID, target))
        }

    @Test
    fun enqueuedEditExceptionRejectsMismatchedExistingTopicAndKeepsDraft() =
        runTest {
            val sessionManager = authenticatedSession()
            val store = MemoryDiscourseDraftStore()
            val remote =
                FakeComposerRemote().apply {
                    updateBlock = { _, _ ->
                        throw DiscoursePostEnqueuedException(
                            pendingCount = 1,
                            pendingPostId = 702L,
                            topicId = 99L,
                        )
                    }
                }
            val repository = DefaultDiscourseComposerRepository(remote, store, sessionManager)
            val target = DiscourseComposerTarget.Edit(topicId = 42L, postId = 501L, postNumber = 3)
            val draft = store.save(ACCOUNT_ID, target, raw = "Queued edit", updatedAtEpochMillis = 1L)

            val failure =
                assertFailsWith<DiscourseSerializationException> {
                    repository.submit(ACCOUNT_ID, target)
                }

            assertEquals(DiscourseSerializationPhase.ResponseDecoding, failure.phase)
            assertEquals(draft, store.load(ACCOUNT_ID, target))
        }

    @Test
    fun newTopicSubmissionEnforcesAdvertisedCategoryAndTagGroupRules() =
        runTest {
            val sessionManager = authenticatedSession()
            val store = MemoryDiscourseDraftStore()
            val remote =
                FakeComposerRemote().apply {
                    siteResponse =
                        DiscourseSiteResponse(
                            categories =
                                listOf(
                                    DiscourseCategory(
                                        id = 8L,
                                        name = "Development",
                                        slug = "development",
                                        minimumRequiredTags = 2,
                                        requiredTagGroups =
                                            listOf(
                                                DiscourseRequiredTagGroup(
                                                    name = "language",
                                                    minimumCount = 1,
                                                    maximumCount = 1,
                                                ),
                                            ),
                                    ),
                                ),
                        )
                    tagsResponse =
                        DiscourseTagsResponse(
                            extras =
                                DiscourseTagExtras(
                                    tagGroups =
                                        listOf(
                                            DiscourseTagGroup(
                                                id = 1L,
                                                name = "language",
                                                tags =
                                                    listOf(
                                                        DiscourseTag(id = 1L, text = "kotlin"),
                                                        DiscourseTag(id = 2L, text = "java"),
                                                    ),
                                            ),
                                        ),
                                ),
                        )
                }
            val repository = DefaultDiscourseComposerRepository(remote, store, sessionManager)
            val target = DiscourseComposerTarget.NewTopic(categoryId = 8L)
            store.save(
                accountId = ACCOUNT_ID,
                target = target,
                title = "Two language tags",
                raw = "Body",
                tags = listOf("kotlin", "java"),
                updatedAtEpochMillis = 1L,
            )

            val rejected =
                assertFailsWith<DiscourseComposerValidationException> {
                    repository.submit(ACCOUNT_ID, target)
                }

            assertEquals(DiscourseComposerValidationFailure.RequiredTagGroupMaximum, rejected.failure)
            assertEquals(0, remote.createCalls)

            store.save(
                accountId = ACCOUNT_ID,
                target = target,
                title = "Valid tags",
                raw = "Body",
                tags = listOf("kotlin", "tooling"),
                updatedAtEpochMillis = 2L,
            )
            assertIs<DiscoursePostSubmissionOutcome.Published>(repository.submit(ACCOUNT_ID, target))
            assertEquals(listOf("kotlin", "tooling"), remote.lastCreateRequest?.tags)
            assertNull(store.load(ACCOUNT_ID, target))
        }

    @Test
    fun uncategorizedNewTopicDefersSitePolicyToTheServer() =
        runTest {
            val sessionManager = authenticatedSession()
            val store = MemoryDiscourseDraftStore()
            val remote = FakeComposerRemote()
            val repository = DefaultDiscourseComposerRepository(remote, store, sessionManager)
            val target = DiscourseComposerTarget.NewTopic(categoryId = null)
            store.save(
                accountId = ACCOUNT_ID,
                target = target,
                title = "Uncategorized fixture topic",
                raw = "Server decides whether uncategorized topics are allowed.",
                updatedAtEpochMillis = 1L,
            )

            assertIs<DiscoursePostSubmissionOutcome.Published>(repository.submit(ACCOUNT_ID, target))
            assertNull(remote.lastCreateRequest?.category)
            assertEquals(1, remote.createCalls)
        }

    @Test
    fun editLoadsAuthoritativeRawAndRejectsAnyIdentityMismatch() =
        runTest {
            val sessionManager = authenticatedSession()
            val remote = FakeComposerRemote()
            val repository =
                DefaultDiscourseComposerRepository(
                    remote = remote,
                    draftStore = MemoryDiscourseDraftStore(),
                    sessionManager = sessionManager,
                )
            val target = DiscourseComposerTarget.Edit(topicId = 42L, postId = 501L, postNumber = 3)
            remote.editable =
                DiscourseEditablePost(
                    id = 501L,
                    topicId = 42L,
                    postNumber = 3,
                    raw = "Authoritative **Markdown**",
                )

            val source = repository.loadEditableSource(ACCOUNT_ID, target)

            assertEquals("Authoritative **Markdown**", source.raw)
            remote.editable = remote.editable.copy(topicId = 99L)
            val mismatch =
                assertFailsWith<DiscourseComposerValidationException> {
                    repository.loadEditableSource(ACCOUNT_ID, target)
                }
            assertEquals(DiscourseComposerValidationFailure.EditableIdentityMismatch, mismatch.failure)
        }

    @Test
    fun accountMismatchFailsBeforeAnyWriteRequest() =
        runTest {
            val sessionManager = authenticatedSession()
            val store = MemoryDiscourseDraftStore()
            val remote = FakeComposerRemote()
            val repository = DefaultDiscourseComposerRepository(remote, store, sessionManager)
            val target = DiscourseComposerTarget.Reply(topicId = 42L)
            store.save(OTHER_ACCOUNT_ID, target, raw = "Wrong account", updatedAtEpochMillis = 1L)

            assertFailsWith<DiscourseAuthenticationException> {
                repository.submit(OTHER_ACCOUNT_ID, target)
            }
            assertEquals(0, remote.createCalls)
            assertNotNull(store.load(OTHER_ACCOUNT_ID, target))
        }

    @Test
    fun publishedDraftCleanupCompletesInNonCancellableContext() =
        runTest {
            supervisorScope {
                val sessionManager = authenticatedSession()
                val store = DelayingDeleteDraftStore()
                val remote = FakeComposerRemote()
                val repository = DefaultDiscourseComposerRepository(remote, store, sessionManager)
                val target = DiscourseComposerTarget.Reply(topicId = 42L)
                store.save(
                    accountId = ACCOUNT_ID,
                    target = target,
                    raw = "Published before caller cancellation",
                    updatedAtEpochMillis = 1L,
                )
                val submission = async { repository.submit(ACCOUNT_ID, target) }
                store.deleteEntered.await()

                submission.cancel()
                store.allowDelete.complete(Unit)

                assertFailsWith<CancellationException> { submission.await() }
                assertNull(store.load(ACCOUNT_ID, target))
            }
        }

    @Test
    fun localCleanupFailureCannotRewriteAnAlreadyPublishedRemoteOutcome() =
        runTest {
            val sessionManager = authenticatedSession()
            val store = FailingDeleteDraftStore()
            val remote = FakeComposerRemote()
            val repository = DefaultDiscourseComposerRepository(remote, store, sessionManager)
            val target = DiscourseComposerTarget.Reply(topicId = 42L)
            val draft =
                store.save(
                    accountId = ACCOUNT_ID,
                    target = target,
                    raw = "Published despite local cleanup failure",
                    updatedAtEpochMillis = 1L,
                )

            val outcome = assertIs<DiscoursePostSubmissionOutcome.Published>(repository.submit(ACCOUNT_ID, target))

            assertEquals(501L, outcome.post.postId)
            assertEquals(1, remote.createCalls)
            assertEquals(draft, store.load(ACCOUNT_ID, target))
        }

    private suspend fun authenticatedSession(): DiscourseSessionManager =
        DiscourseSessionManager().also {
            it.startAuthenticatedSession(accountId = ACCOUNT_ID)
        }

    private companion object {
        const val ACCOUNT_ID: String = "42"
        const val OTHER_ACCOUNT_ID: String = "84"
    }
}

private class DelayingDeleteDraftStore(
    private val delegate: DiscourseDraftStore = MemoryDiscourseDraftStore(),
) : DiscourseDraftStore by delegate {
    val deleteEntered = CompletableDeferred<Unit>()
    val allowDelete = CompletableDeferred<Unit>()

    override suspend fun deleteIfRevision(
        accountId: String,
        target: DiscourseComposerTarget,
        expectedRevision: Long,
    ): Boolean {
        deleteEntered.complete(Unit)
        allowDelete.await()
        return delegate.deleteIfRevision(accountId, target, expectedRevision)
    }
}

private class FailingDeleteDraftStore(
    private val delegate: DiscourseDraftStore = MemoryDiscourseDraftStore(),
) : DiscourseDraftStore by delegate {
    override suspend fun deleteIfRevision(
        accountId: String,
        target: DiscourseComposerTarget,
        expectedRevision: Long,
    ): Boolean = throw IllegalStateException("Fixture local cleanup failure")
}

private class FakeComposerRemote : DiscourseComposerRemoteDataSource {
    var siteResponse: DiscourseSiteResponse = DiscourseSiteResponse()
    var tagsResponse: DiscourseTagsResponse = DiscourseTagsResponse()
    var editable: DiscourseEditablePost =
        DiscourseEditablePost(id = 501L, topicId = 42L, postNumber = 3, raw = "raw")
    var createCalls: Int = 0
    var lastCreateRequest: DiscourseCreatePostRequest? = null
    var createBlock: suspend (DiscourseCreatePostRequest) -> DiscoursePostMutationResponse = {
        publishedResponse(topicId = it.topicId ?: 777L, raw = it.raw)
    }
    var updateBlock: suspend (Long, DiscourseUpdatePostRequest) -> DiscoursePostMutationResponse = { postId, request ->
        publishedResponse(postId = postId, topicId = 42L, postNumber = 3, raw = request.raw)
    }

    override suspend fun site(): DiscourseSiteResponse = siteResponse

    override suspend fun tags(): DiscourseTagsResponse = tagsResponse

    override suspend fun editablePost(postId: Long): DiscourseEditablePost = editable

    override suspend fun createPost(request: DiscourseCreatePostRequest): DiscoursePostMutationResponse {
        createCalls += 1
        lastCreateRequest = request
        return createBlock(request)
    }

    override suspend fun updatePost(
        postId: Long,
        request: DiscourseUpdatePostRequest,
    ): DiscoursePostMutationResponse = updateBlock(postId, request)

    override suspend fun upload(
        request: DiscourseUploadRequest,
        reportProgress: suspend (bytesSent: Long, totalBytes: Long?) -> Unit,
    ): DiscourseUploadResponse =
        DiscourseUploadResponse(
            id = 1L,
            shortUrl = "upload://test",
            originalFilename = request.fileName,
        )
}

private fun publishedResponse(
    postId: Long = 501L,
    topicId: Long,
    postNumber: Int = 3,
    raw: String,
): DiscoursePostMutationResponse =
    DiscoursePostMutationResponse(
        post =
            DiscoursePost(
                id = postId,
                topicId = topicId,
                postNumber = postNumber,
                username = "writer",
                raw = raw,
            ),
        topicId = topicId,
    )
