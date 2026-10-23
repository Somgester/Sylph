package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LineViewportTest {

    @ParameterizedTest
    @CsvSource({
        "100,20,40,0,0,0,2,0", "100,20,40,40,0,2,4,40",
        "100,20,50,25,0,1,4,25", "100,20,50,25,1,0,5,25",
        "100,20,50,-20,1,0,4,0", "100,20,50,9999,1,96,100,1950",
        "1,20,100,10,1,0,1,0", "0,20,100,10,1,0,0,0", "100,20,0,500,1,0,0,500",
        "100,20,1,20,0,1,2,20", "100,20.5,41,41,0,2,4,41"
    })
    void calculatesClampedHalfOpenRows(int count, double rowHeight, double height, double requested,
            int overscan, int first, int end, double scroll) {
        LineViewport viewport = LineViewport.layout(count, rowHeight, height, requested, overscan);
        assertEquals(first, viewport.firstLine());
        assertEquals(end, viewport.endLine());
        assertEquals(scroll, viewport.scrollY());
        assertEquals(Math.max(0, count * rowHeight - height), viewport.maxScrollY());
    }

    @Test
    void fractionalScrollingKeepsPartiallyVisibleRowsAndRejectsOutOfRangeYQueries() {
        LineViewport viewport = LineViewport.layout(100, 20, 50, 25, 1);
        assertEquals(-25, viewport.lineY(0));
        assertEquals(-5, viewport.lineY(1));
        assertEquals(55, viewport.lineY(4));
        assertThrows(IndexOutOfBoundsException.class, () -> viewport.lineY(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> viewport.lineY(5));
    }

    @Test
    void largeDocumentsAndOverscanDoNotOverflowIntegerBounds() {
        LineViewport bottom = LineViewport.layout(Integer.MAX_VALUE, 22, 100, Double.MAX_VALUE, 1);
        assertEquals(Integer.MAX_VALUE, bottom.endLine());
        assertTrue(bottom.endLine() - bottom.firstLine() <= 7);
        LineViewport all = LineViewport.layout(Integer.MAX_VALUE, 22, 100, 0, Integer.MAX_VALUE);
        assertEquals(0, all.firstLine());
        assertEquals(Integer.MAX_VALUE, all.endLine());
    }

    @Test
    void rejectsInvalidOrNonFiniteGeometry() {
        assertThrows(IllegalArgumentException.class, () -> LineViewport.layout(-1, 20, 100, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> LineViewport.layout(1, 20, 100, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> LineViewport.layout(1, 0, 100, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> LineViewport.layout(1, 20, -1, 0, 0));
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> LineViewport.layout(1, value, 100, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> LineViewport.layout(1, 20, value, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> LineViewport.layout(1, 20, 100, value, 0));
        }
        assertThrows(IllegalArgumentException.class,
                () -> LineViewport.layout(2, Double.MAX_VALUE, 100, 0, 0));
    }
}
