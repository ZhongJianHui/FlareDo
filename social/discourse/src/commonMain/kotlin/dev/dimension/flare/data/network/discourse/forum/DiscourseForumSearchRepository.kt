package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.network.discourse.DiscourseDataSource
import dev.dimension.flare.data.network.discourse.DiscourseSearchType
import dev.dimension.flare.data.network.discourse.model.DiscourseSearchResponse
import dev.dimension.flare.data.network.discourse.paging.DiscourseSearchPage
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager

/** Post-oriented Linux.do search used by the forum workspace. */
public interface DiscourseForumSearchRepository {
    /**
     * Loads one logical one-based page and removes IDs already held by the caller.
     *
     * Every request is forced to `type_filter=post`; Discourse does not apply full-page pagination
     * reliably without that filter. [knownPostIds] is explicit so repository instances remain
     * stateless and account/session replacement cannot inherit another screen's de-duplication set.
     */
    public suspend fun search(
        query: String,
        page: DiscourseSearchPage = DiscourseSearchPage.Initial,
        knownPostIds: Set<Long> = emptySet(),
    ): DiscourseForumSearchPage
}

/**
 * Keeps the post request and its sanitizing joins inside one session-generation lease.
 *
 * Search can expose account-visible topics when a user is signed in. Binding mapping as well as
 * transport prevents a response captured before logout from escaping after credentials change.
 */
public class DefaultDiscourseForumSearchRepository internal constructor(
    private val remote: DiscourseForumSearchRemoteDataSource,
    private val mapper: DiscourseForumSearchMapper,
    private val sessionManager: DiscourseSessionManager,
) : DiscourseForumSearchRepository {
    public constructor(
        dataSource: DiscourseDataSource,
        mapper: DiscourseForumSearchMapper,
        sessionManager: DiscourseSessionManager,
    ) : this(
        remote = DefaultDiscourseForumSearchRemoteDataSource(dataSource),
        mapper = mapper,
        sessionManager = sessionManager,
    )

    override suspend fun search(
        query: String,
        page: DiscourseSearchPage,
        knownPostIds: Set<Long>,
    ): DiscourseForumSearchPage {
        require(query.isNotBlank()) { "Search query must not be blank" }
        require(query.length <= MAX_SEARCH_QUERY_CHARS) { "Search query is too long" }
        require(query.none(Char::isForumMappingControlCharacter)) {
            "Search query contains control characters"
        }
        require(knownPostIds.all { it > 0L }) { "Known search post ids must be positive" }
        return sessionManager.runForCurrentSession {
            mapper.mapPage(
                query = query,
                page = page,
                response = remote.search(query = query, page = page, type = DiscourseSearchType.Post),
                knownPostIds = knownPostIds,
            )
        }
    }
}

/** Small seam for deterministic pagination tests without opening a Ktor client. */
internal fun interface DiscourseForumSearchRemoteDataSource {
    suspend fun search(
        query: String,
        page: DiscourseSearchPage,
        type: DiscourseSearchType,
    ): DiscourseSearchResponse
}

private class DefaultDiscourseForumSearchRemoteDataSource(
    private val dataSource: DiscourseDataSource,
) : DiscourseForumSearchRemoteDataSource {
    override suspend fun search(
        query: String,
        page: DiscourseSearchPage,
        type: DiscourseSearchType,
    ): DiscourseSearchResponse = dataSource.search(query = query, page = page, type = type)
}

private const val MAX_SEARCH_QUERY_CHARS: Int = 2_000
