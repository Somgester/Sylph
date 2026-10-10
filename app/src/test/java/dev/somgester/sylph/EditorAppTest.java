package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
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
    void applyThemeUsesDarkStylesheetWhenRequested() throws Exception {
        EditorApp app = new EditorApp();
        String stylesheet = onFxThread(() -> {
            Scene scene = new Scene(new BorderPane());
            invokeApplyTheme(app, scene, true);
            assertEquals(4, scene.getStylesheets().size());
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
            assertEquals(4, scene.getStylesheets().size());
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
