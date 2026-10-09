package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoSaveControllerTest {

    private static final class Clock implements AutoSaveController.Scheduler {

        private double now;

        private double due;

        private Runnable pending;

        @Override
        public void schedule(int seconds, Runnable action) {
            pending = action;
            due = now + seconds;
        }

        @Override
        public void cancel() {
            pending = null;
        }

        void advance(double seconds) {
            now += seconds;
            if (pending != null && now >= due) {
                Runnable action = pending;
                pending = null;
                action.run();
            }
        }
    }

    @TempDir
    Path tempDir;

    private EditorSession session;

    private AppSettings settings;

    private AutoSaveController autoSave;

    private Clock clock;

    @BeforeAll
    static void initialize() throws Exception {
        FxTestSupport.initialize();
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.onFxThread(() -> {
            if (autoSave != null) {
                autoSave.close();
            }
            if (session != null) {
                session.close();
            }
            return null;
        });
    }

    @Test
    void unnamedFilesWaitForTheirFirstManualSave() throws Exception {
        create(new EditorFileService(), true, false);
        FxTestSupport.onFxThread(() -> {
            session.textProperty().set("unsaved");
            assertNull(clock.pending);
            assertTrue(autoSave.statusProperty().get().contains("Save this file once"));
            return null;
        });
    }

    @Test
    void editsResetTheDelayAndOnlyTheLatestTextIsSaved() throws Exception {
        Path file = Files.writeString(tempDir.resolve("file.txt"), "original");
        create(new EditorFileService(), true, false);
        open(file);
        FxTestSupport.onFxThread(() -> {
            session.textProperty().set("first edit");
            clock.advance(0.6);
            session.textProperty().set("latest edit");
            clock.advance(0.5);
            assertFalse(session.busyProperty().get());
            return null;
        });
        assertEquals("original", Files.readString(file));
        advance(0.5);
        awaitIdle();
        assertEquals("latest edit", Files.readString(file));
        assertFalse(FxTestSupport.onFxThread(() -> session.dirtyProperty().get()));
    }

    @Test
    void disablingAutosaveCancelsPendingWorkAndRejectsAnObsoleteCallback() throws Exception {
        Path file = Files.writeString(tempDir.resolve("file.txt"), "original");
        create(new EditorFileService(), true, false);
        open(file);
        FxTestSupport.onFxThread(() -> {
            session.textProperty().set("edited");
            Runnable obsolete = clock.pending;
            settings.autoSaveProperty().set(false);
            assertNull(clock.pending);
            clock.advance(2);
            settings.autoSaveProperty().set(true);
            obsolete.run();
            assertFalse(session.busyProperty().get());
            return null;
        });
        assertEquals("original", Files.readString(file));
        advance(1);
        awaitIdle();
        assertEquals("edited", Files.readString(file));
    }

    @Test
    void typingDuringASaveKeepsNewEditsAndSchedulesAnotherSave() throws Exception {
        Path file = Files.writeString(tempDir.resolve("file.txt"), "original");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger saves = new AtomicInteger();
        create(new EditorFileService() {
            @Override
            Snapshot save(Snapshot original, String text, Path destination, boolean overwrite) throws IOException {
                assertFalse(Platform.isFxApplicationThread());
                if (saves.incrementAndGet() == 1) {
                    started.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("Timed out");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IOException(ex);
                    }
                }
                return super.save(original, text, destination, overwrite);
            }
        }, true, false);
        open(file);
        try {
            FxTestSupport.onFxThread(() -> {
                session.textProperty().set("first edit");
                clock.advance(1);
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            FxTestSupport.onFxThread(() -> {
                assertFalse(session.editingBlockedProperty().get());
                session.textProperty().set("second edit");
                return null;
            });
        } finally {
            release.countDown();
        }
        awaitIdle();
        assertEquals("first edit", Files.readString(file));
        assertTrue(FxTestSupport.onFxThread(() -> session.dirtyProperty().get()));
        advance(1);
        awaitIdle();
        assertEquals("second edit", Files.readString(file));
        assertEquals("second edit", FxTestSupport.onFxThread(() -> session.textProperty().get()));
    }

    @Test
    void externalChangesPauseAutosaveAndKeepEditsUntilSaveAsSucceeds() throws Exception {
        Path file = Files.writeString(tempDir.resolve("file.txt"), "original");
        create(new EditorFileService(), true, false);
        open(file);
        Files.writeString(file, "external edit");
        FxTestSupport.onFxThread(() -> {
            session.textProperty().set("my edit");
            clock.advance(1);
            return null;
        });
        awaitIdle();
        assertEquals("external edit", Files.readString(file));
        FxTestSupport.onFxThread(() -> {
            assertTrue(session.dirtyProperty().get());
            assertTrue(autoSave.statusProperty().get().startsWith("Autosave paused"));
            session.textProperty().set("more edits");
            clock.advance(5);
            assertNull(clock.pending);
            assertFalse(session.busyProperty().get());
            session.save(tempDir.resolve("preserved.txt"), false, false, () -> { }, error -> {
                throw new AssertionError(error);
            });
            return null;
        });
        awaitIdle();
        assertEquals("more edits", Files.readString(tempDir.resolve("preserved.txt")));
        assertEquals("All changes saved.", FxTestSupport.onFxThread(() -> autoSave.statusProperty().get()));
    }

    @Test
    void changingFilesInvalidatesThePreviousFilesPendingTimer() throws Exception {
        Path first = Files.writeString(tempDir.resolve("first.txt"), "first");
        Path second = Files.writeString(tempDir.resolve("second.txt"), "second");
        create(new EditorFileService(), true, false);
        open(first);
        Runnable obsolete = FxTestSupport.onFxThread(() -> {
            session.textProperty().set("old pending edit");
            return clock.pending;
        });
        open(second);
        FxTestSupport.onFxThread(() -> {
            session.textProperty().set("new edit");
            obsolete.run();
            assertFalse(session.busyProperty().get());
            clock.advance(1);
            return null;
        });
        awaitIdle();
        assertEquals("first", Files.readString(first));
        assertEquals("new edit", Files.readString(second));
    }

    @Test
    void immediateModeSavesLatestTextWithoutWaitingForTypingToPause() throws Exception {
        Path file = Files.writeString(tempDir.resolve("immediate.txt"), "original");
        AtomicInteger writes = new AtomicInteger();
        create(new EditorFileService() {
            @Override
            Snapshot save(Snapshot original, String text, Path destination, boolean overwrite) throws IOException {
                assertFalse(Platform.isFxApplicationThread());
                writes.incrementAndGet();
                return super.save(original, text, destination, overwrite);
            }
        }, true, true);
        open(file);
        FxTestSupport.onFxThread(() -> {
            settings.delaySecondsProperty().set(0);
            session.textProperty().set("first edit");
            session.textProperty().set("latest edit");
            assertFalse(session.busyProperty().get());
            return null;
        });
        FxTestSupport.await(() -> !session.dirtyProperty().get() && !session.busyProperty().get());
        assertEquals("latest edit", Files.readString(file));
        assertEquals(1, writes.get());
    }

    @Test
    void immediateModeSavesNewTypingAsSoonAsThePreviousSaveFinishes() throws Exception {
        Path file = Files.writeString(tempDir.resolve("immediate.txt"), "original");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        create(new EditorFileService() {
            @Override
            Snapshot save(Snapshot original, String text, Path destination, boolean overwrite) throws IOException {
                if (writes.incrementAndGet() == 1) {
                    started.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("Timed out");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IOException(ex);
                    }
                }
                return super.save(original, text, destination, overwrite);
            }
        }, true, true);
        open(file);
        try {
            FxTestSupport.onFxThread(() -> {
                settings.delaySecondsProperty().set(0);
                session.textProperty().set("first edit");
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            FxTestSupport.onFxThread(() -> {
                assertFalse(session.editingBlockedProperty().get());
                session.textProperty().set("second edit");
                session.textProperty().set("newest edit");
                return null;
            });
        } finally {
            release.countDown();
        }
        FxTestSupport.await(() -> !session.dirtyProperty().get() && !session.busyProperty().get());
        assertEquals("newest edit", Files.readString(file));
        assertEquals(2, writes.get());
    }

    @Test
    void disablingImmediateModeInvalidatesAlreadyQueuedCallbacks() throws Exception {
        Path file = Files.writeString(tempDir.resolve("immediate.txt"), "original");
        create(new EditorFileService(), true, true);
        open(file);
        FxTestSupport.onFxThread(() -> {
            settings.delaySecondsProperty().set(0);
            session.textProperty().set("keep unsaved");
            settings.autoSaveProperty().set(false);
            return null;
        });
        FxTestSupport.onFxThread(() -> {
            assertFalse(session.busyProperty().get());
            assertTrue(session.dirtyProperty().get());
            return null;
        });
        assertEquals("original", Files.readString(file));
    }

    @Test
    void suspensionInvalidatesPendingSavesAndRestartsTheFullDelayOnRelease() throws Exception {
        Path file = Files.writeString(tempDir.resolve("file.txt"), "original");
        create(new EditorFileService(), true, false);
        open(file);
        FxTestSupport.onFxThread(() -> {
            session.textProperty().set("edited");
            clock.advance(0.8);
            Runnable stale = clock.pending;
            AutoSaveController.Suspension pause = autoSave.suspend();
            stale.run();
            clock.advance(10);
            session.textProperty().set("latest edit");
            assertNull(clock.pending);
            assertFalse(session.busyProperty().get());
            assertTrue(settings.autoSaveProperty().get());
            pause.close();
            clock.advance(0.9);
            assertFalse(session.busyProperty().get());
            assertEquals("original", Files.readString(file));
            clock.advance(0.1);
            return null;
        });
        awaitIdle();
        assertEquals("latest edit", Files.readString(file));
    }

    @Test
    void nestedSuspensionsReleaseOnceAndRespectAutosaveBeingTurnedOff() throws Exception {
        Path file = Files.writeString(tempDir.resolve("file.txt"), "original");
        create(new EditorFileService(), true, false);
        open(file);
        FxTestSupport.onFxThread(() -> {
            session.textProperty().set("edited");
            AutoSaveController.Suspension outer = autoSave.suspend();
            AutoSaveController.Suspension inner = autoSave.suspend();
            outer.close();
            outer.close();
            clock.advance(10);
            assertNull(clock.pending);
            settings.autoSaveProperty().set(false);
            inner.close();
            inner.close();
            clock.advance(10);
            assertNull(clock.pending);
            assertFalse(session.busyProperty().get());
            settings.autoSaveProperty().set(true);
            clock.advance(1);
            return null;
        });
        awaitIdle();
        assertEquals("edited", Files.readString(file));
    }

    @Test
    void suspensionRejectsImmediateCallbacksAlreadyOnTheJavaFxQueue() throws Exception {
        Path file = Files.writeString(tempDir.resolve("immediate.txt"), "original");
        create(new EditorFileService(), true, true);
        open(file);
        AutoSaveController.Suspension pause = FxTestSupport.onFxThread(() -> {
            settings.delaySecondsProperty().set(0);
            session.textProperty().set("edited");
            return autoSave.suspend();
        });
        FxTestSupport.onFxThread(() -> {
            assertFalse(session.busyProperty().get());
            assertEquals("original", Files.readString(file));
            pause.close();
            return null;
        });
        FxTestSupport.await(() -> !session.dirtyProperty().get() && !session.busyProperty().get());
        assertEquals("edited", Files.readString(file));
    }

    @Test
    void realJavaFxTimerSavesAfterTheConfiguredPause() throws Exception {
        Path file = Files.writeString(tempDir.resolve("file.txt"), "original");
        create(new EditorFileService(), true, true);
        open(file);
        FxTestSupport.onFxThread(() -> {
            session.textProperty().set("saved by the timer");
            return null;
        });
        FxTestSupport.await(() -> !session.dirtyProperty().get());
        assertEquals("saved by the timer", Files.readString(file));
    }

    private void create(EditorFileService files, boolean enabled, boolean realTimer) throws Exception {
        FxTestSupport.onFxThread(() -> {
            settings = new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, enabled, 1)));
            session = new EditorSession(files);
            clock = new Clock();
            autoSave = realTimer ? new AutoSaveController(session, settings)
                    : new AutoSaveController(session, settings, clock);
            return null;
        });
    }

    private void open(Path file) throws Exception {
        FxTestSupport.onFxThread(() -> {
            session.open(file, () -> { }, error -> { throw new AssertionError(error); });
            return null;
        });
        awaitIdle();
    }

    private void advance(double seconds) throws Exception {
        FxTestSupport.onFxThread(() -> {
            clock.advance(seconds);
            return null;
        });
    }

    private void awaitIdle() throws Exception {
        FxTestSupport.await(() -> !session.busyProperty().get());
    }
}
