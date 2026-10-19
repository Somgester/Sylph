package dev.somgester.sylph;

import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** Small, local suggestions; semantic completions belong to a language server. */
final class LanguageCompletions {

    record Suggestion(String label, String text, int selectionStart, int selectionEnd) {
        @Override
        public String toString() {
            return label;
        }
    }

    record Result(int start, int end, List<Suggestion> suggestions) {
        Result {
            suggestions = List.copyOf(suggestions);
        }
    }

    private LanguageCompletions() { }

    static Result suggest(String text, int caret, int minimumPrefix, EditorLanguage language) {
        var words = WordCompletions.suggest(text, caret, minimumPrefix);
        String prefix = words.prefix();
        if (caret > 0 && caret < text.length() && Character.isHighSurrogate(text.charAt(caret - 1))
                && Character.isLowSurrogate(text.charAt(caret))) {
            return new Result(caret, caret, List.of());
        }
        // An empty prefix can also mean an invalid token or an automatic request below threshold.
        if (prefix.isEmpty() && (minimumPrefix > 0 || words.start() != caret || words.end() != caret)) {
            return new Result(words.start(), words.end(), List.of());
        }
        var candidates = new TreeMap<String, Suggestion>(
                String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        for (String word : words.words()) {
            candidates.put(word, literal(word));
        }
        for (String keyword : keywords(language).split(" ")) {
            if (!keyword.isEmpty() && keyword.length() > prefix.length()
                    && keyword.regionMatches(true, 0, prefix, 0, prefix.length())) {
                candidates.putIfAbsent(keyword, literal(keyword));
            }
        }
        String indent = indentation(text, words.start());
        for (String template : templates(language)) {
            String trigger = template.substring(0, template.indexOf(':'));
            if (trigger.regionMatches(true, 0, prefix, 0, prefix.length())) {
                String body = template.substring(template.indexOf(':') + 1).replace("\n", "\n" + indent);
                int start = body.indexOf('«');
                int end = body.indexOf('»') - 1;
                body = body.replace("«", "").replace("»", "");
                String label = trigger + " — template";
                candidates.put(label, new Suggestion(label, body, start, end));
            }
        }
        return new Result(words.start(), words.end(), candidates.values().stream().limit(50).toList());
    }

    private static Suggestion literal(String word) {
        return new Suggestion(word, word, word.length(), word.length());
    }

    private static String indentation(String text, int start) {
        int line = text.lastIndexOf('\n', start - 1) + 1;
        int end = line;
        while (end < start && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
            end++;
        }
        return text.substring(line, end);
    }

    private static String keywords(EditorLanguage language) {
        return switch (language) {
            case JAVA -> "abstract assert boolean break byte case catch char class continue default do double else "
                    + "enum extends final finally float for if implements import instanceof int interface long "
                    + "native new package private protected public record return short static super switch "
                    + "synchronized this throw throws transient try var void volatile while true false null";
            case PYTHON -> "False None True and as assert async await break class continue def del elif else except "
                    + "finally for from global if import in is lambda nonlocal not or pass raise return "
                    + "try while with yield";
            case JAVASCRIPT, JAVASCRIPT_JSX -> javascriptKeywords();
            case TYPESCRIPT, TYPESCRIPT_TSX -> javascriptKeywords()
                    + " abstract any as boolean declare enum implements interface keyof namespace never number "
                    + "private protected public readonly string type unknown";
            case JSON -> "true false null";
            case SHELL -> "if then else elif fi for while until do done case esac function in select time "
                    + "export local readonly";
            case C -> cKeywords() + " auto register restrict";
            case CPP -> cKeywords() + " alignas alignof auto bool catch class constexpr delete explicit false friend "
                    + "namespace new noexcept nullptr private protected public reinterpret_cast static_cast "
                    + "template this throw true try typename using virtual";
            case PLAIN_TEXT, MARKDOWN -> "";
        };
    }

    private static String javascriptKeywords() {
        return "async await break case catch class const continue debugger default delete do else export extends "
                + "false finally for function if import in instanceof let new null return static super switch this "
                + "throw true try typeof undefined var void while yield";
    }

    private static String cKeywords() {
        return "break case char const continue default do double else enum extern float for goto if inline int long "
                + "return short signed sizeof static struct switch typedef union unsigned void volatile while";
    }

    private static List<String> templates(EditorLanguage language) {
        return switch (language) {
            case JAVA -> List.of("class:class «ClassName» {\n    \n}", "if:if («condition») {\n    \n}");
            case PYTHON -> List.of("class:class «ClassName»:\n    pass", "def:def «function_name»():\n    pass",
                    "if:if «condition»:\n    pass");
            case JAVASCRIPT, JAVASCRIPT_JSX, TYPESCRIPT, TYPESCRIPT_TSX -> List.of(
                    "function:function «functionName»() {\n    \n}", "if:if («condition») {\n    \n}");
            case C -> List.of("if:if («condition») {\n    \n}", "struct:struct «StructName» {\n    \n};");
            case CPP -> List.of("class:class «ClassName» {\npublic:\n    \n};", "if:if («condition») {\n    \n}");
            case SHELL -> List.of("if:if «command»; then\n    \nfi", "function:«function_name»() {\n    \n}");
            case JSON, PLAIN_TEXT, MARKDOWN -> List.of();
        };
    }
}
