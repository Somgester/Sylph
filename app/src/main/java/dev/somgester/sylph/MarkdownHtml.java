package dev.somgester.sylph;

import org.jsoup.Jsoup;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

// Clean the complete body so paired inline tags and HTML blocks retain their structure.
final class MarkdownHtml {

    private final Cleaner cleaner = new Cleaner(new Safelist()
            .addTags("a", "abbr", "article", "b", "blockquote", "br", "caption", "center", "cite", "code",
                    "dd", "del", "details", "div", "dl", "dt", "em", "figcaption", "figure", "footer",
                    "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "i", "img", "kbd", "li", "mark",
                    "ol", "p", "pre", "q", "s", "samp", "section", "small", "span", "strike", "strong",
                    "sub", "summary", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "u",
                    "ul", "var", "wbr")
            .addAttributes(":all", "title", "dir", "lang")
            .addAttributes("code", "class")
            .addAttributes("span", "class")
            .addAttributes("details", "open")
            .addAttributes("ol", "start", "reversed", "type")
            .addAttributes("li", "value")
            .addAttributes("img", "alt")
            .addAttributes("td", "colspan", "rowspan", "align")
            .addAttributes("th", "colspan", "rowspan", "scope", "align")
            .addAttributes("div", "align")
            .addAttributes("p", "align")
            .addAttributes("h1", "align")
            .addAttributes("h2", "align")
            .addAttributes("h3", "align")
            .addAttributes("h4", "align")
            .addAttributes("h5", "align")
            .addAttributes("h6", "align")
            .addAttributes("table", "align"));

    String clean(String html) {
        var document = cleaner.clean(Jsoup.parseBodyFragment(html));
        document.outputSettings().prettyPrint(false);
        for (var link : document.select("a")) {
            String title = link.attr("title");
            link.tagName("span").clearAttributes();
            link.addClass("markdown-link");
            if (!title.isEmpty()) {
                link.attr("title", title);
            }
        }
        for (var image : document.select("img")) {
            String alt = image.attr("alt");
            image.tagName("span").clearAttributes();
            image.addClass("markdown-image");
            image.text("[Image: " + alt + "]");
        }
        return document.body().html();
    }
}
