package dev.somgester.sylph;

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

    private final Canvas canvas = new Canvas();

    private final Font font = Font.font("Monospaced", 14);

    private final Text measurement = new Text();

    private final double lineHeight;

    private final double baseline;

    private final double tabWidth;

    private TextLines document;

    private double scrollY;

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
            if (!event.isControlDown() && !event.isMetaDown() && !event.isShiftDown()
                    && event.getDeltaY() != 0) {
                double previous = scrollY;
                scrollTo(scrollY - event.getDeltaY());
                if (previous != scrollY) {
                    event.consume();
                }
            }
        });
    }

    void setDocument(TextLines value) {
        document = Objects.requireNonNull(value, "document");
        scrollY = 0;
        refresh();
    }

    void setColors(Color background, Color foreground, Color gutter) {
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(foreground, "foreground");
        Objects.requireNonNull(gutter, "gutter");
        this.background = background;
        this.foreground = foreground;
        this.gutter = gutter;
        refresh();
    }

    double lineHeight() {
        return lineHeight;
    }

    double scrollY() {
        return scrollY;
    }

    void scrollTo(double value) {
        scrollY = LineViewport.layout(document.lineCount(), lineHeight, getHeight(), value, 1).scrollY();
        refresh();
    }

    // Owners call this after edits; the surface never subscribes to mutable document state.
    void refresh() {
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
        for (int line = viewport.firstLine(); line < viewport.endLine(); line++) {
            double y = viewport.lineY(line) + baseline;
            graphics.setTextAlign(TextAlignment.RIGHT);
            graphics.setFill(gutter);
            graphics.fillText(Integer.toString(line + 1), gutterWidth - 8, y);
        }
        graphics.save();
        try {
            graphics.beginPath();
            graphics.rect(gutterWidth, 0, Math.max(0, getWidth() - gutterWidth), getHeight());
            graphics.clip();
            graphics.setFill(foreground);
            graphics.setTextAlign(TextAlignment.LEFT);
            for (int line = viewport.firstLine(); line < viewport.endLine(); line++) {
                drawLine(graphics, document.lineText(line), gutterWidth + 8, viewport.lineY(line) + baseline);
            }
        } finally {
            graphics.restore();
        }
    }

    private void drawLine(GraphicsContext graphics, String text, double origin, double y) {
        int start = 0;
        double x = 0;
        for (int tab = text.indexOf('\t'); tab >= 0; tab = text.indexOf('\t', start)) {
            String segment = text.substring(start, tab);
            graphics.fillText(segment, origin + x, y);
            x += measure(segment);
            x = (Math.floor(x / tabWidth) + 1) * tabWidth;
            start = tab + 1;
        }
        graphics.fillText(text.substring(start), origin + x, y);
    }

    private double measure(String text) {
        measurement.setText(text);
        return measurement.getLayoutBounds().getWidth();
    }
}
