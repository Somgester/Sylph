package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class ShellSyntaxTest {

    @Test
    void shellGrammarColorsFunctionsControlFlowStringsVariablesNumbersAndComments() {
        String source = AdditionalSyntaxTest.sample(EditorLanguage.SHELL);
        var result = complete(new SyntaxTokenizer(), source);
        assertTrue(styleAt(result, source.indexOf("greet")).contains("syntax-function"));
        assertTrue(styleAt(result, source.indexOf("if ")).contains("syntax-keyword"));
        assertTrue(styleAt(result, source.indexOf("hello")).contains("syntax-string"));
        assertTrue(styleAt(result, source.indexOf("$name") + 1).contains("syntax-property"));
        assertTrue(styleAt(result, source.indexOf("echo")).contains("syntax-function"));
        assertTrue(styleAt(result, source.indexOf("42")).contains("syntax-number"));
        assertTrue(styleAt(result, source.indexOf("note")).contains("syntax-comment"));
        assertEquals(source.length(), SyntaxStyles.spans(result).length());
    }

    @Test
    void arithmeticAndCommandSubstitutionKeepDistinctScopesAndSingleQuotesStayLiteral() {
        String source = "count=$((40 + 2))\noutput=$(printf '%s' \"$count\")\necho '$((99))'\n";
        var result = complete(new SyntaxTokenizer(), source);
        assertTrue(styleAt(result, source.indexOf("40")).contains("syntax-number"));
        assertTrue(styleAt(result, source.indexOf("printf")).contains("syntax-function"));
        assertTrue(styleAt(result, source.indexOf("$count") + 1).contains("syntax-property"));
        assertTrue(styleAt(result, source.indexOf("99")).contains("syntax-string"));
    }

    @Test
    void quotedHeredocsKeepStateAndIncrementalEditsMatchFullParsing() {
        var tokenizer = new SyntaxTokenizer();
        String source = "cat <<'EOF'\nHello $name\nEOF\ncount=$((7))\n";
        var before = complete(tokenizer, source);
        assertTrue(styleAt(before, source.indexOf("Hello")).contains("syntax-string"));
        assertTrue(styleAt(before, source.indexOf("$name") + 1).contains("syntax-string"));
        assertTrue(styleAt(before, source.indexOf("7")).contains("syntax-number"));
        String edited = source.replace("\nEOF\n", "\nmissing end\n");
        var after = complete(tokenizer, edited);
        assertTrue(styleAt(after, edited.indexOf("7")).contains("syntax-string"));
        assertEquals(complete(new SyntaxTokenizer(), edited), after);
        assertEquals(before, complete(tokenizer, source));
    }

    @Test
    void multilineQuotesAndCommandSubstitutionKeepEmbeddedScopes() {
        String source = "message=\"hello\ncontinued $name\"\necho \"$(printf '%s' \"$message\")\"\n";
        var result = complete(new SyntaxTokenizer(), source);
        assertTrue(styleAt(result, source.indexOf("continued")).contains("syntax-string"));
        assertTrue(styleAt(result, source.indexOf("$name") + 1).contains("syntax-property"));
        assertTrue(styleAt(result, source.indexOf("printf")).contains("syntax-function"));
        assertTrue(styleAt(result, source.indexOf("$message") + 1).contains("syntax-property"));
    }

    @Test
    void functionBodiesAndStatementBoundariesNeverTriggerGrammarLoopProtection() {
        Logger logger = Logger.getLogger("org.eclipse.tm4e.core.internal.grammar.LineTokenizer");
        List<String> loops = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage().contains("endless loop")) {
                    loops.add(record.getMessage());
                }
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        logger.addHandler(capture);
        try {
            for (String source : List.of(AdditionalSyntaxTest.sample(EditorLanguage.SHELL),
                    "empty() {}\necho 42\n", "greet() { # comment\n echo 42;\n}\n",
                    "if true; then\n echo 42;\nfi\n", "echo 42; # comment\n\n",
                    "function greet {\n echo 42;\n}\n", "\"echo\" 42\n",
                    "echo $((\n42 ^ 2\n))\n")) {
                var result = complete(new SyntaxTokenizer(), source);
                assertEquals(source.length(), SyntaxStyles.spans(result).length());
                assertTrue(styleAt(result, source.indexOf("42")).contains("syntax-number"));
            }
            var random = new java.util.Random(8301);
            String text = AdditionalSyntaxTest.sample(EditorLanguage.SHELL);
            String[] edits = {"\n", "/*", "*/", "\"", "42", "café", "", "\t", "true"};
            var tokenizer = new SyntaxTokenizer();
            for (int step = 0; step < 60; step++) {
                int position = random.nextInt(text.length() + 1);
                int removed = random.nextInt(Math.min(4, text.length() - position) + 1);
                text = text.substring(0, position) + edits[random.nextInt(edits.length)]
                        + text.substring(position + removed);
                complete(tokenizer, text);
            }
            assertTrue(loops.isEmpty(), loops.toString());
        } finally {
            logger.removeHandler(capture);
        }
    }

    private static Collection<String> styleAt(SyntaxTokenizer.Result result, int offset) {
        return SyntaxStyles.spans(result).subView(offset, offset + 1).getStyleSpan(0).getStyle();
    }

    private static SyntaxTokenizer.Result complete(SyntaxTokenizer tokenizer, String source) {
        var result = tokenizer.tokenize(source, EditorLanguage.SHELL);
        if (!result.complete()) {
            result = tokenizer.tokenize(source, EditorLanguage.SHELL);
        }
        assertTrue(result.complete());
        return result;
    }
}
