package dev.somgester.sylph;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.value.ChangeListener;
import javafx.scene.layout.StackPane;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.util.UndoUtils;

final class EditorView extends StackPane implements AutoCloseable {

    private final EditorSession session;

    private final CodeArea area = new CodeArea();

    private final ChangeListener<String> editorTextListener;

    private final ChangeListener<String> sessionTextListener;

    private final ReadOnlyBooleanWrapper undoAvailable = new ReadOnlyBooleanWrapper();

    private final ReadOnlyBooleanWrapper redoAvailable = new ReadOnlyBooleanWrapper();

    private final ChangeListener<Boolean> historyListener;

    private final SyntaxHighlighter highlighter;

    private boolean synchronizing;

    private boolean closed;

    @SuppressWarnings("this-escape")
    EditorView(EditorSession session) {
        this.session = session;
        setId("editor-view");
        area.setId("editor");
        area.setAccessibleText("Document editor");
        area.setWrapText(false);
        var lineNumbers = LineNumberFactory.get(area, digits -> "%1$" + digits + "s", null, null);
        area.setParagraphGraphicFactory(line -> {
            var number = lineNumbers.apply(line);
            // Supply Modena's fallback lookup before the label inherits the editor's theme.
            number.setStyle("-fx-text-background-color: #666666;");
            return number;
        });
        area.replaceText(session.textProperty().get());
        area.getUndoManager().forgetHistory();
        area.editableProperty().bind(session.editingBlockedProperty().not());
        VirtualizedScrollPane<CodeArea> scrolling = new VirtualizedScrollPane<>(area);
        scrolling.setId("editor-scroll");
        getChildren().add(scrolling);
        editorTextListener = (observable, previous, text) -> {
            if (!synchronizing) {
                session.textProperty().set(text);
            }
        };
        sessionTextListener = (observable, previous, text) -> {
            if (!area.getText().equals(text)) {
                synchronizing = true;
                try {
                    area.replaceText(text);
                } finally {
                    synchronizing = false;
                }
            }
        };
        area.textProperty().addListener(editorTextListener);
        session.textProperty().addListener(sessionTextListener);
        historyListener = (observable, previous, available) -> updateHistoryAvailability();
        observeHistory();
        highlighter = new SyntaxHighlighter(area, session.languageProperty());
    }

    CodeArea area() {
        return area;
    }

    ReadOnlyBooleanProperty undoAvailableProperty() {
        return undoAvailable.getReadOnlyProperty();
    }

    ReadOnlyBooleanProperty redoAvailableProperty() {
        return redoAvailable.getReadOnlyProperty();
    }

    void documentOpened() {
        // forgetHistory() clears only undo; a new manager also drops the previous document's redo.
        stopObservingHistory();
        area.setUndoManager(UndoUtils.plainTextUndoManager(area));
        observeHistory();
        area.moveTo(0);
        area.requestFollowCaret();
    }

    private void observeHistory() {
        area.undoAvailableProperty().addListener(historyListener);
        area.redoAvailableProperty().addListener(historyListener);
        updateHistoryAvailability();
    }

    private void stopObservingHistory() {
        area.undoAvailableProperty().removeListener(historyListener);
        area.redoAvailableProperty().removeListener(historyListener);
    }

    private void updateHistoryAvailability() {
        undoAvailable.set(area.isUndoAvailable());
        redoAvailable.set(area.isRedoAvailable());
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            highlighter.close();
            area.textProperty().removeListener(editorTextListener);
            session.textProperty().removeListener(sessionTextListener);
            stopObservingHistory();
            area.editableProperty().unbind();
            area.dispose();
        }
    }
}
