package dev.dimension.flare.apple.shared

import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerMode
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerState
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerTarget
import dev.dimension.flare.ui.model.UiArticleBlock
import dev.dimension.flare.ui.model.UiArticleInline
import dev.dimension.flare.ui.model.UiArticleListItem
import dev.dimension.flare.ui.model.UiArticleTableCell
import dev.dimension.flare.ui.model.UiArticleTableRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

public class AppleForumBridgeTest {
    @Test
    public fun richTextSnapshotPreservesSafeFieldsAndStablePaths() {
        val source =
            listOf(
                UiArticleBlock.Paragraph(
                    text = "Read the guide",
                    inlines =
                        listOf(
                            UiArticleInline.Text("Read "),
                            UiArticleInline.Link("the guide", "https://linux.do/t/42"),
                            UiArticleInline.Code("println()"),
                            UiArticleInline.Image("https://linux.do/image.png", "preview", "Image"),
                            UiArticleInline.Spoiler("hidden", listOf(UiArticleInline.Text("hidden"))),
                        ),
                ),
                UiArticleBlock.Quote(
                    text = "quoted",
                    attribution = "member",
                    blocks = listOf(UiArticleBlock.Code("val answer = 42", "kotlin")),
                ),
                UiArticleBlock.Image(
                    url = "https://linux.do/photo.png",
                    altText = "photo",
                    title = "Photo",
                    linkUrl = "https://linux.do/t/42",
                ),
                UiArticleBlock.ListBlock(
                    ordered = true,
                    startIndex = 3,
                    items =
                        listOf(
                            UiArticleListItem(
                                listOf(UiArticleBlock.Paragraph("first")),
                            ),
                        ),
                ),
                UiArticleBlock.Table(
                    caption = "Metrics",
                    rows =
                        listOf(
                            UiArticleTableRow(
                                listOf(
                                    UiArticleTableCell(
                                        text = "Name",
                                        isHeader = true,
                                        columnSpan = 2,
                                    ),
                                ),
                            ),
                        ),
                ),
                UiArticleBlock.Spoiler(
                    text = "spoiler",
                    summary = "Details",
                    blocks = listOf(UiArticleBlock.Paragraph("revealed")),
                ),
            )

        val first = source.toAppleSnapshots(ownerKey = "post:42")
        val second = source.toAppleSnapshots(ownerKey = "post:42")

        assertEquals(first, second)
        assertEquals("post:42:block:0", first[0].id)
        assertEquals(AppleRichTextBlockKind.PARAGRAPH, first[0].kind)
        assertEquals(AppleRichTextInlineKind.LINK, first[0].inlines[1].kind)
        assertEquals("https://linux.do/t/42", first[0].inlines[1].url)
        assertEquals("post:42:block:1:quote:0", first[1].children.single().id)
        assertEquals("member", first[1].auxiliaryText)
        assertEquals("https://linux.do/t/42", first[2].linkUrl)
        assertTrue(first[3].ordered)
        assertEquals(3, first[3].startIndex)
        assertEquals(
            "post:42:block:3:item:0:block:0",
            first[3]
                .children
                .single()
                .children
                .single()
                .id,
        )
        assertTrue(
            first[4]
                .children
                .single()
                .children
                .single()
                .isHeader,
        )
        assertEquals(
            2,
            first[4]
                .children
                .single()
                .children
                .single()
                .columnSpan,
        )
        assertEquals("post:42:block:5:spoiler:0", first[5].children.single().id)
    }

    @Test
    public fun composerSnapshotRoundTripsEveryOwnerTarget() {
        val targets =
            listOf(
                DiscourseComposerTarget.NewTopic(categoryId = 12L),
                DiscourseComposerTarget.Reply(topicId = 42L, replyToPostNumber = 3),
                DiscourseComposerTarget.Edit(topicId = 42L, postId = 99L, postNumber = 7),
            )

        targets.forEach { target ->
            val snapshot =
                DiscourseComposerState(
                    mode = DiscourseComposerMode.NewTopic,
                    sessionGeneration = 8L,
                    contentVersion = 13L,
                    accountId = "account-1",
                    target = target,
                ).toAppleSnapshot()

            assertEquals(8L, snapshot.sessionGeneration)
            assertEquals(13L, snapshot.contentVersion)
            assertEquals("account-1", snapshot.accountId)
            assertEquals(target.stableKey, snapshot.target?.stableKey)
            assertEquals(target, snapshot.target?.toDiscourseTargetOrNull())
        }
    }

    @Test
    public fun composerTargetRejectsTamperedStableKeyAndMissingIdentity() {
        val tampered =
            AppleComposerTargetSnapshot(
                kind = AppleComposerTargetKind.REPLY,
                stableKey = "topic:41:reply:root",
                categoryId = null,
                topicId = 42L,
                postId = null,
                postNumber = null,
                replyToPostNumber = null,
            )
        val missingTopic = tampered.copy(stableKey = "topic:42:reply:root", topicId = null)

        assertNull(tampered.toDiscourseTargetOrNull())
        assertNull(missingTopic.toDiscourseTargetOrNull())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    public fun observerPublishesInitialDistinctValuesAndStopsAfterCancel() =
        runTest {
            val source = MutableSharedFlow<Int>(replay = 1)
            source.emit(1)
            val received = mutableListOf<Int>()
            val observation =
                observeAppleState(
                    scope = this,
                    flow = source,
                    mapper = { it % 2 },
                    observer = received::add,
                    callbackDispatcher = StandardTestDispatcher(testScheduler),
                )

            runCurrent()
            source.emit(3)
            runCurrent()
            source.emit(2)
            runCurrent()

            assertEquals(listOf(1, 0), received)
            observation.cancel()
            source.emit(4)
            runCurrent()

            assertTrue(observation.isCancelled)
            assertEquals(listOf(1, 0), received)
        }

    @Test
    public fun databasePathRequiresFinalAbsoluteContainerPath() {
        assertTrue("/tmp/flaredo/cache.sqlite".isValidAppleDatabasePath())
        assertTrue(
            "/Users/test/Library/Application Support/io.github.zhongjianhui.flaredo/cache.sqlite"
                .isValidAppleDatabasePath(),
        )

        assertFalse("cache.sqlite".isValidAppleDatabasePath())
        assertFalse("/cache.sqlite".isValidAppleDatabasePath())
        assertFalse("/tmp/flaredo/".isValidAppleDatabasePath())
        assertFalse("/tmp//cache.sqlite".isValidAppleDatabasePath())
        assertFalse("/tmp/../cache.sqlite".isValidAppleDatabasePath())
        assertFalse("/tmp/./cache.sqlite".isValidAppleDatabasePath())
        assertFalse("/tmp/\u0000cache.sqlite".isValidAppleDatabasePath())
        assertFalse(("/tmp/" + "a".repeat(4_096)).isValidAppleDatabasePath())
    }
}
