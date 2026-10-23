package dev.somgester.sylph;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.Tooltip;

// One tab owns its document, editor history, autosave, and optional preview for its entire lifetime.
final class EditorDocument implements AutoCloseable {

    final EditorSession session = new EditorSession(new EditorFileService());

    final EditorView view = new EditorView(session);

    final AutoSaveController autoSave;

    final Tab tab = new Tab();

    private final AppSettings settings;

    private LanguageServerDocument languageServer;

    private MarkdownPreview preview;

    private SplitPane split;

    private boolean previewVisible;

    private boolean active;

    private boolean closed;

    private final List<Runnable> closeHandlers = new ArrayList<>();

    @SuppressWarnings("this-escape")
    EditorDocument(AppSettings settings) {
        this.settings = settings;
        autoSave = new AutoSaveController(session, settings);
        tab.setId("document-tab");
        tab.setContent(view);
        Tooltip location = new Tooltip();
        location.textProperty().bind(session.pathProperty().map(Path::toString).orElse("Untitled"));
        tab.setTooltip(location);
        tab.graphicProperty().bind(Bindings.createObjectBinding(
                () -> FileIcon.forPath(session.pathProperty().get()), session.pathProperty()));
        tab.textProperty().bind(Bindings.createStringBinding(() -> {
            Path path = session.pathProperty().get();
            return (path == null ? "Untitled" : path.getFileName().toString())
                    + (session.dirtyProperty().get() ? " •" : "");
        }, session.pathProperty(), session.dirtyProperty()));
        session.languageProperty().addListener((observable, previous, language) -> {
            if (language != EditorLanguage.MARKDOWN && previewVisible) {
                hidePreview();
            }
        });
    }

    void attachLanguageServer(LanguageServerConnection connection, Consumer<Throwable> onError) {
        if (closed) {
            throw new IllegalStateException("Document is closed");
        }
        if (languageServer != null) {
            languageServer.close();
        }
        languageServer = new LanguageServerDocument(session, connection, onError);
        view.attachLanguageServer(connection, languageServer);
    }

    Runnable onClose(Runnable handler) {
        if (closed) {
            throw new IllegalStateException("Document is closed");
        }
        closeHandlers.add(handler);
        return () -> closeHandlers.remove(handler);
    }

    void detachLanguageServer() {
        if (languageServer != null) {
            languageServer.close();
            languageServer = null;
            view.detachLanguageServer();
        }
    }

    void setActive(boolean value) {
        if (active == value) {
            return;
        }
        active = value;
        if (preview != null) {
            preview.setActive(active && previewVisible);
        }
    }

    void togglePreview() {
        if (previewVisible) {
            hidePreview();
        } else if (session.languageProperty().get() == EditorLanguage.MARKDOWN
                && !session.busyProperty().get()) {
            if (preview == null) {
                preview = new MarkdownPreview(session, settings, this::hidePreview);
                split = new SplitPane();
                split.setId("markdown-workspace");
            }
            previewVisible = true;
            tab.setContent(null);
            split.getItems().setAll(view, preview);
            split.setDividerPositions(0.5);
            tab.setContent(split);
            preview.setActive(active);
        }
    }

    private void hidePreview() {
        previewVisible = false;
        preview.setActive(false);
        tab.setContent(null);
        split.getItems().clear();
        tab.setContent(view);
        view.area().requestFocus();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        List.copyOf(closeHandlers).forEach(Runnable::run);
        closeHandlers.clear();
        detachLanguageServer();
        autoSave.close();
        session.close();
        view.close();
        if (preview != null) {
            preview.close();
        }
        tab.textProperty().unbind();
        tab.graphicProperty().unbind();
        tab.setGraphic(null);
        tab.setContent(null);
    }
}
