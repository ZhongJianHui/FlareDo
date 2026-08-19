package dev.dimension.flare.ui.presenter

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlin.native.HiddenFromObjC

/** Base class for Molecule presenters shared by Compose and SwiftUI clients. */
public expect abstract class PresenterBase<Model : Any>(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    public val models: StateFlow<Model>

    @Composable
    public abstract fun body(): Model

    /** Cancels presenter work and releases its structured coroutine scope. */
    public fun close()
}

@HiddenFromObjC
@Composable
public operator fun <Model : Any> PresenterBase<Model>.invoke(): Model = body()
