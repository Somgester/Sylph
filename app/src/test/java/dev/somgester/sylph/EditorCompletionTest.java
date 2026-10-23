package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EditorCompletionTest {

    private static final String TEXT = "alpha alphabet alphanumeric\nal";

    @TempDir
    Path directory;

    private EditorSession session;

    private EditorView view;

    private Scene scene;

    private Stage stage;

    private TabPane tabs;

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @BeforeEach
    void create() throws Exception {
        FxTestSupport.onFxThread(() -> {
            session = new EditorSession(new EditorFileService());
            view = new EditorView(session);
            tabs = new TabPane(new Tab("File", view));
            tabs.setId("root");
            scene = new Scene(tabs, 420, 280);
            theme("dark");
            stage = new Stage();
            stage.setScene(scene);
            stage.show();
            return null;
        });
        Path file = Files.writeString(directory.resolve("file.txt"), TEXT);
        FxTestSupport.onFxThread(() -> {
            session.open(file, view::documentOpened, error -> { throw new AssertionError(error); });
            return null;
        });
        FxTestSupport.await(() -> !session.busyProperty().get());
        FxTestSupport.onFxThread(() -> {
            view.area().moveTo(TEXT.length());
            view.area().requestFocus();
            tabs.applyCss();
            tabs.layout();
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.close();
            session.close();
            stage.hide();
            return null;
        });
    }

    @ParameterizedTest
    @EnumSource(value = KeyCode.class, names = {"ENTER", "TAB"})
    void acceptsSelectedSuggestionAsOneUndoableDocumentEdit(KeyCode accept) throws Exception {
        show();
        FxTestSupport.onFxThread(() -> {
            assertEquals(List.of("alpha", "alphabet", "alphanumeric"),
                    suggestions().getItems().stream().map(Object::toString).toList());
            assertFalse(session.dirtyProperty().get());
            assertFalse(view.area().isUndoAvailable());
            assertTrue(view.area().isFocused());
            press(KeyCode.DOWN, false);
            press(accept, false);
            assertEquals("alpha alphabet alphanumeric\nalphabet", session.textProperty().get());
            assertFalse(suggestions().isVisible());
            assertTrue(session.dirtyProperty().get());
            view.area().undo();
            assertEquals(TEXT, session.textProperty().get());
            view.area().redo();
            assertTrue(session.textProperty().get().endsWith("\nalphabet"));
            return null;
        });
    }

    @Test
    void typingOpensSuggestionsAndNewPrefixCannotAcceptOldResults() throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.area().deleteText(TEXT.length() - 1, TEXT.length());
            view.area().replaceSelection("l");
            return null;
        });
        FxTestSupport.await(() -> suggestions().isVisible());
        FxTestSupport.onFxThread(() -> {
            view.area().replaceSelection("zz");
            assertFalse(suggestions().isVisible());
            press(KeyCode.TAB, false);
            assertFalse(session.textProperty().get().endsWith("alphabet"));
            return null;
        });
    }

    @Test
    void escapeNavigationSelectionAndReadOnlyStateDismissWithoutChangingText() throws Exception {
        show();
        FxTestSupport.onFxThread(() -> {
            press(KeyCode.ESCAPE, false);
            assertFalse(suggestions().isVisible());
            assertEquals(TEXT, session.textProperty().get());
            return null;
        });
        show();
        FxTestSupport.onFxThread(() -> {
            view.area().moveTo(0);
            assertFalse(suggestions().isVisible());
            view.area().selectRange(0, 5);
            press(KeyCode.SPACE, true);
            assertFalse(suggestions().isVisible());
            view.area().moveTo(TEXT.length());
            view.area().editableProperty().unbind();
            view.area().setEditable(false);
            press(KeyCode.SPACE, true);
            assertFalse(suggestions().isVisible());
            assertEquals(TEXT, session.textProperty().get());
            assertFalse(session.dirtyProperty().get());
            return null;
        });
    }

    @Test
    void mouseAcceptsSuggestionWithoutMovingFocusAwayFromEditor() throws Exception {
        show();
        FxTestSupport.onFxThread(() -> {
            tabs.applyCss();
            tabs.layout();
            ListCell<?> cell = suggestions().lookupAll(".list-cell").stream()
                    .filter(node -> node instanceof ListCell<?> row && "alphabet".equals(row.getText()))
                    .map(node -> (ListCell<?>) node).findFirst().orElseThrow();
            cell.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED, 5, 5, 5, 5, MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false, false, false, true,
                    new PickResult(cell, 5, 5)));
            assertTrue(session.textProperty().get().endsWith("\nalphabet"));
            assertTrue(view.area().isFocused());
            assertFalse(suggestions().isVisible());
            return null;
        });
    }

    @Test
    void switchingTabsHidesOldSuggestionsAndClosingDisposesTheOverlay() throws Exception {
        show();
        EditorSession other = FxTestSupport.onFxThread(() -> new EditorSession(new EditorFileService()));
        EditorView second = FxTestSupport.onFxThread(() -> new EditorView(other));
        try {
            FxTestSupport.onFxThread(() -> {
                other.replaceText("second file");
                tabs.getTabs().add(new Tab("Other", second));
                tabs.getSelectionModel().selectLast();
                second.area().requestFocus();
                return null;
            });
            FxTestSupport.await(() -> !suggestions().isVisible());
            FxTestSupport.onFxThread(() -> {
                assertEquals("second file", other.textProperty().get());
                assertEquals(TEXT, session.textProperty().get());
                view.close();
                assertEquals(null, view.lookup("#completion-list"));
                return null;
            });
        } finally {
            FxTestSupport.onFxThread(() -> {
                second.close();
                other.close();
                return null;
            });
        }
    }

    @Test
    void dropdownFitsViewportAndUsesBothThemes() throws Exception {
        javafx.scene.paint.Paint previous = null;
        for (String name : List.of("dark", "light")) {
            FxTestSupport.onFxThread(() -> {
                theme(name);
                return null;
            });
            show();
            var color = FxTestSupport.onFxThread(() -> {
                tabs.applyCss();
                tabs.layout();
                var bounds = suggestions().getBoundsInParent();
                assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= view.getWidth());
                assertTrue(bounds.getMinY() >= 0 && bounds.getMaxY() <= view.getHeight());
                ListCell<?> selected = suggestions().lookupAll(".list-cell").stream()
                        .filter(node -> node instanceof ListCell<?> cell && cell.isSelected())
                        .map(node -> (ListCell<?>) node).findFirst().orElseThrow();
                var image = scene.snapshot(null);
                BufferedImage output = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < output.getHeight(); y++) {
                    for (int x = 0; x < output.getWidth(); x++) {
                        output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                    }
                }
                Path file = Path.of("build", "reports", "completion", name + ".png");
                Files.createDirectories(file.getParent());
                ImageIO.write(output, "png", file.toFile());
                return selected.getTextFill();
            });
            if (previous != null) {
                assertNotEquals(previous, color);
            }
            previous = color;
        }
    }

    @Test
    void insertsIndentedTemplateSelectsNameAndUndoesInOneStep() throws Exception {
        Path file = Files.writeString(directory.resolve("Example.java"), "    clsuffix");
        FxTestSupport.onFxThread(() -> {
            session.open(file, view::documentOpened, error -> { throw new AssertionError(error); });
            return null;
        });
        FxTestSupport.await(() -> !session.busyProperty().get());
        FxTestSupport.onFxThread(() -> {
            view.area().moveTo(6);
            return null;
        });
        show();
        FxTestSupport.onFxThread(() -> {
            assertEquals(List.of("class", "class — template"),
                    suggestions().getItems().stream().map(Object::toString).toList());
            press(KeyCode.DOWN, false);
            press(KeyCode.TAB, false);
            assertEquals("    class ClassName {\n        \n    }", session.textProperty().get());
            assertEquals("ClassName", view.area().getSelectedText());
            assertFalse(suggestions().isVisible());
            view.area().undo();
            assertEquals("    clsuffix", session.textProperty().get());
            view.area().redo();
            assertEquals("    class ClassName {\n        \n    }", session.textProperty().get());
            return null;
        });
    }

    @Test
    void saveAsChangesSuggestionLanguage() throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.area().replaceText("def");
            session.save(directory.resolve("example.py"), false, false, () -> { },
                    error -> { throw new AssertionError(error); });
            return null;
        });
        FxTestSupport.await(() -> !session.busyProperty().get());
        show();
        FxTestSupport.onFxThread(() -> {
            assertTrue(suggestions().getItems().stream().anyMatch(item -> item.toString().equals("def — template")));
            session.save(directory.resolve("example.java"), false, false, () -> { },
                    error -> { throw new AssertionError(error); });
            return null;
        });
        FxTestSupport.await(() -> !session.busyProperty().get());
        assertFalse(FxTestSupport.onFxThread(() -> suggestions().isVisible()));
        show();
        FxTestSupport.onFxThread(() -> {
            assertEquals(List.of("default"), suggestions().getItems().stream().map(Object::toString).toList());
            return null;
        });
    }

    private void show() throws Exception {
        FxTestSupport.onFxThread(() -> {
            view.area().requestFocus();
            press(KeyCode.SPACE, true);
            return null;
        });
        FxTestSupport.await(() -> suggestions().isVisible());
    }

    private ListView<?> suggestions() {
        return (ListView<?>) view.lookup("#completion-list");
    }

    private void press(KeyCode code, boolean control) {
        view.area().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false));
    }

    private void theme(String name) {
        scene.getStylesheets().setAll(getClass().getResource("/styles/" + name + ".css").toExternalForm(),
                getClass().getResource("/styles/shell.css").toExternalForm(),
                getClass().getResource("/styles/editor.css").toExternalForm());
    }
}
