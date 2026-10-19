package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditorDefinitionNavigationTest {

    @TempDir
    Path directory;

    private EditorApp app;

    private Stage stage;

    private Scene scene;

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.onFxThread(() -> {
            if (app != null) {
                app.closeResources();
                stage.hide();
            }
            return null;
        });
    }

    private CodeArea editor() {
        var tabs = (TabPane) scene.lookup("#editor-tabs");
        return (CodeArea) tabs.getSelectionModel().getSelectedItem().getContent().lookup("#editor");
    }

    @Test
    void crossFileNavigationKeepsUnsavedTabAndReusesExistingTargetTab() throws Exception {
        Path file = Files.writeString(directory.resolve("target with spaces.java"), "class Target {}");
        var location = new Location(file.toUri().toString(), new Range(new Position(0, 6), new Position(0, 12)));
        FxTestSupport.onFxThread(() -> {
            app = new EditorApp();
            stage = new Stage();
            var settings = new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 1)));
            scene = app.createScene(stage, settings);
            stage.setScene(scene);
            stage.show();
            var editor = (CodeArea) scene.lookup("#editor");
            editor.replaceText("unsaved original");
            app.openDefinition(location);
            return null;
        });
        FxTestSupport.await(() -> {
            var tabs = (TabPane) scene.lookup("#editor-tabs");
            return tabs != null && tabs.getTabs().size() == 2 && editor()
                    .getSelectedText().equals("Target");
        });
        FxTestSupport.onFxThread(() -> {
            var tabs = (TabPane) scene.lookup("#editor-tabs");
            tabs.getSelectionModel().select(0);
            assertEquals("unsaved original", editor().getText());
            assertTrue(tabs.getTabs().getFirst().getText().contains("•"));
            app.openDefinition(location);
            assertEquals(2, tabs.getTabs().size());
            assertEquals("Target", editor().getSelectedText());
            app.openDefinition(new Location(file.toUri().toString(),
                    new Range(new Position(900, 0), new Position(900, 1))));
            assertTrue(((Label) scene.lookup("#status")).getText().contains("outside"));
            return null;
        });
    }
}
