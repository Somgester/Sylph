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
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Stage;
import javafx.scene.layout.BorderPane;
import javafx.scene.text.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditorChromeTest {

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @ParameterizedTest
    @ValueSource(ints = {640, 1100})
    void toolbarTextButtonsAreReadableAndUnclippedInBothThemes(int width) throws Exception {
        FxTestSupport.onFxThread(() -> {
            EditorSession session = new EditorSession(new EditorFileService());
            EditorView editor = new EditorView(session);
            try {
                EditorChrome chrome = new EditorChrome(session, settings(), editor, command -> { });
                BorderPane root = new BorderPane();
                root.setId("root");
                root.setTop(chrome);
                Scene scene = new Scene(root, width, 70);
                javafx.scene.paint.Paint previous = null;
                for (String theme : List.of("dark", "light")) {
                    scene.getStylesheets().setAll(getClass().getResource("/styles/" + theme + ".css")
                            .toExternalForm(), getClass().getResource("/styles/shell.css").toExternalForm());
                    root.applyCss();
                    root.layout();
                    Button kiro = chrome.kiroButton();
                    assertTrue(kiro.isVisible());
                    assertEquals("Kiro", ((Text) kiro.lookup(".text")).getText());
                    assertTrue(kiro.getWidth() >= 52);
                    assertTrue(kiro.localToScene(kiro.getBoundsInLocal()).getMaxX() <= scene.getWidth());
                    Button sessions = chrome.kiroSessionsButton();
                    assertTrue(sessions.isVisible());
                    assertEquals("Sessions", ((Text) sessions.lookup(".text")).getText());
                    assertTrue(sessions.getWidth() >= 76);
                    assertEquals(kiro.getTextFill(), sessions.getTextFill());
                    assertTrue(sessions.localToScene(sessions.getBoundsInLocal()).getMaxX() <= scene.getWidth());
                    Button account = (Button) chrome.lookup("#open-account");
                    assertTrue(account.isVisible());
                    assertEquals("", account.getText());
                    assertEquals("Account: guest", account.getAccessibleText());
                    assertTrue(account.getGraphic() != null);
                    assertEquals(28, account.getWidth());
                    var bounds = account.localToScene(account.getBoundsInLocal());
                    assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= scene.getWidth());
                    if (previous != null) {
                        assertFalse(previous.equals(kiro.getTextFill()));
                    }
                    previous = kiro.getTextFill();
                }
                return null;
            } finally {
                editor.close();
                session.close();
            }
        });
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
                items.get(1).fire();
                items.get(4).fire();
                items.get(5).fire();
                assertEquals(List.of(EditorShortcuts.Command.NEW_FILE, EditorShortcuts.Command.OPEN_FILE,
                        EditorShortcuts.Command.SAVE, EditorShortcuts.Command.SAVE_AS), calls);
                assertEquals(EditorShortcuts.Command.SAVE_AS.shortcut(), items.get(5).getAccelerator());
                assertEquals(EditorShortcuts.Command.OPEN_FILE.shortcut(), items.get(1).getAccelerator());
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

    @Test
    void editMenuPastesIntoFocusedTextFieldInsteadOfTheDocument() throws Exception {
        FxTestSupport.onFxThread(() -> {
            EditorSession session = new EditorSession(new EditorFileService());
            EditorView editor = new EditorView(session);
            Stage stage = new Stage();
            try {
                editor.area().replaceText("keep-document");
                editor.area().selectAll();
                EditorChrome chrome = new EditorChrome(session, settings(), editor, command -> { });
                TextField input = new TextField("old-input");
                BorderPane root = new BorderPane(input, chrome, null, null, null);
                stage.setScene(new Scene(root));
                stage.show();
                input.requestFocus();
                input.selectAll();
                ClipboardContent clipboard = new ClipboardContent();
                clipboard.putString("field-input");
                Clipboard.getSystemClipboard().setContent(clipboard);
                var edit = ((MenuBar) chrome.lookup("#main-menu")).getMenus().get(1);
                edit.getItems().get(5).fire();
                assertEquals("field-input", input.getText());
                assertEquals("keep-document", editor.area().getText());
                return null;
            } finally {
                stage.hide();
                editor.close();
                session.close();
            }
        });
    }

    private static AppSettings settings() {
        return new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 2)));
    }
}
