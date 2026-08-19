package dev.dimension.flare.data.network.discourse.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class DiscourseCsrfTokenStoreTest {
    @Test
    fun foldsConcurrentRefreshesIntoOneFetch() =
        runTest {
            val store = DiscourseCsrfTokenStore()
            val fetchEntered = CompletableDeferred<Unit>()
            val releaseFetch = CompletableDeferred<Unit>()
            var fetchCount = 0

            val requests =
                List(20) {
                    async {
                        store.getOrFetch {
                            fetchCount += 1
                            fetchEntered.complete(Unit)
                            releaseFetch.await()
                            "self-authored-csrf"
                        }
                    }
                }
            fetchEntered.await()
            assertEquals(1, fetchCount)
            releaseFetch.complete(Unit)

            assertEquals(List(20) { "self-authored-csrf" }, requests.awaitAll())
            assertEquals(1, fetchCount)
        }

    @Test
    fun invalidationForcesExactlyOneNewFetch() =
        runTest {
            val store = DiscourseCsrfTokenStore()
            var fetchCount = 0

            suspend fun fetch(): String = "csrf-${++fetchCount}"

            assertEquals("csrf-1", store.getOrFetch(::fetch))
            assertEquals("csrf-1", store.getOrFetch(::fetch))
            store.invalidate()
            assertEquals("csrf-2", store.getOrFetch(::fetch))
            assertEquals(2, fetchCount)
        }

    @Test
    fun invalidationDuringFetchCannotCacheTheStaleResponse() =
        runTest {
            val store = DiscourseCsrfTokenStore()
            val firstFetchEntered = CompletableDeferred<Unit>()
            val releaseFirstFetch = CompletableDeferred<Unit>()
            var fetchCount = 0

            val request =
                async {
                    store.getOrFetch {
                        fetchCount += 1
                        if (fetchCount == 1) {
                            firstFetchEntered.complete(Unit)
                            releaseFirstFetch.await()
                            "stale-csrf"
                        } else {
                            "fresh-csrf"
                        }
                    }
                }

            firstFetchEntered.await()
            store.invalidate()
            releaseFirstFetch.complete(Unit)

            assertEquals("fresh-csrf", request.await())
            assertEquals("fresh-csrf", store.getOrFetch { error("Fresh token was not cached") })
            assertEquals(2, fetchCount)
        }

    @Test
    fun lateFailureForOldTokenCannotClearAReplacementToken() =
        runTest {
            val store = DiscourseCsrfTokenStore()

            assertEquals("old-csrf", store.getOrFetch { "old-csrf" })
            assertTrue(store.invalidate(expectedToken = "old-csrf"))
            assertEquals("fresh-csrf", store.getOrFetch { "fresh-csrf" })

            assertFalse(store.invalidate(expectedToken = "old-csrf"))
            assertEquals("fresh-csrf", store.getOrFetch { error("Fresh token was cleared") })
        }

    @Test
    fun refusesUnsafeHeaderValues() =
        runTest {
            val store = DiscourseCsrfTokenStore(maxTokenBytes = 16)

            assertFailsWith<InvalidDiscourseCsrfTokenException> {
                store.getOrFetch { "token\r\ninjected" }
            }
            assertFailsWith<InvalidDiscourseCsrfTokenException> {
                store.getOrFetch { "x".repeat(17) }
            }
        }
}
