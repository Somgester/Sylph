package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LineIndexTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "one", "\n", "\n\n", "one\ntwo", "one\n", "a\r\nb\rc", "🐍\ne\u0301\n世界"})
    void initialLinesMatchLiteralLfBoundariesIncludingTrailingEmptyLines(String text) {
        verify(new LineIndex(text), text);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "0,1", "1,1", "1,2", "1,3", "2,2", "2,4", "3,3", "3,4", "4,4", "0,4"})
    void editsAtNewlinesAndDocumentEdgesPreserveEveryOffset(int start, int end) {
        String original = "a\nb\n";
        for (String replacement : new String[] {"", "X", "\n", "🐍\n\n", "\r\n", "new\nlast\n"}) {
            LineIndex lines = new LineIndex(original);
            lines.replace(start, end, replacement);
            verify(lines, original.substring(0, start) + replacement + original.substring(end));
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 43117, 2026})
    void sequentialEditsMatchIndependentStringBuilderLineScans(long seed) {
        Random random = new Random(seed);
        StringBuilder text = new StringBuilder("first\r\n🐍\nlast\n");
        LineIndex lines = new LineIndex(text.toString());
        String[] insertions = {"", "a", "\n", "\n\n", "🐍", "\r\n", "e\u0301\n世界"};
        for (int edit = 0; edit < 2000; edit++) {
            int start = random.nextInt(text.length() + 1);
            int end = start + random.nextInt(text.length() - start + 1);
            String replacement = insertions[random.nextInt(insertions.length)];
            lines.replace(start, end, replacement);
            text.replace(start, end, replacement);
            verify(lines, text.toString());
        }
    }

    @Test
    void invalidQueriesAndEditsCannotCorruptIndex() {
        String original = "first\n🐍\n";
        LineIndex lines = new LineIndex(original);
        assertThrows(NullPointerException.class, () -> new LineIndex(null));
        assertThrows(IndexOutOfBoundsException.class, () -> lines.lineAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> lines.lineAt(original.length() + 1));
        assertThrows(IndexOutOfBoundsException.class, () -> lines.lineStart(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> lines.lineEnd(3));
        assertThrows(IndexOutOfBoundsException.class, () -> lines.replace(-1, 1, "bad\n"));
        assertThrows(IndexOutOfBoundsException.class, () -> lines.replace(3, 2, "bad\n"));
        assertThrows(IndexOutOfBoundsException.class, () -> lines.replace(0, 10, "bad\n"));
        assertThrows(NullPointerException.class, () -> lines.replace(0, 0, null));
        verify(lines, original);
        lines.replace(0, original.length(), "");
        verify(lines, "");
        lines.replace(0, 0, "new\n");
        verify(lines, "new\n");
    }

    @Test
    void repeatedTypingOnLastLineAndEditsNearBeginningKeepLargeIndexAccurate() {
        String original = "line\n".repeat(20_000);
        LineIndex lines = new LineIndex(original);
        int length = original.length();
        for (int index = 0; index < 10_000; index++) {
            lines.replace(length, length, "x");
            length++;
        }
        assertEquals(20_001, lines.lineCount());
        assertEquals(original.length(), lines.lineStart(20_000));
        assertEquals(length, lines.lineEnd(20_000));
        assertEquals(20_000, lines.lineAt(length));
        lines.replace(0, 1, "prefix");
        assertEquals(10, lines.lineStart(1));
        assertEquals(original.length() + 5, lines.lineStart(20_000));
        assertEquals(length + 5, lines.lineEnd(20_000));
    }

    private static void verify(LineIndex lines, String text) {
        String[] expected = text.split("\n", -1);
        assertEquals(expected.length, lines.lineCount());
        int start = 0;
        for (int line = 0; line < expected.length; line++) {
            assertEquals(start, lines.lineStart(line));
            assertEquals(start + expected[line].length(), lines.lineEnd(line));
            start += expected[line].length() + 1;
        }
        int line = 0;
        for (int offset = 0; offset <= text.length(); offset++) {
            assertEquals(line, lines.lineAt(offset), "Caret offset " + offset);
            if (offset < text.length() && text.charAt(offset) == '\n') {
                line++;
            }
        }
    }
}
