package dev.dimension.flare.data.network.discourse.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract tests use small, self-authored and non-production fixtures. */
class DiscourseModelSerializationTest {
    @Test
    fun siteResponseIgnoresUnknownFieldsAndDefaultsOptionalCapabilities() {
        val response =
            discourseJson.decodeFromString<DiscourseSiteResponse>(
                """
                {
                  "categories": [
                    {
                      "id": 41,
                      "name": "Engineering",
                      "slug": "engineering",
                      "topic_count": 7,
                      "future_category_field": {"enabled": true}
                    }
                  ],
                  "top_tags": [{"id": 91, "name": "Kotlin", "slug": "kotlin-tag"}],
                  "future_site_capability": "ignored"
                }
                """.trimIndent(),
            )

        assertEquals(41L, response.categories.single().id)
        assertEquals(7, response.categories.single().topicCount)
        assertEquals(91L, response.topTags.single().id)
        assertFalse(response.canCreateTag)
        assertTrue(response.postActionTypes.isEmpty())
    }

    @Test
    fun topicListRequiresTopicIdentity() {
        val malformed =
            """
            {
              "topic_list": {
                "topics": [{"title": "Missing numeric identity", "slug": "missing-id"}]
              }
            }
            """.trimIndent()

        assertFailsWith<SerializationException> {
            discourseJson.decodeFromString<DiscourseTopicListResponse>(malformed)
        }
    }

    @Test
    fun topicSummaryPreservesModernTagIdentityAndAcceptsLegacyNames() {
        val summary =
            discourseJson.decodeFromString<DiscourseTopicSummary>(
                """
                {
                  "id": 1401,
                  "title": "Two tag serializer generations",
                  "slug": "two-tag-generations",
                  "tags": [
                    {"id": 501, "name": "Modern Tag", "slug": "modern-tag", "future": true},
                    "legacy-tag"
                  ]
                }
                """.trimIndent(),
            )

        val modern = summary.tags[0]
        assertEquals(501L, modern.id)
        assertEquals("Modern Tag", modern.name)
        assertEquals("modern-tag", modern.slug)
        assertEquals("modern-tag", modern.routeSegment)

        val legacy = summary.tags[1]
        assertNull(legacy.id)
        assertEquals("legacy-tag", legacy.name)
        assertNull(legacy.slug)
        assertEquals("legacy-tag", legacy.routeSegment)
    }

    @Test
    fun topicDetailPreservesModernTagIdentityAndAcceptsLegacyNames() {
        val detail =
            discourseJson.decodeFromString<DiscourseTopicDetail>(
                """
                {
                  "id": 1402,
                  "title": "Detail tag compatibility",
                  "slug": "detail-tag-compatibility",
                  "tags": [
                    "old-detail-tag",
                    {"id": 502, "name": "Detail Tag", "slug": "detail-tag"}
                  ],
                  "post_stream": {"posts": [], "stream": []}
                }
                """.trimIndent(),
            )

        assertEquals("old-detail-tag", detail.tags[0].name)
        assertNull(detail.tags[0].id)
        assertEquals(502L, detail.tags[1].id)
        assertEquals("Detail Tag", detail.tags[1].name)
        assertEquals("detail-tag", detail.tags[1].slug)
    }

    @Test
    fun topicTagsRejectMalformedIdentityBeforeItCanBecomeARouteSegment() {
        val oversizedName = "x".repeat(257)
        val malformedFixtures =
            listOf(
                "\"\"",
                "\"legacy\\ncontrol\"",
                "\"$oversizedName\"",
                """{"name":"missing-id","slug":"missing-id"}""",
                """{"id":0,"name":"zero-id","slug":"zero-id"}""",
                """{"id":503,"name":"","slug":"blank-name"}""",
                """{"id":504,"name":"missing-slug"}""",
                """{"id":505,"name":"control\nname","slug":"control-name"}""",
                """{"id":506,"name":"blank-slug","slug":" "}""",
            )

        malformedFixtures.forEach { fixture ->
            assertFailsWith<SerializationException> {
                discourseJson.decodeFromString<DiscourseTopicTag>(fixture)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            DiscourseTopicTag(name = "programmatic\ncontrol")
        }
    }

    @Test
    fun tagResponseUsesNumericIdentityAndAllowsNullDescription() {
        val response =
            discourseJson.decodeFromString<DiscourseTagsResponse>(
                """
                {
                  "tags": [
                    {
                      "id": 92,
                      "text": "multiplatform",
                      "name": "multiplatform",
                      "slug": "multiplatform",
                      "description": null,
                      "count": 12
                    }
                  ],
                  "extras": {
                    "tag_groups": [
                      {"id": 93, "name": "Technology", "tags": []}
                    ]
                  }
                }
                """.trimIndent(),
            )

        assertEquals(92L, response.tags.single().id)
        assertNull(response.tags.single().description)
        assertEquals(
            "Technology",
            response.extras
                ?.tagGroups
                ?.single()
                ?.name,
        )
    }

    @Test
    fun topicDetailPreservesAuthoritativeStreamOrderAndPostIdentity() {
        val detail =
            discourseJson.decodeFromString<DiscourseTopicDetail>(
                """
                {
                  "id": 7001,
                  "title": "A deterministic topic",
                  "slug": "deterministic-topic",
                  "future_topic_field": 1,
                  "post_stream": {
                    "stream": [8803, 8801, 8802],
                    "posts": [
                      {
                        "id": 8803,
                        "topic_id": 7001,
                        "post_number": 3,
                        "username": "sample-user",
                        "cooked": "<p>Third in server order</p>",
                        "future_post_field": [1, 2, 3]
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )

        assertEquals(listOf(8803L, 8801L, 8802L), detail.postStream.stream)
        assertEquals(
            8803L,
            detail.postStream.posts
                .single()
                .id,
        )
        assertEquals(
            3,
            detail.postStream.posts
                .single()
                .postNumber,
        )
    }

    @Test
    fun wrappedAndDirectBatchPostStreamsAreBothAccepted() {
        val direct =
            """
            {
              "posts": [{"id": 11, "topic_id": 8, "post_number": 1}],
              "stream": [11]
            }
            """.trimIndent()
        val wrapped = """{"post_stream":$direct,"suggested_topics":[]}"""

        assertEquals(listOf(11L), discourseJson.decodeFromString<DiscoursePostStream>(direct).stream)
        assertEquals(listOf(11L), discourseJson.decodeFromString<DiscoursePostStream>(wrapped).stream)
    }

    @Test
    fun customJsonSerializersRejectArrayAndPrimitiveTopLevelsAsSerializationFailures() {
        assertNonObjectTopLevelsFail<DiscourseUserResponse>()
        assertNonObjectTopLevelsFail<DiscoursePostMutationResponse>()
        assertNonObjectTopLevelsFail<DiscourseActionResponse>()
        assertNonObjectTopLevelsFail<DiscoursePostStream>()
        assertFailsWith<SerializationException> {
            discourseJson.decodeFromString<DiscoursePostStream>("""{"post_stream":[]}""")
        }
    }

    @Test
    fun postRequiresAllThreeRoutingIdentities() {
        val missingPostId = """{"topic_id":8,"post_number":2}"""
        val missingTopicId = """{"id":12,"post_number":2}"""
        val missingPostNumber = """{"id":12,"topic_id":8}"""

        assertFailsWith<SerializationException> {
            discourseJson.decodeFromString<DiscoursePost>(missingPostId)
        }
        assertFailsWith<SerializationException> {
            discourseJson.decodeFromString<DiscoursePost>(missingTopicId)
        }
        assertFailsWith<SerializationException> {
            discourseJson.decodeFromString<DiscoursePost>(missingPostNumber)
        }
    }

    @Test
    fun searchResponseRetainsContinuationMetadata() {
        val response =
            discourseJson.decodeFromString<DiscourseSearchResponse>(
                """
                {
                  "posts": [
                    {"id": 101, "topic_id": 202, "post_number": 4, "blurb": "matching text"}
                  ],
                  "topics": [
                    {"id": 202, "title": "Search result", "slug": "search-result"}
                  ],
                  "grouped_search_result": {
                    "term": "query",
                    "more_posts": true,
                    "more_users": null,
                    "more_full_page_results": true,
                    "future_grouping": "ignored"
                  }
                }
                """.trimIndent(),
            )

        assertEquals(202L, response.posts.single().topicId)
        val grouped = requireNotNull(response.groupedSearchResult)
        assertTrue(grouped.morePosts == true)
        assertNull(grouped.moreUsers)
        assertTrue(grouped.moreFullPageResults)
    }

    @Test
    fun userResponseAcceptsWrappedAndDirectShapesButRejectsMissingIdentity() {
        val wrapped =
            discourseJson.decodeFromString<DiscourseUserResponse>(
                """{"user":{"id":71,"username":"wrapped-user","future":true}}""",
            )
        val direct =
            discourseJson.decodeFromString<DiscourseUserResponse>(
                """{"id":72,"username":"direct-user","name":"Example"}""",
            )

        assertEquals("wrapped-user", wrapped.user.username)
        assertEquals(72L, direct.user.id)
        assertFailsWith<SerializationException> {
            discourseJson.decodeFromString<DiscourseUserResponse>("""{"user":{"id":73}}""")
        }
    }

    @Test
    fun notificationRequiresIdentityAndKeepsObjectOrStringData() {
        val objectData =
            discourseJson.decodeFromString<DiscourseNotification>(
                """
                {
                  "id": 301,
                  "user_id": 71,
                  "notification_type": 5,
                  "data": {"topic_title": "Redacted topic", "plugin_key": 9}
                }
                """.trimIndent(),
            )
        val stringData =
            discourseJson.decodeFromString<DiscourseNotification>(
                """
                {
                  "id": 302,
                  "user_id": 71,
                  "notification_type": 6,
                  "data": "{\"topic_title\":\"Legacy payload\"}"
                }
                """.trimIndent(),
            )

        assertIs<JsonObject>(objectData.data)
        assertIs<JsonPrimitive>(stringData.data)
        assertFailsWith<SerializationException> {
            discourseJson.decodeFromString<DiscourseNotification>(
                """{"id":303,"user_id":71}""",
            )
        }
    }

    @Test
    fun sessionAndCsrfModelsDoNotInventSecurityIdentity() {
        val anonymous =
            discourseJson.decodeFromString<DiscourseCurrentSessionResponse>(
                """{"current_user":null,"future_session_field":true}""",
            )
        val authenticated =
            discourseJson.decodeFromString<DiscourseCurrentSessionResponse>(
                """{"current_user":{"id":71,"username":"session-user"}}""",
            )

        assertNull(anonymous.currentUser)
        assertEquals(71L, authenticated.currentUser?.id)
        assertFailsWith<SerializationException> {
            discourseJson.decodeFromString<DiscourseCurrentSessionResponse>(
                """{"current_user":{"username":"missing-id"}}""",
            )
        }
        assertFailsWith<SerializationException> {
            discourseJson.decodeFromString<DiscourseCsrfResponse>("{}")
        }
    }

    @Test
    fun postMutationAcceptsDirectAndWrappedPublishedPosts() {
        val direct =
            discourseJson.decodeFromString<DiscoursePostMutationResponse>(
                """{"id":401,"topic_id":501,"post_number":1,"cooked":"<p>Published</p>"}""",
            )
        val wrapped =
            discourseJson.decodeFromString<DiscoursePostMutationResponse>(
                """
                {
                  "post": {"id":402,"topic_id":501,"post_number":2},
                  "topic_id":501,
                  "future_mutation_field":true
                }
                """.trimIndent(),
            )

        assertEquals(401L, direct.post?.id)
        assertEquals(402L, wrapped.post?.id)
        assertFalse(direct.isEnqueued)
    }

    @Test
    fun moderationQueueResponseNeverInventsPublishedPost() {
        val response =
            discourseJson.decodeFromString<DiscoursePostMutationResponse>(
                """
                {
                  "action": "enqueued",
                  "pending_count": 2,
                  "pending_post": {
                    "raw": "A reviewable reply without a durable post identity",
                    "topic_id": 501
                  },
                  "future_review_field": "ignored"
                }
                """.trimIndent(),
            )

        assertTrue(response.isEnqueued)
        assertEquals(2, response.pendingCount)
        assertEquals(501L, response.pendingPost?.topicId)
        assertNull(response.post)
    }

    @Test
    fun uploadActionAndBookmarkEnvelopesPreserveServerIdentity() {
        val upload =
            discourseJson.decodeFromString<DiscourseUploadResponse>(
                """
                {
                  "id": 601,
                  "short_url": "upload://redacted-token",
                  "url": "/uploads/default/example.png",
                  "original_filename": "example.png",
                  "filesize": 4096,
                  "future_upload_field": false
                }
                """.trimIndent(),
            )
        val urlOnlyUpload =
            discourseJson.decodeFromString<DiscourseUploadResponse>(
                """{"url":"/uploads/default/url-only.bin"}""",
            )
        val action =
            discourseJson.decodeFromString<DiscourseActionResponse>(
                """
                {
                  "post_action": {
                    "id": 701,
                    "post_id": 401,
                    "post_action_type_id": 2
                  }
                }
                """.trimIndent(),
            )
        val directAction =
            discourseJson.decodeFromString<DiscourseActionResponse>(
                """{"id":702,"post_id":402,"post_action_type_id":2}""",
            )
        val bookmark =
            discourseJson.decodeFromString<DiscourseBookmarkResponse>("""{"id":801}""")

        assertEquals(601L, upload.id)
        assertEquals("upload://redacted-token", upload.resolvedReference)
        assertEquals("/uploads/default/url-only.bin", urlOnlyUpload.resolvedReference)
        assertEquals(401L, action.postAction?.postId)
        assertEquals(402L, directAction.postAction?.postId)
        assertEquals(801L, bookmark.id)
        assertFailsWith<SerializationException> {
            discourseJson.decodeFromString<DiscourseUploadResponse>("{}")
        }
    }

    private inline fun <reified T> assertNonObjectTopLevelsFail() {
        listOf("[]", "17").forEach { fixture ->
            assertFailsWith<SerializationException> {
                discourseJson.decodeFromString<T>(fixture)
            }
        }
    }
}
