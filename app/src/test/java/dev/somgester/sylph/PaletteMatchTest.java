package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PaletteMatchTest {

    @Test
    void matchesAllWordsAndPreservesOriginalCaseAndOffsets() {
        assertEquals(List.of(new PaletteMatch.Range(0, 3), new PaletteMatch.Range(4, 9)),
                PaletteMatch.ranges("SRC/Hello.py", "src HELLO").orElseThrow());
        assertTrue(PaletteMatch.ranges("SRC/Hello.py", "src missing").isEmpty());
        assertEquals(List.of(), PaletteMatch.ranges("Hello.py", "  ").orElseThrow());
        assertEquals(List.of(new PaletteMatch.Range(0, 4)),
                PaletteMatch.ranges("src/Hello.py", "SRC\\").orElseThrow());
    }

    @Test
    void overlappingAndRepeatedMatchesMergeWithoutDuplicatingText() {
        assertEquals(List.of(new PaletteMatch.Range(1, 6)),
                PaletteMatch.ranges("banana", "ana nan").orElseThrow());
        assertEquals(List.of(new PaletteMatch.Range(0, 2), new PaletteMatch.Range(3, 5)),
                PaletteMatch.ranges("go go", "go").orElseThrow());
    }

    @Test
    void unicodeMatchesUseUtf16OffsetsWithoutLowercaseExpansionOrRegexInterpretation() {
        assertEquals(List.of(new PaletteMatch.Range(0, 1), new PaletteMatch.Range(2, 6)),
                PaletteMatch.ranges("İ/café/🚀.py", "i CAFÉ").orElseThrow());
        assertEquals(List.of(new PaletteMatch.Range(7, 9)),
                PaletteMatch.ranges("İ/café/🚀.py", "🚀").orElseThrow());
        assertEquals(List.of(new PaletteMatch.Range(4, 6)),
                PaletteMatch.ranges("file[1].txt", "[1").orElseThrow());
    }
}
