package dev.dimension.flare.data.network.discourse.realtime

import dev.dimension.flare.data.database.createJvmFlareDoDatabase
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomDiscourseMessageBusCursorStoreJvmTest {
    @Test
    fun realRoomRestoresOnlyNotificationCursorAfterProcessRestart() =
        runTest {
            val directory = Files.createTempDirectory("flaredo-message-bus-store-")
            val path = directory.resolve("cursor.db")
            try {
                val firstDatabase = createJvmFlareDoDatabase(path)
                try {
                    val database = firstDatabase
                    val store = roomDiscourseMessageBusCursorStore(database.messageBusCursorDao())
                    assertEquals(
                        DiscourseMessageBusCursorAdvance(cursor = 41L, advanced = true),
                        store.advance(ACCOUNT_ID, NOTIFICATION_CHANNEL, 41L),
                    )
                    assertEquals(
                        DiscourseMessageBusCursorAdvance(cursor = 12L, advanced = true),
                        store.advance(ACCOUNT_ID, LATEST_CHANNEL, 12L),
                    )
                } finally {
                    firstDatabase.close()
                }

                val restartedDatabase = createJvmFlareDoDatabase(path)
                try {
                    val database = restartedDatabase
                    val restored = roomDiscourseMessageBusCursorStore(database.messageBusCursorDao())
                    assertEquals(41L, restored.read(ACCOUNT_ID, NOTIFICATION_CHANNEL))
                    assertNull(restored.read(ACCOUNT_ID, LATEST_CHANNEL))
                    assertEquals(
                        DiscourseMessageBusCursorAdvance(cursor = 41L, advanced = false),
                        restored.advance(ACCOUNT_ID, NOTIFICATION_CHANNEL, 40L),
                    )
                    assertEquals(
                        DiscourseMessageBusCursorAdvance(cursor = 45L, advanced = true),
                        restored.advance(ACCOUNT_ID, NOTIFICATION_CHANNEL, 45L),
                    )
                } finally {
                    restartedDatabase.close()
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    private companion object {
        const val ACCOUNT_ID: String = "42"
        const val NOTIFICATION_CHANNEL: String = "/notification/42"
        const val LATEST_CHANNEL: String = "/latest"
    }
}
