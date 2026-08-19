package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAccountMapper
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumAccountRepository
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumSearchMapper
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumSearchRepository
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertSame

internal class DiscourseStage6ModuleTest {
    @Test
    fun searchAndAccountDependenciesAreSingletons() {
        val application = koinApplication { modules(discourseModule) }

        try {
            assertSame(
                application.koin.get<DiscourseForumSearchMapper>(),
                application.koin.get<DiscourseForumSearchMapper>(),
            )
            assertSame(
                application.koin.get<DiscourseForumSearchRepository>(),
                application.koin.get<DiscourseForumSearchRepository>(),
            )
            assertSame(
                application.koin.get<DiscourseForumAccountMapper>(),
                application.koin.get<DiscourseForumAccountMapper>(),
            )
            assertSame(
                application.koin.get<DiscourseForumAccountRepository>(),
                application.koin.get<DiscourseForumAccountRepository>(),
            )
        } finally {
            application.close()
        }
    }
}
