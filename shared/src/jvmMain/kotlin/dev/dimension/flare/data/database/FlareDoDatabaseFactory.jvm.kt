package dev.dimension.flare.data.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.name

private const val MAX_DESKTOP_DATABASE_PATH_CHARS: Int = 4_096

/**
 * Creates the desktop Room database at an explicit application-owned path.
 *
 * Desktop hosts choose their platform configuration directory and pass the complete file path.
 * Requiring an absolute, bounded path avoids silently writing relative to an unpredictable launch
 * directory. The parent directory is created, but existing files are never deleted or replaced.
 */
public fun createJvmFlareDoDatabase(
    path: Path,
    queryDispatcher: CoroutineDispatcher = Dispatchers.IO,
): FlareDoDatabase {
    require(path.isAbsolute) { "Desktop database path must be absolute" }
    val normalized = path.absolute().normalize()
    val pathText = normalized.toString()
    require(pathText.length in 1..MAX_DESKTOP_DATABASE_PATH_CHARS) {
        "Desktop database path is invalid"
    }
    require(pathText.none { it.code < 0x20 || it.code == 0x7f }) {
        "Desktop database path contains control characters"
    }
    require(normalized.name.isNotBlank()) { "Desktop database path must name a file" }

    normalized.parent?.createDirectories()
    return Room
        .databaseBuilder<FlareDoDatabase>(
            name = pathText,
            factory = { FlareDoDatabaseConstructor.initialize() },
        ).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queryDispatcher)
        .build()
}
