package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditorViewTest {

    @TempDir
    Path directory;

    private EditorSession session;

    private EditorView view;

    private AutoSaveController autoSave;

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @BeforeEach
    void createEditor() throws Exception {
        FxTestSupport.onFxThread(() -> {
            session = new EditorSession(new EditorFileService());
            view = new EditorView(session);
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.onFxThread(() -> {
            if (autoSave != null) {
                autoSave.close();
            }
            view.close();
            session.close();
            return null;
        });
    }

    @Test
    void editsAndUndoUpdateTheSessionWithoutResettingTheCaret() throws Exception {
        FxTestSupport.onFxThread(() -> {
            session.replaceText("Hello 世界\n");
            view.documentOpened();
            CodeArea area = view.area();
            assertEquals("Hello 世界\n", area.getText());
            area.moveTo(6);
            area.insertText(6, "café ");
            assertEquals("Hello café 世界\n", session.textProperty().get());
            assertEquals(11, area.getCaretPosition());
            area.undo();
            assertEquals("Hello 世界\n", session.textProperty().get());
            area.redo();
            assertEquals("Hello café 世界\n", session.textProperty().get());
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void stylingDoesNotChangeTextDirtyStateUndoHistoryOrTriggerAutosave(int delay) throws Exception {
        Path file = Files.writeString(directory.resolve("sample.java"), "class Sample {}\n");
        FxTestSupport.onFxThread(() -> {
            session.open(file, view::documentOpened, error -> { throw new AssertionError(error); });
            assertFalse(view.area().isEditable());
            return null;
        });
        FxTestSupport.await(() -> session.completedOperationsProperty().get() == 1);
        FxTestSupport.onFxThread(() -> {
            AppSettings settings = new AppSettings(
                    new AppSettingsTest.MemoryStore(new AppSettings.Values(true, true, delay)));
            autoSave = new AutoSaveController(session, settings);
            view.area().setStyle(0, 5, List.of("keyword"));
            assertEquals("class Sample {}\n", session.textProperty().get());
            assertFalse(session.dirtyProperty().get());
            assertFalse(view.area().isUndoAvailable());
            assertTrue(view.area().isEditable());
            return null;
        });
        Thread.sleep(1300L);
        assertEquals(1, FxTestSupport.onFxThread(() -> session.completedOperationsProperty().get()));
        assertEquals("class Sample {}\n", Files.readString(file));
    }

    @Test
    void closingTheViewDetachesTheSessionListenerAndIsIdempotent() throws Exception {
        FxTestSupport.onFxThread(() -> {
            session.replaceText("before close");
            view.close();
            view.close();
            session.replaceText("after close");
            assertEquals("before close", view.area().getText());
            assertFalse(view.area().editableProperty().isBound());
            return null;
        });
    }

    @Test
    void completionBatchPublishesOnlyCompleteTextAndUndoRedoKeepBufferSynchronized() throws Exception {
        String original = "alpha\nal";
        String expected = "import beta;\nalpha\nalphaBeta";
        Path file = Files.writeString(directory.resolve("completion.java"), original);
        FxTestSupport.onFxThread(() -> {
            session.open(file, view::documentOpened, error -> { throw new AssertionError(error); });
            return null;
        });
        FxTestSupport.await(() -> !session.busyProperty().get());
        FxTestSupport.onFxThread(() -> {
            List<String> observed = new ArrayList<>();
            session.textProperty().addListener((observable, previous, text) -> {
                assertEquals(view.area().getText(), text, "Observers must not see a partial compound edit");
                observed.add(text);
            });
            CompletionEdit edit = new CompletionEdit("alphaBeta", List.of(
                    new CompletionEdit.Replacement(0, 0, "import beta;\n"),
                    new CompletionEdit.Replacement(6, 8, "alphaBeta")), expected.length(), expected.length());
            edit.apply(view.area());
            assertEquals(List.of(expected), observed);
            assertEquals(expected.length(), view.area().getCaretPosition());
            assertTrue(session.dirtyProperty().get());
            view.area().undo();
            assertEquals(original, session.textProperty().get());
            assertFalse(session.dirtyProperty().get());
            view.area().redo();
            assertEquals(expected, session.textProperty().get());
            assertEquals(List.of(expected, original, expected), observed);
            assertTrue(session.dirtyProperty().get());
            return null;
        });
        assertEquals(original, Files.readString(file));
    }

    @Test
    void programmaticRangesAndUndoUseTheSameNormalizedTextAsTheView() throws Exception {
        FxTestSupport.onFxThread(() -> {
            session.replaceText("one\r\ntwo");
            view.documentOpened();
            assertEquals("one\ntwo", view.area().getText());
            session.replaceText(4, 7, "🐍\rthree");
            assertEquals("one\n🐍\nthree", view.area().getText());
            assertEquals(view.area().getText(), session.textProperty().get());
            view.area().undo();
            assertEquals("one\ntwo", session.textProperty().get());
            view.area().redo();
            assertEquals("one\n🐍\nthree", session.textProperty().get());
            return null;
        });
    }

    @Test
    void pastedLineEndingsAndEmojiReachTheBufferUsingActualEditorOffsets() throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.area().replaceText("first\r\n🐍\rlast");
            assertEquals("first\n🐍\nlast", session.textProperty().get());
            view.area().replaceText(6, 8, "😀 café");
            assertEquals("first\n😀 café\nlast", session.textProperty().get());
            view.area().deleteText(0, 6);
            assertEquals("😀 café\nlast", session.textProperty().get());
            assertEquals(view.area().getText(), session.textProperty().get());
            return null;
        });
    }
}
