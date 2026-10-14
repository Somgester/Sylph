package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.animation.PauseTransition;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CommandPaletteTest {

    private static final class ScriptedApp extends EditorApp {
        private File choice;

        @Override
        File chooseOpenFile() {
            return choice;
        }

        @Override
        void showFileError(Throwable error) {
            throw new AssertionError(error);
        }
    }

    @TempDir
    Path directory;

    private ScriptedApp app;

    private Stage stage;

    private AppSettings settings;

    private Throwable dialogFailure;

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.onFxThread(() -> {
            if (app != null) {
                app.closeResources();
                stage.hide();
            }
            return null;
        });
    }

    @Test
    void toolbarEntryIsCenteredWithoutOverlappingActionsInBothThemesAndWindowSizes() throws Exception {
        create();
        FxTestSupport.onFxThread(() -> {
            for (boolean dark : new boolean[] {true, false}) {
                settings.darkThemeProperty().set(dark);
                for (int width : new int[] {640, 1100}) {
                    stage.setWidth(width);
                    var root = stage.getScene().getRoot();
                    root.resize(width, 700);
                    root.applyCss();
                    root.layout();
                    var toolbar = root.lookup("#toolbar");
                    var entry = root.lookup("#open-palette");
                    var bounds = entry.localToScene(entry.getBoundsInLocal());
                    var toolbarBounds = toolbar.localToScene(toolbar.getBoundsInLocal());
                    assertEquals((toolbarBounds.getMinX() + toolbarBounds.getMaxX()) / 2,
                            (bounds.getMinX() + bounds.getMaxX()) / 2, 1);
                    assertTrue(bounds.getWidth() > 60);
                    assertTrue(root.lookup("#save-file").localToScene(
                            root.lookup("#save-file").getBoundsInLocal()).getMaxX() < bounds.getMinX());
                    assertTrue(bounds.getMaxX() < root.lookup("#toggle-sidebar").localToScene(
                            root.lookup("#toggle-sidebar").getBoundsInLocal()).getMinX());
                }
            }
            ((Button) stage.getScene().lookup("#open-palette")).fire();
            assertTrue(palette().isVisible());
            assertEquals("", query().getText());
            key(KeyCode.ESCAPE);
            assertFalse(palette().isVisible());
            return null;
        });
    }

    @Test
    void fileSearchOpensNestedFileAndLatestQueryWins() throws Exception {
        Path nested = Files.createDirectories(directory.resolve("src"));
        Path selected = Files.writeString(nested.resolve("hello.py"), "print('hello')");
        Files.writeString(directory.resolve("other.txt"), "other");
        create();
        FxTestSupport.onFxThread(() -> {
            var sidebar = (Sidebar) stage.getScene().lookup("#sidebar");
            app.openFolder(directory, sidebar, (javafx.scene.control.Label) stage.getScene().lookup("#status"));
            return null;
        });
        FxTestSupport.await(() -> ((Sidebar) stage.getScene().lookup("#sidebar")).getRootDirectory() != null);
        FxTestSupport.onFxThread(() -> {
            EditorShortcutsTest.press(stage.getScene(), KeyCode.P, false, false, true);
            query().setText("other");
            query().setText("SRC hello");
            return null;
        });
        FxTestSupport.await(() -> results().getItems().size() == 1);
        FxTestSupport.onFxThread(() -> {
            key(KeyCode.ENTER);
            return null;
        });
        FxTestSupport.await(() -> editor().getText().equals("print('hello')"));
        assertTrue(FxTestSupport.onFxThread(() -> stage.getTitle().contains(selected.getFileName().toString())));
        assertFalse(FxTestSupport.onFxThread(() -> palette().isVisible()));
    }

    @Test
    void commandSearchFiltersWordsAndEscapePreservesEditorAndFocus() throws Exception {
        create();
        FxTestSupport.onFxThread(() -> {
            editor().replaceText("keep these edits");
            editor().requestFocus();
            EditorShortcutsTest.press(stage.getScene(), KeyCode.P, true, false, true);
            query().setText(">toggle auto");
            assertEquals(1, results().getItems().size());
            key(KeyCode.ENTER);
            assertTrue(settings.autoSaveProperty().get());
            assertEquals("keep these edits", editor().getText());
            EditorShortcutsTest.press(stage.getScene(), KeyCode.P, true, false, true);
            key(KeyCode.ESCAPE);
            assertSame(editor(), stage.getScene().getFocusOwner());
            assertEquals("keep these edits", editor().getText());
            return null;
        });
    }

    @ParameterizedTest
    @EnumSource(value = ButtonBar.ButtonData.class, names = {"NO", "CANCEL_CLOSE", "YES"})
    void reloadHonorsUnsavedDecisionAndRestoresFolderAndFile(ButtonBar.ButtonData decision) throws Exception {
        Path file = Files.writeString(directory.resolve("document.txt"), "original");
        create();
        FxTestSupport.onFxThread(() -> {
            app.choice = file.toFile();
            var sidebar = (Sidebar) stage.getScene().lookup("#sidebar");
            app.openFolder(directory, sidebar, (javafx.scene.control.Label) stage.getScene().lookup("#status"));
            EditorShortcutsTest.press(stage.getScene(), KeyCode.O, false, false, true);
            return null;
        });
        FxTestSupport.await(() -> editor().getText().equals("original")
                && !stage.getScene().lookup("#open-file").isDisabled());
        Scene before = FxTestSupport.onFxThread(() -> stage.getScene());
        javafx.scene.control.RadioButton oldDark = FxTestSupport.onFxThread(() -> {
            EditorShortcutsTest.press(stage.getScene(), KeyCode.COMMA, false, false, true);
            stage.getScene().getRoot().applyCss();
            var control = (javafx.scene.control.RadioButton) stage.getScene().lookup("#theme-dark");
            EditorShortcutsTest.press(stage.getScene(), KeyCode.COMMA, false, false, true);
            return control;
        });
        FxTestSupport.onFxThread(() -> {
            settings.autoSaveProperty().set(true);
            settings.delaySecondsProperty().set(1);
            editor().replaceText("unsaved");
            EditorShortcutsTest.press(stage.getScene(), KeyCode.P, true, false, true);
            query().setText(">reload");
            assertEquals(1, results().getItems().size());
            PauseTransition delay = new PauseTransition(Duration.millis(1200));
            delay.setOnFinished(event -> {
                try {
                    assertEquals("original", read(file));
                } catch (Throwable failure) {
                    dialogFailure = failure;
                } finally {
                    for (Window window : java.util.List.copyOf(Window.getWindows())) {
                        if (window.getScene().getRoot() instanceof DialogPane dialog) {
                            var choice = dialog.getButtonTypes().stream()
                                    .filter(type -> type.getButtonData() == decision).findFirst().orElseThrow();
                            ((Button) dialog.lookupButton(choice)).fire();
                            break;
                        }
                    }
                }
            });
            delay.play();
            key(KeyCode.ENTER);
            return null;
        });
        if (dialogFailure != null) {
            throw new AssertionError(dialogFailure);
        }
        if (decision == ButtonBar.ButtonData.CANCEL_CLOSE) {
            assertSame(before, FxTestSupport.onFxThread(() -> stage.getScene()));
            assertEquals("unsaved", FxTestSupport.onFxThread(() -> editor().getText()));
            assertEquals("original", Files.readString(file));
        } else {
            FxTestSupport.await(() -> stage.getScene() != before
                    && !stage.getScene().lookup("#open-file").isDisabled()
                    && editor().getText().equals(decision == ButtonBar.ButtonData.YES ? "unsaved" : "original"));
            assertNotSame(before, FxTestSupport.onFxThread(() -> stage.getScene()));
            FxTestSupport.await(() -> directory.equals(
                    ((Sidebar) stage.getScene().lookup("#sidebar")).getRootDirectory()));
            assertEquals(decision == ButtonBar.ButtonData.YES ? "unsaved" : "original", Files.readString(file));
            FxTestSupport.onFxThread(() -> {
                settings.darkThemeProperty().set(false);
                assertTrue(stage.getScene().getStylesheets().getFirst().endsWith("light.css"));
                assertTrue(oldDark.isSelected(), "Disposed settings must stop observing the shared preferences");
                return null;
            });
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private void create() throws Exception {
        FxTestSupport.onFxThread(() -> {
            app = new ScriptedApp();
            settings = new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 2)));
            stage = new Stage();
            app.createScene(stage, settings);
            stage.show();
            return null;
        });
    }

    private CommandPalette palette() {
        return (CommandPalette) stage.getScene().lookup("#command-palette");
    }

    private TextField query() {
        return (TextField) stage.getScene().lookup("#palette-query");
    }

    private ListView<?> results() {
        return (ListView<?>) stage.getScene().lookup("#palette-results");
    }

    private CodeArea editor() {
        return (CodeArea) stage.getScene().lookup("#editor");
    }

    private void key(KeyCode key) {
        query().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false));
    }
}
