package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AccountPageTest {

    @BeforeAll
    static void initialize() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @Test
    void nativeAccountButtonAndEscapePreserveUnsavedGuestDocumentInBothThemes() throws Exception {
        FxTestSupport.onFxThread(() -> {
            var app = new EditorApp();
            var settings = new AppSettings(new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 2)));
            Stage stage = new Stage();
            Scene scene = app.createScene(stage, settings);
            stage.show();
            try {
                EditorShortcutsTest.press(scene, KeyCode.N, false, false, true);
                var editor = app.activeEditor();
                editor.replaceText("unsaved guest content");
                for (boolean dark : new boolean[] {true, false}) {
                    settings.darkThemeProperty().set(dark);
                    Button avatar = (Button) scene.getRoot().lookup("#open-account");
                    avatar.fire();
                    assertTrue(avatar.getContextMenu().isShowing());
                    avatar.getContextMenu().getItems().stream()
                            .filter(item -> "account-menu-settings".equals(item.getId()))
                            .findFirst().orElseThrow().fire();
                    avatar.getContextMenu().hide();
                    scene.getRoot().applyCss();
                    scene.getRoot().layout();
                    AccountPage page = (AccountPage) ((BorderPane) scene.getRoot()).getCenter();
                    assertEquals(Color.web(dark ? "#0d0f12" : "#faf9f5"),
                            page.getBackground().getFills().getFirst().getFill());
                    assertTrue(((Button) page.lookup("#account-sign-out")).isDisabled());
                    EditorShortcutsTest.press(scene, KeyCode.ESCAPE, false, false, false);
                    assertFalse(((BorderPane) scene.getRoot()).getCenter() instanceof AccountPage);
                    assertEquals("unsaved guest content", editor.getText());
                }
            } finally {
                app.closeResources();
                stage.hide();
            }
            return null;
        });
    }
}
