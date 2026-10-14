package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CppSyntaxTest {

    @ParameterizedTest
    @EnumSource(value = EditorLanguage.class, names = {"C", "CPP"})
    void preprocessorDirectivesStringsTypesAndNumbersHaveColors(EditorLanguage language) {
        String source = "#include <stdio.h>\n#define ANSWER 42\n"
                + "typedef struct Point { int x; } Point;\n"
                + "const char *message = \"hello\\n\";\n";
        var result = complete(new SyntaxTokenizer(), source, language);
        assertTrue(styleAt(result, source.indexOf("include")).contains("syntax-keyword"));
        assertTrue(styleAt(result, source.indexOf("define")).contains("syntax-keyword"));
        assertTrue(styleAt(result, source.indexOf("42")).contains("syntax-number"));
        assertTrue(styleAt(result, source.indexOf(language == EditorLanguage.C ? "int x" : "Point"))
                .contains("syntax-type"));
        assertTrue(styleAt(result, source.indexOf("hello")).contains("syntax-string"));
        assertEquals(source.length(), SyntaxStyles.spans(result).length());
    }

    @ParameterizedTest
    @EnumSource(value = EditorLanguage.class, names = {"C", "CPP"})
    void multilineCommentEditsPropagateStateAndRestoreColors(EditorLanguage language) {
        String source = "/* note\ncontinued\n*/\nint answer = 42;\n";
        var tokenizer = new SyntaxTokenizer();
        var before = complete(tokenizer, source, language);
        assertTrue(styleAt(before, source.indexOf("continued")).contains("syntax-comment"));
        assertTrue(styleAt(before, source.indexOf("42")).contains("syntax-number"));
        String edited = source.replace("*/", "missing end");
        var after = complete(tokenizer, edited, language);
        assertTrue(styleAt(after, edited.indexOf("42")).contains("syntax-comment"));
        assertEquals(complete(new SyntaxTokenizer(), edited, language), after);
        assertEquals(before, complete(tokenizer, source, language));
    }

    @Test
    void cppRawStringsKeepCustomDelimiterStateAndRestoreAfterAnEdit() {
        String source = "const char *message = R\"tag(hello\ncontinued)tag\";\nint answer = 42;\n";
        var tokenizer = new SyntaxTokenizer();
        var before = complete(tokenizer, source, EditorLanguage.CPP);
        assertTrue(styleAt(before, source.indexOf("continued")).contains("syntax-string"));
        assertTrue(styleAt(before, source.indexOf("42")).contains("syntax-number"));
        String edited = source.replace(")tag\"", ")wrong\"");
        var after = complete(tokenizer, edited, EditorLanguage.CPP);
        assertTrue(styleAt(after, edited.indexOf("42")).contains("syntax-string"));
        assertEquals(complete(new SyntaxTokenizer(), edited, EditorLanguage.CPP), after);
        assertEquals(before, complete(tokenizer, source, EditorLanguage.CPP));
    }

    @Test
    void cppTemplatesLambdasAndContinuedMacrosUseBundledScopes() {
        String source = "template<typename T>\nclass Box { public: T value; };\n"
                + "auto twice = [](int x) { return x * 2; };\n"
                + "#define VALUE \\\n    42\nint answer = 7;\n";
        var result = complete(new SyntaxTokenizer(), source, EditorLanguage.CPP);
        assertTrue(styleAt(result, source.indexOf("template")).contains("syntax-keyword"));
        assertTrue(styleAt(result, source.indexOf("Box")).contains("syntax-type"));
        assertTrue(styleAt(result, source.indexOf("return")).contains("syntax-keyword"));
        for (String number : List.of("2", "42", "7")) {
            assertTrue(styleAt(result, source.indexOf(number)).contains("syntax-number"));
        }
    }

    @ParameterizedTest
    @EnumSource(value = EditorLanguage.class, names = {"C", "CPP"})
    void commentBannersRemainCommentsAndFollowingCodeKeepsItsColors(EditorLanguage language) {
        String source = "// === File ===\n/* === Banner === */\nint answer = 42;\n";
        var result = complete(new SyntaxTokenizer(), source, language);
        assertTrue(styleAt(result, source.indexOf("File")).contains("syntax-comment"));
        assertTrue(styleAt(result, source.indexOf("Banner")).contains("syntax-comment"));
        assertTrue(styleAt(result, source.indexOf("42")).contains("syntax-number"));
    }

    @ParameterizedTest
    @EnumSource(value = EditorLanguage.class, names = {"C", "CPP"})
    void incompleteEditsDoNotTriggerNonAdvancingGrammarLoops(EditorLanguage language) {
        Logger logger = Logger.getLogger("org.eclipse.tm4e.core.internal.grammar.LineTokenizer");
        List<String> loops = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage().contains("endless loop") || record.getMessage().contains("repeat operator")) {
                    loops.add(record.getMessage());
                }
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        Logger regexLogger = Logger.getLogger("org.eclipse.tm4e.core.internal.oniguruma.OnigRegExp");
        logger.addHandler(capture);
        regexLogger.addHandler(capture);
        try {
            var random = new Random(8301);
            String text = AdditionalSyntaxTest.sample(language);
            String[] edits = {"\n", "/*", "*/", "\"", "42", "café", "", "\t", "true"};
            var tokenizer = new SyntaxTokenizer();
            complete(tokenizer, text, language);
            for (int step = 0; step < 60; step++) {
                int position = random.nextInt(text.length() + 1);
                int removed = random.nextInt(Math.min(4, text.length() - position) + 1);
                text = text.substring(0, position) + edits[random.nextInt(edits.length)]
                        + text.substring(position + removed);
                var result = complete(tokenizer, text, language);
                assertEquals(text.length(), SyntaxStyles.spans(result).length());
            }
            assertTrue(loops.isEmpty(), loops.toString());
        } finally {
            logger.removeHandler(capture);
            regexLogger.removeHandler(capture);
        }
    }

    private static Collection<String> styleAt(SyntaxTokenizer.Result result, int offset) {
        return SyntaxStyles.spans(result).subView(offset, offset + 1).getStyleSpan(0).getStyle();
    }

    private static SyntaxTokenizer.Result complete(SyntaxTokenizer tokenizer, String source, EditorLanguage language) {
        var result = tokenizer.tokenize(source, language);
        if (!result.complete()) {
            result = tokenizer.tokenize(source, language);
        }
        assertTrue(result.complete(), () -> "Incomplete " + language + " source: " + source);
        return result;
    }
}
