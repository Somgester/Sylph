package dev.somgester.sylph;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

// Map grammar scopes to theme-independent CSS classes, without parsing source text.
final class SyntaxStyles {

    private SyntaxStyles() { }

    record Snapshot(String text, SyntaxTokenizer.Result result) { }

    record Patch(int start, StyleSpans<Collection<String>> styles) { }

    static StyleSpans<Collection<String>> spans(SyntaxTokenizer.Result result) {
        return range(result, 0, result.lines().size());
    }

    static Patch patch(Snapshot current, Snapshot previous, int dirtyStart, int dirtyEnd) {
        var lines = current.result().lines();
        int prefix = 0;
        int suffix = 0;
        if (previous != null) {
            var old = previous.result().lines();
            while (prefix < lines.size() && prefix < old.size()
                    && lines.get(prefix).start() + lines.get(prefix).length() < dirtyStart
                    && sameLine(current, prefix, previous, prefix)) {
                checkCancelled();
                prefix++;
            }
            while (suffix < lines.size() - prefix && suffix < old.size() - prefix
                    && lines.get(lines.size() - 1 - suffix).start() > dirtyEnd
                    && sameLine(current, lines.size() - 1 - suffix, previous, old.size() - 1 - suffix)) {
                checkCancelled();
                suffix++;
            }
        }
        int start = prefix < lines.size() ? lines.get(prefix).start() : current.result().length();
        return new Patch(start, range(current.result(), prefix, lines.size() - suffix));
    }

    private static boolean sameLine(Snapshot first, int firstIndex, Snapshot second, int secondIndex) {
        var a = first.result().lines().get(firstIndex);
        var b = second.result().lines().get(secondIndex);
        return a.length() == b.length() && a.tokens().equals(b.tokens())
                && first.text().regionMatches(a.start(), second.text(), b.start(), a.length());
    }

    private static StyleSpans<Collection<String>> range(SyntaxTokenizer.Result result, int first, int last) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int start = first < result.lines().size() ? result.lines().get(first).start() : result.length();
        int end = last < result.lines().size() ? result.lines().get(last).start() : result.length();
        int cursor = start;
        for (int index = first; index < last; index++) {
            checkCancelled();
            var line = result.lines().get(index);
            for (var token : line.tokens()) {
                int tokenStart = line.start() + token.start();
                if (tokenStart > cursor) {
                    builder.add(List.of(), tokenStart - cursor);
                }
                builder.add(classes(token.scopes()), token.end() - token.start());
                cursor = line.start() + token.end();
            }
        }
        if (cursor < end || start == end) {
            builder.add(List.of(), end - cursor);
        }
        return builder.create();
    }

    static Collection<String> classes(List<String> scopes) {
        // Embedded words in comments keep the comment color, including Javadoc tags.
        if (scopes.stream().anyMatch(scope -> matches(scope, "comment"))) {
            return List.of("syntax-comment");
        }
        for (int index = scopes.size() - 1; index >= 0; index--) {
            String scope = scopes.get(index);
            String category = category(scope);
            if (category != null) {
                return List.of("syntax-" + category);
            }
        }
        return List.of();
    }

    private static String category(String scope) {
        if (matches(scope, "invalid")) {
            return "invalid";
        }
        if (matches(scope, "support.type.property-name") || matches(scope, "entity.name.tag")) {
            return "property";
        }
        if (matches(scope, "string") || matches(scope, "constant.character")) {
            return "string";
        }
        if (matches(scope, "constant.numeric")) {
            return "number";
        }
        if (matches(scope, "keyword") || matches(scope, "storage.modifier")
                || matches(scope, "constant.language")) {
            return "keyword";
        }
        if (matches(scope, "entity.name.function") || matches(scope, "support.function")) {
            return "function";
        }
        if (matches(scope, "entity.name.type") || matches(scope, "storage.type")
                || matches(scope, "support.type")) {
            return "type";
        }
        return null;
    }

    private static boolean matches(String scope, String prefix) {
        return scope.equals(prefix) || scope.startsWith(prefix + ".");
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Syntax style mapping interrupted");
        }
    }
}
