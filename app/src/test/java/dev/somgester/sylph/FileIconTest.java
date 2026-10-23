package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TreeItem;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Paint;
import javafx.scene.shape.SVGPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FileIconTest {

    @TempDir
    Path directory;

    @BeforeAll
    static void initialize() throws Exception {
        FxTestSupport.initialize();
    }

    @ParameterizedTest
    @CsvSource({
        "Main.java, java", "data.json, json", "script.py, python", "types.PYI, python",
        "index.js, javascript", "module.mjs, javascript", "view.jsx, react", "index.ts, typescript",
        "module.mts, typescript", "view.tsx, react_ts", "README.md, markdown", "run.sh, console",
        ".bashrc, console", "main.c, c", "main.C, cpp", "header.hpp, cpp"
    })
    void recognizedFilesHaveReadableBundledIcons(String filename, String asset) throws Exception {
        FxTestSupport.onFxThread(() -> {
            ImageView icon = (ImageView) FileIcon.forPath(Path.of(filename));
            assertTrue(icon.getImage().getUrl().endsWith("/" + asset + ".png"));
            assertFalse(icon.getImage().isError());
            assertEquals(64, icon.getImage().getWidth());
            assertEquals(64, icon.getImage().getHeight());
            assertEquals(16, icon.getFitWidth());
            assertEquals(16, icon.getFitHeight());
            assertTrue(icon.isMouseTransparent());
            // Catch a missing/blank export even if JavaFX can decode the PNG.
            boolean visible = false;
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    visible |= icon.getImage().getPixelReader().getColor(x, y).getOpacity() > 0;
                }
            }
            assertTrue(visible);
            return null;
        });
    }

    @Test
    void nodesAreIndependentWhileImagesAreShared() throws Exception {
        FxTestSupport.onFxThread(() -> {
            ImageView first = (ImageView) FileIcon.forPath(Path.of("one.py"));
            ImageView second = (ImageView) FileIcon.forPath(Path.of("two.py"));
            assertNotSame(first, second);
            assertSame(first.getImage(), second.getImage());
            StackPane row = new StackPane(first, second);
            assertSame(row, first.getParent());
            assertSame(row, second.getParent());
            return null;
        });
    }

    @Test
    void unknownAndUntitledIconsFollowBothThemes() throws Exception {
        FxTestSupport.onFxThread(() -> {
            Node unknown = FileIcon.forPath(Path.of("notes.unknown"));
            Node untitled = FileIcon.forPath(null);
            assertEquals("File", unknown.getAccessibleText());
            assertEquals("Untitled file", untitled.getAccessibleText());
            StackPane root = new StackPane(unknown, untitled, FileIcon.folder());
            root.setId("root");
            Scene scene = new Scene(root, 100, 100);
            Paint previous = null;
            for (String theme : List.of("dark", "light")) {
                scene.getStylesheets().setAll(resource("/styles/" + theme + ".css"),
                        resource("/styles/shell.css"));
                root.applyCss();
                root.layout();
                SVGPath path = (SVGPath) unknown.lookup(".action-icon");
                assertTrue(path.getStroke().isOpaque());
                if (previous != null) {
                    assertFalse(previous.equals(path.getStroke()));
                }
                previous = path.getStroke();
            }
            return null;
        });
    }

    @Test
    void reusedSidebarCellsClearOldGraphicsAndDistinguishFolders() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("folder.py"));
        Path python = Files.writeString(directory.resolve("a.py"), "pass");
        Files.writeString(directory.resolve("b.txt"), "text");
        FxTestSupport.onFxThread(() -> {
            try (Sidebar sidebar = new Sidebar()) {
                sidebar.setRootDirectory(directory);
                var tree = sidebar.getTreeView();
                var cell = tree.getCellFactory().call(tree);
                cell.updateTreeView(tree);
                cell.updateIndex(1);
                assertEquals(folder, cell.getItem());
                assertEquals("Folder", cell.getGraphic().getAccessibleText());
                cell.updateIndex(2);
                assertEquals(python, cell.getItem());
                assertTrue(cell.getGraphic() instanceof ImageView);
                cell.updateIndex(3);
                assertEquals("File", cell.getGraphic().getAccessibleText());
                cell.updateIndex(20);
                assertNull(cell.getGraphic());
                assertNull(cell.getText());
                cell.updateIndex(0);
                tree.setRoot(new TreeItem<>(null));
                assertEquals("No folder open", cell.getText());
                assertNull(cell.getGraphic());
            }
            return null;
        });
    }

    @Test
    void tabIconUpdatesOnSaveAsButRemainsStableDuringEdits() throws Exception {
        Path python = Files.writeString(directory.resolve("script.py"), "pass");
        Path javascript = directory.resolve("script.js");
        List<Throwable> errors = new ArrayList<>();
        EditorDocument document = FxTestSupport.onFxThread(() -> {
            AppSettings settings = new AppSettings(new AppSettingsTest.MemoryStore(
                    new AppSettings.Values(true, false, 1)));
            return new EditorDocument(settings);
        });
        try {
            FxTestSupport.onFxThread(() -> {
                assertEquals("Untitled file", document.tab.getGraphic().getAccessibleText());
                document.session.open(python, () -> { }, errors::add);
                return null;
            });
            FxTestSupport.await(() -> !document.session.busyProperty().get());
            FxTestSupport.onFxThread(() -> {
                assertTrue(errors.isEmpty());
                Node icon = document.tab.getGraphic();
                assertEquals("Python file", icon.getAccessibleText());
                document.session.textProperty().set("changed");
                assertSame(icon, document.tab.getGraphic());
                document.session.save(javascript, false, false, () -> { }, errors::add);
                return null;
            });
            FxTestSupport.await(() -> !document.session.busyProperty().get());
            FxTestSupport.onFxThread(() -> {
                assertTrue(errors.isEmpty());
                assertEquals("JavaScript file", document.tab.getGraphic().getAccessibleText());
                assertEquals("script.js", document.tab.getText());
                return null;
            });
            assertEquals("changed", Files.readString(javascript));
        } finally {
            FxTestSupport.onFxThread(() -> {
                document.close();
                assertFalse(document.tab.graphicProperty().isBound());
                assertNull(document.tab.getGraphic());
                return null;
            });
        }
    }

    private static String resource(String path) {
        return FileIconTest.class.getResource(path).toExternalForm();
    }
}
