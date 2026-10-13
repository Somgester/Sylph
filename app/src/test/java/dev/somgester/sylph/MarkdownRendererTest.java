package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void rendersCommonMarkdownTablesAndStrikethrough() {
        String source = "# Hello 世界\n\n**bold** *italic* `code` ~~old~~\n\n"
                + "> quote\n\n- first\n- second\n\n1. ordered\n\n"
                + "```java\nclass Example {}\n```\n\n| A | B |\n|---|---|\n| 1 | 2 |\n";
        String html = renderer.render(source, true);
        for (String expected : new String[] {"<h1>Hello 世界</h1>", "<strong>bold</strong>",
                "<em>italic</em>", "<code>code</code>", "<del>old</del>", "<blockquote>", "<ul>",
                "<ol>", "<table>", "<pre><code class=\"language-java\">class Example {}"}) {
            assertTrue(html.contains(expected), expected);
        }
    }

    @Test
    void rawHtmlLinksAndImagesCannotExecuteOrFetchResources() {
        String source = "<script>window.bad = true</script>\n\n"
                + "<iframe src=\"file:///etc/passwd\"></iframe>\n\n"
                + "[**click**](javascript:alert(1))\n\n![remote <image>](https://example.com/tracker.png)";
        String html = renderer.render(source, false);
        assertFalse(html.contains("window.bad"));
        assertTrue(html.contains("<strong>click</strong>"));
        assertTrue(html.contains("[Image: remote &lt;image&gt;]"));
        assertTrue(html.contains("default-src 'none'"));
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("<iframe"));
        assertFalse(html.contains("<a "));
        assertFalse(html.contains("<img"));
        assertFalse(html.contains("tracker.png"));
    }

    @Test
    void rendersHtmlBlocksInlineFormattingAndMarkdownTogether() {
        String source = "<div align=\"center\"><h2>HTML heading</h2><p>Paragraph <b>bold</b></p></div>\n\n"
                + "A <sup>2</sup> and <kbd>Ctrl</kbd> with **Markdown bold**.\n\n"
                + "<details open><summary>More</summary><p>Details text</p></details>\n\n"
                + "<table><tr><th colspan=\"2\">Header</th></tr><tr><td>A</td><td>B</td></tr></table>";
        var body = Jsoup.parse(renderer.render(source, true)).body();
        assertTrue(body.select("div[align=center] > h2").text().equals("HTML heading"));
        assertTrue(body.select("sup").text().equals("2"));
        assertTrue(body.select("kbd").text().equals("Ctrl"));
        assertTrue(body.select("strong").text().equals("Markdown bold"));
        assertTrue(body.select("details[open] > summary").text().equals("More"));
        assertTrue(body.select("th[colspan=2]").text().equals("Header"));
        assertTrue(body.select("td").size() == 2);
    }

    @Test
    void htmlInsideCodeRemainsLiteralAndKeepsWhitespace() {
        String source = "`<b>inline</b>`\n\n```html\n<div>\n  <script>example()</script>\n</div>\n```";
        var body = Jsoup.parse(renderer.render(source, true)).body();
        assertTrue(body.select("b, div, script").isEmpty());
        assertTrue(body.select("code").first().wholeText().equals("<b>inline</b>"));
        assertTrue(body.select("pre > code.language-html").first().wholeText()
                .equals("<div>\n  <script>example()</script>\n</div>\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<div onclick=\"alert(1)\" style=\"background:url(https://example.com/track)\">Visible</div>",
        "<script src=\"https://example.com/script.js\">bad()</script><p>Visible</p>",
        "<iframe src=\"file:///etc/passwd\"></iframe><p>Visible</p>",
        "<meta http-equiv=\"refresh\" content=\"0;url=https://example.com\"><p>Visible</p>",
        "<link rel=\"stylesheet\" href=\"https://example.com/style.css\"><p>Visible</p>",
        "<style>body{background:url(https://example.com/track)}</style><p>Visible</p>",
        "<form action=\"https://example.com\"><input autofocus><button>Visible</button></form>",
        "<svg onload=\"alert(1)\"><a href=\"javascript:alert(1)\">Visible</a></svg>",
        "<object data=\"file:///etc/passwd\"></object><embed src=\"https://example.com\"><p>Visible</p>",
        "<p><a href=\"javascript:alert(1)\" onclick=\"bad()\">Visible</a>"
            + "<img src=\"https://example.com/track\" srcset=\"file:///etc/passwd 2x\" "
            + "onerror=\"bad()\" alt=\"Alt\"></p>"
    })
    void htmlFormattingCannotIntroduceActiveContent(String source) {
        var body = Jsoup.parse(renderer.render(source, false)).body();
        assertTrue(body.text().contains("Visible"));
        assertTrue(body.select("script, iframe, meta, link, style, form, input, button, svg, object, embed, a, img")
                .isEmpty());
        for (var element : body.getAllElements()) {
            for (var attribute : element.attributes()) {
                String key = attribute.getKey();
                assertFalse(key.startsWith("on") || key.equals("style") || key.equals("src")
                        || key.equals("srcset") || key.equals("href") || key.equals("action"), key);
            }
        }
    }

    @Test
    void emptyContentAndBothThemesProduceSelfContainedPages() {
        String dark = renderer.render("", true);
        String light = renderer.render("", false);
        assertTrue(dark.contains("<body></body>"));
        assertTrue(dark.contains("background:#0d0f12"));
        assertTrue(light.contains("background:#faf9f5"));
        assertFalse(dark.contains("<link"));
    }
}
