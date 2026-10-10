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
import java.util.concurrent.atomic.AtomicReference;
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
    void mixedEditsUndoRedoAndWholeReplacementMatchFreshColors() throws Exception {
        open(Files.writeString(directory.resolve("Example.java"), JAVA_SOURCE));
        awaitColors(JAVA_SOURCE);
        String changed = FxTestSupport.onFxThread(() -> {
            int number = view.area().getText().indexOf("42");
            view.area().replaceText(number, number + 2, "\"café 🚀\"");
            int comment = view.area().getText().indexOf("String text");
            view.area().insertText(comment, "/* ");
            int close = view.area().getText().indexOf("// note");
            view.area().insertText(close, "*/\n    ");
            return view.area().getText();
        });
        awaitColors(changed);
        String undone = FxTestSupport.onFxThread(() -> {
            view.area().undo();
            return view.area().getText();
        });
        awaitColors(undone);
        FxTestSupport.onFxThread(() -> {
            view.area().redo();
            assertEquals(changed, view.area().getText());
            return null;
        });
        awaitColors(changed);
        String replacement = changed.replace("Example", "Another");
        FxTestSupport.onFxThread(() -> {
            // RichTextFX discards styles throughout this whole replacement, even in matching text.
            view.area().replaceText(replacement);
            return null;
        });
        awaitColors(replacement);
        FxTestSupport.onFxThread(() -> {
            view.area().clear();
            assertEquals("", view.area().getText());
            view.area().replaceText(JAVA_SOURCE);
            return null;
        });
        awaitColors(JAVA_SOURCE);
    }

    @Test
    void saveAsChangesGrammarWithoutResettingSelectionOrUndo() throws Exception {
        String json = "{\"message\": \"hello\", \"answer\": 42}";
        open(Files.writeString(directory.resolve("data.txt"), json));
        Path destination = directory.resolve("data.json");
        FxTestSupport.onFxThread(() -> {
            view.area().appendText("\n");
            view.area().selectRange(json.indexOf("hello"), json.indexOf("hello") + 5);
            assertTrue(view.area().getStyleOfChar(json.indexOf("hello")).isEmpty());
            session.save(destination, false, false, () -> { }, error -> { throw new AssertionError(error); });
            return null;
        });
        FxTestSupport.await(() -> session.completedOperationsProperty().get() == 2);
        awaitColors(json + "\n");
        FxTestSupport.onFxThread(() -> {
            assertEquals(EditorLanguage.JSON, session.languageProperty().get());
            assertEquals("hello", view.area().getSelectedText());
            assertTrue(view.undoAvailableProperty().get());
            assertFalse(session.dirtyProperty().get());
            return null;
        });
        assertEquals(json + "\n", Files.readString(destination));
    }

    @Test
    void failedOpenKeepsCurrentTextLanguageAndColors() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), JAVA_SOURCE);
        open(file);
        awaitColors(JAVA_SOURCE);
        var colors = FxTestSupport.onFxThread(() -> view.area().getStyleSpans(0, view.area().getLength()));
        Path unsupported = Files.writeString(directory.resolve("unsupported.json"), "{\"value\": \"\u007f\"}");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        FxTestSupport.onFxThread(() -> {
            session.open(unsupported, view::documentOpened, failure::set);
            return null;
        });
        FxTestSupport.await(() -> failure.get() != null);
        FxTestSupport.onFxThread(() -> {
            assertEquals(file, session.pathProperty().get());
            assertEquals(EditorLanguage.JAVA, session.languageProperty().get());
            assertEquals(JAVA_SOURCE, view.area().getText());
            assertEquals(colors, view.area().getStyleSpans(0, view.area().getLength()));
            assertFalse(session.dirtyProperty().get());
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

    private void awaitColors(String source) throws Exception {
        EditorLanguage language = FxTestSupport.onFxThread(() -> session.languageProperty().get());
        var tokenizer = new SyntaxTokenizer();
        var result = tokenizer.tokenize(source, language);
        if (!result.complete()) {
            result = tokenizer.tokenize(source, language);
        }
        assertTrue(result.complete());
        var expected = SyntaxStyles.spans(result);
        FxTestSupport.await(() -> {
            if (!source.equals(view.area().getText())) {
                return false;
            }
            for (int offset = 0; offset < source.length(); offset++) {
                if (source.charAt(offset) != '\n' && !view.area().getStyleOfChar(offset)
                        .equals(expected.subView(offset, offset + 1).getStyleSpan(0).getStyle())) {
                    return false;
                }
            }
            return true;
        });
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
