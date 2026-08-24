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
                        closeAuthentication = { order += "authentication" },
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
                listOf("composer-start", "composer-finished", "authentication", "forum", "dependencies"),
                order,
            )
            assertTrue(shutdown.isCompleted)
        }

    @Test
    fun dependenciesRemainOpenUntilPresenterCleanupCompletes() =
        runBlocking {
            val cleanupStarted = CompletableDeferred<Unit>()
            val allowCleanup = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val shutdown =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    closeDesktopApplication(
                        closeComposer = { order += "composer" },
                        closeAuthentication = {
                            order += "presenters-start"
                            cleanupStarted.complete(Unit)
                            allowCleanup.await()
                            order += "presenters-finished"
                        },
                        closeForum = { order += "forum" },
                        closeDependencies = { order += "dependencies" },
                    )
                }

            cleanupStarted.await()
            assertEquals(listOf("composer", "presenters-start"), order)
            assertFalse(shutdown.isCompleted)

            allowCleanup.complete(Unit)
            shutdown.join()

            assertEquals(
                listOf("composer", "presenters-start", "presenters-finished", "forum", "dependencies"),
                order,
            )
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
                    closeAuthentication = { order += "authentication" },
                    closeForum = { order += "forum" },
                    closeDependencies = { order += "dependencies" },
                )
            }
        }

        assertEquals(listOf("composer", "authentication", "forum", "dependencies"), order)
    }

    @Test
    fun forumAndDependenciesStillCloseWhenAuthenticationCleanupFails() {
        val order = mutableListOf<String>()

        runCatching {
            runBlocking {
                closeDesktopApplication(
                    closeComposer = { order += "composer" },
                    closeAuthentication = {
                        order += "authentication"
                        error("authentication cleanup failed")
                    },
                    closeForum = { order += "forum" },
                    closeDependencies = { order += "dependencies" },
                )
            }
        }

        assertEquals(listOf("composer", "authentication", "forum", "dependencies"), order)
    }
}
