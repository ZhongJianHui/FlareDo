package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerPresenter
import dev.dimension.flare.data.network.discourse.composer.DiscourseComposerRepository
import dev.dimension.flare.data.network.discourse.composer.DiscourseDraftStore
import dev.dimension.flare.data.network.discourse.composer.DiscoursePostActionRepository
import dev.dimension.flare.data.network.discourse.composer.MemoryDiscourseDraftStore
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

internal class DiscourseStage7ModuleTest {
    @Test
    fun composerDraftAndActionDependenciesUseApplicationSingletons() {
        val application = koinApplication { modules(discourseModule) }
        var firstPresenter: DiscourseComposerPresenter? = null
        var secondPresenter: DiscourseComposerPresenter? = null

        try {
            assertIs<MemoryDiscourseDraftStore>(application.koin.get<DiscourseDraftStore>())
            assertSame(
                application.koin.get<DiscourseComposerRepository>(),
                application.koin.get<DiscourseComposerRepository>(),
            )
            assertSame(
                application.koin.get<DiscoursePostActionRepository>(),
                application.koin.get<DiscoursePostActionRepository>(),
            )
            firstPresenter = application.koin.get()
            secondPresenter = application.koin.get()
            assertNotSame(firstPresenter, secondPresenter)
        } finally {
            firstPresenter?.close()
            secondPresenter?.close()
            application.close()
        }
    }
}
