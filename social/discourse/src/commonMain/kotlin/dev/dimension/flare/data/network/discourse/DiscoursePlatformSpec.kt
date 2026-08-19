package dev.dimension.flare.data.network.discourse

import dev.dimension.flare.model.PlatformSpec

/** Canonical Linux.do origin used by every API and authentication request. */
public const val DISCOURSE_ORIGIN: String = "https://linux.do"

/**
 * The single forum implementation installed by FlareDo.
 *
 * The origin is a compile-time constant rather than a user preference. This prevents dynamic
 * endpoint input from turning authenticated cookies or CSRF tokens into cross-origin data.
 */
public data object DiscoursePlatformSpec : PlatformSpec {
    override val id: String = "linux.do"
    override val displayName: String = "Linux.do"
    override val baseUrl: String = DISCOURSE_ORIGIN
}
