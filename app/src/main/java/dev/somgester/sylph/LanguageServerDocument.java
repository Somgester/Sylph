package dev.somgester.sylph;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.util.Duration;

/** JavaFX-side binding. A shared connection is owned by the workspace, not by an individual tab. */
final class LanguageServerDocument implements AutoCloseable {

    private final EditorSession session;

    private final LanguageServerConnection connection;

    private final Consumer<Throwable> onError;

    private final PauseTransition delay = new PauseTransition(Duration.millis(100));

    private final InvalidationListener changed = observable -> schedule();

    private final ChangeListener<EditorSession.SavedDocument> saved = (observable, previous, value) -> {
        pendingSave = value;
        schedule();
    };

    private EditorSession.SavedDocument pendingSave;

    private Path path;

    private String text;

    private boolean closed;

    @SuppressWarnings("this-escape")
    LanguageServerDocument(EditorSession session, LanguageServerConnection connection, Consumer<Throwable> onError) {
        this.session = session;
        this.connection = connection;
        this.onError = onError;
        delay.setOnFinished(event -> flush());
        session.pathProperty().addListener(changed);
        session.textProperty().addListener(changed);
        session.editingBlockedProperty().addListener(changed);
        session.lastSaveProperty().addListener(saved);
        schedule();
    }

    private void schedule() {
        if (!closed) {
            delay.playFromStart();
        }
    }

    // Package-visible for requests that must flush the latest editor contents before being sent.
    void flush() {
        delay.stop();
        if (closed || session.editingBlockedProperty().get()) {
            return;
        }
        Path current = session.pathProperty().get();
        String currentText = session.textProperty().get();
        if (!Objects.equals(path, current)) {
            if (path != null) {
                observe(connection.closeDocument(path));
            }
            path = current;
            text = currentText;
            if (path != null) {
                observe(connection.open(path, session.languageProperty().get(), text));
            }
        } else if (path != null && !currentText.equals(text)) {
            text = currentText;
            observe(connection.change(path, text));
        }
        var save = pendingSave;
        pendingSave = null;
        if (save != null && save.path().equals(path)) {
            observe(connection.saved(path, save.text()));
        }
    }

    private void observe(CompletableFuture<Void> operation) {
        operation.whenComplete((value, error) -> {
            if (error != null) {
                Platform.runLater(() -> {
                    if (!closed) {
                        onError.accept(error);
                    }
                });
            }
        });
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            delay.stop();
            session.pathProperty().removeListener(changed);
            session.textProperty().removeListener(changed);
            session.editingBlockedProperty().removeListener(changed);
            session.lastSaveProperty().removeListener(saved);
            if (path != null) {
                connection.closeDocument(path);
            }
            pendingSave = null;
        }
    }
}
