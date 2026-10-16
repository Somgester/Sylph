package dev.somgester.sylph;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.function.Consumer;
import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;

public class EditorApp extends Application {

    private EditorSession session;

    private AutoSaveController autoSave;

    private BorderPane root;

    private SettingsPage settingsPage;

    private CodeArea editor;

    private EditorView editorView;

    private Sidebar sidebar;

    private Button sidebarButton;

    private Label status;

    private Stage stage;

    private boolean settingsVisible;

    private AppSettings settings;

    private boolean fileActionInProgress;

    private MarkdownPreview markdownPreview;

    private SplitPane previewSplit;

    private boolean previewVisible;

    private TerminalWorkspace terminals;

    private CommandPalette palette;

    private RecentFiles recentFiles = new RecentFiles();

    private ChangeListener<Boolean> themeListener;

    private final class FileAction implements AutoCloseable {

        private final AutoSaveController.Suspension suspension = autoSave.suspend();

        private boolean finished;

        void run(Runnable next) {
            try {
                next.run();
            } catch (RuntimeException | Error ex) {
                close();
                throw ex;
            }
        }

        void finishWith(Runnable next) {
            try {
                next.run();
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            if (!finished) {
                finished = true;
                fileActionInProgress = false;
                suspension.close();
            }
        }
    }

    @Override
    public void start(Stage primaryStage) {
        recentFiles = new RecentFiles(RecentFiles.preferencesStore());
        createScene(primaryStage, new AppSettings(AppSettings.preferencesStore()));
        primaryStage.show();
    }

    Scene createScene(Stage primaryStage, AppSettings settings) {
        stage = primaryStage;
        this.settings = settings;
        session = new EditorSession(new EditorFileService());
        autoSave = new AutoSaveController(session, settings);
        root = new BorderPane();
        root.setId("root");
        sidebar = new Sidebar();
        sidebar.setOnOpenFile(this::openFile);
        sidebar.setOnWorkspaceAction(this::workspaceAction);
        // Foreground file operations block navigation; background autosaves must not dim the sidebar.
        sidebar.getTreeView().disableProperty().bind(session.editingBlockedProperty());
        status = new Label(session.statusProperty().get());
        status.setId("status");
        status.setPadding(new Insets(8, 14, 8, 14));
        status.setMaxWidth(Double.MAX_VALUE);
        Tooltip statusTooltip = new Tooltip();
        statusTooltip.textProperty().bind(status.textProperty());
        status.setTooltip(statusTooltip);
        session.statusProperty().addListener((observable, oldValue, message) -> status.setText(message));
        root.setLeft(sidebar);
        editorView = new EditorView(session);
        editor = editorView.area();
        editorView.installContextMenu(this::toggleMarkdownPreview);
        session.languageProperty().addListener((observable, previous, language) -> {
            if (language != EditorLanguage.MARKDOWN && previewVisible) {
                previewVisible = false;
                markdownPreview.setActive(false);
                if (!settingsVisible) {
                    showDocument();
                }
            }
        });
        EditorChrome chrome = new EditorChrome(session, settings, editorView, this::executeCommand);
        sidebarButton = chrome.sidebarButton();
        root.setTop(chrome);
        root.setCenter(editorView);
        Label language = new Label();
        language.setId("document-language");
        language.textProperty().bind(Bindings.createStringBinding(
                () -> session.languageProperty().get().displayName(), session.languageProperty()));
        language.setTooltip(new Tooltip("Language detected from file name"));
        BorderPane statusBar = new BorderPane();
        statusBar.setId("status-bar");
        statusBar.setCenter(status);
        statusBar.setRight(language);
        root.setBottom(statusBar);
        settingsPage = new SettingsPage(settings, autoSave, this::showEditor);
        Scene scene = new Scene(root, 1100, 700);
        stage.setScene(scene);
        stage.getIcons().setAll(AppIcon.windowIcon());
        stage.setMinWidth(640);
        stage.setMinHeight(420);
        stage.titleProperty().bind(Bindings.createStringBinding(() -> {
            Path path = session.pathProperty().get();
            String name = path == null ? "Untitled" : path.getFileName().toString();
            return (session.dirtyProperty().get() ? "* " : "") + name + " — Sylph";
        }, session.pathProperty(), session.dirtyProperty()));
        themeListener = (observable, oldValue, dark) -> applyTheme(scene, dark);
        settings.darkThemeProperty().addListener(themeListener);
        palette = new CommandPalette(root, sidebar::getRootDirectory, recentFiles::files, this::openFile,
                this::executeCommand, this::commandEnabled);
        EditorShortcuts.install(scene, this::executeCommand,
                command -> command != EditorShortcuts.Command.PREVIEW || !TerminalView.ownsFocus(scene));
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!palette.isVisible() && settingsVisible && event.getCode() == KeyCode.ESCAPE) {
                showEditor();
                event.consume();
            }
        });
        stage.setOnCloseRequest(event -> {
            event.consume();
            FileAction action = beginFileAction();
            if (action == null) {
                status.setText("Please wait for the file operation to finish.");
            } else {
                action.run(() -> afterUnsavedCheck(() -> action.finishWith(() -> {
                    closeResources();
                    stage.hide();
                }), action));
            }
        });
        applyTheme(scene, settings.darkThemeProperty().get());
        return scene;
    }

    private void toggleSettings() {
        if (settingsVisible) {
            showEditor();
        } else {
            settingsVisible = true;
            if (markdownPreview != null) {
                markdownPreview.setActive(false);
            }
            root.setLeft(null);
            root.setCenter(settingsPage);
            sidebarButton.setDisable(true);
            settingsPage.requestFocus();
        }
    }

    private void showEditor() {
        settingsVisible = false;
        root.setLeft(sidebar);
        showDocument();
        if (terminals != null) {
            terminals.selectDocument();
        }
        sidebarButton.setDisable(false);
        editor.requestFocus();
    }

    private void toggleMarkdownPreview() {
        if (previewVisible) {
            hideMarkdownPreview();
            return;
        }
        if (session.languageProperty().get() != EditorLanguage.MARKDOWN || session.busyProperty().get()) {
            return;
        }
        if (markdownPreview == null) {
            markdownPreview = new MarkdownPreview(session, settings, this::hideMarkdownPreview);
            previewSplit = new SplitPane();
            previewSplit.setId("markdown-workspace");
        }
        previewVisible = !previewVisible;
        showEditor();
    }

    private void hideMarkdownPreview() {
        previewVisible = false;
        if (!settingsVisible) {
            showDocument();
            editor.requestFocus();
        } else if (markdownPreview != null) {
            markdownPreview.setActive(false);
        }
    }

    private void showDocument() {
        double divider = previewSplit == null || previewSplit.getDividers().isEmpty()
                ? 0.5 : previewSplit.getDividerPositions()[0];
        root.setCenter(null);
        if (terminals != null) {
            terminals.setDocument(null);
        }
        if (previewSplit != null) {
            previewSplit.getItems().clear();
        }
        Node document = editorView;
        if (previewVisible) {
            previewSplit.getItems().setAll(editorView, markdownPreview);
            previewSplit.setDividerPositions(divider);
            document = previewSplit;
        }
        if (terminals == null) {
            root.setCenter(document);
        } else {
            terminals.setDocument(document);
            root.setCenter(terminals);
        }
        if (markdownPreview != null) {
            markdownPreview.setActive(previewVisible);
        }
    }

    private void executeCommand(EditorShortcuts.Command command) {
        if (command != EditorShortcuts.Command.PALETTE && command != EditorShortcuts.Command.QUICK_OPEN) {
            palette.hide();
        }
        switch (command) {
            case PALETTE -> palette.show(true);
            case QUICK_OPEN -> palette.show(false);
            case RELOAD -> reloadWindow();
            case OPEN_FILE -> openFileDialog();
            case OPEN_FOLDER -> openFolderDialog(stage, sidebar, status);
            case SAVE -> saveDocument(false, () -> { });
            case SAVE_AS -> saveDocument(true, () -> { });
            case SIDEBAR -> {
                if (!settingsVisible) {
                    sidebar.toggleCollapsed();
                }
            }
            case TERMINAL_PANEL -> openTerminal(false);
            case TERMINAL_EDITOR -> openTerminal(true);
            case SETTINGS -> toggleSettings();
            case PREVIEW -> toggleMarkdownPreview();
            case AUTOSAVE -> settings.autoSaveProperty().set(!settings.autoSaveProperty().get());
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    private void openTerminal(boolean inEditor) {
        if (settingsVisible) {
            showEditor();
        }
        if (terminals == null) {
            terminals = new TerminalWorkspace(session, this::createTerminal, editor::requestFocus);
            showDocument();
        }
        Path directory = sidebar.getRootDirectory();
        if (directory == null) {
            Path file = session.pathProperty().get();
            directory = file == null ? Path.of(System.getProperty("user.dir")) : file.getParent();
        }
        if (inEditor) {
            terminals.open(directory, true);
        } else {
            terminals.togglePanel(directory);
        }
    }

    TerminalView createTerminal(Path directory) {
        return new TerminalView(directory, settings);
    }

    private boolean commandEnabled(EditorShortcuts.Command command) {
        return switch (command) {
            case OPEN_FILE, OPEN_FOLDER, SAVE, SAVE_AS, RELOAD ->
                    !session.busyProperty().get() && !fileActionInProgress;
            case PREVIEW -> session.languageProperty().get() == EditorLanguage.MARKDOWN
                    && !session.busyProperty().get();
            case SIDEBAR -> !settingsVisible;
            default -> true;
        };
    }

    void workspaceAction(Sidebar.WorkspaceAction command, Path selected) {
        Path workspace = sidebar.getRootDirectory();
        if (workspace == null || selected == null) {
            status.setText("Open a folder before changing workspace files.");
            return;
        }
        Path target = selected.toAbsolutePath().normalize();
        if (!target.startsWith(workspace) || command == Sidebar.WorkspaceAction.DELETE && target.equals(workspace)) {
            status.setText("Choose an entry inside the workspace; the workspace root is protected.");
            return;
        }
        FileAction action = beginFileAction();
        if (action == null) {
            status.setText("Please wait for the file operation to finish.");
            return;
        }
        action.run(() -> {
            if (command == Sidebar.WorkspaceAction.DELETE) {
                Runnable next = () -> deleteWorkspaceEntry(workspace, target, action);
                try {
                    if (containsDocument(target)) {
                        afterUnsavedCheck(next, action);
                    } else {
                        next.run();
                    }
                } catch (IOException error) {
                    action.finishWith(() -> showFileError(error));
                }
            } else {
                boolean folder = command == Sidebar.WorkspaceAction.NEW_FOLDER;
                Path directory = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) ? target : target.getParent();
                Runnable next = () -> createWorkspaceEntry(workspace, directory, folder, action);
                if (folder) {
                    next.run();
                } else {
                    afterUnsavedCheck(next, action);
                }
            }
        });
    }

    private boolean containsDocument(Path target) throws IOException {
        Path current = session.pathProperty().get();
        if (current == null || current.startsWith(target)) {
            return current != null;
        }
        // Resolve parent aliases without following the selected entry if it is itself a link.
        Path document = current.getParent().toRealPath().resolve(current.getFileName());
        Path entry = target.getParent().toRealPath().resolve(target.getFileName());
        return document.startsWith(entry);
    }

    private void createWorkspaceEntry(Path workspace, Path directory, boolean folder, FileAction action) {
        String name = chooseWorkspaceName(folder, directory);
        if (name == null) {
            action.close();
            return;
        }
        WorkspaceFileService files = new WorkspaceFileService();
        session.mutateWorkspace(folder ? "Creating folder…" : "Creating file…",
                () -> files.create(workspace, directory, name, folder), created -> action.run(() -> {
                    refreshWorkspace(directory, created);
                    if (folder) {
                        action.finishWith(() -> status.setText("Created folder  |  " + created.getFileName()));
                    } else {
                        loadFile(created, action);
                    }
                }), error -> action.finishWith(() -> showFileError(error)));
    }

    private void deleteWorkspaceEntry(Path workspace, Path target, FileAction action) {
        if (!confirmWorkspaceDelete(target)) {
            action.close();
            return;
        }
        final boolean deletingDocument;
        try {
            // Save As may have changed the active path. Capture this before deletion removes it.
            deletingDocument = containsDocument(target);
        } catch (IOException error) {
            action.finishWith(() -> showFileError(error));
            return;
        }
        WorkspaceFileService files = new WorkspaceFileService();
        session.mutateWorkspace("Deleting  |  " + target.getFileName(), () -> files.delete(workspace, target),
                deleted -> action.finishWith(() -> {
                    if (deletingDocument) {
                        session.clearDocument();
                        editorView.documentOpened();
                        showEditor();
                    }
                    recentFiles.removeUnder(deleted);
                    sidebar.forgetEntry(deleted);
                    refreshWorkspace(deleted.getParent(), deleted.getParent());
                    status.setText("Deleted  |  " + deleted.getFileName());
                }), error -> action.finishWith(() -> {
                    sidebar.forgetEntry(target);
                    refreshWorkspace(target.getParent(), target.getParent());
                    showFileError(error);
                }));
    }

    private void refreshWorkspace(Path directory, Path selected) {
        sidebar.refreshDirectory(directory, selected,
                error -> status.setText("Unable to refresh workspace  |  " + error.getMessage()));
    }

    String chooseWorkspaceName(boolean folder, Path directory) {
        TextInputDialog prompt = new TextInputDialog(folder ? "new-folder" : "untitled.txt");
        prompt.initOwner(stage);
        prompt.setTitle(folder ? "New Folder" : "New File");
        prompt.setHeaderText("Create in " + directory);
        prompt.setContentText("Name:");
        return prompt.showAndWait().orElse(null);
    }

    boolean confirmWorkspaceDelete(Path target) {
        Alert prompt = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete " + target + "?\nFolders and all their contents will be permanently deleted. "
                        + "This cannot be undone.", ButtonType.YES, ButtonType.CANCEL);
        prompt.initOwner(stage);
        prompt.setTitle("Delete workspace entry");
        prompt.setHeaderText("Permanently delete this entry?");
        return prompt.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.YES;
    }

    private void reloadWindow() {
        FileAction action = beginFileAction();
        if (action == null) {
            status.setText("Please wait for the file operation to finish.");
            return;
        }
        action.run(() -> afterUnsavedCheck(() -> {
            Path file = session.pathProperty().get();
            Path directory = sidebar.getRootDirectory();
            double width = stage.getWidth();
            double height = stage.getHeight();
            action.close();
            closeResources();
            stage.titleProperty().unbind();
            settingsVisible = false;
            previewVisible = false;
            markdownPreview = null;
            previewSplit = null;
            terminals = null;
            createScene(stage, settings);
            stage.setWidth(width);
            stage.setHeight(height);
            if (directory != null) {
                openFolder(directory, sidebar, status);
            }
            if (file != null) {
                openFile(file);
            }
        }, action));
    }

    private void openFileDialog() {
        FileAction action = beginFileAction();
        if (action == null) {
            return;
        }
        action.run(() -> afterUnsavedCheck(() -> {
            File file = chooseOpenFile();
            if (file != null) {
                loadFile(file.toPath(), action);
            } else {
                action.close();
            }
        }, action));
    }

    private void openFile(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        if (normalized.equals(session.pathProperty().get())) {
            recentFiles.record(normalized);
            showEditor();
            return;
        }
        FileAction action = beginFileAction();
        if (action != null) {
            action.run(() -> afterUnsavedCheck(() -> loadFile(normalized, action), action));
        }
    }

    private void loadFile(Path file, FileAction action) {
        session.open(file, () -> action.finishWith(() -> {
            recentFiles.record(session.pathProperty().get());
            editorView.documentOpened();
            showEditor();
        }),
                error -> action.finishWith(() -> showFileError(error)));
    }

    File chooseOpenFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open File");
        return chooser.showOpenDialog(stage);
    }

    private FileAction beginFileAction() {
        if (session.busyProperty().get() || fileActionInProgress) {
            return null;
        }
        FileAction action = new FileAction();
        fileActionInProgress = true;
        return action;
    }

    private void afterUnsavedCheck(Runnable next, FileAction action) {
        if (!session.dirtyProperty().get()) {
            next.run();
            return;
        }
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Discard", ButtonBar.ButtonData.NO);
        Alert prompt = new Alert(Alert.AlertType.CONFIRMATION,
                "Save your changes before continuing?", save, discard, ButtonType.CANCEL);
        prompt.initOwner(stage);
        prompt.setTitle("Unsaved changes");
        prompt.setHeaderText("This file has unsaved changes");
        ButtonType choice = prompt.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == save) {
            saveDocument(false, next, action);
        } else if (choice == discard) {
            next.run();
        } else {
            action.close();
        }
    }

    private void saveDocument(boolean saveAs, Runnable afterSave) {
        FileAction action = beginFileAction();
        if (action == null) {
            return;
        }
        action.run(() -> saveDocument(saveAs, () -> action.finishWith(afterSave), action));
    }

    private void saveDocument(boolean saveAs, Runnable afterSave, FileAction action) {
        Path destination = session.pathProperty().get();
        if (destination == null || saveAs) {
            File file = chooseSaveFile(destination);
            if (file == null) {
                action.close();
                return;
            }
            destination = file.toPath();
        }
        Path target = destination;
        Runnable onSaved = () -> action.run(() -> {
            recentFiles.record(session.pathProperty().get());
            afterSave.run();
        });
        Consumer<Throwable> onError = error -> action.run(() -> {
            if (error instanceof FileAlreadyExistsException) {
                Alert prompt = new Alert(Alert.AlertType.CONFIRMATION,
                        "Replace " + target + "?", ButtonType.YES, ButtonType.NO);
                prompt.initOwner(stage);
                prompt.setTitle("Replace existing file");
                prompt.setHeaderText("A file already exists at this location");
                if (prompt.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
                    session.save(target, false, true, onSaved,
                            failure -> action.finishWith(() -> showFileError(failure)));
                } else {
                    action.close();
                }
            } else {
                action.finishWith(() -> showFileError(error));
            }
        });
        session.save(target, false, false, onSaved, onError);
    }

    File chooseSaveFile(Path destination) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save As");
        chooser.setInitialFileName(destination == null ? "untitled.txt" : destination.getFileName().toString());
        return chooser.showSaveDialog(stage);
    }

    void showFileError(Throwable error) {
        Alert alert = new Alert(Alert.AlertType.ERROR,
                error.getMessage() == null ? "The file operation failed." : error.getMessage(), ButtonType.OK);
        alert.initOwner(stage);
        alert.setTitle("Sylph");
        alert.setHeaderText("Unable to complete the file action");
        alert.showAndWait();
    }

    @Override
    public void stop() {
        closeResources();
    }

    void closeResources() {
        if (terminals != null) {
            terminals.close();
        }
        if (sidebar != null) {
            sidebar.close();
        }
        if (palette != null) {
            palette.close();
        }
        if (settingsPage != null) {
            settingsPage.close();
        }
        if (settings != null && themeListener != null) {
            settings.darkThemeProperty().removeListener(themeListener);
        }
        if (markdownPreview != null) {
            markdownPreview.close();
        }
        if (autoSave != null) {
            autoSave.close();
        }
        if (session != null) {
            session.close();
        }
        if (editorView != null) {
            editorView.close();
        }
    }

    private void openFolderDialog(Stage stage, Sidebar sidebar, Label status) {
        if (session.busyProperty().get() || fileActionInProgress) {
            status.setText("Please wait for the file operation to finish.");
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open Folder");
        try {
            Path current = sidebar.getRootDirectory();
            if (current != null && Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                chooser.setInitialDirectory(current.toFile());
            }
        } catch (IllegalArgumentException | SecurityException ex) {
            // Fall back to the default directory.
        }
        File selected = chooser.showDialog(stage);
        if (selected != null) {
            openFolder(selected.toPath(), sidebar, status);
        }
    }

    void openFolder(Path directory, Sidebar sidebar, Label status) {
        sidebar.setRootDirectoryAsync(directory);
        Path normalized = directory.toAbsolutePath().normalize();
        Path fileName = normalized.getFileName();
        String name;
        if (fileName == null) {
            name = normalized.toString();
        } else {
            name = fileName.toString();
        }
        status.setText("Opened  |  " + name);
        status.setTooltip(new Tooltip(normalized.toString()));
    }

    private void applyTheme(Scene scene, boolean dark) {
        String stylesheet = dark ? "dark.css" : "light.css";
        var resource = getClass().getResource("/styles/" + stylesheet);
        if (resource == null) {
            throw new IllegalStateException("Missing theme stylesheet: " + stylesheet);
        }
        var settingsStyle = getClass().getResource("/styles/settings.css");
        if (settingsStyle == null) {
            throw new IllegalStateException("Missing settings stylesheet");
        }
        var shellStyle = getClass().getResource("/styles/shell.css");
        if (shellStyle == null) {
            throw new IllegalStateException("Missing shell stylesheet");
        }
        var editorStyle = getClass().getResource("/styles/editor.css");
        if (editorStyle == null) {
            throw new IllegalStateException("Missing editor stylesheet");
        }
        scene.getStylesheets().setAll(resource.toExternalForm(), settingsStyle.toExternalForm(),
                shellStyle.toExternalForm(), editorStyle.toExternalForm());
    }
}
