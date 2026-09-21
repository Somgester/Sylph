package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SidebarTest {

    @TempDir
    Path tempDir;

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
    void initialStateHasNoRootDirectory() throws Exception {
        Sidebar sidebar = onFxThread(Sidebar::new);
        assertNull(onFxThread(sidebar::getRootDirectory));
        assertNotNull(onFxThread(() -> sidebar.getTreeView()));
        assertNotNull(onFxThread(() -> sidebar.getTreeView().getRoot()));
    }

    @Test
    void setRootDirectoryListsAllEntriesSortedDirsFirst() throws Exception {
        Files.createDirectory(tempDir.resolve("zebra"));
        Files.createDirectory(tempDir.resolve("alpha"));
        Files.writeString(tempDir.resolve("readme.md"), "hi");
        Files.writeString(tempDir.resolve("Build.Gradle"), "hi");

        Sidebar sidebar = onFxThread(Sidebar::new);
        onFxThread(() -> {
            sidebar.setRootDirectory(tempDir);
            return null;
        });

        List<String> names = onFxThread(() -> {
            return sidebar.getTreeView().getRoot().getChildren().stream()
                    .map(item -> Sidebar.displayName(item.getValue()))
                    .toList();
        });

        assertEquals(List.of("alpha", "zebra", "Build.Gradle", "readme.md"), names);
    }

    @Test
    void expandingSubdirectoryLoadsChildrenLazily() throws Exception {
        Path sub = Files.createDirectory(tempDir.resolve("sub"));
        Files.writeString(sub.resolve("inner.txt"), "hi");

        Sidebar sidebar = onFxThread(Sidebar::new);
        onFxThread(() -> {
            sidebar.setRootDirectory(tempDir);
            return null;
        });

        onFxThread(() -> {
            TreeItem<Path> root = sidebar.getTreeView().getRoot();
            TreeItem<Path> subItem = root.getChildren().stream()
                    .filter(item -> item.getValue().endsWith("sub"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(1, subItem.getChildren().size());
            subItem.setExpanded(true);
            return null;
        });
        assertTrue(waitForChild(sidebar, "sub", "inner.txt"),
                "Timed out waiting for async child load");
    }

    @Test
    void asyncRootLoadPopulatesChildren() throws Exception {
        Files.createDirectory(tempDir.resolve("alpha"));
        Files.writeString(tempDir.resolve("readme.md"), "hi");

        Sidebar sidebar = onFxThread(Sidebar::new);
        onFxThread(() -> {
            sidebar.setRootDirectoryAsync(tempDir);
            return null;
        });
        assertEquals(tempDir.toAbsolutePath().normalize(), onFxThread(sidebar::getRootDirectory));
        long deadline = System.currentTimeMillis() + 5000L;
        List<String> names = List.of();
        while (System.currentTimeMillis() < deadline) {
            names = onFxThread(() -> {
                return sidebar.getTreeView().getRoot().getChildren().stream()
                        .map(item -> Sidebar.displayName(item.getValue()))
                        .toList();
            });
            if (names.contains("alpha") && names.contains("readme.md")) {
                break;
            }
            Thread.sleep(100L);
        }
        assertEquals(List.of("alpha", "readme.md"), names);
    }

    @Test
    void setRootDirectoryRejectsFiles() throws Exception {
        Path file = Files.writeString(tempDir.resolve("file.txt"), "hi");
        Sidebar sidebar = onFxThread(Sidebar::new);
        onFxThread(() -> {
            assertThrows(IllegalArgumentException.class, () -> sidebar.setRootDirectory(file));
            assertThrows(NullPointerException.class, () -> sidebar.setRootDirectory((Path) null));
            return null;
        });
    }

    @Test
    void openFolderUpdatesSidebarAndStatus() throws Exception {
        Files.createDirectory(tempDir.resolve("mydir"));
        Sidebar sidebar = onFxThread(Sidebar::new);
        Label status = onFxThread(() -> new Label("Ready"));
        EditorApp app = new EditorApp();
        onFxThread(() -> {
            app.openFolder(tempDir, sidebar, status);
            return null;
        });
        assertEquals(tempDir.toAbsolutePath().normalize(), onFxThread(sidebar::getRootDirectory));
        assertEquals("Opened  |  " + tempDir.getFileName().toString(), onFxThread(status::getText));
    }

    @Test
    void openFolderButtonHasExpectedIdAndAction() throws Exception {
        EditorApp app = new EditorApp();
        AtomicInteger calls = new AtomicInteger(0);
        Button button = onFxThread(() -> {
            Method method = EditorApp.class.getDeclaredMethod("createOpenFolderButton", Runnable.class);
            method.setAccessible(true);
            return (Button) method.invoke(app, (Runnable) calls::incrementAndGet);
        });
        assertEquals("Open Folder", button.getText());
        assertEquals("open-folder", button.getId());
        assertTrue(button.getStyleClass().contains("theme-toggle"));
        onFxThread(() -> {
            button.fire();
            return null;
        });
        assertEquals(1, calls.get());
    }

    @Test
    void ctrlOShortcutTriggersOpenAction() throws Exception {
        EditorApp app = new EditorApp();
        AtomicInteger calls = new AtomicInteger(0);
        Scene scene = onFxThread(() -> new Scene(new BorderPane(), 800, 600));
        onFxThread(() -> {
            invokeRegisterShortcuts(app, scene, calls::incrementAndGet);
            return null;
        });
        onFxThread(() -> {
            KeyEvent event = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.O,
                    false, true, false, false);
            scene.getRoot().fireEvent(event);
            return null;
        });
        assertEquals(1, calls.get());
    }

    @Test
    void ctrlKThenCtrlOChordTriggersOpenActionOnce() throws Exception {
        EditorApp app = new EditorApp();
        AtomicInteger calls = new AtomicInteger(0);
        Scene scene = onFxThread(() -> new Scene(new BorderPane(), 800, 600));
        onFxThread(() -> {
            invokeRegisterShortcuts(app, scene, calls::incrementAndGet);
            return null;
        });
        onFxThread(() -> {
            KeyEvent chordStart = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.K,
                    false, true, false, false);
            scene.getRoot().fireEvent(chordStart);
            KeyCombination openCombo = new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN);
            assertTrue(openCombo.match(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.O,
                    false, true, false, false)));
            KeyEvent chordEnd = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.O,
                    false, true, false, false);
            scene.getRoot().fireEvent(chordEnd);
            return null;
        });
        assertEquals(1, calls.get());
    }

    @Test
    void ctrlKAloneDoesNotTriggerOpenAction() throws Exception {
        EditorApp app = new EditorApp();
        AtomicInteger calls = new AtomicInteger(0);
        Scene scene = onFxThread(() -> new Scene(new BorderPane(), 800, 600));
        onFxThread(() -> {
            invokeRegisterShortcuts(app, scene, calls::incrementAndGet);
            return null;
        });
        onFxThread(() -> {
            KeyEvent chordStart = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.K,
                    false, true, false, false);
            scene.getRoot().fireEvent(chordStart);
            return null;
        });
        assertEquals(0, calls.get());
    }

    @Test
    void listChildrenIsEmptyForEmptyDirectory() throws Exception {
        List<Path> children = Sidebar.listChildren(tempDir);
        assertTrue(children.isEmpty());
    }

    @Test
    void sidebarStartsExpandedWithDefaultWidth() throws Exception {
        Sidebar sidebar = onFxThread(Sidebar::new);
        assertFalse(onFxThread(sidebar::isCollapsed));
        assertEquals(Sidebar.DEFAULT_WIDTH, onFxThread(sidebar::getSidebarWidth));
        assertTrue(onFxThread(sidebar::isVisible));
        assertTrue(onFxThread(sidebar::isManaged));
    }

    @Test
    void collapseHidesAndRestoreShows() throws Exception {
        Sidebar sidebar = onFxThread(Sidebar::new);
        onFxThread(() -> {
            sidebar.setCollapsed(true);
            return null;
        });
        assertTrue(onFxThread(sidebar::isCollapsed));
        assertFalse(onFxThread(sidebar::isVisible));
        assertFalse(onFxThread(sidebar::isManaged));
        onFxThread(() -> {
            sidebar.setCollapsed(false);
            return null;
        });
        assertFalse(onFxThread(sidebar::isCollapsed));
        assertTrue(onFxThread(sidebar::isVisible));
        assertTrue(onFxThread(sidebar::isManaged));
        assertEquals(Sidebar.DEFAULT_WIDTH, onFxThread(sidebar::getSidebarWidth));
    }

    @Test
    void collapsedSidebarFullyHides() throws Exception {
        Sidebar sidebar = onFxThread(Sidebar::new);
        onFxThread(() -> {
            sidebar.setCollapsed(true);
            return null;
        });
        assertFalse(onFxThread(sidebar::isVisible));
        assertFalse(onFxThread(sidebar::isManaged));
        onFxThread(() -> {
            sidebar.toggleCollapsed();
            return null;
        });
        assertFalse(onFxThread(sidebar::isCollapsed));
        assertTrue(onFxThread(sidebar::isVisible));
        assertTrue(onFxThread(sidebar::isManaged));
    }

    @Test
    void toggleCollapsedFlipsState() throws Exception {
        Sidebar sidebar = onFxThread(Sidebar::new);
        onFxThread(() -> {
            sidebar.toggleCollapsed();
            return null;
        });
        assertTrue(onFxThread(sidebar::isCollapsed));
        onFxThread(() -> {
            sidebar.toggleCollapsed();
            return null;
        });
        assertFalse(onFxThread(sidebar::isCollapsed));
    }

    @Test
    void setSidebarWidthClampsToBounds() throws Exception {
        Sidebar sidebar = onFxThread(Sidebar::new);
        onFxThread(() -> {
            sidebar.setSidebarWidth(300.0);
            return null;
        });
        assertEquals(300.0, onFxThread(sidebar::getSidebarWidth));
        onFxThread(() -> {
            sidebar.setSidebarWidth(1.0);
            return null;
        });
        assertEquals(Sidebar.MIN_WIDTH, onFxThread(sidebar::getSidebarWidth));
        onFxThread(() -> {
            sidebar.setSidebarWidth(5000.0);
            return null;
        });
        assertEquals(Sidebar.MAX_WIDTH, onFxThread(sidebar::getSidebarWidth));
    }

    @Test
    void setSidebarWidthWhileCollapsedRestoresOnExpand() throws Exception {
        Sidebar sidebar = onFxThread(Sidebar::new);
        onFxThread(() -> {
            sidebar.setCollapsed(true);
            sidebar.setSidebarWidth(300.0);
            sidebar.setCollapsed(false);
            return null;
        });
        assertEquals(300.0, onFxThread(sidebar::getSidebarWidth));
    }

    @Test
    void clampWidthRespectsBounds() {
        assertEquals(Sidebar.MIN_WIDTH, Sidebar.clampWidth(1.0));
        assertEquals(Sidebar.MAX_WIDTH, Sidebar.clampWidth(5000.0));
        assertEquals(250.0, Sidebar.clampWidth(250.0));
    }

    @Test
    void dragHandleIsConfiguredForResize() throws Exception {
        Sidebar sidebar = onFxThread(Sidebar::new);
        String handleId = onFxThread(() -> sidebar.getDragHandle().getId());
        assertEquals("sidebar-drag-handle", handleId);
        double handleWidth = onFxThread(() -> sidebar.getDragHandle().getPrefWidth());
        assertEquals(Sidebar.DRAG_HANDLE_WIDTH, handleWidth);
        Cursor cursor = onFxThread(() -> sidebar.getDragHandle().getCursor());
        assertEquals(Cursor.H_RESIZE, cursor);
    }

    @Test
    void dragHandleDoesNotCoverTreeView() throws Exception {
        Sidebar sidebar = onFxThread(Sidebar::new);
        double maxWidth = onFxThread(() -> sidebar.getDragHandle().getMaxWidth());
        double minWidth = onFxThread(() -> sidebar.getDragHandle().getMinWidth());
        assertEquals(Sidebar.DRAG_HANDLE_WIDTH, maxWidth);
        assertEquals(Sidebar.DRAG_HANDLE_WIDTH, minWidth);
        assertTrue(onFxThread(() -> sidebar.getTreeView().isVisible()));
        assertFalse(onFxThread(() -> sidebar.getTreeView().isDisabled()));
    }

    @Test
    void sidebarToggleButtonHasExpectedIdAndAction() throws Exception {
        EditorApp app = new EditorApp();
        AtomicInteger calls = new AtomicInteger(0);
        Button button = onFxThread(() -> {
            Method method = EditorApp.class.getDeclaredMethod("createSidebarToggleButton", Runnable.class);
            method.setAccessible(true);
            return (Button) method.invoke(app, (Runnable) calls::incrementAndGet);
        });
        assertEquals("Sidebar", button.getText());
        assertEquals("toggle-sidebar", button.getId());
        onFxThread(() -> {
            button.fire();
            return null;
        });
        assertEquals(1, calls.get());
    }

    @Test
    void ctrlBShortcutTriggersToggleAction() throws Exception {
        EditorApp app = new EditorApp();
        AtomicInteger calls = new AtomicInteger(0);
        Scene scene = onFxThread(() -> new Scene(new BorderPane(), 800, 600));
        onFxThread(() -> {
            invokeRegisterSidebarShortcut(app, scene, calls::incrementAndGet);
            return null;
        });
        onFxThread(() -> {
            KeyEvent event = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.B,
                    false, true, false, false);
            scene.getRoot().fireEvent(event);
            return null;
        });
        assertEquals(1, calls.get());
    }

    private static void invokeRegisterShortcuts(EditorApp app, Scene scene, Runnable action) throws Exception {
        Method method = EditorApp.class.getDeclaredMethod("registerOpenFolderShortcuts",
                Scene.class, Runnable.class);
        method.setAccessible(true);
        method.invoke(app, scene, action);
    }

    private static void invokeRegisterSidebarShortcut(EditorApp app, Scene scene, Runnable action)
            throws Exception {
        Method method = EditorApp.class.getDeclaredMethod("registerSidebarToggleShortcut",
                Scene.class, Runnable.class);
        method.setAccessible(true);
        method.invoke(app, scene, action);
    }

    private static <T> T onFxThread(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        Platform.runLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }

    private static boolean waitForChild(Sidebar sidebar, String parentName, String childName)
            throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            boolean loaded = onFxThread(() -> {
                TreeItem<Path> root = sidebar.getTreeView().getRoot();
                return root.getChildren().stream()
                        .filter(item -> item.getValue().endsWith(parentName))
                        .findFirst()
                        .map(parent -> parent.getChildren().stream()
                                .anyMatch(child -> child.getValue() != null
                                        && child.getValue().endsWith(childName)))
                        .orElse(false);
            });
            if (loaded) {
                return true;
            }
            Thread.sleep(100L);
        }
        return false;
    }
}
