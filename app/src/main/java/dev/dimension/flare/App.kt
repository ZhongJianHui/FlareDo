package dev.dimension.flare

import android.app.Application
import dev.dimension.flare.data.database.FlareDoDatabase
import dev.dimension.flare.data.database.createAndroidFlareDoDatabase
import dev.dimension.flare.data.network.discourse.discourseModule
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumCache
import dev.dimension.flare.data.network.discourse.forum.DiscourseForumPresenter
import dev.dimension.flare.data.network.discourse.forum.roomDiscourseForumCache
import dev.dimension.flare.di.sharedModule
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * Android process entry point for FlareDo.
 *
 * Keep initialization that genuinely requires an Android [Application] context here. Portable forum
 * services and their dependency graph belong to the shared modules so Android, desktop, and Apple hosts
 * observe the same behavior.
 */
class App : Application() {
    private lateinit var dependencies: KoinApplication

    internal val koin: Koin
        get() = dependencies.koin

    override fun onCreate() {
        super.onCreate()
        dependencies =
            koinApplication {
                allowOverride(true)
                modules(
                    sharedModule,
                    discourseModule,
                    module {
                        single { createAndroidFlareDoDatabase(this@App) } onClose { database ->
                            database?.close()
                        }
                        single<DiscourseForumCache> {
                            roomDiscourseForumCache(
                                dao = get<FlareDoDatabase>().forumCacheEntryDao(),
                            )
                        }
                    },
                )
            }
    }

    /** A presenter has an Activity lifecycle and is never retained after its `close()` call. */
    internal fun createForumPresenter(): DiscourseForumPresenter = koin.get()

    override fun onTerminate() {
        if (::dependencies.isInitialized) dependencies.close()
        super.onTerminate()
    }
}
