package dev.dimension.flare.data.network.discourse.forum

import dev.dimension.flare.data.network.discourse.content.DiscourseCookedHtmlParser
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationException
import dev.dimension.flare.data.network.discourse.error.DiscourseSerializationPhase
import dev.dimension.flare.ui.model.UiArticleBlock
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.takeFrom
import kotlinx.serialization.SerializationException
import kotlin.time.Instant

/**
 * Shared fail-closed helpers for account-facing Discourse mappers.
 *
 * The anonymous feed mapper predates these helpers and deliberately remains untouched. Keeping the
 * Stage 6 helpers in a small file avoids a broad refactor while still applying one sanitization and
 * URL policy to search, profiles, activity, and notifications.
 */
internal fun <T> mapForumResponse(block: () -> T): T =
    try {
        block()
    } catch (known: DiscourseSerializationException) {
        throw known
    } catch (_: SerializationException) {
        throw forumProtocolFailure()
    } catch (_: IllegalArgumentException) {
        throw forumProtocolFailure()
    } catch (_: IllegalStateException) {
        throw forumProtocolFailure()
    }

internal fun forumProtocolFailure(): DiscourseSerializationException =
    DiscourseSerializationException(DiscourseSerializationPhase.ResponseDecoding)

/** Sanitizes cooked or plain server text and returns a bounded plain-text projection. */
internal fun DiscourseCookedHtmlParser.sanitizeForumText(
    value: String?,
    maxChars: Int,
): String? {
    val candidate = value?.takeIf { it.length <= MAX_SERVER_TEXT_INPUT_CHARS } ?: return null
    return parse(candidate)
        .forumPlainText()
        .take(maxChars)
        .trim()
        .takeIf(String::isNotEmpty)
}

internal fun List<UiArticleBlock>.forumPlainText(): String = joinToString(separator = "\n") { it.forumPlainText() }

private fun UiArticleBlock.forumPlainText(): String =
    when (this) {
        is UiArticleBlock.Paragraph -> {
            text
        }

        is UiArticleBlock.Quote -> {
            text
        }

        is UiArticleBlock.Code -> {
            code
        }

        is UiArticleBlock.Image -> {
            altText.orEmpty()
        }

        is UiArticleBlock.ListBlock -> {
            items.joinToString(separator = "\n") { item ->
                item.blocks.joinToString(separator = " ") { it.forumPlainText() }
            }
        }

        is UiArticleBlock.Table -> {
            rows.joinToString(separator = "\n") { row ->
                row.cells.joinToString(separator = " ") { it.text }
            }
        }

        is UiArticleBlock.Spoiler -> {
            text
        }
    }

internal fun parseForumEpochMillis(value: String?): Long? {
    val candidate = value?.takeIf { it.length <= MAX_TIMESTAMP_CHARS } ?: return null
    return runCatching { Instant.parse(candidate).toEpochMilliseconds() }
        .getOrNull()
        ?.takeIf { it >= 0L }
}

internal fun String?.safeForumDisplayValue(maxChars: Int): String? {
    val candidate = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return candidate.takeIf {
        it.length <= maxChars && it.none(Char::isForumMappingControlCharacter)
    }
}

internal fun String.requireForumRoute(): String {
    require(isNotBlank())
    require(length <= MAX_ROUTE_CHARS)
    require(this == trim())
    require(none(Char::isForumMappingControlCharacter))
    return this
}

internal fun String?.toSafeForumAvatarUrl(): String? {
    val template = this ?: return null
    if (
        template.isBlank() ||
        template.length > MAX_URL_CHARS ||
        template != template.trim() ||
        template.any(Char::isForumMappingControlCharacter)
    ) {
        return null
    }
    val expanded = template.replace("{size}", AVATAR_SIZE.toString())
    if ('{' in expanded || '}' in expanded) return null
    return expanded.toSafeForumUrl(allowRelative = true)
}

/** Accepts HTTPS links only; relative URLs are restricted to the fixed Linux.do origin. */
internal fun String?.toSafeForumUrl(allowRelative: Boolean = false): String? {
    val candidate = this ?: return null
    if (
        candidate.isBlank() ||
        candidate.length > MAX_URL_CHARS ||
        candidate != candidate.trim() ||
        candidate.any(Char::isForumMappingControlCharacter)
    ) {
        return null
    }
    val normalized = if (candidate.startsWith("//")) "https:$candidate" else candidate
    return try {
        val url =
            when {
                normalized.startsWith("https://", ignoreCase = true) -> Url(normalized)
                allowRelative -> URLBuilder(FORUM_ORIGIN).takeFrom(normalized).build()
                else -> return null
            }
        if (
            url.protocol != URLProtocol.HTTPS ||
            url.host.isBlank() ||
            url.user != null ||
            url.password != null
        ) {
            null
        } else {
            url.toString()
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun Char.isForumMappingControlCharacter(): Boolean = code < 0x20 || code == 0x7f

internal const val MAX_FORUM_TITLE_CHARS: Int = 1_000
internal const val MAX_FORUM_EXCERPT_CHARS: Int = 4_000
internal const val MAX_FORUM_USERNAME_CHARS: Int = 256
internal const val MAX_FORUM_SMALL_TEXT_CHARS: Int = 512
internal const val MAX_FORUM_TAG_CHARS: Int = 256

private const val FORUM_ORIGIN: String = "https://linux.do/"
private const val AVATAR_SIZE: Int = 96
private const val MAX_ROUTE_CHARS: Int = 256
private const val MAX_URL_CHARS: Int = 2_048
private const val MAX_TIMESTAMP_CHARS: Int = 128
private const val MAX_SERVER_TEXT_INPUT_CHARS: Int = 256 * 1024
