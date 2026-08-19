package dev.dimension.flare.ui.presenter

import androidx.compose.runtime.Composable
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

public actual abstract class PresenterBase<Model : Any> actual constructor(
    dispatcher: CoroutineDispatcher,
) {
    private val presenterScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    public actual val models: StateFlow<Model> by lazy {
        presenterScope.launchMolecule(RecompositionMode.Immediate) { body() }
    }

    @Composable
    public actual abstract fun body(): Model

    public actual fun close() {
        presenterScope.cancel()
    }
}
