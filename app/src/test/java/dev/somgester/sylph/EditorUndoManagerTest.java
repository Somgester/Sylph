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

class EditorUndoManagerTest {

    private EditorSession session;

    private EditorView view;

    private EditorUndoManager manager;

    private final AtomicLong time = new AtomicLong();

    private boolean editable = true;

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
