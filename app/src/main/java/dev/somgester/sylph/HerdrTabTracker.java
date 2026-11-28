package dev.somgester.sylph;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;

/** Serializes lifecycle saves outside JavaFX and preserves saved tabs until restore is implemented. */
final class HerdrTabTracker implements AutoCloseable {

    private final HerdrSessionStore store;

    private final Consumer<String> onFailure;

    private final Set<String> live = ConcurrentHashMap.newKeySet();

    private boolean closed;

    // Finish queued disk writes on normal JVM shutdown instead of dropping the final tab update.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task ->
            new Thread(task, "sylph-herdr-tab-state"));

    HerdrTabTracker(HerdrSessionStore store, Consumer<String> onFailure) {
        this.store = store;
        this.onFailure = onFailure;
    }

    void opened(HerdrSessionStore.Tab tab) {
        live.add(tab.herdrSessionId());
        update(state -> {
            var tabs = new ArrayList<>(state.tabs());
            var previous = tabs.stream().filter(saved -> saved.herdrSessionId().equals(tab.herdrSessionId()))
                    .findFirst().orElse(tab);
            tabs.removeIf(saved -> saved.herdrSessionId().equals(tab.herdrSessionId()));
            tabs.add(new HerdrSessionStore.Tab(tab.herdrSessionId(),
                    tab.kiroSessionId() == null ? previous.kiroSessionId() : tab.kiroSessionId(),
                    tab.directory(), tab.inEditor(), tab.theme(), previous.paneSessions()));
            return new HerdrSessionStore.State(tabs, tab.herdrSessionId());
        });
    }

    void selected(String id) {
        update(state -> {
            var selected = state.tabs().stream().filter(tab -> tab.herdrSessionId().equals(id)).findFirst();
            if (selected.isEmpty()) {
                return state;
            }
            var tabs = new ArrayList<>(state.tabs());
            tabs.remove(selected.get());
            tabs.add(selected.get());
            return new HerdrSessionStore.State(tabs, id);
        });
    }

    void removed(String id) {
        live.remove(id);
        update(state -> {
            var tabs = state.tabs().stream().filter(tab -> !tab.herdrSessionId().equals(id)).toList();
            String active = id.equals(state.activeSessionId()) ? null : state.activeSessionId();
            return new HerdrSessionStore.State(tabs, active);
        });
    }

    synchronized CompletableFuture<Boolean> captured(String id, String kiroId, Path directory, String pane) {
        if (closed || !live.contains(id)) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.supplyAsync(() -> {
            if (!live.contains(id)) {
                return false;
            }
            try {
                var state = store.load();
                if (state.tabs().stream().noneMatch(tab -> tab.herdrSessionId().equals(id))) {
                    return false;
                }
                var tabs = state.tabs().stream().map(tab -> tab.herdrSessionId().equals(id)
                        ? tab.captured(pane, kiroId, directory)
                        : tab).toList();
                var next = new HerdrSessionStore.State(tabs, state.activeSessionId());
                if (!next.equals(state)) {
                    store.save(next);
                }
                return true;
            } catch (IOException | IllegalArgumentException | SecurityException failure) {
                onFailure.accept("Could not record Kiro session. Existing session state was kept.");
                throw new CompletionException(failure);
            }
        }, worker);
    }

    private synchronized void update(UnaryOperator<HerdrSessionStore.State> operation) {
        if (closed) {
            return;
        }
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

    synchronized CompletableFuture<HerdrSessionStore.State> saved() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.load();
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, worker);
    }

    @Override
    public synchronized void close() {
        closed = true;
        worker.shutdown();
    }
}
