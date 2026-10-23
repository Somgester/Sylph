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

    private record Batch(List<Change> changes, long units) { }

    private final int maxBatches;

    private final long maxUnits;

    private final ArrayDeque<Batch> undo = new ArrayDeque<>();

    private final ArrayDeque<Batch> redo = new ArrayDeque<>();

    private long retainedUnits;

    private boolean replaying;

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
        }
        redo.clear();
        if (units > maxUnits) {
            // An unrecorded edit is a barrier: older offsets must never be replayed across it.
            clear();
            return;
        }
        while (!undo.isEmpty()
                && (undo.size() >= maxBatches || retainedUnits > maxUnits - units)) {
            retainedUnits -= undo.removeFirst().units();
        }
        undo.addLast(new Batch(captured, units));
        retainedUnits += units;
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
        } finally {
            replaying = false;
        }
        return true;
    }

    void clear() {
        requireIdle();
        undo.clear();
        redo.clear();
        retainedUnits = 0;
    }

    private void requireIdle() {
        if (replaying) {
            throw new IllegalStateException("Cannot mutate history during replay");
        }
    }
}
