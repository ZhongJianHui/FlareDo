package dev.dimension.flare.data.network.discourse.content

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import dev.dimension.flare.ui.model.UiArticleBlock
import dev.dimension.flare.ui.model.UiArticleInline
import dev.dimension.flare.ui.model.UiArticleListItem
import dev.dimension.flare.ui.model.UiArticleTableCell
import dev.dimension.flare.ui.model.UiArticleTableRow
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.takeFrom

/**
 * Converts Discourse `cooked` HTML into the only rich-text values accepted by the UI layer.
 *
 * Parsing is intentionally fail-closed. Neither malformed input nor a parser failure can expose raw
 * HTML to Compose, SwiftUI, or a WebView. Unsupported but inert elements are unwrapped, while active
 * elements such as scripts, iframes, forms, and SVG are discarded with their complete subtrees.
 */
public class DiscourseCookedHtmlParser(
    private val limits: DiscourseRichTextLimits = DiscourseRichTextLimits(),
) {
    /** Returns a sanitized block tree. Raw HTML is never retained in the result. */
    public fun parse(cookedHtml: String): List<UiArticleBlock> = parseDocument(cookedHtml).blocks

    /** Exposes truncation metadata to module tests without expanding the business-layer API. */
    internal fun parseDocument(cookedHtml: String): DiscourseRichTextDocument {
        val boundedInput = cookedHtml.boundedUtf8Prefix(limits)
        if (boundedInput.value.isEmpty()) {
            return DiscourseRichTextDocument(
                blocks = emptyList(),
                wasTruncated = boundedInput.wasTruncated,
                removedUnsafeContent = false,
            )
        }

        return try {
            val state = ParseState(limits, boundedInput.wasTruncated)
            val body = Ksoup.parseBodyFragment(boundedInput.value, FORUM_ORIGIN).body()
            val blocks = DocumentTreeParser(state).parseBlocks(body.childNodes(), depth = 1)
            DiscourseRichTextDocument(
                blocks = blocks,
                wasTruncated = state.wasTruncated,
                removedUnsafeContent = state.removedUnsafeContent,
            )
        } catch (_: Exception) {
            // A safe empty document is preferable to falling back to raw HTML after any parser bug.
            DiscourseRichTextDocument(
                blocks = emptyList(),
                wasTruncated = true,
                removedUnsafeContent = true,
            )
        }
    }
}

private class DocumentTreeParser(
    private val state: ParseState,
) {
    fun parseBlocks(
        nodes: List<Node>,
        depth: Int,
    ): List<UiArticleBlock> {
        val result = mutableListOf<UiArticleBlock>()
        var pendingInlines = InlineCollector()

        fun flushParagraph() {
            val paragraph = pendingInlines.toParagraph(state) ?: return
            result += paragraph
            pendingInlines = InlineCollector()
        }

        for (node in nodes) {
            val element = node as? Element
            val isStandaloneImage =
                element?.normalName() == "img" ||
                    (element?.normalName() == "a" && element.hasOnlyLinkedImage())
            val isBlock = element?.isBlock() == true || isStandaloneImage

            if (isBlock) {
                flushParagraph()
                result += parseBlockNode(node, depth)
            } else {
                parseInlineNode(node, depth, pendingInlines)
            }

            if (state.nodeBudgetExhausted) break
        }

        flushParagraph()
        return result
    }

    private fun parseBlockNode(
        node: Node,
        depth: Int,
    ): List<UiArticleBlock> {
        if (!state.enterNode(depth)) return emptyList()
        if (node is TextNode) {
            val inlines = InlineCollector().also { it.appendText(state.takeText(node.getWholeText())) }
            return listOfNotNull(inlines.toParagraph(state))
        }

        val element = node as? Element ?: return emptyList()
        if (!prepareElement(element)) return emptyList()

        if (element.isDiscourseQuoteElement()) {
            return listOfNotNull(parseDiscourseQuote(element, depth))
        }

        return when (element.normalName()) {
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "figcaption", "dt", "dd" -> {
                listOfNotNull(parseParagraph(element, depth))
            }

            "blockquote" -> {
                listOfNotNull(parseQuote(element, depth))
            }

            "pre" -> {
                listOfNotNull(parseCodeBlock(element, depth))
            }

            "ul", "ol" -> {
                listOfNotNull(parseList(element, depth))
            }

            "table" -> {
                listOfNotNull(parseTable(element, depth))
            }

            "details" -> {
                listOfNotNull(parseDetailsSpoiler(element, depth))
            }

            "img" -> {
                listOfNotNull(parseImageBlock(element, depth, linkUrl = null, entered = true))
            }

            "a" -> {
                listOfNotNull(parseLinkedImageBlock(element, depth))
            }

            "hr" -> {
                emptyList()
            }

            else -> {
                if (element.isSpoilerElement()) {
                    listOfNotNull(parseBlockSpoiler(element, depth))
                } else {
                    parseBlocks(element.childNodes(), depth + 1)
                }
            }
        }
    }

    private fun parseParagraph(
        element: Element,
        depth: Int,
    ): UiArticleBlock? {
        val inlines = parseInlineChildren(element.childNodes(), depth + 1)
        val standaloneImage = inlines.values.singleOrNull() as? UiArticleInline.Image
        if (element.normalName() == "p" && standaloneImage != null) {
            if (!state.claimBlock()) return null
            return UiArticleBlock.Image(
                url = standaloneImage.url,
                altText = standaloneImage.altText,
                title = standaloneImage.title,
                linkUrl = standaloneImage.linkUrl,
            )
        }
        return inlines.toParagraph(state)
    }

    private fun parseQuote(
        element: Element,
        depth: Int,
    ): UiArticleBlock.Quote? {
        if (!state.claimBlock()) return null
        val blocks = parseBlocks(element.childNodes(), depth + 1)
        val text = blocks.plainText()
        if (blocks.isEmpty()) {
            state.releaseBlock()
            return null
        }

        return UiArticleBlock.Quote(
            text = text,
            attribution = safeAttributeText(element, "data-username"),
            blocks = blocks,
        )
    }

    private fun parseDiscourseQuote(
        element: Element,
        depth: Int,
    ): UiArticleBlock.Quote? {
        if (!state.claimBlock()) return null
        val blocks = mutableListOf<UiArticleBlock>()
        for (child in element.childNodes()) {
            if (child is Element && child.normalName() == "blockquote") {
                if (state.enterNode(depth + 1) && prepareElement(child)) {
                    blocks += parseBlocks(child.childNodes(), depth + 2)
                }
            } else {
                // The title contains avatar and quote-control markup, not post body content.
                scanDiscardedNode(child, depth + 1)
            }
        }
        val text = blocks.plainText()
        if (blocks.isEmpty()) {
            state.releaseBlock()
            return null
        }
        return UiArticleBlock.Quote(
            text = text,
            attribution = safeAttributeText(element, "data-username"),
            blocks = blocks,
        )
    }

    private fun parseCodeBlock(
        element: Element,
        depth: Int,
    ): UiArticleBlock.Code? {
        val code = collectRawText(element.childNodes(), depth + 1)
        if (code.isEmpty() || !state.claimBlock()) return null
        val codeElement =
            element.childNodes().filterIsInstance<Element>().firstOrNull {
                it.normalName() == "code"
            }
        return UiArticleBlock.Code(
            code = code,
            language = codeElement?.codeLanguage(),
        )
    }

    private fun parseList(
        element: Element,
        depth: Int,
    ): UiArticleBlock.ListBlock? {
        if (!state.claimBlock()) return null
        val items = mutableListOf<UiArticleListItem>()
        for (child in element.childNodes()) {
            val listItem = child as? Element
            if (listItem?.normalName() != "li") {
                scanDiscardedNode(child, depth + 1)
                continue
            }
            if (!state.claimListItem()) break
            if (!state.enterNode(depth + 1) || !prepareElement(listItem)) continue
            val blocks = parseBlocks(listItem.childNodes(), depth + 2)
            if (blocks.isNotEmpty()) items += UiArticleListItem(blocks)
        }

        if (items.isEmpty()) {
            state.releaseBlock()
            return null
        }
        val ordered = element.normalName() == "ol"
        return UiArticleBlock.ListBlock(
            ordered = ordered,
            startIndex = if (ordered) element.safeListStart() else 1,
            items = items,
        )
    }

    private fun parseTable(
        element: Element,
        depth: Int,
    ): UiArticleBlock.Table? {
        if (!state.claimBlock()) return null
        val table = TableAccumulator()
        for (child in element.childNodes()) {
            parseTableNode(child, depth + 1, table)
            if (state.nodeBudgetExhausted) break
        }
        if (table.rows.isEmpty()) {
            state.releaseBlock()
            return null
        }
        return UiArticleBlock.Table(caption = table.caption, rows = table.rows)
    }

    private fun parseTableNode(
        node: Node,
        depth: Int,
        table: TableAccumulator,
    ) {
        if (!state.enterNode(depth)) return
        val element = node as? Element ?: return
        if (!prepareElement(element)) return
        when (element.normalName()) {
            "caption" -> {
                if (table.caption == null) {
                    table.caption = parseInlineChildren(element.childNodes(), depth + 1).plainTextOrNull()
                } else {
                    scanDiscardedChildren(element.childNodes(), depth + 1)
                }
            }

            "thead", "tbody", "tfoot" -> {
                for (child in element.childNodes()) parseTableNode(child, depth + 1, table)
            }

            "tr" -> {
                parseTableRow(element, depth, table)
            }

            else -> {
                for (child in element.childNodes()) parseTableNode(child, depth + 1, table)
            }
        }
    }

    private fun parseTableRow(
        row: Element,
        depth: Int,
        table: TableAccumulator,
    ) {
        val cells = mutableListOf<UiArticleTableCell>()
        for (child in row.childNodes()) {
            val cell = child as? Element
            if (cell == null || cell.normalName() !in TABLE_CELL_TAGS) {
                scanDiscardedNode(child, depth + 1)
                continue
            }
            if (!state.claimTableCell()) break
            if (!state.enterNode(depth + 1) || !prepareElement(cell)) continue
            val inlines = parseInlineChildren(cell.childNodes(), depth + 2)
            cells +=
                UiArticleTableCell(
                    text = inlines.plainText(),
                    inlines = inlines.values,
                    isHeader = cell.normalName() == "th",
                    columnSpan = safeTableSpan(cell, "colspan"),
                    rowSpan = safeTableSpan(cell, "rowspan"),
                )
        }
        if (cells.isNotEmpty()) table.rows += UiArticleTableRow(cells)
    }

    private fun parseDetailsSpoiler(
        element: Element,
        depth: Int,
    ): UiArticleBlock.Spoiler? {
        if (!state.claimBlock()) return null
        var summary: String? = null
        val bodyNodes = mutableListOf<Node>()
        for (child in element.childNodes()) {
            if (summary == null && child is Element && child.normalName() == "summary") {
                if (state.enterNode(depth + 1) && prepareElement(child)) {
                    summary = parseInlineChildren(child.childNodes(), depth + 2).plainTextOrNull()
                }
            } else {
                bodyNodes += child
            }
        }
        val blocks = parseBlocks(bodyNodes, depth + 1)
        val text = blocks.plainText()
        if (blocks.isEmpty()) {
            state.releaseBlock()
            return null
        }
        return UiArticleBlock.Spoiler(text = text, summary = summary, blocks = blocks)
    }

    private fun parseBlockSpoiler(
        element: Element,
        depth: Int,
    ): UiArticleBlock.Spoiler? {
        if (!state.claimBlock()) return null
        val blocks = parseBlocks(element.childNodes(), depth + 1)
        val text = blocks.plainText()
        if (blocks.isEmpty()) {
            state.releaseBlock()
            return null
        }
        return UiArticleBlock.Spoiler(
            text = text,
            summary = safeAttributeText(element, "data-spoiler-title"),
            blocks = blocks,
        )
    }

    private fun parseLinkedImageBlock(
        link: Element,
        depth: Int,
    ): UiArticleBlock.Image? {
        val destination = safeUrl(link.attr("href"))
        if (destination == null) {
            state.removedUnsafeContent = true
            scanDiscardedChildren(link.childNodes(), depth + 1)
            return null
        }
        val image =
            link.childNodes().filterIsInstance<Element>().singleOrNull {
                it.normalName() == "img"
            } ?: return null
        return parseImageBlock(image, depth + 1, destination, entered = false)
    }

    private fun parseImageBlock(
        element: Element,
        depth: Int,
        linkUrl: String?,
        entered: Boolean,
    ): UiArticleBlock.Image? {
        if (!entered && (!state.enterNode(depth) || !prepareElement(element))) return null
        val image = parseImage(element, linkUrl) ?: return null
        if (!state.claimBlock()) return null
        return UiArticleBlock.Image(
            url = image.url,
            altText = image.altText,
            title = image.title,
            linkUrl = image.linkUrl,
        )
    }

    private fun parseInlineChildren(
        nodes: List<Node>,
        depth: Int,
    ): InlineCollector =
        InlineCollector().also { collector ->
            for (node in nodes) {
                parseInlineNode(node, depth, collector)
                if (state.nodeBudgetExhausted) break
            }
        }

    private fun parseInlineNode(
        node: Node,
        depth: Int,
        collector: InlineCollector,
    ) {
        if (!state.enterNode(depth)) return
        if (node is TextNode) {
            collector.appendText(state.takeText(node.getWholeText()))
            return
        }
        val element = node as? Element ?: return
        if (!prepareElement(element)) return

        when (element.normalName()) {
            "br" -> {
                collector.appendLineBreak()
            }

            "a" -> {
                parseInlineLink(element, depth, collector)
            }

            "img" -> {
                parseImage(element, linkUrl = null)?.let(collector::append)
            }

            "code" -> {
                val code = collectRawText(element.childNodes(), depth + 1)
                if (code.isNotEmpty()) collector.append(UiArticleInline.Code(code))
            }

            else -> {
                if (element.isSpoilerElement()) {
                    val nested = parseInlineChildren(element.childNodes(), depth + 1)
                    val text = nested.plainText()
                    if (text.isNotBlank()) {
                        collector.append(
                            UiArticleInline.Spoiler(
                                text = text,
                                inlines = nested.values,
                            ),
                        )
                    }
                } else {
                    val addBoundary = element.isBlock() && collector.hasContent
                    if (addBoundary) collector.appendLineBreak()
                    for (child in element.childNodes()) {
                        parseInlineNode(child, depth + 1, collector)
                    }
                    if (addBoundary) collector.appendLineBreak()
                }
            }
        }
    }

    private fun parseInlineLink(
        element: Element,
        depth: Int,
        collector: InlineCollector,
    ) {
        val rawHref = element.attr("href")
        val destination = safeUrl(rawHref)
        val nested = parseInlineChildren(element.childNodes(), depth + 1)
        if (destination == null) {
            if (rawHref.isNotEmpty()) state.removedUnsafeContent = true
            collector.appendUnlinked(nested)
            return
        }
        collector.appendLinked(nested, destination)
    }

    private fun parseImage(
        element: Element,
        linkUrl: String?,
    ): UiArticleInline.Image? {
        val rawSource = element.attr("src")
        val source = safeUrl(rawSource)
        if (source == null) {
            if (rawSource.isNotEmpty()) state.removedUnsafeContent = true
            return null
        }
        return UiArticleInline.Image(
            url = source,
            altText = safeAttributeText(element, "alt"),
            title = safeAttributeText(element, "title"),
            linkUrl = linkUrl,
        )
    }

    private fun collectRawText(
        nodes: List<Node>,
        depth: Int,
    ): String =
        buildString {
            for (node in nodes) {
                if (!state.enterNode(depth)) break
                if (node is TextNode) {
                    append(state.takeText(node.getWholeText()))
                    continue
                }
                val element = node as? Element ?: continue
                if (!prepareElement(element)) continue
                if (element.normalName() == "br") {
                    append(state.takeText("\n"))
                } else {
                    append(collectRawText(element.childNodes(), depth + 1))
                }
            }
        }

    private fun safeAttributeText(
        element: Element,
        name: String,
    ): String? {
        val value = element.attr(name)
        if (value.isEmpty()) return null
        return state.takeText(value).normalizeProse().takeIf(String::isNotEmpty)
    }

    private fun safeTableSpan(
        element: Element,
        name: String,
    ): Int {
        val raw = element.attr(name)
        if (raw.isEmpty()) return 1
        val parsed = raw.toIntOrNull()
        if (parsed == null || parsed < 1) {
            state.removedUnsafeContent = true
            return 1
        }
        if (parsed > MAX_TABLE_SPAN) {
            state.wasTruncated = true
            return MAX_TABLE_SPAN
        }
        return parsed
    }

    private fun prepareElement(element: Element): Boolean {
        if (element.attributesSize() > state.limits.maxAttributesPerElement) {
            state.wasTruncated = true
            state.removedUnsafeContent = true
            return false
        }
        if (element.normalName() in DANGEROUS_ELEMENTS) {
            state.removedUnsafeContent = true
            return false
        }
        if (element.hasAttributes()) {
            for (attribute in element.attributes()) {
                val name = attribute.key.lowercase()
                if (name.startsWith("on") || name in DANGEROUS_ATTRIBUTES) {
                    // Attributes are never copied, but recording their removal aids diagnostics and tests.
                    state.removedUnsafeContent = true
                }
            }
        }
        return true
    }

    private fun scanDiscardedChildren(
        nodes: List<Node>,
        depth: Int,
    ) {
        for (node in nodes) scanDiscardedNode(node, depth)
    }

    private fun scanDiscardedNode(
        node: Node,
        depth: Int,
    ) {
        if (!state.enterNode(depth)) return
        val element = node as? Element ?: return
        if (!prepareElement(element)) return
        scanDiscardedChildren(element.childNodes(), depth + 1)
    }

    private fun safeUrl(rawValue: String): String? {
        if (rawValue.isEmpty() || rawValue.length > state.limits.maxUrlChars) {
            if (rawValue.length > state.limits.maxUrlChars) state.wasTruncated = true
            return null
        }
        if (rawValue != rawValue.trim() || rawValue.any(Char::isForbiddenUrlCharacter)) return null
        if (rawValue.startsWith("//") || rawValue.hasEncodedForbiddenOctet()) return null

        return try {
            val colon = rawValue.indexOf(':')
            val firstPathSeparator = rawValue.indexOfAny(charArrayOf('/', '?', '#')).orMaxValue()
            val hasScheme = colon >= 0 && colon < firstPathSeparator
            val builder =
                if (hasScheme) {
                    if (!rawValue.startsWith("https://", ignoreCase = true)) return null
                    if (
                        rawValue.length == HTTPS_AUTHORITY_OFFSET ||
                        rawValue[HTTPS_AUTHORITY_OFFSET] in URL_AUTHORITY_DELIMITERS
                    ) {
                        return null
                    }
                    URLBuilder(rawValue).also { absoluteBuilder ->
                        // URLBuilder otherwise fills an absent host from the process origin at build time.
                        if (absoluteBuilder.host.isEmpty()) return null
                    }
                } else {
                    URLBuilder(FORUM_ORIGIN).takeFrom(rawValue)
                }
            builder.pathSegments = builder.pathSegments.withoutDotSegments()
            val url = builder.build()
            if (
                url.protocol != URLProtocol.HTTPS ||
                url.host.isEmpty() ||
                url.user != null ||
                url.password != null
            ) {
                null
            } else {
                url.toString()
            }
        } catch (_: Exception) {
            null
        }
    }
}

private class ParseState(
    val limits: DiscourseRichTextLimits,
    inputWasTruncated: Boolean,
) {
    var wasTruncated: Boolean = inputWasTruncated
    var removedUnsafeContent: Boolean = false
    var nodeBudgetExhausted: Boolean = false
        private set

    private var visitedNodes: Int = 0
    private var producedBlocks: Int = 0
    private var producedTextChars: Int = 0
    private var producedListItems: Int = 0
    private var producedTableCells: Int = 0

    fun enterNode(depth: Int): Boolean {
        if (depth > limits.maxDepth || visitedNodes >= limits.maxNodes) {
            wasTruncated = true
            if (visitedNodes >= limits.maxNodes) nodeBudgetExhausted = true
            return false
        }
        visitedNodes += 1
        return true
    }

    fun claimBlock(): Boolean {
        if (producedBlocks >= limits.maxBlocks) {
            wasTruncated = true
            return false
        }
        producedBlocks += 1
        return true
    }

    fun releaseBlock() {
        check(producedBlocks > 0)
        producedBlocks -= 1
    }

    fun claimListItem(): Boolean {
        if (producedListItems >= limits.maxListItems) {
            wasTruncated = true
            return false
        }
        producedListItems += 1
        return true
    }

    fun claimTableCell(): Boolean {
        if (producedTableCells >= limits.maxTableCells) {
            wasTruncated = true
            return false
        }
        producedTableCells += 1
        return true
    }

    fun takeText(value: String): String {
        if (value.isEmpty()) return value
        val remaining = limits.maxTextChars - producedTextChars
        if (remaining <= 0) {
            wasTruncated = true
            return ""
        }
        val end = safeUtf16PrefixEnd(value, minOf(value.length, remaining))
        producedTextChars += end
        if (end < value.length) wasTruncated = true
        return value.substring(0, end)
    }
}

private class InlineCollector {
    val values: MutableList<UiArticleInline> = mutableListOf()
    var hasContent: Boolean = false
        private set
    private var pendingWhitespace: Boolean = false
    private var leadingWhitespace: Boolean = false

    fun appendText(text: String) {
        for (character in text) {
            if (character.isWhitespace()) {
                if (!hasContent) leadingWhitespace = true
                pendingWhitespace = true
            } else {
                flushPendingWhitespace()
                appendTextCharacter(character)
                hasContent = true
            }
        }
    }

    fun appendLineBreak() {
        if (!hasContent) return
        pendingWhitespace = false
        val lastText = values.lastOrNull() as? UiArticleInline.Text
        if (lastText?.text?.endsWith('\n') == true) return
        appendTextValue("\n")
    }

    fun append(value: UiArticleInline) {
        flushPendingWhitespace()
        values += value
        hasContent = true
    }

    fun appendUnlinked(nested: InlineCollector) {
        if (nested.leadingWhitespace) noteWhitespace()
        for (value in nested.values) {
            when (value) {
                is UiArticleInline.Text -> appendText(value.text)
                else -> append(value)
            }
        }
        if (nested.pendingWhitespace) noteWhitespace()
    }

    fun appendLinked(
        nested: InlineCollector,
        destination: String,
    ) {
        if (nested.leadingWhitespace) noteWhitespace()
        for (value in nested.values) {
            when (value) {
                is UiArticleInline.Image -> append(value.copy(linkUrl = destination))
                is UiArticleInline.Text -> append(UiArticleInline.Link(value.text, destination))
                is UiArticleInline.Link -> append(UiArticleInline.Link(value.text, destination))
                is UiArticleInline.Code -> append(UiArticleInline.Link(value.code, destination))
                is UiArticleInline.Spoiler -> append(UiArticleInline.Link(value.text, destination))
            }
        }
        if (nested.pendingWhitespace) noteWhitespace()
    }

    fun plainText(): String = values.joinToString(separator = "") { it.plainText() }

    fun plainTextOrNull(): String? = plainText().takeIf(String::isNotBlank)

    fun toParagraph(state: ParseState): UiArticleBlock.Paragraph? {
        if (!hasContent) return null
        val text = plainText()
        if (text.isBlank() || !state.claimBlock()) return null
        return UiArticleBlock.Paragraph(text = text, inlines = values.toList())
    }

    private fun noteWhitespace() {
        pendingWhitespace = true
    }

    private fun flushPendingWhitespace() {
        if (pendingWhitespace && hasContent) appendTextValue(" ")
        pendingWhitespace = false
    }

    private fun appendTextCharacter(character: Char) {
        val last = values.lastOrNull() as? UiArticleInline.Text
        if (last == null) {
            values += UiArticleInline.Text(character.toString())
        } else {
            values[values.lastIndex] = last.copy(text = last.text + character)
        }
    }

    private fun appendTextValue(text: String) {
        val last = values.lastOrNull() as? UiArticleInline.Text
        if (last == null) {
            values += UiArticleInline.Text(text)
        } else {
            values[values.lastIndex] = last.copy(text = last.text + text)
        }
    }
}

private data class TableAccumulator(
    var caption: String? = null,
    val rows: MutableList<UiArticleTableRow> = mutableListOf(),
)

private data class BoundedInput(
    val value: String,
    val wasTruncated: Boolean,
)

private fun String.boundedUtf8Prefix(limits: DiscourseRichTextLimits): BoundedInput {
    var end = safeUtf16PrefixEnd(this, minOf(length, limits.maxInputChars))
    var wasTruncated = end < length
    if (substring(0, end).encodeToByteArray().size > limits.maxInputBytes) {
        var low = 0
        var high = end
        while (low < high) {
            val middle = (low + high + 1) / 2
            val safeMiddle = safeUtf16PrefixEnd(this, middle)
            if (substring(0, safeMiddle).encodeToByteArray().size <= limits.maxInputBytes) {
                low = middle
            } else {
                high = middle - 1
            }
        }
        end = safeUtf16PrefixEnd(this, low)
        wasTruncated = true
    }
    return BoundedInput(substring(0, end), wasTruncated)
}

private fun safeUtf16PrefixEnd(
    value: String,
    requestedEnd: Int,
): Int {
    var end = requestedEnd.coerceIn(0, value.length)
    if (end in 1 until value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) {
        end -= 1
    }
    return end
}

private fun UiArticleInline.plainText(): String =
    when (this) {
        is UiArticleInline.Text -> text
        is UiArticleInline.Link -> text
        is UiArticleInline.Code -> code
        is UiArticleInline.Image -> altText.orEmpty()
        is UiArticleInline.Spoiler -> text
    }

private fun List<UiArticleBlock>.plainText(): String =
    mapNotNull { block ->
        when (block) {
            is UiArticleBlock.Paragraph -> {
                block.text
            }

            is UiArticleBlock.Quote -> {
                block.text
            }

            is UiArticleBlock.Code -> {
                block.code
            }

            is UiArticleBlock.Image -> {
                block.altText
            }

            is UiArticleBlock.ListBlock -> {
                block.items.joinToString("\n") { item -> item.blocks.plainText() }
            }

            is UiArticleBlock.Table -> {
                block.rows.joinToString("\n") { row -> row.cells.joinToString("\t") { it.text } }
            }

            is UiArticleBlock.Spoiler -> {
                block.text
            }
        }?.takeIf(String::isNotBlank)
    }.joinToString("\n")

private fun Element.hasOnlyLinkedImage(): Boolean {
    var imageCount = 0
    for (child in childNodes()) {
        when (child) {
            is TextNode -> {
                if (!child.isBlank()) return false
            }

            is Element -> {
                if (child.normalName() != "img") return false
                imageCount += 1
            }

            else -> {
                return false
            }
        }
    }
    return imageCount == 1
}

private fun Element.isSpoilerElement(): Boolean =
    classNames().any { className ->
        val normalized = className.lowercase()
        normalized == "spoiler" || normalized == "blur-spoiler"
    }

private fun Element.isDiscourseQuoteElement(): Boolean =
    normalName() == "aside" &&
        (hasClass("quote") || attr("data-username").isNotEmpty())

private fun Element.codeLanguage(): String? {
    val language =
        classNames().firstNotNullOfOrNull { className ->
            when {
                className.startsWith("language-") -> className.removePrefix("language-")
                className.startsWith("lang-") -> className.removePrefix("lang-")
                else -> null
            }
        } ?: return null
    return language.take(MAX_CODE_LANGUAGE_CHARS).takeIf { candidate ->
        candidate.isNotEmpty() && candidate.all { it.isLetterOrDigit() || it in CODE_LANGUAGE_PUNCTUATION }
    }
}

private fun Element.safeListStart(): Int {
    val start = attr("start").toIntOrNull() ?: return 1
    return start.coerceIn(1, MAX_LIST_START)
}

private fun String.normalizeProse(): String {
    val result = StringBuilder(length)
    var pendingWhitespace = false
    for (character in this) {
        if (character.isWhitespace()) {
            pendingWhitespace = result.isNotEmpty()
        } else {
            if (pendingWhitespace) result.append(' ')
            result.append(character)
            pendingWhitespace = false
        }
    }
    return result.toString()
}

private fun Char.isForbiddenUrlCharacter(): Boolean = this == '\\' || isWhitespace() || code < 0x20 || code == 0x7f

private fun String.hasEncodedForbiddenOctet(): Boolean {
    var index = 0
    while (index + 2 < length) {
        if (this[index] == '%') {
            val value = substring(index + 1, index + 3).toIntOrNull(radix = 16)
            if (value != null && (value < 0x20 || value == 0x5c || value == 0x7f)) return true
            index += 3
        } else {
            index += 1
        }
    }
    return false
}

private fun Int.orMaxValue(): Int = if (this < 0) Int.MAX_VALUE else this

private fun List<String>.withoutDotSegments(): List<String> {
    val normalized = mutableListOf<String>()
    for (segment in this) {
        when (segment) {
            "." -> {
                continue
            }

            ".." -> {
                if (normalized.size > 1 || normalized.firstOrNull()?.isNotEmpty() == true) {
                    normalized.removeAt(normalized.lastIndex)
                }
            }

            else -> {
                normalized += segment
            }
        }
    }
    return normalized
}

private const val FORUM_ORIGIN = "https://linux.do/"
private const val HTTPS_AUTHORITY_OFFSET = 8
private const val MAX_CODE_LANGUAGE_CHARS = 32
private const val MAX_LIST_START = 1_000_000
private const val MAX_TABLE_SPAN = 100
private val CODE_LANGUAGE_PUNCTUATION = setOf('_', '-', '+', '#', '.')
private val URL_AUTHORITY_DELIMITERS = setOf('/', '?', '#')
private val TABLE_CELL_TAGS = setOf("td", "th")
private val DANGEROUS_ATTRIBUTES = setOf("style", "srcdoc")
private val DANGEROUS_ELEMENTS =
    setOf(
        "applet",
        "audio",
        "base",
        "button",
        "canvas",
        "embed",
        "form",
        "frame",
        "frameset",
        "iframe",
        "input",
        "link",
        "math",
        "meta",
        "noscript",
        "object",
        "portal",
        "script",
        "select",
        "source",
        "style",
        "svg",
        "template",
        "textarea",
        "track",
        "video",
    )
