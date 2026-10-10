package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorLanguageIntegrationTest {

    private static final class TestApp extends EditorApp {

        private Path openChoice;

        private Path saveChoice;

        private final List<Throwable> errors = new ArrayList<>();

        @Override
        File chooseOpenFile() {
            return openChoice == null ? null : openChoice.toFile();
        }

        @Override
        File chooseSaveFile(Path destination) {
            return saveChoice == null ? null : saveChoice.toFile();
        }

        @Override
        void showFileError(Throwable error) {
            errors.add(error);
        }
    }

    @TempDir
    Path directory;

    private TestApp app;

    private AppSettings settings;

    private Stage stage;

    private Scene scene;

    private CodeArea editor;

    private Label language;

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @BeforeEach
    void createEditor() throws Exception {
        FxTestSupport.onFxThread(() -> {
            app = new TestApp();
            settings = new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 1)));
            stage = new Stage();
            scene = app.createScene(stage, settings);
            stage.show();
            editor = (CodeArea) scene.getRoot().lookup("#editor");
            language = (Label) scene.getRoot().lookup("#document-language");
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.onFxThread(() -> {
            app.closeResources();
            stage.hide();
            return null;
        });
    }

    @Test
    void successfulOpensUpdateLanguageEvenWhenDocumentTextIsIdentical() throws Exception {
        assertEquals("Plain Text", FxTestSupport.onFxThread(language::getText));
        for (String name : List.of("Sample.JAVA", "config.json", "notes.txt")) {
            Path file = Files.writeString(directory.resolve(name), "{}\n");
            open(file);
            FxTestSupport.await(() -> stage.getTitle().equals(name + " — Sylph"));
            FxTestSupport.onFxThread(() -> {
                assertEquals(EditorLanguage.forPath(file).displayName(), language.getText());
                assertEquals("{}\n", editor.getText());
                assertFalse(editor.isUndoAvailable());
                return null;
            });
        }
    }

    @Test
    void failedAndCancelledOpensKeepTheCurrentLanguageAndDocument() throws Exception {
        Path file = Files.writeString(directory.resolve("Sample.java"), "class Sample {}\n");
        open(file);
        FxTestSupport.await(() -> language.getText().equals("Java"));
        open(directory.resolve("missing.json"));
        FxTestSupport.await(() -> app.errors.size() == 1);
        open(null);
        FxTestSupport.onFxThread(() -> {
            assertEquals("Java", language.getText());
            assertEquals("class Sample {}\n", editor.getText());
            assertEquals("Sample.java — Sylph", stage.getTitle());
            assertFalse(editor.isUndoAvailable());
            return null;
        });
    }

    @Test
    void saveAsChangesLanguageOnlyOnSuccessAndPreservesSelectionAndHistory() throws Exception {
        FxTestSupport.onFxThread(() -> {
            editor.replaceText("{}");
            editor.selectRange(0, 1);
            assertEquals("Plain Text", language.getText());
            return null;
        });
        Path json = directory.resolve("config.json");
        saveAs(json);
        FxTestSupport.await(() -> stage.getTitle().equals("config.json — Sylph"));
        saveAs(null);
        saveAs(directory.resolve("missing-parent/Sample.java"));
        FxTestSupport.await(() -> app.errors.size() == 1);
        FxTestSupport.onFxThread(() -> {
            assertEquals("JSON", language.getText());
            assertEquals("config.json — Sylph", stage.getTitle());
            assertEquals("{", editor.getSelectedText());
            assertTrue(editor.isUndoAvailable());
            return null;
        });
        Path plain = directory.resolve("notes.txt");
        saveAs(plain);
        FxTestSupport.await(() -> stage.getTitle().equals("notes.txt — Sylph"));
        assertEquals("{}", Files.readString(json));
        assertEquals("{}", Files.readString(plain));
        FxTestSupport.onFxThread(() -> {
            assertEquals("Plain Text", language.getText());
            assertEquals("{", editor.getSelectedText());
            editor.undo();
            assertEquals("", editor.getText());
            editor.redo();
            assertEquals("{}", editor.getText());
            assertEquals("notes.txt — Sylph", stage.getTitle());
            return null;
        });
    }

    @Test
    void languageIndicatorUsesTheActiveThemeAndFitsTheMinimumWindowWidth() throws Exception {
        FxTestSupport.onFxThread(() -> {
            stage.setWidth(640);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            var darkText = language.getTextFill();
            settings.darkThemeProperty().set(false);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            assertNotEquals(darkText, language.getTextFill());
            assertEquals("Plain Text", language.getText());
            assertTrue(language.getWidth() >= language.prefWidth(-1));
            var bounds = language.localToScene(language.getBoundsInLocal());
            assertTrue(bounds.getMaxX() <= scene.getWidth());
            return null;
        });
    }

    private void open(Path file) throws Exception {
        FxTestSupport.onFxThread(() -> {
            app.openChoice = file;
            ((Button) scene.getRoot().lookup("#open-file")).fire();
            return null;
        });
    }

    private void saveAs(Path destination) throws Exception {
        FxTestSupport.onFxThread(() -> {
            app.saveChoice = destination;
            EditorShortcutsTest.press(scene, KeyCode.S, true, false, true);
            return null;
        });
    }
}
