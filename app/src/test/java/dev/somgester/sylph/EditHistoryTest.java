package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EditHistoryTest {

    private final EditHistory history = new EditHistory(100, 100_000);

    @Test
    void reversesCompoundEditsInReverseOrderAndRedoesInOriginalOrder() {
        PieceTable text = new PieceTable("class A {}");
        List<EditHistory.Change> batch = List.of(
                new EditHistory.Change(6, "A", "Example"),
                new EditHistory.Change(0, "", "import x;\n"));
        apply(text, batch);
        history.record(batch);
        assertEquals("import x;\nclass Example {}", text.toString());
        assertTrue(history.undo(changes -> apply(text, changes)));
        assertEquals("class A {}", text.toString());
        assertFalse(history.canUndo());
        assertTrue(history.canRedo());
        assertTrue(history.redo(changes -> apply(text, changes)));
        assertEquals("import x;\nclass Example {}", text.toString());
        assertFalse(history.canRedo());
    }

    @Test
    void capturesImmutableBatchesAndIgnoresNoOpsWithoutLosingRedo() {
        List<EditHistory.Change> batch = new ArrayList<>();
        batch.add(new EditHistory.Change(0, "", "🐍\r\ne\u0301"));
        history.record(batch);
        batch.clear();
        history.undo(changes -> {
            assertEquals("🐍\r\ne\u0301", changes.getFirst().removed());
            assertThrows(UnsupportedOperationException.class, () -> changes.clear());
        });
        history.record(List.of());
        history.record(List.of(new EditHistory.Change(0, "same", "same")));
        assertTrue(history.canRedo());
        assertFalse(history.canUndo());
    }

    @Test
    void newEditsDiscardRedoBranch() {
        history.record(List.of(new EditHistory.Change(0, "", "a")));
        history.record(List.of(new EditHistory.Change(1, "", "b")));
        history.undo(changes -> { });
        history.record(List.of(new EditHistory.Change(1, "", "c")));
        assertFalse(history.canRedo());
        history.undo(changes -> assertEquals("c", changes.getFirst().removed()));
        history.undo(changes -> assertEquals("a", changes.getFirst().removed()));
        assertFalse(history.canUndo());
    }

    @Test
    void failedReplayCanBeRetriedAndCannotReenterHistory() {
        history.record(List.of(new EditHistory.Change(0, "", "a")));
        assertThrows(IllegalStateException.class, () -> history.undo(changes -> {
            assertThrows(IllegalStateException.class, history::clear);
            assertThrows(IllegalStateException.class, () -> history.record(List.of()));
            assertThrows(IllegalStateException.class, () -> history.redo(ignored -> { }));
            throw new IllegalStateException("Document blocked");
        }));
        assertTrue(history.canUndo());
        assertFalse(history.canRedo());
        assertTrue(history.undo(changes -> { }));
        assertThrows(IllegalArgumentException.class, () -> history.redo(changes -> {
            throw new IllegalArgumentException("Invalid range");
        }));
        assertTrue(history.canRedo());
        assertTrue(history.redo(changes -> { }));
    }

    @Test
    void countLimitEvictsOnlyOldestCompleteBatches() {
        EditHistory bounded = new EditHistory(2, 100);
        for (int index = 0; index < 3; index++) {
            bounded.record(List.of(new EditHistory.Change(index, "", "x")));
        }
        bounded.undo(changes -> assertEquals(2, changes.getFirst().position()));
        bounded.undo(changes -> assertEquals(1, changes.getFirst().position()));
        assertFalse(bounded.canUndo());
        assertTrue(bounded.redo(changes -> { }));
        assertTrue(bounded.redo(changes -> { }));
        assertFalse(bounded.canRedo());
    }

    @Test
    void payloadLimitCountsRemovedAndInsertedUtf16AndReclaimsRedo() {
        EditHistory bounded = new EditHistory(10, 5);
        bounded.record(List.of(new EditHistory.Change(0, "🐍", "a"))); // Three UTF-16 units.
        bounded.record(List.of(new EditHistory.Change(0, "a", "bb"))); // Evicts previous batch.
        bounded.undo(changes -> assertEquals("bb", changes.getFirst().removed()));
        assertFalse(bounded.canUndo());
        bounded.record(List.of(new EditHistory.Change(0, "a", "1234"))); // Exact limit; redo reclaimed.
        assertTrue(bounded.canUndo());
        assertFalse(bounded.canRedo());
        bounded.undo(changes -> assertEquals("1234", changes.getFirst().removed()));
    }

    @Test
    void oversizedBatchCreatesBarrierAndClearDropsBothStacks() {
        EditHistory bounded = new EditHistory(10, 2);
        bounded.record(List.of(new EditHistory.Change(0, "", "a")));
        bounded.record(List.of(new EditHistory.Change(1, "", "b")));
        bounded.undo(changes -> { });
        bounded.record(List.of(new EditHistory.Change(0, "a", "long")));
        assertFalse(bounded.canUndo());
        assertFalse(bounded.canRedo());
        bounded.record(List.of(new EditHistory.Change(0, "", "a")));
        bounded.record(List.of(new EditHistory.Change(1, "", "b")));
        bounded.undo(changes -> { });
        bounded.clear();
        assertFalse(bounded.canUndo());
        assertFalse(bounded.canRedo());
        assertFalse(bounded.undo(changes -> { throw new AssertionError("Empty history"); }));
        assertFalse(bounded.redo(changes -> { throw new AssertionError("Empty history"); }));
    }

    @Test
    void rejectsInvalidInputWithoutDiscardingExistingHistory() {
        assertThrows(IllegalArgumentException.class, () -> new EditHistory(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new EditHistory(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new EditHistory.Change(-1, "", "a"));
        assertThrows(NullPointerException.class, () -> new EditHistory.Change(0, null, "a"));
        assertThrows(NullPointerException.class, () -> new EditHistory.Change(0, "a", null));
        assertThrows(ArithmeticException.class, () -> new EditHistory.Change(Integer.MAX_VALUE, "a", ""));
        history.record(List.of(new EditHistory.Change(0, "", "a")));
        history.undo(changes -> { });
        assertThrows(NullPointerException.class, () -> history.record(null));
        assertThrows(NullPointerException.class, () -> history.record(java.util.Arrays.asList(
                new EditHistory.Change(0, "", "x"), null)));
        assertThrows(NullPointerException.class, () -> history.redo(null));
        assertTrue(history.canRedo());
    }

    @Test
    void seededBatchesRoundTripAgainstIndependentSnapshots() {
        Random random = new Random(43117);
        EditHistory large = new EditHistory(1000, 1_000_000);
        PieceTable buffer = new PieceTable("original\n🐍");
        StringBuilder reference = new StringBuilder(buffer.toString());
        List<String> snapshots = new ArrayList<>(List.of(reference.toString()));
        String[] inserts = {"a", "", "🐍", "\r\n", "世界", "e\u0301"};
        for (int batchIndex = 0; batchIndex < 500; batchIndex++) {
            List<EditHistory.Change> changes = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                int start = random.nextInt(reference.length() + 1);
                int end = start + random.nextInt(reference.length() - start + 1);
                String inserted = inserts[random.nextInt(inserts.length)];
                String removed = reference.substring(start, end);
                changes.add(new EditHistory.Change(start, removed, inserted));
                reference.replace(start, end, inserted);
            }
            apply(buffer, changes);
            assertEquals(reference.toString(), buffer.toString());
            large.record(changes);
            if (changes.stream().anyMatch(change -> !change.removed().equals(change.inserted()))) {
                snapshots.add(reference.toString());
            }
        }
        for (int index = snapshots.size() - 2; index >= 0; index--) {
            assertTrue(large.undo(changes -> apply(buffer, changes)));
            assertEquals(snapshots.get(index), buffer.toString());
        }
        assertFalse(large.canUndo());
        for (int index = 1; index < snapshots.size(); index++) {
            assertTrue(large.redo(changes -> apply(buffer, changes)));
            assertEquals(snapshots.get(index), buffer.toString());
        }
        assertFalse(large.canRedo());
    }

    private static void apply(PieceTable buffer, List<EditHistory.Change> changes) {
        for (EditHistory.Change change : changes) {
            int end = change.position() + change.removed().length();
            assertEquals(change.removed(), buffer.subSequence(change.position(), end));
            buffer.replace(change.position(), end, change.inserted());
        }
    }
}
