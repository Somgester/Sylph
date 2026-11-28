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
import javafx.application.Platform;
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
import javafx.stage.Window;
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

    private Path herdrCli;

    private Path herdrLauncher;

    private final class ScriptedApp extends EditorApp {
        private File openChoice;

        private AppSettings settings;

        private TerminalView lastTerminal;

        private Path commandDirectory;

        private KiroSessions.Listing savedSessions = new KiroSessions.Listing(List.of(), true);

        @Override
        KiroSessions.Listing loadKiroSessions() {
            return savedSessions;
        }

        @Override
        TerminalView createTerminal(Path directory) {
            var environment = new HashMap<>(System.getenv());
            environment.put("ENV", "/dev/null");
            environment.put("PS1", "");
            if (commandDirectory != null) {
                environment.put("PATH", commandDirectory + File.pathSeparator + environment.getOrDefault("PATH", ""));
            }
            if (herdrLauncher != null) {
                environment.put("SHELL", "/bin/sh");
                return createTerminal(directory, environment);
            }
            lastTerminal = new TerminalView(
                    new TerminalSession(directory, List.of("/bin/sh", "-i"), environment), settings);
            return lastTerminal;
        }

        @Override
        File chooseOpenFile() {
            return openChoice;
        }

        @Override
        Path herdrCliExecutable() {
            return herdrCli;
        }

        @Override
        Path herdrShellLauncher() {
            return herdrLauncher;
        }

        @Override
        Path herdrCliProfile() {
            return directory.resolve("sylph-herdr-profile");
        }

        @Override
        Path herdrSessionFile() {
            return directory.resolve(".sylph/last-session.json");
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
    void terminalChooserCancelsOrOpensTheChoiceAndLeavesPanelTogglingAlone() throws Exception {
        Path helper = directory.resolve("herdr");
        Files.writeString(helper, "#!/bin/sh\nexit 0\n");
        assertTrue(helper.toFile().setExecutable(true));
        herdrCli = helper;
        create(true);
        FxTestSupport.onFxThread(() -> {
            TabPane tabs = (TabPane) scene.lookup("#editor-tabs");
            answerTerminalChooser("terminal-choice-cancel");
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, false, false, true);
            assertEquals(1, tabs.getTabs().size());
            assertSame(editor, app.activeEditor());

            answerTerminalChooser("terminal-choice-shell");
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, true, false, true);
            TabPane panel = (TabPane) scene.lookup("#terminal-panel");
            assertEquals(1, panel.getTabs().size());
            sessions.add(((TerminalView) panel.getTabs().getFirst().getContent()).session());
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, true, false, true);
            assertEquals(null, scene.lookup("#terminal-panel"));
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, true, false, true);
            assertSame(panel, scene.lookup("#terminal-panel"));

            settings.darkThemeProperty().set(false);
            answerTerminalChooser("terminal-choice-herdr-cli");
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, false, false, true);
            return null;
        });
        FxTestSupport.await(() -> ((TabPane) scene.lookup("#editor-tabs")).getTabs().size() == 2);
    }

    private void answerTerminalChooser(String id) {
        Platform.runLater(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window != stage && window.isShowing()
                        && window.getScene().lookup("#" + id) instanceof Button card) {
                    card.fire();
                    return;
                }
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void herdrCliOpensInTheShortcutLocationWithWorkspaceDirectory(boolean inEditor) throws Exception {
        herdrCli = directory.resolve("herdr");
        Files.writeString(herdrCli, """
                #!/bin/sh
                printf 'HERDR_PTY:%s\\n' "$(pwd)"
                printf 'HERDR_ARGS:%s:%s\\n' "$1" "$2"
                printf 'HERDR_SCOPE:%s\\n' "$SYLPH_HERDR_SESSION_ID"
                test -f "$SYLPH_KIRO_CAPTURE_FILE" || exit 9
                if [ -n "$HERDR_CONFIG_PATH" ]; then cat "$HERDR_CONFIG_PATH"; fi
                test -t 0
                """);
        assertTrue(herdrCli.toFile().setExecutable(true));
        create(true);
        FxTestSupport.onFxThread(() -> {
            if (inEditor) {
                settings.herdrThemeProperty().set(HerdrCliTheme.NORD);
            }
            answerTerminalChooser("terminal-choice-herdr-cli");
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, !inEditor, false, true);
            return null;
        });
        String selector = inEditor ? "#editor-tabs" : "#terminal-panel";
        FxTestSupport.await(() -> scene.lookup(selector) instanceof TabPane tabs
                && tabs.getSelectionModel().getSelectedItem().getContent() instanceof TerminalView);
        TerminalView terminal = selected(selector);
        FxTestSupport.await(() -> screen(terminal).contains("HERDR_PTY:" + directory));
        String sessionId = terminal.herdrSession().herdrSessionId();
        FxTestSupport.await(() -> screen(terminal).contains("HERDR_ARGS:--session:" + sessionId));
        FxTestSupport.await(() -> screen(terminal).contains("HERDR_SCOPE:" + sessionId));
        FxTestSupport.await(() -> new HerdrSessionStore(app.herdrSessionFile()).load().tabs().stream()
                .anyMatch(tab -> tab.herdrSessionId().equals(sessionId) && tab.inEditor() == inEditor));
        if (inEditor) {
            FxTestSupport.await(() -> screen(terminal).contains("name = \"nord\""));
        } else {
            assertFalse(Files.exists(app.herdrCliProfile()));
        }
        terminal.session().completion().get(5, TimeUnit.SECONDS);
        FxTestSupport.await(() -> ((Label) terminal.getBottom()).getText().contains("exited: 0"));
    }

    @Test
    void chooserRestoresBothTabsForCurrentFolderAndSelectsLast() throws Exception {
        herdrCli = directory.resolve("herdr");
        Files.writeString(herdrCli, "#!/bin/sh\nstty size\nprintf 'RESTORED:%s\\n' \"$2\"\n");
        assertTrue(herdrCli.toFile().setExecutable(true));
        create(true);
        var last = new HerdrSessionStore.Tab("sylph-last", null, directory, false, HerdrCliTheme.NORD);
        var older = new HerdrSessionStore.Tab("sylph-older", null, directory, true, HerdrCliTheme.DEFAULT);
        var other = new HerdrSessionStore.Tab("sylph-other", null, directory.resolve("other"),
                true, HerdrCliTheme.DEFAULT);
        var store = new HerdrSessionStore(app.herdrSessionFile());
        store.save(new HerdrSessionStore.State(List.of(older, last, other), other.herdrSessionId()));
        FxTestSupport.onFxThread(() -> {
            var tabs = (TabPane) scene.lookup("#editor-tabs");
            assertEquals(1, tabs.getTabs().size());
            answerTerminalChooser("terminal-choice-herdr-cli");
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, false, false, true);
            return null;
        });
        FxTestSupport.await(() -> ((TabPane) scene.lookup("#editor-tabs")).getTabs().size() == 3);
        var view = selected("#editor-tabs");
        assertEquals(last.herdrSessionId(), view.herdrSession().herdrSessionId());
        FxTestSupport.await(() -> screen(view).contains("RESTORED:sylph-last"));
        assertFalse(FxTestSupport.onFxThread(() -> screen(view).contains("0 0")));
    }

    @Test
    void bareHerdrInExistingShellUsesItsCurrentFolderAndKeepsTheSameTab() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(TerminalSession.findOnPath(
                "python3", System.getenv("PATH"), System.getProperty("os.name")) != null);
        herdrLauncher = Path.of("..", "scripts", "herdr-shell").toAbsolutePath().normalize();
        herdrCli = directory.resolve("herdr");
        Files.writeString(herdrCli, "#!/bin/sh\nprintf 'ATTACHED:%s\\n' \"$2\"\n");
        assertTrue(herdrCli.toFile().setExecutable(true));
        create(true);
        Path folder = Files.createDirectory(directory.resolve("other"));
        var saved = new HerdrSessionStore.Tab("sylph-shell", null, folder, false, HerdrCliTheme.DEFAULT);
        var store = new HerdrSessionStore(app.herdrSessionFile());
        store.save(new HerdrSessionStore.State(List.of(saved), saved.herdrSessionId()));
        FxTestSupport.onFxThread(() -> {
            answerTerminalChooser("terminal-choice-shell");
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, false, false, true);
            return null;
        });
        var view = selected("#editor-tabs");
        view.session().send("cd other && herdr\r");
        FxTestSupport.await(() -> screen(view).contains("ATTACHED:sylph-shell"));
        FxTestSupport.onFxThread(() -> {
            TabPane tabs = (TabPane) scene.lookup("#editor-tabs");
            assertEquals(2, tabs.getTabs().size());
            assertEquals(saved.herdrSessionId(), view.herdrSession().herdrSessionId());
            tabs.getTabs().remove(tabs.getSelectionModel().getSelectedItem());
            return null;
        });
        FxTestSupport.await(() -> store.load().tabs().isEmpty());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "SYLPH_TEST_REAL_KIRO", matches = "1")
    void realKiroWritesDistinctIdsToDiskBeforeSylphExits() throws Exception {
        herdrCli = TerminalSession.findOnPath("herdr", System.getenv("PATH"), System.getProperty("os.name"));
        herdrLauncher = Path.of("..", "scripts", "herdr-shell").toAbsolutePath().normalize();
        create(true);
        var store = new HerdrSessionStore(app.herdrSessionFile());
        var servers = new ArrayList<String>();
        try {
            FxTestSupport.onFxThread(() -> {
                settings.herdrThemeProperty().set(HerdrCliTheme.DEFAULT);
                answerTerminalChooser("terminal-choice-herdr-cli");
                EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, false, false, true);
                return null;
            });
            FxTestSupport.await(() -> store.load().tabs().size() == 1);
            FxTestSupport.onFxThread(() -> {
                terminalButton((TabPane) scene.lookup("#editor-tabs"), "#duplicate-terminal").fire();
                return null;
            });
            FxTestSupport.await(() -> store.load().tabs().size() == 2);
            for (var tab : store.load().tabs()) {
                servers.add(tab.herdrSessionId());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (nativeHerdr(tab.herdrSessionId(), "pane", "run", "w1:p1", "kiro-cli") != 0) {
                    assertTrue(System.nanoTime() < deadline, "Herdr pane failed to start");
                    Thread.sleep(200);
                }
                assertEquals(0, nativeHerdr(tab.herdrSessionId(), "tab", "create", "--cwd", directory.toString()));
                assertEquals(0, nativeHerdr(tab.herdrSessionId(), "pane", "run", "w1:p2", "kiro-cli"));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (store.load().tabs().stream().anyMatch(tab -> tab.paneSessions().size() != 2)) {
                assertTrue(System.nanoTime() < deadline, "Kiro ID missing from Sylph state file");
                Thread.sleep(200);
            }
            assertEquals(4, store.load().tabs().stream().flatMap(tab -> tab.paneSessions().values().stream())
                    .map(HerdrSessionStore.Chat::sessionId).distinct().count());
            FxTestSupport.onFxThread(() -> {
                var tabs = (TabPane) scene.lookup("#editor-tabs");
                tabs.getTabs().remove(tabs.getSelectionModel().getSelectedItem());
                return null;
            });
            FxTestSupport.await(() -> store.load().tabs().size() == 1);
            verifyPaneFallbackAfterRestart(store.load().tabs().getFirst());
        } finally {
            for (String id : servers) {
                nativeHerdr(id, "session", "stop", id);
            }
        }
    }

    private void verifyPaneFallbackAfterRestart(HerdrSessionStore.Tab saved) throws Exception {
        close();
        String id = saved.herdrSessionId();
        assertEquals(0, nativeHerdr(id, "session", "stop", id));
        Path config = Path.of(System.getenv().getOrDefault("XDG_CONFIG_HOME",
                Path.of(System.getProperty("user.home"), ".config").toString()));
        Path layout = config.resolve("herdr/sessions").resolve(id).resolve("session.json");
        var root = com.google.gson.JsonParser.parseString(Files.readString(layout)).getAsJsonObject();
        var second = root.getAsJsonArray("workspaces").get(0).getAsJsonObject().getAsJsonArray("tabs").get(1)
                .getAsJsonObject().getAsJsonObject("panes").getAsJsonObject("2");
        second.remove("agent_resume");
        Files.writeString(layout, root.toString());
        String expected = saved.paneSessions().get("w1:p2").sessionId();
        assertFalse(Files.readString(layout).contains(expected));
        create(true);
        FxTestSupport.onFxThread(() -> {
            answerTerminalChooser("terminal-choice-herdr-cli");
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, false, false, true);
            return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!Files.readString(layout).contains(expected)) {
            assertTrue(System.nanoTime() < deadline, "Missing pane did not register its original Kiro session");
            Thread.sleep(200);
        }
    }

    private int nativeHerdr(String id, String... args) throws Exception {
        var command = new ArrayList<>(List.of(herdrCli.toString(), "--session", id));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void duplicatedHerdrTabsHaveIndependentSessionIdsAndClosingOneRemovesOnlyItsSavedEntry() throws Exception {
        herdrCli = directory.resolve("herdr");
        Files.writeString(herdrCli, "#!/bin/sh\nexit 0\n");
        assertTrue(herdrCli.toFile().setExecutable(true));
        create(true);
        FxTestSupport.onFxThread(() -> {
            answerTerminalChooser("terminal-choice-herdr-cli");
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, false, false, true);
            return null;
        });
        FxTestSupport.await(() -> ((TabPane) scene.lookup("#editor-tabs")).getTabs().size() == 2);
        String[] ids = FxTestSupport.onFxThread(() -> {
            TabPane pane = (TabPane) scene.lookup("#editor-tabs");
            Tab original = pane.getSelectionModel().getSelectedItem();
            var first = ((TerminalView) original.getContent()).herdrSession();
            terminalButton(pane, "#duplicate-terminal").fire();
            var duplicate = ((TerminalView) pane.getSelectionModel().getSelectedItem().getContent()).herdrSession();
            assertFalse(first.herdrSessionId().equals(duplicate.herdrSessionId()));
            assertEquals(first.directory(), duplicate.directory());
            pane.getTabs().remove(original);
            return new String[] {first.herdrSessionId(), duplicate.herdrSessionId()};
        });
        FxTestSupport.await(() -> {
            var saved = new HerdrSessionStore(app.herdrSessionFile()).load();
            return saved.tabs().size() == 1 && saved.tabs().getFirst().herdrSessionId().equals(ids[1])
                    && ids[1].equals(saved.activeSessionId());
        });
        FxTestSupport.onFxThread(() -> {
            app.closeResources();
            return null;
        });
        assertEquals(ids[1], new HerdrSessionStore(app.herdrSessionFile()).load().tabs().getFirst().herdrSessionId());
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
    void kiroButtonRunsCommandOnceInANewTerminalWithoutChangingTheDocument(boolean workspace) throws Exception {
        Path commands = Files.createDirectory(directory.resolve("commands"));
        Path kiro = Files.writeString(commands.resolve("kiro-cli"),
                "#!/bin/sh\nprintf '%s' \"$PWD\" > kiro-cwd\nprintf 'run\\n' >> kiro-runs\n"
                        + "printf 'SYLPH_KIRO_STARTED\\n'\n");
        assertTrue(kiro.toFile().setExecutable(true));
        create(workspace);
        if (!workspace) {
            open(Files.writeString(directory.resolve("file.txt"), "original"));
        }
        FxTestSupport.onFxThread(() -> {
            app.commandDirectory = commands;
            editor.replaceText("unsaved document");
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, false, false, true);
            return null;
        });
        TerminalView original = selected("#editor-tabs");
        FxTestSupport.onFxThread(() -> {
            ((Button) scene.lookup("#open-kiro")).fire();
            return null;
        });
        TerminalView terminal = selected("#editor-tabs");
        assertNotSame(original, terminal);
        assertEquals(directory, terminal.session().directory());
        awaitShell(terminal);
        FxTestSupport.await(() -> Files.exists(directory.resolve("kiro-cwd"))
                && Files.readString(directory.resolve("kiro-cwd")).equals(directory.toString())
                && screen(terminal).contains("SYLPH_KIRO_STARTED"));
        assertEquals("run\n", Files.readString(directory.resolve("kiro-runs")));
        FxTestSupport.onFxThread(() -> {
            assertEquals(3, ((TabPane) scene.lookup("#editor-tabs")).getTabs().size());
            assertEquals("unsaved document", editor.getText());
            assertTrue(editor.isUndoAvailable());
            return null;
        });
    }

    @Test
    void selectingKiroSessionResumesItsIdInItsOriginalWorkspace() throws Exception {
        Path savedFolder = Files.createDirectory(directory.resolve("previous project"));
        Path commands = Files.createDirectory(directory.resolve("commands"));
        Path executable = Files.writeString(commands.resolve("kiro-cli"), """
                #!/bin/sh
                test "$#" -eq 3 || exit 2
                test "$1 $2" = 'chat --resume-id' || exit 3
                printf '%s' "$PWD" > resume-cwd
                printf '%s' "$3" > resume-id
                printf 'SYLPH_SESSION_RESUMED\n'
                """);
        assertTrue(executable.toFile().setExecutable(true));
        create(true);
        FxTestSupport.onFxThread(() -> {
            app.commandDirectory = commands;
            app.savedSessions = new KiroSessions.Listing(List.of(new KiroSessions.Session("session-123", "Previous",
                    savedFolder, java.time.Instant.EPOCH)), true);
            editor.replaceText("keep my edits");
            ((Button) scene.lookup("#list-kiro-sessions")).fire();
            return null;
        });
        FxTestSupport.await(() -> ((javafx.scene.control.ListView<?>) scene.lookup("#palette-results"))
                .getItems().size() == 1);
        FxTestSupport.onFxThread(() -> {
            scene.lookup("#palette-query").fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                    false, false, false, false));
            return null;
        });
        TerminalView terminal = selected("#editor-tabs");
        awaitShell(terminal);
        FxTestSupport.await(() -> Files.exists(savedFolder.resolve("resume-id"))
                && Files.readString(savedFolder.resolve("resume-id")).equals("session-123")
                && screen(terminal).contains("SYLPH_SESSION_RESUMED"));
        assertEquals(savedFolder.toString(), Files.readString(savedFolder.resolve("resume-cwd")));
        FxTestSupport.onFxThread(() -> {
            assertEquals("keep my edits", editor.getText());
            assertFalse(scene.lookup("#command-palette").isVisible());
            return null;
        });
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
            terminalButton(pane, "#duplicate-terminal").fire();
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
                assertTrue(pane.getTabs().getFirst().isClosable());
            } else {
                assertEquals(1, ((TerminalWorkspace) scene.lookup("#terminal-workspace")).getItems().size());
            }
            return null;
        });
        original.session().completion().get(5, TimeUnit.SECONDS);
        duplicate.session().completion().get(5, TimeUnit.SECONDS);
    }

    @Test
    void tabMenusCloseOnlyClickedTerminalThenAllTabsAndDisposeBothShells() throws Exception {
        create(true);
        FxTestSupport.onFxThread(() -> {
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, false, false, true);
            return null;
        });
        TerminalView top = selected("#editor-tabs");
        awaitShell(top);
        FxTestSupport.onFxThread(() -> {
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, true, false, true);
            return null;
        });
        TerminalView bottom = selected("#terminal-panel");
        awaitShell(bottom);
        FxTestSupport.onFxThread(() -> {
            TabPane editors = (TabPane) scene.lookup("#editor-tabs");
            Tab terminal = editors.getTabs().stream().filter(tab -> tab.getContent() == top).findFirst().orElseThrow();
            terminal.getContextMenu().getItems().getFirst().fire();
            assertEquals(1, editors.getTabs().size());
            assertFalse(bottom.session().completion().isDone());
            TabPane panel = (TabPane) scene.lookup("#terminal-panel");
            panel.getTabs().getFirst().getContextMenu().getItems().getLast().fire();
            assertTrue(editors.getTabs().isEmpty());
            assertTrue(panel.getTabs().isEmpty());
            assertEquals(null, app.activeEditor());
            assertTrue(scene.lookup("#empty-editor").isVisible());
            return null;
        });
        top.session().completion().get(5, TimeUnit.SECONDS);
        bottom.session().completion().get(5, TimeUnit.SECONDS);
        FxTestSupport.onFxThread(() -> {
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, false, false, true);
            return null;
        });
        TerminalView reopened = selected("#editor-tabs");
        awaitShell(reopened);
        assertEquals(directory, reopened.session().directory());
        FxTestSupport.await(() -> TerminalView.ownsFocus(scene));
        FxTestSupport.onFxThread(() -> {
            assertEquals(null, app.activeEditor());
            assertEquals(1, ((TabPane) scene.lookup("#editor-tabs")).getTabs().size());
            ClipboardContent clipboard = new ClipboardContent();
            clipboard.putString("printf 'empty workspace terminal' > empty-terminal-result\n");
            Clipboard.getSystemClipboard().setContent(clipboard);
            Menu edit = ((MenuBar) scene.lookup("#main-menu")).getMenus().get(1);
            assertFalse(editItem(edit, "Paste").isDisable());
            editItem(edit, "Paste").fire();
            return null;
        });
        FxTestSupport.await(() -> Files.exists(directory.resolve("empty-terminal-result"))
                && Files.readString(directory.resolve("empty-terminal-result")).equals("empty workspace terminal"));
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
                terminalButton(panel, "#duplicate-terminal").fire();
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
            assertTrue(scene.lookup("#terminal-workspace") instanceof TerminalWorkspace);
            assertEquals(null, app.activeEditor());
            assertTrue(((TabPane) scene.lookup("#editor-tabs")).getTabs().isEmpty());
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
            EditorShortcutsTest.press(scene, KeyCode.N, false, false, true);
            editor = app.activeEditor();
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

    private static Button terminalButton(TabPane pane, String selector) {
        return (Button) pane.getSelectionModel().getSelectedItem().getGraphic().lookup(selector);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void expandAndRestoreKeepTheLiveTerminalAndDocument(boolean inEditor) throws Exception {
        create(true);
        FxTestSupport.onFxThread(() -> {
            editor.replaceText("draft");
            EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, true, false, true);
            if (inEditor) {
                EditorShortcutsTest.press(scene, KeyCode.BACK_QUOTE, false, false, true);
            }
            return null;
        });
        String paneId = inEditor ? "#editor-tabs" : "#terminal-panel";
        TerminalView terminal = selected(paneId);
        awaitShell(terminal);
        FxTestSupport.onFxThread(() -> {
            TerminalWorkspace workspace = (TerminalWorkspace) scene.lookup("#terminal-workspace");
            TabPane pane = (TabPane) scene.lookup(paneId);
            Button expand = terminalButton(pane, "#toggle-terminal-expand");
            expand.fire();
            assertEquals("Restore", expand.getText());
            assertEquals(1, workspace.getItems().size());
            assertSame(terminal, pane.getSelectionModel().getSelectedItem().getContent());
            assertFalse(scene.lookup("#toolbar").getParent().isVisible());
            assertEquals(null, scene.lookup("#sidebar"));
            return null;
        });
        terminal.session().send("printf 'expanded-ok\\n'\r");
        FxTestSupport.await(() -> screen(terminal).contains("expanded-ok"));
        FxTestSupport.onFxThread(() -> {
            TabPane pane = (TabPane) scene.lookup(paneId);
            terminalButton(pane, "#toggle-terminal-expand").fire();
            TerminalWorkspace workspace = (TerminalWorkspace) scene.lookup("#terminal-workspace");
            assertEquals(2, workspace.getItems().size());
            assertSame(terminal, pane.getSelectionModel().getSelectedItem().getContent());
            assertTrue(screen(terminal).contains("expanded-ok"));
            assertTrue(scene.lookup("#toolbar").getParent().isVisible());
            assertTrue(scene.lookup("#sidebar").isVisible());
            assertEquals("draft", editor.getText());
            return null;
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
