package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PieceTableTest {

    @Test
    void lineReadsFollowOriginalAndAddedPiecesWithoutFlatteningTheBuffer() {
        PieceTable buffer = new PieceTable("first\nlast\n");
        buffer.replace(6, 6, "🐍\n");
        assertLines(buffer, "first\n🐍\nlast\n");
        buffer.replace(5, 9, "!");
        assertLines(buffer, "first!last\n");
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.lineText(2));
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.lineAt(buffer.length() + 1));
        buffer.replace(0, buffer.length(), "");
        assertLines(buffer, "");
    }

    @Test
    void emptyBufferCanBeEditedAndClearedRepeatedly() {
        PieceTable buffer = new PieceTable();
        assertTrue(buffer.isEmpty());
        assertEquals("", buffer.subSequence(0, 0));
        buffer.replace(0, 0, "first");
        assertEquals("first", buffer.toString());
        buffer.replace(0, 5, "");
        assertTrue(buffer.isEmpty());
        buffer.replace(0, 0, "second");
        buffer.replace(0, 6, "third");
        assertEquals("third", buffer.toString());
    }

    @ParameterizedTest
    @CsvSource(value = {
        "0|0|X|Xabcdef", "3|3|X|abcXdef", "6|6|X|abcdefX",
        "0|2|''|cdef", "2|4|''|abef", "4|6|''|abcd", "0|6|''|''",
        "0|2|XY|XYcdef", "2|4|XYZ|abXYZef", "4|6|X|abcdX", "0|6|XYZ|XYZ",
        "0|0|''|abcdef", "3|3|''|abcdef", "6|6|''|abcdef"
    }, delimiter = '|')
    void replacesBoundaryAndInteriorRanges(int start, int end, String replacement, String expected) {
        PieceTable buffer = new PieceTable("abcdef");
        buffer.replace(start, end, replacement);
        assertEquals(expected, buffer.toString());
        assertEquals(expected.length(), buffer.length());
        for (int index = 0; index < expected.length(); index++) {
            assertEquals(expected.charAt(index), buffer.charAt(index));
        }
    }

    @Test
    void replacementsCanSpanOriginalAndInsertedText() {
        PieceTable buffer = new PieceTable("abcdef");
        buffer.replace(2, 2, "1234");
        buffer.replace(4, 6, "XY");
        assertEquals("ab12XYcdef", buffer.toString());
        assertEquals("b12XYcd", buffer.subSequence(1, 8));
        buffer.replace(1, 8, "!");
        assertEquals("a!ef", buffer.toString());
        buffer.replace(1, 2, "");
        assertEquals("aef", buffer.toString());
    }

    @Test
    void utf16OffsetsPreserveEmojiCombiningMarksAndLineEndings() {
        String original = "\uFEFFa\r\n🐍e\u0301\n世界\r\u0000\u007F";
        PieceTable buffer = new PieceTable(original);
        assertEquals(original, buffer.toString());
        assertEquals(original.length(), buffer.length());
        int emoji = original.indexOf("🐍");
        assertEquals("🐍", buffer.subSequence(emoji, emoji + 2));
        assertEquals(original.charAt(emoji), buffer.charAt(emoji));
        assertEquals(original.charAt(emoji + 1), buffer.charAt(emoji + 1));
        buffer.replace(emoji, emoji + 2, "😀\n");
        assertEquals(original.substring(0, emoji) + "😀\n" + original.substring(emoji + 2), buffer.toString());
    }

    @Test
    void snapshotsAndSlicesDoNotChangeAfterLaterEdits() {
        PieceTable buffer = new PieceTable("original");
        buffer.replace(4, 4, "123");
        String snapshot = buffer.toString();
        CharSequence slice = buffer.subSequence(2, 8);
        buffer.replace(0, buffer.length(), "replacement");
        assertEquals("orig123inal", snapshot);
        assertEquals("ig123i", slice.toString());
        assertEquals("replacement", buffer.toString());
    }

    @Test
    void invalidOffsetsAndNullTextLeaveExistingContentUntouched() {
        assertThrows(NullPointerException.class, () -> new PieceTable(null));
        PieceTable buffer = new PieceTable("abc");
        buffer.replace(1, 1, "XY");
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.charAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.charAt(5));
        for (int[] range : new int[][] {{-1, 0}, {0, 6}, {3, 2}, {6, 6}}) {
            assertThrows(IndexOutOfBoundsException.class, () -> buffer.subSequence(range[0], range[1]));
            assertThrows(IndexOutOfBoundsException.class, () -> buffer.replace(range[0], range[1], "wrong"));
            assertEquals("aXYbc", buffer.toString());
        }
        assertThrows(NullPointerException.class, () -> buffer.replace(0, 0, null));
        assertEquals("aXYbc", buffer.toString());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 17, 42, 2026, 99991})
    void mixedEditsAndReadsMatchStringBuilder(long seed) {
        Random random = new Random(seed);
        String original = "first\r\n🐍 café\nlast";
        PieceTable buffer = new PieceTable(original);
        StringBuilder expected = new StringBuilder(original);
        String alphabet = "abc XYZ\r\n\t🐍e\u0301世界\u0000";
        for (int edit = 0; edit < 2000; edit++) {
            int start = random.nextInt(expected.length() + 1);
            int end = start + random.nextInt(expected.length() - start + 1);
            StringBuilder replacement = new StringBuilder();
            int insertedLength = random.nextInt(12);
            for (int index = 0; index < insertedLength; index++) {
                replacement.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            buffer.replace(start, end, replacement.toString());
            expected.replace(start, end, replacement.toString());
            assertEquals(expected.length(), buffer.length(), "seed=" + seed + ", edit=" + edit);
            assertEquals(expected.toString(), buffer.toString(), "seed=" + seed + ", edit=" + edit);
            assertLines(buffer, expected.toString());
            for (int index = 0; index < expected.length(); index++) {
                assertEquals(expected.charAt(index), buffer.charAt(index));
            }
            start = random.nextInt(expected.length() + 1);
            end = start + random.nextInt(expected.length() - start + 1);
            assertEquals(expected.substring(start, end), buffer.subSequence(start, end));
        }
    }

    @Test
    void repeatedTypingAndRangeDeletionPreserveALargeOriginalDocument() {
        String original = "original line\r\n".repeat(20000);
        PieceTable buffer = new PieceTable(original);
        for (int character = 0; character < 10000; character++) {
            buffer.replace(buffer.length(), buffer.length(), "x");
        }
        assertEquals(original.length() + 10000, buffer.length());
        assertEquals(original, buffer.subSequence(0, original.length()));
        assertEquals("x".repeat(10000), buffer.subSequence(original.length(), buffer.length()));
        buffer.replace(original.length(), buffer.length(), "");
        assertEquals(original, buffer.toString());
    }

    private static void assertLines(PieceTable buffer, String text) {
        String[] expected = text.split("\n", -1);
        assertEquals(expected.length, buffer.lineCount());
        int start = 0;
        for (int line = 0; line < expected.length; line++) {
            assertEquals(start, buffer.lineStart(line));
            assertEquals(start + expected[line].length(), buffer.lineEnd(line));
            assertEquals(expected[line], buffer.lineText(line));
            start += expected[line].length() + 1;
        }
        int line = 0;
        for (int offset = 0; offset <= text.length(); offset++) {
            assertEquals(line, buffer.lineAt(offset));
            if (offset < text.length() && text.charAt(offset) == '\n') {
                line++;
            }
        }
    }
}
