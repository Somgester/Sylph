package dev.somgester.sylph;

import java.io.File;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.function.Consumer;
import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
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
        sidebar.getTreeView().disableProperty().bind(session.busyProperty());
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
        EditorChrome chrome = new EditorChrome(session, settings, editorView, this::executeCommand);
        sidebarButton = chrome.sidebarButton();
        root.setTop(chrome);
        root.setCenter(editorView);
        root.setBottom(status);
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
        settings.darkThemeProperty().addListener((observable, oldValue, dark) -> applyTheme(scene, dark));
        EditorShortcuts.install(scene, this::executeCommand);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (settingsVisible && event.getCode() == KeyCode.ESCAPE) {
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
            root.setLeft(null);
            root.setCenter(settingsPage);
            sidebarButton.setDisable(true);
            settingsPage.requestFocus();
        }
    }

    private void showEditor() {
        settingsVisible = false;
        root.setLeft(sidebar);
        root.setCenter(editorView);
        sidebarButton.setDisable(false);
        editor.requestFocus();
    }

    private void executeCommand(EditorShortcuts.Command command) {
        switch (command) {
            case OPEN_FILE -> openFileDialog();
            case OPEN_FOLDER -> openFolderDialog(stage, sidebar, status);
            case SAVE -> saveDocument(false, () -> { });
            case SAVE_AS -> saveDocument(true, () -> { });
            case SIDEBAR -> {
                if (!settingsVisible) {
                    sidebar.toggleCollapsed();
                }
            }
            case SETTINGS -> toggleSettings();
            case AUTOSAVE -> settings.autoSaveProperty().set(!settings.autoSaveProperty().get());
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        }
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
            editor.requestFocus();
            return;
        }
        FileAction action = beginFileAction();
        if (action != null) {
            action.run(() -> afterUnsavedCheck(() -> loadFile(normalized, action), action));
        }
    }

    private void loadFile(Path file, FileAction action) {
        session.open(file, () -> action.finishWith(() -> {
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
        Consumer<Throwable> onError = error -> action.run(() -> {
            if (error instanceof FileAlreadyExistsException) {
                Alert prompt = new Alert(Alert.AlertType.CONFIRMATION,
                        "Replace " + target + "?", ButtonType.YES, ButtonType.NO);
                prompt.initOwner(stage);
                prompt.setTitle("Replace existing file");
                prompt.setHeaderText("A file already exists at this location");
                if (prompt.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
                    session.save(target, false, true, () -> action.run(afterSave),
                            failure -> action.finishWith(() -> showFileError(failure)));
                } else {
                    action.close();
                }
            } else {
                action.finishWith(() -> showFileError(error));
            }
        });
        session.save(target, false, false, () -> action.run(afterSave), onError);
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
