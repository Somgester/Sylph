package dev.somgester.sylph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;

// Confined to the preview worker. Markdown can format content, not run code or load resources.
final class MarkdownRenderer {

    private static final String CSS = loadCss();

    private final MarkdownHtml html = new MarkdownHtml();

    private final Parser parser;

    private final HtmlRenderer renderer;

    MarkdownRenderer() {
        var extensions = List.of(TablesExtension.create(), StrikethroughExtension.create());
        parser = Parser.builder().extensions(extensions).build();
        renderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(false).sanitizeUrls(true)
                .nodeRendererFactory(InertLinks::new).build();
    }

    String render(String markdown, boolean dark) {
        String palette = dark
                ? "body{background:#0d0f12;color:#e5e7eb;}pre,code{background:#1c2029;}"
                    + "blockquote{color:#afbdaf;}a,.markdown-link{color:#90bde8;}"
                    + "th,td,hr,blockquote{border-color:#394150;}"
                : "body{background:#faf9f5;color:#141413;}pre,code{background:#efe9de;}"
                    + "blockquote{color:#4e5e48;}a,.markdown-link{color:#24558b;}"
                    + "th,td,hr,blockquote{border-color:#d4cbc1;}";
        return "<!doctype html><html><head><meta charset=\"UTF-8\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                + "style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\">"
                + "<style>" + CSS + palette + "</style></head><body>"
                + html.clean(renderer.render(parser.parse(markdown))) + "</body></html>";
    }

    private static String loadCss() {
        try (var input = MarkdownRenderer.class.getResourceAsStream("/styles/markdown.css")) {
            if (input == null) {
                throw new IllegalStateException("Missing Markdown stylesheet");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read Markdown stylesheet", failure);
        }
    }

    private static final class InertLinks implements NodeRenderer {

        private final HtmlNodeRendererContext context;

        InertLinks(HtmlNodeRendererContext context) {
            this.context = context;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Link.class, Image.class);
        }

        @Override
        public void render(Node node) {
            var writer = context.getWriter();
            if (node instanceof Link link) {
                writer.tag("span", Map.of("class", "markdown-link", "title", link.getDestination()));
                renderChildren(node);
                writer.tag("/span");
            } else {
                writer.tag("span", Map.of("class", "markdown-image"));
                writer.text("[Image: ");
                renderAlt(node);
                writer.text("]");
                writer.tag("/span");
            }
        }

        private void renderChildren(Node parent) {
            for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
                context.render(child);
            }
        }

        private void renderAlt(Node parent) {
            for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof Text text) {
                    context.getWriter().text(text.getLiteral());
                } else if (child instanceof Code code) {
                    context.getWriter().text(code.getLiteral());
                } else if (child instanceof HtmlInline html) {
                    context.getWriter().text(html.getLiteral());
                } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                    context.getWriter().text(" ");
                } else {
                    renderAlt(child);
                }
            }
        }
    }
}
