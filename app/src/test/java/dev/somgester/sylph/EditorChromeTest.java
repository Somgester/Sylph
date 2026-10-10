package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.MenuBar;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EditorChromeTest {

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @Test
    void compactIconActionsSitAboveBrandAndHaveAccessibleNames() throws Exception {
        FxTestSupport.onFxThread(() -> {
            EditorSession session = new EditorSession(new EditorFileService());
            EditorView editor = new EditorView(session);
            try {
                List<EditorShortcuts.Command> calls = new ArrayList<>();
                EditorChrome chrome = new EditorChrome(session, settings(), editor, calls::add);
                BorderPane root = new BorderPane();
                root.setId("root");
                root.setTop(chrome);
                Scene scene = new Scene(root, 640, 70);
                scene.getStylesheets().setAll(getClass().getResource("/styles/dark.css").toExternalForm(),
                        getClass().getResource("/styles/shell.css").toExternalForm());
                root.applyCss();
                root.layout();
                for (String id : List.of("open-file", "open-folder", "save-file",
                        "toggle-sidebar", "open-settings")) {
                    Button button = (Button) chrome.lookup("#" + id);
                    assertEquals("", button.getText());
                    assertNotNull(button.getGraphic());
                    assertFalse(button.getAccessibleText().isBlank());
                    assertTrue(button.getTooltip().getText().contains("("));
                    assertTrue(button.getHeight() <= 28);
                    assertTrue(button.localToScene(button.getBoundsInLocal()).getMaxY()
                            < chrome.lookup("#brand").localToScene(
                                    chrome.lookup("#brand").getBoundsInLocal()).getMinY());
                    button.fire();
                }
                assertEquals(List.of(EditorShortcuts.Command.OPEN_FILE, EditorShortcuts.Command.OPEN_FOLDER,
                        EditorShortcuts.Command.SAVE,
                        EditorShortcuts.Command.SIDEBAR, EditorShortcuts.Command.SETTINGS), calls);
                assertEquals("Sylph", chrome.lookup("#brand").getAccessibleText());
                assertNull(chrome.lookup("#save-as"));
                return null;
            } finally {
                editor.close();
                session.close();
            }
        });
    }

    @Test
    void fileMenuUsesSameCommandsAndAutosaveCheckTracksSettings() throws Exception {
        FxTestSupport.onFxThread(() -> {
            EditorSession session = new EditorSession(new EditorFileService());
            EditorView editor = new EditorView(session);
            try {
                AppSettings settings = settings();
                List<EditorShortcuts.Command> calls = new ArrayList<>();
                EditorChrome chrome = new EditorChrome(session, settings, editor, calls::add);
                MenuBar menus = (MenuBar) chrome.lookup("#main-menu");
                var items = menus.getMenus().getFirst().getItems();
                items.get(0).fire();
                items.get(3).fire();
                items.get(4).fire();
                assertEquals(List.of(EditorShortcuts.Command.OPEN_FILE, EditorShortcuts.Command.SAVE,
                        EditorShortcuts.Command.SAVE_AS), calls);
                assertEquals(EditorShortcuts.Command.SAVE_AS.shortcut(), items.get(4).getAccelerator());
                assertEquals(EditorShortcuts.Command.OPEN_FILE.shortcut(), items.get(0).getAccelerator());
                CheckMenuItem autosave = (CheckMenuItem) items.getLast();
                settings.autoSaveProperty().set(true);
                assertTrue(autosave.isSelected());
                autosave.setSelected(false);
                assertFalse(settings.autoSaveProperty().get());
                return null;
            } finally {
                editor.close();
                session.close();
            }
        });
    }

    @Test
    void editMenuTracksTheNewUndoManagerAfterOpeningAnotherDocument() throws Exception {
        FxTestSupport.onFxThread(() -> {
            EditorSession session = new EditorSession(new EditorFileService());
            EditorView editor = new EditorView(session);
            try {
                EditorChrome chrome = new EditorChrome(session, settings(), editor, command -> { });
                MenuBar menus = (MenuBar) chrome.lookup("#main-menu");
                var items = menus.getMenus().get(1).getItems();
                var undo = items.get(0);
                var redo = items.get(1);
                editor.area().replaceText("document");
                editor.documentOpened();
                assertTrue(undo.isDisable());
                assertTrue(redo.isDisable());
                editor.area().appendText(" edit");
                assertFalse(undo.isDisable());
                undo.fire();
                assertEquals("document", editor.area().getText());
                assertFalse(redo.isDisable());
                editor.documentOpened();
                assertTrue(undo.isDisable());
                assertTrue(redo.isDisable());
                editor.area().appendText(" next");
                assertFalse(undo.isDisable());
                undo.fire();
                assertEquals("document", editor.area().getText());
                redo.fire();
                assertEquals("document next", editor.area().getText());
                assertTrue(items.get(3).isDisable());
                assertTrue(items.get(4).isDisable());
                items.getLast().fire();
                assertEquals("document next", editor.area().getSelectedText());
                assertFalse(items.get(3).isDisable());
                assertFalse(items.get(4).isDisable());
                return null;
            } finally {
                editor.close();
                session.close();
            }
        });
    }

    private static AppSettings settings() {
        return new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 2)));
    }
}
