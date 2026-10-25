package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditorCanvasTest {

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @Test
    void hitTestAccountsForBothScrollAxesAndKeepsGutterAtLineStart() throws Exception {
        FxTestSupport.onFxThread(() -> {
            EditorCanvas surface = new EditorCanvas(new PieceTable(("a".repeat(100) + "\n").repeat(30)));
            surface.resize(300, surface.lineHeight() * 3);
            surface.layout();
            Text glyph = new Text("M");
            glyph.setFont(Font.font("Monospaced", 14));
            double cell = glyph.getLayoutBounds().getWidth();
            Text digits = new Text("31");
            digits.setFont(Font.font("Monospaced", 14));
            double origin = digits.getLayoutBounds().getWidth() + 24;
            surface.scrollToX(cell * 10);
            surface.scrollTo(surface.lineHeight() * 10.25);
            assertEquals(new EditorCanvas.Position(10, 12),
                    surface.hitTest(origin + cell * 2.1, surface.lineHeight() * 0.5).orElseThrow());
            assertEquals(new EditorCanvas.Position(10, 0),
                    surface.hitTest(1, surface.lineHeight() * 0.5).orElseThrow());
            return null;
        });
    }

    @Test
    void hitsUseCurrentDocumentAndClampBlankSpaceToLastCaret() throws Exception {
        FxTestSupport.onFxThread(() -> {
            PieceTable buffer = new PieceTable("old");
            EditorCanvas surface = new EditorCanvas(buffer);
            surface.resize(300, 200);
            surface.layout();
            buffer.replace(0, buffer.length(), "first\nnew\n");
            surface.refresh();
            assertEquals(new EditorCanvas.Position(1, 3),
                    surface.hitTest(1000, surface.lineHeight() * 1.5).orElseThrow());
            assertEquals(new EditorCanvas.Position(2, 0), surface.hitTest(1000, 1000).orElseThrow());
            surface.setDocument(new PieceTable());
            assertEquals(new EditorCanvas.Position(0, 0), surface.hitTest(1000, 1000).orElseThrow());
            for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                assertThrows(IllegalArgumentException.class, () -> surface.hitTest(value, 0));
                assertThrows(IllegalArgumentException.class, () -> surface.hitTest(0, value));
            }
            surface.resize(0, 200);
            assertTrue(surface.hitTest(0, 0).isEmpty());
            return null;
        });
    }

    @Test
    void shiftWheelAndTrackpadScrollHorizontallyWhileModifiedAndBoundaryEventsReachParent() throws Exception {
        FxTestSupport.onFxThread(() -> {
            EditorCanvas surface = new EditorCanvas(new PieceTable("long text ".repeat(100) + "\nshort\n".repeat(30)));
            Pane parent = new Pane(surface);
            AtomicInteger passed = new AtomicInteger();
            parent.addEventHandler(ScrollEvent.SCROLL, event -> passed.incrementAndGet());
            surface.resize(300, 100);
            surface.layout();
            Event.fireEvent(surface, wheel(0, -30, true, false));
            assertEquals(30, surface.scrollX());
            assertEquals(0, surface.scrollY());
            Event.fireEvent(surface, wheel(-25, 0, false, false));
            assertEquals(55, surface.scrollX());
            assertEquals(0, passed.get());
            Event.fireEvent(surface, wheel(-25, 0, false, true));
            assertEquals(55, surface.scrollX());
            assertEquals(1, passed.get());
            surface.scrollToX(Double.MAX_VALUE);
            assertEquals(surface.maxScrollX(), surface.scrollX());
            Event.fireEvent(surface, wheel(-25, 0, false, false));
            assertEquals(2, passed.get());
            surface.scrollToX(-1);
            Event.fireEvent(surface, wheel(25, 0, false, false));
            assertEquals(0, surface.scrollX());
            assertEquals(3, passed.get());
            Event.fireEvent(surface, wheel(-20, -15, false, false));
            assertEquals(20, surface.scrollX());
            assertEquals(15, surface.scrollY());
            Event.fireEvent(surface, wheel(-10, 0, true, false));
            assertEquals(30, surface.scrollX());
            assertEquals(15, surface.scrollY());
            assertEquals(3, passed.get());
            return null;
        });
    }

    @Test
    void horizontalScrollingMovesTextPixelsWithoutMovingGutterOrChangingSource() throws Exception {
        FxTestSupport.onFxThread(() -> {
            String text = "BEGIN " + "abcdefgh".repeat(30) + " END_MARKER";
            PieceTable buffer = new PieceTable(text);
            EditorCanvas surface = new EditorCanvas(buffer);
            surface.resize(300, 60);
            surface.layout();
            WritableImage before = surface.snapshot(null, null);
            surface.scrollToX(Double.MAX_VALUE);
            surface.layout();
            assertTrue(surface.scrollX() > 0);
            WritableImage after = surface.snapshot(null, null);
            save(after, "horizontal");
            int changed = 0;
            for (int y = 0; y < 60; y++) {
                for (int x = 0; x < 300; x++) {
                    int original = before.getPixelReader().getArgb(x, y);
                    int scrolled = after.getPixelReader().getArgb(x, y);
                    if (x < 24) {
                        assertEquals(original, scrolled, "Gutter must stay fixed");
                    } else if (original != scrolled) {
                        changed++;
                    }
                }
            }
            assertTrue(changed > 100);
            assertEquals(text, buffer.toString());
            return null;
        });
    }

    @Test
    void horizontalExtentSurvivesVerticalScrollAndThemeButClampsOnResizeEditsAndReplacement() throws Exception {
        FxTestSupport.onFxThread(() -> {
            PieceTable buffer = new PieceTable("long text ".repeat(100) + "\n" + "short\n".repeat(30));
            EditorCanvas surface = new EditorCanvas(buffer);
            surface.resize(300, surface.lineHeight() * 3);
            surface.layout();
            surface.scrollToX(100);
            surface.scrollTo(surface.lineHeight() * 10);
            surface.layout();
            assertEquals(100, surface.scrollX(), "Visited long rows keep the horizontal extent available");
            surface.setColors(Color.WHITE, Color.BLACK, Color.GRAY);
            surface.layout();
            assertEquals(100, surface.scrollX());
            surface.resize(20_000, surface.lineHeight() * 3);
            surface.layout();
            assertEquals(0, surface.scrollX());
            assertEquals(0, surface.maxScrollX());
            surface.resize(300, surface.lineHeight() * 3);
            surface.scrollTo(0);
            surface.layout();
            surface.scrollToX(100);
            buffer.replace(0, buffer.length(), "short");
            surface.refresh();
            surface.layout();
            assertEquals(0, surface.scrollX());
            assertEquals(0, surface.maxScrollX());
            surface.setDocument(new PieceTable("another long line ".repeat(100)));
            surface.layout();
            surface.scrollToX(100);
            for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                assertThrows(IllegalArgumentException.class, () -> surface.scrollToX(value));
                assertEquals(100, surface.scrollX());
            }
            surface.setDocument(new PieceTable());
            surface.layout();
            assertEquals(0, surface.scrollX());
            assertEquals(0, surface.maxScrollX());
            return null;
        });
    }

    @Test
    void verticalWheelScrollsButModifiedAndBoundaryEventsRemainAvailableToParent() throws Exception {
        FxTestSupport.onFxThread(() -> {
            EditorCanvas surface = new EditorCanvas(new PieceTable("line\n".repeat(100)));
            Pane parent = new Pane(surface);
            AtomicInteger passed = new AtomicInteger();
            parent.addEventHandler(ScrollEvent.SCROLL, event -> passed.incrementAndGet());
            surface.resize(500, 100);
            surface.layout();
            Event.fireEvent(surface, wheel(-10, false));
            assertEquals(10, surface.scrollY());
            assertEquals(0, passed.get());
            Event.fireEvent(surface, wheel(-10, true));
            assertEquals(10, surface.scrollY());
            assertEquals(1, passed.get());
            surface.scrollTo(0);
            Event.fireEvent(surface, wheel(10, false));
            assertEquals(0, surface.scrollY());
            assertEquals(2, passed.get());
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 20})
    void tabStopsMatchSpaceAlignedRenderingWithoutChangingDocument(double scroll) throws Exception {
        FxTestSupport.onFxThread(() -> {
            PieceTable tabbed = new PieceTable("A\tB\tC");
            EditorCanvas surface = new EditorCanvas(tabbed);
            surface.resize(80, 60);
            surface.layout();
            surface.scrollToX(scroll);
            surface.layout();
            WritableImage tabs = surface.snapshot(null, null);
            surface.setDocument(new PieceTable("A   B   C"));
            surface.layout();
            surface.scrollToX(scroll);
            surface.layout();
            WritableImage spaces = surface.snapshot(null, null);
            for (int y = 0; y < 60; y++) {
                for (int x = 0; x < 80; x++) {
                    assertEquals(spaces.getPixelReader().getArgb(x, y), tabs.getPixelReader().getArgb(x, y),
                            "Tab alignment at " + x + "," + y);
                }
            }
            assertEquals("A\tB\tC", tabbed.toString());
            return null;
        });
    }

    private static ScrollEvent wheel(double deltaY, boolean control) {
        return wheel(0, deltaY, false, control);
    }

    private static ScrollEvent wheel(double deltaX, double deltaY, boolean shift, boolean control) {
        return new ScrollEvent(ScrollEvent.SCROLL, 0, 0, 0, 0, shift, control, false, false, false, false,
                deltaX, deltaY, deltaX, deltaY, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null);
    }

    @Test
    void millionLineDocumentReadsOnlyViewportRowsAndClampsScrollAfterResize() throws Exception {
        FxTestSupport.onFxThread(() -> {
            List<Integer> reads = new ArrayList<>();
            EditorCanvas surface = new EditorCanvas(new TextLines() {
                @Override
                public int lineCount() {
                    return 1_000_000;
                }

                @Override
                public String lineText(int line) {
                    reads.add(line);
                    return "row " + line;
                }
            });
            surface.resize(500, surface.lineHeight() * 4);
            surface.layout();
            assertEquals(List.of(0, 1, 2, 3, 4), reads);
            reads.clear();
            surface.scrollTo(surface.lineHeight() * 500_000 + 0.5);
            surface.layout();
            assertEquals(List.of(499_999, 500_000, 500_001, 500_002, 500_003, 500_004, 500_005), reads);
            reads.clear();
            surface.scrollTo(Double.MAX_VALUE);
            surface.layout();
            assertEquals(List.of(999_995, 999_996, 999_997, 999_998, 999_999), reads);
            surface.resize(500, surface.lineHeight() * 10);
            surface.layout();
            assertEquals(surface.lineHeight() * (1_000_000 - 10), surface.scrollY());
            return null;
        });
    }

    @Test
    void documentRefreshClampsAfterDeletionAndReplacementResetsScroll() throws Exception {
        FxTestSupport.onFxThread(() -> {
            PieceTable buffer = new PieceTable("row\n".repeat(100));
            EditorCanvas surface = new EditorCanvas(buffer);
            surface.resize(500, surface.lineHeight() * 3);
            surface.layout();
            surface.scrollTo(Double.MAX_VALUE);
            surface.layout();
            assertTrue(surface.scrollY() > 0);
            buffer.replace(0, buffer.length(), "new\n");
            surface.refresh();
            surface.layout();
            assertEquals(0, surface.scrollY());
            surface.setDocument(new PieceTable("other\n".repeat(200)));
            surface.layout();
            assertEquals(0, surface.scrollY());
            assertThrows(NullPointerException.class, () -> surface.setDocument(null));
            assertThrows(IllegalArgumentException.class, () -> surface.scrollTo(Double.NaN));
            return null;
        });
    }

    @Test
    void zeroSizedSurfaceDoesNotReadAnyDocumentLines() throws Exception {
        FxTestSupport.onFxThread(() -> {
            EditorCanvas surface = new EditorCanvas(new TextLines() {
                @Override
                public int lineCount() {
                    return 100;
                }

                @Override
                public String lineText(int line) {
                    throw new AssertionError("No rows should be read for a zero-sized surface");
                }
            });
            surface.resize(500, 0);
            surface.layout();
            surface.resize(0, 100);
            surface.layout();
            return null;
        });
    }

    @Test
    void paintsTextGutterTabsAndThemeColorsWithoutEditingSource() throws Exception {
        FxTestSupport.onFxThread(() -> {
            String original = "class Example {\n\tString name = \"café 世界 🐍\";\n\t\treturn;\n}\n";
            PieceTable buffer = new PieceTable(original);
            EditorCanvas surface = new EditorCanvas(buffer);
            new Scene(surface, 520, 180);
            surface.resize(520, 180);
            surface.applyCss();
            surface.layout();
            WritableImage dark = surface.snapshot(null, null);
            assertEquals(Color.web("#0f172a"), dark.getPixelReader().getColor(519, 179));
            assertTrue(paintedPixels(dark, 35, 510, 0, 100, Color.web("#0f172a")) > 200);
            assertTrue(paintedPixels(dark, 0, 30, 0, 100, Color.web("#0f172a")) > 20);
            save(dark, "dark");
            surface.setColors(Color.WHITE, Color.web("#172033"), Color.web("#64748b"));
            surface.layout();
            WritableImage light = surface.snapshot(null, null);
            assertEquals(Color.WHITE, light.getPixelReader().getColor(519, 179));
            assertTrue(paintedPixels(light, 35, 510, 0, 100, Color.WHITE) > 200);
            save(light, "light");
            assertEquals(original, buffer.toString());
            assertFalse(surface.isFocusTraversable());
            return null;
        });
    }

    private static int paintedPixels(WritableImage image, int left, int right, int top, int bottom, Color background) {
        int count = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if (!image.getPixelReader().getColor(x, y).equals(background)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void save(WritableImage image, String theme) throws Exception {
        BufferedImage output = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < output.getHeight(); y++) {
            for (int x = 0; x < output.getWidth(); x++) {
                output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            }
        }
        Path path = Path.of("build", "editor-canvas-preview", theme + ".png");
        Files.createDirectories(path.getParent());
        ImageIO.write(output, "png", path.toFile());
    }
}
