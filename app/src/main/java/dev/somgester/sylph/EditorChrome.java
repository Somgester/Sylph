package dev.somgester.sylph;

import java.nio.file.Path;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

final class EditorChrome extends VBox {

    private final Button sidebarButton;

    private final Button kiroButton = new Button("Kiro");

    private final Button kiroSessionsButton = new Button("Sessions");

    private final ObjectProperty<EditorSession> active = new SimpleObjectProperty<>();

    private final BooleanProperty fileAction = new SimpleBooleanProperty();

    private final EditorEditMenu edit;

    private final AccountMenu accountMenu;

    EditorChrome(EditorSession session, AppSettings settings, EditorView editorView,
            Consumer<EditorShortcuts.Command> execute) {
        active.set(session);
        var sessionBusy = active.flatMap(EditorSession::busyProperty);
        var busy = Bindings.createBooleanBinding(() -> fileAction.get() || sessionBusy.getValue(),
                fileAction, sessionBusy);
        var language = active.flatMap(EditorSession::languageProperty);
        Button openFile = action(EditorShortcuts.Command.OPEN_FILE, AppIcon.Symbol.FILE, "open-file", execute);
        Button openFolder = action(EditorShortcuts.Command.OPEN_FOLDER, AppIcon.Symbol.FOLDER, "open-folder", execute);
        Button save = action(EditorShortcuts.Command.SAVE, AppIcon.Symbol.SAVE, "save-file", execute);
        sidebarButton = action(EditorShortcuts.Command.SIDEBAR, AppIcon.Symbol.SIDEBAR, "toggle-sidebar", execute);
        Button preferences = action(EditorShortcuts.Command.SETTINGS, AppIcon.Symbol.SETTINGS,
                "open-settings", execute);
        openFile.disableProperty().bind(busy);
        save.disableProperty().bind(busy);

        Menu file = new Menu("File");
        file.getItems().addAll(item(EditorShortcuts.Command.OPEN_FILE, execute, busy),
                item(EditorShortcuts.Command.OPEN_FOLDER, execute, busy), new SeparatorMenuItem(),
                item(EditorShortcuts.Command.SAVE, execute, busy),
                item(EditorShortcuts.Command.SAVE_AS, execute, busy), new SeparatorMenuItem());
        CheckMenuItem autoSave = new CheckMenuItem("Autosave");
        autoSave.setAccelerator(EditorShortcuts.Command.AUTOSAVE.shortcut());
        autoSave.selectedProperty().bindBidirectional(settings.autoSaveProperty());
        file.getItems().add(autoSave);
        edit = new EditorEditMenu(editorView, session);
        sceneProperty().addListener((observable, previous, current) -> edit.setScene(current));
        Menu view = new Menu("View");
        MenuItem sidebar = item(EditorShortcuts.Command.SIDEBAR, execute, busy);
        sidebar.disableProperty().bind(sidebarButton.disabledProperty());
        MenuItem preview = item(EditorShortcuts.Command.PREVIEW, execute, busy);
        preview.disableProperty().bind(Bindings.createBooleanBinding(
                () -> language.getValue() != EditorLanguage.MARKDOWN || busy.get(), language, busy));
        view.getItems().addAll(sidebar, preview, item(EditorShortcuts.Command.SETTINGS, execute, busy),
                item(EditorShortcuts.Command.ACCOUNT, execute, busy),
                new SeparatorMenuItem(), item(EditorShortcuts.Command.PALETTE, execute, busy),
                item(EditorShortcuts.Command.QUICK_OPEN, execute, busy),
                item(EditorShortcuts.Command.RELOAD, execute, busy), new SeparatorMenuItem(),
                item(EditorShortcuts.Command.TERMINAL_PANEL, execute, busy),
                item(EditorShortcuts.Command.TERMINAL_EDITOR, execute, busy));
        MenuBar menus = new MenuBar(file, edit, view);
        menus.setUseSystemMenuBar(false);
        menus.setId("main-menu");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(2, openFile, openFolder, save);
        actions.getStyleClass().add("file-actions");
        HBox left = new HBox(6, menus, actions);
        left.setAlignment(Pos.CENTER_LEFT);
        kiroButton.setId("open-kiro");
        kiroButton.setAccessibleText("Open Kiro CLI");
        kiroButton.setTooltip(new Tooltip("Open a new terminal and run kiro-cli"));
        kiroButton.getStyleClass().add("chrome-action");
        kiroSessionsButton.setId("list-kiro-sessions");
        kiroSessionsButton.setAccessibleText("List Kiro sessions");
        kiroSessionsButton.setTooltip(new Tooltip("Search saved Kiro sessions across all workspaces"));
        kiroSessionsButton.getStyleClass().add("chrome-action");
        accountMenu = new AccountMenu(() -> execute.accept(EditorShortcuts.Command.ACCOUNT));
        HBox right = new HBox(6, kiroButton, kiroSessionsButton, accountMenu, sidebarButton, preferences);
        right.setAlignment(Pos.CENTER_RIGHT);
        HBox row = new HBox(left, spacer, right);
        row.setAlignment(Pos.CENTER_LEFT);
        Button palette = new Button("Search files or commands…");
        palette.setId("open-palette");
        palette.setMinWidth(0);
        palette.setAccessibleText("Search files or commands");
        palette.setTooltip(new Tooltip(EditorShortcuts.Command.QUICK_OPEN.help() + "\n"
                + EditorShortcuts.Command.PALETTE.help()));
        palette.setOnAction(event -> execute.accept(EditorShortcuts.Command.QUICK_OPEN));
        StackPane toolbar = new StackPane(row, palette);
        toolbar.setId("toolbar");
        palette.prefWidthProperty().bind(Bindings.createDoubleBinding(() ->
                Math.max(0, Math.min(360, toolbar.getWidth()
                        - 2 * Math.max(left.prefWidth(-1), right.prefWidth(-1)) - 44)),
                toolbar.widthProperty(), left.layoutBoundsProperty(), right.layoutBoundsProperty()));
        palette.setMaxWidth(Region.USE_PREF_SIZE);

        StackPane brand = AppIcon.logo(24);
        brand.setId("brand");
        brand.setMouseTransparent(false);
        Tooltip.install(brand, new Tooltip("Sylph"));
        Label document = new Label();
        document.setId("document-title");
        var path = active.flatMap(EditorSession::pathProperty);
        var dirty = active.flatMap(EditorSession::dirtyProperty);
        document.textProperty().bind(Bindings.createStringBinding(() -> {
            Path currentPath = path.getValue();
            return (currentPath == null ? "Untitled" : currentPath.getFileName().toString())
                    + (dirty.getValue() ? " •" : "");
        }, path, dirty));
        HBox header = new HBox(12, brand, document);
        header.setId("workspace-header");
        header.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(toolbar, header);
    }

    void setDocument(EditorSession session, EditorView view) {
        active.set(session);
        edit.setDocument(view, session);
    }

    void setFileActionInProgress(boolean value) {
        fileAction.set(value);
    }

    Button sidebarButton() {
        return sidebarButton;
    }

    Button kiroSessionsButton() {
        return kiroSessionsButton;
    }

    Button kiroButton() {
        return kiroButton;
    }

    AccountMenu accountMenu() {
        return accountMenu;
    }

    private static Button action(EditorShortcuts.Command command, AppIcon.Symbol symbol, String id,
            Consumer<EditorShortcuts.Command> execute) {
        Button button = new Button();
        button.setId(id);
        button.setGraphic(AppIcon.symbol(symbol));
        button.setAccessibleText(command.label());
        button.getStyleClass().add("chrome-action");
        button.setTooltip(new Tooltip(command.help()));
        button.setOnAction(event -> execute.accept(command));
        return button;
    }

    private static MenuItem item(EditorShortcuts.Command command, Consumer<EditorShortcuts.Command> execute,
            ObservableValue<Boolean> busy) {
        MenuItem item = new MenuItem(command.label());
        item.setAccelerator(command.shortcut());
        item.setOnAction(event -> execute.accept(command));
        if (command == EditorShortcuts.Command.OPEN_FILE || command == EditorShortcuts.Command.SAVE
                || command == EditorShortcuts.Command.SAVE_AS || command == EditorShortcuts.Command.RELOAD) {
            item.disableProperty().bind(busy);
        }
        return item;
    }
}
