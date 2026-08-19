package dev.dimension.flare.data.network.discourse.content

import dev.dimension.flare.ui.model.UiArticleBlock
import dev.dimension.flare.ui.model.UiArticleInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscourseCookedHtmlParserTest {
    @Test
    fun mapsSupportedCookedHtmlToPlatformNeutralRichText() {
        val document =
            DiscourseCookedHtmlParser().parseDocument(
                """
                <p>Hello <a href="/t/safe-topic/42">topic</a>, <code>val answer = 42</code>
                  and <span class="spoiler">hidden text</span>.</p>
                <a href="https://linux.do/t/image-topic/7">
                  <img src="/uploads/default/original/1X/safe.png" alt="diagram" title="Safe image">
                </a>
                <blockquote data-username="reader"><p>Quoted <strong>message</strong>.</p></blockquote>
                <pre><code class="language-kotlin">fun main() {
                    println(&quot;safe&quot;)
                }</code></pre>
                <ol start="3">
                  <li>First</li>
                  <li>Second<ul><li>Nested</li></ul></li>
                </ol>
                <table>
                  <caption>Build matrix</caption>
                  <thead><tr><th>Target</th><th>Status</th></tr></thead>
                  <tbody><tr><td rowspan="2">Android</td><td colspan="2">Ready</td></tr></tbody>
                </table>
                <details><summary>Reveal</summary><p>Details body</p></details>
                """.trimIndent(),
            )

        assertFalse(document.wasTruncated)
        assertFalse(document.removedUnsafeContent)
        assertEquals(7, document.blocks.size)

        val paragraph = assertIs<UiArticleBlock.Paragraph>(document.blocks[0])
        assertEquals("Hello topic, val answer = 42 and hidden text.", paragraph.text)
        assertTrue(paragraph.inlines.any { it is UiArticleInline.Link && it.url == "https://linux.do/t/safe-topic/42" })
        assertTrue(paragraph.inlines.any { it is UiArticleInline.Code && it.code == "val answer = 42" })
        assertTrue(paragraph.inlines.any { it is UiArticleInline.Spoiler && it.text == "hidden text" })

        val image = assertIs<UiArticleBlock.Image>(document.blocks[1])
        assertEquals("https://linux.do/uploads/default/original/1X/safe.png", image.url)
        assertEquals("https://linux.do/t/image-topic/7", image.linkUrl)
        assertEquals("diagram", image.altText)

        val quote = assertIs<UiArticleBlock.Quote>(document.blocks[2])
        assertEquals("reader", quote.attribution)
        assertEquals("Quoted message.", quote.text)
        assertIs<UiArticleBlock.Paragraph>(quote.blocks.single())

        val code = assertIs<UiArticleBlock.Code>(document.blocks[3])
        assertEquals("kotlin", code.language)
        assertTrue(code.code.contains("println(\"safe\")"))

        val list = assertIs<UiArticleBlock.ListBlock>(document.blocks[4])
        assertTrue(list.ordered)
        assertEquals(3, list.startIndex)
        assertEquals(2, list.items.size)
        assertIs<UiArticleBlock.ListBlock>(list.items[1].blocks[1])

        val table = assertIs<UiArticleBlock.Table>(document.blocks[5])
        assertEquals("Build matrix", table.caption)
        assertEquals(2, table.rows.size)
        assertTrue(table.rows[0].cells[0].isHeader)
        assertEquals(2, table.rows[1].cells[0].rowSpan)
        assertEquals(2, table.rows[1].cells[1].columnSpan)

        val spoiler = assertIs<UiArticleBlock.Spoiler>(document.blocks[6])
        assertEquals("Reveal", spoiler.summary)
        assertEquals("Details body", spoiler.text)
    }

    @Test
    fun promotesImageOnlyParagraphsWithoutReorderingMixedInlineContent() {
        val blocks =
            DiscourseCookedHtmlParser().parse(
                """
                <p>
                  <a href="/t/image-topic/7">
                    <img src="/uploads/default/original/1X/linked.png" alt="diagram" title="Safe image">
                  </a>
                </p>
                <p><img src="/uploads/default/original/1X/plain.png" alt="plain"></p>
                <p>before <img src="/uploads/default/original/1X/inline.png" alt="middle"> after</p>
                """.trimIndent(),
            )

        val linkedImage = assertIs<UiArticleBlock.Image>(blocks[0])
        assertEquals("https://linux.do/uploads/default/original/1X/linked.png", linkedImage.url)
        assertEquals("https://linux.do/t/image-topic/7", linkedImage.linkUrl)
        assertEquals("diagram", linkedImage.altText)
        assertEquals("Safe image", linkedImage.title)

        val plainImage = assertIs<UiArticleBlock.Image>(blocks[1])
        assertEquals("https://linux.do/uploads/default/original/1X/plain.png", plainImage.url)
        assertNull(plainImage.linkUrl)

        val mixedParagraph = assertIs<UiArticleBlock.Paragraph>(blocks[2])
        assertEquals("before middle after", mixedParagraph.text)
        assertEquals(
            listOf(UiArticleInline.Text::class, UiArticleInline.Image::class, UiArticleInline.Text::class),
            mixedParagraph.inlines.map { it::class },
        )
    }

    @Test
    fun retainsImageOnlyQuoteAndSpoilerWithoutAltText() {
        val blocks =
            DiscourseCookedHtmlParser().parse(
                """
                <blockquote><p><img src="/uploads/default/original/1X/quote.png"></p></blockquote>
                <details>
                  <summary>Reveal image</summary>
                  <p><img src="/uploads/default/original/1X/spoiler.png"></p>
                </details>
                """.trimIndent(),
            )

        val quote = assertIs<UiArticleBlock.Quote>(blocks[0])
        assertEquals("", quote.text)
        assertEquals(
            "https://linux.do/uploads/default/original/1X/quote.png",
            assertIs<UiArticleBlock.Image>(quote.blocks.single()).url,
        )

        val spoiler = assertIs<UiArticleBlock.Spoiler>(blocks[1])
        assertEquals("", spoiler.text)
        assertEquals("Reveal image", spoiler.summary)
        assertEquals(
            "https://linux.do/uploads/default/original/1X/spoiler.png",
            assertIs<UiArticleBlock.Image>(spoiler.blocks.single()).url,
        )
    }

    @Test
    fun removesActiveSubtreesAttributesAndUnsafeUrls() {
        val document =
            DiscourseCookedHtmlParser().parseDocument(
                """
                <script>SCRIPT_SECRET</script>
                <style>STYLE_SECRET</style>
                <iframe src="https://linux.do">IFRAME_SECRET</iframe>
                <object>OBJECT_SECRET</object>
                <form>FORM_SECRET<input value="INPUT_SECRET"></form>
                <svg><text>SVG_SECRET</text></svg>
                <p onclick="steal()" style="display:none">Visible text</p>
                <p>
                  <a href="java&#x73;cript:alert(1)">javascript label</a>
                  <a href="vbscript:alert(1)">vbscript label</a>
                  <a href="data:text/html,bad">data label</a>
                  <a href="file:///tmp/bad">file label</a>
                  <a href="http://linux.do/insecure">http label</a>
                  <a href="https://user:password@linux.do/private">userinfo label</a>
                </p>
                <img src="http://linux.do/insecure.png" alt="http image">
                """.trimIndent(),
            )

        assertTrue(document.removedUnsafeContent)
        val visibleText = document.blocks.plainText()
        assertTrue(visibleText.contains("Visible text"))
        assertTrue(visibleText.contains("javascript label"))
        assertTrue(visibleText.contains("userinfo label"))
        assertFalse(visibleText.contains("SCRIPT_SECRET"))
        assertFalse(visibleText.contains("STYLE_SECRET"))
        assertFalse(visibleText.contains("IFRAME_SECRET"))
        assertFalse(visibleText.contains("OBJECT_SECRET"))
        assertFalse(visibleText.contains("FORM_SECRET"))
        assertFalse(visibleText.contains("SVG_SECRET"))
        assertTrue(document.blocks.flatMap(UiArticleBlock::allInlines).none { it is UiArticleInline.Link })
        assertTrue(document.blocks.none { it is UiArticleBlock.Image })
    }

    @Test
    fun mapsDiscourseAsideQuoteWithoutLeakingItsControlTitle() {
        val document =
            DiscourseCookedHtmlParser().parseDocument(
                """
                <aside class="quote no-group" data-username="quoted-user" data-post="12" data-topic="34">
                  <div class="title">
                    <div class="quote-controls">CONTROL_LABEL</div>
                    quoted-user:
                  </div>
                  <blockquote><p>Actual quoted body.</p></blockquote>
                </aside>
                """.trimIndent(),
            )

        val quote = assertIs<UiArticleBlock.Quote>(document.blocks.single())
        assertEquals("quoted-user", quote.attribution)
        assertEquals("Actual quoted body.", quote.text)
        assertFalse(quote.text.contains("CONTROL_LABEL"))
        assertFalse(document.wasTruncated)
        assertFalse(document.removedUnsafeContent)
    }

    @Test
    fun malformedHtmlIsRepairedWithoutRawHtmlFallback() {
        val document =
            DiscourseCookedHtmlParser().parseDocument(
                "<p>before <strong>bold<table><tr><td>cell<script>secret",
            )

        assertTrue(document.removedUnsafeContent)
        assertTrue(document.blocks.plainText().contains("before bold"))
        assertFalse(document.blocks.plainText().contains("secret"))
        assertFalse(document.blocks.plainText().contains("<script>"))
    }

    @Test
    fun rejectsObfuscatedProtocolsControlCharactersAndOversizedUrls() {
        val parser =
            DiscourseCookedHtmlParser(
                DiscourseRichTextLimits(maxUrlChars = 48),
            )
        val document =
            parser.parseDocument(
                """
                <p>
                  <a href="java&#115;cript:alert(1)">entity</a>
                  <a href="https://linux.do/%0dheader">encoded control</a>
                  <a href="https://linux.do/\\evil">backslash</a>
                  <a href="https://linux.do/${"x".repeat(80)}">oversized</a>
                  <a href="../safe-relative">relative</a>
                </p>
                """.trimIndent(),
            )

        val paragraph = assertIs<UiArticleBlock.Paragraph>(document.blocks.single())
        val links = paragraph.inlines.filterIsInstance<UiArticleInline.Link>()
        assertEquals(1, links.size)
        assertEquals("relative", links.single().text)
        assertEquals("https://linux.do/safe-relative", links.single().url)
        assertTrue(document.removedUnsafeContent)
        assertTrue(document.wasTruncated)
    }

    @Test
    fun enforcesTraversalAndOutputBudgets() {
        val limits =
            DiscourseRichTextLimits(
                maxInputChars = 512,
                maxInputBytes = 512,
                maxNodes = 12,
                maxDepth = 3,
                maxBlocks = 3,
                maxTextChars = 18,
                maxUrlChars = 128,
                maxAttributesPerElement = 2,
                maxListItems = 1,
                maxTableCells = 1,
            )
        val document =
            DiscourseCookedHtmlParser(limits).parseDocument(
                """
                <p>1234567890abcdefghij</p>
                <div><div><div><div>too deep</div></div></div></div>
                <ul><li>one</li><li>two</li></ul>
                <table><tr><td>A</td><td>B</td></tr></table>
                """.trimIndent(),
            )

        assertTrue(document.wasTruncated)
        assertTrue(document.blocks.size <= limits.maxBlocks)
        assertTrue(document.blocks.plainText().length <= limits.maxTextChars)
    }

    @Test
    fun rejectsElementWhoseAttributeCountExceedsBudget() {
        val document =
            DiscourseCookedHtmlParser(
                DiscourseRichTextLimits(maxAttributesPerElement = 2),
            ).parseDocument("<p a=\"1\" b=\"2\" c=\"3\">must not survive</p><p>safe</p>")

        assertTrue(document.wasTruncated)
        assertTrue(document.removedUnsafeContent)
        assertEquals("safe", assertIs<UiArticleBlock.Paragraph>(document.blocks.single()).text)
    }

    @Test
    fun fixedForumOriginCannotBeChangedByBaseElement() {
        val document =
            DiscourseCookedHtmlParser().parseDocument(
                "<base href=\"https://attacker.invalid/\"><p><a href=\"relative-topic\">safe</a></p>",
            )

        val paragraph = assertIs<UiArticleBlock.Paragraph>(document.blocks.single())
        val link = assertIs<UiArticleInline.Link>(paragraph.inlines.single())
        assertEquals("https://linux.do/relative-topic", link.url)
    }

    @Test
    fun absoluteHttpsUrlRequiresAuthorityAndRejectsUserInfo() {
        val document =
            DiscourseCookedHtmlParser().parseDocument(
                """
                <p>
                  <a href="https://">missing host</a>
                  <a href="https:///path">ambiguous host</a>
                  <a href="https://linux.do@attacker.invalid/path">userinfo</a>
                  <a href="https://cdn.example.test/image">valid external</a>
                </p>
                """.trimIndent(),
            )

        val paragraph = assertIs<UiArticleBlock.Paragraph>(document.blocks.single())
        val links = paragraph.inlines.filterIsInstance<UiArticleInline.Link>()
        assertEquals(listOf("valid external"), links.map(UiArticleInline.Link::text))
        assertEquals("https://cdn.example.test/image", links.single().url)
        assertTrue(document.removedUnsafeContent)
    }

    @Test
    fun encodedDotSegmentsAreResolvedUnderFixedForumOrigin() {
        val paragraph =
            assertIs<UiArticleBlock.Paragraph>(
                DiscourseCookedHtmlParser().parse("<p><a href=\"a/%2e%2e/topic\">topic</a></p>").single(),
            )
        val link = assertIs<UiArticleInline.Link>(paragraph.inlines.single())

        assertEquals("https://linux.do/topic", link.url)
    }

    @Test
    fun eachOutputBudgetIsEnforcedIndependently() {
        val nodeLimited =
            DiscourseCookedHtmlParser(
                DiscourseRichTextLimits(maxNodes = 2),
            ).parseDocument("<p><span>first</span><span>second</span></p>")
        assertTrue(nodeLimited.wasTruncated)

        val depthLimited =
            DiscourseCookedHtmlParser(
                DiscourseRichTextLimits(maxDepth = 2),
            ).parseDocument("<p><span><strong>too deep</strong></span></p>")
        assertTrue(depthLimited.wasTruncated)

        val blockLimited =
            DiscourseCookedHtmlParser(
                DiscourseRichTextLimits(maxBlocks = 1),
            ).parseDocument("<p>first</p><p>second</p>")
        assertTrue(blockLimited.wasTruncated)
        assertEquals("first", assertIs<UiArticleBlock.Paragraph>(blockLimited.blocks.single()).text)

        val textLimited =
            DiscourseCookedHtmlParser(
                DiscourseRichTextLimits(maxTextChars = 4),
            ).parseDocument("<p>abcdef</p>")
        assertTrue(textLimited.wasTruncated)
        assertEquals("abcd", assertIs<UiArticleBlock.Paragraph>(textLimited.blocks.single()).text)

        val listLimited =
            DiscourseCookedHtmlParser(
                DiscourseRichTextLimits(maxListItems = 1),
            ).parseDocument("<ul><li>first</li><li>second</li></ul>")
        assertTrue(listLimited.wasTruncated)
        assertEquals(1, assertIs<UiArticleBlock.ListBlock>(listLimited.blocks.single()).items.size)

        val tableLimited =
            DiscourseCookedHtmlParser(
                DiscourseRichTextLimits(maxTableCells = 1),
            ).parseDocument("<table><tr><td>first</td><td>second</td></tr></table>")
        assertTrue(tableLimited.wasTruncated)
        assertEquals(
            1,
            assertIs<UiArticleBlock.Table>(tableLimited.blocks.single())
                .rows
                .single()
                .cells.size,
        )
    }

    @Test
    fun inputCharacterBudgetIsAppliedBeforeDomConstruction() {
        val document =
            DiscourseCookedHtmlParser(
                DiscourseRichTextLimits(maxInputChars = 8),
            ).parseDocument("<p>abcdefghij</p>")

        assertTrue(document.wasTruncated)
        assertEquals("abcde", assertIs<UiArticleBlock.Paragraph>(document.blocks.single()).text)
    }

    @Test
    fun discourseBlockSpoilerProducesOneSafeWrapper() {
        val spoiler =
            assertIs<UiArticleBlock.Spoiler>(
                DiscourseCookedHtmlParser()
                    .parse(
                        "<div class=\"spoiler\"><div class=\"spoiler-content\"><p>hidden</p></div></div>",
                    ).single(),
            )

        assertEquals("hidden", spoiler.text)
        assertIs<UiArticleBlock.Paragraph>(spoiler.blocks.single())
    }

    @Test
    fun inputIsBoundedByUtf8BytesWithoutSplittingSurrogatePairs() {
        val document =
            DiscourseCookedHtmlParser(
                DiscourseRichTextLimits(
                    maxInputChars = 64,
                    maxInputBytes = 10,
                ),
            ).parseDocument("<p>😀😀😀tail</p>")

        assertTrue(document.wasTruncated)
        val text = document.blocks.plainText()
        assertEquals("😀", text)
        assertTrue(text.hasOnlyPairedSurrogates())
    }

    @Test
    fun invalidImageHasNoStructuredFallback() {
        val blocks = DiscourseCookedHtmlParser().parse("<img src=\"data:image/png;base64,AAAA\" alt=\"bad\">")

        assertTrue(blocks.isEmpty())
        assertNull(blocks.filterIsInstance<UiArticleBlock.Image>().singleOrNull())
    }
}

private fun List<UiArticleBlock>.plainText(): String =
    joinToString("\n") { block ->
        when (block) {
            is UiArticleBlock.Paragraph -> block.text
            is UiArticleBlock.Quote -> block.text
            is UiArticleBlock.Code -> block.code
            is UiArticleBlock.Image -> block.altText.orEmpty()
            is UiArticleBlock.ListBlock -> block.items.joinToString("\n") { it.blocks.plainText() }
            is UiArticleBlock.Table -> block.rows.joinToString("\n") { row -> row.cells.joinToString(" ") { it.text } }
            is UiArticleBlock.Spoiler -> block.text
        }
    }

private fun UiArticleBlock.allInlines(): List<UiArticleInline> =
    when (this) {
        is UiArticleBlock.Paragraph -> inlines
        is UiArticleBlock.Quote -> blocks.flatMap(UiArticleBlock::allInlines)
        is UiArticleBlock.ListBlock -> items.flatMap { it.blocks.flatMap(UiArticleBlock::allInlines) }
        is UiArticleBlock.Table -> rows.flatMap { row -> row.cells.flatMap { it.inlines } }
        is UiArticleBlock.Spoiler -> blocks.flatMap(UiArticleBlock::allInlines)
        is UiArticleBlock.Code, is UiArticleBlock.Image -> emptyList()
    }

private fun String.hasOnlyPairedSurrogates(): Boolean {
    var index = 0
    while (index < length) {
        val current = this[index]
        when {
            current.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }

            current.isLowSurrogate() -> {
                return false
            }

            else -> {
                index += 1
            }
        }
    }
    return true
}
