package dev.somgester.sylph;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

/** Read-only rendering prototype; it does not replace the live editor or handle editing input. */
final class EditorCanvas extends Region {

    private record RenderedLine(int number, String text) { }

    private final Canvas canvas = new Canvas();

    private final Font font = Font.font("Monospaced", 14);

    private final Text measurement = new Text();

    private final double lineHeight;

    private final double baseline;

    private final double tabWidth;

    private TextLines document;

    private double scrollY;

    private double scrollX;

    private double maxScrollX;

    // Grow lazily as rows are visited; content edits invalidate the learned extent.
    private double knownWidth;

    private Color background = Color.web("#0f172a");

    private Color foreground = Color.web("#e2e8f0");

    private Color gutter = Color.web("#94a3b8");

    @SuppressWarnings("this-escape")
    EditorCanvas(TextLines document) {
        this.document = Objects.requireNonNull(document, "document");
        measurement.setFont(font);
        measurement.setText("Mg");
        lineHeight = Math.ceil(measurement.getLayoutBounds().getHeight() + 6);
        baseline = 3 + measurement.getBaselineOffset();
        tabWidth = measure("    ");
        getChildren().add(canvas);
        setMinSize(0, 0);
        setPrefSize(640, 400);
        setFocusTraversable(false);
        addEventHandler(ScrollEvent.SCROLL, event -> {
            if (!event.isControlDown() && !event.isMetaDown()) {
                double previousX = scrollX;
                double previousY = scrollY;
                double horizontal = event.getDeltaX();
                if (event.isShiftDown() && horizontal == 0) {
                    horizontal = event.getDeltaY();
                }
                if (horizontal != 0) {
                    scrollToX(scrollX - horizontal);
                }
                if (!event.isShiftDown() && event.getDeltaY() != 0) {
                    scrollTo(scrollY - event.getDeltaY());
                }
                if (previousX != scrollX || previousY != scrollY) {
                    event.consume();
                }
            }
        });
    }

    void setDocument(TextLines value) {
        document = Objects.requireNonNull(value, "document");
        scrollY = 0;
        scrollX = 0;
        maxScrollX = 0;
        refresh();
    }

    void setColors(Color background, Color foreground, Color gutter) {
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(foreground, "foreground");
        Objects.requireNonNull(gutter, "gutter");
        this.background = background;
        this.foreground = foreground;
        this.gutter = gutter;
        requestLayout();
    }

    double lineHeight() {
        return lineHeight;
    }

    double scrollY() {
        return scrollY;
    }

    double scrollX() {
        return scrollX;
    }

    double maxScrollX() {
        return maxScrollX;
    }

    void scrollToX(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Horizontal scroll offset must be finite");
        }
        scrollX = Math.clamp(value, 0, maxScrollX);
        requestLayout();
    }

    void scrollTo(double value) {
        scrollY = LineViewport.layout(document.lineCount(), lineHeight, getHeight(), value, 1).scrollY();
        requestLayout();
    }

    // Owners call this after edits; the surface never subscribes to mutable document state.
    void refresh() {
        knownWidth = 0;
        requestLayout();
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        LineViewport viewport = LineViewport.layout(document.lineCount(), lineHeight, getHeight(), scrollY, 1);
        scrollY = viewport.scrollY();
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.clearRect(0, 0, getWidth(), getHeight());
        graphics.setFill(background);
        graphics.fillRect(0, 0, getWidth(), getHeight());
        if (getWidth() == 0 || viewport.firstLine() == viewport.endLine()) {
            return;
        }
        graphics.setFont(font);
        graphics.setTextBaseline(VPos.BASELINE);
        double gutterWidth = measure(Integer.toString(Math.max(1, document.lineCount()))) + 16;
        List<RenderedLine> rows = new ArrayList<>();
        for (int line = viewport.firstLine(); line < viewport.endLine(); line++) {
            RenderedLine row = new RenderedLine(line, document.lineText(line));
            rows.add(row);
            knownWidth = Math.max(knownWidth, lineWidth(row.text()));
            double y = viewport.lineY(line) + baseline;
            graphics.setTextAlign(TextAlignment.RIGHT);
            graphics.setFill(gutter);
            graphics.fillText(Integer.toString(line + 1), gutterWidth - 8, y);
        }
        double availableWidth = Math.max(0, getWidth() - gutterWidth - 16);
        maxScrollX = Math.max(0, knownWidth - availableWidth);
        scrollX = Math.clamp(scrollX, 0, maxScrollX);
        graphics.save();
        try {
            graphics.beginPath();
            graphics.rect(gutterWidth, 0, Math.max(0, getWidth() - gutterWidth), getHeight());
            graphics.clip();
            graphics.setFill(foreground);
            graphics.setTextAlign(TextAlignment.LEFT);
            for (RenderedLine row : rows) {
                double y = viewport.lineY(row.number()) + baseline;
                drawLine(graphics, row.text(), gutterWidth + 8 - scrollX, y);
            }
        } finally {
            graphics.restore();
        }
    }

    private double lineWidth(String text) {
        int start = 0;
        double x = 0;
        for (int tab = text.indexOf('\t'); tab >= 0; tab = text.indexOf('\t', start)) {
            x = nextTab(x, text.substring(start, tab));
            start = tab + 1;
        }
        return x + measure(text.substring(start));
    }

    private void drawLine(GraphicsContext graphics, String text, double origin, double y) {
        int start = 0;
        double x = 0;
        for (int tab = text.indexOf('\t'); tab >= 0; tab = text.indexOf('\t', start)) {
            String segment = text.substring(start, tab);
            graphics.fillText(segment, origin + x, y);
            x = nextTab(x, segment);
            start = tab + 1;
        }
        graphics.fillText(text.substring(start), origin + x, y);
    }

    private double nextTab(double x, String segment) {
        return (Math.floor((x + measure(segment)) / tabWidth) + 1) * tabWidth;
    }

    private double measure(String text) {
        measurement.setText(text);
        return measurement.getLayoutBounds().getWidth();
    }
}
