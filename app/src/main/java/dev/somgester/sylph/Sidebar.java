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
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class Sidebar extends VBox implements AutoCloseable {

    enum WorkspaceAction { NEW_FILE, NEW_FOLDER, DELETE }

    private BiConsumer<WorkspaceAction, Path> onWorkspaceAction = (action, path) -> { };

    private final BooleanProperty workspaceOpen = new SimpleBooleanProperty();

    private final Map<Path, Long> refreshRevisions = new HashMap<>();

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

    private Consumer<Path> onOpenFile = path -> { };

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
            {
                ContextMenu menu = workspaceMenu(() -> getItem(), true);
                setOnContextMenuRequested(event -> {
                    if (!isEmpty() && getItem() != null) {
                        getTreeView().getSelectionModel().select(getTreeItem());
                    }
                });
                emptyProperty().addListener((observable, previous, empty) -> setContextMenu(empty ? null : menu));
                setContextMenu(menu);
                setOnMouseClicked(event -> {
                    if (!isEmpty() && event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1) {
                        openFile(getItem());
                    }
                });
            }

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
        treeView.setOnKeyPressed(event -> {
            TreeItem<Path> selected = treeView.getSelectionModel().getSelectedItem();
            if (event.getCode() == KeyCode.ENTER && selected != null && openFile(selected.getValue())) {
                event.consume();
            } else if (event.getCode() == KeyCode.DELETE && selected != null && selected.getValue() != null
                    && !selected.getValue().equals(rootDirectory)) {
                onWorkspaceAction.accept(WorkspaceAction.DELETE, selected.getValue());
                event.consume();
            }
        });
        treeView.setContextMenu(workspaceMenu(() -> rootDirectory, false));
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
        Label explorer = new Label("Explorer");
        explorer.setMinWidth(0);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(4, explorer, spacer,
                createButton(WorkspaceAction.NEW_FILE, AppIcon.Symbol.FILE, "new-workspace-file", "New File"),
                createButton(WorkspaceAction.NEW_FOLDER, AppIcon.Symbol.FOLDER, "new-workspace-folder", "New Folder"));
        header.setId("workspace-actions");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 8, 6, 10));
        getChildren().addAll(header, content);
    }

    void setOnWorkspaceAction(BiConsumer<WorkspaceAction, Path> handler) {
        onWorkspaceAction = Objects.requireNonNull(handler, "handler");
    }

    private Path selectedPath() {
        TreeItem<Path> selected = treeView.getSelectionModel().getSelectedItem();
        return selected == null || selected.getValue() == null ? rootDirectory : selected.getValue();
    }

    private Button createButton(WorkspaceAction action, AppIcon.Symbol symbol, String id, String label) {
        Button button = new Button();
        button.setId(id);
        button.setGraphic(AppIcon.symbol(symbol));
        button.getStyleClass().add("chrome-action");
        button.setAccessibleText(label);
        button.setTooltip(new Tooltip(label));
        button.disableProperty().bind(workspaceOpen.not().or(treeView.disabledProperty()));
        button.setOnAction(event -> onWorkspaceAction.accept(action, selectedPath()));
        return button;
    }

    private ContextMenu workspaceMenu(java.util.function.Supplier<Path> target, boolean includeDelete) {
        ContextMenu menu = new ContextMenu();
        MenuItem file = new MenuItem("New File…");
        MenuItem folder = new MenuItem("New Folder…");
        MenuItem delete = new MenuItem("Delete…");
        file.setOnAction(event -> onWorkspaceAction.accept(WorkspaceAction.NEW_FILE, (Path) menu.getUserData()));
        folder.setOnAction(event -> onWorkspaceAction.accept(WorkspaceAction.NEW_FOLDER, (Path) menu.getUserData()));
        delete.setOnAction(event -> onWorkspaceAction.accept(WorkspaceAction.DELETE, (Path) menu.getUserData()));
        menu.getItems().addAll(file, folder);
        if (includeDelete) {
            menu.getItems().add(delete);
        }
        menu.setOnShowing(event -> {
            Path path = target.get();
            menu.setUserData(path);
            boolean unavailable = path == null || rootDirectory == null || treeView.isDisabled();
            file.setDisable(unavailable);
            folder.setDisable(unavailable);
            delete.setDisable(unavailable || path.equals(rootDirectory));
        });
        return menu;
    }

    void forgetEntry(Path path) {
        TreeItem<Path> entry = findNode(treeView.getRoot(), path);
        if (entry != null && entry.getParent() != null) {
            entry.getParent().getChildren().remove(entry);
        }
    }

    void refreshDirectory(Path directory, Path selection, Consumer<Throwable> onError) {
        TreeItem<Path> parent = findNode(treeView.getRoot(), directory);
        if (parent == null) {
            return;
        }
        long rootGeneration = loadGeneration;
        long revision = refreshRevisions.merge(directory, 1L, Long::sum);
        Task<List<Path>> task = new Task<>() {
            @Override
            protected List<Path> call() throws IOException {
                return listChildren(directory);
            }
        };
        task.setOnSucceeded(event -> {
            if (loadGeneration != rootGeneration || !Objects.equals(refreshRevisions.get(directory), revision)
                    || findNode(treeView.getRoot(), directory) != parent) {
                return;
            }
            Map<Path, TreeItem<Path>> existing = new HashMap<>();
            for (TreeItem<Path> child : parent.getChildren()) {
                if (child.getValue() != null) {
                    existing.put(child.getValue(), child);
                }
            }
            List<TreeItem<Path>> children = task.getValue().stream()
                    .map(path -> existing.containsKey(path) ? existing.get(path) : createNode(path)).toList();
            parent.getChildren().setAll(children);
            parent.setExpanded(true);
            TreeItem<Path> selected = findNode(parent, selection);
            if (selected != null) {
                treeView.getSelectionModel().select(selected);
                treeView.scrollTo(treeView.getRow(selected));
            }
        });
        task.setOnFailed(event -> {
            if (loadGeneration == rootGeneration && Objects.equals(refreshRevisions.get(directory), revision)
                    && findNode(treeView.getRoot(), directory) == parent) {
                onError.accept(task.getException());
            }
        });
        Thread loader = new Thread(task, "sylph-sidebar-refresh");
        loader.setDaemon(true);
        loader.start();
    }

    private static TreeItem<Path> findNode(TreeItem<Path> item, Path path) {
        if (Objects.equals(item.getValue(), path)) {
            return item;
        }
        for (TreeItem<Path> child : item.getChildren()) {
            if (child.getValue() != null) {
                TreeItem<Path> found = findNode(child, path);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Override
    public void close() {
        loadGeneration++;
        refreshRevisions.clear();
        workspaceOpen.set(false);
        onOpenFile = path -> { };
        onWorkspaceAction = (action, path) -> { };
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

    public void setOnOpenFile(Consumer<Path> handler) {
        onOpenFile = Objects.requireNonNull(handler, "handler");
    }

    private boolean openFile(Path path) {
        if (path == null || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        onOpenFile.accept(path);
        return true;
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
        refreshRevisions.clear();
        workspaceOpen.set(true);
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
        refreshRevisions.clear();
        workspaceOpen.set(true);
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
            if (generation != loadGeneration || !normalized.equals(rootDirectory)
                    || refreshRevisions.containsKey(normalized)) {
                return;
            }
            loadingRoot.getChildren().setAll(task.getValue().stream().map(Sidebar::createNode).toList());
        });
        task.setOnFailed(event -> {
            // A workspace refresh may already have superseded the initial listing.
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
