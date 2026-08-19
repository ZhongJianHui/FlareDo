package dev.dimension.flare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.ChevronDown
import compose.icons.fontawesomeicons.solid.ChevronUp
import compose.icons.fontawesomeicons.solid.Code
import compose.icons.fontawesomeicons.solid.Image
import compose.icons.fontawesomeicons.solid.QuoteLeft
import dev.dimension.flare.compose.ui.Res
import dev.dimension.flare.compose.ui.forum_code
import dev.dimension.flare.compose.ui.forum_hide_spoiler
import dev.dimension.flare.compose.ui.forum_show_spoiler
import dev.dimension.flare.ui.model.UiArticleBlock
import dev.dimension.flare.ui.model.UiArticleInline
import dev.dimension.flare.ui.model.UiArticleListItem
import org.jetbrains.compose.resources.stringResource

private const val MAX_RICH_TEXT_DEPTH = 5
private val QuoteSpineWidth = 3.dp
private val TableCellWidth = 164.dp

private sealed interface ForumParagraphSegment {
    data class Inlines(
        val values: List<UiArticleInline>,
    ) : ForumParagraphSegment

    data class Image(
        val value: UiArticleInline.Image,
    ) : ForumParagraphSegment
}

/**
 * Renders the safe document produced by the cooked-HTML sanitizer.
 *
 * There is intentionally no raw-HTML fallback. An unknown future block must first gain a safe
 * shared model and sanitizer mapping, otherwise it cannot acquire script execution, event
 * attributes, iframe access, or an unsafe URI through the Compose layer.
 */
@Composable
internal fun ForumRichText(
    blocks: List<UiArticleBlock>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        blocks.forEach { ForumRichTextBlock(it, depth = 0) }
    }
}

@Composable
private fun ForumRichTextBlock(
    block: UiArticleBlock,
    depth: Int,
) {
    if (depth >= MAX_RICH_TEXT_DEPTH) {
        Text(
            block.plainTextProjection(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        return
    }

    when (block) {
        is UiArticleBlock.Paragraph -> ForumParagraph(block)
        is UiArticleBlock.Quote -> ForumQuote(block, depth)
        is UiArticleBlock.Code -> ForumCodeBlock(block)
        is UiArticleBlock.Image -> ForumImageBlock(block)
        is UiArticleBlock.ListBlock -> ForumListBlock(block, depth)
        is UiArticleBlock.Table -> ForumTable(block)
        is UiArticleBlock.Spoiler -> ForumSpoiler(block, depth)
    }
}

@Composable
private fun ForumParagraph(block: UiArticleBlock.Paragraph) {
    if (block.inlines.isEmpty()) {
        Text(
            text = block.text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    } else if (block.inlines.none { it is UiArticleInline.Image }) {
        Text(
            text = forumAnnotatedText(block.inlines),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            block.inlines.toForumParagraphSegments().forEach { segment ->
                when (segment) {
                    is ForumParagraphSegment.Inlines -> {
                        Text(
                            text = forumAnnotatedText(segment.values),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }

                    is ForumParagraphSegment.Image -> {
                        val image = segment.value
                        ForumImageBlock(
                            UiArticleBlock.Image(
                                url = image.url,
                                altText = image.altText,
                                title = image.title,
                                linkUrl = image.linkUrl,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private fun List<UiArticleInline>.toForumParagraphSegments(): List<ForumParagraphSegment> =
    buildList {
        val pending = mutableListOf<UiArticleInline>()

        fun flushPending() {
            if (pending.isNotEmpty()) {
                add(ForumParagraphSegment.Inlines(pending.toList()))
                pending.clear()
            }
        }

        for (inline in this@toForumParagraphSegments) {
            if (inline is UiArticleInline.Image) {
                flushPending()
                add(ForumParagraphSegment.Image(inline))
            } else {
                pending += inline
            }
        }
        flushPending()
    }

@Composable
private fun forumAnnotatedText(inlines: List<UiArticleInline>): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val spoilerBackground = MaterialTheme.colorScheme.secondaryContainer
    val spoilerColor = MaterialTheme.colorScheme.onSecondaryContainer
    var revealSpoilers by rememberSaveable { mutableStateOf(false) }
    val revealListener =
        remember {
            LinkInteractionListener { revealSpoilers = true }
        }

    return buildAnnotatedString {
        inlines.forEachIndexed { index, inline ->
            when (inline) {
                is UiArticleInline.Text -> {
                    append(inline.text)
                }

                is UiArticleInline.Link -> {
                    withLink(
                        LinkAnnotation.Url(
                            url = inline.url,
                            styles =
                                TextLinkStyles(
                                    style =
                                        SpanStyle(
                                            color = linkColor,
                                            textDecoration = TextDecoration.Underline,
                                        ),
                                ),
                        ),
                    ) {
                        append(inline.text)
                    }
                }

                is UiArticleInline.Code -> {
                    withStyle(
                        SpanStyle(
                            color = codeColor,
                            background = codeBackground,
                            fontFamily = FontFamily.Monospace,
                        ),
                    ) {
                        append(inline.code)
                    }
                }

                is UiArticleInline.Image -> {
                    val label = inline.altText?.takeIf(String::isNotBlank) ?: "image"
                    withLink(
                        LinkAnnotation.Url(
                            url = inline.linkUrl ?: inline.url,
                            styles = TextLinkStyles(style = SpanStyle(color = linkColor)),
                        ),
                    ) {
                        append("[$label]")
                    }
                }

                is UiArticleInline.Spoiler -> {
                    if (revealSpoilers) {
                        withStyle(
                            SpanStyle(color = spoilerColor, background = spoilerBackground),
                        ) {
                            append(inline.text)
                        }
                    } else {
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "spoiler-$index",
                                styles =
                                    TextLinkStyles(
                                        style =
                                            SpanStyle(
                                                color = spoilerColor,
                                                background = spoilerColor,
                                            ),
                                    ),
                                linkInteractionListener = revealListener,
                            ),
                        ) {
                            append(inline.text.ifBlank { "hidden" })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForumQuote(
    block: UiArticleBlock.Quote,
    depth: Int,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(QuoteSpineWidth)
                .heightIn(min = 44.dp)
                .background(MaterialTheme.colorScheme.tertiary),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    FontAwesomeIcons.Solid.QuoteLeft,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                block.attribution?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (block.blocks.isEmpty()) {
                Text(
                    block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                block.blocks.forEach { ForumRichTextBlock(it, depth + 1) }
            }
        }
    }
}

@Composable
private fun ForumCodeBlock(block: UiArticleBlock.Code) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    FontAwesomeIcons.Solid.Code,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    block.language?.takeIf(String::isNotBlank)
                        ?: stringResource(Res.string.forum_code),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SelectionContainer {
                Text(
                    block.code,
                    modifier =
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ForumImageBlock(block: UiArticleBlock.Image) {
    val uriHandler = LocalUriHandler.current
    val destination = block.linkUrl ?: block.url
    val description = block.altText?.takeIf(String::isNotBlank) ?: block.title
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 480.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { uriHandler.openUri(destination) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                FontAwesomeIcons.Solid.Image,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            AsyncImage(
                model = block.url,
                contentDescription = description,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 480.dp),
                contentScale = ContentScale.Fit,
            )
        }
        description?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ForumListBlock(
    block: UiArticleBlock.ListBlock,
    depth: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.items.forEachIndexed { index, item ->
            ForumListItem(
                marker = if (block.ordered) "${block.startIndex + index}." else "•",
                item = item,
                depth = depth,
            )
        }
    }
}

@Composable
private fun ForumListItem(
    marker: String,
    item: UiArticleListItem,
    depth: Int,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            marker,
            modifier = Modifier.width(28.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.SemiBold,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item.blocks.forEach { ForumRichTextBlock(it, depth + 1) }
        }
    }
}

@Composable
private fun ForumTable(block: UiArticleBlock.Table) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.caption?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(4.dp),
            color = Color.Transparent,
            border =
                androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                ),
        ) {
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                block.rows.forEachIndexed { rowIndex, row ->
                    Row {
                        row.cells.forEach { cell ->
                            Box(
                                modifier =
                                    Modifier
                                        .width(TableCellWidth * cell.columnSpan)
                                        .background(
                                            if (cell.isHeader) {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            } else {
                                                Color.Transparent
                                            },
                                        ).padding(horizontal = 10.dp, vertical = 9.dp),
                            ) {
                                if (cell.inlines.isEmpty()) {
                                    Text(
                                        text = cell.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight =
                                            if (cell.isHeader) {
                                                FontWeight.SemiBold
                                            } else {
                                                FontWeight.Normal
                                            },
                                    )
                                } else {
                                    Text(
                                        text = forumAnnotatedText(cell.inlines),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight =
                                            if (cell.isHeader) {
                                                FontWeight.SemiBold
                                            } else {
                                                FontWeight.Normal
                                            },
                                    )
                                }
                            }
                        }
                    }
                    if (rowIndex != block.rows.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ForumSpoiler(
    block: UiArticleBlock.Spoiler,
    depth: Int,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = { expanded = !expanded }) {
            Icon(
                if (expanded) FontAwesomeIcons.Solid.ChevronUp else FontAwesomeIcons.Solid.ChevronDown,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                block.summary?.takeIf(String::isNotBlank)
                    ?: stringResource(
                        if (expanded) {
                            Res.string.forum_hide_spoiler
                        } else {
                            Res.string.forum_show_spoiler
                        },
                    ),
            )
        }
        if (expanded) {
            if (block.blocks.isEmpty()) {
                Text(
                    block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                block.blocks.forEach { ForumRichTextBlock(it, depth + 1) }
            }
        }
    }
}

private fun UiArticleBlock.plainTextProjection(): String =
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
            altText ?: title.orEmpty()
        }

        is UiArticleBlock.ListBlock -> {
            items.joinToString(separator = "\n") { item ->
                item.blocks.joinToString(separator = " ") { it.plainTextProjection() }
            }
        }

        is UiArticleBlock.Table -> {
            rows.joinToString(separator = "\n") { row ->
                row.cells.joinToString(separator = " | ") { it.text }
            }
        }

        is UiArticleBlock.Spoiler -> {
            text
        }
    }
