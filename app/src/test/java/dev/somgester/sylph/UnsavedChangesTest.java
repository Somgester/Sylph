package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UnsavedChangesTest {

    private static final String ORIGINAL = "original";

    private static final String EDITED = "unsaved edits";

    private static final class ScriptedApp extends EditorApp {

        private final Queue<Supplier<File>> openChoices = new ArrayDeque<>();

        private final Queue<Supplier<File>> saveChoices = new ArrayDeque<>();

        private Consumer<Throwable> errorHandler = error -> { throw new AssertionError(error); };

        private int handledErrors;

        @Override
        File chooseOpenFile() {
            return openChoices.remove().get();
        }

        @Override
        File chooseSaveFile(Path destination) {
            return saveChoices.remove().get();
        }

        @Override
        void showFileError(Throwable error) {
            errorHandler.accept(error);
            handledErrors++;
        }
    }

    @TempDir
    Path directory;

    private ScriptedApp app;

    private Stage stage;

    private Scene scene;

    private CodeArea editor;

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

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void discardOnCloseNeverWritesWhileTheRealConfirmationIsOpenOrAfterClosing(int delay) throws Exception {
        Path file = createAndOpen(delay);
        FxTestSupport.onFxThread(() -> {
            editor.replaceText(EDITED);
            answerConfirmationAfterAutosaveWouldHaveRun(ButtonBar.ButtonData.NO, file);
            stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
            assertFalse(stage.isShowing());
            pumpPickerEvents(file);
            return null;
        });
        assertNoDialogFailure();
        assertEquals(ORIGINAL, Files.readString(file));
    }

    @Test
    void cancellingTheConfirmationKeepsEditsAndRestartsAutosave() throws Exception {
        Path file = createAndOpen(1);
        FxTestSupport.onFxThread(() -> {
            editor.replaceText(EDITED);
            answerConfirmationAfterAutosaveWouldHaveRun(ButtonBar.ButtonData.CANCEL_CLOSE, file);
            stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
            assertTrue(stage.isShowing());
            assertEquals(EDITED, editor.getText());
            assertTrue(settings.autoSaveProperty().get());
            assertEquals(ORIGINAL, Files.readString(file));
            return null;
        });
        assertNoDialogFailure();
        FxTestSupport.await(() -> Files.readString(file).equals(EDITED));
    }

    @Test
    void discardWhenOpeningKeepsTheOldFileUntouchedThroughThePickerAndFileSwitch() throws Exception {
        Path file = createAndOpen(1);
        Path next = Files.writeString(directory.resolve("next.txt"), "next file");
        FxTestSupport.onFxThread(() -> {
            editor.replaceText(EDITED);
            answerConfirmationAfterAutosaveWouldHaveRun(ButtonBar.ButtonData.NO, file);
            app.openChoices.add(() -> {
                pumpPickerEvents(file);
                return next.toFile();
            });
            EditorShortcutsTest.press(scene, KeyCode.O, false, false, true);
            return null;
        });
        FxTestSupport.await(() -> editor.getText().equals("next file"));
        assertNoDialogFailure();
        assertEquals(ORIGINAL, Files.readString(file));
    }

    @Test
    void cancellingTheOpenPickerAfterDiscardKeepsEditsAndRestartsAutosave() throws Exception {
        Path file = createAndOpen(1);
        FxTestSupport.onFxThread(() -> {
            editor.replaceText(EDITED);
            answerConfirmationAfterAutosaveWouldHaveRun(ButtonBar.ButtonData.NO, file);
            app.openChoices.add(() -> {
                pumpPickerEvents(file);
                return null;
            });
            EditorShortcutsTest.press(scene, KeyCode.O, false, false, true);
            assertEquals(ORIGINAL, Files.readString(file));
            assertEquals(EDITED, editor.getText());
            return null;
        });
        assertNoDialogFailure();
        FxTestSupport.await(() -> Files.readString(file).equals(EDITED));
    }

    @Test
    void cancellingSaveAsKeepsAutosavePausedUntilThePickerCloses() throws Exception {
        Path file = createAndOpen(1);
        FxTestSupport.onFxThread(() -> {
            editor.replaceText(EDITED);
            app.saveChoices.add(() -> {
                pumpPickerEvents(file);
                return null;
            });
            EditorShortcutsTest.press(scene, KeyCode.S, true, false, true);
            assertEquals(ORIGINAL, Files.readString(file));
            return null;
        });
        FxTestSupport.await(() -> Files.readString(file).equals(EDITED));
    }

    @Test
    void choosingSaveStillWritesTheChangesBeforeClosing() throws Exception {
        Path file = createAndOpen(1);
        FxTestSupport.onFxThread(() -> {
            editor.replaceText(EDITED);
            answerConfirmationAfterAutosaveWouldHaveRun(ButtonBar.ButtonData.YES, file);
            stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
            return null;
        });
        FxTestSupport.await(() -> !stage.isShowing());
        assertNoDialogFailure();
        assertEquals(EDITED, Files.readString(file));
    }

    @Test
    void choosingSaveBeforeOpeningWritesTheOldFileAndCompletesTheFileSwitch() throws Exception {
        Path file = createAndOpen(1);
        Path next = Files.writeString(directory.resolve("next.txt"), "next file");
        FxTestSupport.onFxThread(() -> {
            editor.replaceText(EDITED);
            answerConfirmationAfterAutosaveWouldHaveRun(ButtonBar.ButtonData.YES, file);
            app.openChoices.add(next::toFile);
            EditorShortcutsTest.press(scene, KeyCode.O, false, false, true);
            return null;
        });
        FxTestSupport.await(() -> editor.getText().equals("next file"));
        assertNoDialogFailure();
        assertEquals(EDITED, Files.readString(file));
        FxTestSupport.onFxThread(() -> {
            editor.replaceText("new file edits");
            return null;
        });
        FxTestSupport.await(() -> Files.readString(next).equals("new file edits"));
    }

    @Test
    void failedFileSwitchKeepsAutosavePausedThroughTheErrorThenRestoresIt() throws Exception {
        Path file = createAndOpen(1);
        FxTestSupport.onFxThread(() -> {
            editor.replaceText(EDITED);
            answerConfirmationAfterAutosaveWouldHaveRun(ButtonBar.ButtonData.NO, file);
            app.openChoices.add(() -> directory.resolve("missing.txt").toFile());
            app.errorHandler = error -> pumpPickerEvents(file);
            EditorShortcutsTest.press(scene, KeyCode.O, false, false, true);
            return null;
        });
        FxTestSupport.await(() -> app.handledErrors == 1);
        assertNoDialogFailure();
        assertEquals(EDITED, FxTestSupport.onFxThread(editor::getText));
        FxTestSupport.await(() -> Files.readString(file).equals(EDITED));
    }

    private Path createAndOpen(int delay) throws Exception {
        Path file = Files.writeString(directory.resolve("original.txt"), ORIGINAL);
        FxTestSupport.onFxThread(() -> {
            app = new ScriptedApp();
            stage = new Stage();
            settings = new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, true, delay)));
            scene = app.createScene(stage, settings);
            stage.show();
            editor = (CodeArea) scene.getRoot().lookup("#editor");
            app.openChoices.add(file::toFile);
            EditorShortcutsTest.press(scene, KeyCode.O, false, false, true);
            return null;
        });
        FxTestSupport.await(() -> editor.getText().equals(ORIGINAL));
        return file;
    }

    private void answerConfirmationAfterAutosaveWouldHaveRun(ButtonBar.ButtonData choice, Path file) {
        PauseTransition delay = new PauseTransition(Duration.millis(1300));
        delay.setOnFinished(event -> {
            try {
                assertEquals(ORIGINAL, Files.readString(file));
            } catch (Throwable failure) {
                dialogFailure = failure;
            } finally {
                for (Window window : java.util.List.copyOf(Window.getWindows())) {
                    if (window.getScene().getRoot() instanceof DialogPane pane) {
                        ButtonType button = pane.getButtonTypes().stream()
                                .filter(type -> type.getButtonData() == choice).findFirst().orElseThrow();
                        ((Button) pane.lookupButton(button)).fire();
                        break;
                    }
                }
            }
        });
        delay.play();
    }

    private static void pumpPickerEvents(Path file) {
        Object loop = new Object();
        PauseTransition delay = new PauseTransition(Duration.millis(1300));
        delay.setOnFinished(event -> Platform.exitNestedEventLoop(loop, null));
        delay.play();
        Platform.enterNestedEventLoop(loop);
        try {
            assertEquals(ORIGINAL, Files.readString(file));
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    private void assertNoDialogFailure() {
        if (dialogFailure != null) {
            throw new AssertionError("Autosave wrote while the confirmation was open", dialogFailure);
        }
    }
}
