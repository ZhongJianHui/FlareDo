package dev.dimension.flare.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun mediumPaneBudgetPreservesTheDetailAtItsLowerBoundary() {
        val layout = forumManualMultiPaneLayoutFor(600.dp, ForumLayoutClass.Medium)

        assertEquals(280.dp, layout.detailPaneWidth)
        assertNull(layout.supportingPaneWidth)
        assertEquals(
            600.dp,
            ForumNavigationWidth +
                (ForumPaneDividerWidth * 2) +
                layout.listPaneWidth +
                layout.detailPaneWidth,
        )
    }

    @Test
    fun expandedPaneBudgetKeepsReadableArticleAtEightHundredFortyAndNineHundredDp() {
        listOf(840.dp, 900.dp).forEach { width ->
            val layout = forumManualMultiPaneLayoutFor(width, ForumLayoutClass.Expanded)
            val supportingWidth = assertNotNull(layout.supportingPaneWidth)

            assertTrue(layout.detailPaneWidth >= 360.dp)
            assertTrue(layout.listPaneWidth <= ForumExpandedListPaneWidth)
            assertTrue(supportingWidth <= ForumSupportingPaneWidth)
            assertEquals(
                width,
                ForumNavigationWidth +
                    (ForumPaneDividerWidth * 3) +
                    layout.listPaneWidth +
                    layout.detailPaneWidth +
                    supportingWidth,
            )
        }
    }

    @Test
    fun expandedPaneBudgetCollapsesSupportingBeforeShrinkingTheArticle() {
        val layout = forumManualMultiPaneLayoutFor(820.dp, ForumLayoutClass.Expanded)

        assertNull(layout.supportingPaneWidth)
        assertTrue(layout.detailPaneWidth >= 360.dp)
        assertEquals(
            820.dp,
            ForumNavigationWidth +
                (ForumPaneDividerWidth * 2) +
                layout.listPaneWidth +
                layout.detailPaneWidth,
        )
    }

    @Test
    fun compactWorkspaceCannotRequestAMultiPaneBudget() {
        assertFailsWith<IllegalArgumentException> {
            forumManualMultiPaneLayoutFor(400.dp, ForumLayoutClass.Compact)
        }
    }
}
