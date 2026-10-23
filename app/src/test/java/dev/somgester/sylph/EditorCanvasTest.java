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
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EditorCanvasTest {

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
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

    @Test
    void tabStopsMatchSpaceAlignedRenderingWithoutChangingDocument() throws Exception {
        FxTestSupport.onFxThread(() -> {
            PieceTable tabbed = new PieceTable("A\tB\tC");
            EditorCanvas surface = new EditorCanvas(tabbed);
            surface.resize(300, 60);
            surface.layout();
            WritableImage tabs = surface.snapshot(null, null);
            surface.setDocument(new PieceTable("A   B   C"));
            surface.layout();
            WritableImage spaces = surface.snapshot(null, null);
            for (int y = 0; y < 60; y++) {
                for (int x = 0; x < 300; x++) {
                    assertEquals(spaces.getPixelReader().getArgb(x, y), tabs.getPixelReader().getArgb(x, y),
                            "Tab alignment at " + x + "," + y);
                }
            }
            assertEquals("A\tB\tC", tabbed.toString());
            return null;
        });
    }

    private static ScrollEvent wheel(double deltaY, boolean control) {
        return new ScrollEvent(ScrollEvent.SCROLL, 0, 0, 0, 0, false, control, false, false, false, false,
                0, deltaY, 0, deltaY, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
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
