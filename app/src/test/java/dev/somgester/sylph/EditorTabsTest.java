package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.Region;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EditorTabsTest {

    private static final class TestApp extends EditorApp {
        private Path choice;

        private Path saveChoice;

        private int savePrompts;

        private Consumer<Path> onChooseSave = previous -> { };

        private final List<Throwable> errors = new ArrayList<>();

        @Override
        File chooseOpenFile() {
            return choice.toFile();
        }

        @Override
        File chooseSaveFile(Path previous) {
            savePrompts++;
            onChooseSave.accept(previous);
            return saveChoice == null ? null : saveChoice.toFile();
        }

        @Override
        boolean confirmWorkspaceDelete(Path target) {
            return true;
        }

        @Override
        void showFileError(Throwable error) {
            errors.add(error);
        }
    }

    @TempDir
    Path directory;

    private TestApp app;

    private Scene scene;

    private Stage stage;

    private AppSettings settings;

    private final List<PauseTransition> timers = new ArrayList<>();

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
    void filesKeepIndependentEditsSelectionHistoryAndSaveTargets() throws Exception {
        create(false);
        Path first = Files.writeString(directory.resolve("first.java"), "class First {}");
        Path second = Files.writeString(directory.resolve("second.py"), "number = 42");
        open(first);
        CodeArea original = FxTestSupport.onFxThread(app::activeEditor);
        Tab firstTab = FxTestSupport.onFxThread(() -> tabs().getSelectionModel().getSelectedItem());
        FxTestSupport.onFxThread(() -> {
            original.appendText(" // edit");
            original.selectRange(1, 4);
            return null;
        });
        open(second);
        FxTestSupport.onFxThread(() -> {
            assertEquals(2, tabs().getTabs().size());
            assertTrue(firstTab.isClosable());
            assertTrue(firstTab.getText().endsWith(" •"));
            assertNotSame(original, app.activeEditor());
            assertFalse(app.activeEditor().isUndoAvailable());
            app.activeEditor().appendText(" # edit");
            tabs().getSelectionModel().select(firstTab);
            assertSame(original, app.activeEditor());
            assertEquals("las", original.getSelectedText());
            assertTrue(original.isUndoAvailable());
            EditorShortcutsTest.press(scene, KeyCode.S, false, false, true);
            return null;
        });
        FxTestSupport.await(() -> ready() && Files.readString(first).endsWith(" // edit"));
        assertEquals("number = 42", Files.readString(second));
        FxTestSupport.onFxThread(() -> {
            tabs().getSelectionModel().selectLast();
            assertEquals("number = 42 # edit", app.activeEditor().getText());
            app.activeEditor().undo();
            assertEquals("number = 42", app.activeEditor().getText());
            return null;
        });
    }

    @Test
    void reopeningAnExistingFileSelectsItsTabWithoutReloadingOrLosingEdits() throws Exception {
        create(false);
        Path first = Files.writeString(directory.resolve("first.txt"), "first");
        Path second = Files.writeString(directory.resolve("second.txt"), "second");
        open(first);
        CodeArea original = FxTestSupport.onFxThread(app::activeEditor);
        FxTestSupport.onFxThread(() -> {
            original.appendText(" pending");
            return null;
        });
        open(second);
        open(first);
        FxTestSupport.onFxThread(() -> {
            assertEquals(2, tabs().getTabs().size());
            assertSame(original, app.activeEditor());
            assertEquals("first pending", original.getText());
            return null;
        });
    }

    @ParameterizedTest
    @EnumSource(value = ButtonBar.ButtonData.class, names = {"YES", "NO", "CANCEL_CLOSE"})
    void closingAnInactiveDirtyTabHonorsSaveDiscardAndCancel(ButtonBar.ButtonData decision) throws Exception {
        create(false);
        Path first = Files.writeString(directory.resolve("first.txt"), "original");
        Path second = Files.writeString(directory.resolve("second.txt"), "second");
        open(first);
        Tab tab = FxTestSupport.onFxThread(() -> {
            app.activeEditor().replaceText("changed");
            return tabs().getSelectionModel().getSelectedItem();
        });
        open(second);
        FxTestSupport.onFxThread(() -> {
            answer(decision);
            Event.fireEvent(tab, new Event(Tab.TAB_CLOSE_REQUEST_EVENT));
            return null;
        });
        FxTestSupport.await(this::ready);
        FxTestSupport.onFxThread(() -> {
            boolean cancelled = decision == ButtonBar.ButtonData.CANCEL_CLOSE;
            assertEquals(cancelled ? 2 : 1, tabs().getTabs().size());
            assertEquals(cancelled, tabs().getTabs().contains(tab));
            assertEquals(cancelled ? "changed" : "second", app.activeEditor().getText());
            return null;
        });
        assertEquals(decision == ButtonBar.ButtonData.YES ? "changed" : "original", Files.readString(first));
    }

    @Test
    void autosaveContinuesForInactiveTabs() throws Exception {
        create(true);
        Path first = Files.writeString(directory.resolve("first.txt"), "first");
        Path second = Files.writeString(directory.resolve("second.txt"), "second");
        open(first);
        FxTestSupport.onFxThread(() -> {
            app.activeEditor().appendText(" saved in background");
            return null;
        });
        open(second);
        FxTestSupport.await(() -> Files.readString(first).equals("first saved in background"));
        assertEquals("second", Files.readString(second));
    }

    @Test
    void windowCloseChecksDirtyTabsEvenWhenSelectedFileIsClean() throws Exception {
        create(false);
        Path first = Files.writeString(directory.resolve("first.txt"), "first");
        Path second = Files.writeString(directory.resolve("second.txt"), "second");
        open(first);
        FxTestSupport.onFxThread(() -> {
            app.activeEditor().appendText(" pending");
            return null;
        });
        open(second);
        FxTestSupport.onFxThread(() -> {
            answer(ButtonBar.ButtonData.CANCEL_CLOSE);
            stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
            assertTrue(stage.isShowing());
            assertEquals("first pending", app.activeEditor().getText());
            assertEquals(2, tabs().getTabs().size());
            return null;
        });
    }

    @Test
    void saveAsCannotOverwriteAnotherOpenTabAndLastCloseLeavesEmptyEditor() throws Exception {
        create(false);
        Path first = Files.writeString(directory.resolve("first.txt"), "first");
        Path second = Files.writeString(directory.resolve("second.txt"), "second");
        open(first);
        open(second);
        FxTestSupport.onFxThread(() -> {
            app.saveChoice = first;
            EditorShortcutsTest.press(scene, KeyCode.S, true, false, true);
            assertEquals(1, app.errors.size());
            assertEquals("second.txt — Sylph", stage.getTitle());
            for (Tab tab : List.copyOf(tabs().getTabs())) {
                Event.fireEvent(tab, new Event(Tab.TAB_CLOSE_REQUEST_EVENT));
            }
            assertEmptyEditor();
            return null;
        });
        assertEquals("first", Files.readString(first));
        assertEquals("second", Files.readString(second));
    }

    @Test
    void modifiedUntitledDocumentAndEditMenuStayWithTheirTabs() throws Exception {
        create(false);
        CodeArea untitled = FxTestSupport.onFxThread(() -> {
            EditorShortcutsTest.press(scene, KeyCode.N, false, false, true);
            app.activeEditor().replaceText("untitled work");
            return app.activeEditor();
        });
        Path file = Files.writeString(directory.resolve("file.txt"), "file");
        open(file);
        FxTestSupport.onFxThread(() -> {
            app.activeEditor().appendText(" changed");
            tabs().getSelectionModel().selectFirst();
            Menu edit = ((MenuBar) scene.lookup("#main-menu")).getMenus().stream()
                    .filter(menu -> menu.getText().equals("Edit")).findFirst().orElseThrow();
            edit.getItems().stream().filter(item -> "Undo".equals(item.getText())).findFirst().orElseThrow().fire();
            assertEquals("", untitled.getText());
            tabs().getSelectionModel().selectLast();
            assertEquals("file changed", app.activeEditor().getText());
            return null;
        });
    }

    @Test
    void reloadRestoresAllFileTabsAndTheSelectedFile() throws Exception {
        create(false);
        Path first = Files.writeString(directory.resolve("first.txt"), "first");
        Path second = Files.writeString(directory.resolve("second.txt"), "second");
        open(first);
        open(second);
        CodeArea previous = FxTestSupport.onFxThread(() -> {
            tabs().getSelectionModel().selectFirst();
            CodeArea area = app.activeEditor();
            EditorShortcutsTest.press(scene, KeyCode.R, false, true, true);
            scene = stage.getScene();
            return area;
        });
        FxTestSupport.await(() -> ready() && tabs().getTabs().size() == 2
                && stage.getTitle().equals("first.txt — Sylph"));
        FxTestSupport.onFxThread(() -> {
            assertNotSame(previous, app.activeEditor());
            assertEquals("first", app.activeEditor().getText());
            tabs().getSelectionModel().selectLast();
            assertEquals("second", app.activeEditor().getText());
            return null;
        });
    }

    @Test
    void folderDeleteChecksEveryAffectedTabAndCancelPreservesAllDocuments() throws Exception {
        create(false);
        Path folder = Files.createDirectory(directory.resolve("source"));
        Path first = Files.writeString(folder.resolve("first.txt"), "first");
        Path second = Files.writeString(folder.resolve("second.txt"), "second");
        FxTestSupport.onFxThread(() -> {
            app.openFolder(directory, (Sidebar) scene.lookup("#sidebar"), (Label) scene.lookup("#status"));
            return null;
        });
        FxTestSupport.await(() -> directory.equals(((Sidebar) scene.lookup("#sidebar")).getRootDirectory()));
        open(first);
        CodeArea original = FxTestSupport.onFxThread(() -> {
            app.activeEditor().appendText(" pending");
            return app.activeEditor();
        });
        open(second);
        FxTestSupport.onFxThread(() -> {
            app.activeEditor().appendText(" pending");
            answer(ButtonBar.ButtonData.NO);
            answer(ButtonBar.ButtonData.CANCEL_CLOSE, 180);
            app.workspaceAction(Sidebar.WorkspaceAction.DELETE, folder);
            assertEquals(2, tabs().getTabs().size());
            assertEquals("first pending", original.getText());
            assertEquals("second pending", app.activeEditor().getText());
            assertTrue(ready());
            return null;
        });
        assertEquals("first", Files.readString(first));
        assertEquals("second", Files.readString(second));
    }

    @Test
    void closeButtonsRemainVisibleAndFollowBothThemes() throws Exception {
        create(false);
        open(Files.writeString(directory.resolve("first.java"), "class First {}"));
        open(Files.writeString(directory.resolve("second.py"), "value = 2"));
        // JavaFX hides close controls during the tab-opening animation.
        FxTestSupport.await(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            var buttons = tabs().lookupAll(".tab-close-button");
            return buttons.size() == 2 && buttons.stream().allMatch(Node::isVisible);
        });
        FxTestSupport.onFxThread(() -> {
            Paint previous = null;
            for (boolean dark : new boolean[] {true, false}) {
                settings.darkThemeProperty().set(dark);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                var buttons = tabs().lookupAll(".tab-close-button");
                assertEquals(2, buttons.size());
                for (var node : buttons) {
                    assertTrue(node.isVisible());
                    assertTrue(node.getBoundsInLocal().getWidth() > 0);
                }
                var color = ((Region) buttons.iterator().next()).getBackground().getFills().getFirst().getFill();
                if (previous != null) {
                    assertFalse(previous.equals(color));
                }
                previous = color;
                saveScene("editor-tabs", dark);
            }
            return null;
        });
    }

    @Test
    void startupAndReloadStayEmptyUntilAnExplicitCreateOrOpen() throws Exception {
        create(true);
        FxTestSupport.onFxThread(() -> {
            assertEmptyEditor();
            assertTrue(scene.lookup("#save-file").isDisabled());
            for (boolean dark : new boolean[] {true, false}) {
                settings.darkThemeProperty().set(dark);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                saveScene("empty-editor", dark);
            }
            EditorShortcutsTest.press(scene, KeyCode.S, false, false, true);
            assertEquals(0, app.savePrompts);
            EditorShortcutsTest.press(scene, KeyCode.COMMA, false, false, true);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            assertTrue(((Label) scene.lookup("#autosave-state")).getText().contains("Open or create"));
            EditorShortcutsTest.press(scene, KeyCode.ESCAPE, false, false, false);
            assertEmptyEditor();
            EditorShortcutsTest.press(scene, KeyCode.R, false, true, true);
            scene = stage.getScene();
            assertEmptyEditor();
            return null;
        });
        open(Files.writeString(directory.resolve("opened.txt"), "opened"));
        FxTestSupport.onFxThread(() -> {
            assertEquals(1, tabs().getTabs().size());
            assertEquals("opened", app.activeEditor().getText());
            return null;
        });
    }

    @Test
    void doubleClickCreatesUnsavedFilesOnlyInEmptySpaceOrBlankTabBar() throws Exception {
        create(true);
        FxTestSupport.onFxThread(() -> {
            settings.delaySecondsProperty().set(0);
            doubleClick(scene.lookup("#empty-editor"));
            assertEquals(1, tabs().getTabs().size());
            assertEquals("Untitled — Sylph", stage.getTitle());
            app.activeEditor().replaceText("first unsaved buffer");
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            doubleClick(tabs().lookup(".tab-header-background"));
            assertEquals(2, tabs().getTabs().size());
            assertEquals("", app.activeEditor().getText());
            doubleClick(tabs().lookup(".tab"));
            assertEquals(2, tabs().getTabs().size());
            doubleClick(app.activeEditor());
            assertEquals(2, tabs().getTabs().size());
            tabs().getSelectionModel().selectFirst();
            assertEquals("first unsaved buffer", app.activeEditor().getText());
            assertEquals(0, app.savePrompts);
            return null;
        });
        FxTestSupport.onFxThread(() -> {
            assertEquals(0, app.savePrompts);
            assertTrue(stage.getTitle().startsWith("* Untitled"));
            return null;
        });
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void cancelledFirstSaveKeepsBufferAndAutosaveStartsOnlyAfterConfirmedLocation() throws Exception {
        create(true);
        Path destination = directory.resolve("chosen.py");
        FxTestSupport.onFxThread(() -> {
            settings.delaySecondsProperty().set(0);
            EditorShortcutsTest.press(scene, KeyCode.N, false, false, true);
            app.activeEditor().replaceText("unsaved text");
            app.onChooseSave = previous -> {
                assertNull(previous);
                assertFalse(Files.exists(destination));
                assertTrue(stage.getTitle().startsWith("* Untitled"));
            };
            EditorShortcutsTest.press(scene, KeyCode.S, false, false, true);
            assertEquals(1, app.savePrompts);
            assertTrue(stage.getTitle().startsWith("* Untitled"));
            assertEquals("unsaved text", app.activeEditor().getText());
            return null;
        });
        FxTestSupport.onFxThread(() -> {
            assertFalse(Files.exists(destination));
            assertEquals(1, app.savePrompts);
            app.saveChoice = destination;
            EditorShortcutsTest.press(scene, KeyCode.S, false, false, true);
            return null;
        });
        FxTestSupport.await(() -> ready() && stage.getTitle().equals("chosen.py — Sylph"));
        assertEquals("unsaved text", Files.readString(destination));
        FxTestSupport.onFxThread(() -> {
            assertEquals("Python file", tabs().getSelectionModel().getSelectedItem().getGraphic().getAccessibleText());
            app.activeEditor().replaceText("autosaved text");
            return null;
        });
        FxTestSupport.await(() -> Files.readString(destination).equals("autosaved text") && ready());
        assertEquals(2, app.savePrompts);
        assertTrue(app.errors.isEmpty());
    }

    @Test
    void contextMenuClosesTheClickedTabAndCloseAllLeavesNoReplacement() throws Exception {
        create(false);
        Path first = Files.writeString(directory.resolve("first.py"), "first");
        Path second = Files.writeString(directory.resolve("second.py"), "second");
        open(first);
        open(second);
        FxTestSupport.onFxThread(() -> {
            Tab clicked = tabs().getTabs().getFirst();
            assertEquals(List.of("Close Tab", "Close All Tabs"), clicked.getContextMenu().getItems().stream()
                    .map(item -> item.getText()).toList());
            clicked.getContextMenu().getItems().getFirst().fire();
            assertEquals(1, tabs().getTabs().size());
            assertEquals("second", app.activeEditor().getText());
            tabs().getTabs().getFirst().getContextMenu().getItems().getLast().fire();
            assertEmptyEditor();
            return null;
        });
        open(first);
        FxTestSupport.onFxThread(() -> {
            assertEquals(1, tabs().getTabs().size());
            return null;
        });
    }

    @ParameterizedTest
    @EnumSource(value = ButtonBar.ButtonData.class, names = {"YES", "NO", "CANCEL_CLOSE"})
    void closeAllChecksEveryDirtyTabBeforeRemovingAny(ButtonBar.ButtonData secondDecision) throws Exception {
        create(false);
        Path saved = Files.writeString(directory.resolve("saved.txt"), "original");
        Path destination = directory.resolve("new.txt");
        open(saved);
        FxTestSupport.onFxThread(() -> {
            app.activeEditor().replaceText("saved tab edit");
            EditorShortcutsTest.press(scene, KeyCode.N, false, false, true);
            app.activeEditor().replaceText("new tab edit");
            app.saveChoice = destination;
            answer(ButtonBar.ButtonData.NO);
            answer(secondDecision, 160);
            tabs().getTabs().getFirst().getContextMenu().getItems().getLast().fire();
            return null;
        });
        FxTestSupport.await(this::ready);
        FxTestSupport.onFxThread(() -> {
            if (secondDecision == ButtonBar.ButtonData.CANCEL_CLOSE) {
                assertEquals(2, tabs().getTabs().size());
                assertEquals("new tab edit", app.activeEditor().getText());
                tabs().getSelectionModel().selectFirst();
                assertEquals("saved tab edit", app.activeEditor().getText());
            } else {
                assertEmptyEditor();
            }
            return null;
        });
        assertEquals("original", Files.readString(saved));
        if (secondDecision == ButtonBar.ButtonData.YES) {
            assertEquals("new tab edit", Files.readString(destination));
        } else {
            assertFalse(Files.exists(destination));
        }
    }

    @Test
    void cancellingSaveAsDuringCloseAllKeepsAllTabsAndUnsavedContent() throws Exception {
        create(true);
        FxTestSupport.onFxThread(() -> {
            settings.delaySecondsProperty().set(0);
            EditorShortcutsTest.press(scene, KeyCode.N, false, false, true);
            app.activeEditor().replaceText("keep me");
            EditorShortcutsTest.press(scene, KeyCode.N, false, false, true);
            answer(ButtonBar.ButtonData.YES);
            tabs().getTabs().getLast().getContextMenu().getItems().getLast().fire();
            assertEquals(2, tabs().getTabs().size());
            assertEquals("keep me", app.activeEditor().getText());
            assertTrue(ready());
            assertEquals(1, app.savePrompts);
            return null;
        });
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void closeAllSuspendsAutosaveWhileDiscardDecisionIsOpen() throws Exception {
        create(true);
        Path file = Files.writeString(directory.resolve("saved.txt"), "original");
        open(file);
        FxTestSupport.onFxThread(() -> {
            app.activeEditor().replaceText("discard this edit");
            answer(ButtonBar.ButtonData.NO, 1300);
            tabs().getTabs().getFirst().getContextMenu().getItems().getLast().fire();
            assertEmptyEditor();
            return null;
        });
        assertEquals("original", Files.readString(file));
    }

    @Test
    void failedFirstSaveDuringCloseAllKeepsEveryTabAndReportsError() throws Exception {
        create(true);
        FxTestSupport.onFxThread(() -> {
            EditorShortcutsTest.press(scene, KeyCode.N, false, false, true);
            app.activeEditor().replaceText("keep me after error");
            EditorShortcutsTest.press(scene, KeyCode.N, false, false, true);
            app.saveChoice = directory.resolve("missing/fail.txt");
            answer(ButtonBar.ButtonData.YES);
            tabs().getTabs().getLast().getContextMenu().getItems().getLast().fire();
            return null;
        });
        FxTestSupport.await(() -> ready() && !app.errors.isEmpty());
        FxTestSupport.onFxThread(() -> {
            assertEquals(2, tabs().getTabs().size());
            assertEquals("keep me after error", app.activeEditor().getText());
            assertTrue(stage.getTitle().startsWith("* Untitled"));
            return null;
        });
        assertFalse(Files.exists(directory.resolve("missing/fail.txt")));
    }

    private void assertEmptyEditor() {
        assertTrue(tabs().getTabs().isEmpty());
        assertNull(app.activeEditor());
        assertEquals("Sylph", stage.getTitle());
        assertEquals("", ((Label) scene.lookup("#document-title")).getText());
        assertTrue(scene.lookup("#empty-editor").isVisible());
    }

    private void saveScene(String report, boolean dark) throws Exception {
        var image = scene.snapshot(null);
        BufferedImage output = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < output.getHeight(); y++) {
            for (int x = 0; x < output.getWidth(); x++) {
                output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            }
        }
        Path path = Path.of("build", "reports", report, dark ? "dark.png" : "light.png");
        Files.createDirectories(path.getParent());
        ImageIO.write(output, "png", path.toFile());
    }

    private static void doubleClick(Node target) {
        Event.fireEvent(target, new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 2,
                false, false, false, false, false, false, false, false, false, true,
                new PickResult(target, 0, 0)));
    }

    private void create(boolean autosave) throws Exception {
        FxTestSupport.onFxThread(() -> {
            app = new TestApp();
            settings = new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, autosave, 1)));
            stage = new Stage();
            scene = app.createScene(stage, settings);
            stage.show();
            return null;
        });
    }

    private void open(Path file) throws Exception {
        FxTestSupport.onFxThread(() -> {
            app.choice = file;
            EditorShortcutsTest.press(scene, KeyCode.O, false, false, true);
            return null;
        });
        FxTestSupport.await(() -> stage.getTitle().endsWith(file.getFileName() + " — Sylph") && ready());
        FxTestSupport.await(() -> app.activeEditor().isFocused());
        FxTestSupport.onFxThread(() -> {
            assertEquals(tabs().getSelectionModel().getSelectedItem().getText(),
                    ((Label) scene.lookup("#document-title")).getText());
            return null;
        });
    }

    private boolean ready() {
        return !scene.lookup("#open-file").isDisabled();
    }

    private TabPane tabs() {
        return (TabPane) scene.lookup("#editor-tabs");
    }

    private void answer(ButtonBar.ButtonData decision) {
        answer(decision, 80);
    }

    private void answer(ButtonBar.ButtonData decision, int milliseconds) {
        PauseTransition timer = new PauseTransition(Duration.millis(milliseconds));
        timers.add(timer);
        timer.setOnFinished(event -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window.getScene().getRoot() instanceof DialogPane pane) {
                    var choice = pane.getButtonTypes().stream()
                            .filter(type -> type.getButtonData() == decision).findFirst().orElseThrow();
                    ((Button) pane.lookupButton(choice)).fire();
                }
            }
        });
        timer.play();
    }
}
