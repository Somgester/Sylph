package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.scene.shape.Path;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EditorLayoutTest {

    private EditorSession session;

    private EditorView view;

    private CodeArea area;

    private Scene scene;

    private Stage stage;

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @BeforeEach
    void createEditor() throws Exception {
        FxTestSupport.onFxThread(() -> {
            session = new EditorSession(new EditorFileService());
            view = new EditorView(session);
            area = view.area();
            scene = new Scene(view, 500, 300);
            stage = new Stage();
            stage.setScene(scene);
            applyTheme("dark");
            stage.show();
            render();
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

    @Test
    void gutterTracksEmptyDocumentsInsertedLinesUndoAndTrailingBlankLines() throws Exception {
        FxTestSupport.onFxThread(() -> {
            assertEquals(List.of(1), visibleNumbers());
            area.replaceText("first\nsecond\n");
            render();
            assertEquals(List.of(1, 2, 3), visibleNumbers());
            view.documentOpened();
            area.insertText(0, "inserted\n");
            render();
            assertEquals(List.of(1, 2, 3, 4), visibleNumbers());
            area.undo();
            render();
            assertEquals(List.of(1, 2, 3), visibleNumbers());
            assertEquals("first\nsecond\n", session.textProperty().get());
            area.replaceText("");
            render();
            assertEquals(List.of(1), visibleNumbers());
            return null;
        });
    }

    @Test
    void gutterWidthGrowsWhenTheLineCountNeedsMoreDigits() throws Exception {
        FxTestSupport.onFxThread(() -> {
            area.replaceText(lines(9));
            render();
            double oneDigit = lineNumber(1).getWidth();
            area.replaceText(lines(10));
            render();
            double twoDigits = lineNumber(1).getWidth();
            assertTrue(twoDigits > oneDigit);
            area.replaceText(lines(100));
            render();
            assertTrue(lineNumber(1).getWidth() > twoDigits);
            assertEquals(100, area.getParagraphs().size());
            return null;
        });
    }

    @Test
    void gutterStaysVisibleWhenScrollingToTheEndOfALongFileAndLine() throws Exception {
        FxTestSupport.onFxThread(() -> {
            area.replaceText(IntStream.rangeClosed(1, 200)
                    .mapToObj(number -> "line " + number + " " + "wide ".repeat(100))
                    .collect(Collectors.joining("\n")));
            area.moveTo(area.getLength());
            area.requestFollowCaret();
            render();
            return null;
        });
        FxTestSupport.await(() -> {
            render();
            return visibleNumbers().contains(200);
        });
        FxTestSupport.onFxThread(() -> {
            Bounds editorBounds = area.localToScene(area.getBoundsInLocal());
            Label last = lineNumber(200);
            Bounds numberBounds = last.localToScene(last.getBoundsInLocal());
            assertTrue(numberBounds.getMinX() >= editorBounds.getMinX());
            assertTrue(numberBounds.getMaxX() <= editorBounds.getMaxX());
            assertTrue(numberBounds.getMinY() >= editorBounds.getMinY());
            assertTrue(numberBounds.getMaxY() <= editorBounds.getMaxY());
            assertFalse(visibleNumbers().contains(1));
            return null;
        });
    }

    @Test
    void liveThemeChangesKeepMonospaceTextSelectionAndUndoWithReadableColors() throws Exception {
        FxTestSupport.onFxThread(() -> {
            area.replaceText("iiii\nWWWW");
            view.documentOpened();
            area.appendText(" edit");
            area.selectRange(0, 4);
            area.requestFocus();
            render();
            Color previousText = null;
            for (String theme : List.of("dark", "light", "dark")) {
                applyTheme(theme);
                render();
                assertEquals("iiii\nWWWW edit", area.getText());
                assertEquals("iiii", area.getSelectedText());
                assertTrue(view.undoAvailableProperty().get());
                Text narrow = codeText("iiii");
                Text wide = codeText("WWWW edit");
                Text measured = new Text("WWWW");
                measured.setFont(wide.getFont());
                assertEquals(narrow.getLayoutBounds().getWidth(), measured.getLayoutBounds().getWidth(), 0.01);
                Color text = (Color) narrow.getFill();
                Color background = (Color) area.getBackground().getFills().getFirst().getFill();
                Color gutter = (Color) lineNumber(1).getTextFill();
                assertTrue(contrast(text, background) >= 4.5);
                assertTrue(contrast(gutter, background) >= 4.5);
                if (previousText != null) {
                    assertFalse(previousText.equals(text));
                }
                previousText = text;
                Path caret = (Path) area.lookup(".caret");
                assertTrue(contrast((Color) caret.getStroke(), background) >= 4.5);
                Path selection = (Path) area.lookup(".selection");
                assertTrue(contrast(text, (Color) selection.getFill()) >= 4.5);
            }
            area.undo();
            assertEquals("iiii\nWWWW", session.textProperty().get());
            return null;
        });
    }

    private void applyTheme(String theme) {
        scene.getStylesheets().setAll(getClass().getResource("/styles/" + theme + ".css").toExternalForm(),
                getClass().getResource("/styles/editor.css").toExternalForm());
    }

    private void render() {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        scene.snapshot(null);
    }

    private List<Integer> visibleNumbers() {
        return area.lookupAll(".lineno").stream().map(node -> ((Label) node).getText())
                .filter(text -> text != null && !text.isBlank()).map(String::strip).map(Integer::valueOf)
                .sorted().toList();
    }

    private Label lineNumber(int number) {
        return area.lookupAll(".lineno").stream().map(node -> (Label) node)
                .filter(label -> Integer.toString(number).equals(label.getText().strip()))
                .findFirst().orElseThrow();
    }

    private Text codeText(String text) {
        return area.lookupAll(".paragraph-text .text").stream()
                .filter(node -> node instanceof Text span && text.equals(span.getText()))
                .map(node -> (Text) node).findFirst().orElseThrow();
    }

    private static String lines(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(number -> "line " + number)
                .collect(Collectors.joining("\n"));
    }

    private static double contrast(Color first, Color second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(Color color) {
        return 0.2126 * linear(color.getRed()) + 0.7152 * linear(color.getGreen()) + 0.0722 * linear(color.getBlue());
    }

    private static double linear(double component) {
        return component <= 0.04045 ? component / 12.92 : Math.pow((component + 0.055) / 1.055, 2.4);
    }
}
