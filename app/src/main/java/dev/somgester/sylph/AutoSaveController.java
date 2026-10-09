package dev.somgester.sylph;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.util.Duration;

final class AutoSaveController implements AutoCloseable {

    private final EditorSession session;

    private final AppSettings settings;

    interface Scheduler {

        void schedule(int seconds, Runnable action);

        void cancel();
    }

    private static final class FxScheduler implements Scheduler {

        private final PauseTransition delay = new PauseTransition();

        @Override
        public void schedule(int seconds, Runnable action) {
            if (seconds == 0) {
                Platform.runLater(action);
                return;
            }
            delay.setDuration(Duration.seconds(seconds));
            delay.setOnFinished(event -> action.run());
            delay.playFromStart();
        }

        @Override
        public void cancel() {
            delay.stop();
        }
    }

    private final Scheduler timer;

    private long generation;

    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper();

    private final InvalidationListener scheduleListener = observable -> schedule();

    private final InvalidationListener resetListener = observable -> {
        pausedAfterFailure = false;
        schedule();
    };

    private boolean pausedAfterFailure;

    private boolean closed;

    private int suspensionCount;

    final class Suspension implements AutoCloseable {

        private boolean released;

        @Override
        public void close() {
            if (!released) {
                released = true;
                suspensionCount--;
                schedule();
            }
        }
    }

    AutoSaveController(EditorSession session, AppSettings settings) {
        this(session, settings, new FxScheduler());
    }

    AutoSaveController(EditorSession session, AppSettings settings, Scheduler timer) {
        this.timer = timer;
        this.session = session;
        this.settings = settings;
        session.textProperty().addListener(scheduleListener);
        session.busyProperty().addListener(scheduleListener);
        session.completedOperationsProperty().addListener(resetListener);
        settings.autoSaveProperty().addListener(resetListener);
        settings.delaySecondsProperty().addListener(scheduleListener);
        schedule();
    }

    ReadOnlyStringProperty statusProperty() {
        return status.getReadOnlyProperty();
    }

    Suspension suspend() {
        if (closed || !Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Autosave suspension requires an active controller on the JavaFX thread");
        }
        suspensionCount++;
        schedule();
        return new Suspension();
    }

    private boolean eligible() {
        return !closed && suspensionCount == 0 && settings.autoSaveProperty().get() && !pausedAfterFailure
                && session.pathProperty().get() != null && session.dirtyProperty().get()
                && !session.busyProperty().get();
    }

    private void schedule() {
        timer.cancel();
        generation++;
        if (closed) {
            return;
        }
        if (!settings.autoSaveProperty().get()) {
            status.set("Autosave is off.");
        } else if (suspensionCount > 0) {
            status.set("Autosave paused while a file action is in progress.");
        } else if (session.pathProperty().get() == null) {
            status.set("Save this file once to give autosave a location.");
        } else if (pausedAfterFailure) {
            status.set("Autosave paused. Save manually or turn it off and on to retry.");
        } else if (session.busyProperty().get()) {
            status.set("Waiting for the current file operation…");
        } else if (!session.dirtyProperty().get()) {
            status.set("All changes saved.");
        } else {
            status.set(settings.delaySecondsProperty().get() == 0
                    ? "Saving changes immediately…" : "Waiting for typing to pause…");
            long scheduledGeneration = generation;
            timer.schedule(settings.delaySecondsProperty().get(), () -> {
                if (scheduledGeneration == generation && eligible()) {
                    session.save(session.pathProperty().get(), true, false, () -> { }, error -> {
                        pausedAfterFailure = true;
                        schedule();
                    });
                }
            });
        }
    }

    @Override
    public void close() {
        closed = true;
        timer.cancel();
        generation++;
        session.textProperty().removeListener(scheduleListener);
        session.busyProperty().removeListener(scheduleListener);
        session.completedOperationsProperty().removeListener(resetListener);
        settings.autoSaveProperty().removeListener(resetListener);
        settings.delaySecondsProperty().removeListener(scheduleListener);
    }
}
