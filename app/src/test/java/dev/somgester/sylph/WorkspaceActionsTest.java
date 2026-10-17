package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeCell;
import javafx.geometry.Side;
import javafx.scene.paint.Color;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class WorkspaceActionsTest {

    private static final class ScriptedApp extends EditorApp {
        private String name;

        private File openChoice;

        private final List<Throwable> errors = new ArrayList<>();

        @Override
        String chooseWorkspaceName(boolean folder, Path parent) {
            return name;
        }

        @Override
        File chooseOpenFile() {
            return openChoice;
        }

        @Override
        void showFileError(Throwable error) {
            errors.add(error);
        }
    }

    @TempDir
    Path directory;

    private ScriptedApp app;

    private Stage stage;

    private Scene scene;

    private Sidebar sidebar;

    private AppSettings settings;

    private Throwable dialogFailure;

    private final List<PauseTransition> timers = new ArrayList<>();

    private final List<String> answeredDialogs = new ArrayList<>();

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.onFxThread(() -> {
            timers.forEach(PauseTransition::stop);
            if (app != null) {
                app.closeResources();
                stage.hide();
            }
            return null;
        });
    }

    @Test
    void selectedFileUsesThemeColorsWithSidebarAndEditorFocus() throws Exception {
        Path file = Files.writeString(directory.resolve("selected.txt"), "selected");
        create();
        showFolder();
        open(file);
        FxTestSupport.onFxThread(() -> {
            sidebar.getTreeView().getSelectionModel().select(find(sidebar.getTreeView().getRoot(), file));
            for (boolean dark : new boolean[] {true, false}) {
                settings.darkThemeProperty().set(dark);
                for (boolean sidebarFocus : new boolean[] {true, false}) {
                    if (sidebarFocus) {
                        sidebar.getTreeView().requestFocus();
                    } else {
                        editor().requestFocus();
                    }
                    scene.getRoot().applyCss();
                    scene.getRoot().layout();
                    scene.getRoot().applyCss();
                    TreeCell<?> cell = sidebar.getTreeView().lookupAll(".tree-cell").stream()
                            .filter(node -> node instanceof TreeCell<?> row && file.equals(row.getItem()))
                            .map(node -> (TreeCell<?>) node).findFirst().orElseThrow();
                    assertTrue(cell.isSelected());
                    assertEquals(Color.web(dark ? "#06b6d4" : "#a9583e"), cell.getTextFill());
                    assertEquals(Color.web(dark ? "#242936" : "#e8e0d2"),
                            cell.getBackground().getFills().getFirst().getFill());
                }
            }
            return null;
        });
    }

    @Test
    void createButtonsRequireWorkspaceAndNewFileOpensWithCleanHistory() throws Exception {
        create();
        FxTestSupport.onFxThread(() -> {
            assertTrue(scene.lookup("#new-workspace-file").isDisabled());
            assertTrue(scene.lookup("#new-workspace-folder").isDisabled());
            return null;
        });
        showFolder();
        FxTestSupport.onFxThread(() -> {
            app.name = "new.cpp";
            ((Button) scene.lookup("#new-workspace-file")).fire();
            return null;
        });
        Path file = directory.resolve("new.cpp");
        FxTestSupport.await(() -> stage.getTitle().equals("new.cpp — Sylph") && ready());
        FxTestSupport.await(() -> find(sidebar.getTreeView().getRoot(), file) != null);
        assertEquals("", Files.readString(file));
        FxTestSupport.onFxThread(() -> {
            assertFalse(editor().isUndoAvailable());
            assertEquals("C++", ((Label) scene.lookup("#document-language")).getText());
            editor().appendText("int answer = 42;");
            EditorShortcutsTest.press(scene, KeyCode.S, false, false, true);
            return null;
        });
        FxTestSupport.await(() -> Files.readString(file).equals("int answer = 42;") && ready());
        assertTrue(app.errors.isEmpty());
    }

    @Test
    void newFolderPreservesUnsavedDocumentAndRefreshesWithoutLosingOtherExpandedNodes() throws Exception {
        Path existing = Files.createDirectory(directory.resolve("existing"));
        Files.writeString(existing.resolve("keep.txt"), "keep");
        create();
        showFolder();
        TreeItem<Path> original = FxTestSupport.onFxThread(() -> {
            var node = find(sidebar.getTreeView().getRoot(), existing);
            node.setExpanded(true);
            return node;
        });
        FxTestSupport.await(() -> original.getChildren().stream().anyMatch(node -> node.getValue() != null));
        FxTestSupport.onFxThread(() -> {
            editor().replaceText("untitled edits");
            app.name = "new-folder";
            ((Button) scene.lookup("#new-workspace-folder")).fire();
            return null;
        });
        Path folder = directory.resolve("new-folder");
        FxTestSupport.await(() -> find(sidebar.getTreeView().getRoot(), folder) != null && ready());
        FxTestSupport.onFxThread(() -> {
            assertEquals("untitled edits", editor().getText());
            assertTrue(stage.getTitle().startsWith("* Untitled"));
            assertSame(original, find(sidebar.getTreeView().getRoot(), existing));
            assertTrue(original.isExpanded());
            app.name = "nested.txt";
            ((Button) scene.lookup("#new-workspace-file")).fire();
            return null;
        });
        FxTestSupport.await(() -> stage.getTitle().equals("nested.txt — Sylph") && ready());
        assertTrue(Files.exists(folder.resolve("nested.txt")));
        assertTrue(app.errors.isEmpty());
    }

    @Test
    void duplicateOrCancelledCreationPreservesExistingFileAndAllowsRetry() throws Exception {
        Path file = Files.writeString(directory.resolve("keep.txt"), "keep");
        create();
        showFolder();
        FxTestSupport.onFxThread(() -> {
            app.name = "keep.txt";
            ((Button) scene.lookup("#new-workspace-file")).fire();
            return null;
        });
        FxTestSupport.await(() -> app.errors.size() == 1 && ready());
        assertEquals("keep", Files.readString(file));
        FxTestSupport.onFxThread(() -> {
            app.name = null;
            ((Button) scene.lookup("#new-workspace-file")).fire();
            assertTrue(ready());
            app.name = "retry.txt";
            ((Button) scene.lookup("#new-workspace-file")).fire();
            return null;
        });
        FxTestSupport.await(() -> stage.getTitle().equals("retry.txt — Sylph") && ready());
    }

    @ParameterizedTest
    @EnumSource(value = ButtonBar.ButtonData.class, names = {"NO", "CANCEL_CLOSE", "YES"})
    void deleteActiveFolderProtectsUnsavedEditsAndPausesAutosave(ButtonBar.ButtonData decision) throws Exception {
        Path folder = Files.createDirectory(directory.resolve("remove"));
        Path file = Files.writeString(folder.resolve("active.txt"), "original");
        create();
        showFolder();
        open(file);
        FxTestSupport.onFxThread(() -> {
            settings.autoSaveProperty().set(true);
            settings.delaySecondsProperty().set(1);
            editor().replaceText("unsaved");
            answer(ButtonBar.ButtonData.YES, 1400, file, "original", "Delete workspace entry");
            answer(decision, 1200, file, "original", "Unsaved changes");
            app.workspaceAction(Sidebar.WorkspaceAction.DELETE, folder);
            return null;
        });
        if (dialogFailure != null) {
            throw new AssertionError(dialogFailure);
        }
        if (decision == ButtonBar.ButtonData.CANCEL_CLOSE) {
            assertTrue(Files.exists(file));
            assertEquals("unsaved", FxTestSupport.onFxThread(() -> editor().getText()));
        } else {
            FxTestSupport.await(() -> !Files.exists(folder) && ready() && stage.getTitle().equals("Untitled — Sylph"));
            FxTestSupport.onFxThread(() -> {
                assertEquals("", editor().getText());
                assertFalse(editor().isUndoAvailable());
                return null;
            });
        }
        assertTrue(app.errors.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"false, CANCEL_CLOSE", "true, CANCEL_CLOSE", "false, NO", "true, NO"})
    void deletingDocumentOpenedThroughParentAliasProtectsEdits(boolean folder, ButtonBar.ButtonData decision,
            @TempDir Path aliases) throws Exception {
        Path parent = Files.createDirectory(directory.resolve("source"));
        Path file = Files.writeString(parent.resolve("active.txt"), "original");
        Path alias = Files.createSymbolicLink(aliases.resolve("workspace"), directory);
        create();
        showFolder();
        open(alias.resolve("source/active.txt"));
        FxTestSupport.onFxThread(() -> {
            editor().replaceText("unsaved");
            answer(ButtonBar.ButtonData.YES, 60, file, "original", "Delete workspace entry");
            answer(decision, 30, file, "original", "Unsaved changes");
            app.workspaceAction(Sidebar.WorkspaceAction.DELETE, folder ? parent : file);
            assertTrue(answeredDialogs.contains("Unsaved changes"));
            return null;
        });
        if (decision == ButtonBar.ButtonData.CANCEL_CLOSE) {
            assertEquals("original", Files.readString(file));
            FxTestSupport.onFxThread(() -> {
                assertEquals("unsaved", editor().getText());
                assertTrue(stage.getTitle().startsWith("* active.txt"));
                assertFalse(answeredDialogs.contains("Delete workspace entry"));
                assertTrue(ready());
                return null;
            });
        } else {
            FxTestSupport.await(() -> !Files.exists(file) && ready() && stage.getTitle().equals("Untitled — Sylph"));
            FxTestSupport.onFxThread(() -> {
                assertEquals("", editor().getText());
                assertFalse(editor().isUndoAvailable());
                return null;
            });
        }
        assertTrue(app.errors.isEmpty());
        assertEquals(null, dialogFailure);
    }

    @Test
    void rowContextMenuDeletesItsOwnTargetAndProtectsTheRoot() throws Exception {
        Path file = Files.writeString(directory.resolve("remove.txt"), "remove");
        create();
        showFolder();
        FxTestSupport.onFxThread(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            for (var node : sidebar.getTreeView().lookupAll(".tree-cell")) {
                if (node instanceof TreeCell<?> cell && directory.equals(cell.getItem())) {
                    var menu = cell.getContextMenu();
                    menu.show(cell, Side.BOTTOM, 0, 0);
                    assertTrue(menu.getItems().get(2).isDisable());
                    menu.hide();
                }
            }
            TreeCell<?> cell = sidebar.getTreeView().lookupAll(".tree-cell").stream()
                    .filter(node -> node instanceof TreeCell<?> row && file.equals(row.getItem()))
                    .map(node -> (TreeCell<?>) node).findFirst().orElseThrow();
            var menu = cell.getContextMenu();
            menu.show(cell, Side.BOTTOM, 0, 0);
            assertFalse(menu.getItems().get(2).isDisable());
            answer(ButtonBar.ButtonData.YES, 30, file, "remove", "Delete workspace entry");
            menu.getItems().get(2).fire();
            menu.hide();
            return null;
        });
        FxTestSupport.await(() -> !Files.exists(file) && ready());
        FxTestSupport.await(() -> find(sidebar.getTreeView().getRoot(), file) == null);
        assertTrue(app.errors.isEmpty());
    }

    @Test
    void deletingFolderThenRecreatingNameAsFileDoesNotReuseCachedChildren() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("recreated"));
        Files.writeString(folder.resolve("old.txt"), "old");
        create();
        showFolder();
        FxTestSupport.onFxThread(() -> {
            find(sidebar.getTreeView().getRoot(), folder).setExpanded(true);
            return null;
        });
        FxTestSupport.await(() -> find(sidebar.getTreeView().getRoot(), folder).getChildren().stream()
                .anyMatch(child -> child.getValue() != null));
        FxTestSupport.onFxThread(() -> {
            answer(ButtonBar.ButtonData.YES, 30, folder.resolve("old.txt"), "old", "Delete workspace entry");
            app.workspaceAction(Sidebar.WorkspaceAction.DELETE, folder);
            return null;
        });
        FxTestSupport.await(() -> !Files.exists(folder) && ready());
        FxTestSupport.onFxThread(() -> {
            app.name = "recreated";
            app.workspaceAction(Sidebar.WorkspaceAction.NEW_FILE, directory);
            return null;
        });
        FxTestSupport.await(() -> stage.getTitle().equals("recreated — Sylph") && ready());
        FxTestSupport.await(() -> find(sidebar.getTreeView().getRoot(), folder) != null);
        FxTestSupport.onFxThread(() -> {
            assertTrue(find(sidebar.getTreeView().getRoot(), folder).isLeaf());
            return null;
        });
        assertTrue(Files.isRegularFile(folder));
        assertTrue(app.errors.isEmpty());
    }

    @Test
    void failedDeletionKeepsTheActiveDocumentAndItsUndoHistory() throws Exception {
        Path file = Files.writeString(directory.resolve("missing.txt"), "original");
        create();
        showFolder();
        open(file);
        FxTestSupport.onFxThread(() -> {
            editor().appendText(" edits");
            return null;
        });
        Files.delete(file);
        FxTestSupport.onFxThread(() -> {
            answer(ButtonBar.ButtonData.YES, 60, null, "", "Delete workspace entry");
            answer(ButtonBar.ButtonData.NO, 30, null, "", "Unsaved changes");
            app.workspaceAction(Sidebar.WorkspaceAction.DELETE, file);
            return null;
        });
        FxTestSupport.await(() -> app.errors.size() == 1 && ready());
        FxTestSupport.onFxThread(() -> {
            assertEquals("original edits", editor().getText());
            assertTrue(editor().isUndoAvailable());
            editor().undo();
            assertEquals("original", editor().getText());
            return null;
        });
    }

    @Test
    void cancelledDeleteKeepsFileAndRootDeleteIsRejectedWithoutADialog() throws Exception {
        Path file = Files.writeString(directory.resolve("keep.txt"), "keep");
        create();
        showFolder();
        FxTestSupport.onFxThread(() -> {
            answer(ButtonBar.ButtonData.CANCEL_CLOSE, 30, file, "keep", "Delete workspace entry");
            app.workspaceAction(Sidebar.WorkspaceAction.DELETE, file);
            assertTrue(ready());
            app.workspaceAction(Sidebar.WorkspaceAction.DELETE, directory);
            assertTrue(ready());
            return null;
        });
        assertEquals("keep", Files.readString(file));
        assertTrue(Files.exists(directory));
    }

    private void answer(ButtonBar.ButtonData choice, int milliseconds, Path protectedFile, String expected,
            String title) {
        PauseTransition delay = new PauseTransition(Duration.millis(milliseconds));
        timers.add(delay);
        delay.setOnFinished(event -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window instanceof Stage dialog && title.equals(dialog.getTitle())
                        && dialog.getScene().getRoot() instanceof DialogPane pane) {
                    try {
                        if (protectedFile != null && !Files.readString(protectedFile).equals(expected)
                                && choice != ButtonBar.ButtonData.YES) {
                            throw new AssertionError("Autosave changed disk during confirmation");
                        }
                    } catch (Throwable failure) {
                        dialogFailure = failure;
                    } finally {
                        var button = pane.getButtonTypes().stream().filter(type -> type.getButtonData() == choice)
                                .findFirst().orElseThrow();
                        answeredDialogs.add(title);
                        ((Button) pane.lookupButton(button)).fire();
                    }
                }
            }
        });
        delay.play();
    }

    private void create() throws Exception {
        FxTestSupport.onFxThread(() -> {
            app = new ScriptedApp();
            stage = new Stage();
            settings = new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 2)));
            scene = app.createScene(stage, settings);
            stage.show();
            sidebar = (Sidebar) scene.lookup("#sidebar");
            return null;
        });
    }

    private void showFolder() throws Exception {
        FxTestSupport.onFxThread(() -> {
            app.openFolder(directory, sidebar, (Label) scene.lookup("#status"));
            return null;
        });
        long count;
        try (var entries = Files.list(directory)) {
            count = entries.count();
        }
        long expected = count;
        FxTestSupport.await(() -> sidebar.getTreeView().getRoot().getChildren().size() == expected
                && sidebar.getTreeView().getRoot().getChildren().stream().noneMatch(node -> node.getValue() == null));
    }

    private void open(Path file) throws Exception {
        FxTestSupport.onFxThread(() -> {
            app.openChoice = file.toFile();
            EditorShortcutsTest.press(scene, KeyCode.O, false, false, true);
            return null;
        });
        FxTestSupport.await(() -> stage.getTitle().equals(file.getFileName() + " — Sylph") && ready());
    }

    private boolean ready() {
        return !scene.lookup("#open-file").isDisabled();
    }

    private CodeArea editor() {
        return app.activeEditor();
    }

    private static TreeItem<Path> find(TreeItem<Path> node, Path path) {
        if (path.equals(node.getValue())) {
            return node;
        }
        for (TreeItem<Path> child : node.getChildren()) {
            TreeItem<Path> result = find(child, path);
            if (result != null) {
                return result;
            }
        }
        return null;
    }
}
