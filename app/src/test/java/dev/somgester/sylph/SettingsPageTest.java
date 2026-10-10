package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TitledPane;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.PopupWindow;
import javafx.stage.Window;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SettingsPageTest {

    @BeforeAll
    static void initializeJavaFx() throws InterruptedException {
        FxTestSupport.initialize();
    }

    @Test
    void controlsApplyThemeImmediatelyAndPersistAutosaveChoices() throws Exception {
        FxTestSupport.onFxThread(() -> {
            AppSettingsTest.MemoryStore store = new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 2));
            AppSettings settings = new AppSettings(store);
            EditorApp app = new EditorApp();
            Stage stage = new Stage();
            Scene scene = app.createScene(stage, settings);
            try {
                BorderPane root = (BorderPane) scene.getRoot();
                root.applyCss();
                root.layout();
                ((Button) root.lookup("#open-settings")).fire();
                root.applyCss();
                root.layout();
                SettingsPage page = (SettingsPage) root.getCenter();
                ComboBox<?> delay = (ComboBox<?>) page.lookup("#autosave-delay");
                assertTrue(delay.isDisabled());
                ((RadioButton) page.lookup("#theme-light")).fire();
                root.applyCss();
                assertFalse(settings.darkThemeProperty().get());
                assertTrue(scene.getStylesheets().getFirst().endsWith("light.css"));
                Color background = (Color) page.getBackground().getFills().getFirst().getFill();
                assertEquals(Color.web("#faf9f5"), background);
                ((CheckBox) page.lookup("#autosave-toggle")).fire();
                delay.getSelectionModel().select(3);
                assertFalse(delay.isDisabled());
                AppSettings reloaded = new AppSettings(store);
                assertFalse(reloaded.darkThemeProperty().get());
                assertTrue(reloaded.autoSaveProperty().get());
                assertEquals(5, reloaded.delaySecondsProperty().get());
                delay.getSelectionModel().selectFirst();
                assertEquals(0, settings.delaySecondsProperty().get());
                assertEquals(0, new AppSettings(store).delaySecondsProperty().get());
                return null;
            } finally {
                app.closeResources();
                stage.hide();
            }
        });
    }

    @Test
    void backAndKeyboardNavigationKeepUnsavedEditorContent() throws Exception {
        FxTestSupport.onFxThread(() -> {
            EditorApp app = new EditorApp();
            Stage stage = new Stage();
            Scene scene = app.createScene(stage, new AppSettings(
                    new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 2))));
            try {
                BorderPane root = (BorderPane) scene.getRoot();
                root.applyCss();
                root.layout();
                EditorView editorView = (EditorView) root.getCenter();
                CodeArea editor = editorView.area();
                editor.replaceText("Keep my unsaved work\nwhile changing settings.");
                boolean mac = System.getProperty("os.name").startsWith("Mac");
                Event.fireEvent(root, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.COMMA,
                        false, !mac, false, mac));
                assertTrue(root.getCenter() instanceof SettingsPage);
                root.applyCss();
                root.layout();
                ((Button) root.lookup("#settings-back")).fire();
                assertSame(editorView, root.getCenter());
                ((Button) root.lookup("#open-settings")).fire();
                Event.fireEvent(root, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE,
                        false, false, false, false));
                assertSame(editorView, root.getCenter());
                assertEquals("Keep my unsaved work\nwhile changing settings.", editor.getText());
                assertTrue(stage.getTitle().startsWith("* "));
                EditorShortcutsTest.press(scene, KeyCode.S, false, true, true);
                ((Button) root.lookup("#open-settings")).fire();
                root.applyCss();
                assertTrue(((CheckBox) root.lookup("#autosave-toggle")).isSelected());
                return null;
            } finally {
                app.closeResources();
                stage.hide();
            }
        });
    }

    @Test
    void openAutosaveDropdownTracksBothThemesAndKeepsItsSelection() throws Exception {
        Preview preview = FxTestSupport.onFxThread(() -> {
            AppSettings settings = new AppSettings(
                    new AppSettingsTest.MemoryStore(new AppSettings.Values(true, true, 2)));
            EditorApp app = new EditorApp();
            Stage stage = new Stage();
            BorderPane root = (BorderPane) app.createScene(stage, settings).getRoot();
            stage.show();
            root.applyCss();
            root.layout();
            ((Button) root.lookup("#open-settings")).fire();
            root.applyCss();
            root.layout();
            ((ComboBox<?>) root.lookup("#autosave-delay")).show();
            return new Preview(app, stage, settings, root);
        });
        try {
            FxTestSupport.await(() -> popupList() != null);
            FxTestSupport.onFxThread(() -> {
                checkPopupColors("#151820", "#e5e7eb", "#06b6d4", "#0d0f12", "dropdown-dark");
                checkClosedDropdownColors(preview.root(), "#0d0f12", "#e5e7eb");
                preview.settings().darkThemeProperty().set(false);
                return null;
            });
            FxTestSupport.onFxThread(() -> {
                preview.root().applyCss();
                preview.root().layout();
                checkPopupColors("#ffffff", "#141413", "#a9583e", "#ffffff", "dropdown-light");
                checkClosedDropdownColors(preview.root(), "#faf9f5", "#141413");
                ComboBox<?> delay = (ComboBox<?>) preview.root().lookup("#autosave-delay");
                assertEquals(2, delay.getValue());
                delay.getSelectionModel().selectFirst();
                assertEquals(0, preview.settings().delaySecondsProperty().get());
                delay.hide();
                ((Button) preview.root().lookup("#settings-back")).fire();
                preview.settings().darkThemeProperty().set(true);
                ((Button) preview.root().lookup("#open-settings")).fire();
                preview.root().applyCss();
                preview.root().layout();
                delay.show();
                return null;
            });
            FxTestSupport.await(() -> popupList() != null);
            FxTestSupport.onFxThread(() -> {
                checkPopupColors("#151820", "#e5e7eb", "#06b6d4", "#0d0f12", "dropdown-dark-reopened");
                assertEquals(0, ((ComboBox<?>) preview.root().lookup("#autosave-delay")).getValue());
                return null;
            });
        } finally {
            FxTestSupport.onFxThread(() -> {
                ((ComboBox<?>) preview.root().lookup("#autosave-delay")).hide();
                preview.app().closeResources();
                preview.stage().hide();
                return null;
            });
        }
    }

    private static ListView<?> popupList() {
        for (Window window : Window.getWindows()) {
            if (window instanceof PopupWindow && window.isShowing()) {
                var list = window.getScene().getRoot().lookup(".list-view");
                if (list instanceof ListView<?> options) {
                    return options;
                }
            }
        }
        return null;
    }

    private static void checkClosedDropdownColors(Parent root, String background, String text) {
        ComboBox<?> delay = (ComboBox<?>) root.lookup("#autosave-delay");
        assertEquals(Color.web(background), delay.getBackground().getFills().getFirst().getFill());
        ListCell<?> displayed = (ListCell<?>) delay.lookup(".list-cell");
        assertEquals(Color.web(text), displayed.getTextFill());
    }

    private static void checkPopupColors(String surface, String text, String selectedBackground,
            String selectedText, String name) throws Exception {
        ListView<?> list = popupList();
        assertTrue(list != null, "The real dropdown popup should be open");
        Parent popup = list.getScene().getRoot();
        popup.applyCss();
        requestLayout(popup);
        popup.layout();
        assertEquals(Color.web(surface), list.getBackground().getFills().getFirst().getFill());
        int visibleRows = 0;
        for (var node : list.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && !cell.isEmpty()) {
                visibleRows++;
                assertEquals(Color.web(cell.isSelected() ? selectedBackground : surface),
                        cell.getBackground().getFills().getFirst().getFill());
                assertEquals(Color.web(cell.isSelected() ? selectedText : text), cell.getTextFill());
            }
        }
        assertEquals(5, visibleRows);
        writeSnapshot(popup, name);
    }

    @Test
    void renderBothThemesAndScrollableSmallWindow() throws Exception {
        Preview preview = FxTestSupport.onFxThread(() -> {
            AppSettings settings = new AppSettings(
                    new AppSettingsTest.MemoryStore(new AppSettings.Values(true, false, 2)));
            EditorApp app = new EditorApp();
            Stage stage = new Stage();
            Scene scene = app.createScene(stage, settings);
            BorderPane root = (BorderPane) scene.getRoot();
            root.applyCss();
            root.layout();
            ((Button) root.lookup("#open-settings")).fire();
            return new Preview(app, stage, settings, root);
        });
        try {
            FxTestSupport.onFxThread(() -> {
                render(preview.root(), "dark", 1100, 700);
                preview.settings().darkThemeProperty().set(false);
                preview.settings().autoSaveProperty().set(true);
                preview.settings().delaySecondsProperty().set(0);
                return null;
            });
            FxTestSupport.onFxThread(() -> {
                render(preview.root(), "light", 1100, 700);
                SettingsPage page = (SettingsPage) preview.root().getCenter();
                assertTrue(page.lookup("#autosave-delay").localToScene(
                        page.lookup("#autosave-delay").getBoundsInLocal()).getMaxY() < 665);
                assertTrue(page.getContent().getLayoutBounds().getHeight() <= page.getViewportBounds().getHeight() + 1);
                return null;
            });
            FxTestSupport.onFxThread(() -> {
                SettingsPage page = (SettingsPage) preview.root().getCenter();
                TitledPane shortcuts = (TitledPane) page.lookup("#settings-keybindings");
                shortcuts.setExpanded(true);
                preview.root().applyCss();
                requestLayout(preview.root());
                preview.root().layout();
                page.setVvalue(1);
                render(preview.root(), "keybindings", 1100, 700);
                assertEquals(8, shortcuts.lookupAll(".shortcut-key").size());
                assertTrue(shortcuts.getContent().localToScene(
                        shortcuts.getContent().getBoundsInLocal()).getMaxY() < 665);
                return null;
            });
            FxTestSupport.onFxThread(() -> {
                SettingsPage currentPage = (SettingsPage) preview.root().getCenter();
                ((TitledPane) currentPage.lookup("#settings-keybindings")).setExpanded(false);
                currentPage.setVvalue(0);
                render(preview.root(), "small", 640, 420);
                SettingsPage page = (SettingsPage) preview.root().getCenter();
                assertTrue(page.getContent().getLayoutBounds().getHeight() > page.getViewportBounds().getHeight());
                assertTrue(page.getContent().getLayoutBounds().getWidth() <= page.getViewportBounds().getWidth() + 1);
                return null;
            });
        } finally {
            FxTestSupport.onFxThread(() -> {
                preview.app().closeResources();
                preview.stage().hide();
                return null;
            });
        }
    }

    private record Preview(EditorApp app, Stage stage, AppSettings settings, BorderPane root) { }

    private static void render(BorderPane root, String name, int width, int height) throws Exception {
        root.resize(width, height);
        root.applyCss();
        requestLayout(root);
        root.layout();
        writeSnapshot(root, name);
    }

    private static void writeSnapshot(Parent root, String name) throws Exception {
        WritableImage snapshot = root.snapshot(null, null);
        int imageWidth = (int) snapshot.getWidth();
        int imageHeight = (int) snapshot.getHeight();
        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < imageHeight; y++) {
            for (int x = 0; x < imageWidth; x++) {
                image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
            }
        }
        Path output = Path.of("build", "reports", "settings-preview", name + ".png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }

    private static void requestLayout(Parent parent) {
        for (var child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Parent nested) {
                requestLayout(nested);
            }
        }
        parent.requestLayout();
    }
}
