package dev.dimension.flare.model

/**
 * Describes a forum implementation that can be registered with the shared application core.
 *
 * FlareDo currently targets Linux.do only, but keeping the contract in the shared module avoids
 * coupling presenters and caches to the Discourse transport module introduced in stage 3.
 */
public interface PlatformSpec {
    /** Stable identifier used in persisted, non-secret records. */
    public val id: String

    /** Human-readable name shown in account and diagnostics screens. */
    public val displayName: String

    /** Canonical HTTPS origin. Implementations must not derive this value from untrusted input. */
    public val baseUrl: String
}

/** Immutable lookup table for installed [PlatformSpec] implementations. */
public class PlatformRegistry(
    specs: List<PlatformSpec>,
) {
    private val specsById: Map<String, PlatformSpec> =
        specs.associateBy(PlatformSpec::id).also { indexed ->
            require(indexed.size == specs.size) { "Platform ids must be unique" }
        }

    /** Returns all registered platforms in deterministic identifier order. */
    public val all: List<PlatformSpec> = specsById.values.sortedBy(PlatformSpec::id)

    /** Finds a platform without turning unknown persisted identifiers into crashes. */
    public fun find(id: String): PlatformSpec? = specsById[id]
}
