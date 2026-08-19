package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.network.discourse.content.DiscourseCookedHtmlParser
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.model.DiscourseNotification
import dev.dimension.flare.data.network.discourse.model.DiscourseNotificationResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUser
import dev.dimension.flare.data.network.discourse.model.DiscourseUserAction
import dev.dimension.flare.data.network.discourse.model.DiscourseUserActionsResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserResponse
import dev.dimension.flare.data.network.discourse.model.DiscourseUserSummary
import dev.dimension.flare.data.network.discourse.model.DiscourseUserSummaryResponse
import dev.dimension.flare.data.network.discourse.paging.DiscourseNotificationOffset
import dev.dimension.flare.ui.model.UiArticleBlock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class DiscourseForumAccountMapperStage6Test {
    private val mapper = DiscourseForumAccountMapper(DiscourseCookedHtmlParser())

    @Test
    fun profileUsesCookedBioOnlyAndBoundsPresentationValues() {
        val profile =
            mapper.mapProfile(
                requestedUsername = "member",
                profileResponse =
                    DiscourseUserResponse(
                        user =
                            DiscourseUser(
                                id = 42L,
                                username = "member",
                                name = "x".repeat(600),
                                bioRaw = "RAW SECRET <script>raw()</script>",
                                bioCooked =
                                    "<script>cookedBad()</script><p>Hello " +
                                        "<a href=\"javascript:bad()\">world</a></p>",
                                website = "javascript:steal()",
                                trustLevel = -3,
                            ),
                    ),
                summaryResponse =
                    DiscourseUserSummaryResponse(
                        userSummary =
                            DiscourseUserSummary(
                                likesGiven = -4,
                                likesReceived = 7,
                                timeReadSeconds = -9L,
                            ),
                    ),
            )

        assertEquals("member", profile.displayName)
        assertEquals(0, profile.trustLevel)
        assertEquals(0, profile.summary.likesGiven)
        assertEquals(7, profile.summary.likesReceived)
        assertEquals(0L, profile.summary.timeReadSeconds)
        assertNull(profile.websiteUrl)
        val paragraph = profile.bio.single() as UiArticleBlock.Paragraph
        assertEquals("Hello world", paragraph.text)
        assertFalse(paragraph.text.contains("RAW SECRET"))
        assertFalse(paragraph.text.contains("script", ignoreCase = true))
    }

    @Test
    fun profileIdentityMismatchFailsClosed() {
        assertFailsWith<DiscourseSerializationException> {
            mapper.mapProfile(
                requestedUsername = "expected",
                profileResponse =
                    DiscourseUserResponse(
                        user = DiscourseUser(id = 42L, username = "different"),
                    ),
                summaryResponse = DiscourseUserSummaryResponse(DiscourseUserSummary()),
            )
        }
    }

    @Test
    fun activityIdentityDeduplicatesButOffsetUsesRawRows() {
        val first = activity(actionType = 5, postId = 81L, userId = 42L)
        val distinctActor = activity(actionType = 5, postId = 81L, userId = 43L)
        val response = DiscourseUserActionsResponse(listOf(first, first, distinctActor))

        val page = mapper.mapActivityPage(offset = 30, response = response)

        assertEquals(2, page.items.size)
        assertEquals(33, page.nextOffset)
        assertEquals(
            2,
            page.items
                .map { it.itemKey }
                .distinct()
                .size,
        )
        assertTrue(page.items.all { it.excerpt == "Visible excerpt" })
    }

    @Test
    fun knownActivityOverlapStillAdvancesByRawRowCount() {
        val response = DiscourseUserActionsResponse(listOf(activity()))
        val first = mapper.mapActivityPage(offset = 0, response = response)

        val overlap =
            mapper.mapActivityPage(
                offset = 1,
                response = response,
                knownItemKeys = setOf(first.items.single().itemKey),
            )

        assertEquals(emptyList(), overlap.items)
        assertEquals(2, overlap.nextOffset)
    }

    @Test
    fun notificationObjectAndJsonStringDataNormalizeIdentically() {
        val objectData =
            JsonObject(
                mapOf(
                    "topic_title" to JsonPrimitive("<b>Safe topic</b>"),
                    "username" to JsonPrimitive("actor"),
                    "count" to JsonPrimitive(3),
                    "plugin_secret" to JsonPrimitive("must disappear"),
                ),
            )
        val stringData =
            JsonPrimitive(
                """{"topic_title":"<b>Safe topic</b>","username":"actor","count":3,"plugin_secret":"must disappear"}""",
            )
        val objectPage =
            mapper.mapNotificationPage(
                offset = DiscourseNotificationOffset.Initial,
                response = notificationResponse(notification(id = 101L, data = objectData)),
                expectedRecipientUserId = 42L,
            )
        val stringPage =
            mapper.mapNotificationPage(
                offset = DiscourseNotificationOffset.Initial,
                response = notificationResponse(notification(id = 102L, data = stringData)),
                expectedRecipientUserId = 42L,
            )

        assertEquals(objectPage.items.single().data, stringPage.items.single().data)
        assertEquals("Safe topic", objectPage.items.single().title)
        assertEquals(
            3,
            objectPage.items
                .single()
                .data.count,
        )
    }

    @Test
    fun unknownNotificationTypeIsGenericAndMaliciousTextIsSanitized() {
        val page =
            mapper.mapNotificationPage(
                offset = DiscourseNotificationOffset.Initial,
                response =
                    notificationResponse(
                        notification(
                            notificationType = 9_999,
                            data =
                                JsonObject(
                                    mapOf(
                                        "topic_title" to
                                            JsonPrimitive(
                                                "<script>bad()</script><span>Visible</span>",
                                            ),
                                    ),
                                ),
                        ),
                    ),
                expectedRecipientUserId = 42L,
            )

        val item = page.items.single()
        assertEquals(DiscourseForumNotificationKind.Generic, item.kind)
        assertEquals("Visible", item.title)
        assertFalse(item.title.orEmpty().contains('<'))
    }

    @Test
    fun bookmarkReminderAndReactionUseTheirDiscourseCoreTypeIds() {
        val reminder =
            mapper
                .mapNotificationPage(
                    offset = DiscourseNotificationOffset.Initial,
                    response = notificationResponse(notification(id = 101L, notificationType = 24)),
                    expectedRecipientUserId = 42L,
                ).items
                .single()
        val reaction =
            mapper
                .mapNotificationPage(
                    offset = DiscourseNotificationOffset.Initial,
                    response = notificationResponse(notification(id = 102L, notificationType = 25)),
                    expectedRecipientUserId = 42L,
                ).items
                .single()

        assertEquals(DiscourseForumNotificationKind.Reminder, reminder.kind)
        assertEquals(DiscourseForumNotificationKind.Reaction, reaction.kind)
    }

    @Test
    fun notificationRecipientMismatchFailsBeforeDeduplication() {
        assertFailsWith<DiscourseSerializationException> {
            mapper.mapNotificationPage(
                offset = DiscourseNotificationOffset.Initial,
                response = notificationResponse(notification(userId = 99L)),
                expectedRecipientUserId = 42L,
                knownIds = setOf(101L),
            )
        }
    }

    @Test
    fun overlapAdvancesOnlyByNewIdsAndNeverUsesContinuationUrl() {
        val page =
            mapper.mapNotificationPage(
                offset = DiscourseNotificationOffset(10),
                response =
                    notificationResponse(
                        notification(id = 101L),
                        notification(id = 102L),
                        loadMore = "https://attacker.invalid/steal?offset=999999",
                    ),
                expectedRecipientUserId = 42L,
                knownIds = setOf(101L),
            )

        assertEquals(listOf(102L), page.items.map { it.id })
        assertEquals(DiscourseNotificationOffset(11), page.nextOffset)
    }

    @Test
    fun continuationWithoutAcceptedIdentityFailsInsteadOfLooping() {
        assertFailsWith<DiscourseSerializationException> {
            mapper.mapNotificationPage(
                offset = DiscourseNotificationOffset(10),
                response =
                    notificationResponse(
                        notification(id = 101L),
                        loadMore = "/notifications?offset=11",
                    ),
                expectedRecipientUserId = 42L,
                knownIds = setOf(101L),
            )
        }
    }
}

private fun activity(
    actionType: Int = 5,
    postId: Long = 81L,
    userId: Long = 42L,
): DiscourseUserAction =
    DiscourseUserAction(
        actionType = actionType,
        createdAt = "2026-08-19T01:02:03Z",
        userId = userId,
        username = "member$userId",
        topicId = 7L,
        postId = postId,
        postNumber = 2,
        slug = "safe-topic",
        title = "Safe title",
        excerpt = "<script>bad()</script><p>Visible excerpt</p>",
    )

private fun notification(
    id: Long = 101L,
    userId: Long = 42L,
    notificationType: Int = 2,
    data: kotlinx.serialization.json.JsonElement? = null,
): DiscourseNotification =
    DiscourseNotification(
        id = id,
        userId = userId,
        notificationType = notificationType,
        createdAt = "2026-08-19T01:02:03Z",
        topicId = 7L,
        postNumber = 2,
        slug = "safe-topic",
        data = data,
    )

private fun notificationResponse(
    vararg notifications: DiscourseNotification,
    loadMore: String? = null,
): DiscourseNotificationResponse =
    DiscourseNotificationResponse(
        notifications = notifications.toList(),
        totalRowsNotifications = notifications.size,
        seenNotificationId = 0L,
        loadMoreNotifications = loadMore,
    )
