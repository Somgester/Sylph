package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javafx.event.Event;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditorUndoManagerTest {

    private EditorSession session;

    private EditorView view;

    private EditorUndoManager manager;

    private final AtomicLong time = new AtomicLong();

    private boolean editable = true;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacementUndoRestoresSelectionDirectionAndRedoRestoresFinalCaret(boolean backwards) throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.area().replaceText("hello 🐍 world");
            manager.forgetHistory();
            int anchor = backwards ? 8 : 6;
            int caret = backwards ? 6 : 8;
            view.area().selectRange(anchor, caret);
            view.area().replaceSelection("fox");
            assertEquals("hello fox world", session.textProperty().get());
            view.area().moveTo(0);
            assertTrue(manager.undo());
            assertEquals("hello 🐍 world", session.textProperty().get());
            assertSelection(anchor, caret);
            view.area().moveTo(view.area().getLength());
            assertTrue(manager.redo());
            assertEquals("hello fox world", session.textProperty().get());
            assertSelection(9, 9);
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void backspaceAndDeleteRestoreTheirDistinctOriginalCarets(boolean backspace) throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.area().replaceText("a🐍b");
            manager.forgetHistory();
            int start = backspace ? 3 : 1;
            view.area().moveTo(start);
            if (backspace) {
                view.area().deletePreviousChar();
            } else {
                view.area().deleteNextChar();
            }
            assertEquals("ab", session.textProperty().get());
            assertSelection(1, 1);
            manager.undo();
            assertEquals("a🐍b", session.textProperty().get());
            assertSelection(start, start);
            manager.redo();
            assertEquals("ab", session.textProperty().get());
            assertSelection(1, 1);
            return null;
        });
    }

    @Test
    void mergedTypingRestoresFirstCaretAndLatestCaretDespiteSubsequentNavigation() throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.area().replaceText("left right");
            manager.forgetHistory();
            view.area().moveTo(5);
            view.area().replaceSelection("🐍");
            view.area().replaceSelection("!");
            view.area().moveTo(0);
            manager.undo();
            assertEquals("left right", session.textProperty().get());
            assertSelection(5, 5);
            assertFalse(manager.isUndoAvailable());
            manager.redo();
            assertEquals("left 🐍!right", session.textProperty().get());
            assertSelection(8, 8);
            return null;
        });
    }

    @Test
    void completionRestoresOriginalSelectionAndFinalPlaceholderSelection() throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.area().replaceText("alpha\nal");
            manager.forgetHistory();
            view.area().selectRange(8, 6);
            String completed = "import beta;\nalpha\nalphaBeta";
            CompletionEdit edit = new CompletionEdit("alphaBeta", List.of(
                    new CompletionEdit.Replacement(0, 0, "import beta;\n"),
                    new CompletionEdit.Replacement(6, 8, "alphaBeta")),
                    completed.length(), completed.length() - 4);
            edit.apply(view.area());
            assertEquals(completed, session.textProperty().get());
            assertSelection(completed.length(), completed.length() - 4);
            view.area().moveTo(0);
            view.area().setStyle(0, 6, List.of("keyword"));
            manager.undo();
            assertEquals("alpha\nal", session.textProperty().get());
            assertSelection(8, 6);
            assertFalse(manager.isUndoAvailable());
            manager.redo();
            assertEquals(completed, session.textProperty().get());
            assertSelection(completed.length(), completed.length() - 4);
            assertEquals("Beta", view.area().getSelectedText());
            return null;
        });
    }

    @Test
    void noOpCompletionCannotOverwriteSelectionOfPreviousEdit() throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.area().replaceSelection("x");
            new CompletionEdit("same", List.of(new CompletionEdit.Replacement(0, 1, "x")), 0, 0)
                    .apply(view.area());
            manager.undo();
            assertSelection(0, 0);
            assertEquals("", session.textProperty().get());
            manager.redo();
            assertEquals("x", session.textProperty().get());
            assertSelection(1, 1);
            return null;
        });
    }

    private void assertSelection(int anchor, int caret) {
        assertEquals(anchor, view.area().getAnchor(), "Selection anchor");
        assertEquals(caret, view.area().getCaretPosition(), "Selection caret");
    }

    @Test
    void selectedCharacterDeletionDoesNotMergeWithFollowingBackspace() throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.area().replaceText("abc");
            manager.forgetHistory();
            view.area().selectRange(3, 2);
            view.area().replaceSelection("");
            view.area().deletePreviousChar();
            assertEquals("a", session.textProperty().get());
            manager.undo();
            assertEquals("ab", session.textProperty().get());
            assertSelection(2, 2);
            manager.undo();
            assertEquals("abc", session.textProperty().get());
            assertSelection(3, 2);
            return null;
        });
    }

    @Test
    void singleChangeBuilderRestoresItsActualSelectionInsteadOfAssumingInsertionEnd() throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.area().replaceText("abcd");
            manager.forgetHistory();
            view.area().selectRange(1, 3);
            view.area().createMultiChange(1).replaceTextAbsolutely(0, 1, "XYZ").commit();
            int afterAnchor = view.area().getAnchor();
            int afterCaret = view.area().getCaretPosition();
            assertTrue(afterAnchor != afterCaret);
            view.area().moveTo(0);
            manager.undo();
            assertEquals("abcd", session.textProperty().get());
            assertSelection(1, 3);
            manager.redo();
            assertEquals("XYZbcd", session.textProperty().get());
            assertSelection(afterAnchor, afterCaret);
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void groupedDeletesKeepFirstAndLastCaretSnapshots(boolean backspace) throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.area().replaceText("ab🐍c");
            manager.forgetHistory();
            int originalCaret = backspace ? 4 : 0;
            view.area().moveTo(originalCaret);
            for (int index = 0; index < 2; index++) {
                if (backspace) {
                    view.area().deletePreviousChar();
                } else {
                    view.area().deleteNextChar();
                }
            }
            String edited = view.area().getText();
            int finalCaret = view.area().getCaretPosition();
            manager.undo();
            assertFalse(manager.isUndoAvailable());
            assertEquals("ab🐍c", session.textProperty().get());
            assertSelection(originalCaret, originalCaret);
            manager.redo();
            assertEquals(edited, session.textProperty().get());
            assertSelection(finalCaret, finalCaret);
            return null;
        });
    }

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @BeforeEach
    void create() throws Exception {
        FxTestSupport.onFxThread(() -> {
            session = new EditorSession(new EditorFileService());
            view = new EditorView(session);
            manager = new EditorUndoManager(view.area(), () -> editable, time::get);
            view.area().setUndoManager(manager);
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.close();
            session.close();
            return null;
        });
    }

    @Test
    void typingBurstsGroupButPausesNewlinesAndExplicitBoundariesDoNot() throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.area().appendText("a");
            time.addAndGet(100_000_000L);
            view.area().appendText("🐍");
            time.addAndGet(501_000_000L);
            view.area().appendText("b");
            view.area().appendText("\n");
            view.area().appendText("c");
            manager.preventMerge();
            view.area().appendText("d");
            for (String expected : List.of("a🐍b\nc", "a🐍b\n", "a🐍b", "a🐍", "")) {
                assertTrue(manager.undo());
                assertEquals(expected, session.textProperty().get());
                assertEquals(expected.length(), view.area().getCaretPosition());
            }
            assertFalse(manager.isUndoAvailable());
            for (int index = 0; index < 5; index++) {
                assertTrue(manager.redo());
            }
            assertEquals("a🐍b\ncd", session.textProperty().get());
            return null;
        });
    }

    @Test
    void navigationAndPasteCreateBoundaries() throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.area().appendText("a");
            Event.fireEvent(view.area(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.LEFT,
                    false, false, false, false));
            view.area().appendText("b");
            view.area().appendText("paste");
            view.area().appendText("c");
            for (String expected : List.of("abpaste", "ab", "a", "")) {
                assertTrue(manager.undo());
                assertEquals(expected, session.textProperty().get());
            }
            return null;
        });
    }

    @Test
    void replacementsCompoundEditsAndStyleChangesPreserveBufferAndDirtyState() throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.area().replaceText("abc");
            manager.forgetHistory();
            view.area().setStyle(0, 1, List.of("keyword"));
            assertFalse(manager.isUndoAvailable());
            view.area().createMultiChange(2)
                    .replaceTextAbsolutely(0, 1, "XYZ")
                    .replaceTextAbsolutely(3, 5, "!").commit();
            assertEquals("XYZ!", session.textProperty().get());
            assertTrue(manager.undo());
            assertEquals("abc", session.textProperty().get());
            assertFalse(manager.isUndoAvailable());
            assertTrue(manager.redo());
            assertEquals("XYZ!", session.textProperty().get());
            return null;
        });
    }

    @Test
    void commandsRespectEditLockAndClosedManagerAndResetDropsRedo() throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.area().appendText("x");
            editable = false;
            assertFalse(manager.undo());
            assertTrue(manager.isUndoAvailable());
            editable = true;
            assertTrue(manager.undo());
            editable = false;
            assertFalse(manager.redo());
            editable = true;
            view.documentOpened();
            assertFalse(view.undoAvailableProperty().get());
            assertFalse(view.redoAvailableProperty().get());
            assertFalse(manager.redo());
            view.area().appendText("new");
            assertFalse(manager.isUndoAvailable());
            view.area().undo();
            assertEquals("", session.textProperty().get());
            return null;
        });
    }

    @Test
    void marksSurviveUndoRedoButInvalidateOnBranchAndForgettingKeepsRedo() throws Exception {
        FxTestSupport.onFxThread(() -> {
            assertTrue(manager.isAtMarkedPosition());
            view.area().appendText("a");
            var marked = manager.getCurrentPosition();
            marked.mark();
            view.area().appendText("b");
            assertFalse(manager.isAtMarkedPosition());
            manager.undo();
            assertTrue(manager.isAtMarkedPosition());
            manager.forgetHistory();
            assertFalse(manager.isUndoAvailable());
            assertTrue(manager.isRedoAvailable());
            manager.redo();
            manager.undo();
            assertTrue(manager.isAtMarkedPosition());
            var abandoned = manager.getNextRedo();
            view.area().appendText("c");
            assertFalse(manager.isRedoAvailable());
            assertEquals("b", abandoned.getFirst().inserted());
            assertTrue(marked.isValid());
            return null;
        });
    }

    @Test
    void keyboardShortcutsAndContextMenuUseCustomHistory() throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.installContextMenu(() -> { });
            view.area().appendText("hello");
            Event.fireEvent(view.area(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.Z,
                    false, true, false, false));
            assertEquals("", session.textProperty().get());
            Event.fireEvent(view.area(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.Z,
                    true, true, false, false));
            assertEquals("hello", session.textProperty().get());
            view.area().getContextMenu().getItems().getFirst().fire();
            assertEquals("", session.textProperty().get());
            view.area().getContextMenu().getItems().get(1).fire();
            assertEquals("hello", session.textProperty().get());
            return null;
        });
    }
}
