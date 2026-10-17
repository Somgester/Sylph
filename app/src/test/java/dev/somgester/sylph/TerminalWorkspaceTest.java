package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledOnOs({OS.LINUX, OS.MAC})
class TerminalWorkspaceTest {

    private static final class ScriptedApp extends EditorApp {
        private File openChoice;

        private AppSettings settings;

        private TerminalView lastTerminal;

        @Override
        TerminalView createTerminal(Path directory) {
            var environment = new HashMap<>(System.getenv());
            environment.put("ENV", "/dev/null");
            environment.put("PS1", "");
            lastTerminal = new TerminalView(
                    new TerminalSession(directory, List.of("/bin/sh", "-i"), environment), settings);
            return lastTerminal;
        }

        @Override
        File chooseOpenFile() {
            return openChoice;
        }
    }

    @TempDir
    Path directory;

    private ScriptedApp app;

    private AppSettings settings;

    private Stage stage;

    private Scene scene;

    private CodeArea editor;

    private final List<TerminalSession> sessions = new ArrayList<>();

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
        for (TerminalSession session : sessions) {
            session.completion().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shortcutsOpenBothLayoutsAndTogglingPanelPreservesItsShellAndDocument() throws Exception {
        create(true);
        FxTestSupport.onFxThread(() -> {
            editor.replaceText("unsaved file stays here");
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, true, false, true);
            return null;
        });
        TerminalView bottom = selected("#terminal-panel");
        awaitShell(bottom);
        assertEquals(directory, bottom.session().directory());
        bottom.session().send("pwd > terminal-cwd\r");
        FxTestSupport.await(() -> Files.exists(directory.resolve("terminal-cwd"))
                && Files.readString(directory.resolve("terminal-cwd")).trim().equals(directory.toString()));
        FxTestSupport.onFxThread(() -> {
            TerminalWorkspace workspace = (TerminalWorkspace) scene.lookup("#terminal-workspace");
            assertEquals(2, workspace.getItems().size());
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, true, false, true);
            assertEquals(1, workspace.getItems().size());
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, true, false, true);
            assertEquals(2, workspace.getItems().size());
            assertSame(bottom, ((TabPane) scene.lookup("#terminal-panel")).getTabs().getFirst().getContent());
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, false, false, true);
            return null;
        });
        TerminalView top = selected("#editor-tabs");
        awaitShell(top);
        assertNotSame(bottom.session(), top.session());
        FxTestSupport.onFxThread(() -> {
            TabPane tabs = (TabPane) scene.lookup("#editor-tabs");
            tabs.getSelectionModel().selectFirst();
            assertEquals("unsaved file stays here", editor.getText());
            assertTrue(editor.isUndoAvailable());
            assertTrue(tabs.getTabs().getFirst().getText().contains("•"));
            return null;
        });
        FxTestSupport.await(editor::isFocused);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateStartsAnIndependentShellAndClosingTabsDisposesThem(boolean inEditor) throws Exception {
        create(true);
        FxTestSupport.onFxThread(() -> {
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, !inEditor, false, true);
            return null;
        });
        String selector = inEditor ? "#editor-tabs" : "#terminal-panel";
        TerminalView original = selected(selector);
        awaitShell(original);
        original.session().send("export SYLPH_DUPLICATE_TEST=original\r");
        FxTestSupport.onFxThread(() -> {
            TabPane pane = (TabPane) scene.lookup(selector);
            ((Button) pane.getSelectionModel().getSelectedItem().getGraphic()).fire();
            return null;
        });
        TerminalView duplicate = selected(selector);
        awaitShell(duplicate);
        assertNotSame(original.session(), duplicate.session());
        assertEquals(original.session().directory(), duplicate.session().directory());
        duplicate.session().send("printf '%s' \"${SYLPH_DUPLICATE_TEST-fresh}\" > duplicate-env\r");
        FxTestSupport.await(() -> Files.exists(directory.resolve("duplicate-env"))
                && Files.readString(directory.resolve("duplicate-env")).equals("fresh"));
        FxTestSupport.onFxThread(() -> {
            TabPane pane = (TabPane) scene.lookup(selector);
            List<Tab> remove = pane.getTabs().stream().filter(tab -> tab.getContent() instanceof TerminalView).toList();
            pane.getTabs().removeAll(remove);
            if (inEditor) {
                assertEquals(1, pane.getTabs().size());
                assertFalse(pane.getTabs().getFirst().isClosable());
            } else {
                assertEquals(1, ((TerminalWorkspace) scene.lookup("#terminal-workspace")).getItems().size());
            }
            return null;
        });
        original.session().completion().get(5, TimeUnit.SECONDS);
        duplicate.session().completion().get(5, TimeUnit.SECONDS);
    }

    @Test
    void terminalClipboardShortcutDoesNotOpenMarkdownPreviewOrModifyTheDocument() throws Exception {
        Path file = Files.writeString(directory.resolve("readme.md"), "# Keep document");
        create(false);
        open(file);
        FxTestSupport.onFxThread(() -> {
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, false, false, true);
            return null;
        });
        TerminalView terminal = selected("#editor-tabs");
        awaitShell(terminal);
        WebView web = FxTestSupport.onFxThread(() -> (WebView) terminal.getCenter());
        FxTestSupport.await(() -> TerminalView.ownsFocus(scene));
        FxTestSupport.onFxThread(() -> {
            ClipboardContent clipboard = new ClipboardContent();
            clipboard.putString("printf 'pasted' > clipboard-result");
            Clipboard.getSystemClipboard().setContent(clipboard);
            web.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.V, true, true, false, false));
            terminal.session().send("\r");
            return null;
        });
        FxTestSupport.await(() -> Files.exists(directory.resolve("clipboard-result"))
                && Files.readString(directory.resolve("clipboard-result")).equals("pasted"));
        terminal.session().send("printf 'started' > interrupt-start; sleep 30\r");
        FxTestSupport.await(() -> Files.exists(directory.resolve("interrupt-start")));
        FxTestSupport.onFxThread(() -> {
            web.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, false, true, false, false));
            terminal.session().send("printf 'interrupted' > interrupt-result\r");
            return null;
        });
        FxTestSupport.await(() -> Files.exists(directory.resolve("interrupt-result"))
                && Files.readString(directory.resolve("interrupt-result")).equals("interrupted"));
        FxTestSupport.onFxThread(() -> {
            assertEquals("# Keep document", editor.getText());
            assertEquals(null, scene.lookup("#markdown-preview"));
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void editMenuTargetsTerminalWithoutEditingOrAutosavingTheDocument(boolean inEditor) throws Exception {
        Path file = Files.writeString(directory.resolve("keep.txt"), "keep-file");
        create(true);
        open(file);
        FxTestSupport.onFxThread(() -> {
            editor.selectRange(0, 4);
            settings.delaySecondsProperty().set(0);
            settings.autoSaveProperty().set(true);
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, !inEditor, false, true);
            return null;
        });
        TerminalView terminal = selected(inEditor ? "#editor-tabs" : "#terminal-panel");
        awaitShell(terminal);
        FxTestSupport.await(() -> TerminalView.ownsFocus(scene));
        terminal.session().send("printf 'terminal-marker\n'\r");
        FxTestSupport.await(() -> screen(terminal).contains("terminal-marker"));
        FxTestSupport.onFxThread(() -> {
            MenuBar menus = (MenuBar) scene.lookup("#main-menu");
            Menu edit = menus.getMenus().get(1);
            edit.show();
            menus.setFocusTraversable(true);
            menus.requestFocus();
            assertTrue(menus.isFocused());
            for (String label : List.of("Undo", "Redo", "Cut")) {
                assertTrue(editItem(edit, label).isDisable());
                editItem(edit, label).fire();
            }
            editItem(edit, "Select All").fire();
            assertEquals("keep", editor.getSelectedText());
            editItem(edit, "Copy").fire();
            assertTrue(Clipboard.getSystemClipboard().getString().contains("terminal-marker"));
            ClipboardContent clipboard = new ClipboardContent();
            clipboard.putString("printf 'pasted' > menu-paste\n");
            Clipboard.getSystemClipboard().setContent(clipboard);
            editItem(edit, "Paste").fire();
            edit.hide();
            assertEquals("keep-file", editor.getText());
            return null;
        });
        FxTestSupport.await(() -> Files.exists(directory.resolve("menu-paste"))
                && Files.readString(directory.resolve("menu-paste")).equals("pasted"));
        assertEquals("keep-file", Files.readString(file));
        FxTestSupport.onFxThread(() -> {
            assertEquals("keep.txt — Sylph", stage.getTitle());
            settings.autoSaveProperty().set(false);
            if (inEditor) {
                ((TabPane) scene.lookup("#editor-tabs")).getSelectionModel().selectFirst();
            }
            return null;
        });
        FxTestSupport.onFxThread(() -> {
            editor.requestFocus();
            Menu edit = ((MenuBar) scene.lookup("#main-menu")).getMenus().get(1);
            ClipboardContent clipboard = new ClipboardContent();
            clipboard.putString("document-input");
            Clipboard.getSystemClipboard().setContent(clipboard);
            editItem(edit, "Select All").fire();
            editItem(edit, "Paste").fire();
            assertEquals("document-input", editor.getText());
            return null;
        });
        assertEquals("keep-file", Files.readString(file));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void terminalLoadHonorsAnEditorFocusChoiceMadeWhileItWasStarting(boolean duplicate) throws Exception {
        create(true);
        if (duplicate) {
            FxTestSupport.onFxThread(() -> {
                EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, true, false, true);
                return null;
            });
            awaitShell(selected("#terminal-panel"));
        }
        TerminalView terminal = FxTestSupport.onFxThread(() -> {
            editor.requestFocus();
            if (duplicate) {
                TabPane panel = (TabPane) scene.lookup("#terminal-panel");
                ((Button) panel.getSelectionModel().getSelectedItem().getGraphic()).fire();
            } else {
                EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, true, false, true);
            }
            TerminalView pending = app.lastTerminal;
            sessions.add(pending.session());
            assertTrue(TerminalView.ownsFocus(scene));
            editor.requestFocus();
            assertTrue(editor.isFocused());
            assertFalse(((Label) pending.getBottom()).getText().startsWith("Shell running"));
            return pending;
        });
        awaitShell(terminal);
        FxTestSupport.onFxThread(() -> {
            assertTrue(editor.isFocused());
            editor.appendText("editor keeps typing");
            return null;
        });
        FxTestSupport.onFxThread(() -> {
            assertTrue(editor.isFocused());
            assertEquals("editor keeps typing", editor.getText());
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, true, false, true);
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, true, false, true);
            return null;
        });
        FxTestSupport.await(() -> ((WebView) terminal.getCenter()).isFocused());
        FxTestSupport.onFxThread(() -> {
            terminal.requestFocus();
            return null;
        });
        FxTestSupport.await(() -> ((WebView) terminal.getCenter()).isFocused());
    }

    private static MenuItem editItem(Menu menu, String label) {
        return menu.getItems().stream().filter(item -> label.equals(item.getText())).findFirst().orElseThrow();
    }

    @Test
    void rendererPreservesAnsiUnicodeAndOutputAcrossThemeChanges() throws Exception {
        create(true);
        FxTestSupport.onFxThread(() -> {
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, false, false, true);
            return null;
        });
        TerminalView terminal = selected("#editor-tabs");
        awaitShell(terminal);
        System.gc();
        terminal.session().send("stty -echo; printf '\\033[2J\\033[H\\033[31mλ ☃ RED\\033[0m\\n'; "
                + "printf '<script>window.untrusted=1</script>\\n'\r");
        try {
            FxTestSupport.await(() -> screen(terminal).contains("λ ☃ RED"));
        } catch (AssertionError failure) {
            throw new AssertionError(FxTestSupport.onFxThread(() ->
                    ((Label) terminal.getBottom()).getText() + "\n" + screen(terminal)), failure);
        }
        FxTestSupport.onFxThread(() -> {
            WebView web = (WebView) terminal.getCenter();
            String before = screen(terminal);
            assertTrue(before.contains("<script>window.untrusted=1</script>"));
            assertFalse(before.contains("\u001b[31m"));
            assertEquals("undefined", web.getEngine().executeScript("typeof window.untrusted"));
            settings.darkThemeProperty().set(false);
            assertEquals("rgb(250, 249, 245)",
                    web.getEngine().executeScript("document.body.style.backgroundColor"));
            assertEquals(before, screen(terminal));
            return null;
        });
        terminal.session().send("i=0; while [ $i -lt 10000 ]; do echo output-$i; i=$((i+1)); done; "
                + "echo output-finished\r");
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!FxTestSupport.onFxThread(() -> screen(terminal, 30).contains("output-finished"))) {
                assertTrue(System.nanoTime() < deadline, "Timed out draining terminal output");
                Thread.sleep(25);
            }
        } catch (AssertionError failure) {
            String output = FxTestSupport.onFxThread(() -> screen(terminal));
            throw new AssertionError(output.substring(Math.max(0, output.length() - 700)), failure);
        }
        assertTrue(FxTestSupport.onFxThread(() -> screen(terminal).split("\n").length <= 5100));
    }

    @Test
    void reloadClosesExistingTerminalsAndCreatesAFreshEditor() throws Exception {
        create(true);
        FxTestSupport.onFxThread(() -> {
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, false, false, true);
            return null;
        });
        TerminalView terminal = selected("#editor-tabs");
        awaitShell(terminal);
        FxTestSupport.onFxThread(() -> {
            EditorShortcutsTest.press(scene, KeyCode.R, false, true, true);
            scene = stage.getScene();
            assertEquals(null, scene.lookup("#terminal-workspace"));
            assertTrue(scene.lookup("#editor") instanceof CodeArea);
            return null;
        });
        terminal.session().completion().get(5, TimeUnit.SECONDS);
    }

    private void create(boolean workspace) throws Exception {
        FxTestSupport.onFxThread(() -> {
            app = new ScriptedApp();
            stage = new Stage();
            settings = new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 2)));
            app.settings = settings;
            scene = app.createScene(stage, settings);
            editor = (CodeArea) scene.lookup("#editor");
            stage.show();
            if (workspace) {
                app.openFolder(directory, (Sidebar) scene.lookup("#sidebar"), (Label) scene.lookup("#status"));
            }
            return null;
        });
    }

    private void open(Path file) throws Exception {
        FxTestSupport.onFxThread(() -> {
            app.openChoice = file.toFile();
            EditorShortcutsTest.press(scene, KeyCode.O, false, false, true);
            return null;
        });
        FxTestSupport.await(() -> stage.getTitle().equals(file.getFileName() + " — Sylph"));
    }

    private TerminalView selected(String selector) throws Exception {
        FxTestSupport.await(() -> scene.lookup(selector) instanceof TabPane);
        return FxTestSupport.onFxThread(() -> {
            TabPane pane = (TabPane) scene.lookup(selector);
            TerminalView terminal = (TerminalView) pane.getSelectionModel().getSelectedItem().getContent();
            sessions.add(terminal.session());
            return terminal;
        });
    }

    private static void awaitShell(TerminalView terminal) throws Exception {
        try {
            FxTestSupport.await(() -> ((Label) terminal.getBottom()).getText().startsWith("Shell running"));
        } catch (AssertionError failure) {
            throw new AssertionError(FxTestSupport.onFxThread(() -> ((Label) terminal.getBottom()).getText()), failure);
        }
    }

    private static String screen(TerminalView terminal) {
        return screen(terminal, 0);
    }

    private static String screen(TerminalView terminal, int lastRows) {
        WebView web = (WebView) terminal.getCenter();
        return (String) web.getEngine().executeScript("window.sylphTerminal.screen(" + lastRows + ")");
    }
}
