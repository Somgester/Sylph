package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AdditionalSyntaxTest {

    @ParameterizedTest
    @EnumSource(value = EditorLanguage.class,
            names = {"PYTHON", "JAVASCRIPT", "JAVASCRIPT_JSX", "TYPESCRIPT", "TYPESCRIPT_TSX"})
    void newGrammarsColorFunctionsKeywordsStringsNumbersAndComments(EditorLanguage language) {
        String source = sample(language);
        var result = complete(new SyntaxTokenizer(), source, language);
        assertEquals("syntax-function", styleAt(result, source.indexOf("greet")).iterator().next());
        assertEquals("syntax-keyword", styleAt(result, source.indexOf("return")).iterator().next());
        assertEquals("syntax-string", styleAt(result, source.indexOf("hello")).iterator().next());
        assertEquals("syntax-number", styleAt(result, source.indexOf("42")).iterator().next());
        assertEquals("syntax-comment", styleAt(result, source.indexOf("note")).iterator().next());
        assertEquals(source.length(), SyntaxStyles.spans(result).length());
    }

    @Test
    void pythonTripleStringsAndInterpolationKeepMultilineState() {
        var tokenizer = new SyntaxTokenizer();
        String source = "message = f\"\"\"hello\ncontinued {42}\n\"\"\"\nanswer = 7";
        var before = complete(tokenizer, source, EditorLanguage.PYTHON);
        assertTrue(styleAt(before, source.indexOf("continued")).contains("syntax-string"));
        assertTrue(styleAt(before, source.indexOf("42")).contains("syntax-number"));
        assertTrue(styleAt(before, source.indexOf("7")).contains("syntax-number"));
        String edited = source.replace("\n\"\"\"\n", "\nmissing end\n");
        var after = complete(tokenizer, edited, EditorLanguage.PYTHON);
        assertTrue(styleAt(after, edited.indexOf("7")).contains("syntax-string"));
        assertEquals(complete(new SyntaxTokenizer(), edited, EditorLanguage.PYTHON), after);
        assertEquals(before, complete(tokenizer, source, EditorLanguage.PYTHON));
    }

    @ParameterizedTest
    @EnumSource(value = EditorLanguage.class,
            names = {"JAVASCRIPT", "JAVASCRIPT_JSX", "TYPESCRIPT", "TYPESCRIPT_TSX"})
    void templateLiteralsCarryStateAndColorInterpolatedExpressions(EditorLanguage language) {
        var tokenizer = new SyntaxTokenizer();
        String source = "const message = `hello\ncontinued ${42}\n`;\nconst answer = 7;";
        var before = complete(tokenizer, source, language);
        assertTrue(styleAt(before, source.indexOf("continued")).contains("syntax-string"));
        assertTrue(styleAt(before, source.indexOf("42")).contains("syntax-number"));
        assertTrue(styleAt(before, source.indexOf("7")).contains("syntax-number"));
        String edited = source.replace("\n`;\n", "\nmissing end\n");
        var after = complete(tokenizer, edited, language);
        assertTrue(styleAt(after, edited.indexOf("7")).contains("syntax-string"));
        assertEquals(complete(new SyntaxTokenizer(), edited, language), after);
        assertEquals(before, complete(tokenizer, source, language));
    }

    @ParameterizedTest
    @EnumSource(value = EditorLanguage.class, names = {"JAVASCRIPT_JSX", "TYPESCRIPT_TSX"})
    void reactGrammarsColorTagsAttributesAndEmbeddedExpressions(EditorLanguage language) {
        String source = "const element = <Widget label=\"hello\">{42}</Widget>;";
        var result = complete(new SyntaxTokenizer(), source, language);
        assertTrue(styleAt(result, source.indexOf("Widget")).contains("syntax-property"));
        assertTrue(styleAt(result, source.indexOf("label")).contains("syntax-property"));
        assertTrue(styleAt(result, source.indexOf("hello")).contains("syntax-string"));
        assertTrue(styleAt(result, source.indexOf("42")).contains("syntax-number"));
    }

    static String sample(EditorLanguage language) {
        return switch (language) {
            case PYTHON -> "def greet(name):\n    message = \"hello\"\n    return 42  # note\n";
            case JAVASCRIPT, JAVASCRIPT_JSX -> "function greet(name) {\n    const message = \"hello\";\n"
                    + "    return 42; // note\n}\n";
            case TYPESCRIPT, TYPESCRIPT_TSX -> "function greet(name: string): number {\n"
                    + "    const message: string = \"hello\";\n    return 42; // note\n}\n";
            default -> throw new IllegalArgumentException("Expected a newly supported language");
        };
    }

    private static Collection<String> styleAt(SyntaxTokenizer.Result result, int offset) {
        return SyntaxStyles.spans(result).subView(offset, offset + 1).getStyleSpan(0).getStyle();
    }

    private static SyntaxTokenizer.Result complete(SyntaxTokenizer tokenizer, String source, EditorLanguage language) {
        var result = tokenizer.tokenize(source, language);
        if (!result.complete()) {
            result = tokenizer.tokenize(source, language);
        }
        assertTrue(result.complete());
        return result;
    }
}
