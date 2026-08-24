package dev.dimension.flare.auth

/**
 * Process-memory handoff between the exported redirect Activity and the visible app host.
 *
 * The inbox deliberately has one slot: Linux.do supports one active authorization attempt, and a
 * bounded slot prevents an exported component from retaining attacker-controlled callback strings.
 * Taking a callback clears the slot before invoking the consumer, so failed or throwing consumers
 * cannot accidentally replay sensitive material. Cryptographic replay protection remains the
 * responsibility of the common nonce store after the Activity-scoped authentication presenter
 * accepts the callback.
 */
internal class DiscourseAuthRedirectInbox : DiscourseAuthRedirectSink {
    private val lock = Any()
    private var pending: DiscourseAuthRedirectCallback? = null

    override fun enqueue(callback: DiscourseAuthRedirectCallback): DiscourseAuthRedirectSinkResult =
        synchronized(lock) {
            if (pending != null) {
                DiscourseAuthRedirectSinkResult.Rejected
            } else {
                pending = callback
                DiscourseAuthRedirectSinkResult.Accepted
            }
        }

    /**
     * Delivers at most one callback to an Activity-scoped presenter.
     *
     * [consumer] receives only the already validated URI string and must not persist or log it. A
     * `false` result is fail-closed: the callback has still been consumed from process memory.
     */
    fun deliverPending(consumer: (String) -> Boolean): DiscourseAuthRedirectDeliveryResult {
        val callback =
            synchronized(lock) {
                pending.also { pending = null }
            } ?: return DiscourseAuthRedirectDeliveryResult.Empty

        return try {
            if (consumer(callback.encodedUri)) {
                DiscourseAuthRedirectDeliveryResult.Delivered
            } else {
                DiscourseAuthRedirectDeliveryResult.RejectedByPresenter
            }
        } catch (_: Exception) {
            DiscourseAuthRedirectDeliveryResult.RejectedByPresenter
        }
    }
}

/** Fixed, non-sensitive outcome of an in-memory redirect handoff. */
internal enum class DiscourseAuthRedirectDeliveryResult {
    Empty,
    Delivered,
    RejectedByPresenter,
}
