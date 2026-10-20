package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguageServerManagerTest {

    @TempDir
    Path directory;

    private LanguageServerSettings settings;

    private LanguageServerManager manager;

    private final List<EditorDocument> documents = new ArrayList<>();

    private final List<LanguageServerTestSupport> servers = new ArrayList<>();

    private final List<Path> roots = new ArrayList<>();

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.onFxThread(() -> {
            if (manager != null) {
                manager.close();
            }
            documents.forEach(EditorDocument::close);
            return null;
        });
        servers.forEach(LanguageServerTestSupport::close);
    }

    @Test
    void disabledServersNeverStartAndEnabledTabsShareWorkspaceProcess() throws Exception {
        create();
        var first = open(directory.resolve("first.py"));
        assertTrue(servers.isEmpty());
        enable(true);
        ready();
        var second = open(directory.resolve("second.py"));
        assertEquals(1, servers.size());
        servers.getFirst().await("textDocument/didOpen", 2);
        FxTestSupport.onFxThread(() -> {
            first.close();
            return null;
        });
        servers.getFirst().await("textDocument/didClose", 1);
        assertFalse(servers.getFirst().connection.stopped().isDone());
        FxTestSupport.onFxThread(() -> {
            second.close();
            return null;
        });
        servers.getFirst().connection.stopped().get(5, TimeUnit.SECONDS);
    }

    @Test
    void applyingUnchangedSettingsRestartsAndDisablingStopsTheServer() throws Exception {
        create();
        enable(true);
        open(directory.resolve("first.py"));
        ready();
        enable(true);
        FxTestSupport.await(() -> servers.size() == 2);
        ready();
        servers.getFirst().connection.stopped().get(5, TimeUnit.SECONDS);
        servers.get(1).await("textDocument/didOpen", 1);
        enable(false);
        servers.get(1).connection.stopped().get(5, TimeUnit.SECONDS);
        assertEquals("Disabled", FxTestSupport.onFxThread(() ->
                manager.status(LanguageServerSettings.Server.PYTHON).get()));
    }

    @Test
    void saveAsSwitchesLanguageAndWorkspaceChangesMoveTheServerRoot() throws Exception {
        create();
        enable(true);
        Path subdirectory = Files.createDirectory(directory.resolve("nested"));
        var document = open(subdirectory.resolve("first.py"));
        ready();
        assertEquals(subdirectory, roots.getFirst());
        FxTestSupport.onFxThread(() -> {
            manager.setWorkspace(directory);
            return null;
        });
        ready();
        assertEquals(directory, roots.getLast());
        servers.getFirst().connection.stopped().get(5, TimeUnit.SECONDS);
        FxTestSupport.onFxThread(() -> {
            document.session.save(directory.resolve("plain.txt"), false, false, () -> { },
                    error -> { throw new AssertionError(error); });
            return null;
        });
        FxTestSupport.await(() -> !document.session.busyProperty().get());
        servers.getLast().connection.stopped().get(5, TimeUnit.SECONDS);
        assertTrue(FxTestSupport.onFxThread(() -> document.view.lookup("#language-info") == null));
    }

    @Test
    void startupFailureIsReportedAndDoesNotCauseAnAutomaticRestartLoop() throws Exception {
        FxTestSupport.onFxThread(() -> {
            settings = new LanguageServerSettings(LanguageServerSettings.memoryStore());
            manager = new LanguageServerManager(settings);
            settings.apply(LanguageServerSettings.Server.PYTHON,
                    new LanguageServerSettings.Configuration(true, directory.resolve("missing-server").toString()));
            return null;
        });
        var document = open(directory.resolve("first.py"));
        FxTestSupport.await(() -> manager.status(LanguageServerSettings.Server.PYTHON).get().startsWith("Failed:"));
        FxTestSupport.onFxThread(() -> {
            document.session.textProperty().set("unsaved edits survive");
            assertTrue(document.session.dirtyProperty().get());
            assertTrue(document.view.lookup("#language-info") == null);
            assertTrue(manager.status(LanguageServerSettings.Server.PYTHON).get().contains("retry"));
            return null;
        });
    }

    private void create() throws Exception {
        FxTestSupport.onFxThread(() -> {
            settings = new LanguageServerSettings(LanguageServerSettings.memoryStore());
            manager = new LanguageServerManager(settings, (command, root) -> {
                try {
                    assertEquals(List.of("test-server", "--stdio"), command);
                    var fixture = new LanguageServerTestSupport(root, LanguageServerTestSupport.FULL, "normal");
                    servers.add(fixture);
                    roots.add(root);
                    return fixture.connection;
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
            return null;
        });
    }

    private void enable(boolean enabled) throws Exception {
        FxTestSupport.onFxThread(() -> {
            settings.apply(LanguageServerSettings.Server.PYTHON,
                    new LanguageServerSettings.Configuration(enabled, "test-server"));
            return null;
        });
    }

    private EditorDocument open(Path path) throws Exception {
        Files.writeString(path, "value = 1\n");
        var document = FxTestSupport.onFxThread(() -> {
            var item = new EditorDocument(new AppSettings(new AppSettingsTest.MemoryStore(
                    new AppSettings.Values(true, false, 1))));
            documents.add(item);
            manager.watch(item);
            item.session.open(path, item.view::documentOpened, error -> { throw new AssertionError(error); });
            return item;
        });
        FxTestSupport.await(() -> !document.session.busyProperty().get());
        return document;
    }

    private void ready() throws Exception {
        FxTestSupport.await(() -> manager.status(LanguageServerSettings.Server.PYTHON).get().startsWith("Ready"));
    }
}
