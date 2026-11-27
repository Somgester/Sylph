package dev.somgester.sylph;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Serializes lifecycle saves outside JavaFX and preserves saved tabs until restore is implemented. */
final class HerdrTabTracker implements AutoCloseable {

    private final HerdrSessionStore store;

    private final Consumer<String> onFailure;

    // Finish queued disk writes on normal JVM shutdown instead of dropping the final tab update.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task ->
            new Thread(task, "sylph-herdr-tab-state"));

    HerdrTabTracker(HerdrSessionStore store, Consumer<String> onFailure) {
        this.store = store;
        this.onFailure = onFailure;
    }

    void opened(HerdrSessionStore.Tab tab) {
        update(state -> {
            var tabs = new ArrayList<>(state.tabs());
            if (tabs.stream().noneMatch(saved -> saved.herdrSessionId().equals(tab.herdrSessionId()))) {
                tabs.add(tab);
            }
            return new HerdrSessionStore.State(tabs, tab.herdrSessionId());
        });
    }

    void selected(String id) {
        update(state -> state.tabs().stream().anyMatch(tab -> tab.herdrSessionId().equals(id))
                ? new HerdrSessionStore.State(state.tabs(), id) : state);
    }

    void removed(String id) {
        update(state -> {
            var tabs = state.tabs().stream().filter(tab -> !tab.herdrSessionId().equals(id)).toList();
            String active = id.equals(state.activeSessionId()) ? null : state.activeSessionId();
            return new HerdrSessionStore.State(tabs, active);
        });
    }

    private void update(UnaryOperator<HerdrSessionStore.State> operation) {
        worker.execute(() -> {
            try {
                var state = store.load();
                var next = operation.apply(state);
                if (!next.equals(state)) {
                    store.save(next);
                }
            } catch (IOException | IllegalArgumentException | SecurityException failure) {
                onFailure.accept("Could not save Herdr tabs. Existing session state was kept.");
            }
        });
    }

    CompletableFuture<Void> flushed() {
        return CompletableFuture.runAsync(() -> { }, worker);
    }

    @Override
    public void close() {
        worker.shutdown();
    }
}
