package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorCanvasPreviewTest {

    @TempDir
    Path directory;

    private AppSettings settings;

    private EditorDocument document;

    private StackPane root;

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @BeforeEach
    void create() throws Exception {
        FxTestSupport.onFxThread(() -> {
            settings = new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 2)));
            document = new EditorDocument(settings);
            document.view.installContextMenu(() -> { });
            root = new StackPane(document.view);
            root.setId("root");
            new Scene(root, 1000, 400);
            root.resize(1000, 400);
            render();
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.onFxThread(() -> {
            document.close();
            return null;
        });
    }

    @Test
    void menuOpensLiveReadOnlyPreviewWithoutChangingSelectionHistoryOrDirtyState() throws Exception {
        open(Files.writeString(directory.resolve("source.txt"), "first line\nsecond line\n"));
        FxTestSupport.onFxThread(() -> {
            assertNull(root.lookup("#canvas-preview"));
            document.view.area().selectRange(8, 2);
            menu().fire();
            render();
            assertEquals("Hide Canvas Preview", menu().getText());
            assertEquals(8, document.view.area().getAnchor());
            assertEquals(2, document.view.area().getCaretPosition());
            assertFalse(document.session.dirtyProperty().get());
            assertFalse(document.view.undoAvailableProperty().get());
            EditorCanvas canvas = canvas();
            long original = pixels(canvas);
            document.view.area().appendText("another line\n");
            render();
            assertNotEquals(original, pixels(canvas));
            assertTrue(document.session.dirtyProperty().get());
            document.view.area().undo();
            render();
            assertEquals(original, pixels(canvas));
            assertFalse(document.session.dirtyProperty().get());
            document.view.area().redo();
            render();
            assertNotEquals(original, pixels(canvas));
            assertTrue(document.view.area().isEditable());
            return null;
        });
    }

    @Test
    void previewTracksThemeAndHeaderCanCloseAndReopenIt() throws Exception {
        open(Files.writeString(directory.resolve("Example.java"),
                "class Example {\n\tString text = \"preview\";\n}\n"));
        FxTestSupport.onFxThread(() -> {
            menu().fire();
            render();
            EditorCanvas first = canvas();
            assertEquals(Color.web("#0d0f12"), background(first));
            savePreview("dark");
            settings.darkThemeProperty().set(false);
            render();
            assertEquals(Color.web("#faf9f5"), background(first));
            Button close = (Button) root.lookup("#close-canvas-preview");
            assertEquals(Color.web("#413c36"), close.getTextFill());
            savePreview("light");
            close.fire();
            render();
            assertNull(root.lookup("#canvas-preview"));
            assertEquals("Show Canvas Preview", menu().getText());
            menu().fire();
            render();
            assertNotEquals(first, canvas());
            assertEquals(Color.web("#faf9f5"), background(canvas()));
            return null;
        });
    }

    @Test
    void editsAndSaveAsKeepScrollWhileSuccessfulOpenAndClearResetIt() throws Exception {
        String text = ("line" + " long".repeat(30) + "\n").repeat(100);
        Path first = Files.writeString(directory.resolve("first.txt"), text);
        Path same = Files.writeString(directory.resolve("same.txt"), text);
        Path destination = directory.resolve("saved.txt");
        open(first);
        FxTestSupport.onFxThread(() -> {
            menu().fire();
            render();
            canvas().scrollTo(200);
            canvas().scrollToX(100);
            render();
            document.session.replaceText(0, 1, "L");
            render();
            assertEquals(200, canvas().scrollY());
            assertEquals(100, canvas().scrollX());
            document.view.area().undo();
            document.session.save(destination, false, false, () -> { }, error -> { throw new AssertionError(error); });
            return null;
        });
        awaitIdle();
        FxTestSupport.onFxThread(() -> {
            render();
            assertEquals(200, canvas().scrollY());
            assertEquals(100, canvas().scrollX());
            document.session.open(directory.resolve("missing.txt"), () -> { }, error -> { });
            return null;
        });
        awaitIdle();
        FxTestSupport.onFxThread(() -> {
            render();
            assertEquals(200, canvas().scrollY());
            assertEquals(100, canvas().scrollX());
            return null;
        });
        open(same);
        FxTestSupport.onFxThread(() -> {
            render();
            assertEquals(0, canvas().scrollY(), "Identical text in another document must reset the viewport");
            assertEquals(0, canvas().scrollX());
            canvas().scrollTo(200);
            canvas().scrollToX(100);
            document.session.clearDocument();
            render();
            assertEquals(0, canvas().scrollY());
            assertEquals(0, canvas().scrollX());
            assertEquals(1, document.session.lineCount());
            assertEquals("", document.session.lineText(0));
            return null;
        });
        assertEquals(text, Files.readString(destination));
    }

    @Test
    void closingDetachesListenersAndPendingLayoutsCannotReadClosedSession() throws Exception {
        FxTestSupport.onFxThread(() -> {
            menu().fire();
            render();
            EditorCanvas retained = canvas();
            menu().fire();
            document.session.replaceText("later edits");
            document.session.clearDocument();
            settings.darkThemeProperty().set(false);
            document.close();
            retained.resize(300, 200);
            retained.layout();
            assertEquals(Color.web("#0d0f12"), background(retained));
            return null;
        });
    }

    @Test
    void closingDocumentWithVisiblePreviewMakesPendingCanvasLayoutSafe() throws Exception {
        FxTestSupport.onFxThread(() -> {
            menu().fire();
            render();
            EditorCanvas retained = canvas();
            document.session.replaceText("pending repaint\n");
            document.close();
            retained.resize(400, 200);
            retained.layout();
            assertEquals(0, retained.scrollY());
            settings.darkThemeProperty().set(false);
            retained.layout();
            assertEquals(Color.web("#0d0f12"), background(retained));
            return null;
        });
    }

    private void open(Path file) throws Exception {
        FxTestSupport.onFxThread(() -> {
            document.session.open(file, document.view::documentOpened,
                    error -> { throw new AssertionError(error); });
            return null;
        });
        awaitIdle();
    }

    private void awaitIdle() throws Exception {
        FxTestSupport.await(() -> !document.session.busyProperty().get());
    }

    private MenuItem menu() {
        return document.view.area().getContextMenu().getItems().stream()
                .filter(item -> "toggle-canvas-preview".equals(item.getId())).findFirst().orElseThrow();
    }

    private EditorCanvas canvas() {
        return (EditorCanvas) root.lookup("#canvas-preview-surface");
    }

    private void render() {
        root.getStylesheets().setAll(
                getClass().getResource("/styles/shell.css").toExternalForm(),
                getClass().getResource("/styles/editor.css").toExternalForm(),
                getClass().getResource(settings.darkThemeProperty().get()
                        ? "/styles/dark.css" : "/styles/light.css").toExternalForm());
        root.applyCss();
        root.layout();
    }

    private static Color background(EditorCanvas canvas) {
        WritableImage image = canvas.snapshot(null, null);
        return image.getPixelReader().getColor((int) image.getWidth() - 1, (int) image.getHeight() - 1);
    }

    private static long pixels(EditorCanvas canvas) {
        WritableImage image = canvas.snapshot(null, null);
        long hash = 1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                hash = 31 * hash + image.getPixelReader().getArgb(x, y);
            }
        }
        return hash;
    }

    private void savePreview(String theme) throws Exception {
        WritableImage image = root.snapshot(null, null);
        BufferedImage output = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < output.getHeight(); y++) {
            for (int x = 0; x < output.getWidth(); x++) {
                output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            }
        }
        Path target = Path.of("build", "editor-canvas-preview", "live-" + theme + ".png");
        Files.createDirectories(target.getParent());
        ImageIO.write(output, "png", target.toFile());
    }
}
