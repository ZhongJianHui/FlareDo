package dev.dimension.flare.data.network.discourse.model

import kotlinx.serialization.json.Json

/**
 * The single JSON contract used for Linux.do API payloads.
 *
 * Discourse adds fields frequently, including plugin-owned fields that vary between installations.
 * Ignoring unknown keys lets an older FlareDo build continue reading those forward-compatible
 * additions. Known fields remain strict: a missing required identity or a value with the wrong JSON
 * type still raises [kotlinx.serialization.SerializationException]. Callers must not create a more
 * permissive parser for network responses because coercion could turn a malformed identity into a
 * valid-looking local entity.
 */
public val discourseJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        isLenient = false
    }
