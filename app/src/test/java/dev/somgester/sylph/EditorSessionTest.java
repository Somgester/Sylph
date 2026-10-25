package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorSessionTest {

    @Test
    void lineSourceReadsCurrentBufferOnlyOnFxThreadWhileSessionIsOpen() throws Exception {
        create(new EditorFileService());
        assertThrows(IllegalStateException.class, () -> session.lineCount());
        assertThrows(IllegalStateException.class, () -> session.lineText(0));
        FxTestSupport.onFxThread(() -> {
            session.replaceText("first\r\n🐍\n");
            assertEquals(3, session.lineCount());
            assertEquals("first", session.lineText(0));
            assertEquals("🐍", session.lineText(1));
            assertEquals("", session.lineText(2));
            session.replaceText(6, 8, "other\nline");
            assertEquals(4, session.lineCount());
            assertEquals("other", session.lineText(1));
            assertEquals("line", session.lineText(2));
            session.close();
            assertThrows(IllegalStateException.class, () -> session.lineCount());
            assertThrows(IllegalStateException.class, () -> session.lineText(0));
            return null;
        });
    }

    @TempDir
    Path directory;

    private EditorSession session;

    private final List<Throwable> errors = new ArrayList<>();

    @BeforeAll
    static void initialize() throws Exception {
        FxTestSupport.initialize();
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.onFxThread(() -> {
            if (session != null) {
                session.close();
            }
            return null;
        });
    }

    @Test
    void rangeEditsAndSnapshotsFollowUtf16OffsetsAndDirtyState() throws Exception {
        create(new EditorFileService());
        Path file = Files.writeString(directory.resolve("unicode.txt"), "a🐍b\n");
        open(file);
        FxTestSupport.onFxThread(() -> {
            String original = session.textProperty().get();
            session.replaceText(1, 3, "😀 café");
            assertEquals("a😀 caféb\n", session.textProperty().get());
            assertTrue(session.dirtyProperty().get());
            session.replaceText(1, 8, "🐍");
            assertEquals(original, session.textProperty().get());
            assertFalse(session.dirtyProperty().get());
            session.replaceText(0, 1, "");
            assertEquals("🐍b\n", session.textProperty().get());
            assertEquals("a🐍b\n", original);
            return null;
        });
    }

    @Test
    void batchOffsetsAreSequentialAndOnlyTheFinalSnapshotIsPublished() throws Exception {
        create(new EditorFileService());
        FxTestSupport.onFxThread(() -> {
            session.replaceText("abc");
            List<String> observed = new ArrayList<>();
            session.textProperty().addListener((observable, previous, text) -> observed.add(text));
            session.applyEdits(List.of(new EditorSession.TextEdit(0, 0, "prefix\r\n"),
                    new EditorSession.TextEdit(8, 10, "🐍")));
            assertEquals(List.of("prefix\na🐍"), observed);
            assertEquals("prefix\na🐍", session.textProperty().get());
            session.applyEdits(List.of());
            session.replaceText(0, 0, "");
            assertEquals(1, observed.size());
            return null;
        });
    }

    @Test
    void invalidBatchesDoNotApplyEarlierValidChangesOrNotifyObservers() throws Exception {
        create(new EditorFileService());
        FxTestSupport.onFxThread(() -> {
            session.replaceText("abcdef");
            List<String> observed = new ArrayList<>();
            session.textProperty().addListener((observable, previous, text) -> observed.add(text));
            assertThrows(IndexOutOfBoundsException.class, () -> session.applyEdits(List.of(
                    new EditorSession.TextEdit(0, 3, ""), new EditorSession.TextEdit(4, 4, "wrong"))));
            assertThrows(NullPointerException.class, () -> session.applyEdits(Arrays.asList(
                    new EditorSession.TextEdit(0, 0, "wrong"), null)));
            assertThrows(NullPointerException.class, () -> session.replaceText((String) null));
            assertEquals("abcdef", session.textProperty().get());
            assertTrue(observed.isEmpty());
            session.replaceText(0, 1, "A");
            assertEquals("Abcdef", session.textProperty().get());
            return null;
        });
    }

    @Test
    void reopenFailureSuccessfulResetAndClearKeepTheRightBuffer() throws Exception {
        create(new EditorFileService());
        Path first = Files.writeString(directory.resolve("first.txt"), "first");
        Path second = Files.writeString(directory.resolve("second.txt"), "second");
        open(first);
        FxTestSupport.onFxThread(() -> {
            session.replaceText(0, 5, "first edited");
            session.open(directory.resolve("missing.txt"), () -> { }, errors::add);
            return null;
        });
        awaitIdle();
        FxTestSupport.onFxThread(() -> {
            assertEquals(1, errors.size());
            assertEquals("first edited", session.textProperty().get());
            assertEquals(first, session.pathProperty().get());
            assertTrue(session.dirtyProperty().get());
            return null;
        });
        errors.clear();
        open(second);
        FxTestSupport.onFxThread(() -> {
            assertEquals("second", session.textProperty().get());
            assertFalse(session.dirtyProperty().get());
            session.replaceText(0, 1, "S");
            assertEquals("Second", session.textProperty().get());
            session.clearDocument();
            assertEquals("", session.textProperty().get());
            assertFalse(session.dirtyProperty().get());
            session.replaceText(0, 0, "new buffer");
            assertEquals("new buffer", session.textProperty().get());
            return null;
        });
    }

    @Test
    void saveAsPreservesLineEndingsAndBomWhileFurtherRangeEditsUseTheSameDocument() throws Exception {
        create(new EditorFileService());
        Path source = Files.writeString(directory.resolve("source.txt"), "\uFEFFfirst\r\nsecond\r\n");
        Path destination = directory.resolve("chosen.txt");
        open(source);
        FxTestSupport.onFxThread(() -> {
            assertEquals("first\nsecond\n", session.textProperty().get());
            session.replaceText(6, 12, "changed\r\nlast");
            session.save(destination, false, false, () -> { }, errors::add);
            return null;
        });
        awaitIdle();
        assertEquals("\uFEFFfirst\r\nchanged\r\nlast\r\n", Files.readString(destination));
        FxTestSupport.onFxThread(() -> {
            assertTrue(errors.isEmpty());
            assertFalse(session.dirtyProperty().get());
            session.replaceText(0, 5, "FIRST");
            session.save(destination, false, false, () -> { }, errors::add);
            return null;
        });
        awaitIdle();
        assertEquals("\uFEFFFIRST\r\nchanged\r\nlast\r\n", Files.readString(destination));
        assertEquals("\uFEFFfirst\r\nsecond\r\n", Files.readString(source));
        assertTrue(errors.isEmpty());
    }

    @Test
    void backgroundSaveUsesCapturedSnapshotWhileRangeEditsRemainDirty() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        create(new EditorFileService() {
            @Override
            Snapshot save(Snapshot original, String text, Path target, boolean overwrite) throws IOException {
                assertFalse(Platform.isFxApplicationThread());
                writing.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting for test save");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                return super.save(original, text, target, overwrite);
            }
        });
        Path file = Files.writeString(directory.resolve("saved.txt"), "original");
        open(file);
        try {
            FxTestSupport.onFxThread(() -> {
                session.replaceText(0, 8, "captured");
                session.save(file, true, false, () -> { }, errors::add);
                return null;
            });
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            FxTestSupport.onFxThread(() -> {
                session.replaceText(0, 8, "newest buffer");
                return null;
            });
        } finally {
            release.countDown();
        }
        awaitIdle();
        assertEquals("captured", Files.readString(file));
        FxTestSupport.onFxThread(() -> {
            assertEquals("newest buffer", session.textProperty().get());
            assertTrue(session.dirtyProperty().get());
            assertEquals("captured", session.lastSaveProperty().get().text());
            session.save(file, false, false, () -> { }, errors::add);
            return null;
        });
        awaitIdle();
        assertEquals("newest buffer", Files.readString(file));
        assertTrue(errors.isEmpty());
    }

    @Test
    void editsRejectWrongThreadForegroundOperationsAndClosedSessions() throws Exception {
        create(new EditorFileService());
        assertThrows(IllegalStateException.class, () -> session.replaceText("wrong thread"));
        Path file = Files.writeString(directory.resolve("loaded.txt"), "loaded");
        FxTestSupport.onFxThread(() -> {
            session.open(file, () -> { }, errors::add);
            assertThrows(IllegalStateException.class, () -> session.replaceText("during open"));
            return null;
        });
        awaitIdle();
        FxTestSupport.onFxThread(() -> {
            assertEquals("loaded", session.textProperty().get());
            session.close();
            assertThrows(IllegalStateException.class, () -> session.replaceText("after close"));
            assertEquals("loaded", session.textProperty().get());
            return null;
        });
    }

    private void create(EditorFileService files) throws Exception {
        FxTestSupport.onFxThread(() -> {
            session = new EditorSession(files);
            return null;
        });
    }

    private void open(Path file) throws Exception {
        FxTestSupport.onFxThread(() -> {
            session.open(file, () -> { }, errors::add);
            return null;
        });
        awaitIdle();
        assertTrue(errors.isEmpty());
    }

    private void awaitIdle() throws Exception {
        FxTestSupport.await(() -> !session.busyProperty().get());
    }
}
