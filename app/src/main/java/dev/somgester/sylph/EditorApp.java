package dev.somgester.sylph;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.eclipse.lsp4j.Location;
import org.fxmisc.richtext.CodeArea;

public class EditorApp extends Application {

    private EditorSession session;

    private AutoSaveController autoSave;

    private BorderPane root;

    private SettingsPage settingsPage;

    private AccountPage accountPage;

    private AccountController account;

    private LanguageServerManager languageServers;

    private LanguageServerSettings serverSettings;

    private CodeArea editor;

    private EditorView editorView;

    private Sidebar sidebar;

    private Button sidebarButton;

    private Label status;

    private Stage stage;

    private boolean toolPageVisible;

    private AppSettings settings;

    private List<EditorDocument> documents;

    private EditorDocument document;

    private EditorDocument openingDocument;

    private EditorChrome chrome;

    private Label language;

    private final BooleanProperty foregroundAction = new SimpleBooleanProperty();

    private final ChangeListener<String> statusListener = (observable, previous, message) -> status.setText(message);

    private TerminalWorkspace terminals;

    private CommandPalette palette;

    private RecentFiles recentFiles = new RecentFiles();

    private ChangeListener<Boolean> themeListener;

    private final class FileAction implements AutoCloseable {

        private final List<AutoSaveController.Suspension> suspensions = documents.stream()
                .map(item -> item.autoSave.suspend()).toList();

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
                foregroundAction.set(false);
                chrome.setFileActionInProgress(false);
                suspensions.forEach(AutoSaveController.Suspension::close);
            }
        }
    }

    @Override
    public void start(Stage primaryStage) {
        recentFiles = new RecentFiles(RecentFiles.preferencesStore());
        createScene(primaryStage, new AppSettings(AppSettings.preferencesStore()),
                new LanguageServerSettings(LanguageServerSettings.preferencesStore()));
        primaryStage.show();
    }

    Scene createScene(Stage primaryStage, AppSettings settings) {
        return createScene(primaryStage, settings, new LanguageServerSettings(LanguageServerSettings.memoryStore()));
    }

    Scene createScene(Stage primaryStage, AppSettings settings, LanguageServerSettings servers) {
        stage = primaryStage;
        this.settings = settings;
        serverSettings = servers;
        languageServers = new LanguageServerManager(servers);
        documents = new ArrayList<>();
        document = createDocument();
        documents.add(document);
        session = document.session;
        autoSave = document.autoSave;
        root = new BorderPane();
        root.setId("root");
        sidebar = new Sidebar();
        sidebar.setOnOpenFile(this::openFile);
        sidebar.setOnWorkspaceAction(this::workspaceAction);
        // Foreground file operations block navigation; background autosaves must not dim the sidebar.
        sidebar.getTreeView().disableProperty().bind(session.editingBlockedProperty().or(foregroundAction));
        status = new Label(session.statusProperty().get());
        status.setId("status");
        status.setPadding(new Insets(8, 14, 8, 14));
        status.setMaxWidth(Double.MAX_VALUE);
        Tooltip statusTooltip = new Tooltip();
        statusTooltip.textProperty().bind(status.textProperty());
        status.setTooltip(statusTooltip);
        session.statusProperty().addListener(statusListener);
        root.setLeft(sidebar);
        editorView = document.view;
        editor = editorView.area();
        chrome = new EditorChrome(session, settings, editorView, this::executeCommand);
        chrome.kiroButton().setOnAction(event -> openKiroTerminal());
        chrome.kiroSessionsButton().setOnAction(event -> palette.showKiroSessions());
        sidebarButton = chrome.sidebarButton();
        root.setTop(chrome);
        terminals = new TerminalWorkspace(this::createTerminal, () -> editor.requestFocus(),
                tab -> documents.stream().filter(item -> item.tab == tab).findFirst().ifPresent(this::activateDocument),
                () -> !foregroundAction.get() && documents.stream()
                        .noneMatch(item -> item.session.editingBlockedProperty().get()));
        terminals.addDocument(document.tab);
        root.setCenter(terminals);
        language = new Label();
        language.setId("document-language");
        language.textProperty().bind(Bindings.createStringBinding(
                () -> session.languageProperty().get().displayName(), session.languageProperty()));
        language.setTooltip(new Tooltip("Language detected from file name"));
        BorderPane statusBar = new BorderPane();
        statusBar.setId("status-bar");
        statusBar.setCenter(status);
        statusBar.setRight(language);
        root.setBottom(statusBar);
        settingsPage = new SettingsPage(settings, autoSave, this::showEditor, servers, languageServers);
        if (account == null) {
            account = AccountController.environment(uri -> getHostServices().showDocument(uri.toString()));
        }
        accountPage = new AccountPage(account, this::showEditor);
        chrome.accountMenu().connect(account);
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
                this::executeCommand, this::commandEnabled, this::loadKiroSessions, this::resumeKiroSession);
        EditorShortcuts.install(scene, this::executeCommand,
                command -> command != EditorShortcuts.Command.PREVIEW || !TerminalView.ownsFocus(scene));
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!palette.isVisible() && toolPageVisible && event.getCode() == KeyCode.ESCAPE) {
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
                action.run(() -> afterUnsavedChecks(List.copyOf(documents), 0, () -> action.finishWith(() -> {
                    closeResources();
                    stage.hide();
                }), action));
            }
        });
        applyTheme(scene, settings.darkThemeProperty().get());
        return scene;
    }

    private void toggleSettings() {
        if (toolPageVisible && root.getCenter() == settingsPage) {
            showEditor();
        } else {
            toolPageVisible = true;
            document.setActive(false);
            root.setLeft(null);
            root.setCenter(settingsPage);
            sidebarButton.setDisable(true);
            settingsPage.requestFocus();
        }
    }

    private void showAccount() {
        toolPageVisible = true;
        document.setActive(false);
        root.setLeft(null);
        root.setCenter(accountPage);
        sidebarButton.setDisable(true);
        accountPage.requestFocus();
    }

    private void showEditor() {
        toolPageVisible = false;
        root.setLeft(sidebar);
        showDocument();
        if (terminals != null) {
            terminals.selectDocument();
        }
        sidebarButton.setDisable(false);
        editor.requestFocus();
    }

    private void toggleMarkdownPreview() {
        showEditor();
        document.togglePreview();
    }

    private void showDocument() {
        root.setCenter(terminals);
        document.setActive(!toolPageVisible);
    }

    private EditorDocument createDocument() {
        EditorDocument item = new EditorDocument(settings);
        languageServers.watch(item);
        item.view.installContextMenu(this::toggleMarkdownPreview);
        item.view.setOnDefinition(this::openDefinition);
        item.tab.setOnCloseRequest(event -> {
            event.consume();
            closeDocument(item);
        });
        return item;
    }

    private void activateDocument(EditorDocument next) {
        if (next == document) {
            return;
        }
        document.setActive(false);
        session.statusProperty().removeListener(statusListener);
        document = next;
        session = next.session;
        autoSave = next.autoSave;
        editorView = next.view;
        editor = editorView.area();
        chrome.setDocument(session, editorView);
        settingsPage.setAutoSave(autoSave);
        sidebar.getTreeView().disableProperty().unbind();
        sidebar.getTreeView().disableProperty().bind(session.editingBlockedProperty().or(foregroundAction));
        status.setText(session.statusProperty().get());
        session.statusProperty().addListener(statusListener);
        language.textProperty().unbind();
        language.textProperty().bind(session.languageProperty().map(EditorLanguage::displayName));
        stage.titleProperty().unbind();
        EditorSession selected = session;
        stage.titleProperty().bind(Bindings.createStringBinding(() -> {
            Path path = selected.pathProperty().get();
            return (selected.dirtyProperty().get() ? "* " : "")
                    + (path == null ? "Untitled" : path.getFileName().toString()) + " — Sylph";
        }, selected.pathProperty(), selected.dirtyProperty()));
        next.setActive(!toolPageVisible);
    }

    private void closeDocument(EditorDocument item) {
        if (!documents.contains(item)) {
            return;
        }
        FileAction action = beginFileAction();
        if (action != null) {
            action.run(() -> {
                terminals.selectDocument(item.tab);
                afterUnsavedCheck(() -> action.finishWith(() -> removeDocuments(List.of(item))), action);
            });
        } else {
            status.setText("Please wait for the file operation to finish.");
        }
    }

    private void removeDocuments(List<EditorDocument> removed) {
        for (EditorDocument item : removed) {
            documents.remove(item);
            terminals.removeDocument(item.tab);
            item.close();
        }
        if (documents.isEmpty()) {
            EditorDocument blank = createDocument();
            documents.add(blank);
            terminals.addDocument(blank.tab);
        } else if (removed.contains(document)) {
            terminals.selectDocument(documents.getLast().tab);
        }
        showEditor();
    }

    private void afterUnsavedChecks(List<EditorDocument> items, int index, Runnable next, FileAction action) {
        if (index == items.size()) {
            next.run();
        } else {
            EditorDocument item = items.get(index);
            Runnable remaining = () -> afterUnsavedChecks(items, index + 1, next, action);
            if (item.session.dirtyProperty().get()) {
                terminals.selectDocument(item.tab);
                afterUnsavedCheck(remaining, action);
            } else {
                remaining.run();
            }
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
                if (!toolPageVisible) {
                    sidebar.toggleCollapsed();
                }
            }
            case TERMINAL_PANEL -> openTerminal(false);
            case TERMINAL_EDITOR -> openTerminal(true);
            case SETTINGS -> toggleSettings();
            case ACCOUNT -> showAccount();
            case PREVIEW -> toggleMarkdownPreview();
            case AUTOSAVE -> settings.autoSaveProperty().set(!settings.autoSaveProperty().get());
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    private void openTerminal(boolean inEditor) {
        if (toolPageVisible) {
            showEditor();
        }
        Path directory = terminalDirectory();
        if (inEditor) {
            terminals.open(directory, true);
        } else {
            terminals.togglePanel(directory);
        }
    }

    private void openKiroTerminal() {
        if (toolPageVisible) {
            showEditor();
        }
        TerminalView terminal = terminals.open(terminalDirectory(), true);
        if (terminal != null) {
            terminal.session().send("kiro-cli\r");
        }
    }

    KiroSessions.Listing loadKiroSessions() throws IOException, InterruptedException {
        return new KiroSessions().list();
    }

    private void resumeKiroSession(KiroSessions.Session saved) {
        if (toolPageVisible) {
            showEditor();
        }
        TerminalView terminal = terminals.open(saved.directory(), true);
        if (terminal != null) {
            terminal.session().send(saved.resumeCommand());
        }
    }

    private Path terminalDirectory() {
        Path directory = sidebar.getRootDirectory();
        if (directory == null) {
            Path file = session.pathProperty().get();
            directory = file == null ? Path.of(System.getProperty("user.dir")) : file.getParent();
        }
        return directory;
    }

    CodeArea activeEditor() {
        return editor;
    }

    TerminalView createTerminal(Path directory) {
        return new TerminalView(directory, settings);
    }

    private boolean commandEnabled(EditorShortcuts.Command command) {
        return switch (command) {
            case OPEN_FILE, OPEN_FOLDER, SAVE, SAVE_AS, RELOAD ->
                    !foregroundAction.get() && documents.stream().noneMatch(item -> item.session.busyProperty().get());
            case PREVIEW -> session.languageProperty().get() == EditorLanguage.MARKDOWN
                    && !session.busyProperty().get();
            case SIDEBAR -> !toolPageVisible;
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
                    afterUnsavedChecks(documentsUnder(target), 0, next, action);
                } catch (IOException error) {
                    action.finishWith(() -> showFileError(error));
                }
            } else {
                boolean folder = command == Sidebar.WorkspaceAction.NEW_FOLDER;
                Path directory = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) ? target : target.getParent();
                createWorkspaceEntry(workspace, directory, folder, action);
            }
        });
    }

    private List<EditorDocument> documentsUnder(Path target) throws IOException {
        List<EditorDocument> affected = new ArrayList<>();
        for (EditorDocument item : documents) {
            if (containsDocument(target, item.session.pathProperty().get())) {
                affected.add(item);
            }
        }
        return affected;
    }

    private boolean containsDocument(Path target, Path current) throws IOException {
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
        final List<EditorDocument> deletingDocuments;
        try {
            // Save As may have changed the active path. Capture this before deletion removes it.
            deletingDocuments = documentsUnder(target);
        } catch (IOException error) {
            action.finishWith(() -> showFileError(error));
            return;
        }
        WorkspaceFileService files = new WorkspaceFileService();
        session.mutateWorkspace("Deleting  |  " + target.getFileName(), () -> files.delete(workspace, target),
                deleted -> action.finishWith(() -> {
                    if (!deletingDocuments.isEmpty()) {
                        removeDocuments(deletingDocuments);
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
        EditorDocument selected = document;
        action.run(() -> afterUnsavedChecks(List.copyOf(documents), 0, () -> {
            Path file = selected.session.pathProperty().get();
            List<Path> files = documents.stream().map(item -> item.session.pathProperty().get())
                    .filter(Objects::nonNull).toList();
            Path directory = sidebar.getRootDirectory();
            double width = stage.getWidth();
            double height = stage.getHeight();
            action.close();
            closeResources(false);
            stage.titleProperty().unbind();
            toolPageVisible = false;
            terminals = null;
            createScene(stage, settings, serverSettings);
            stage.setWidth(width);
            stage.setHeight(height);
            if (directory != null) {
                openFolder(directory, sidebar, status);
            }
            restoreFiles(files, 0, file);
        }, action));
    }

    private void restoreFiles(List<Path> files, int index, Path active) {
        if (index == files.size()) {
            EditorDocument restored = active == null ? null : findDocument(active);
            if (restored != null) {
                terminals.selectDocument(restored.tab);
            }
            return;
        }
        FileAction action = beginFileAction();
        if (action != null) {
            action.run(() -> loadFile(files.get(index), action, () -> restoreFiles(files, index + 1, active)));
        }
    }

    private void openFileDialog() {
        FileAction action = beginFileAction();
        if (action == null) {
            return;
        }
        action.run(() -> {
            File file = chooseOpenFile();
            if (file != null) {
                loadFile(file.toPath(), action);
            } else {
                action.close();
            }
        });
    }

    private EditorDocument findDocument(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        return documents.stream().filter(item -> {
            Path path = item.session.pathProperty().get();
            if (path == null) {
                return false;
            }
            try {
                return normalized.equals(path) || Files.isSameFile(normalized, path);
            } catch (IOException error) {
                return normalized.equals(path);
            }
        }).findFirst().orElse(null);
    }

    void openDefinition(Location location) {
        final Path target;
        try {
            target = Path.of(java.net.URI.create(location.getUri()));
        } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException error) {
            status.setText("Definition location is not a local file.");
            return;
        }
        FileAction action = beginFileAction();
        if (action != null) {
            action.run(() -> loadFile(target, action, () -> {
                EditorDocument opened = findDocument(target);
                if (opened != null && opened == document) {
                    var coordinates = new LspText(opened.view.area().getText());
                    if (coordinates.valid(location.getRange())) {
                        opened.view.area().selectRange(coordinates.offset(location.getRange().getStart()),
                                coordinates.offset(location.getRange().getEnd()));
                        opened.view.area().requestFollowCaret();
                    } else {
                        status.setText("Definition range is outside the target document.");
                    }
                }
            }));
        }
    }

    private void openFile(Path file) {
        FileAction action = beginFileAction();
        if (action != null) {
            action.run(() -> loadFile(file, action));
        }
    }

    private void loadFile(Path file, FileAction action) {
        loadFile(file, action, () -> { });
    }

    private void loadFile(Path file, FileAction action, Runnable afterOpened) {
        EditorDocument existing = findDocument(file);
        if (existing != null) {
            action.finishWith(() -> {
                terminals.selectDocument(existing.tab);
                recentFiles.record(existing.session.pathProperty().get());
                showEditor();
            });
            afterOpened.run();
            return;
        }
        boolean reuseBlank = documents.size() == 1 && session.pathProperty().get() == null
                && !session.dirtyProperty().get();
        EditorDocument opened = reuseBlank ? document : createDocument();
        openingDocument = opened;
        opened.session.open(file, () -> {
            openingDocument = null;
            action.finishWith(() -> {
                if (!reuseBlank) {
                    documents.add(opened);
                    terminals.addDocument(opened.tab);
                }
                recentFiles.record(opened.session.pathProperty().get());
                opened.view.documentOpened();
                showEditor();
            });
            afterOpened.run();
        }, error -> {
            action.finishWith(() -> {
                openingDocument = null;
                if (!reuseBlank) {
                    opened.close();
                }
                showFileError(error);
            });
            afterOpened.run();
        });
    }

    File chooseOpenFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open File");
        return chooser.showOpenDialog(stage);
    }

    private FileAction beginFileAction() {
        if (foregroundAction.get() || documents.stream().anyMatch(item -> item.session.busyProperty().get())) {
            return null;
        }
        FileAction action = new FileAction();
        foregroundAction.set(true);
        chrome.setFileActionInProgress(true);
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
        Path path = session.pathProperty().get();
        prompt.setHeaderText("Unsaved changes: " + (path == null ? "Untitled" : path.getFileName()));
        if (path != null) {
            prompt.setContentText("Save your changes before continuing?\n" + path);
        }
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
        EditorDocument other = findDocument(destination);
        if (other != null && other != document) {
            action.finishWith(() -> showFileError(new IOException("This file is already open in another tab.")));
            return;
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
        closeResources(true);
    }

    private void closeResources(boolean closeAccount) {
        if (languageServers != null) {
            languageServers.close();
        }
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
        if (accountPage != null) {
            accountPage.close();
        }
        if (chrome != null) {
            chrome.accountMenu().close();
        }
        if (closeAccount && account != null) {
            account.close();
            account = null;
        }
        if (settings != null && themeListener != null) {
            settings.darkThemeProperty().removeListener(themeListener);
        }
        if (openingDocument != null && !documents.contains(openingDocument)) {
            openingDocument.close();
            openingDocument = null;
        }
        if (documents != null) {
            documents.forEach(EditorDocument::close);
        }
    }

    private void openFolderDialog(Stage stage, Sidebar sidebar, Label status) {
        if (session.busyProperty().get() || foregroundAction.get()) {
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
        if (languageServers != null) {
            languageServers.setWorkspace(directory);
        }
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
