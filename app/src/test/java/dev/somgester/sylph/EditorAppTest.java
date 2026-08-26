package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EditorAppTest {

    @BeforeAll
    static void initJavaFxToolkit() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyStarted) {
            latch.countDown();
        }
        boolean initialized = latch.await(5, TimeUnit.SECONDS);
        assertTrue(initialized, "Timed out while initializing JavaFX toolkit");
    }

    @Test
    void createThemeButtonSetsLabelGroupSelectionAndPadding() throws Exception {
        EditorApp app = new EditorApp();
        ToggleGroup toggleGroup = onFxThread(ToggleGroup::new);

        ToggleButton button = onFxThread(() -> {
            Method method = EditorApp.class.getDeclaredMethod(
                    "createThemeButton", String.class, ToggleGroup.class, boolean.class);
            method.setAccessible(true);
            return (ToggleButton) method.invoke(app, "Dark", toggleGroup, true);
        });

        assertEquals("Dark", button.getText());
        assertSame(toggleGroup, button.getToggleGroup());
        assertTrue(button.isSelected());

        Insets padding = button.getPadding();
        assertEquals(7.0, padding.getTop());
        assertEquals(12.0, padding.getRight());
        assertEquals(7.0, padding.getBottom());
        assertEquals(12.0, padding.getLeft());
    }

    @Test
    void applyThemeUsesDarkStylesheetWhenRequested() throws Exception {
        EditorApp app = new EditorApp();
        String stylesheet = onFxThread(() -> {
            Scene scene = new Scene(new BorderPane());
            invokeApplyTheme(app, scene, true);
            assertEquals(1, scene.getStylesheets().size());
            return scene.getStylesheets().getFirst();
        });

        assertNotNull(stylesheet);
        assertTrue(stylesheet.contains("/styles/dark.css"));
    }

    @Test
    void applyThemeUsesLightStylesheetWhenRequested() throws Exception {
        EditorApp app = new EditorApp();
        String stylesheet = onFxThread(() -> {
            Scene scene = new Scene(new BorderPane());
            invokeApplyTheme(app, scene, false);
            assertEquals(1, scene.getStylesheets().size());
            return scene.getStylesheets().getFirst();
        });

        assertNotNull(stylesheet);
        assertTrue(stylesheet.contains("/styles/light.css"));
        assertFalse(stylesheet.contains("/styles/dark.css"));
    }

    private static void invokeApplyTheme(EditorApp app, Scene scene, boolean dark) throws Exception {
        Method method = EditorApp.class.getDeclaredMethod("applyTheme", Scene.class, boolean.class);
        method.setAccessible(true);
        method.invoke(app, scene, dark);
    }

    private static <T> T onFxThread(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        Platform.runLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }
}
