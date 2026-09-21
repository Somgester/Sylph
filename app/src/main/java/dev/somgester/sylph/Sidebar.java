package dev.somgester.sylph;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class Sidebar extends VBox {

    static final double DEFAULT_WIDTH = 210.0;

    static final double MIN_WIDTH = 120.0;

    static final double MAX_WIDTH = 500.0;

    static final double DRAG_HANDLE_WIDTH = 6.0;

    private final TreeView<Path> treeView;

    private final Region dragHandle;

    private Path rootDirectory;

    private boolean collapsed;

    private double expandedWidth;

    private double dragStartX;

    private double dragStartWidth;

    private long loadGeneration;

    @SuppressWarnings("this-escape")
    public Sidebar() {
        setId("sidebar");
        setPadding(new Insets(14, 0, 0, 0));
        setPrefWidth(DEFAULT_WIDTH);
        setMinWidth(MIN_WIDTH);
        setMaxWidth(MAX_WIDTH);
        expandedWidth = DEFAULT_WIDTH;
        collapsed = false;
        treeView = new TreeView<>();
        treeView.setShowRoot(true);
        treeView.setPrefWidth(DEFAULT_WIDTH);
        treeView.setCellFactory(param -> new TreeCell<Path>() {
            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setText(null);
                } else if (item == null) {
                    if (getTreeItem() != null && getTreeItem() == getTreeView().getRoot()) {
                        setText("No folder open");
                    } else {
                        setText(null);
                    }
                } else {
                    setText(displayName(item));
                }
            }
        });
        TreeItem<Path> placeholder = new TreeItem<>(null);
        placeholder.setExpanded(true);
        treeView.setRoot(placeholder);
        StackPane content = new StackPane(treeView);
        VBox.setVgrow(content, Priority.ALWAYS);
        dragHandle = new Region();
        dragHandle.setId("sidebar-drag-handle");
        dragHandle.setPrefWidth(DRAG_HANDLE_WIDTH);
        dragHandle.setMinWidth(DRAG_HANDLE_WIDTH);
        dragHandle.setMaxWidth(DRAG_HANDLE_WIDTH);
        dragHandle.setMaxHeight(Double.MAX_VALUE);
        dragHandle.setCursor(Cursor.H_RESIZE);
        StackPane.setAlignment(dragHandle, Pos.CENTER_RIGHT);
        Tooltip.install(dragHandle, new Tooltip("Drag to resize - Double-click to collapse"));
        dragHandle.setOnMousePressed(event -> {
            dragStartX = event.getSceneX();
            dragStartWidth = getPrefWidth();
            event.consume();
        });
        dragHandle.setOnMouseDragged(event -> {
            double delta = event.getSceneX() - dragStartX;
            setSidebarWidth(dragStartWidth + delta);
            event.consume();
        });
        dragHandle.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                toggleCollapsed();
                event.consume();
            }
        });
        content.getChildren().add(dragHandle);
        getChildren().add(content);
    }

    public Region getDragHandle() {
        return dragHandle;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void setCollapsed(boolean shouldCollapse) {
        if (this.collapsed == shouldCollapse) {
            return;
        }
        this.collapsed = shouldCollapse;
        if (shouldCollapse) {
            expandedWidth = getPrefWidth();
            setVisible(false);
            setManaged(false);
        } else {
            setVisible(true);
            setManaged(true);
            setPrefWidth(expandedWidth);
        }
    }

    public void toggleCollapsed() {
        setCollapsed(!collapsed);
    }

    public double getSidebarWidth() {
        return getPrefWidth();
    }

    public void setSidebarWidth(double width) {
        double clamped = clampWidth(width);
        if (isCollapsed()) {
            expandedWidth = clamped;
        } else {
            setPrefWidth(clamped);
            expandedWidth = clamped;
        }
    }

    public TreeView<Path> getTreeView() {
        return treeView;
    }

    public Path getRootDirectory() {
        return rootDirectory;
    }

    public void setRootDirectory(File directory) {
        Objects.requireNonNull(directory, "directory");
        setRootDirectory(directory.toPath());
    }

    public void setRootDirectory(Path directory) {
        Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Not a directory: " + directory);
        }
        loadGeneration++;
        this.rootDirectory = directory.toAbsolutePath().normalize();
        TreeItem<Path> root = createNode(this.rootDirectory);
        loadChildren(root);
        root.setExpanded(true);
        treeView.setRoot(root);
    }

    public void setRootDirectoryAsync(File directory) {
        Objects.requireNonNull(directory, "directory");
        setRootDirectoryAsync(directory.toPath());
    }

    public void setRootDirectoryAsync(Path directory) {
        Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Not a directory: " + directory);
        }
        Path normalized = directory.toAbsolutePath().normalize();
        loadGeneration++;
        long generation = loadGeneration;
        this.rootDirectory = normalized;
        TreeItem<Path> loadingRoot = new TreeItem<>(normalized);
        loadingRoot.setExpanded(true);
        treeView.setRoot(loadingRoot);
        Task<List<Path>> task = new Task<>() {
            @Override
            protected List<Path> call() throws Exception {
                return listChildren(normalized);
            }
        };
        task.setOnSucceeded(event -> {
            if (generation != loadGeneration || !normalized.equals(rootDirectory)) {
                return;
            }
            TreeItem<Path> root = new TreeItem<>(normalized);
            for (Path child : task.getValue()) {
                root.getChildren().add(createNode(child));
            }
            root.setExpanded(true);
            treeView.setRoot(root);
        });
        task.setOnFailed(event -> {
            if (generation != loadGeneration || !normalized.equals(rootDirectory)) {
                return;
            }
            TreeItem<Path> emptyRoot = new TreeItem<>(normalized);
            emptyRoot.setExpanded(true);
            treeView.setRoot(emptyRoot);
        });
        Thread loader = new Thread(task, "sylph-sidebar-loader");
        loader.setDaemon(true);
        loader.start();
    }

    static TreeItem<Path> createNode(Path path) {
        Objects.requireNonNull(path, "path");
        TreeItem<Path> item = new TreeItem<>(path);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            item.getChildren().add(new TreeItem<>(null));
            item.expandedProperty().addListener((observable, oldValue, newValue) -> {
                if (Boolean.TRUE.equals(newValue)) {
                    loadChildrenAsync(item);
                }
            });
        }
        return item;
    }

    static void loadChildren(TreeItem<Path> item) {
        Path dir = item.getValue();
        if (dir == null) {
            return;
        }
        boolean isDirectory;
        try {
            isDirectory = Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS);
        } catch (SecurityException ex) {
            return;
        }
        if (!isDirectory) {
            return;
        }
        ObservableList<TreeItem<Path>> children = item.getChildren();
        if (!(children.size() == 1 && children.get(0).getValue() == null)) {
            return;
        }
        List<Path> listed;
        try {
            listed = listChildren(dir);
        } catch (IOException | SecurityException ex) {
            return;
        }
        if (!(item.getChildren().size() == 1 && item.getChildren().get(0).getValue() == null)) {
            return;
        }
        item.getChildren().clear();
        for (Path child : listed) {
            item.getChildren().add(createNode(child));
        }
    }

    static void loadChildrenAsync(TreeItem<Path> item) {
        Path dir = item.getValue();
        if (dir == null) {
            return;
        }
        boolean isDirectory;
        try {
            isDirectory = Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS);
        } catch (SecurityException ex) {
            return;
        }
        if (!isDirectory) {
            return;
        }
        if (!(item.getChildren().size() == 1 && item.getChildren().get(0).getValue() == null)) {
            return;
        }
        Task<List<Path>> task = new Task<>() {
            @Override
            protected List<Path> call() throws Exception {
                return listChildren(dir);
            }
        };
        task.setOnSucceeded(event -> {
            if (!(item.getChildren().size() == 1 && item.getChildren().get(0).getValue() == null)) {
                return;
            }
            item.getChildren().clear();
            for (Path child : task.getValue()) {
                item.getChildren().add(createNode(child));
            }
        });
        task.setOnFailed(event -> {
            // Keep the placeholder so the user can retry by collapsing and expanding.
        });
        Thread loader = new Thread(task, "sylph-sidebar-loader");
        loader.setDaemon(true);
        loader.start();
    }

    static List<Path> listChildren(Path dir) throws IOException {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                result.add(child);
            }
        }
        Map<Path, Boolean> directoryFlags = new HashMap<>();
        for (Path child : result) {
            directoryFlags.put(child, Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS));
        }
        result.sort(Comparator.comparing((Path path) -> !directoryFlags.getOrDefault(path, false))
                .thenComparing(Sidebar::displayName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    static double clampWidth(double width) {
        return Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, width));
    }

    static String displayName(Path path) {
        if (path == null) {
            return "";
        }
        Path fileName = path.getFileName();
        if (fileName == null) {
            return path.toString();
        }
        return fileName.toString();
    }
}
