package dev.somgester.sylph;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import org.fxmisc.richtext.CodeArea;
import org.reactfx.Subscription;

final class EditorCompletion implements AutoCloseable {

    private final StackPane host;

    private final CodeArea area;

    private final ListView<String> list = new ListView<>();

    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, action -> {
        Thread thread = new Thread(action, "sylph-completion");
        thread.setDaemon(true);
        return thread;
    });

    private final Subscription textChanges;

    private final InvalidationListener caretListener = observable -> hide();

    private final InvalidationListener focusListener = observable -> {
        if (!eligible()) {
            dismiss();
        }
    };

    private final InvalidationListener resizeListener = observable -> dismiss();

    private final EventHandler<KeyEvent> keys = this::keyPressed;

    private final EventHandler<MouseEvent> mouse = event -> dismiss();

    private final EventHandler<ScrollEvent> scroll = event -> dismiss();

    private Future<?> pending;

    private long revision;

    private long textRevision;

    private boolean closed;

    private boolean applying;

    private String snapshot;

    private int snapshotCaret;

    private WordCompletions.Result result;

    @SuppressWarnings("this-escape")
    EditorCompletion(StackPane host, CodeArea area) {
        this.host = host;
        this.area = area;
        worker.setRemoveOnCancelPolicy(true);
        list.setId("completion-list");
        list.setAccessibleText("Document word suggestions");
        list.setManaged(false);
        list.setVisible(false);
        list.setFocusTraversable(false);
        list.setFixedCellSize(28);
        list.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                for (Node node = event.getPickResult().getIntersectedNode(); node != null; node = node.getParent()) {
                    if (node instanceof ListCell<?> cell && !cell.isEmpty()) {
                        list.getSelectionModel().select(cell.getIndex());
                        accept();
                        event.consume();
                        break;
                    }
                }
            }
        });
        host.getChildren().add(list);
        host.addEventFilter(KeyEvent.KEY_PRESSED, keys);
        area.addEventFilter(MouseEvent.MOUSE_PRESSED, mouse);
        area.addEventFilter(ScrollEvent.SCROLL, scroll);
        area.caretPositionProperty().addListener(caretListener);
        area.selectionProperty().addListener(caretListener);
        area.focusedProperty().addListener(focusListener);
        area.editableProperty().addListener(focusListener);
        area.sceneProperty().addListener(focusListener);
        host.widthProperty().addListener(resizeListener);
        host.heightProperty().addListener(resizeListener);
        textChanges = area.plainTextChanges().subscribe(change -> {
            dismiss();
            if (applying) {
                return;
            }
            long requestedText = textRevision;
            int expectedCaret = change.getPosition() + change.getInserted().length();
            // RichTextFX updates the caret as part of the edit; capture it after that update.
            Platform.runLater(() -> {
                if (!closed && textRevision == requestedText && area.getCaretPosition() == expectedCaret) {
                    request(false);
                }
            });
        });
    }

    private boolean eligible() {
        if (closed || !area.isEditable() || !area.isFocused() || area.getScene() == null
                || area.getSelection().getLength() != 0) {
            return false;
        }
        for (Node node = area; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private void keyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.SPACE && event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
            dismiss();
            request(true);
            event.consume();
            return;
        }
        if (event.getCode() == KeyCode.ESCAPE) {
            boolean visible = list.isVisible();
            dismiss();
            if (visible) {
                event.consume();
            }
            return;
        }
        if (!list.isVisible() || event.isControlDown() || event.isMetaDown() || event.isAltDown()
                || event.isShiftDown()) {
            return;
        }
        switch (event.getCode()) {
            case DOWN, UP -> {
                int delta = event.getCode() == KeyCode.DOWN ? 1 : -1;
                int index = Math.clamp(list.getSelectionModel().getSelectedIndex() + delta,
                        0, list.getItems().size() - 1);
                list.getSelectionModel().select(index);
                list.scrollTo(index);
            }
            case ENTER, TAB -> accept();
            default -> { return; }
        }
        event.consume();
    }

    private void request(boolean manual) {
        hide();
        if (!eligible()) {
            return;
        }
        long requestedRevision = revision;
        String text = area.getText();
        int caret = area.getCaretPosition();
        pending = worker.schedule(() -> {
            try {
                var found = WordCompletions.suggest(text, caret, manual ? 0 : 2);
                Platform.runLater(() -> {
                    if (revision == requestedRevision && eligible() && area.getCaretPosition() == caret
                            && area.getText().equals(text) && !found.words().isEmpty()) {
                        snapshot = text;
                        snapshotCaret = caret;
                        result = found;
                        list.getItems().setAll(found.words());
                        list.getSelectionModel().selectFirst();
                        show();
                    }
                });
            } catch (CancellationException cancelled) {
                // A newer edit, navigation, or close superseded this snapshot.
            }
        }, manual ? 0 : 120, TimeUnit.MILLISECONDS);
    }

    private void show() {
        host.applyCss();
        host.layout();
        var bounds = area.getCaretBounds();
        if (bounds.isEmpty()) {
            hide();
            return;
        }
        var below = host.screenToLocal(bounds.orElseThrow().getMinX(), bounds.orElseThrow().getMaxY());
        double width = Math.min(320, host.getWidth() - 8);
        double height = Math.min(Math.min(8, list.getItems().size()) * 28 + 2, host.getHeight() - 8);
        if (width < 80 || height < 30) {
            hide();
            return;
        }
        double y = below.getY();
        if (y + height > host.getHeight() - 4) {
            y = host.screenToLocal(0, bounds.orElseThrow().getMinY()).getY() - height;
        }
        list.resizeRelocate(Math.clamp(below.getX(), 4, host.getWidth() - width - 4),
                Math.clamp(y, 4, host.getHeight() - height - 4), width, height);
        list.setVisible(true);
        list.toFront();
    }

    private void accept() {
        String word = list.getSelectionModel().getSelectedItem();
        var accepted = result;
        boolean current = word != null && accepted != null && eligible()
                && area.getCaretPosition() == snapshotCaret && area.getText().equals(snapshot);
        dismiss();
        if (current) {
            area.getUndoManager().preventMerge();
            applying = true;
            try {
                area.replaceText(accepted.start(), accepted.end(), word);
            } finally {
                applying = false;
            }
            area.getUndoManager().preventMerge();
            area.requestFollowCaret();
        }
    }

    private void hide() {
        revision++;
        if (pending != null) {
            pending.cancel(true);
            pending = null;
        }
        list.setVisible(false);
        list.getItems().clear();
        result = null;
        snapshot = null;
    }

    private void dismiss() {
        textRevision++;
        hide();
    }

    @Override
    public void close() {
        if (!closed) {
            dismiss();
            closed = true;
            worker.shutdownNow();
            textChanges.unsubscribe();
            host.removeEventFilter(KeyEvent.KEY_PRESSED, keys);
            area.removeEventFilter(MouseEvent.MOUSE_PRESSED, mouse);
            area.removeEventFilter(ScrollEvent.SCROLL, scroll);
            area.caretPositionProperty().removeListener(caretListener);
            area.selectionProperty().removeListener(caretListener);
            area.focusedProperty().removeListener(focusListener);
            area.editableProperty().removeListener(focusListener);
            area.sceneProperty().removeListener(focusListener);
            host.widthProperty().removeListener(resizeListener);
            host.heightProperty().removeListener(resizeListener);
            host.getChildren().remove(list);
        }
    }
}
