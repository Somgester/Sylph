package dev.somgester.sylph;

import java.lang.System.Logger.Level;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.value.ChangeListener;
import org.fxmisc.richtext.CodeArea;
import org.reactfx.Subscription;

// Editor events and result application stay on JavaFX; grammars belong to the worker.
final class SyntaxHighlighter implements AutoCloseable {

    private final CodeArea area;

    private final ReadOnlyObjectProperty<EditorLanguage> language;

    private final BiFunction<String, EditorLanguage, SyntaxTokenizer.Result> tokenize;

    private final ScheduledThreadPoolExecutor worker;

    private final Subscription textChanges;

    private final ChangeListener<EditorLanguage> languageListener;

    private Future<?> pending;

    private long revision;

    private boolean closed;

    private SyntaxStyles.Snapshot painted;

    private int dirtyStart;

    private int dirtyEnd;

    SyntaxHighlighter(CodeArea area, ReadOnlyObjectProperty<EditorLanguage> language) {
        this(area, language, new SyntaxTokenizer()::tokenize);
    }

    @SuppressWarnings("this-escape")
    SyntaxHighlighter(CodeArea area, ReadOnlyObjectProperty<EditorLanguage> language,
            BiFunction<String, EditorLanguage, SyntaxTokenizer.Result> tokenize) {
        this.area = area;
        this.language = language;
        this.tokenize = tokenize;
        worker = new ScheduledThreadPoolExecutor(1, action -> {
            Thread thread = new Thread(action, "sylph-syntax");
            thread.setDaemon(true);
            return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        dirtyEnd = area.getLength();
        textChanges = area.plainTextChanges().subscribe(change -> {
            dirtyStart = Math.min(dirtyStart, change.getPosition());
            if (change.getPosition() < dirtyEnd) {
                dirtyEnd = Math.max(change.getPosition(),
                        dirtyEnd + change.getInserted().length() - change.getRemoved().length());
            }
            dirtyEnd = Math.max(dirtyEnd, change.getPosition() + change.getInserted().length());
            request();
        });
        languageListener = (observable, previous, current) -> {
            clearStyles();
            request();
        };
        language.addListener(languageListener);
        request();
    }

    private void request() {
        long requestedRevision = ++revision;
        if (pending != null) {
            pending.cancel(true);
        }
        EditorLanguage requestedLanguage = language.get();
        if (requestedLanguage == EditorLanguage.PLAIN_TEXT || area.getLength() == 0) {
            clearStyles();
            return;
        }
        String text = area.getText();
        var previous = painted;
        int firstDirty = dirtyStart;
        int lastDirty = dirtyEnd;
        pending = worker.schedule(() -> highlight(text, requestedLanguage, requestedRevision,
                previous, firstDirty, lastDirty),
                120, TimeUnit.MILLISECONDS);
    }

    private void highlight(String text, EditorLanguage requestedLanguage, long requestedRevision,
            SyntaxStyles.Snapshot previous, int firstDirty, int lastDirty) {
        try {
            var result = tokenize.apply(text, requestedLanguage);
            // First-use regex compilation can consume the line budget. Retry once with warm caches.
            if (!result.complete() && !Thread.currentThread().isInterrupted()) {
                result = tokenize.apply(text, requestedLanguage);
            }
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            var snapshot = new SyntaxStyles.Snapshot(text, result);
            var patch = SyntaxStyles.patch(snapshot, previous, firstDirty, lastDirty);
            Platform.runLater(() -> {
                if (!closed && revision == requestedRevision) {
                    area.setStyleSpans(patch.start(), patch.styles());
                    painted = snapshot;
                    dirtyStart = Integer.MAX_VALUE;
                    dirtyEnd = -1;
                }
            });
        } catch (CancellationException cancelled) {
            // A newer document snapshot or close superseded this job.
        } catch (RuntimeException failure) {
            System.getLogger(SyntaxHighlighter.class.getName()).log(Level.WARNING,
                    "Syntax highlighting failed", failure);
            Platform.runLater(() -> {
                if (!closed && revision == requestedRevision) {
                    clearStyles();
                }
            });
        }
    }

    private void clearStyles() {
        area.setStyle(0, area.getLength(), List.of());
        painted = null;
        dirtyStart = 0;
        dirtyEnd = area.getLength();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            revision++;
            textChanges.unsubscribe();
            language.removeListener(languageListener);
            if (pending != null) {
                pending.cancel(true);
            }
            worker.shutdownNow();
        }
    }
}
