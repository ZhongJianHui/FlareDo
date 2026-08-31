package dev.dimension.flare.data.network.discourse.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class DiscourseQrSharePresenterTest {
    @Test
    fun generateRegenerateAndRevokeKeepSecretsOutOfPresentation() =
        runTest {
            val backend = RecordingQrShareBackend()
            val presenter = presenter(backend)
            val models = presenter.models
            try {
                runCurrent()
                assertTrue(presenter.dispatch(DiscourseQrShareAction.Generate))
                advanceUntilIdle()

                val first = assertNotNull(models.value.share)
                assertEquals(1L, first.id)
                assertFalse(first.toString().contains("secret-key-1"))
                assertFalse(first.toString().contains("member-1"))

                assertTrue(presenter.dispatch(DiscourseQrShareAction.Generate))
                advanceUntilIdle()
                val second = assertNotNull(models.value.share)
                assertEquals(2L, second.id)
                assertEquals(listOf(1), backend.revoked)

                assertTrue(presenter.dispatch(DiscourseQrShareAction.Revoke))
                advanceUntilIdle()
                assertNull(models.value.share)
                assertEquals(listOf(1, 2), backend.revoked)
                assertFailsWith<IllegalStateException> { backend.created[1].copyApiKey() }
            } finally {
                presenter.close()
                runCurrent()
            }
        }

    @Test
    fun closeRevokesTheVisibleCapability() =
        runTest {
            val backend = RecordingQrShareBackend()
            val presenter = presenter(backend)
            presenter.models.value
            runCurrent()
            presenter.dispatch(DiscourseQrShareAction.Generate)
            advanceUntilIdle()

            presenter.closeAndJoin()

            assertEquals(listOf(1), backend.revoked)
        }

    private fun TestScope.presenter(backend: RecordingQrShareBackend): DiscourseQrSharePresenter =
        DiscourseQrSharePresenter(
            backend = backend,
            dispatcher = StandardTestDispatcher(testScheduler),
            testMarker = Unit,
        )
}

private class RecordingQrShareBackend : DiscourseQrShareBackend {
    val created = mutableListOf<DiscourseQrLoginPayload>()
    val revoked = mutableListOf<Int>()

    override suspend fun create(): DiscourseQrLoginPayload {
        val number = created.size + 1
        return DiscourseQrLoginPayload(
            apiKey = "secret-key-$number".encodeToByteArray(),
            otp = "abcdef$number".encodeToByteArray(),
            username = "member-$number",
            expiresAtEpochMillis = 600_000L + number,
        ).also(created::add)
    }

    override suspend fun revoke(payload: DiscourseQrLoginPayload): Boolean {
        val index = created.indexOf(payload) + 1
        revoked += index
        payload.close()
        return true
    }
}
