package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LanguageServerSettingsPaneTest {

    private EditorApp app;

    private Stage stage;

    private Scene scene;

    private AppSettings appearance;

    private LanguageServerSettings settings;

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

    @Test
    void settingsApplyOnlyWhenRequestedAndRejectWorkspaceRelativeExecutables() throws Exception {
        create();
        FxTestSupport.onFxThread(() -> {
            var enabled = (CheckBox) scene.lookup("#python-server-enabled");
            var executable = (TextField) scene.lookup("#python-server-executable");
            var apply = (Button) scene.lookup("#python-server-apply");
            enabled.setSelected(true);
            executable.setText("custom-pyright");
            assertFalse(settings.configuration(LanguageServerSettings.Server.PYTHON).get().enabled());
            apply.fire();
            assertTrue(settings.configuration(LanguageServerSettings.Server.PYTHON).get().enabled());
            assertEquals("custom-pyright",
                    settings.configuration(LanguageServerSettings.Server.PYTHON).get().executable());
            assertTrue(((Label) scene.lookup("#python-server-status")).getText().startsWith("Enabled."));
            executable.setText("./workspace-server");
            apply.fire();
            assertEquals("custom-pyright",
                    settings.configuration(LanguageServerSettings.Server.PYTHON).get().executable());
            assertTrue(((Label) scene.lookup("#python-server-validation")).getText().contains("absolute"));
            return null;
        });
    }

    @Test
    void reloadKeepsEnabledStateAndExecutable() throws Exception {
        create();
        FxTestSupport.onFxThread(() -> {
            settings.apply(LanguageServerSettings.Server.PYTHON,
                    new LanguageServerSettings.Configuration(true, "custom-pyright"));
            EditorShortcutsTest.press(scene, KeyCode.R, false, true, true);
            scene = stage.getScene();
            EditorShortcutsTest.press(scene, KeyCode.COMMA, false, false, true);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            ((TitledPane) scene.lookup("#settings-language-servers")).setExpanded(true);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            assertTrue(((CheckBox) scene.lookup("#python-server-enabled")).isSelected());
            assertEquals("custom-pyright", ((TextField) scene.lookup("#python-server-executable")).getText());
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dark", "light"})
    void serverControlsUseBothThemesAndFitTheSettingsPage(String theme) throws Exception {
        create();
        FxTestSupport.onFxThread(() -> {
            appearance.darkThemeProperty().set(theme.equals("dark"));
            ((ScrollPane) scene.lookup("#settings-page")).setVvalue(1);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            var executable = (TextField) scene.lookup("#python-server-executable");
            var background = executable.getBackground().getFills().getFirst().getFill();
            assertEquals(Color.web(theme.equals("dark") ? "#0d0f12" : "#faf9f5"), background);
            var image = scene.snapshot(null);
            var output = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < output.getHeight(); y++) {
                for (int x = 0; x < output.getWidth(); x++) {
                    output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                }
            }
            Path file = Path.of("build", "reports", "server-settings", theme + ".png");
            Files.createDirectories(file.getParent());
            javax.imageio.ImageIO.write(output, "png", file.toFile());
            return null;
        });
    }

    private void create() throws Exception {
        FxTestSupport.onFxThread(() -> {
            settings = new LanguageServerSettings(LanguageServerSettings.memoryStore());
            appearance = new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 1)));
            app = new EditorApp();
            stage = new Stage();
            scene = app.createScene(stage, appearance, settings);
            stage.show();
            EditorShortcutsTest.press(scene, KeyCode.COMMA, false, false, true);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            ((TitledPane) scene.lookup("#settings-language-servers")).setExpanded(true);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            return null;
        });
    }
}
