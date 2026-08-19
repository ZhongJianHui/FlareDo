package dev.dimension.flare.data.database

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

private const val DEFAULT_ANDROID_DATABASE_NAME: String = "flaredo.db"
private val ANDROID_DATABASE_NAME = Regex("^[A-Za-z0-9._-]{1,128}$")

/**
 * Creates the Android Room database in the application's private database directory.
 *
 * The caller owns the returned instance and must close it with the application dependency graph.
 * Only a file name is accepted, never an arbitrary path, so a future configuration value cannot
 * move public forum cache data outside Android's app-private storage.
 */
public fun createAndroidFlareDoDatabase(
    context: Context,
    name: String = DEFAULT_ANDROID_DATABASE_NAME,
    queryDispatcher: CoroutineDispatcher = Dispatchers.IO,
): FlareDoDatabase {
    require(ANDROID_DATABASE_NAME.matches(name)) { "Android database name is invalid" }

    return Room
        .databaseBuilder<FlareDoDatabase>(
            context = context.applicationContext,
            name = name,
            factory = { FlareDoDatabaseConstructor.initialize() },
        ).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queryDispatcher)
        .build()
}
