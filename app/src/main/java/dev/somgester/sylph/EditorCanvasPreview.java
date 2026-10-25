package dev.somgester.sylph;

import java.util.function.BiConsumer;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

/** Owns live Canvas subscriptions; closing the pane releases its document and theme listeners. */
final class EditorCanvasPreview extends BorderPane implements AutoCloseable {

    private final EditorSession session;

    private final ObservableBooleanValue darkTheme;

    private final EditorCanvas canvas;

    private final ChangeListener<String> textListener;

    private final ChangeListener<Number> revisionListener;

    private final ChangeListener<Boolean> themeListener;

    private final CodeArea source;

    private final InvalidationListener selectionListener;

    private boolean closed;

    @SuppressWarnings("this-escape")
    EditorCanvasPreview(EditorSession session, ObservableBooleanValue darkTheme, CodeArea source,
            Runnable onClose,
            BiConsumer<EditorCanvas.Position, Boolean> onNavigate) {
        this.session = session;
        this.source = source;
        this.darkTheme = darkTheme;
        canvas = new EditorCanvas(session);
        // Resolve positions at render time, after text and selection updates have settled.
        canvas.setSelectionSource(() -> {
            var anchor = source.offsetToPosition(source.getAnchor(), Bias.Forward);
            var caret = source.offsetToPosition(source.getCaretPosition(), Bias.Forward);
            return new EditorCanvas.Selection(new EditorCanvas.Position(anchor.getMajor(), anchor.getMinor()),
                    new EditorCanvas.Position(caret.getMajor(), caret.getMinor()));
        });
        selectionListener = observable -> canvas.requestLayout();
        source.anchorProperty().addListener(selectionListener);
        source.caretPositionProperty().addListener(selectionListener);
        canvas.setId("canvas-preview-surface");
        setId("canvas-preview");
        setMinWidth(180);
        Label title = new Label("Canvas Preview · Read-only");
        title.setTooltip(new Tooltip("Click preview text to locate source; Shift-click extends source selection."));
        canvas.setOnNavigate((position, extend) -> {
            if (!closed) {
                onNavigate.accept(position, extend);
            }
        });
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
            canvas.setSelectionColor(Color.web("#164e63"));
            canvas.setColors(Color.web("#0d0f12"), Color.web("#e5e7eb"), Color.web("#7f8a9d"));
        } else {
            canvas.setSelectionColor(Color.web("#d9d2c5"));
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
            source.anchorProperty().removeListener(selectionListener);
            source.caretPositionProperty().removeListener(selectionListener);
            canvas.setSelectionSource(() -> null);
            canvas.setOnNavigate((position, extend) -> { });
            canvas.setDocument(new PieceTable());
        }
    }
}
