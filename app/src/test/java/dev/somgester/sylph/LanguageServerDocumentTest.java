package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguageServerDocumentTest {

    @TempDir
    Path directory;

    private EditorSession session;

    private LanguageServerDocument binding;

    private final List<Throwable> errors = new ArrayList<>();

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.onFxThread(() -> {
            if (binding != null) {
                binding.close();
            }
            if (session != null) {
                session.close();
            }
            return null;
        });
    }

    @Test
    void opensLoadedSnapshotCoalescesEditsAndStopsAfterBindingCloses() throws Exception {
        try (var fixture = new LanguageServerTestSupport(directory, LanguageServerTestSupport.FULL, "normal")) {
            create(fixture, new EditorFileService());
            FxTestSupport.onFxThread(() -> {
                session.replaceText("untitled text");
                binding.flush();
                return null;
            });
            assertTrue(fixture.messages("textDocument/didOpen").isEmpty());
            Path file = Files.writeString(directory.resolve("example.java"), "loaded text");
            open(file);
            assertEquals("loaded text", fixture.await("textDocument/didOpen", 1).getAsJsonObject("params")
                    .getAsJsonObject("textDocument").get("text").getAsString());
            FxTestSupport.onFxThread(() -> {
                session.replaceText("first edit");
                session.replaceText("second edit");
                session.replaceText("latest edit");
                return null;
            });
            assertEquals("latest edit", fixture.await("textDocument/didChange", 1).getAsJsonObject("params")
                    .getAsJsonArray("contentChanges").get(0).getAsJsonObject().get("text").getAsString());
            FxTestSupport.onFxThread(() -> {
                assertTrue(session.dirtyProperty().get());
                binding.close();
                binding.close();
                session.replaceText("must not sync");
                binding.flush();
                return null;
            });
            fixture.await("textDocument/didClose", 1);
            // Closing one tab must leave the shared server usable for another document.
            fixture.connection.open(directory.resolve("other.java"), EditorLanguage.JAVA, "other")
                    .get(5, TimeUnit.SECONDS);
            fixture.await("textDocument/didOpen", 2);
            fixture.connection.close();
            fixture.connection.stopped().get(5, TimeUnit.SECONDS);
            assertEquals(1, fixture.messages("textDocument/didChange").size());
            assertTrue(FxTestSupport.onFxThread(errors::isEmpty));
        }
    }

    @Test
    void autosaveSnapshotStaysDistinctFromNewerEditsAndSaveAsReopensWithNewLanguage() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var files = new EditorFileService() {
            @Override
            Snapshot save(Snapshot original, String text, Path target, boolean overwrite) throws IOException {
                writing.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release test save");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                return super.save(original, text, target, overwrite);
            }
        };
        try (var fixture = new LanguageServerTestSupport(directory, LanguageServerTestSupport.FULL, "normal")) {
            create(fixture, files);
            Path original = Files.writeString(directory.resolve("example.java"), "original");
            open(original);
            FxTestSupport.onFxThread(() -> {
                session.replaceText("saved snapshot");
                session.save(original, true, false, () -> { }, errors::add);
                return null;
            });
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            FxTestSupport.onFxThread(() -> {
                session.replaceText("newer buffer");
                binding.flush();
                return null;
            });
            release.countDown();
            FxTestSupport.await(() -> !session.busyProperty().get());
            FxTestSupport.onFxThread(() -> {
                binding.flush();
                return null;
            });
            assertEquals("saved snapshot", fixture.await("textDocument/didSave", 1).getAsJsonObject("params")
                    .get("text").getAsString());
            assertEquals("newer buffer", fixture.await("textDocument/didChange", 1).getAsJsonObject("params")
                    .getAsJsonArray("contentChanges").get(0).getAsJsonObject().get("text").getAsString());
            Path renamed = directory.resolve("renamed.py");
            FxTestSupport.onFxThread(() -> {
                assertTrue(session.dirtyProperty().get());
                session.save(renamed, false, false, () -> { }, errors::add);
                return null;
            });
            FxTestSupport.await(() -> !session.busyProperty().get());
            FxTestSupport.onFxThread(() -> {
                binding.flush();
                assertFalse(session.dirtyProperty().get());
                assertTrue(errors.isEmpty());
                return null;
            });
            assertEquals(original.toUri().toString(), fixture.await("textDocument/didClose", 1)
                    .getAsJsonObject("params").getAsJsonObject("textDocument").get("uri").getAsString());
            var reopened = fixture.await("textDocument/didOpen", 2).getAsJsonObject("params")
                    .getAsJsonObject("textDocument");
            assertEquals(renamed.toUri().toString(), reopened.get("uri").getAsString());
            assertEquals("python", reopened.get("languageId").getAsString());
            assertEquals("newer buffer", reopened.get("text").getAsString());
            assertEquals(1, reopened.get("version").getAsInt());
            fixture.await("textDocument/didSave", 2);
            assertEquals(1, fixture.messages("textDocument/didChange").size());
        } finally {
            release.countDown();
        }
    }

    @Test
    void closingDocumentTabDetachesItAndKeepsSharedServerAlive() throws Exception {
        try (var fixture = new LanguageServerTestSupport(directory, LanguageServerTestSupport.FULL, "normal")) {
            Path file = Files.writeString(directory.resolve("example.java"), "class Example {}");
            var document = FxTestSupport.onFxThread(() -> {
                var settings = new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 1)));
                var item = new EditorDocument(settings);
                item.attachLanguageServer(fixture.connection, errors::add);
                item.session.open(file, item.view::documentOpened, errors::add);
                return item;
            });
            try {
                FxTestSupport.await(() -> !document.session.busyProperty().get());
                fixture.await("textDocument/didOpen", 1);
                FxTestSupport.onFxThread(() -> {
                    document.close();
                    document.close();
                    assertThrows(IllegalStateException.class,
                            () -> document.attachLanguageServer(fixture.connection, errors::add));
                    assertTrue(errors.isEmpty());
                    return null;
                });
                fixture.await("textDocument/didClose", 1);
                fixture.connection.open(directory.resolve("another.java"), EditorLanguage.JAVA, "class Another {}")
                        .get(5, TimeUnit.SECONDS);
                fixture.await("textDocument/didOpen", 2);
            } finally {
                FxTestSupport.onFxThread(() -> {
                    document.close();
                    return null;
                });
            }
        }
    }

    @Test
    void failedConnectionReportsOnFxThreadAndKeepsEditorText() throws Exception {
        try (var fixture = new LanguageServerTestSupport(directory, LanguageServerTestSupport.FULL, "normal")) {
            create(fixture, new EditorFileService());
            open(Files.writeString(directory.resolve("example.java"), "original"));
            ProcessHandle.of(fixture.pid()).orElseThrow().destroyForcibly();
            fixture.connection.stopped().get(5, TimeUnit.SECONDS);
            FxTestSupport.onFxThread(() -> {
                session.replaceText("edits survive server failure");
                binding.flush();
                return null;
            });
            FxTestSupport.await(() -> !errors.isEmpty());
            assertEquals("edits survive server failure", FxTestSupport.onFxThread(() -> session.textProperty().get()));
            assertTrue(FxTestSupport.onFxThread(() -> session.dirtyProperty().get()));
        }
    }

    private void create(LanguageServerTestSupport fixture, EditorFileService files) throws Exception {
        FxTestSupport.onFxThread(() -> {
            session = new EditorSession(files);
            binding = new LanguageServerDocument(session, fixture.connection, error -> {
                assertTrue(Platform.isFxApplicationThread());
                errors.add(error);
            });
            return null;
        });
    }

    private void open(Path file) throws Exception {
        FxTestSupport.onFxThread(() -> {
            session.open(file, () -> { }, errors::add);
            return null;
        });
        FxTestSupport.await(() -> !session.busyProperty().get());
        FxTestSupport.onFxThread(() -> {
            binding.flush();
            return null;
        });
    }
}
