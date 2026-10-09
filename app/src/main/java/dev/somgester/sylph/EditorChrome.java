package dev.somgester.sylph;

import java.nio.file.Path;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

final class EditorChrome extends VBox {

    private final Button sidebarButton;

    EditorChrome(EditorSession session, AppSettings settings, EditorView editorView,
            Consumer<EditorShortcuts.Command> execute) {
        var editor = editorView.area();
        Button openFile = action(EditorShortcuts.Command.OPEN_FILE, AppIcon.Symbol.FILE, "open-file", execute);
        Button openFolder = action(EditorShortcuts.Command.OPEN_FOLDER, AppIcon.Symbol.FOLDER, "open-folder", execute);
        Button save = action(EditorShortcuts.Command.SAVE, AppIcon.Symbol.SAVE, "save-file", execute);
        sidebarButton = action(EditorShortcuts.Command.SIDEBAR, AppIcon.Symbol.SIDEBAR, "toggle-sidebar", execute);
        Button preferences = action(EditorShortcuts.Command.SETTINGS, AppIcon.Symbol.SETTINGS,
                "open-settings", execute);
        openFile.disableProperty().bind(session.busyProperty());
        save.disableProperty().bind(session.busyProperty());

        Menu file = new Menu("File");
        file.getItems().addAll(item(EditorShortcuts.Command.OPEN_FILE, execute, session),
                item(EditorShortcuts.Command.OPEN_FOLDER, execute, session), new SeparatorMenuItem(),
                item(EditorShortcuts.Command.SAVE, execute, session),
                item(EditorShortcuts.Command.SAVE_AS, execute, session), new SeparatorMenuItem());
        CheckMenuItem autoSave = new CheckMenuItem("Autosave");
        autoSave.setAccelerator(EditorShortcuts.Command.AUTOSAVE.shortcut());
        autoSave.selectedProperty().bindBidirectional(settings.autoSaveProperty());
        file.getItems().add(autoSave);
        Menu edit = new Menu("Edit");
        MenuItem undo = editItem("Undo", KeyCode.Z, editor::undo);
        undo.disableProperty().bind(editorView.undoAvailableProperty().not().or(session.editingBlockedProperty()));
        MenuItem redo = editItem("Redo", KeyCode.Z, editor::redo, KeyCombination.SHIFT_DOWN);
        redo.disableProperty().bind(editorView.redoAvailableProperty().not().or(session.editingBlockedProperty()));
        var noSelection = Bindings.createBooleanBinding(() -> editor.getSelectedText().isEmpty(),
                editor.selectedTextProperty());
        MenuItem cut = editItem("Cut", KeyCode.X, editor::cut);
        cut.disableProperty().bind(noSelection.or(session.editingBlockedProperty()));
        MenuItem copy = editItem("Copy", KeyCode.C, editor::copy);
        copy.disableProperty().bind(noSelection);
        MenuItem paste = editItem("Paste", KeyCode.V, editor::paste);
        paste.disableProperty().bind(session.editingBlockedProperty());
        edit.getItems().addAll(undo, redo, new SeparatorMenuItem(), cut, copy, paste,
                editItem("Select All", KeyCode.A, editor::selectAll));
        Menu view = new Menu("View");
        MenuItem sidebar = item(EditorShortcuts.Command.SIDEBAR, execute, session);
        sidebar.disableProperty().bind(sidebarButton.disabledProperty());
        view.getItems().addAll(sidebar, item(EditorShortcuts.Command.SETTINGS, execute, session));
        MenuBar menus = new MenuBar(file, edit, view);
        menus.setUseSystemMenuBar(false);
        menus.setId("main-menu");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(2, openFile, openFolder, save);
        actions.getStyleClass().add("file-actions");
        HBox toolbar = new HBox(6, menus, actions, spacer, sidebarButton, preferences);
        toolbar.setId("toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        StackPane brand = AppIcon.logo(24);
        brand.setId("brand");
        brand.setMouseTransparent(false);
        Tooltip.install(brand, new Tooltip("Sylph"));
        Label document = new Label();
        document.setId("document-title");
        document.textProperty().bind(Bindings.createStringBinding(() -> {
            Path path = session.pathProperty().get();
            return (path == null ? "Untitled" : path.getFileName().toString())
                    + (session.dirtyProperty().get() ? " •" : "");
        }, session.pathProperty(), session.dirtyProperty()));
        HBox header = new HBox(12, brand, document);
        header.setId("workspace-header");
        header.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(toolbar, header);
    }

    Button sidebarButton() {
        return sidebarButton;
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
            EditorSession session) {
        MenuItem item = new MenuItem(command.label());
        item.setAccelerator(command.shortcut());
        item.setOnAction(event -> execute.accept(command));
        if (command == EditorShortcuts.Command.OPEN_FILE || command == EditorShortcuts.Command.SAVE
                || command == EditorShortcuts.Command.SAVE_AS) {
            item.disableProperty().bind(session.busyProperty());
        }
        return item;
    }

    private static MenuItem editItem(String label, KeyCode key, Runnable action,
            KeyCombination.Modifier... modifiers) {
        MenuItem item = new MenuItem(label);
        KeyCombination.Modifier[] allModifiers = new KeyCombination.Modifier[modifiers.length + 1];
        allModifiers[0] = KeyCombination.SHORTCUT_DOWN;
        System.arraycopy(modifiers, 0, allModifiers, 1, modifiers.length);
        item.setAccelerator(new KeyCodeCombination(key, allModifiers));
        item.setOnAction(event -> action.run());
        return item;
    }
}
