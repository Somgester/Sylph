package dev.somgester.sylph;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.event.EventHandler;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.undo.UndoManager;
import org.reactfx.Subscription;
import org.reactfx.value.Val;
import org.reactfx.value.Var;

/** Keeps RichTextFX commands compatible while Sylph owns bounded, plain-text history. */
final class EditorUndoManager implements UndoManager<List<EditHistory.Change>> {

    private static final long MERGE_NANOS = 500_000_000L;

    private final EditHistory history = new EditHistory(1000, 4L * 1024 * 1024);

    private final CodeArea area;

    private final BooleanSupplier editable;

    private final LongSupplier clock;

    private final Var<Boolean> undoAvailable = Var.newSimpleVar(false);

    private final Var<Boolean> redoAvailable = Var.newSimpleVar(false);

    private final Var<List<EditHistory.Change>> nextUndo = Var.newSimpleVar(null);

    private final Var<List<EditHistory.Change>> nextRedo = Var.newSimpleVar(null);

    private final ReadOnlyBooleanWrapper performing = new ReadOnlyBooleanWrapper();

    private final ReadOnlyBooleanWrapper atMark = new ReadOnlyBooleanWrapper(true);

    private final Subscription changes;

    private final ChangeListener<Boolean> updateListener;

    private final EventHandler<MouseEvent> mouseBoundary = event -> preventMerge();

    private final EventHandler<KeyEvent> keyBoundary = event -> {
        switch (event.getCode()) {
            case LEFT, RIGHT, UP, DOWN, HOME, END, PAGE_UP, PAGE_DOWN, ESCAPE, TAB -> preventMerge();
            default -> { }
        }
    };

    private final ChangeListener<Boolean> focusBoundary = (observable, previous, current) -> preventMerge();

    private EditHistory.Position marked = history.position();

    private boolean mergeAllowed;

    private long lastEdit;

    private boolean closed;

    private EditHistory.Selection selectionBefore;

    private EditHistory.Position pendingSelection;

    EditorUndoManager(CodeArea area, BooleanSupplier editable) {
        this(area, editable, System::nanoTime);
    }

    @SuppressWarnings("this-escape")
    EditorUndoManager(CodeArea area, BooleanSupplier editable, LongSupplier clock) {
        this.area = area;
        this.editable = editable;
        this.clock = clock;
        selectionBefore = selection();
        updateListener = (observable, previous, updating) -> {
            if (!performing.get() && !closed) {
                if (updating) {
                    selectionBefore = selection();
                } else if (pendingSelection != null) {
                    history.updateSelectionAfter(pendingSelection, selection());
                    pendingSelection = null;
                }
            }
        };
        area.beingUpdatedProperty().addListener(updateListener);
        changes = area.multiPlainChanges().subscribe(batch -> {
            if (!performing.get() && !closed) {
                List<EditHistory.Change> edits = batch.stream().map(change -> new EditHistory.Change(
                        change.getPosition(), change.getRemoved(), change.getInserted())).toList();
                if (edits.stream().allMatch(change -> change.removed().equals(change.inserted()))) {
                    return;
                }
                long now = clock.getAsLong();
                boolean simple = edits.size() == 1 && simple(edits.getFirst())
                        && selectionBefore.anchor() == selectionBefore.caret();
                history.record(edits, simple && mergeAllowed && now - lastEdit <= MERGE_NANOS,
                        selectionBefore, selection());
                if (area.isBeingUpdated()) {
                    pendingSelection = history.position();
                }
                mergeAllowed = simple;
                lastEdit = now;
                refresh();
            }
        });
        area.addEventFilter(KeyEvent.KEY_PRESSED, keyBoundary);
        area.addEventFilter(MouseEvent.MOUSE_PRESSED, mouseBoundary);
        area.focusedProperty().addListener(focusBoundary);
    }

    private EditHistory.Selection selection() {
        return new EditHistory.Selection(area.getAnchor(), area.getCaretPosition());
    }

    // Completion text and its explicitly chosen selection belong to the same undo step.
    void performEdit(Runnable action) {
        preventMerge();
        try {
            captureSelection(action);
        } finally {
            preventMerge();
        }
    }

    void captureSelection(Runnable action) {
        EditHistory.Position before = history.position();
        try {
            action.run();
        } finally {
            if (!closed && !performing.get() && history.position() != before) {
                history.updateSelectionAfter(history.position(), selection());
            }
        }
    }

    private static boolean simple(EditHistory.Change change) {
        String content = change.removed().isEmpty() ? change.inserted() : change.removed();
        return (change.removed().isEmpty() || change.inserted().isEmpty())
                && content.codePointCount(0, content.length()) == 1
                && !content.contains("\n") && !content.contains("\r");
    }

    @Override
    public boolean undo() {
        return replay(true);
    }

    @Override
    public boolean redo() {
        return replay(false);
    }

    private boolean replay(boolean inverse) {
        if (closed || performing.get() || !editable.getAsBoolean()) {
            return false;
        }
        preventMerge();
        performing.set(true);
        try {
            EditHistory.Selection target = inverse ? history.undoSelection() : history.redoSelection();
            return inverse ? history.undo(edits -> apply(edits, target)) : history.redo(edits -> apply(edits, target));
        } finally {
            performing.set(false);
            refresh();
        }
    }

    private void apply(List<EditHistory.Change> edits, EditHistory.Selection target) {
        // Validate sequential content before committing anything to the live document.
        StringBuilder expected = new StringBuilder(area.getText());
        var builder = area.createMultiChange(edits.size());
        for (EditHistory.Change edit : edits) {
            int end = Math.addExact(edit.position(), edit.removed().length());
            if (!expected.substring(edit.position(), end).equals(edit.removed())) {
                throw new IllegalStateException("Undo history does not match document");
            }
            expected.replace(edit.position(), end, edit.inserted());
            builder.replaceTextAbsolutely(edit.position(), end, edit.inserted());
        }
        if (target != null && (target.anchor() > expected.length() || target.caret() > expected.length())) {
            throw new IllegalStateException("Undo selection does not match document");
        }
        builder.commit();
        if (target == null) {
            EditHistory.Change last = edits.getLast();
            area.moveTo(last.position() + last.inserted().length());
        } else {
            area.selectRange(target.anchor(), target.caret());
        }
        area.requestFollowCaret();
    }

    private void refresh() {
        undoAvailable.setValue(history.canUndo());
        redoAvailable.setValue(history.canRedo());
        nextUndo.setValue(history.nextUndo());
        nextRedo.setValue(history.nextRedo());
        atMark.set(history.position() == marked);
    }

    @Override
    public Val<Boolean> undoAvailableProperty() {
        return undoAvailable;
    }

    @Override
    public boolean isUndoAvailable() {
        return undoAvailable.getValue();
    }

    @Override
    public Val<Boolean> redoAvailableProperty() {
        return redoAvailable;
    }

    @Override
    public boolean isRedoAvailable() {
        return redoAvailable.getValue();
    }

    @Override
    public Val<List<EditHistory.Change>> nextUndoProperty() {
        return nextUndo;
    }

    @Override
    public Val<List<EditHistory.Change>> nextRedoProperty() {
        return nextRedo;
    }

    @Override
    public ObservableBooleanValue performingActionProperty() {
        return performing.getReadOnlyProperty();
    }

    @Override
    public boolean isPerformingAction() {
        return performing.get();
    }

    @Override
    public void preventMerge() {
        mergeAllowed = false;
    }

    @Override
    public void forgetHistory() {
        if (!closed) {
            preventMerge();
            history.forgetUndo();
            refresh();
        }
    }

    @Override
    public UndoPosition getCurrentPosition() {
        EditHistory.Position captured = history.position();
        return new UndoPosition() {
            @Override
            public void mark() {
                preventMerge();
                marked = captured;
                refresh();
            }

            @Override
            public boolean isValid() {
                return captured.isValid();
            }
        };
    }

    @Override
    public ObservableBooleanValue atMarkedPositionProperty() {
        return atMark.getReadOnlyProperty();
    }

    @Override
    public boolean isAtMarkedPosition() {
        return atMark.get();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            changes.unsubscribe();
            area.beingUpdatedProperty().removeListener(updateListener);
            area.removeEventFilter(KeyEvent.KEY_PRESSED, keyBoundary);
            area.removeEventFilter(MouseEvent.MOUSE_PRESSED, mouseBoundary);
            area.focusedProperty().removeListener(focusBoundary);
            history.clear();
            refresh();
        }
    }
}
