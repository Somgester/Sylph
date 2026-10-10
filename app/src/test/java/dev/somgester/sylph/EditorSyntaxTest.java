package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorSyntaxTest {

    private static final String JAVA_SOURCE = "public class Example {\n"
            + "    String text = \"hello\";\n"
            + "    int answer = 42;\n"
            + "    void run() {}\n"
            + "    // note\n}";

    @TempDir
    Path directory;

    private EditorSession session;

    private EditorView view;

    private AutoSaveController autoSave;

    private Scene scene;

    private Stage stage;

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @BeforeEach
    void createEditor() throws Exception {
        FxTestSupport.onFxThread(() -> {
            session = new EditorSession(new EditorFileService());
            view = new EditorView(session);
            scene = new Scene(view, 620, 280);
            applyTheme("dark");
            stage = new Stage();
            stage.setScene(scene);
            stage.show();
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.onFxThread(() -> {
            if (autoSave != null) {
                autoSave.close();
            }
            view.close();
            session.close();
            stage.hide();
            return null;
        });
    }

    @Test
    void actualHighlightingDoesNotDirtySaveOrAddUndoHistory() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), JAVA_SOURCE);
        open(file);
        FxTestSupport.onFxThread(() -> {
            var settings = new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, true, 0)));
            autoSave = new AutoSaveController(session, settings);
            view.area().selectRange(JAVA_SOURCE.indexOf("hello"), JAVA_SOURCE.indexOf("hello") + 5);
            return null;
        });
        FxTestSupport.await(() -> view.area().getStyleOfChar(JAVA_SOURCE.indexOf("hello"))
                .contains("syntax-string"));
        FxTestSupport.onFxThread(() -> {
            assertEquals(JAVA_SOURCE, session.textProperty().get());
            assertEquals("hello", view.area().getSelectedText());
            assertFalse(session.dirtyProperty().get());
            assertFalse(view.undoAvailableProperty().get());
            assertFalse(view.redoAvailableProperty().get());
            assertEquals(1, session.completedOperationsProperty().get());
            return null;
        });
        assertEquals(JAVA_SOURCE, Files.readString(file));
    }

    @Test
    void renderedSyntaxColorsSwitchThemesAndKeepSelectionAndUndo() throws Exception {
        open(Files.writeString(directory.resolve("Example.java"), JAVA_SOURCE));
        FxTestSupport.await(() -> view.area().getStyleOfChar(JAVA_SOURCE.indexOf("hello"))
                .contains("syntax-string"));
        FxTestSupport.onFxThread(() -> {
            view.area().appendText("\n// edited");
            view.area().selectRange(JAVA_SOURCE.indexOf("hello"), JAVA_SOURCE.indexOf("hello") + 5);
            return null;
        });
        FxTestSupport.await(() -> view.area().getStyleOfChar(view.area().getLength() - 1)
                .contains("syntax-comment"));
        FxTestSupport.onFxThread(() -> {
            Map<String, Color> previous = new HashMap<>();
            for (String theme : List.of("dark", "light", "dark")) {
                applyTheme(theme);
                render();
                Color background = (Color) view.area().getBackground().getFills().getFirst().getFill();
                var palette = new HashSet<Color>();
                for (String category : List.of("keyword", "type", "string", "number", "function", "comment")) {
                    Text text = styledText(category);
                    Color color = (Color) text.getFill();
                    assertTrue(palette.add(color), "Syntax categories must have distinct colors");
                    assertTrue(contrast(color, background) >= 4.5, theme + " " + category);
                    Color selection = theme.equals("dark") ? Color.web("#164e63") : Color.web("#d9d2c5");
                    assertTrue(contrast(color, selection) >= 4.5, theme + " selected " + category);
                    if (previous.containsKey(category)) {
                        assertNotEquals(previous.get(category), color);
                    }
                    previous.put(category, color);
                }
                assertEquals(JAVA_SOURCE + "\n// edited", session.textProperty().get());
                assertEquals("hello", view.area().getSelectedText());
                assertTrue(view.undoAvailableProperty().get());
                savePreview(theme);
            }
            view.area().undo();
            assertEquals(JAVA_SOURCE, session.textProperty().get());
            view.area().redo();
            assertEquals(JAVA_SOURCE + "\n// edited", session.textProperty().get());
            return null;
        });
    }

    @Test
    void jsonKeysAndValuesReceiveDistinctColorsAndPlainFilesClearThem() throws Exception {
        String json = "{\n  \"message\": \"hello\",\n  \"answer\": 42,\n  \"enabled\": true\n}";
        open(Files.writeString(directory.resolve("data.json"), json));
        FxTestSupport.await(() -> view.area().getStyleOfChar(json.indexOf("message")).contains("syntax-property"));
        FxTestSupport.onFxThread(() -> {
            assertTrue(view.area().getStyleOfChar(json.indexOf("hello")).contains("syntax-string"));
            assertTrue(view.area().getStyleOfChar(json.indexOf("42")).contains("syntax-number"));
            assertTrue(view.area().getStyleOfChar(json.indexOf("true")).contains("syntax-keyword"));
            for (String theme : List.of("dark", "light")) {
                applyTheme(theme);
                render();
                Color background = (Color) view.area().getBackground().getFills().getFirst().getFill();
                assertTrue(contrast((Color) styledText("property").getFill(), background) >= 4.5);
                assertNotEquals(styledText("property").getFill(), styledText("string").getFill());
            }
            return null;
        });
        open(Files.writeString(directory.resolve("data.txt"), json));
        FxTestSupport.onFxThread(() -> {
            assertEquals(EditorLanguage.PLAIN_TEXT, session.languageProperty().get());
            assertTrue(view.area().getStyleOfChar(json.indexOf("message")).isEmpty());
            assertEquals(json, session.textProperty().get());
            return null;
        });
    }

    private void open(Path file) throws Exception {
        int completed = FxTestSupport.onFxThread(() -> session.completedOperationsProperty().get());
        FxTestSupport.onFxThread(() -> {
            session.open(file, view::documentOpened, error -> { throw new AssertionError(error); });
            return null;
        });
        FxTestSupport.await(() -> session.completedOperationsProperty().get() == completed + 1);
    }

    private void applyTheme(String theme) {
        scene.getStylesheets().setAll(getClass().getResource("/styles/" + theme + ".css").toExternalForm(),
                getClass().getResource("/styles/editor.css").toExternalForm());
    }

    private void render() {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        scene.snapshot(null);
    }

    private Text styledText(String category) {
        return view.area().lookupAll(".paragraph-text .syntax-" + category).stream()
                .filter(Text.class::isInstance).map(Text.class::cast).findFirst().orElseThrow();
    }

    private void savePreview(String theme) throws Exception {
        var image = scene.snapshot(null);
        BufferedImage buffered = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < buffered.getHeight(); y++) {
            for (int x = 0; x < buffered.getWidth(); x++) {
                buffered.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            }
        }
        Path output = Path.of("build", "reports", "syntax-preview", theme + ".png");
        Files.createDirectories(output.getParent());
        ImageIO.write(buffered, "png", output.toFile());
    }

    private static double contrast(Color first, Color second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(Color color) {
        return 0.2126 * linear(color.getRed()) + 0.7152 * linear(color.getGreen()) + 0.0722 * linear(color.getBlue());
    }

    private static double linear(double component) {
        return component <= 0.04045 ? component / 12.92 : Math.pow((component + 0.055) / 1.055, 2.4);
    }
}
