package dev.somgester.sylph;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;

/** Owns live Canvas subscriptions; closing the pane releases its document and theme listeners. */
final class EditorCanvasPreview extends BorderPane implements AutoCloseable {

    private final EditorSession session;

    private final ObservableBooleanValue darkTheme;

    private final EditorCanvas canvas;

    private final ChangeListener<String> textListener;

    private final ChangeListener<Number> revisionListener;

    private final ChangeListener<Boolean> themeListener;

    private boolean closed;

    @SuppressWarnings("this-escape")
    EditorCanvasPreview(EditorSession session, ObservableBooleanValue darkTheme, Runnable onClose) {
        this.session = session;
        this.darkTheme = darkTheme;
        canvas = new EditorCanvas(session);
        canvas.setId("canvas-preview-surface");
        setId("canvas-preview");
        setMinWidth(180);
        Label title = new Label("Canvas Preview · Read-only");
        Button close = new Button("Close");
        close.setId("close-canvas-preview");
        close.setAccessibleText("Close Canvas Preview");
        close.getStyleClass().add("chrome-action");
        close.setOnAction(event -> onClose.run());
        BorderPane header = new BorderPane(title, null, close, null, null);
        header.setId("canvas-preview-header");
        setTop(header);
        setCenter(canvas);
        textListener = (observable, previous, text) -> canvas.refresh();
        revisionListener = (observable, previous, revision) -> canvas.setDocument(session);
        themeListener = (observable, previous, dark) -> updateTheme();
        session.textProperty().addListener(textListener);
        session.documentRevisionProperty().addListener(revisionListener);
        darkTheme.addListener(themeListener);
        updateTheme();
    }

    private void updateTheme() {
        if (darkTheme.get()) {
            canvas.setColors(Color.web("#0d0f12"), Color.web("#e5e7eb"), Color.web("#7f8a9d"));
        } else {
            canvas.setColors(Color.web("#faf9f5"), Color.web("#141413"), Color.web("#75695b"));
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            session.textProperty().removeListener(textListener);
            session.documentRevisionProperty().removeListener(revisionListener);
            darkTheme.removeListener(themeListener);
            canvas.setDocument(new PieceTable());
        }
    }
}
