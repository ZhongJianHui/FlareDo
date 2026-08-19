package dev.dimension.flare.data.network.discourse.paging

import kotlin.jvm.JvmInline

/**
 * A zero-based page used by Discourse topic-list endpoints.
 *
 * Discourse treats the first topic-list page as page `0`. The first request normally omits the
 * `page` query parameter, while later requests send the numeric value. Keeping this cursor distinct
 * from [DiscourseSearchPage] prevents a shared paging helper from accidentally skipping either the
 * first list page or the first search page.
 */
@JvmInline
public value class DiscourseListPage(
    public val value: Int,
) {
    init {
        require(value >= FIRST_PAGE) { "A Discourse list page cannot be negative" }
    }

    /** Returns `null` for the initial request because Discourse defaults an omitted page to zero. */
    public fun queryValueOrNull(): Int? = value.takeIf { it > FIRST_PAGE }

    /** Advances by one page, rejecting integer overflow instead of wrapping to a negative cursor. */
    public fun next(): DiscourseListPage {
        check(value < Int.MAX_VALUE) { "The Discourse list page cursor is exhausted" }
        return DiscourseListPage(value + 1)
    }

    public companion object {
        /** First page for latest, hot, new, category, tag, and bookmark topic lists. */
        public const val FIRST_PAGE: Int = 0

        /** Canonical initial list cursor. */
        public val Initial: DiscourseListPage = DiscourseListPage(FIRST_PAGE)
    }
}

/**
 * A one-based page used by `/search.json`.
 *
 * The first search request omits `page`, but its logical page is `1`, unlike topic lists. Discourse
 * only applies search pagination reliably when a `type_filter` is also supplied; that endpoint rule
 * is intentionally left to the API request builder while this type protects the page origin.
 */
@JvmInline
public value class DiscourseSearchPage(
    public val value: Int,
) {
    init {
        require(value >= FIRST_PAGE) { "A Discourse search page must start at one" }
    }

    /** Returns `null` for the initial request because Discourse defaults an omitted page to one. */
    public fun queryValueOrNull(): Int? = value.takeIf { it > FIRST_PAGE }

    /** Advances by one page, rejecting integer overflow instead of wrapping the cursor. */
    public fun next(): DiscourseSearchPage {
        check(value < Int.MAX_VALUE) { "The Discourse search page cursor is exhausted" }
        return DiscourseSearchPage(value + 1)
    }

    public companion object {
        /** First page for `/search.json`. */
        public const val FIRST_PAGE: Int = 1

        /** Canonical initial search cursor. */
        public val Initial: DiscourseSearchPage = DiscourseSearchPage(FIRST_PAGE)
    }
}

/**
 * An item offset used by the paged `/notifications` endpoint.
 *
 * Notification pagination is not page based. The next offset advances by the number of rows that
 * were actually accepted after response de-duplication, not by the requested limit. This avoids
 * skipping rows when Discourse returns a short or overlapping page.
 */
@JvmInline
public value class DiscourseNotificationOffset(
    public val value: Int,
) {
    init {
        require(value >= INITIAL_OFFSET) { "A Discourse notification offset cannot be negative" }
    }

    /** Returns `null` for the first request, matching Discourse's omitted-offset behavior. */
    public fun queryValueOrNull(): Int? = value.takeIf { it > INITIAL_OFFSET }

    /**
     * Advances this offset by [acceptedRowCount].
     *
     * A zero-sized page leaves the cursor unchanged. Overflow is rejected so a malformed count can
     * never turn a valid offset into a negative query parameter.
     */
    public fun advanceBy(acceptedRowCount: Int): DiscourseNotificationOffset {
        require(acceptedRowCount >= 0) { "The accepted notification row count cannot be negative" }
        check(acceptedRowCount <= Int.MAX_VALUE - value) {
            "The Discourse notification offset is exhausted"
        }
        return DiscourseNotificationOffset(value + acceptedRowCount)
    }

    public companion object {
        /** First notification offset. */
        public const val INITIAL_OFFSET: Int = 0

        /** Canonical initial notification cursor. */
        public val Initial: DiscourseNotificationOffset =
            DiscourseNotificationOffset(INITIAL_OFFSET)
    }
}
