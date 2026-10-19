package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

class LspTextTest {

    @Test
    void roundTripsUtf16PositionsIncludingEmptyTrailingLine() {
        var coordinates = new LspText("🙂x\n\nend\n");
        for (int offset : new int[] {0, 2, 3, 4, 5, 6, 7, 8, 9}) {
            assertEquals(offset, coordinates.offset(coordinates.position(offset)));
        }
        assertEquals(new Position(3, 0), coordinates.position(9));
    }

    @Test
    void rejectsInvalidLinesColumnsRangesAndSplitSurrogates() {
        var coordinates = new LspText("🙂x\nend");
        assertEquals(-1, coordinates.offset(new Position(0, 1)));
        assertEquals(-1, coordinates.offset(new Position(0, 4)));
        assertEquals(-1, coordinates.offset(new Position(2, 0)));
        assertEquals(-1, coordinates.offset(new Position(-1, 0)));
        assertEquals(-1, coordinates.offset(new Position(1, Integer.MAX_VALUE)));
        assertEquals(-1, coordinates.offset(null));
        assertThrows(IllegalArgumentException.class, () -> coordinates.position(1));
        assertFalse(coordinates.valid(new Range(new Position(1, 2), new Position(0, 0))));
        assertFalse(coordinates.valid(null));
    }
}
