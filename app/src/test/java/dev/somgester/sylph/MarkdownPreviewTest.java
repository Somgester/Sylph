package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.concurrent.Worker;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.PickResult;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownPreviewTest {

    private static final String SOURCE = "# Hello 世界\n\n**Bold** text.\n\n- One\n- Two\n\n"
            + "```java\nclass Example {}\n```\n\n| A | B |\n|---|---|\n| 1 | 2 |\n";

    private static final class TestApp extends EditorApp {

        private Path choice;

        @Override
        File chooseOpenFile() {
            return choice.toFile();
        }

        @Override
        void showFileError(Throwable error) {
            throw new AssertionError(error);
        }
    }

    @TempDir
    Path directory;

    private TestApp app;

    private AppSettings settings;

    private Scene scene;

    private Stage stage;

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @BeforeEach
    void createApp() throws Exception {
        FxTestSupport.onFxThread(() -> {
            app = new TestApp();
            settings = new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 1)));
            stage = new Stage();
            scene = app.createScene(stage, settings);
            stage.show();
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.onFxThread(() -> {
            app.closeResources();
            stage.hide();
            return null;
        });
    }

    @Test
    void shortcutOpensLivePreviewAndPreservesUnsavedTextSelectionAndUndo() throws Exception {
        Path file = open("README.md", SOURCE);
        FxTestSupport.onFxThread(() -> {
            editor().appendText("\nUnsaved edit");
            editor().selectRange(2, 7);
            EditorShortcutsTest.press(scene, KeyCode.V, true, false, true);
            assertTrue(((BorderPane) scene.getRoot()).getCenter() instanceof SplitPane);
            return null;
        });
        awaitHeading("Hello 世界");
        FxTestSupport.onFxThread(() -> {
            assertTrue(web().getEngine().getDocument().getDocumentElement().getTextContent().contains("Unsaved edit"));
            assertEquals("Hello", editor().getSelectedText());
            assertTrue(editor().isUndoAvailable());
            assertTrue(stage.getTitle().startsWith("* "));
            editor().replaceText(2, 7, "Updated");
            return null;
        });
        awaitHeading("Updated 世界");
        FxTestSupport.onFxThread(() -> {
            EditorShortcutsTest.press(scene, KeyCode.V, true, false, true);
            assertEquals("editor-view", documentContent().getId());
            assertTrue(editor().isUndoAvailable());
            editor().undo();
            assertTrue(editor().getText().startsWith("# Hello 世界"));
            return null;
        });
        assertEquals(SOURCE, Files.readString(file));
    }

    @Test
    void rightClickPreviewRendersBothThemesAndCloseButtonReturnsToSource() throws Exception {
        open("README.MD", SOURCE);
        FxTestSupport.onFxThread(() -> {
            editor().fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    30, 30, 100, 100, false, new PickResult(editor(), 30, 30)));
            assertTrue(editor().getContextMenu().isShowing());
            previewItem().fire();
            editor().getContextMenu().hide();
            return null;
        });
        awaitHeading("Hello 世界");
        for (boolean dark : new boolean[] {true, false}) {
            FxTestSupport.onFxThread(() -> {
                settings.darkThemeProperty().set(dark);
                return null;
            });
            String color = dark ? "#0d0f12" : "#faf9f5";
            FxTestSupport.await(() -> web().getEngine().getLoadWorker().getState() == Worker.State.SUCCEEDED
                    && web().getEngine().getDocument().getElementsByTagName("style").item(0)
                    .getTextContent().contains("background:" + color));
            // WebKit paints on a later pulse than its DOM load completion.
            FxTestSupport.await(() -> web().snapshot(null, null).getPixelReader().getColor(8, 8)
                    .equals(Color.web(color)));
            FxTestSupport.onFxThread(() -> {
                var document = web().getEngine().getDocument();
                assertEquals(1, document.getElementsByTagName("table").getLength());
                assertEquals(1, document.getElementsByTagName("strong").getLength());
                assertFalse(web().getEngine().isJavaScriptEnabled());
                savePreview(dark ? "dark" : "light");
                return null;
            });
        }
        FxTestSupport.onFxThread(() -> {
            ((Button) scene.getRoot().lookup("#close-markdown-preview")).fire();
            assertEquals("editor-view", documentContent().getId());
            assertEquals(SOURCE, editor().getText());
            assertFalse(editor().isUndoAvailable());
            return null;
        });
    }

    @Test
    void previewIsDisabledForOtherFilesAndClosesWhenOpeningOne() throws Exception {
        FxTestSupport.onFxThread(() -> {
            assertTrue(previewItem().isDisable());
            EditorShortcutsTest.press(scene, KeyCode.V, true, false, true);
            assertEquals("editor-view", documentContent().getId());
            return null;
        });
        open("README.md", SOURCE);
        FxTestSupport.onFxThread(() -> {
            previewItem().fire();
            return null;
        });
        awaitHeading("Hello 世界");
        open("notes.txt", "plain file");
        FxTestSupport.onFxThread(() -> {
            assertTrue(previewItem().isDisable());
            assertEquals("editor-view", documentContent().getId());
            assertEquals("plain file", editor().getText());
            return null;
        });
    }

    @Test
    void pageHtmlIsInertAndSettingsReturnRestoresPreview() throws Exception {
        open("unsafe.md", "# Safe\n\n<script>window.bad = true</script>\n\n"
                + "[go](javascript:alert(1))\n\n![tracker](https://example.com/tracker.png)");
        FxTestSupport.onFxThread(() -> {
            previewItem().fire();
            return null;
        });
        awaitHeading("Safe");
        FxTestSupport.onFxThread(() -> {
            var document = web().getEngine().getDocument();
            assertEquals(0, document.getElementsByTagName("script").getLength());
            assertEquals(0, document.getElementsByTagName("img").getLength());
            assertEquals(0, document.getElementsByTagName("a").getLength());
            EditorShortcutsTest.press(scene, KeyCode.COMMA, false, false, true);
            assertTrue(((BorderPane) scene.getRoot()).getCenter() instanceof SettingsPage);
            EditorShortcutsTest.press(scene, KeyCode.ESCAPE, false, false, false);
            assertTrue(((BorderPane) scene.getRoot()).getCenter() instanceof SplitPane);
            return null;
        });
        awaitHeading("Safe");
    }

    @Test
    void embeddedHtmlRendersInWebViewAndUpdatesAfterEdits() throws Exception {
        open("html.md", "<div align=\"center\"><h1>HTML heading</h1><p><b>Bold</b></p></div>\n\n"
                + "A <sup>2</sup> and **Markdown bold**.\n\n"
                + "<details open><summary>More</summary><p>Details text</p></details>\n\n"
                + "<table><tr><th colspan=\"2\">Header</th></tr><tr><td>A</td><td>B</td></tr></table>");
        FxTestSupport.onFxThread(() -> {
            previewItem().fire();
            return null;
        });
        awaitHeading("HTML heading");
        FxTestSupport.onFxThread(() -> {
            var document = web().getEngine().getDocument();
            assertEquals(1, document.getElementsByTagName("b").getLength());
            assertEquals(1, document.getElementsByTagName("sup").getLength());
            assertEquals(1, document.getElementsByTagName("strong").getLength());
            assertEquals(1, document.getElementsByTagName("details").getLength());
            assertEquals("More", document.getElementsByTagName("summary").item(0).getTextContent());
            assertEquals(2, document.getElementsByTagName("td").getLength());
            assertFalse(web().getEngine().isJavaScriptEnabled());
            editor().replaceText(editor().getText().replace("HTML heading", "Updated HTML heading"));
            return null;
        });
        awaitHeading("Updated HTML heading");
    }

    @Test
    void liveEditsAndThemeChangesKeepPreviewScroll() throws Exception {
        open("long.md", "# Long\n\n" + "Paragraph text.\n\n".repeat(100));
        FxTestSupport.onFxThread(() -> {
            previewItem().fire();
            return null;
        });
        awaitHeading("Long");
        scrollPreviewTo(1000);
        FxTestSupport.onFxThread(() -> {
            editor().replaceText(2, 6, "Updated");
            return null;
        });
        awaitHeading("Updated");
        assertPreviewScroll(1000);
        FxTestSupport.onFxThread(() -> {
            settings.darkThemeProperty().set(false);
            return null;
        });
        FxTestSupport.await(() -> web().isVisible() && web().getEngine().getDocument() != null
                && web().getEngine().getDocument().getElementsByTagName("style").item(0)
                .getTextContent().contains("background:#faf9f5"));
        assertPreviewScroll(1000);
        FxTestSupport.onFxThread(() -> {
            assertFalse(web().getEngine().isJavaScriptEnabled());
            return null;
        });
    }

    @Test
    void openingAnotherMarkdownFileResetsScrollEvenWithIdenticalText() throws Exception {
        String text = "# Long\n\n" + "Paragraph text.\n\n".repeat(100);
        open("first.md", text);
        FxTestSupport.onFxThread(() -> {
            previewItem().fire();
            return null;
        });
        awaitHeading("Long");
        scrollPreviewTo(1000);
        var firstTab = FxTestSupport.onFxThread(() ->
                ((TabPane) scene.lookup("#editor-tabs"))
                        .getSelectionModel().getSelectedItem());
        var previousDocument = FxTestSupport.onFxThread(() -> web().getEngine().getDocument());
        open("second.md", text);
        FxTestSupport.onFxThread(() -> {
            previewItem().fire();
            return null;
        });
        FxTestSupport.await(() -> web() != null && web().isVisible() && web().getEngine().getDocument() != null
                && !previousDocument.equals(web().getEngine().getDocument()));
        assertPreviewScroll(0);
        FxTestSupport.onFxThread(() -> {
            ((TabPane) scene.lookup("#editor-tabs"))
                    .getSelectionModel().select(firstTab);
            return null;
        });
        awaitHeading("Long");
        assertPreviewScroll(1000);
    }

    @Test
    void shrinkingDocumentClampsScrollAndLaterEditsKeepClampedPosition() throws Exception {
        open("long.md", "# Long\n\n" + "Paragraph text.\n\n".repeat(100));
        FxTestSupport.onFxThread(() -> {
            previewItem().fire();
            return null;
        });
        awaitHeading("Long");
        scrollPreviewTo(1000);
        FxTestSupport.onFxThread(() -> {
            editor().replaceText("# Short");
            return null;
        });
        awaitHeading("Short");
        assertPreviewScroll(0);
        FxTestSupport.onFxThread(() -> {
            editor().replaceText("# Longer\n\n" + "Paragraph text.\n\n".repeat(100));
            return null;
        });
        awaitHeading("Longer");
        assertPreviewScroll(0);
    }

    private void scrollPreviewTo(int y) throws Exception {
        FxTestSupport.onFxThread(() -> {
            web().getEngine().executeScript("window.scrollTo(0, " + y + ")");
            return null;
        });
        assertPreviewScroll(y);
    }

    private void assertPreviewScroll(int expected) throws Exception {
        FxTestSupport.onFxThread(() -> {
            assertEquals(expected, ((Number) web().getEngine().executeScript("window.scrollY")).doubleValue(), 1.0);
            return null;
        });
    }

    private Path open(String filename, String text) throws Exception {
        Path file = Files.writeString(directory.resolve(filename), text);
        FxTestSupport.onFxThread(() -> {
            app.choice = file;
            ((Button) scene.getRoot().lookup("#open-file")).fire();
            return null;
        });
        FxTestSupport.await(() -> text.equals(editor().getText())
                && !((Button) scene.getRoot().lookup("#open-file")).isDisabled());
        return file;
    }

    private MenuItem previewItem() {
        return editor().getContextMenu().getItems().stream().filter(item -> "preview-markdown".equals(item.getId()))
                .findFirst().orElseThrow();
    }

    private WebView web() {
        return (WebView) documentContent().lookup("#markdown-preview-web");
    }

    private void awaitHeading(String title) throws Exception {
        FxTestSupport.await(() -> {
            if (web() == null) {
                return false;
            }
            var document = web().getEngine().getDocument();
            return web().isVisible() && document != null && document.getElementsByTagName("h1").getLength() == 1
                    && title.equals(document.getElementsByTagName("h1").item(0).getTextContent());
        });
    }

    private void savePreview(String theme) throws Exception {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        var image = scene.snapshot(null);
        BufferedImage buffered = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < buffered.getHeight(); y++) {
            for (int x = 0; x < buffered.getWidth(); x++) {
                buffered.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            }
        }
        Path output = Path.of("build", "reports", "markdown-preview", theme + ".png");
        Files.createDirectories(output.getParent());
        ImageIO.write(buffered, "png", output.toFile());
    }

    private CodeArea editor() {
        return app.activeEditor();
    }

    private Node documentContent() {
        return ((TabPane) scene.lookup("#editor-tabs"))
                .getSelectionModel().getSelectedItem().getContent();
    }

}
