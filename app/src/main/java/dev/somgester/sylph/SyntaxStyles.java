package dev.somgester.sylph;

import java.util.Collection;
import java.util.List;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

// Map grammar scopes to theme-independent CSS classes, without parsing source text.
final class SyntaxStyles {

    private SyntaxStyles() { }

    static StyleSpans<Collection<String>> spans(SyntaxTokenizer.Result result) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int cursor = 0;
        for (var line : result.lines()) {
            for (var token : line.tokens()) {
                int start = line.start() + token.start();
                if (start > cursor) {
                    builder.add(List.of(), start - cursor);
                }
                builder.add(classes(token.scopes()), token.end() - token.start());
                cursor = line.start() + token.end();
            }
        }
        if (cursor < result.length() || result.length() == 0) {
            builder.add(List.of(), result.length() - cursor);
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
}
