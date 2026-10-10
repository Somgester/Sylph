package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SyntaxStylesTest {

    @ParameterizedTest
    @CsvSource({
        "comment.block.java, comment",
        "string.quoted.double.json, string",
        "constant.character.escape.java, string",
        "constant.numeric.json, number",
        "keyword.control.java, keyword",
        "storage.modifier.java, keyword",
        "constant.language.json, keyword",
        "storage.type.java, type",
        "entity.name.type.class.java, type",
        "entity.name.function.java, function",
        "support.type.property-name.json, property",
        "invalid.illegal.json, invalid"
    })
    void grammarScopesMapToSharedCssCategories(String scope, String category) {
        assertEquals(List.of("syntax-" + category), SyntaxStyles.classes(List.of("source.java", scope)));
    }

    @Test
    void mostSpecificRecognizedScopeWinsAndCommentsKeepTheirColor() {
        assertEquals(List.of("syntax-property"), SyntaxStyles.classes(List.of("source.json",
                "string.quoted.double.json", "support.type.property-name.json",
                "punctuation.definition.string.begin.json")));
        assertEquals(List.of("syntax-comment"), SyntaxStyles.classes(List.of("source.java",
                "comment.block.documentation.java", "keyword.other.documentation.java")));
        assertEquals(List.of(), SyntaxStyles.classes(List.of("source.java", "meta.class.java")));
        assertEquals(List.of(), SyntaxStyles.classes(List.of("stringish.unknown")));
    }

    @Test
    void spansCoverSourceExactlyIncludingBlankLinesAndSyntheticNewlines() {
        String source = "class Example {}\n\n\"hello\"\n";
        var result = new SyntaxTokenizer().tokenize(source, EditorLanguage.JAVA);
        var spans = SyntaxStyles.spans(result);
        assertEquals(source.length(), spans.length());
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == '\n') {
                assertEquals(List.of(), spans.subView(index, index + 1).getStyleSpan(0).getStyle());
            }
        }
        assertEquals(List.of("syntax-string"),
                spans.subView(source.indexOf("hello"), source.indexOf("hello") + 1).getStyleSpan(0).getStyle());
    }

    @Test
    void emptyAndPlainDocumentsHaveDefaultStyles() {
        var tokenizer = new SyntaxTokenizer();
        assertEquals(0, SyntaxStyles.spans(tokenizer.tokenize("", EditorLanguage.PLAIN_TEXT)).length());
        var spans = SyntaxStyles.spans(tokenizer.tokenize("plain\ntext\n", EditorLanguage.PLAIN_TEXT));
        assertEquals(11, spans.length());
        assertEquals(1, spans.getSpanCount());
        assertEquals(List.of(), spans.getStyleSpan(0).getStyle());
    }
}
