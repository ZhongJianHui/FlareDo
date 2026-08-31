package dev.dimension.flare.data.network.discourse.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.dimension.flare.ui.presenter.PresenterBase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** Redacted QR share value rendered by Compose without retaining clearable API key or OTP bytes. */
public data class DiscourseQrShare(
    public val id: Long,
    public val encodedValue: String,
    public val username: String,
    public val expiresAtEpochMillis: Long,
) {
    init {
        require(id > 0L) { "QR share id must be positive" }
        require(encodedValue.startsWith("flaredo://qr-login?")) { "QR share value uses an invalid route" }
        require(expiresAtEpochMillis > 0L) { "QR share expiry must be positive" }
    }

    override fun toString(): String =
        "DiscourseQrShare(id=$id, encodedValue=<redacted>, username=<redacted>, expiresAtEpochMillis=$expiresAtEpochMillis)"
}

public data class DiscourseQrShareState(
    public val isBusy: Boolean = false,
    public val share: DiscourseQrShare? = null,
    public val failure: DiscourseQrLoginFailure? = null,
)

public sealed interface DiscourseQrShareAction {
    public data object Generate : DiscourseQrShareAction

    public data object Revoke : DiscourseQrShareAction
}

/** Retained owner for one displayed QR capability and its non-cancellable revocation cleanup. */
public class DiscourseQrSharePresenter private constructor(
    private val backend: DiscourseQrShareBackend,
    dispatcher: CoroutineDispatcher,
) : PresenterBase<DiscourseQrShareState>(dispatcher) {
    public constructor(
        service: DiscourseQrLoginService,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(DefaultDiscourseQrShareBackend(service), dispatcher)

    internal constructor(
        backend: DiscourseQrShareBackend,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        @Suppress("UNUSED_PARAMETER") testMarker: Unit,
    ) : this(backend, dispatcher)

    private val actions = Channel<DiscourseQrShareAction>(capacity = 4)
    private val actorCompleted = CompletableDeferred<Unit>()

    public fun dispatch(action: DiscourseQrShareAction): Boolean = actions.trySend(action).isSuccess

    @Composable
    override fun body(): DiscourseQrShareState {
        var state by remember { mutableStateOf(DiscourseQrShareState()) }
        LaunchedEffect(backend) {
            try {
                runActor(setState = { state = it })
            } finally {
                actorCompleted.complete(Unit)
            }
        }
        return state
    }

    override fun onClose() {
        actions.cancel()
    }

    public suspend fun closeAndJoin() {
        withContext(NonCancellable) {
            close()
            actorCompleted.await()
        }
    }

    private suspend fun runActor(setState: (DiscourseQrShareState) -> Unit): Unit =
        coroutineScope {
            val events = Channel<QrShareEvent>(capacity = 1)
            var activePayload: DiscourseQrLoginPayload? = null
            var operation: Job? = null
            var operationId = 0L
            var presentationId = 0L

            suspend fun revokeActive() {
                activePayload?.let { payload -> backend.revoke(payload) }
                activePayload = null
            }

            fun generate() {
                operationId += 1L
                check(operationId > 0L) { "QR share operation id space is exhausted" }
                val id = operationId
                setState(DiscourseQrShareState(isBusy = true))
                operation =
                    launch {
                        try {
                            events.send(QrShareEvent.Created(id, backend.create()))
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Throwable) {
                            events.send(QrShareEvent.Failed(id, failure.toQrShareFailure()))
                        }
                    }
            }

            try {
                while (true) {
                    select<Unit> {
                        actions.onReceive { action ->
                            when (action) {
                                DiscourseQrShareAction.Generate -> {
                                    operation?.cancelAndJoin()
                                    operation = null
                                    withContext(NonCancellable) { revokeActive() }
                                    generate()
                                }

                                DiscourseQrShareAction.Revoke -> {
                                    operation?.cancelAndJoin()
                                    operation = null
                                    withContext(NonCancellable) { revokeActive() }
                                    setState(DiscourseQrShareState())
                                }
                            }
                        }
                        events.onReceive { event ->
                            if (event.operationId != operationId) {
                                if (event is QrShareEvent.Created) {
                                    withContext(NonCancellable) { backend.revoke(event.payload) }
                                }
                                return@onReceive
                            }
                            operation = null
                            when (event) {
                                is QrShareEvent.Created -> {
                                    presentationId += 1L
                                    check(presentationId > 0L) { "QR share presentation id space is exhausted" }
                                    activePayload = event.payload
                                    setState(
                                        DiscourseQrShareState(
                                            share =
                                                DiscourseQrShare(
                                                    id = presentationId,
                                                    encodedValue = DiscourseQrLoginProtocol.encode(event.payload),
                                                    username = event.payload.username,
                                                    expiresAtEpochMillis = event.payload.expiresAtEpochMillis,
                                                ),
                                        ),
                                    )
                                }

                                is QrShareEvent.Failed -> {
                                    setState(DiscourseQrShareState(failure = event.failure))
                                }
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    operation?.cancelAndJoin()
                    revokeActive()
                }
            }
        }
}

internal interface DiscourseQrShareBackend {
    suspend fun create(): DiscourseQrLoginPayload

    suspend fun revoke(payload: DiscourseQrLoginPayload): Boolean
}

private class DefaultDiscourseQrShareBackend(
    private val service: DiscourseQrLoginService,
) : DiscourseQrShareBackend {
    override suspend fun create(): DiscourseQrLoginPayload = service.createShare()

    override suspend fun revoke(payload: DiscourseQrLoginPayload): Boolean = service.revokeAndClose(payload)
}

private sealed interface QrShareEvent {
    val operationId: Long

    data class Created(
        override val operationId: Long,
        val payload: DiscourseQrLoginPayload,
    ) : QrShareEvent

    data class Failed(
        override val operationId: Long,
        val failure: DiscourseQrLoginFailure,
    ) : QrShareEvent
}

private fun Throwable.toQrShareFailure(): DiscourseQrLoginFailure =
    (this as? DiscourseQrLoginException)?.failure ?: DiscourseQrLoginFailure.CreateFailed
