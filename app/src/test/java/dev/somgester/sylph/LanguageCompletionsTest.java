package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LanguageCompletionsTest {

    @ParameterizedTest
    @EnumSource(EditorLanguage.class)
    void languageSuggestionsAreAvailableOnlyForCode(EditorLanguage language) {
        var result = LanguageCompletions.suggest("", 0, 0, language);
        assertEquals(language == EditorLanguage.PLAIN_TEXT || language == EditorLanguage.MARKDOWN,
                result.suggestions().isEmpty());
    }

    @Test
    void keepsDocumentWordsAndSeparatesTemplatesFromKeywords() {
        var result = LanguageCompletions.suggest("classify\ncl", 11, 2, EditorLanguage.JAVA);
        assertEquals(List.of("class", "class — template", "classify"),
                result.suggestions().stream().map(Object::toString).toList());
        assertEquals("class", result.suggestions().getFirst().text());
        assertEquals("class ClassName {\n    \n}", result.suggestions().get(1).text());
    }

    @Test
    void respectsLanguageAndPrefixThreshold() {
        assertTrue(LanguageCompletions.suggest("d", 1, 2, EditorLanguage.PYTHON).suggestions().isEmpty());
        assertTrue(LanguageCompletions.suggest("123", 3, 0, EditorLanguage.JAVA).suggestions().isEmpty());
        assertTrue(LanguageCompletions.suggest("\uD801\uDC00", 1, 0, EditorLanguage.JAVA).suggestions().isEmpty());
        assertFalse(labels("def", EditorLanguage.JAVA).contains("def — template"));
        assertTrue(labels("def", EditorLanguage.PYTHON).contains("def — template"));
        assertTrue(labels("inter", EditorLanguage.TYPESCRIPT).contains("interface"));
        assertFalse(labels("inter", EditorLanguage.JAVASCRIPT).contains("interface"));
        assertEquals(List.of("false", "null", "true"), labels("", EditorLanguage.JSON));
    }

    @Test
    void templatesPreserveIndentAndReplaceWholeIdentifier() {
        String text = "\t  clsuffix";
        var result = LanguageCompletions.suggest(text, 5, 2, EditorLanguage.JAVA);
        var template = result.suggestions().stream().filter(s -> s.label().endsWith("template"))
                .findFirst().orElseThrow();
        assertEquals(3, result.start());
        assertEquals(text.length(), result.end());
        assertEquals("class ClassName {\n\t      \n\t  }", template.text());
        assertEquals("ClassName", template.text().substring(template.selectionStart(), template.selectionEnd()));
    }

    private static List<String> labels(String prefix, EditorLanguage language) {
        return LanguageCompletions.suggest(prefix, prefix.length(), 0, language).suggestions().stream()
                .map(Object::toString).toList();
    }
}
