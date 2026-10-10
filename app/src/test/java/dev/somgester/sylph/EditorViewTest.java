package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
            session.textProperty().set("Hello 世界\n");
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
            session.textProperty().set("before close");
            view.close();
            view.close();
            session.textProperty().set("after close");
            assertEquals("before close", view.area().getText());
            assertFalse(view.area().editableProperty().isBound());
            return null;
        });
    }
}
