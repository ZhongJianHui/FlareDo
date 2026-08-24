package dev.dimension.flare.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscourseAuthRedirectInboxTest {
    @Test
    fun redirectEntryPointOnlyEnqueuesUntilTheVisibleHostDelivers() {
        val inbox = DiscourseAuthRedirectInbox()
        val callback = DiscourseAuthRedirectCallback(FIRST_CALLBACK)
        var exchangeStarted = false

        assertEquals(DiscourseAuthRedirectSinkResult.Accepted, inbox.enqueue(callback))
        assertFalse(exchangeStarted)

        val delivery =
            inbox.deliverPending { rawUri ->
                assertEquals(FIRST_CALLBACK, rawUri)
                exchangeStarted = true
                true
            }

        assertEquals(DiscourseAuthRedirectDeliveryResult.Delivered, delivery)
        assertTrue(exchangeStarted)
        assertEquals(
            DiscourseAuthRedirectDeliveryResult.Empty,
            inbox.deliverPending { error("one-shot callback was delivered twice") },
        )
    }

    @Test
    fun boundedInboxRejectsASecondCallbackWithoutReplacingTheFirst() {
        val inbox = DiscourseAuthRedirectInbox()

        assertEquals(
            DiscourseAuthRedirectSinkResult.Accepted,
            inbox.enqueue(DiscourseAuthRedirectCallback(FIRST_CALLBACK)),
        )
        assertEquals(
            DiscourseAuthRedirectSinkResult.Rejected,
            inbox.enqueue(DiscourseAuthRedirectCallback(REPLAY_CALLBACK)),
        )

        var delivered: String? = null
        assertEquals(
            DiscourseAuthRedirectDeliveryResult.Delivered,
            inbox.deliverPending { rawUri ->
                delivered = rawUri
                true
            },
        )
        assertEquals(FIRST_CALLBACK, delivered)
    }

    @Test
    fun presenterRejectionConsumesTheCallbackFailClosed() {
        val inbox = DiscourseAuthRedirectInbox()
        inbox.enqueue(DiscourseAuthRedirectCallback(FIRST_CALLBACK))

        assertEquals(
            DiscourseAuthRedirectDeliveryResult.RejectedByPresenter,
            inbox.deliverPending { false },
        )
        assertEquals(
            DiscourseAuthRedirectDeliveryResult.Empty,
            inbox.deliverPending { true },
        )
    }
}

private const val FIRST_CALLBACK: String =
    "discourse://auth_redirect?payload=first&oneTimePassword=first"
private const val REPLAY_CALLBACK: String =
    "discourse://auth_redirect?payload=replay&oneTimePassword=replay"
