package dev.somgester.sylph;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javafx.geometry.Point2D;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.LineTo;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

/** Shared drawing/hit-test metrics. Owned by the Canvas's JavaFX thread. */
final class CanvasTextMetrics {

    private final Text measurement = new Text();

    private final BreakIterator boundaries = BreakIterator.getCharacterInstance(Locale.ROOT);

    private final double lineHeight;

    private final double baseline;

    private final double tabWidth;

    CanvasTextMetrics(Font font) {
        measurement.setFont(font);
        measurement.setText("Mg");
        lineHeight = Math.ceil(measurement.getLayoutBounds().getHeight() + 6);
        baseline = 3 + measurement.getBaselineOffset();
        tabWidth = measure("    ");
    }

    double lineHeight() {
        return lineHeight;
    }

    double baseline() {
        return baseline;
    }

    double measure(String text) {
        measurement.setText(text);
        return measurement.getLayoutBounds().getWidth();
    }

    double nextTab(double x, String segment) {
        return (Math.floor((x + measure(segment)) / tabWidth) + 1) * tabWidth;
    }

    double width(String text) {
        int start = 0;
        double x = 0;
        for (int tab = text.indexOf('\t'); tab >= 0; tab = text.indexOf('\t', start)) {
            x = nextTab(x, text.substring(start, tab));
            start = tab + 1;
        }
        return x + measure(text.substring(start));
    }

    int columnAt(String text, double x) {
        if (!Double.isFinite(x)) {
            throw new IllegalArgumentException("Hit-test coordinate must be finite");
        }
        int start = 0;
        double left = 0;
        for (int tab = text.indexOf('\t'); tab >= 0; tab = text.indexOf('\t', start)) {
            String segment = text.substring(start, tab);
            double end = left + measure(segment);
            if (x <= end) {
                return start + hitSegment(segment, x - left);
            }
            double next = nextTab(left, segment);
            if (x < next) {
                return x - end < next - x ? tab : tab + 1;
            }
            left = next;
            start = tab + 1;
        }
        return start + hitSegment(text.substring(start), x - left);
    }

    record Span(double start, double end) { }

    double caretX(String text, int column) {
        Objects.checkFromToIndex(column, column, text.length());
        int start = 0;
        double x = 0;
        for (int tab = text.indexOf('\t'); tab >= 0; tab = text.indexOf('\t', start)) {
            if (column <= tab) {
                measurement.setText(text.substring(start, tab));
                return x + caretX(column - start);
            }
            x = nextTab(x, text.substring(start, tab));
            start = tab + 1;
        }
        measurement.setText(text.substring(start));
        return x + caretX(column - start);
    }

    // JavaFX rangeShape returns one rectangle per visual run, including disjoint bidi runs.
    List<Span> selectionSpans(String text, int from, int to) {
        Objects.checkFromToIndex(from, to, text.length());
        List<Span> spans = new ArrayList<>();
        int start = 0;
        double x = 0;
        while (start <= text.length()) {
            int tab = text.indexOf('\t', start);
            int end = tab < 0 ? text.length() : tab;
            String segment = text.substring(start, end);
            measurement.setText(segment);
            if (from < end && to > start) {
                double left = Double.NaN;
                double right = Double.NaN;
                for (var element : measurement.rangeShape(Math.max(from, start) - start,
                        Math.min(to, end) - start)) {
                    if (element instanceof MoveTo move) {
                        if (Double.isFinite(left)) {
                            spans.add(new Span(x + left, x + right));
                        }
                        left = move.getX();
                        right = left;
                    } else if (element instanceof LineTo line) {
                        left = Math.min(left, line.getX());
                        right = Math.max(right, line.getX());
                    }
                }
                if (Double.isFinite(left)) {
                    spans.add(new Span(x + left, x + right));
                }
            }
            if (tab < 0) {
                break;
            }
            double next = nextTab(x, segment);
            if (from <= tab && to > tab) {
                spans.add(new Span(x + measure(segment), next));
            }
            x = next;
            start = tab + 1;
        }
        return List.copyOf(spans);
    }

    private int hitSegment(String text, double x) {
        if (text.isEmpty()) {
            return 0;
        }
        measurement.setText(text);
        var bounds = measurement.getLayoutBounds();
        double localX = Math.clamp(x, bounds.getMinX() - 1, bounds.getMaxX() + 1);
        var hit = measurement.hitTest(new Point2D(localX, bounds.getMinY() + bounds.getHeight() / 2));
        int column = Math.clamp(hit.getInsertionIndex(), 0, text.length());
        boundaries.setText(text);
        if (boundaries.isBoundary(column)) {
            return column;
        }
        int before = boundaries.preceding(column);
        int after = boundaries.following(column);
        double beforeX = caretX(before);
        double afterX = caretX(after);
        if (Double.isFinite(beforeX) && Double.isFinite(afterX)) {
            return Math.abs(localX - beforeX) < Math.abs(localX - afterX) ? before : after;
        }
        return hit.isLeading() ? before : after;
    }

    private double caretX(int column) {
        if (measurement.getText().isEmpty()) {
            return 0;
        }
        for (var element : measurement.caretShape(column, true)) {
            if (element instanceof MoveTo move) {
                return move.getX();
            }
        }
        return Double.NaN;
    }

    void clear() {
        measurement.setText("");
        boundaries.setText("");
    }
}
