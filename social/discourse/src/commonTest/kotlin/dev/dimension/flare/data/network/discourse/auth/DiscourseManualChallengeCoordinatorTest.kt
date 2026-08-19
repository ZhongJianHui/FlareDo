package dev.dimension.flare.data.network.discourse.auth

import dev.dimension.flare.data.network.discourse.DISCOURSE_ORIGIN
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class DiscourseManualChallengeCoordinatorTest {
    @Test
    fun completionPublishesOnlyTheFixedOriginAndResumesTheCaller() =
        runTest {
            val coordinator = DiscourseManualChallengeCoordinator()
            val result = async { coordinator.present(DISCOURSE_ORIGIN) }
            runCurrent()

            val request = requireNotNull(coordinator.request.value)
            assertEquals(DISCOURSE_ORIGIN, request.origin)
            assertTrue(coordinator.complete(request.requestId))
            assertTrue(result.await())
            assertNull(coordinator.request.value)
        }

    @Test
    fun cancellationFromTheForegroundUiResumesWithFalse() =
        runTest {
            val coordinator = DiscourseManualChallengeCoordinator()
            val result = async { coordinator.present(DISCOURSE_ORIGIN) }
            runCurrent()

            val request = requireNotNull(coordinator.request.value)
            assertTrue(coordinator.cancel(request.requestId))
            assertFalse(result.await())
            assertNull(coordinator.request.value)
        }

    @Test
    fun concurrentPresentationFailsClosedWithoutJoiningTheActiveRequest() =
        runTest {
            val coordinator = DiscourseManualChallengeCoordinator()
            val first = async { coordinator.present(DISCOURSE_ORIGIN) }
            runCurrent()

            assertFalse(coordinator.present(DISCOURSE_ORIGIN))
            val request = requireNotNull(coordinator.request.value)
            assertTrue(coordinator.complete(request.requestId))
            assertTrue(first.await())
        }

    @Test
    fun callerCancellationPropagatesAndRemovesItsVisibleRequest() =
        runTest {
            val coordinator = DiscourseManualChallengeCoordinator()
            val result = async { coordinator.present(DISCOURSE_ORIGIN) }
            runCurrent()
            requireNotNull(coordinator.request.value)

            result.cancel()
            assertFailsWith<CancellationException> { result.await() }
            assertNull(coordinator.request.value)
        }

    @Test
    fun staleCompletionCannotResolveAReplacementRequest() =
        runTest {
            val coordinator = DiscourseManualChallengeCoordinator()
            val first = async { coordinator.present(DISCOURSE_ORIGIN) }
            runCurrent()
            val firstRequest = requireNotNull(coordinator.request.value)
            assertTrue(coordinator.cancel(firstRequest.requestId))
            assertFalse(first.await())

            val second = async { coordinator.present(DISCOURSE_ORIGIN) }
            runCurrent()
            val secondRequest = requireNotNull(coordinator.request.value)
            assertNotEquals(firstRequest.requestId, secondRequest.requestId)
            assertFalse(coordinator.complete(firstRequest.requestId))
            assertEquals(secondRequest, coordinator.request.value)
            assertTrue(coordinator.complete(secondRequest.requestId))
            assertTrue(second.await())
        }

    @Test
    fun arbitraryOriginIsRejectedWithoutPublishingUiState() =
        runTest {
            val coordinator = DiscourseManualChallengeCoordinator()

            assertFalse(coordinator.present("https://example.invalid"))
            assertNull(coordinator.request.value)
        }

    @Test
    fun missingForegroundHostTimesOutAndRemovesTheRequest() =
        runTest {
            val coordinator = DiscourseManualChallengeCoordinator(timeoutMillis = 1_000L)
            val result = async { coordinator.present(DISCOURSE_ORIGIN) }
            runCurrent()
            requireNotNull(coordinator.request.value)

            advanceTimeBy(1_000L)
            runCurrent()

            assertFalse(result.await())
            assertNull(coordinator.request.value)
        }
}
