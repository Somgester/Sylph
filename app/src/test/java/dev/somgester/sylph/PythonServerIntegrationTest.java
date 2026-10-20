package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("pythonServer")
class PythonServerIntegrationTest {

    @TempDir
    Path directory;

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @Test
    void settingsEnableRealPythonCompletionInsideTheApp() throws Exception {
        String executable = System.getProperty("sylph.pythonServerExecutable", "");
        assertFalse(executable.isBlank(), "Supply -PpythonServerExecutable=/absolute/path/to/pyright-langserver");
        Path file = Files.writeString(directory.resolve("ui.py"), "message: str = \"hello\"\nmessage.up\n");
        var app = new EditorApp();
        var stage = FxTestSupport.onFxThread(() -> new Stage());
        Scene scene = FxTestSupport.onFxThread(() -> {
            var appearance = new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 1)));
            var settings = new LanguageServerSettings(LanguageServerSettings.memoryStore());
            var result = app.createScene(stage, appearance, settings);
            stage.show();
            EditorShortcutsTest.press(result, KeyCode.COMMA, false, false, true);
            result.getRoot().applyCss();
            result.getRoot().layout();
            ((TitledPane) result.lookup("#settings-language-servers")).setExpanded(true);
            result.getRoot().applyCss();
            result.getRoot().layout();
            ((CheckBox) result.lookup("#python-server-enabled")).setSelected(true);
            ((TextField) result.lookup("#python-server-executable")).setText(executable);
            ((Button) result.lookup("#python-server-apply")).fire();
            assertTrue(((Label) result.lookup("#python-server-status")).getText().startsWith("Enabled."));
            EditorShortcutsTest.press(result, KeyCode.ESCAPE, false, false, false);
            app.openDefinition(new Location(file.toUri().toString(),
                    new Range(new Position(1, 10), new Position(1, 10))));
            return result;
        });
        try {
            FxTestSupport.await(() -> stage.getTitle().endsWith("ui.py — Sylph"));
            FxTestSupport.onFxThread(() -> {
                EditorShortcutsTest.press(scene, KeyCode.COMMA, false, false, true);
                return null;
            });
            FxTestSupport.await(() -> ((Label) scene.lookup("#python-server-status")).getText().startsWith("Ready"));
            FxTestSupport.onFxThread(() -> {
                EditorShortcutsTest.press(scene, KeyCode.ESCAPE, false, false, false);
                return null;
            });
            FxTestSupport.await(() -> activeEditor(scene).isFocused());
            FxTestSupport.onFxThread(() -> {
                int caret = activeEditor(scene).getText().indexOf("message.up") + "message.up".length();
                activeEditor(scene).moveTo(caret);
                activeEditor(scene).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SPACE,
                        false, true, false, false));
                return null;
            });
            FxTestSupport.await(() -> {
                var list = completions(scene);
                return list != null && list.getItems().stream().anyMatch(item -> item.toString().equals("upper"));
            });
            FxTestSupport.onFxThread(() -> {
                var list = completions(scene);
                int selected = -1;
                for (int index = 0; index < list.getItems().size(); index++) {
                    if (list.getItems().get(index).toString().equals("upper")) {
                        selected = index;
                    }
                }
                list.getSelectionModel().select(selected);
                activeEditor(scene).fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB,
                        false, false, false, false));
                assertTrue(activeEditor(scene).getText().contains("message.upper"));
                activeEditor(scene).undo();
                assertEquals("message: str = \"hello\"\nmessage.up\n", activeEditor(scene).getText());
                return null;
            });
        } finally {
            FxTestSupport.onFxThread(() -> {
                app.closeResources();
                stage.hide();
                return null;
            });
        }
    }

    private static ListView<?> completions(Scene scene) {
        var tabs = (TabPane) scene.lookup("#editor-tabs");
        return (ListView<?>) tabs.getSelectionModel().getSelectedItem().getContent().lookup("#completion-list");
    }

    private static CodeArea activeEditor(Scene scene) {
        var tabs = (TabPane) scene.lookup("#editor-tabs");
        return (CodeArea) tabs.getSelectionModel().getSelectedItem().getContent().lookup("#editor");
    }

    @Test
    void realPyrightProvidesCompletionHoverDefinitionAndDiagnostics() throws Exception {
        String executable = System.getProperty("sylph.pythonServerExecutable", "");
        assertFalse(executable.isBlank(), "Supply -PpythonServerExecutable=/absolute/path/to/pyright-langserver");
        Path file = directory.resolve("example.py");
        String text = "def greet(name: str) -> str:\n    return name.upper()\nmessage = greet(\"hi\")\n"
                + "message.up\nbad: int = \"wrong\"\n";
        Files.writeString(file, text);
        var connection = new LanguageServerConnection(List.of(executable, "--stdio"), directory,
                LanguageServerTestSupport.client());
        var diagnostics = new AtomicReference<LanguageServerConnection.Diagnostics>();
        connection.onDiagnostics(value -> {
            if (value.items().stream().anyMatch(item -> item.getSeverity() == DiagnosticSeverity.Error)) {
                diagnostics.set(value);
            }
        });
        try {
            connection.ready().get(15, TimeUnit.SECONDS);
            connection.open(file, EditorLanguage.PYTHON, text).get(10, TimeUnit.SECONDS);
            var identifier = new TextDocumentIdentifier(file.toUri().toString());
            var completion = connection.request(file, server -> server.getTextDocumentService()
                    .completion(new CompletionParams(identifier, new Position(3, 10)))).get(15, TimeUnit.SECONDS);
            var items = completion.isLeft() ? completion.getLeft() : completion.getRight().getItems();
            assertTrue(items.stream().anyMatch(item -> item.getLabel().equals("upper")));
            int caret = text.indexOf("message.up") + "message.up".length();
            var local = LanguageCompletions.suggest(text, caret, 0, EditorLanguage.PYTHON);
            assertTrue(ServerCompletions.convert(text, caret, local, items).stream()
                    .anyMatch(item -> item.label().equals("upper")),
                    "The actual server edit must be supported by the editor");
            var hover = connection.request(file, server -> server.getTextDocumentService()
                    .hover(new HoverParams(identifier, new Position(2, 12)))).get(15, TimeUnit.SECONDS);
            assertTrue(hover.getContents().toString().contains("greet"));
            var definition = connection.request(file, server -> server.getTextDocumentService()
                    .definition(new DefinitionParams(identifier, new Position(2, 12)))).get(15, TimeUnit.SECONDS);
            int line = definition.isLeft() ? definition.getLeft().getFirst().getRange().getStart().getLine()
                    : definition.getRight().getFirst().getTargetSelectionRange().getStart().getLine();
            assertEquals(0, line);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (diagnostics.get() == null && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(diagnostics.get() != null, "Pyright must publish diagnostics for invalid Python code");
            assertEquals(text, diagnostics.get().text());
        } finally {
            connection.close();
            connection.stopped().get(5, TimeUnit.SECONDS);
        }
    }
}
