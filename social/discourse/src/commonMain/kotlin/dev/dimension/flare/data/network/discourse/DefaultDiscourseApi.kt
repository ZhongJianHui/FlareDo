package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.error.DiscourseCsrfException
import dev.dimension.flare.data.network.discourse.error.DiscourseException
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkException
import dev.dimension.flare.data.network.discourse.error.DiscourseNetworkFailureKind
import dev.dimension.flare.data.network.discourse.error.DiscoursePostEnqueuedException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationPhase
import dev.dimension.flare.data.network.discourse.model.DiscourseActionResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseBookmarkResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseCategoryListResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseCreateBookmarkRequest
import dev.dimension.flare.data.network.discourse.model.DiscourseCreatePostRequest
import dev.dimension.flare.data.network.discourse.model.DiscourseCurrentSessionResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseNotificationResponse
import dev.dimension.flare.data.network.discourse.model.DiscoursePostActionRequest
import dev.dimension.flare.data.network.discourse.model.DiscoursePostMutationResponse
import dev.dimension.flare.data.network.discourse.model.DiscoursePostStream
import dev.dimension.flare.data.network.discourse.model.DiscourseSearchResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseSiteResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTagsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicDetail
import dev.dimension.flare.data.network.discourse.model.DiscourseTopicListResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUpdatePostRequest
import dev.dimension.flare.data.network.discourse.model.DiscourseUploadResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserActionsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserBookmarkListResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserSummaryResponse
import dev.dimension.flare.data.network.discourse.paging.DISCOURSE_TOPIC_STREAM_BATCH_SIZE
import dev.dimension.flare.data.network.discourse.paging.DiscourseListPage
import dev.dimension.flare.data.network.discourse.paging.DiscourseNotificationOffset
import dev.dimension.flare.data.network.discourse.paging.DiscourseSearchPage
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.encodeURLPathPart
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

private const val MAX_SEARCH_QUERY_CHARS: Int = 2_000
private const val MAX_USER_ACTION_OFFSET: Int = Int.MAX_VALUE

/** Production implementation that adds session and protocol guarantees around generated routes. */
internal class DefaultDiscourseApi(
    private val wire: DiscourseWireApi,
    private val sessionManager: DiscourseSessionManager,
) : DiscourseApi {
    override suspend fun site(): DiscourseSiteResponse = read { wire.site() }

    override suspend fun categories(): DiscourseCategoryListResponse = read { wire.categories() }

    override suspend fun tags(): DiscourseTagsResponse = read { wire.tags() }

    override suspend fun topics(request: DiscourseTopicListRequest): DiscourseTopicListResponse =
        read { wire.topics(request.toAbsoluteUrl()) }

    override suspend fun topic(
        topicId: Long,
        trackVisit: Boolean,
    ): DiscourseTopicDetail {
        requirePositiveId(topicId, "Topic id")
        return read {
            wire.topic(
                topicId = topicId,
                trackVisit = true.takeIf { trackVisit },
                trackView = "1".takeIf { trackVisit },
                trackedTopicId = topicId.toString().takeIf { trackVisit },
            )
        }
    }

    override suspend fun topicPosts(
        topicId: Long,
        postIds: List<Long>,
        includeSuggested: Boolean,
    ): DiscoursePostStream {
        requirePositiveId(topicId, "Topic id")
        require(postIds.isNotEmpty()) { "A topic post batch must not be empty" }
        require(postIds.size <= DISCOURSE_TOPIC_STREAM_BATCH_SIZE) {
            "A topic post batch exceeds the exact stream batch size"
        }
        require(postIds.all { it > 0L }) { "Post ids must be positive" }
        require(postIds.distinct().size == postIds.size) { "Post ids must not contain duplicates" }
        return read {
            wire.topicPosts(
                topicId = topicId,
                postIds = postIds,
                includeSuggested = true.takeIf { includeSuggested },
            )
        }
    }

    override suspend fun search(
        query: String,
        page: DiscourseSearchPage,
        type: DiscourseSearchType?,
    ): DiscourseSearchResponse {
        require(query.isNotBlank()) { "Search query must not be blank" }
        require(query.length <= MAX_SEARCH_QUERY_CHARS) { "Search query is too long" }
        require(query.none(Char::isControlCharacter)) {
            "Search query must not contain control characters"
        }
        return read {
            wire.search(
                query = query,
                page = page.queryValueOrNull(),
                type = type?.wireValue,
            )
        }
    }

    override suspend fun user(username: String): DiscourseUserResponse {
        requireUsername(username)
        return read { wire.user(username) }
    }

    override suspend fun userSummary(username: String): DiscourseUserSummaryResponse {
        requireUsername(username)
        return read { wire.userSummary(username) }
    }

    override suspend fun userActions(
        username: String,
        offset: Int,
        filter: String?,
    ): DiscourseUserActionsResponse {
        requireUsername(username)
        require(offset in 0..MAX_USER_ACTION_OFFSET) { "User action offset cannot be negative" }
        filter?.let {
            require(it.length <= 128 && it.none(Char::isControlCharacter)) {
                "User action filter is invalid"
            }
        }
        return read { wire.userActions(username = username, offset = offset, filter = filter) }
    }

    override suspend fun notifications(
        offset: DiscourseNotificationOffset,
        limit: Int,
    ): DiscourseNotificationResponse {
        require(limit in 1..60) { "Notification limit must be between 1 and 60" }
        return read {
            wire.notifications(
                offset = offset.queryValueOrNull(),
                limit = limit,
            )
        }
    }

    override suspend fun currentSession(): DiscourseCurrentSessionResponse = read { wire.currentSession() }

    override suspend fun logout(username: String) {
        requireUsername(username)
        mutate { csrfToken -> wire.logout(username = username, csrfToken = csrfToken) }
    }

    override suspend fun userBookmarks(
        username: String,
        page: DiscourseListPage,
        limit: Int,
    ): DiscourseUserBookmarkListResponse {
        requireUsername(username)
        require(limit in 1..20) { "Bookmark limit must be between 1 and 20" }
        return read {
            wire.userBookmarks(
                username = username,
                page = page.queryValueOrNull(),
                limit = limit,
            )
        }
    }

    override suspend fun bookmarkedTopics(page: DiscourseListPage): DiscourseTopicListResponse =
        read { wire.bookmarkedTopics(page.queryValueOrNull()) }

    override suspend fun createPost(request: DiscourseCreatePostRequest): DiscoursePostMutationResponse {
        request.validateForCreation()
        val response =
            mutate { csrfToken ->
                wire.createPost(
                    csrfToken = csrfToken,
                    raw = request.raw,
                    title = request.title,
                    topicId = request.topicId,
                    categoryId = request.category,
                    archetype = request.archetype ?: "regular".takeIf { request.title != null },
                    replyToPostNumber = request.replyToPostNumber,
                    tags = request.tags,
                )
            }
        response.throwIfEnqueued()
        return response
    }

    override suspend fun updatePost(
        postId: Long,
        request: DiscourseUpdatePostRequest,
    ): DiscoursePostMutationResponse {
        requirePositiveId(postId, "Post id")
        requireContent(request.raw)
        return mutate { csrfToken ->
            wire.updatePost(
                postId = postId,
                csrfToken = csrfToken,
                raw = request.raw,
                editReason = request.editReason,
            )
        }
    }

    override suspend fun markNotificationsRead(notificationId: Long?) {
        notificationId?.let { requirePositiveId(it, "Notification id") }
        mutate { csrfToken ->
            wire.markNotificationsRead(
                csrfToken = csrfToken,
                notificationId = notificationId,
            )
        }
    }

    override suspend fun createPostAction(request: DiscoursePostActionRequest): DiscourseActionResponse {
        requirePositiveId(request.id, "Post id")
        requirePositiveId(request.postActionTypeId, "Post action type id")
        return mutate { csrfToken ->
            wire.createPostAction(
                csrfToken = csrfToken,
                postId = request.id,
                actionTypeId = request.postActionTypeId,
                flagTopic = request.flagTopic,
                message = request.message,
            )
        }
    }

    override suspend fun deletePostAction(
        postId: Long,
        actionTypeId: Long,
    ): DiscourseActionResponse {
        requirePositiveId(postId, "Post id")
        requirePositiveId(actionTypeId, "Post action type id")
        return mutate { csrfToken ->
            wire.deletePostAction(
                postId = postId,
                csrfToken = csrfToken,
                actionTypeId = actionTypeId,
            )
        }
    }

    override suspend fun createBookmark(request: DiscourseCreateBookmarkRequest): DiscourseBookmarkResponse {
        requirePositiveId(request.bookmarkableId, "Bookmarkable id")
        require(request.bookmarkableType == "Topic" || request.bookmarkableType == "Post") {
            "Bookmarkable type must be Topic or Post"
        }
        return mutate { csrfToken ->
            wire.createBookmark(
                csrfToken = csrfToken,
                bookmarkableId = request.bookmarkableId,
                bookmarkableType = request.bookmarkableType,
                name = request.name,
                reminderAt = request.reminderAt,
                autoDeletePreference = request.autoDeletePreference,
            )
        }
    }

    override suspend fun deleteBookmark(bookmarkId: Long) {
        requirePositiveId(bookmarkId, "Bookmark id")
        mutate { csrfToken -> wire.deleteBookmark(bookmarkId, csrfToken) }
    }

    override suspend fun upload(request: DiscourseUploadRequest): DiscourseUploadResponse =
        mutate { csrfToken ->
            wire.upload(
                csrfToken = csrfToken,
                clientId = request.messageBusClientId,
                body = request.toMultipartBody(),
            )
        }

    private suspend fun <T> read(block: suspend () -> T): T =
        translateTransportFailures {
            sessionManager.runForCurrentSession { block() }
        }

    /**
     * Runs an unsafe request with an in-memory token and one explicit-CSRF replay at most.
     * Cloudflare, permission, rate-limit, and every other failure leave the request untouched.
     */
    private suspend fun <T> mutate(block: suspend (csrfToken: String) -> T): T =
        translateTransportFailures {
            sessionManager.runForCurrentSession {
                val firstToken =
                    sessionManager.csrfTokenStore.getOrFetch {
                        wire.csrf().csrf
                    }
                try {
                    block(firstToken)
                } catch (csrfFailure: DiscourseCsrfException) {
                    sessionManager.invalidateCsrfToken(firstToken)
                    val refreshedToken =
                        sessionManager.csrfTokenStore.getOrFetch {
                            wire.csrf().csrf
                        }
                    block(refreshedToken)
                }
            }
        }
}

private suspend fun <T> translateTransportFailures(block: suspend () -> T): T =
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (known: DiscourseException) {
        throw known
    } catch (_: HttpRequestTimeoutException) {
        throw DiscourseNetworkException(DiscourseNetworkFailureKind.Timeout)
    } catch (_: ConnectTimeoutException) {
        throw DiscourseNetworkException(DiscourseNetworkFailureKind.Timeout)
    } catch (_: SocketTimeoutException) {
        throw DiscourseNetworkException(DiscourseNetworkFailureKind.Timeout)
    } catch (_: ContentConvertException) {
        throw DiscourseSerializationException(DiscourseSerializationPhase.ResponseDecoding)
    } catch (_: SerializationException) {
        throw DiscourseSerializationException(DiscourseSerializationPhase.ResponseDecoding)
    } catch (_: IOException) {
        throw DiscourseNetworkException(DiscourseNetworkFailureKind.Connection)
    }

private fun DiscourseTopicListRequest.toAbsoluteUrl(): String {
    val route =
        when {
            category != null -> {
                val parent = category.parentSlug?.let { "/${it.encodeURLPathPart()}" }.orEmpty()
                "/c$parent/${category.slug.encodeURLPathPart()}/${category.id}/l/${feed.pathSegment}.json"
            }

            tags.isNotEmpty() -> {
                "/tag/${tags.first().encodeURLPathPart()}/l/${feed.pathSegment}.json"
            }

            else -> {
                "/${feed.pathSegment}.json"
            }
        }
    val builder = URLBuilder("$DISCOURSE_ORIGIN$route")
    page.queryValueOrNull()?.let { builder.parameters.append("page", it.toString()) }
    period?.let { builder.parameters.append("period", it) }
    order?.let { builder.parameters.append("order", it) }
    ascending?.let { builder.parameters.append("ascending", it.toString()) }
    subset?.let { builder.parameters.append("subset", it) }

    val queryTags = if (category == null && tags.isNotEmpty()) tags.drop(1) else tags
    queryTags.forEach { builder.parameters.append("tags[]", it) }
    if (category == null && tags.size > 1) {
        builder.parameters.append("match_all_tags", "true")
    }
    return builder.buildString()
}

private fun DiscourseCreatePostRequest.validateForCreation() {
    requireContent(raw)
    val isTopic = title != null
    val isReply = topicId != null
    require(isTopic.xor(isReply)) { "A post request must be either a new topic or a reply" }
    if (isTopic) {
        require(title.orEmpty().isNotBlank()) { "Topic title must not be blank" }
        require(title.orEmpty().length <= 1_000) { "Topic title is too long" }
        require(category != null && category > 0L) { "A new topic requires a positive category id" }
        require(replyToPostNumber == null) { "A new topic cannot target a reply number" }
    } else {
        requirePositiveId(checkNotNull(topicId), "Topic id")
        require(category == null && tags.isEmpty()) { "A reply cannot change topic category or tags" }
        replyToPostNumber?.let { require(it > 0) { "Reply target number must be positive" } }
    }
}

private fun DiscoursePostMutationResponse.throwIfEnqueued() {
    if (!isEnqueued) return
    throw DiscoursePostEnqueuedException(
        pendingCount = pendingCount ?: 0,
        pendingPostId = pendingPost?.id,
        topicId = pendingPost?.topicId ?: topicId,
    )
}

private fun DiscourseUploadRequest.toMultipartBody(): MultiPartFormDataContent {
    val safeFileName =
        fileName
            .replace('\\', '_')
            .replace('/', '_')
            .replace(':', '_')
            .replace('"', '_')
            .take(255)
            .ifBlank { "upload" }
    val resolvedContentType =
        contentType
            ?.let { value -> runCatching { ContentType.parse(value) }.getOrNull() }
            ?: ContentType.Application.OctetStream
    val ownedBytes = bytes.copyOf()
    return MultiPartFormDataContent(
        formData {
            append("upload_type", "composer")
            append("synchronous", "true")
            append(
                key = "file",
                value = ownedBytes,
                headers =
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$safeFileName\"")
                        append(HttpHeaders.ContentType, resolvedContentType.toString())
                    },
            )
        },
    )
}

private fun requirePositiveId(
    id: Long,
    label: String,
) {
    require(id > 0L) { "$label must be positive" }
}

private fun requireUsername(username: String) {
    require(username.isNotBlank()) { "Username must not be blank" }
    require(username.length <= 256) { "Username is too long" }
    require(username.none(Char::isControlCharacter)) { "Username contains control characters" }
}

private fun requireContent(raw: String) {
    require(raw.isNotBlank()) { "Post content must not be blank" }
    require(raw.length <= 1_000_000) { "Post content is too large" }
}

private fun Char.isControlCharacter(): Boolean = code < 0x20 || code == 0x7f
