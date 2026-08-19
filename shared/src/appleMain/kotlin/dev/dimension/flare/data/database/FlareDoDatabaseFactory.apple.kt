@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.dimension.flare.data.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSFileManager

private const val MAX_APPLE_DATABASE_PATH_CHARS: Int = 4_096

/**
 * Creates the iOS/macOS Room database at an app-container path supplied by the Swift host.
 *
 * The host obtains an Application Support URL from Foundation and passes its final database path.
 * Only a bounded absolute POSIX path is accepted. Parent creation does not replace existing files,
 * and all public cache plus opaque vault references remain inside the application container.
 */
public fun createAppleFlareDoDatabase(
    path: String,
    queryDispatcher: CoroutineDispatcher = Dispatchers.Default,
): FlareDoDatabase {
    require(path.length in 2..MAX_APPLE_DATABASE_PATH_CHARS && path.startsWith('/')) {
        "Apple database path must be absolute"
    }
    require(path.none { it.code < 0x20 || it.code == 0x7f }) {
        "Apple database path contains control characters"
    }
    require(!path.endsWith('/')) { "Apple database path must name a file" }
    val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
    require(parent.isNotEmpty()) { "Apple database path must have an app-container parent" }

    val created =
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = parent,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    check(created) { "Apple application database directory is unavailable" }

    return Room
        .databaseBuilder<FlareDoDatabase>(
            name = path,
            factory = { FlareDoDatabaseConstructor.initialize() },
        ).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queryDispatcher)
        .build()
}
