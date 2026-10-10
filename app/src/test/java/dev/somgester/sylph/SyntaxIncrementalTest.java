package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SyntaxIncrementalTest {

    private final CountingGrammars grammars = new CountingGrammars();

    private final SyntaxTokenizer tokenizer = new SyntaxTokenizer(grammars::forLanguage);

    @Test
    void oneEditInTwoThousandLinesTokenizesOnlyTheChangedLine() {
        String original = "[\n" + IntStream.range(0, 2000).mapToObj(index -> "  " + index + ",")
                .collect(Collectors.joining("\n")) + "\n0\n]";
        var before = complete(original, EditorLanguage.JSON);
        String edited = original.replace("  1234,", "  4321,");
        grammars.calls.clear();
        var after = tokenizer.tokenize(edited, EditorLanguage.JSON);
        assertTrue(after.complete());
        assertEquals(List.of("  4321,"), grammars.calls);
        assertSame(before.lines().get(1800).tokens(), after.lines().get(1800).tokens());
        assertMatchesFresh(edited, EditorLanguage.JSON, after);
        grammars.calls.clear();
        assertEquals(after, tokenizer.tokenize(edited, EditorLanguage.JSON));
        assertTrue(grammars.calls.isEmpty());
    }

    @Test
    void insertingAndDeletingALineReusesTheShiftedSuffix() {
        String original = "class Example {\nint one = 1;\nint two = 2;\n}";
        complete(original, EditorLanguage.JAVA);
        String inserted = original.replace("int two", "// café 🚀\nint two");
        grammars.calls.clear();
        var afterInsert = tokenizer.tokenize(inserted, EditorLanguage.JAVA);
        assertEquals(List.of("// café 🚀"), grammars.calls);
        assertMatchesFresh(inserted, EditorLanguage.JAVA, afterInsert);
        grammars.calls.clear();
        var afterDelete = tokenizer.tokenize(original, EditorLanguage.JAVA);
        assertTrue(grammars.calls.isEmpty());
        assertMatchesFresh(original, EditorLanguage.JAVA, afterDelete);
    }

    @Test
    void editsToCommentAndTextBlockDelimitersPropagateUntilStateConverges() {
        List<String> documents = List.of(
                "class Example {\n// marker\nint one = 1;\nint two = 2;\n}",
                "class Example {\n/* marker\nint one = 1;\nint two = 2;\n}",
                "class Example {\n/* marker\nint one = 1; */\nint two = 2;\n}",
                "class Example {\nString text = \"\"\"\nhello\n\"\"\";\nint two = 2;\n}",
                "class Example {\nString text = \"\"\"\nhello\nmissing end\nint two = 2;\n}",
                "class Example {\nString text = \"\"\"\nhello\n\"\"\";\nint two = 2;\n}");
        for (String text : documents) {
            var actual = complete(text, EditorLanguage.JAVA);
            assertMatchesFresh(text, EditorLanguage.JAVA, actual);
        }
    }

    @Test
    void cancelledUpdateDoesNotPublishPartialCache() {
        String original = "class Example {\nint one = 1;\nint two = 2;\n}";
        var before = complete(original, EditorLanguage.JAVA);
        String edited = original.replace("one = 1", "one = 3");
        grammars.interruptNext = true;
        try {
            assertThrows(CancellationException.class, () -> tokenizer.tokenize(edited, EditorLanguage.JAVA));
        } finally {
            Thread.interrupted();
        }
        grammars.calls.clear();
        assertEquals(before, tokenizer.tokenize(original, EditorLanguage.JAVA));
        assertTrue(grammars.calls.isEmpty());
        assertMatchesFresh(edited, EditorLanguage.JAVA, complete(edited, EditorLanguage.JAVA));
    }

    @Test
    void timedOutUpdateKeepsLastCompleteCacheAndCanRecover() {
        String original = "class Example {\nint one = 1;\nint two = 2;\n}";
        var before = complete(original, EditorLanguage.JAVA);
        String edited = original.replace("one = 1", "one = 3");
        grammars.stopNext = true;
        var partial = tokenizer.tokenize(edited, EditorLanguage.JAVA);
        assertFalse(partial.complete());
        assertTrue(partial.lines().get(2).tokens().stream().allMatch(token -> token.scopes().isEmpty()));
        grammars.calls.clear();
        assertEquals(before, tokenizer.tokenize(original, EditorLanguage.JAVA));
        assertTrue(grammars.calls.isEmpty());
        var recovered = tokenizer.tokenize(edited, EditorLanguage.JAVA);
        assertTrue(recovered.complete());
        assertEquals(List.of("int one = 3;"), grammars.calls);
        assertMatchesFresh(edited, EditorLanguage.JAVA, recovered);
    }

    @Test
    void removingFirstLineRespectsFirstLineGrammarState() {
        String original = "// heading\nclass Example {\nint one = 1;\n}";
        complete(original, EditorLanguage.JAVA);
        String edited = original.substring(original.indexOf('\n') + 1);
        assertMatchesFresh(edited, EditorLanguage.JAVA, complete(edited, EditorLanguage.JAVA));
    }

    @ParameterizedTest
    @EnumSource(value = EditorLanguage.class, names = {"JAVA", "JSON"})
    void repeatedMixedEditsMatchAnIndependentFullParse(EditorLanguage language) {
        Random random = new Random(8301);
        String text = language == EditorLanguage.JAVA
                ? "class Example {\nString text = \"hello\";\n/* note */ int number = 42;\n}"
                : "{\n\"message\": \"hello\",\n\"number\": 42,\n\"enabled\": true\n}";
        String[] insertions = {"\n", "/*", "*/", "\"", "42", "café", "", "\t", "true"};
        var referenceGrammars = new SyntaxGrammars();
        for (int step = 0; step < 60; step++) {
            int position = random.nextInt(text.length() + 1);
            int removed = random.nextInt(Math.min(4, text.length() - position) + 1);
            text = text.substring(0, position) + insertions[random.nextInt(insertions.length)]
                    + text.substring(position + removed);
            var actual = complete(text, language);
            // A new tokenizer has no line cache; the independent registry can retain regex compilation.
            var reference = new SyntaxTokenizer(referenceGrammars::forLanguage);
            var expected = reference.tokenize(text, language);
            if (!expected.complete()) {
                expected = reference.tokenize(text, language);
            }
            assertTrue(expected.complete());
            assertEquals(expected, actual, "Edit step " + step + " for " + language);
        }
    }

    @Test
    void languageChangeInvalidatesCacheEvenWhenTextIsIdentical() {
        String text = "42\ntrue\n\"hello\"";
        complete(text, EditorLanguage.JAVA);
        grammars.calls.clear();
        var json = complete(text, EditorLanguage.JSON);
        assertEquals(3, grammars.calls.size());
        assertMatchesFresh(text, EditorLanguage.JSON, json);
        complete(text, EditorLanguage.PLAIN_TEXT);
        grammars.calls.clear();
        assertMatchesFresh(text, EditorLanguage.JAVA, complete(text, EditorLanguage.JAVA));
        assertEquals(3, grammars.calls.size());
    }

    private SyntaxTokenizer.Result complete(String text, EditorLanguage language) {
        var result = tokenizer.tokenize(text, language);
        if (!result.complete()) {
            result = tokenizer.tokenize(text, language);
        }
        assertTrue(result.complete());
        return result;
    }

    private static void assertMatchesFresh(String text, EditorLanguage language, SyntaxTokenizer.Result actual) {
        var fresh = new SyntaxTokenizer();
        var expected = fresh.tokenize(text, language);
        if (!expected.complete()) {
            expected = fresh.tokenize(text, language);
        }
        assertTrue(expected.complete());
        assertEquals(expected, actual);
    }

    private static final class CountingGrammars {

        private final SyntaxGrammars source = new SyntaxGrammars();

        private final EnumMap<EditorLanguage, IGrammar> wrappers = new EnumMap<>(EditorLanguage.class);

        private final List<String> calls = new ArrayList<>();

        private boolean stopNext;

        private boolean interruptNext;

        Optional<IGrammar> forLanguage(EditorLanguage language) {
            return source.forLanguage(language).map(grammar -> wrappers.computeIfAbsent(language,
                    ignored -> wrap(grammar)));
        }

        private IGrammar wrap(IGrammar grammar) {
            // Warm the regex engine before counting work, avoiding first-use timing variability.
            grammar.tokenizeLine("class Example { int value = 42; }", null, Duration.ofSeconds(2));
            return (IGrammar) Proxy.newProxyInstance(IGrammar.class.getClassLoader(), new Class<?>[] {IGrammar.class},
                    (proxy, method, arguments) -> {
                        assertEquals("tokenizeLine", method.getName());
                        calls.add((String) arguments[0]);
                        var result = grammar.tokenizeLine((String) arguments[0],
                                (IStateStack) arguments[1], (Duration) arguments[2]);
                        if (interruptNext) {
                            interruptNext = false;
                            Thread.currentThread().interrupt();
                        }
                        if (stopNext) {
                            stopNext = false;
                            return stoppedEarly(result);
                        }
                        return result;
                    });
        }
    }

    private static ITokenizeLineResult<IToken[]> stoppedEarly(ITokenizeLineResult<IToken[]> result) {
        return new ITokenizeLineResult<>() {
            @Override
            public IToken[] getTokens() {
                return result.getTokens();
            }

            @Override
            public IStateStack getRuleStack() {
                return result.getRuleStack();
            }

            @Override
            public boolean isStoppedEarly() {
                return true;
            }
        };
    }
}
