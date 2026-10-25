package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import javafx.geometry.Point2D;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CanvasTextMetricsTest {

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @Test
    void asciiClicksChooseNearestCaretAndTabGapsChooseNearestTabEdge() throws Exception {
        FxTestSupport.onFxThread(() -> {
            CanvasTextMetrics metrics = new CanvasTextMetrics(Font.font("Monospaced", 14));
            double cell = metrics.measure("M");
            assertEquals(0, metrics.columnAt("abcd", -100));
            assertEquals(0, metrics.columnAt("abcd", cell * 0.4));
            assertEquals(1, metrics.columnAt("abcd", cell * 0.6));
            assertEquals(2, metrics.columnAt("abcd", cell * 2.2));
            assertEquals(4, metrics.columnAt("abcd", Double.MAX_VALUE));
            assertEquals(1, metrics.columnAt("a\tb", cell * 1.2));
            assertEquals(2, metrics.columnAt("a\tb", cell * 3));
            assertEquals(3, metrics.columnAt("a\tb", cell * 4.6));
            assertEquals(2, metrics.columnAt("a\t\tb", cell * 5));
            assertEquals(3, metrics.columnAt("a\t\tb", cell * 7));
            assertEquals(0, metrics.columnAt("\t", cell * 0.2));
            assertEquals(1, metrics.columnAt("\t", cell * 3));
            assertEquals(metrics.measure("a   b"), metrics.width("a\tb"), 0.001);
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"e\u0301", "🐍", "👨‍👩‍👧‍👦", "🇮🇳", "a\te\u0301\t🐍世界", "किरण"})
    void clicksNeverSplitExtendedGraphemesOrSurrogatePairs(String text) throws Exception {
        FxTestSupport.onFxThread(() -> {
            CanvasTextMetrics metrics = new CanvasTextMetrics(Font.font("Monospaced", 14));
            Set<Integer> boundaries = new HashSet<>(Set.of(0));
            var matcher = Pattern.compile("\\X").matcher(text);
            while (matcher.find()) {
                boundaries.add(matcher.end());
            }
            double width = metrics.width(text);
            for (double x = -10; x < width + 10; x += 0.5) {
                int column = metrics.columnAt(text, x);
                assertTrue(boundaries.contains(column), "Invalid grapheme offset " + column + " at x=" + x);
            }
            return null;
        });
    }

    @Test
    void mixedDirectionGlyphHitsFollowJavaFxLayout() throws Exception {
        FxTestSupport.onFxThread(() -> {
            Font font = Font.font("Monospaced", 14);
            String text = "left אבג right";
            Text nativeText = new Text(text);
            nativeText.setFont(font);
            CanvasTextMetrics metrics = new CanvasTextMetrics(font);
            var bounds = nativeText.getLayoutBounds();
            for (double x = 0; x < bounds.getWidth(); x += 0.5) {
                int expected = nativeText.hitTest(new Point2D(x, bounds.getMinY() + bounds.getHeight() / 2))
                        .getInsertionIndex();
                assertEquals(expected, metrics.columnAt(text, x));
            }
            return null;
        });
    }

    @Test
    void caretAndSelectionRespectTabsAndEmptySegments() throws Exception {
        FxTestSupport.onFxThread(() -> {
            CanvasTextMetrics metrics = new CanvasTextMetrics(Font.font("Monospaced", 14));
            double cell = metrics.measure(" ");
            assertEquals(0, metrics.caretX("", 0));
            assertEquals(cell, metrics.caretX("a\tb", 1), 0.001);
            assertEquals(cell * 4, metrics.caretX("a\tb", 2), 0.001);
            assertEquals(cell * 8, metrics.caretX("\t\t", 2), 0.001);
            assertEquals(java.util.List.of(new CanvasTextMetrics.Span(cell, cell * 4)),
                    metrics.selectionSpans("a\tb", 1, 2));
            assertEquals(cell * 5, metrics.selectionSpans("a\tb", 0, 3).stream()
                    .mapToDouble(span -> span.end() - span.start()).sum(), 0.001);
            assertTrue(metrics.selectionSpans("", 0, 0).isEmpty());
            assertThrows(IndexOutOfBoundsException.class, () -> metrics.caretX("a", 2));
            assertThrows(IndexOutOfBoundsException.class, () -> metrics.selectionSpans("a", 1, 0));
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"left אבג right", "e\u0301🐍किरण"})
    void selectionGeometryMatchesNativeGlyphLayout(String text) throws Exception {
        FxTestSupport.onFxThread(() -> {
            Font font = Font.font("Monospaced", 14);
            Text nativeText = new Text(text);
            nativeText.setFont(font);
            CanvasTextMetrics metrics = new CanvasTextMetrics(font);
            for (int end = 1; end <= text.length(); end++) {
                var shape = new javafx.scene.shape.Path(nativeText.rangeShape(0, end));
                shape.setStroke(null);
                shape.setFill(javafx.scene.paint.Color.BLACK);
                var spans = metrics.selectionSpans(text, 0, end);
                double y = nativeText.getLayoutBounds().getMinY() / 2;
                for (double x = 0.25; x < metrics.width(text); x += 0.5) {
                    final double point = x;
                    assertEquals(shape.contains(x, y), spans.stream()
                            .anyMatch(span -> point > span.start() && point < span.end()));
                }
                var move = (javafx.scene.shape.MoveTo) nativeText.caretShape(end, true)[0];
                assertEquals(move.getX(), metrics.caretX(text, end), 0.001);
            }
            return null;
        });
    }

    @Test
    void emptyTextAndInvalidCoordinatesAreHandledWithoutChangingMetrics() throws Exception {
        FxTestSupport.onFxThread(() -> {
            CanvasTextMetrics metrics = new CanvasTextMetrics(Font.font("Monospaced", 14));
            assertEquals(0, metrics.columnAt("", -100));
            assertEquals(0, metrics.columnAt("", 100));
            for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                assertThrows(IllegalArgumentException.class, () -> metrics.columnAt("text", value));
            }
            metrics.clear();
            assertEquals(4, metrics.columnAt("text", 1000));
            return null;
        });
    }
}
