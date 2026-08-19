package dev.dimension.flare.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

internal class ForumLayoutClassTest {
    @Test
    fun compactLayoutEndsBeforeSixHundredDp() {
        assertEquals(ForumLayoutClass.Compact, forumLayoutClassFor(599.dp))
    }

    @Test
    fun mediumLayoutCoversTabletAndFoldableWidths() {
        assertEquals(ForumLayoutClass.Medium, forumLayoutClassFor(600.dp))
        assertEquals(ForumLayoutClass.Medium, forumLayoutClassFor(839.dp))
    }

    @Test
    fun expandedLayoutStartsAtEightHundredFortyDp() {
        assertEquals(ForumLayoutClass.Expanded, forumLayoutClassFor(840.dp))
    }
}
