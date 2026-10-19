package dev.somgester.sylph;

import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Task;

final class EditorSession implements AutoCloseable {

    record SavedDocument(Path path, String text) { }

    private final ReadOnlyObjectWrapper<SavedDocument> lastSave = new ReadOnlyObjectWrapper<>();

    private final EditorFileService files;

    private final StringProperty text = new SimpleStringProperty("");

    private final StringProperty savedText = new SimpleStringProperty("");

    private final ReadOnlyBooleanWrapper dirty = new ReadOnlyBooleanWrapper();

    private final ReadOnlyBooleanWrapper busy = new ReadOnlyBooleanWrapper();

    private final ReadOnlyBooleanWrapper editingBlocked = new ReadOnlyBooleanWrapper();

    private final ReadOnlyObjectWrapper<Path> path = new ReadOnlyObjectWrapper<>();

    private final ReadOnlyObjectWrapper<EditorLanguage> language = new ReadOnlyObjectWrapper<>();

    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper("Ready  |  Untitled");

    private final ReadOnlyIntegerWrapper completedOperations = new ReadOnlyIntegerWrapper();

    private final ThreadPoolExecutor executor;

    private EditorFileService.Snapshot snapshot = new EditorFileService.Snapshot(null, "", "\n", false, null);

    private Task<?> operation;

    private boolean closed;

    EditorSession(EditorFileService files) {
        this.files = files;
        dirty.bind(text.isNotEqualTo(savedText));
        language.bind(Bindings.createObjectBinding(() -> EditorLanguage.forPath(path.get()), path));
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable, "sylph-file-worker");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    StringProperty textProperty() {
        return text;
    }

    ReadOnlyBooleanProperty dirtyProperty() {
        return dirty.getReadOnlyProperty();
    }

    ReadOnlyBooleanProperty busyProperty() {
        return busy.getReadOnlyProperty();
    }

    ReadOnlyBooleanProperty editingBlockedProperty() {
        return editingBlocked.getReadOnlyProperty();
    }

    ReadOnlyObjectProperty<Path> pathProperty() {
        return path.getReadOnlyProperty();
    }

    ReadOnlyObjectProperty<EditorLanguage> languageProperty() {
        return language.getReadOnlyProperty();
    }

    ReadOnlyStringProperty statusProperty() {
        return status.getReadOnlyProperty();
    }

    ReadOnlyIntegerProperty completedOperationsProperty() {
        return completedOperations.getReadOnlyProperty();
    }

    ReadOnlyObjectProperty<SavedDocument> lastSaveProperty() {
        return lastSave.getReadOnlyProperty();
    }

    void open(Path file, Runnable onOpened, Consumer<Throwable> onError) {
        requireAvailable();
        Task<EditorFileService.Snapshot> task = new Task<>() {
            @Override
            protected EditorFileService.Snapshot call() throws Exception {
                return files.read(file);
            }
        };
        task.setOnSucceeded(event -> {
            if (!isCurrent(task)) {
                return;
            }
            snapshot = task.getValue();
            lastSave.set(null);
            path.set(snapshot.path());
            savedText.set(snapshot.text());
            text.set(snapshot.text());
            completedOperations.set(completedOperations.get() + 1);
            status.set("Opened  |  " + displayName(snapshot.path()));
            finish();
            onOpened.run();
        });
        task.setOnFailed(event -> {
            if (isCurrent(task)) {
                status.set("Unable to open  |  " + message(task.getException()));
                finish();
                onError.accept(task.getException());
            }
        });
        submit(task, true, "Opening  |  " + displayName(file));
    }

    void save(Path destination, boolean automatic, boolean overwrite, Runnable onSaved,
            Consumer<Throwable> onError) {
        requireAvailable();
        EditorFileService.Snapshot original = snapshot;
        String capturedText = text.get();
        Task<EditorFileService.Snapshot> task = new Task<>() {
            @Override
            protected EditorFileService.Snapshot call() throws Exception {
                return files.save(original, capturedText, destination, overwrite);
            }
        };
        task.setOnSucceeded(event -> {
            if (!isCurrent(task)) {
                return;
            }
            snapshot = task.getValue();
            path.set(snapshot.path());
            savedText.set(capturedText);
            lastSave.set(new SavedDocument(snapshot.path(), capturedText));
            completedOperations.set(completedOperations.get() + 1);
            status.set((automatic ? "Autosaved  |  " : "Saved  |  ") + displayName(snapshot.path()));
            finish();
            onSaved.run();
        });
        task.setOnFailed(event -> {
            if (isCurrent(task)) {
                status.set((automatic ? "Autosave paused  |  " : "Unable to save  |  ")
                        + message(task.getException()));
                // The autosave callback suspends retries before the busy listener can schedule another save.
                if (automatic) {
                    onError.accept(task.getException());
                    finish();
                } else {
                    finish();
                    onError.accept(task.getException());
                }
            }
        });
        submit(task, !automatic, (automatic ? "Autosaving…" : "Saving…"));
    }

    void mutateWorkspace(String description, Callable<Path> mutation, Consumer<Path> onChanged,
            Consumer<Throwable> onError) {
        requireAvailable();
        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                return mutation.call();
            }
        };
        task.setOnSucceeded(event -> {
            if (isCurrent(task)) {
                finish();
                onChanged.accept(task.getValue());
            }
        });
        task.setOnFailed(event -> {
            if (isCurrent(task)) {
                finish();
                onError.accept(task.getException());
            }
        });
        submit(task, true, description);
    }

    void clearDocument() {
        requireAvailable();
        snapshot = new EditorFileService.Snapshot(null, "", "\n", false, null);
        lastSave.set(null);
        path.set(null);
        savedText.set("");
        text.set("");
        completedOperations.set(completedOperations.get() + 1);
        status.set("Ready  |  Untitled");
    }

    private void submit(Task<?> task, boolean blockEditing, String description) {
        operation = task;
        editingBlocked.set(blockEditing);
        busy.set(true);
        status.set(description);
        executor.execute(task);
    }

    private boolean isCurrent(Task<?> task) {
        return !closed && operation == task;
    }

    private void finish() {
        operation = null;
        editingBlocked.set(false);
        busy.set(false);
    }

    private void requireAvailable() {
        if (!Platform.isFxApplicationThread() || closed || busy.get()) {
            throw new IllegalStateException("Editor actions require an available session on the JavaFX thread");
        }
    }

    private static String displayName(Path file) {
        Path name = file.getFileName();
        return name == null ? file.toString() : name.toString();
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override
    public void close() {
        closed = true;
        if (operation != null) {
            operation.cancel(true);
        }
        executor.shutdownNow();
    }
}
