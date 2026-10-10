package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SyntaxTokenizerTest {

    private final SyntaxTokenizer tokenizer = new SyntaxTokenizer();

    @Test
    void javaBlockCommentsContinueAcrossBlankLinesAndEndBeforeCode() {
        String text = "/* start\n\ncontinued */ class Example {}";
        var result = tokenizer.tokenize(text, EditorLanguage.JAVA);
        assertTrue(result.complete());
        assertTrue(scopesAt(result, text.indexOf("continued")).contains("comment.block.java"));
        assertFalse(scopesAt(result, text.indexOf("Example")).contains("comment.block.java"));
        assertTrue(scopesAt(result, text.indexOf("Example")).contains("entity.name.type.class.java"));
        assertCoverage(result, text);
    }

    @Test
    void javaTextBlocksCarryStringStateAndEndBeforeFollowingCode() {
        String text = "String message = \"\"\"\nhello world\n\"\"\";\nint answer = 42;";
        var result = tokenizer.tokenize(text, EditorLanguage.JAVA);
        assertTrue(result.complete());
        assertTrue(scopesAt(result, text.indexOf("hello")).contains("string.quoted.triple.java"));
        assertFalse(scopesAt(result, text.indexOf("42")).contains("string.quoted.triple.java"));
        assertTrue(scopesAt(result, text.indexOf("42")).stream()
                .anyMatch(scope -> scope.startsWith("constant.numeric")));
        assertCoverage(result, text);
    }

    @Test
    void jsonUsesGrammarScopesForKeysEscapesNumbersAndLiterals() {
        String text = "{\n  \"key\": \"escaped \\n\",\n  \"number\": -12.5,\n  \"enabled\": true\n}";
        var result = tokenizer.tokenize(text, EditorLanguage.JSON);
        assertTrue(result.complete());
        assertTrue(scopesAt(result, text.indexOf("key")).stream()
                .anyMatch(scope -> scope.startsWith("support.type.property-name")));
        assertTrue(scopesAt(result, text.indexOf("\\n")).stream()
                .anyMatch(scope -> scope.startsWith("constant.character.escape")));
        assertTrue(scopesAt(result, text.indexOf("12.5")).stream()
                .anyMatch(scope -> scope.startsWith("constant.numeric")));
        assertTrue(scopesAt(result, text.indexOf("true")).contains("constant.language.json"));
        assertCoverage(result, text);
    }

    @ParameterizedTest
    @EnumSource(EditorLanguage.class)
    void tokensUseUtf16OffsetsAndPreserveTabsAndTrailingEmptyLines(EditorLanguage language) {
        String text = "\t\"café 🚀 漢字\" 42\n\n";
        var result = tokenizer.tokenize(text, language);
        assertTrue(result.complete());
        assertEquals(List.of(0, text.length() - 1, text.length()),
                result.lines().stream().map(SyntaxTokenizer.Line::start).toList());
        assertTrue(result.lines().get(1).tokens().isEmpty());
        assertTrue(result.lines().get(2).tokens().isEmpty());
        assertCoverage(result, text);
        if (language != EditorLanguage.PLAIN_TEXT) {
            assertTrue(scopesAt(result, text.indexOf("42")).stream()
                    .anyMatch(scope -> scope.startsWith("constant.numeric")));
        }
    }

    @ParameterizedTest
    @EnumSource(EditorLanguage.class)
    void emptyDocumentHasOneEmptyLineWithoutSyntheticNewline(EditorLanguage language) {
        var result = tokenizer.tokenize("", language);
        assertTrue(result.complete());
        assertEquals(0, result.length());
        assertEquals(List.of(new SyntaxTokenizer.Line(0, 0, List.of())), result.lines());
    }

    @Test
    void plainTextHasNoGrammarScopes() {
        String text = "class Example {}\n{\"number\": 42}";
        var result = tokenizer.tokenize(text, EditorLanguage.PLAIN_TEXT);
        assertTrue(result.complete());
        assertTrue(result.lines().stream().flatMap(line -> line.tokens().stream())
                .allMatch(token -> token.scopes().isEmpty()));
        assertCoverage(result, text);
    }

    @Test
    void documentAndLanguageChangesNeverReusePreviousDocumentState() {
        tokenizer.tokenize("/* unfinished", EditorLanguage.JAVA);
        var java = tokenizer.tokenize("class Example {}", EditorLanguage.JAVA);
        assertTrue(scopesAt(java, 6).contains("entity.name.type.class.java"));
        assertFalse(scopesAt(java, 0).contains("comment.block.java"));
        var json = tokenizer.tokenize("{\"answer\": 42}", EditorLanguage.JSON);
        assertTrue(scopesAt(json, 11).contains("source.json"));
        assertFalse(scopesAt(json, 11).contains("source.java"));
    }

    @Test
    void timeoutKeepsCompletedLinesAndLeavesRemainingLinesUnstyled() {
        var realGrammar = new SyntaxGrammars().forLanguage(EditorLanguage.JAVA).orElseThrow();
        AtomicInteger calls = new AtomicInteger();
        IGrammar boundedGrammar = (IGrammar) Proxy.newProxyInstance(IGrammar.class.getClassLoader(),
                new Class<?>[] {IGrammar.class}, (proxy, method, arguments) -> {
                    assertEquals("tokenizeLine", method.getName());
                    assertEquals(Duration.ofMillis(100), arguments[2]);
                    var result = realGrammar.tokenizeLine((String) arguments[0],
                            (IStateStack) arguments[1], (Duration) arguments[2]);
                    return calls.incrementAndGet() == 2 ? stoppedEarly(result) : result;
                });
        var bounded = new SyntaxTokenizer(language -> Optional.of(boundedGrammar));
        String text = "class Example {}\n/* unfinished\ncontinued */ class Next {}";
        var result = bounded.tokenize(text, EditorLanguage.JAVA);
        assertFalse(result.complete());
        assertEquals(2, calls.get());
        assertTrue(scopesAt(result, 6).contains("entity.name.type.class.java"));
        assertTrue(result.lines().subList(1, 3).stream().flatMap(line -> line.tokens().stream())
                .allMatch(token -> token.scopes().isEmpty()));
        assertCoverage(result, text);
    }

    @Test
    void interruptedWorkerStopsWithoutClearingInterruptStatus() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class,
                    () -> tokenizer.tokenize("class Example {}", EditorLanguage.JAVA));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertTrue(tokenizer.tokenize("class Example {}", EditorLanguage.JAVA).complete());
    }

    @Test
    void resultCollectionsAreImmutableAndDetachedFromInputs() {
        List<String> scopes = new ArrayList<>(List.of("source.java"));
        var token = new SyntaxTokenizer.Token(0, 1, scopes);
        scopes.clear();
        assertEquals(List.of("source.java"), token.scopes());
        var result = tokenizer.tokenize("class Example {}", EditorLanguage.JAVA);
        assertThrows(UnsupportedOperationException.class, () -> result.lines().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.lines().getFirst().tokens().clear());
        assertThrows(UnsupportedOperationException.class, () -> token.scopes().clear());
    }

    private static List<String> scopesAt(SyntaxTokenizer.Result result, int offset) {
        for (var line : result.lines()) {
            for (var token : line.tokens()) {
                if (offset >= line.start() + token.start() && offset < line.start() + token.end()) {
                    return token.scopes();
                }
            }
        }
        throw new AssertionError("No token at offset " + offset);
    }

    private static void assertCoverage(SyntaxTokenizer.Result result, String text) {
        assertEquals(text.length(), result.length());
        int offset = 0;
        for (var line : result.lines()) {
            assertEquals(offset, line.start());
            int cursor = 0;
            for (var token : line.tokens()) {
                assertEquals(cursor, token.start());
                assertTrue(token.end() > token.start());
                assertTrue(token.end() <= line.length());
                cursor = token.end();
            }
            assertEquals(line.length(), cursor);
            offset += line.length();
            if (offset < text.length()) {
                assertEquals('\n', text.charAt(offset));
                offset++;
            }
        }
        assertEquals(text.length(), offset);
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
