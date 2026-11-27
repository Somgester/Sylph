package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HerdrTabTrackerTest {

    @TempDir
    Path directory;

    @Test
    void lifecycleChangesAreSavedInOrderAndExplicitCloseRemovesOnlyThatTab() throws Exception {
        var store = new HerdrSessionStore(directory.resolve("state.json"));
        var failures = new ArrayList<String>();
        var first = tab("sylph-first", true);
        var second = tab("sylph-second", false);
        try (var tracker = new HerdrTabTracker(store, failures::add)) {
            tracker.opened(first);
            tracker.opened(second);
            tracker.selected(first.herdrSessionId());
            tracker.flushed().get(5, TimeUnit.SECONDS);
            assertEquals(new HerdrSessionStore.State(List.of(first, second), first.herdrSessionId()), store.load());
            tracker.removed(first.herdrSessionId());
            tracker.selected(second.herdrSessionId());
            tracker.flushed().get(5, TimeUnit.SECONDS);
            assertEquals(new HerdrSessionStore.State(List.of(second), second.herdrSessionId()), store.load());
            tracker.removed(second.herdrSessionId());
            tracker.flushed().get(5, TimeUnit.SECONDS);
            assertEquals(HerdrSessionStore.State.empty(), store.load());
            assertTrue(failures.isEmpty());
        }
    }

    @Test
    void normalShutdownKeepsOpenTabAndExistingExactKiroId() throws Exception {
        var store = new HerdrSessionStore(directory.resolve("state.json"));
        var saved = new HerdrSessionStore.Tab("sylph-first", "exact-kiro-id", directory, true, HerdrCliTheme.NORD);
        store.save(new HerdrSessionStore.State(List.of(saved), saved.herdrSessionId()));
        try (var tracker = new HerdrTabTracker(store, message -> { throw new AssertionError(message); })) {
            tracker.opened(tab(saved.herdrSessionId(), true));
            tracker.selected(saved.herdrSessionId());
            tracker.flushed().get(5, TimeUnit.SECONDS);
        }
        assertEquals(List.of(saved), store.load().tabs());
    }

    @Test
    void malformedFileIsPreservedAndFailureIsReported() throws Exception {
        Path file = directory.resolve("state.json");
        Files.writeString(file, "keep these invalid bytes");
        var failures = new ArrayList<String>();
        try (var tracker = new HerdrTabTracker(new HerdrSessionStore(file), failures::add)) {
            tracker.opened(tab("sylph-first", true));
            tracker.flushed().get(5, TimeUnit.SECONDS);
            assertEquals(1, failures.size());
            assertEquals("keep these invalid bytes", Files.readString(file));
        }
    }

    private HerdrSessionStore.Tab tab(String id, boolean inEditor) {
        return new HerdrSessionStore.Tab(id, null, directory, inEditor, HerdrCliTheme.NORD);
    }
}
