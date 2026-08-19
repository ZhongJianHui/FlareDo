package dev.dimension.flare.ui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

internal class DiscourseMetadataCompatibilityTest {
    private val json: Json = Json { ignoreUnknownKeys = true }

    @Test
    fun topicFromOlderCacheDefaultsDiscourseMetadata() {
        val cached =
            """
            {
              "itemKey":"topic-7",
              "title":"Cached topic",
              "excerpt":"Cached excerpt",
              "author":{"username":"reader","displayName":"Reader"},
              "replyCount":2,
              "viewCount":10,
              "lastActivityEpochMillis":1000
            }
            """.trimIndent()

        val topic = json.decodeFromString<UiTimelineV2.Topic>(cached)

        assertEquals("topic-7", topic.itemKey)
        assertNull(topic.discourse)
    }

    @Test
    fun articleFromOlderCacheDefaultsDiscourseMetadata() {
        val cached =
            """
            {
              "itemKey":"post-9",
              "title":"Cached post",
              "author":{"username":"writer","displayName":"Writer"},
              "createdAtEpochMillis":2000,
              "blocks":[]
            }
            """.trimIndent()

        val article = json.decodeFromString<UiArticle>(cached)

        assertEquals("post-9", article.itemKey)
        assertNull(article.discourse)
    }
}
