package dev.dimension.flare.ui.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun legacyArticleBlocksDefaultNewStructuredRichTextFields() {
        val cached =
            """
            {
              "itemKey":"post-10",
              "title":"Legacy rich text",
              "author":{"username":"writer","displayName":"Writer"},
              "createdAtEpochMillis":3000,
              "blocks":[
                {
                  "type":"dev.dimension.flare.ui.model.UiArticleBlock.Paragraph",
                  "text":"Plain cached paragraph"
                },
                {
                  "type":"dev.dimension.flare.ui.model.UiArticleBlock.Quote",
                  "text":"Plain cached quote"
                }
              ]
            }
            """.trimIndent()

        val article = json.decodeFromString<UiArticle>(cached)
        val paragraph = article.blocks[0] as UiArticleBlock.Paragraph
        val quote = article.blocks[1] as UiArticleBlock.Quote

        assertTrue(paragraph.inlines.isEmpty())
        assertTrue(quote.blocks.isEmpty())
        assertEquals("Plain cached paragraph", paragraph.text)
        assertEquals("Plain cached quote", quote.text)
    }
}
