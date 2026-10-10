package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import org.eclipse.tm4e.core.grammar.IToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SyntaxGrammarsTest {

    @ParameterizedTest
    @EnumSource(value = EditorLanguage.class, names = {"JAVA", "JSON"})
    void bundledGrammarsProduceStringAndNumberTokens(EditorLanguage language) {
        SyntaxGrammars grammars = new SyntaxGrammars();
        var grammar = grammars.forLanguage(language).orElseThrow();
        String source = language == EditorLanguage.JAVA
                ? "class Example { String message = \"hello\"; int answer = 42; }"
                : "{\"message\": \"hello\", \"answer\": 42}";
        var result = grammar.tokenizeLine(source, null, Duration.ofSeconds(2));
        assertFalse(result.isStoppedEarly());
        assertTrue(hasScope(result.getTokens(), "string.quoted.double"));
        assertTrue(hasScope(result.getTokens(), "constant.numeric"));
        assertSame(grammar, grammars.forLanguage(language).orElseThrow());
    }

    @Test
    void javaGrammarCarriesBlockCommentStateAcrossLines() {
        var grammar = new SyntaxGrammars().forLanguage(EditorLanguage.JAVA).orElseThrow();
        var first = grammar.tokenizeLine("/* comment starts", null, Duration.ofSeconds(2));
        var second = grammar.tokenizeLine("continues */ class Example {}", first.getRuleStack(),
                Duration.ofSeconds(2));
        assertFalse(first.isStoppedEarly());
        assertFalse(second.isStoppedEarly());
        assertTrue(second.getTokens()[0].getScopes().contains("comment.block.java"));
        assertTrue(hasScope(second.getTokens(), "entity.name.type.class.java"));
    }

    @Test
    void switchingGrammarsDoesNotChangeTheirScopesAndPlainTextHasNoGrammar() {
        SyntaxGrammars grammars = new SyntaxGrammars();
        assertTrue(grammars.forLanguage(EditorLanguage.PLAIN_TEXT).isEmpty());
        var java = grammars.forLanguage(EditorLanguage.JAVA).orElseThrow();
        var json = grammars.forLanguage(EditorLanguage.JSON).orElseThrow();
        assertEquals("source.java", java.getScopeName());
        assertEquals("source.json", json.getScopeName());
        assertSame(java, grammars.forLanguage(EditorLanguage.JAVA).orElseThrow());
        assertTrue(grammars.forLanguage(EditorLanguage.PLAIN_TEXT).isEmpty());
    }

    private static boolean hasScope(IToken[] tokens, String prefix) {
        return Arrays.stream(tokens).flatMap(token -> token.getScopes().stream())
                .anyMatch(scope -> scope.startsWith(prefix));
    }
}
