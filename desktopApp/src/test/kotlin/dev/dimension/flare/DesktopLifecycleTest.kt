package dev.dimension.flare

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class DesktopLifecycleTest {
    @Test
    fun dependenciesRemainOpenUntilTheComposerFlushCompletes() =
        runBlocking {
            val flushStarted = CompletableDeferred<Unit>()
            val allowFlush = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val shutdown =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    closeDesktopApplication(
                        closeComposer = {
                            order += "composer-start"
                            flushStarted.complete(Unit)
                            allowFlush.await()
                            order += "composer-finished"
                        },
                        closeForum = { order += "forum" },
                        closeDependencies = { order += "dependencies" },
                    )
                }

            flushStarted.await()
            assertEquals(listOf("composer-start"), order)
            assertFalse(shutdown.isCompleted)

            allowFlush.complete(Unit)
            shutdown.join()

            assertEquals(
                listOf("composer-start", "composer-finished", "forum", "dependencies"),
                order,
            )
            assertTrue(shutdown.isCompleted)
        }

    @Test
    fun laterResourcesStillCloseWhenComposerFlushFails() {
        val order = mutableListOf<String>()

        runCatching {
            runBlocking {
                closeDesktopApplication(
                    closeComposer = {
                        order += "composer"
                        error("flush failed")
                    },
                    closeForum = { order += "forum" },
                    closeDependencies = { order += "dependencies" },
                )
            }
        }

        assertEquals(listOf("composer", "forum", "dependencies"), order)
    }
}
