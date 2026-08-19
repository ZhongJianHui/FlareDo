package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.paging.DiscourseTopicStreamCursor
import dev.dimension.flare.data.network.discourse.session.DiscourseCookieStorage
import dev.dimension.flare.data.network.discourse.session.DiscourseSessionManager
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

internal class DiscourseDataSourceTest {
    @Test
    fun exactBatchFollowsAuthoritativeStreamOrder() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals("/t/42/posts.json", request.url.encodedPath)
                    respond(
                        content =
                            postStreamFixture(
                                postFixture(id = 44L, topicId = 42L, postNumber = 3),
                                postFixture(id = 91L, topicId = 42L, postNumber = 1),
                                postFixture(id = 12L, topicId = 42L, postNumber = 2),
                            ),
                        headers = dataSourceJsonHeaders(),
                    )
                }
            val cookieStorage = DiscourseCookieStorage()
            val client = createDiscourseHttpClient(engine, cookieStorage)
            val dataSource =
                DiscourseDataSource(
                    DefaultDiscourseApi(
                        wire = createDiscourseWireTransport(client),
                        sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage),
                    ),
                )

            try {
                val page =
                    dataSource.topicPosts(
                        topicId = 42L,
                        streamPostIds = listOf(91L, 12L, 44L),
                    )

                assertEquals(listOf(91L, 12L, 44L), page.posts.map { it.id })
                assertEquals(DiscourseTopicStreamCursor(3), page.nextCursor)
                assertFalse(page.hasMore)
            } finally {
                client.close()
            }
        }

    @Test
    fun malformedBatchesCannotAdvanceCursor() =
        runTest {
            val malformedResponses =
                listOf(
                    postStreamFixture(
                        postFixture(id = 91L, topicId = 42L, postNumber = 1),
                        postFixture(id = 12L, topicId = 42L, postNumber = 2),
                    ),
                    postStreamFixture(
                        postFixture(id = 91L, topicId = 42L, postNumber = 1),
                        postFixture(id = 12L, topicId = 42L, postNumber = 2),
                        postFixture(id = 12L, topicId = 42L, postNumber = 2),
                        postFixture(id = 44L, topicId = 42L, postNumber = 3),
                    ),
                    postStreamFixture(
                        postFixture(id = 91L, topicId = 42L, postNumber = 1),
                        postFixture(id = 12L, topicId = 42L, postNumber = 2),
                        postFixture(id = 45L, topicId = 42L, postNumber = 3),
                    ),
                    postStreamFixture(
                        postFixture(id = 91L, topicId = 42L, postNumber = 1),
                        postFixture(id = 12L, topicId = 99L, postNumber = 2),
                        postFixture(id = 44L, topicId = 42L, postNumber = 3),
                    ),
                )
            var responseIndex = 0
            val engine =
                MockEngine {
                    val body = malformedResponses[responseIndex]
                    responseIndex += 1
                    respond(content = body, headers = dataSourceJsonHeaders())
                }
            val cookieStorage = DiscourseCookieStorage()
            val client = createDiscourseHttpClient(engine, cookieStorage)
            val dataSource =
                DiscourseDataSource(
                    DefaultDiscourseApi(
                        wire = createDiscourseWireTransport(client),
                        sessionManager = DiscourseSessionManager(cookieStorage = cookieStorage),
                    ),
                )

            try {
                malformedResponses.forEach {
                    assertFailsWith<DiscourseSerializationException> {
                        dataSource.topicPosts(
                            topicId = 42L,
                            streamPostIds = listOf(91L, 12L, 44L),
                        )
                    }
                }
                assertEquals(malformedResponses.size, responseIndex)
            } finally {
                client.close()
            }
        }
}

private fun postFixture(
    id: Long,
    topicId: Long,
    postNumber: Int,
): String = """{"id":$id,"topic_id":$topicId,"post_number":$postNumber}"""

private fun postStreamFixture(vararg posts: String): String =
    posts.joinToString(
        prefix = "{\"posts\":[",
        postfix = "]}",
    )

private fun dataSourceJsonHeaders(): Headers =
    Headers.build {
        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
