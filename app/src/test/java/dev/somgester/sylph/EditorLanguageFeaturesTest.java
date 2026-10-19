package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.eclipse.lsp4j.Location;
import org.fxmisc.richtext.event.MouseOverTextEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditorLanguageFeaturesTest {

    private static final String TEXT = "alphaLocal\nal";

    private static final String CAPABILITIES = "{\"textDocumentSync\":{\"openClose\":true,\"change\":1},"
            + "\"completionProvider\":{\"triggerCharacters\":[\".\"]},\"hoverProvider\":true,"
            + "\"definitionProvider\":true}";

    @TempDir
    Path directory;

    private LanguageServerTestSupport fixture;

    private EditorDocument document;

    private Stage stage;

    private AppSettings settings;

    private final List<Location> navigated = new ArrayList<>();

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.onFxThread(() -> {
            if (document != null) {
                document.close();
            }
            if (stage != null) {
                stage.hide();
            }
            return null;
        });
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void completionAppliesImportsAndTextEditAsOneUndoableEdit() throws Exception {
        start("features", TEXT);
        press(KeyCode.SPACE, true, false);
        FxTestSupport.await(() -> completions().getItems().stream()
                .anyMatch(item -> item.toString().equals("alphaServer")));
        FxTestSupport.onFxThread(() -> {
            assertEquals("alphaServer", completions().getItems().getFirst().toString());
            pressNow(KeyCode.TAB, false, false);
            assertEquals("import sample;\nalphaLocal\nalphaServer", document.session.textProperty().get());
            assertEquals(document.view.area().getLength(), document.view.area().getCaretPosition());
            assertFalse(completions().isVisible());
            document.view.area().undo();
            assertEquals(TEXT, document.session.textProperty().get());
            document.view.area().redo();
            assertEquals("import sample;\nalphaLocal\nalphaServer", document.session.textProperty().get());
            return null;
        });
        assertEquals(TEXT, Files.readString(directory.resolve("source.java")));
    }

    @Test
    void immediateAutosavePersistsTheEntireCompletion() throws Exception {
        start("features", TEXT);
        FxTestSupport.onFxThread(() -> {
            settings.delaySecondsProperty().set(0);
            settings.autoSaveProperty().set(true);
            return null;
        });
        press(KeyCode.SPACE, true, false);
        FxTestSupport.await(() -> completions().getItems().stream()
                .anyMatch(item -> item.toString().equals("alphaServer")));
        press(KeyCode.TAB, false, false);
        FxTestSupport.await(() -> !document.session.dirtyProperty().get() && !document.session.busyProperty().get());
        assertEquals("import sample;\nalphaLocal\nalphaServer", Files.readString(directory.resolve("source.java")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dark", "light"})
    void informationOverlayUsesThemeColorsAndFitsInsideEditor(String theme) throws Exception {
        start("features", TEXT);
        FxTestSupport.onFxThread(() -> {
            settings.darkThemeProperty().set(theme.equals("dark"));
            stage.getScene().getStylesheets().set(0,
                    getClass().getResource("/styles/" + theme + ".css").toExternalForm());
            return null;
        });
        press(KeyCode.SPACE, true, true);
        FxTestSupport.await(() -> document.view.lookup("#language-info").isVisible());
        FxTestSupport.onFxThread(() -> {
            document.view.applyCss();
            document.view.layout();
            var panel = document.view.lookup("#language-info");
            var bounds = panel.getBoundsInParent();
            assertTrue(bounds.getMinX() >= 0 && bounds.getMinY() >= 0);
            assertTrue(bounds.getMaxX() <= document.view.getWidth());
            assertTrue(bounds.getMaxY() <= document.view.getHeight());
            var expected = javafx.scene.paint.Color.web(theme.equals("dark") ? "#cbd5e1" : "#413c36");
            assertEquals(expected, information().getTextFill());
            var image = stage.getScene().snapshot(null);
            var output = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < output.getHeight(); y++) {
                for (int x = 0; x < output.getWidth(); x++) {
                    output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                }
            }
            Path file = Path.of("build", "reports", "language-features", theme + ".png");
            Files.createDirectories(file.getParent());
            javax.imageio.ImageIO.write(output, "png", file.toFile());
            return null;
        });
    }

    @Test
    void serverTriggerCharactersStartAutomaticCompletion() throws Exception {
        start("features", TEXT);
        FxTestSupport.onFxThread(() -> {
            document.view.area().replaceText("object.");
            return null;
        });
        var context = fixture.await("textDocument/completion", 1).getAsJsonObject("params").getAsJsonObject("context");
        assertEquals(2, context.get("triggerKind").getAsInt());
        assertEquals(".", context.get("triggerCharacter").getAsString());
    }

    @Test
    void keyboardAndMouseHoverRenderPlainTextInsideEditor() throws Exception {
        start("features", TEXT);
        press(KeyCode.SPACE, true, true);
        FxTestSupport.await(() -> document.view.lookup("#language-info").isVisible());
        FxTestSupport.onFxThread(() -> {
            assertEquals("<b>type information</b>", information().getText());
            assertFalse(document.session.dirtyProperty().get());
            assertFalse(document.view.area().isUndoAvailable());
            pressNow(KeyCode.ESCAPE, false, false);
            assertFalse(document.view.lookup("#language-info").isVisible());
            document.view.area().fireEvent(MouseOverTextEvent.beginAt(new Point2D(10, 10), 2));
            return null;
        });
        fixture.await("textDocument/hover", 2);
        FxTestSupport.await(() -> document.view.lookup("#language-info").isVisible());
        FxTestSupport.onFxThread(() -> {
            document.view.area().fireEvent(MouseOverTextEvent.end());
            return null;
        });
        FxTestSupport.await(() -> !document.view.lookup("#language-info").isVisible());
    }

    @ParameterizedTest
    @ValueSource(strings = {"features", "features-links"})
    void definitionSelectsTargetWithoutChangingTextOrUndoHistory(String mode) throws Exception {
        start(mode, TEXT);
        press(KeyCode.F12, false, false);
        FxTestSupport.await(() -> document.view.area().getSelectedText().equals("alpha"));
        FxTestSupport.onFxThread(() -> {
            assertEquals(TEXT, document.session.textProperty().get());
            assertFalse(document.session.dirtyProperty().get());
            assertFalse(document.view.area().isUndoAvailable());
            return null;
        });
    }

    @Test
    void multipleDefinitionsOfferKeyboardChoiceAndCrossFileLocationsGoToNavigator() throws Exception {
        start("features-multiple", TEXT);
        press(KeyCode.F12, false, false);
        FxTestSupport.await(() -> document.view.lookup("#definition-list").isVisible());
        FxTestSupport.onFxThread(() -> {
            document.view.area().fireEvent(MouseOverTextEvent.end());
            assertTrue(document.view.lookup("#definition-list").isVisible());
            return null;
        });
        press(KeyCode.DOWN, false, false);
        press(KeyCode.ENTER, false, false);
        assertEquals("Local", FxTestSupport.onFxThread(() -> document.view.area().getSelectedText()));
        FxTestSupport.onFxThread(() -> {
            document.close();
            stage.hide();
            return null;
        });
        fixture.close();
        start("features-cross", TEXT);
        press(KeyCode.F12, false, false);
        FxTestSupport.await(() -> !navigated.isEmpty());
        assertEquals(directory.resolve("target.java").toUri().toString(), navigated.getFirst().getUri());
    }

    @ParameterizedTest
    @ValueSource(strings = {"completion", "hover", "definition"})
    void delayedResponsesCannotAffectEditedDocument(String feature) throws Exception {
        start("features-delayed", TEXT);
        if (feature.equals("definition")) {
            press(KeyCode.F12, false, false);
        } else {
            press(KeyCode.SPACE, true, feature.equals("hover"));
        }
        fixture.await("textDocument/" + feature, 1);
        FxTestSupport.onFxThread(() -> {
            document.view.area().replaceSelection("z");
            return null;
        });
        fixture.await("textDocument/didChange", 1);
        fixture.await("$/cancelRequest", 1);
        FxTestSupport.onFxThread(() -> {
            assertTrue(document.view.area().getSelectedText().isEmpty());
            assertFalse(document.view.lookup("#language-info").isVisible());
            assertTrue(completions().getItems().stream().noneMatch(item -> item.toString().equals("alphaServer")));
            assertEquals(TEXT + "z", document.session.textProperty().get());
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"features", "features-unversioned"})
    void diagnosticsShowMessagesWithoutChangingSyntaxTextOrUndoHistory(String mode) throws Exception {
        start(mode, "class Example {}");
        FxTestSupport.await(() -> !document.view.lookupAll(".diagnostic-error").isEmpty());
        FxTestSupport.await(() -> document.view.area().getStyleOfChar(0).contains("syntax-keyword"));
        press(KeyCode.M, true, true);
        FxTestSupport.onFxThread(() -> {
            assertTrue(information().getText().contains("Line 1: Example problem"));
            assertFalse(document.session.dirtyProperty().get());
            assertFalse(document.view.area().isUndoAvailable());
            assertTrue(document.view.area().getStyleOfChar(0).contains("syntax-keyword"));
            return null;
        });
    }

    @Test
    void outdatedDiagnosticVersionsAreIgnoredAfterEditing() throws Exception {
        start("features-stale-diagnostics", TEXT);
        FxTestSupport.await(() -> !document.view.lookupAll(".diagnostic-error").isEmpty());
        FxTestSupport.onFxThread(() -> {
            document.view.area().replaceSelection("z");
            return null;
        });
        LanguageServerTestSupport.waitUntil(() -> fixture.diagnosticsReceived.get() >= 2);
        fixture.connection.request(directory.resolve("source.java"), server -> java.util.concurrent.CompletableFuture
                .completedFuture(null)).get(5, TimeUnit.SECONDS);
        FxTestSupport.onFxThread(() -> {
            assertTrue(document.view.lookupAll(".diagnostic-error").isEmpty());
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Show Hover", "Go to Definition", "Show Problems"})
    void contextMenuActionsRestoreEditorFocusBeforeRunning(String command) throws Exception {
        start("features", TEXT);
        FxTestSupport.onFxThread(() -> {
            var menu = document.view.area().getContextMenu();
            menu.show(document.view.area(), 50, 50);
            menu.getItems().stream().filter(item -> command.equals(item.getText())).findFirst().orElseThrow().fire();
            menu.hide();
            return null;
        });
        if (command.equals("Go to Definition")) {
            FxTestSupport.await(() -> document.view.area().getSelectedText().equals("alpha"));
        } else {
            FxTestSupport.await(() -> document.view.lookup("#language-info").isVisible());
        }
        assertTrue(FxTestSupport.onFxThread(() -> document.view.area().isFocused()));
    }

    @Test
    void closingTabRemovesFeatureOverlayAndCancelsPendingRequests() throws Exception {
        start("features-delayed", TEXT);
        press(KeyCode.SPACE, true, true);
        fixture.await("textDocument/hover", 1);
        FxTestSupport.onFxThread(() -> {
            document.close();
            assertTrue(document.view.getChildren().isEmpty() || document.view.lookup("#language-info") == null);
            return null;
        });
        fixture.await("$/cancelRequest", 1);
        fixture.await("textDocument/didClose", 1);
    }

    private void start(String mode, String text) throws Exception {
        fixture = new LanguageServerTestSupport(directory, CAPABILITIES, mode);
        Path file = Files.writeString(directory.resolve("source.java"), text);
        FxTestSupport.onFxThread(() -> {
            settings = new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 1)));
            document = new EditorDocument(settings);
            document.view.setOnDefinition(navigated::add);
            document.view.installContextMenu(() -> { });
            document.attachLanguageServer(fixture.connection, error -> { throw new AssertionError(error); });
            var tabs = new TabPane(document.tab);
            tabs.setId("root");
            var scene = new Scene(tabs, 700, 400);
            scene.getStylesheets().setAll(getClass().getResource("/styles/dark.css").toExternalForm(),
                    getClass().getResource("/styles/shell.css").toExternalForm(),
                    getClass().getResource("/styles/editor.css").toExternalForm());
            stage = new Stage();
            stage.setScene(scene);
            stage.show();
            document.session.open(file, document.view::documentOpened, error -> { throw new AssertionError(error); });
            return null;
        });
        FxTestSupport.await(() -> !document.session.busyProperty().get());
        FxTestSupport.onFxThread(() -> {
            document.view.area().moveTo(text.length());
            document.view.area().requestFocus();
            document.view.applyCss();
            document.view.layout();
            return null;
        });
        fixture.await("textDocument/didOpen", 1);
    }

    private ListView<?> completions() {
        return (ListView<?>) document.view.lookup("#completion-list");
    }

    private Label information() {
        return (Label) document.view.lookup("#language-info-text");
    }

    private void press(KeyCode key, boolean control, boolean shift) throws Exception {
        FxTestSupport.onFxThread(() -> {
            pressNow(key, control, shift);
            return null;
        });
    }

    private void pressNow(KeyCode key, boolean control, boolean shift) {
        document.view.area().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, shift, control, false, false));
    }
}
