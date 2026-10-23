package dev.somgester.sylph;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Thread-confined history of committed edit batches; document mutation belongs to the caller. */
final class EditHistory {

    // Offsets address the document after preceding changes in the same batch, in UTF-16 units.
    record Change(int position, String removed, String inserted) {

        Change {
            Objects.requireNonNull(removed, "removed");
            Objects.requireNonNull(inserted, "inserted");
            if (position < 0) {
                throw new IllegalArgumentException("Negative edit position");
            }
            Math.addExact(position, removed.length());
            Math.addExact(position, inserted.length());
        }

        Change inverse() {
            return new Change(position, inserted, removed);
        }
    }

    static final class Position {

        private boolean valid = true;

        boolean isValid() {
            return valid;
        }
    }

    record Selection(int anchor, int caret) {

        Selection {
            if (anchor < 0 || caret < 0) {
                throw new IllegalArgumentException("Negative selection offset");
            }
        }
    }

    private record Batch(List<Change> changes, long units, Position before, Position after,
            Selection selectionBefore, Selection selectionAfter) { }

    private final int maxBatches;

    private final long maxUnits;

    private final ArrayDeque<Batch> undo = new ArrayDeque<>();

    private final ArrayDeque<Batch> redo = new ArrayDeque<>();

    private long retainedUnits;

    private boolean replaying;

    private Position position = new Position();

    // Limits cover both stacks; units count removed + inserted UTF-16 content, not heap bytes.
    EditHistory(int maxBatches, long maxUnits) {
        if (maxBatches < 1 || maxUnits < 1) {
            throw new IllegalArgumentException("History limits must be positive");
        }
        this.maxBatches = maxBatches;
        this.maxUnits = maxUnits;
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    // Call only after the entire batch has been successfully applied to the document.
    void record(List<Change> changes) {
        record(changes, false);
    }

    void record(List<Change> changes, boolean merge) {
        record(changes, merge, null, null);
    }

    void record(List<Change> changes, boolean merge, Selection selectionBefore, Selection selectionAfter) {
        requireIdle();
        List<Change> captured = List.copyOf(changes).stream()
                .filter(change -> !change.removed().equals(change.inserted())).toList();
        if (captured.isEmpty()) {
            return;
        }
        long units = 0;
        for (Change change : captured) {
            units = Math.addExact(units, (long) change.removed().length() + change.inserted().length());
        }
        for (Batch batch : redo) {
            retainedUnits -= batch.units();
            batch.after().valid = false;
        }
        redo.clear();
        if (units > maxUnits) {
            // An unrecorded edit is a barrier: older offsets must never be replayed across it.
            clear();
            return;
        }
        Position before = position;
        Batch previous = undo.peekLast();
        Change combined = merge && previous != null && captured.size() == 1 && previous.changes().size() == 1
                && Objects.equals(previous.selectionAfter(), selectionBefore)
                ? merge(previous.changes().getFirst(), captured.getFirst()) : null;
        if (combined != null && previous.units() <= maxUnits - units) {
            undo.removeLast();
            retainedUnits -= previous.units();
            before = previous.before();
            previous.after().valid = false;
            units += previous.units();
            captured = List.of(combined);
            selectionBefore = previous.selectionBefore();
        }
        while (!undo.isEmpty()
                && (undo.size() >= maxBatches || retainedUnits > maxUnits - units)) {
            Batch forgotten = undo.removeFirst();
            retainedUnits -= forgotten.units();
            forgotten.before().valid = false;
        }
        position = new Position();
        undo.addLast(new Batch(captured, units, before, position, selectionBefore, selectionAfter));
        retainedUnits += units;
    }

    private static Change merge(Change previous, Change next) {
        if (previous.removed().isEmpty() && next.removed().isEmpty()
                && next.position() == previous.position() + previous.inserted().length()) {
            return new Change(previous.position(), "", previous.inserted() + next.inserted());
        }
        if (previous.inserted().isEmpty() && next.inserted().isEmpty()) {
            if (next.position() == previous.position()) {
                return new Change(previous.position(), previous.removed() + next.removed(), "");
            }
            if (next.position() + next.removed().length() == previous.position()) {
                return new Change(next.position(), next.removed() + previous.removed(), "");
            }
        }
        return null;
    }

    Position position() {
        return position;
    }

    List<Change> nextUndo() {
        return undo.isEmpty() ? null : undo.getLast().changes();
    }

    List<Change> nextRedo() {
        return redo.isEmpty() ? null : redo.getLast().changes();
    }

    Selection undoSelection() {
        return undo.isEmpty() ? null : undo.getLast().selectionBefore();
    }

    Selection redoSelection() {
        return redo.isEmpty() ? null : redo.getLast().selectionAfter();
    }

    // A compound edit may choose its final selection after publishing its text changes.
    void updateSelectionAfter(Position expected, Selection selection) {
        requireIdle();
        Objects.requireNonNull(selection, "selection");
        if (position == expected && !undo.isEmpty()) {
            Batch batch = undo.removeLast();
            undo.addLast(new Batch(batch.changes(), batch.units(), batch.before(), batch.after(),
                    batch.selectionBefore(), selection));
        }
    }

    boolean undo(Consumer<List<Change>> applyAtomically) {
        return replay(undo, redo, true, applyAtomically);
    }

    boolean redo(Consumer<List<Change>> applyAtomically) {
        return replay(redo, undo, false, applyAtomically);
    }

    // The callback must apply all changes atomically or throw without changing the document.
    // It must not record replayed changes. A failed callback leaves both stacks unchanged.
    private boolean replay(ArrayDeque<Batch> source, ArrayDeque<Batch> target, boolean inverse,
            Consumer<List<Change>> applyAtomically) {
        requireIdle();
        Objects.requireNonNull(applyAtomically, "applyAtomically");
        Batch batch = source.peekLast();
        if (batch == null) {
            return false;
        }
        List<Change> changes = inverse
                ? batch.changes().reversed().stream().map(Change::inverse).toList() : batch.changes();
        replaying = true;
        try {
            applyAtomically.accept(changes);
            source.removeLast();
            target.addLast(batch);
            position = inverse ? batch.before() : batch.after();
        } finally {
            replaying = false;
        }
        return true;
    }

    void clear() {
        requireIdle();
        forgetUndo();
        for (Batch batch : redo) {
            batch.after().valid = false;
        }
        position.valid = false;
        position = new Position();
        redo.clear();
        retainedUnits = 0;
    }

    void forgetUndo() {
        requireIdle();
        for (Batch batch : undo) {
            batch.before().valid = false;
            retainedUnits -= batch.units();
        }
        undo.clear();
    }

    private void requireIdle() {
        if (replaying) {
            throw new IllegalStateException("Cannot mutate history during replay");
        }
    }
}
