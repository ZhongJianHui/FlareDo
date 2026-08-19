package dev.dimension.flare.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory diagnostic ring with strict entry and aggregate bounds.
 *
 * Sanitizing happens before truncation so a future change to the truncation policy cannot expose a
 * partially clipped secret. [MutableStateFlow.update] also makes concurrent writers atomic without
 * blocking platform threads.
 */
public class BoundedLogBuffer(
    private val sanitizer: SensitiveDataSanitizer = SensitiveDataSanitizer(),
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxEntryChars: Int = DEFAULT_MAX_ENTRY_CHARS,
    private val maxTotalChars: Int = DEFAULT_MAX_TOTAL_CHARS,
) {
    private val mutableEntries: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxEntryChars > 0) { "maxEntryChars must be positive" }
        require(maxTotalChars >= maxEntryChars) {
            "maxTotalChars must be at least maxEntryChars"
        }
    }

    /** Observable sanitized entries, oldest first. */
    public val entries: StateFlow<List<String>> = mutableEntries.asStateFlow()

    /** Sanitizes, bounds, and atomically appends one diagnostic entry. */
    public fun append(message: String) {
        val safeEntry = sanitizer.sanitize(message).take(maxEntryChars)
        mutableEntries.update { existing ->
            var next = (existing + safeEntry).takeLast(maxEntries)
            var totalChars = next.sumOf(String::length)
            while (totalChars > maxTotalChars && next.size > 1) {
                totalChars -= next.first().length
                next = next.drop(1)
            }
            next
        }
    }

    /** Clears all local diagnostics, for example when a user logs out. */
    public fun clear() {
        mutableEntries.value = emptyList()
    }

    public companion object {
        public const val DEFAULT_MAX_ENTRIES: Int = 200
        public const val DEFAULT_MAX_ENTRY_CHARS: Int = 16 * 1024
        public const val DEFAULT_MAX_TOTAL_CHARS: Int = 256 * 1024
    }
}
