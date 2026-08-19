package dev.dimension.flare.apple.shared

/**
 * Narrow bootstrap surface exposed to Swift while the forum data layer is being built.
 *
 * This bridge intentionally performs no process-wide initialization. In particular, it does not
 * install telemetry, AI callbacks, credentials, or a dependency graph as a side effect of opening
 * the application. Later stages can add explicit, testable session initialization here without
 * restoring any of those removed integrations.
 */
public object AppleSharedHelper {
    /** Returns the canonical product name from the shared framework. */
    public fun productName(): String = "FlareDo"
}
