package dev.dimension.flare.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class BoundedLogBufferTest {
    @Test
    fun sanitizesThenBoundsEveryEntry() {
        val buffer = BoundedLogBuffer(maxEntries = 2, maxEntryChars = 24, maxTotalChars = 48)

        buffer.append("discarded")
        buffer.append("token=extremely-secret-value")
        buffer.append("last")

        val entries = buffer.entries.value
        assertEquals(2, entries.size)
        assertFalse(entries.joinToString().contains("extremely-secret-value"))
        assertTrue(entries.all { it.length <= 24 })
        assertEquals("last", entries.last())
    }

    @Test
    fun evictsOldEntriesToHonorAggregateBound() {
        val buffer = BoundedLogBuffer(maxEntries = 10, maxEntryChars = 10, maxTotalChars = 20)

        repeat(4) { index -> buffer.append("entry-$index") }

        assertEquals(listOf("entry-2", "entry-3"), buffer.entries.value)
        assertTrue(buffer.entries.value.sumOf(String::length) <= 20)
    }

    @Test
    fun clearRemovesAllEntries() {
        val buffer = BoundedLogBuffer()
        buffer.append("message")

        buffer.clear()

        assertTrue(buffer.entries.value.isEmpty())
    }
}
